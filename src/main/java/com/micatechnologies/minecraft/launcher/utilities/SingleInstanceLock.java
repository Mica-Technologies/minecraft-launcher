/*
 * Copyright (c) 2021 Mica Technologies
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.EnumSet;

/**
 * Provides single-instance enforcement for the launcher application using a localhost
 * {@link ServerSocket} on a fixed port. When the launcher starts, it attempts to bind the
 * port on the loopback address. If the port is already bound (another instance is running),
 * acquisition fails and the caller should either notify the user and exit OR — for URI
 * forwarding — connect to the existing instance and hand off the URI before exiting.
 *
 * <p>Using a {@link ServerSocket} instead of a file lock ensures the lock is automatically
 * released by the OS when the process terminates, regardless of how it exits (crash, kill,
 * normal shutdown). This avoids stale lock files.</p>
 *
 * <p><b>IPC protocol.</b> The lock socket doubles as an IPC channel. A second instance
 * connects to the same port and sends a single UTF-8 text line terminated by '\n'. The
 * server reads the line and, if it parses as a {@code mmcl://} URI, dispatches it through
 * {@link LauncherUriHandler#handle(String)} and brings the launcher window to focus. Any
 * non-URI payload is logged and ignored. Only loopback connections are accepted because
 * the socket is bound to {@link InetAddress#getLoopbackAddress()}.</p>
 *
 * @author Mica Technologies
 * @version 2.0
 * @since 2.0
 */
public class SingleInstanceLock
{
    /**
     * The server socket used to hold the single-instance lock. Kept open for the lifetime of the process.
     */
    private static ServerSocket lockSocket = null;

    /** Accept-loop thread that handles incoming IPC connections. Daemon so it doesn't keep
     *  the JVM alive past shutdown; the loop exits naturally when {@link #release()} closes
     *  the socket and {@code accept()} throws. */
    private static Thread acceptThread = null;

    /** Filename of the per-launch IPC shared-secret token. Lives in the launcher config dir,
     *  written mode-0600 (POSIX) or owner-only ACL (Windows) so only the launcher process
     *  owner can read it. Second-instance forwarders read the token from this file and send
     *  it as the first protocol line — the server refuses any connection that doesn't open
     *  with the right token, which closes the "any local process on loopback can inject
     *  mmcl:// URIs" hole that bare loopback IPC would otherwise leave open. */
    private static final String IPC_TOKEN_FILENAME = "single-instance.token";

    /** Hex-encoded random 256-bit token sent as the first protocol line. Initialized in
     *  {@link #tryAcquire()} (server side) and re-read by {@link #forwardToRunningInstance(String)}
     *  (client side). Volatile because the server reads it on the IPC accept thread while
     *  release() may clear it from any thread. */
    private static volatile String ipcToken = null;

    /**
     * Attempts to acquire the single-instance lock by binding a {@link ServerSocket} on localhost. The port used depends
     * on whether the launcher is in development mode, allowing dev and release builds to run simultaneously.
     *
     * @return {@code true} if the lock was acquired (this is the only running instance), {@code false} if another
     *         instance already holds the lock
     *
     * @since 1.0
     */
    public static boolean tryAcquire()
    {
        // Idempotent: if this process already holds the lock, report success without
        // rebinding. The launcher's restart loop calls tryAcquire() at the top of every
        // lifecycle iteration; on the first iteration the lock is already held from the
        // pre-loop acquire, and re-binding the same port would fail spuriously. A held
        // lock means the socket is open and the IPC accept loop is running, so there's
        // nothing to redo.
        if ( lockSocket != null && !lockSocket.isClosed() ) {
            return true;
        }
        int port = ipcPort();
        try {
            // Backlog 8 — enough to absorb a small burst of website-link clicks. Bound to
            // the loopback address so we're only reachable from this machine. Even on
            // loopback, any local process (including those running as other UIDs) can
            // connect, so authentication is mandatory — see the token check in
            // handleIncoming() and the per-launch token written by writeIpcToken().
            lockSocket = new ServerSocket( port, 8, InetAddress.getLoopbackAddress() );
            ipcToken = writeIpcToken();
            startAcceptLoop();
            // Confirm in the log so a user troubleshooting "deep links don't reach my
            // running launcher" can verify the IPC server actually came up. Without
            // this line, the only signal was a passive absence of "already running"
            // popups across instances.
            Logger.logDebug( LocalizationManager.format( "log.singleInstance.lockAcquired", port ) );
            return true;
        }
        catch ( IOException e ) {
            // Port is already bound -- another instance is running
            return false;
        }
    }

