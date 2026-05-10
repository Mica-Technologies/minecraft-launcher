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
        int port = ipcPort();
        try {
            // Backlog 8 — enough to absorb a small burst of website-link clicks. Bound to
            // the loopback address so we're only reachable from this machine; the IPC payload
            // is unauthenticated, so opening it to remote interfaces would be a serious foot-gun.
            lockSocket = new ServerSocket( port, 8, InetAddress.getLoopbackAddress() );
            startAcceptLoop();
            return true;
        }
        catch ( IOException e ) {
            // Port is already bound -- another instance is running
            return false;
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
        // Strip embedded newlines so a malformed argv can't smuggle multiple commands.
        String safePayload = payload.replace( '\n', ' ' ).replace( '\r', ' ' );

        int port = ipcPort();
        try ( Socket socket = new Socket( InetAddress.getLoopbackAddress(), port );
              OutputStreamWriter writer = new OutputStreamWriter( socket.getOutputStream(), StandardCharsets.UTF_8 ) ) {
            socket.setSoTimeout( 2000 );
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
                LauncherUriHandler.handle( line );
            }
            else {
                Logger.logWarningSilent( "Ignoring unrecognized IPC payload: " + line );
            }
        }
        catch ( IOException ignored ) {
            // Best-effort — a malformed or aborted connection isn't actionable.
        }
    }
}