    /**
     * Generates a fresh random 256-bit token and persists it to the launcher config
     * directory with owner-only file permissions. Same-UID processes can read it (and
     * therefore use {@link #forwardToRunningInstance(String)}); cross-UID processes
     * on the same machine cannot, which is the property the IPC needs.
     *
     * <p>Returns the in-memory token. If persistence fails (read-only FS, no config
     * dir yet), an ephemeral token is still returned — that disables cross-process
     * forwarding for this session, but at least preserves the single-instance lock.
     */
    private static String writeIpcToken()
    {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes( bytes );
        StringBuilder hex = new StringBuilder( bytes.length * 2 );
        for ( byte b : bytes ) {
            hex.append( String.format( "%02x", b ) );
        }
        String token = hex.toString();
        try {
            Path tokenPath = ipcTokenPath();
            Files.createDirectories( tokenPath.getParent() );
            Files.writeString( tokenPath, token, StandardCharsets.UTF_8 );
            applyOwnerOnlyPermissions( tokenPath );
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.singleInstance.tokenPersistFailed",
                                                                 e.getClass().getSimpleName() ) );
        }
        return token;
    }

    /** Path to the per-launch IPC token file. Dev and release builds use distinct ports
     *  (see {@link #ipcPort}) so colocating their token files is safe.
     *
     *  <p>Deliberately uses the game-mode-independent client config path. This runs from
     *  {@code LauncherCore.main()} — before {@code parseLauncherArgs} sets the game mode —
     *  so the general {@link LocalPathManager#getLauncherConfigFolderPath()} would resolve
     *  to the server-mode (current-working-directory) path. The running launcher and the
     *  short-lived forwarding process spawned by the OS scheme handler have different
     *  working directories, so a CWD-relative token would never match between them and
     *  forwarding to an already-open launcher would silently fail. Anchoring to the fixed
     *  {@code ~/.MicaMinecraftLauncher[DEV]/config} path makes both processes agree. */
    private static Path ipcTokenPath()
    {
        return Path.of( LocalPathManager.getClientConfigFolderPath(), IPC_TOKEN_FILENAME );
    }

    /** Delegates to the shared {@link FilePermissions} helper. */
    private static void applyOwnerOnlyPermissions( Path path )
    {
        FilePermissions.applyOwnerOnly( path );
    }

    /** Reads the IPC token from disk (client side). Used by
     *  {@link #forwardToRunningInstance(String)} to authenticate against a running
     *  instance owned by the same OS user. Returns {@code null} if the file is missing or
     *  unreadable — same-UID-only readability is exactly what we depend on. */
    private static String readIpcTokenFromDisk()
    {
        try {
            Path tokenPath = ipcTokenPath();
            if ( !Files.isRegularFile( tokenPath ) ) {
                return null;
            }
            return Files.readString( tokenPath, StandardCharsets.UTF_8 ).trim();
        }
        catch ( IOException e ) {
            return null;
        }
    }

    /**
     * Forwards a single text payload to the running launcher instance. Used by a fresh
     * second-instance startup to hand its {@code mmcl://...} URI to the running launcher
     * before exiting. Connects, writes the payload + newline, closes — the receiving server
     * dispatches the URI on its own thread.
     *
     * @param payload the text to send (typically a {@code mmcl://...} URI); newlines in the
     *                payload itself would confuse the line-based protocol and are stripped.
     *
     * @return {@code true} if the connection succeeded and the payload was written;
     *         {@code false} if no running instance answered (in which case the caller should
     *         fall back to the existing "already running" UX or, if it's the first instance
     *         attempting tryAcquire, retry).
     *
     * @since 2.0
     */
    public static boolean forwardToRunningInstance( String payload )
    {
        if ( payload == null ) {
            return false;
        }
        // Read the running instance's shared-secret token. Only same-UID processes can
        // read this file (owner-only perms set by writeIpcToken). If we can't read it,
        // the running instance is either owned by a different OS user or the token file
        // was never persisted — either way, refuse to forward.
        String token = readIpcTokenFromDisk();
        if ( token == null || token.isBlank() ) {
            return false;
        }
        // Strip embedded newlines so a malformed argv can't smuggle multiple commands.
        String safePayload = payload.replace( '\n', ' ' ).replace( '\r', ' ' );

        int port = ipcPort();
        try ( Socket socket = new Socket( InetAddress.getLoopbackAddress(), port );
              OutputStreamWriter writer = new OutputStreamWriter( socket.getOutputStream(), StandardCharsets.UTF_8 ) ) {
            socket.setSoTimeout( 2000 );
            // Protocol: line 1 = token, line 2 = payload.
            writer.write( token );
            writer.write( '\n' );
            writer.write( safePayload );
            writer.write( '\n' );
            writer.flush();
            return true;
        }
        catch ( IOException e ) {
            // Couldn't reach the running instance — it may have crashed without releasing the
            // port (unlikely, since the OS reclaims it), or there isn't actually one. Caller's
            // fallback handles it.
            return false;
        }
    }

    /**
     * Releases the single-instance lock by closing the server socket. This is normally handled automatically by the OS
     * on process exit, but can be called explicitly during shutdown if desired.
     *
     * @since 1.0
     */
    public static void release()
    {
        if ( lockSocket != null ) {
            try {
                lockSocket.close();
            }
            catch ( IOException ignored ) {
            }
            lockSocket = null;
        }
        if ( acceptThread != null ) {
            // The thread will exit naturally because accept() throws once the socket closes.
            // Don't interrupt — leave it to finish gracefully.
            acceptThread = null;
        }
        // Drop the on-disk IPC token. If the process is killed and another launcher
        // starts, a fresh token is generated; a stale file would not authenticate
        // either way (server compares against the in-memory token) but cleaning up
        // matches the spirit of the rest of the auth-file hygiene.
        ipcToken = null;
        try {
            Files.deleteIfExists( ipcTokenPath() );
        }
        catch ( IOException ignored ) {
            // Stale token on disk doesn't open any attack surface; ignore.
        }
    }

    // =========================================================================================
    //  IPC accept loop
    // =========================================================================================

    private static int ipcPort()
    {
        return LauncherConstants.LAUNCHER_IS_DEV
                ? LauncherConstants.SINGLE_INSTANCE_PORT_DEV
                : LauncherConstants.SINGLE_INSTANCE_PORT;
    }

    /** Spawns the daemon thread that handles incoming IPC connections. Idempotent — only the
     *  first call after a successful bind actually starts the thread. */
    private static void startAcceptLoop()
    {
        if ( acceptThread != null ) {
            return;
        }
        acceptThread = new Thread( SingleInstanceLock::acceptLoop, "single-instance-ipc" );
        acceptThread.setDaemon( true );
        acceptThread.start();
    }

    private static void acceptLoop()
    {
        ServerSocket server = lockSocket;
        while ( server != null && !server.isClosed() ) {
            try {
                Socket client = server.accept();
                // One short-lived thread per connection. Caps per-instance memory but lets
                // multiple URI hand-offs in quick succession not block each other. Inline
                // would also work, but the lambda + spawnNewTask pattern matches the rest
                // of the launcher's threading model.
                SystemUtilities.spawnNewTask( () -> handleIncoming( client ) );
            }
            catch ( IOException e ) {
                // Socket closed (release() ran) or other accept error — exit the loop.
                break;
            }
            server = lockSocket;
        }
    }

    private static void handleIncoming( Socket client )
    {
        try ( client;
              BufferedReader reader = new BufferedReader(
                      new InputStreamReader( client.getInputStream(), StandardCharsets.UTF_8 ) ) ) {
            client.setSoTimeout( 2000 );

            // Protocol: line 1 = shared-secret token, line 2 = payload. Refuse if the
            // token line is missing or doesn't match — same-UID-only file perms on the
            // token file mean any process that knows the token already has the OS-level
            // ability to act as us. Other UIDs on the box can connect to the loopback
            // port but cannot read the token, so this gate stops cross-UID injection.
            String tokenLine = reader.readLine();
            String expected = ipcToken;
            if ( expected == null || tokenLine == null
                    || !slowEquals( expected, tokenLine.trim() ) ) {
                Logger.logWarningSilent( LocalizationManager.get( "log.singleInstance.badToken" ) );
                return;
            }

            String line = reader.readLine();
            if ( line == null || line.isBlank() ) {
                return;
            }
            line = line.trim();

            // Surface the launcher window before dispatching the URI. The user just clicked
            // a deep-link expecting to land here — if the existing window were buried under
            // other apps the action would feel ignored.
            MCLauncherGuiController.requestFocus();

            if ( LauncherUriHandler.isLauncherUri( line ) ) {
                // Note: the token check above authenticates the SENDER as the same OS
                // user, but any same-UID process can read the token file and forward
                // an mmcl://play / join here. That's an accepted boundary (same-UID
                // code can already act as the user); the meaningful guard is that
                // LauncherUriHandler confirms game launches with the user
                // (confirmDeepLinkLaunch), so an injected play can't start a game
                // silently.
                Logger.logDebug( LocalizationManager.format( "log.singleInstance.dispatchDeepLink", line ) );
                LauncherUriHandler.handle( line );
            }
            else {
                Logger.logWarningSilent( LocalizationManager.format( "log.singleInstance.unrecognizedPayload",
                                                                     line ) );
            }
        }
        catch ( IOException ignored ) {
            // Best-effort — a malformed or aborted connection isn't actionable.
        }
    }

    /**
     * Constant-time string comparison for the IPC token check. Avoids the early-exit
     * timing side-channel of {@code String.equals} — not really an exploitable attack
     * over a local socket, but trivial enough to be the right default for any secret
     * comparison.
     */
    private static boolean slowEquals( String a, String b )
    {
        if ( a == null || b == null ) {
            return false;
        }
        byte[] aBytes = a.getBytes( StandardCharsets.UTF_8 );
        byte[] bBytes = b.getBytes( StandardCharsets.UTF_8 );
        if ( aBytes.length != bBytes.length ) {
            return false;
        }
        int diff = 0;
        for ( int i = 0; i < aBytes.length; i++ ) {
            diff |= aBytes[i] ^ bBytes[i];
        }
        return diff == 0;
    }
}
