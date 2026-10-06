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

package com.micatechnologies.minecraft.launcher;

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackScanDetectionException;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthResult;
import com.micatechnologies.minecraft.launcher.config.GameModeManager;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackProgressProvider;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.gui.GUIUtilities;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiController;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherLoginGui;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherProgressGui;
import com.micatechnologies.minecraft.launcher.utilities.*;
import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;
import com.micatechnologies.minecraft.launcher.utilities.SchemeRegistrar;
import com.micatechnologies.minecraft.launcher.utilities.SingleInstanceLock;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Shell32;
import org.apache.commons.lang3.SystemUtils;

import java.io.File;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Launcher core class. This class is the main entry point of the Mica Forge Launcher, and handles the main processes
 * that are required by the launcher for full functionality, such as login, starting game, etc. Note that methods and
 * fields that were deprecated earlier than version 2.0 have been removed in version 2.0
 *
 * @author Mica Technologies
 * @version 2.0
 * @since START
 */
public class LauncherCore
{

    /**
     * Counted down once the early FX toolkit prestart (initiated from
     * {@link #main(String[])}) has finished {@link javafx.application.Platform#startup}.
     * {@link LauncherSession}'s FX-prestart thread awaits this before kicking
     * off prestartGui / prebuildMainGui so the two threads don't race for the
     * toolkit init.
     *
     * <p>Initialized to count=0 (already released) in server mode so the
     * await is a no-op there. Otherwise initialized in {@link #main} when
     * the early prestart is fired.</p>
     */
    private static java.util.concurrent.CountDownLatch fxToolkitReadyLatch =
            new java.util.concurrent.CountDownLatch( 0 );

    /** Await the toolkit-ready latch (see {@link #fxToolkitReadyLatch}). Caps
     *  the wait at 10 s so a wedged Platform.startup can't pin the session
     *  thread forever; downstream code's existing IllegalStateException
     *  catch in JFXPlatformRun handles the "still not ready" case the same
     *  as it always did. */
    public static void awaitFxToolkitReady() {
        try {
            fxToolkitReadyLatch.await( 10, java.util.concurrent.TimeUnit.SECONDS );
        }
        catch ( InterruptedException ignored ) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Launcher application restart flag. This flag must be true for the application to start. If this flag is set when
     * the launcher closes, it will restart.
     */
    private static boolean restartFlag = true;

    /**
     * String error reason for the application restarting. Null if there is no error.
     */
    private static String restartError = null;

    /**
     * The current launcher session. Each iteration of the restart loop creates a new session.
     */
    private static LauncherSession currentSession;

    /**
     * Pending {@code mmcl://} URI captured from argv at startup, awaiting dispatch once the
     * main GUI is up. Cleared by {@link #consumePendingLauncherUri()}. {@code null} when no URI
     * is pending. Volatile because it's set on the main thread and read by the session thread.
     */
    private static volatile String pendingLauncherUri = null;

    /** Sets a pending launcher URI to be dispatched after the main GUI is up. Used by the
     *  argv parser and (on macOS) the {@code Desktop.setOpenURIHandler} bridge. Idempotent —
     *  subsequent URIs overwrite the previous one if the launcher hasn't gotten around to
     *  dispatching yet. */
    public static void setPendingLauncherUri( String uri ) {
        pendingLauncherUri = uri;
    }

    /** Returns and clears the pending launcher URI, or {@code null} if there is none. Called
     *  by the launcher session right after the main GUI loads. */
    public static String consumePendingLauncherUri() {
        String result = pendingLauncherUri;
        pendingLauncherUri = null;
        return result;
    }

    /**
     * Pending {@code .mmcjson} manifest files captured before the main GUI is up — the macOS
     * {@code Desktop.setOpenFileHandler} bridge can fire during cold start (drag-drop a
     * {@code .mmcjson} onto the dock icon, or double-click in Finder while the launcher
     * is launching) before {@link com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiController#getTopStageOrNull()}
     * returns a stage we could meaningfully foreground. Stash here, drain in
     * {@link LauncherSession#run} after auth + modpack list are ready. Same pattern as
     * {@link #pendingLauncherUri} except the OPEN_FILE event can carry multiple files,
     * so this is a list rather than a single slot.
     */
    private static final java.util.List< java.io.File > pendingMmcjsonFiles =
            java.util.Collections.synchronizedList( new java.util.ArrayList<>() );

    /** Adds a {@code .mmcjson} file to the pending-import queue. */
    public static void addPendingMmcjsonFile( java.io.File file ) {
        if ( file != null ) pendingMmcjsonFiles.add( file );
    }

    /** Returns and clears the pending {@code .mmcjson} files, or an empty list. Called by
     *  the launcher session right after the main GUI loads. */
    public static java.util.List< java.io.File > consumePendingMmcjsonFiles() {
        synchronized ( pendingMmcjsonFiles ) {
            if ( pendingMmcjsonFiles.isEmpty() ) {
                return java.util.Collections.emptyList();
            }
            java.util.List< java.io.File > drained = new java.util.ArrayList<>( pendingMmcjsonFiles );
            pendingMmcjsonFiles.clear();
            return drained;
        }
    }

    /**
     * Launcher application main method/entry point.
     *
     * @param args launcher arguments
     *
     * @since 1.0
     */
    public static void main( String[] args ) {
        ColdStartProfiler.mark( "main_entry" );

        // Before anything that can fail: the main log only exists once the session starts, so a
        // launch that ends earlier would otherwise leave no trace (see StartupDiagnostics).
        StartupDiagnostics.installUncaughtExceptionHandler();
        // Not the --mcp relay: MCP clients spawn it often, and it would crowd out the launches.
        if ( args.length == 0 || !LauncherConstants.PROGRAM_ARG_MCP.equalsIgnoreCase( args[ 0 ] ) ) {
            StartupDiagnostics.recordLaunch( args );
        }

        // Raise the per-host keep-alive connection cap for the legacy
        // HttpURLConnection stack (JDK default is 5). The launcher fans out
        // availableProcessors-many concurrent downloads at the same Mojang /
        // Forge CDNs during a cold install; with only 5 pooled connections per
        // host the rest pay a fresh TLS handshake each. Must be set before the
        // first HTTP connection. Cheap, broad win independent of the larger
        // (deferred) shared-HttpClient/HTTP-2 migration.
        System.setProperty( "http.maxConnections", "32" );

        // Remove the current working directory from the Windows DLL search order
        // before any bare-name native load (the RGB vendor SDKs are loaded by name
        // and aren't System32 KnownDLLs, so a planted DLL in the launcher's working
        // directory could otherwise be loaded into the process). No-op off Windows.
        com.micatechnologies.minecraft.launcher.utilities.WindowsDllSearchHardening
                .removeCurrentDirectoryFromSearchPath();

        // Opt the process into Per-Monitor DPI Awareness V2 before anything else.
        // JavaFX's Glass backend internally sets only V1, which scales the JavaFX
        // client area per-monitor but leaves the OS-managed title bar sized at the
        // system DPI — so on a mixed-DPI multi-monitor setup the launcher's chrome
        // ends up tiny on the higher-DPI monitor. V2 has to be set before any
        // window is created or DPI API is queried; later upgrades fail silently.
        WindowsDpiAwareness.enablePerMonitorV2();

        // Full-screen TUI mode (--cli / --tui): capture the REAL console streams now, before the
        // logger (configureLogger, later) reassigns System.out to a file stream. Lanterna renders to
        // these captured streams while launcher logging goes file-only, so logs can't corrupt the
        // TUI. The launcher still runs as a normal CLIENT for config/path resolution.
        if ( com.micatechnologies.minecraft.launcher.tui.TuiMode.requestedIn( args ) ) {
            com.micatechnologies.minecraft.launcher.tui.TuiMode.enable( System.out, System.in );
        }

        // Headless manifest diagnostic (--diag-manifest <url>): resolve a pack's
        // Minecraft library manifest twice and report whether the second resolve
        // is memoized (client.json parsed once, not twice). Runs before the
        // single-instance lock + GUI/auth bootstrap so it works standalone with
        // the launcher open, needs no auth, and never spawns the game.
        if ( args.length == 2 && LauncherConstants.PROGRAM_ARG_DIAG_MANIFEST.equalsIgnoreCase( args[ 0 ] ) ) {
            runManifestDiagnostic( args[ 1 ] );
            return;
        }

        // MCP stdio relay (--mcp). An MCP client launches its server as a subprocess speaking
        // newline-delimited JSON-RPC over stdio, but this launcher cannot BE that subprocess:
        // it enforces single-instance because concurrent processes mutating the same modpack
        // folders is unsafe. So this mode runs a thin relay that forwards stdio to the running
        // launcher's loopback server, which owns the files and applies every approval check.
        //
        // Runs before the single-instance lock for the same reason --diag-manifest does: it
        // must work alongside an already-open launcher rather than being turned away by it.
        // stdout is reserved for protocol -- a stray log line there would corrupt the stream --
        // so this path returns before the logger is configured and reports only on stderr.
        if ( args.length >= 1 && LauncherConstants.PROGRAM_ARG_MCP.equalsIgnoreCase( args[ 0 ] ) ) {
            System.exit( com.micatechnologies.minecraft.launcher.mcp.transport.StdioProxy.run(
                    com.micatechnologies.minecraft.launcher.mcp.McpEndpointFile.defaultPath(),
                    System.in, System.out, System.err ) );
            return;
        }

        // Enforce single instance. If another copy holds the lock, hand this launch to it (a
        // mmcl:// URI from argv, or a plain "open" that brings its window forward) and exit; if
        // that can't be done, say why instead of exiting silently.
        if ( !SingleInstanceLock.tryAcquire() ) {
            handleSecondLaunch( args );
            return;
        }

        // NOTE: don't call any ConfigManager getter from here — GameModeManager
        // hasn't been initialized yet, isClient() returns false, and a config
        // read would resolve to LocalPathConstants.SERVER_MODE_LAUNCHER_FOLDER_PATH
        // (= the current working directory) instead of the real config folder.
        // That used to create a stale empty config and load defaults into the
        // ConfigManager singleton before the real config was read. The RGB
        // bootstrap moved to LauncherSession.run() after parseLauncherArgs.

        // Kick off Platform.startup on a daemon thread BEFORE the session
        // begins so the ~270 ms JavaFX toolkit bootstrap overlaps with
        // parseLauncherArgs + RGB + locale + auth + pack-load on the
        // session thread. The remaining prestart steps (window + main-GUI
        // FXML prebuild) still run from LauncherSession.run because they
        // need GameModeManager + the resolved locale to have settled
        // first — both unsafe to touch from here. This split move alone
        // gives the prestart chain ~150 ms more head start.
        //
        // Determined client-likely by argv: explicit --client flag, or no
        // server-mode flag at all (the default). The single false positive
        // (a launch that turns out to be server mode after parseLauncherArgs
        // resolves it) just leaves an unused toolkit running, which is
        // harmless — Platform.startup is a one-shot.
        boolean likelyClient = true;
        for ( String arg : args ) {
            if ( "-s".equals( arg ) || "--server".equals( arg ) ) {
                likelyClient = false;
                break;
            }
        }
        // TUI mode is a CLIENT but renders no JavaFX — skip the toolkit prestart entirely.
        if ( com.micatechnologies.minecraft.launcher.tui.TuiMode.isEnabled() ) {
            likelyClient = false;
        }
        if ( likelyClient ) {
            fxToolkitReadyLatch = new java.util.concurrent.CountDownLatch( 1 );
            Thread fxToolkitPrestart = new Thread( () -> {
                try {
                    javafx.application.Platform.startup( () -> { /* no-op init */ } );
                }
                catch ( IllegalStateException already ) {
                    // Toolkit already up — fine.
                }
                catch ( Throwable t ) {
                    // Logger isn't configured yet (configureLogger runs in
                    // LauncherSession.run); use stderr so the message still
                    // surfaces for debugging.
                    System.err.println( "[mmcl] Early FX toolkit start failed: " + t );
                    StartupDiagnostics.record( "Early JavaFX toolkit start failed", t );
                }
                finally {
                    fxToolkitReadyLatch.countDown();
                }
            }, "mmcl-fx-toolkit-prestart" );
            fxToolkitPrestart.setDaemon( true );
            fxToolkitPrestart.start();
        }

        while ( restartFlag ) {
            // Reset restart flag and create a new session for this lifecycle iteration
            String previousRestartError = restartError;
            restartFlag = false;
            restartError = null;
            // The previous session's restart has finished; accept close/restart requests again.
            lifecycleTransition.set( false );

            // Re-acquire the single-instance lock + IPC accept loop for this lifecycle
            // iteration. cleanupApp() releases the lock at the end of every session
            // (restart and exit alike), so without this a restarted session — Logout,
            // Add/Switch account, Reset Launcher — would run with no lock: a second
            // launcher process could start concurrently (re-opening the concurrent
            // config/install-index write corruption class) and mmcl:// deep-link
            // forwarding would silently stop. tryAcquire() is idempotent, so the
            // first iteration (lock still held from the pre-loop acquire above) is a
            // no-op; later iterations re-bind the socket and regenerate the IPC token.
            // A failed re-acquire is non-fatal — the session runs degraded rather than
            // refusing to come back.
            if ( !SingleInstanceLock.tryAcquire() ) {
                Logger.logWarningSilent( "Could not re-acquire single-instance lock after restart; "
                                                 + "a second instance may be able to start and deep-link "
                                                 + "forwarding may be unavailable this session." );
            }

            currentSession = new LauncherSession( args, previousRestartError );
            currentSession.run();
        }
    }

    /**
     * Handles a launch that found the single-instance port taken. Hands the launch to the
     * running copy: the {@code mmcl://} URI from argv, or {@code mmcl://open} for a plain
     * launch, which brings its window forward. If no launcher took it, or the one that did has
     * no window to show, tells the user why with a dialog that stays on top, rather than ending
     * with a busy cursor and nothing else. Every outcome goes to the startup log. Exits.
     *
     * @param args the launcher's arguments
     *
     * @since 2026.10
     */
    private static void handleSecondLaunch( String[] args )
    {
        String uri = null;
        for ( String arg : args ) {
            if ( LauncherUriHandler.isLauncherUri( arg ) ) {
                uri = arg;
                break;
            }
        }
        IOException bindFailure = SingleInstanceLock.lastAcquireFailure();
        String reason = bindFailure == null || bindFailure.getMessage() == null
                        ? String.valueOf( bindFailure )
                        : bindFailure.getMessage();
        int port = SingleInstanceLock.port();

        // Terminal mode with nothing to hand over: the running launcher's window is no use to a
        // --cli session. Keep the console message.
        boolean tui = com.micatechnologies.minecraft.launcher.tui.TuiMode.isEnabled();
        if ( tui && uri == null ) {
            StartupDiagnostics.record( "Port " + port + " unavailable (" + reason + "); --cli exits" );
            com.micatechnologies.minecraft.launcher.tui.TuiMode.realOut()
                    .println( LocalizationManager.get( "tui.alreadyRunning" ) );
            System.exit( 0 );
            return;
        }

        SingleInstanceLock.ForwardResult result = SingleInstanceLock.forwardToRunningInstance(
                uri != null ? uri : LauncherUriHandler.SCHEME + "://open" );
        StartupDiagnostics.record( "Port " + port + " unavailable (" + reason + "); handed "
                                   + ( uri != null ? "a deep link" : "an open request" )
                                   + " to the running launcher: " + result );
        if ( result.delivered() ) {
            System.exit( 0 );
            return;
        }

        String message = switch ( result ) {
            case DELIVERED_NO_WINDOW -> LocalizationManager.get( "startup.alreadyRunning.noWindow" );
            case REJECTED -> LocalizationManager.get( "startup.alreadyRunning.unreachable" );
            default -> LocalizationManager.format( "startup.portUnavailable", String.valueOf( port ), reason );
        };
        if ( tui || java.awt.GraphicsEnvironment.isHeadless() ) {
            System.err.println( message );
        }
        else {
            showStartupDialog( message );
        }
        System.exit( 1 );
    }

    /**
     * Shows a message before any launcher window exists, on top of other windows. A Swing
     * option pane with no owner gets no taskbar button and can open behind other apps, which
     * looks like the launcher did nothing; this one is pinned on top. Blocks until dismissed.
     *
     * @param message the text to show
     *
     * @since 2026.10
     */
    private static void showStartupDialog( String message )
    {
        try {
            javax.swing.SwingUtilities.invokeAndWait( () -> {
                // Option panes don't wrap text; a fixed-width HTML body does.
                String html = "<html><body style='width: 380px'>"
                              + message.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" )
                                       .replace( "\n", "<br>" )
                              + "</body></html>";
                javax.swing.JOptionPane pane = new javax.swing.JOptionPane( html,
                                                                            javax.swing.JOptionPane.WARNING_MESSAGE );
                javax.swing.JDialog dialog = pane.createDialog( null, LauncherConstants.LAUNCHER_APPLICATION_NAME );
                dialog.setAlwaysOnTop( true );
                dialog.setVisible( true );
                dialog.dispose();
            } );
        }
        catch ( Exception e ) {
            StartupDiagnostics.record( "Could not show the startup dialog", e );
            System.err.println( message );
        }
    }

    /**
     * Indicates whether any game is launching or running.
     *
     * @return {@code true} while any {@link com.micatechnologies.minecraft.launcher.game.session.GameSession}
     *         is preparing or running
     *
     * @since 3.5
     */
    public static boolean isGameRunning()
    {
        return com.micatechnologies.minecraft.launcher.game.session.GameSessionRegistry.get().hasActive();
    }

    /**
     * Surfaces a pre-launch confirmation dialog when {@link ModConflictDetector}
     * found one or more known-bad mod combinations in the pack's {@code mods/}
     * folder. Returns {@code true} if the user wants to continue the launch
     * (either after disabling a mod or by choosing "Launch anyway"),
     * {@code false} if they cancelled.
     *
     * <p>Choices presented:</p>
     * <ul>
     *   <li><b>Disable {first mod}</b> — atomically renames the offending jar
     *       to {@code .jar.disabled} and continues the launch. The other
     *       half of the conflict stays enabled.</li>
     *   <li><b>Launch anyway</b> — proceeds with both mods enabled. The user
     *       may have a reason (testing a fork, etc.) or is fine with the
     *       game crashing.</li>
     *   <li><b>Cancel</b> — bails out; the launch never starts. The user
     *       can resolve the conflict from the modpack detail modal's mod
     *       toggles.</li>
     * </ul>
     *
     * @param pack      the mod pack being launched, used to locate and disable the
     *                  offending jar inside its {@code mods/} folder
     * @param conflicts the non-empty list of detected conflicts; only the first is
     *                  offered for one-click resolution, the rest are listed in the
     *                  dialog body
     *
     * @return {@code true} to proceed with the launch (mod disabled, or "Launch
     *         anyway" chosen), {@code false} to cancel
     */
    private static boolean promptForConflicts(
            GameModPack pack,
            java.util.List< com.micatechnologies.minecraft.launcher.game.modpack.ModConflictDetector.Conflict >
                    conflicts )
    {
        // Multi-conflict packs are rare; if more than one rule fires we
        // just stack their summaries in the dialog body so the user sees
        // all the issues. The button row only offers to resolve the
        // FIRST conflict to keep the dialog simple — if there are more,
        // the user will see them again on the next launch attempt.
        var first = conflicts.get( 0 );
        StringBuilder body = new StringBuilder();
        for ( int i = 0; i < conflicts.size(); i++ ) {
            if ( i > 0 ) body.append( "\n\n" );
            body.append( "• " ).append( conflicts.get( i ).title() );
            body.append( "\n  " ).append( conflicts.get( i ).description() );
        }
        int response = GUIUtilities.showQuestionMessage(
                "Mod conflict detected",
                "These mods don't get along",
                body.toString(),
                "Disable " + first.firstJarName(),
                "Launch anyway",
                MCLauncherGuiController.getTopStageOrNull() );
        // showQuestionMessage returns 1 for button1 (Disable), 2 for
        // button2 (Launch anyway), 0 for Cancel (the default escape).
        if ( response == 1 ) {
            boolean ok = com.micatechnologies.minecraft.launcher.game.modpack.ModConflictDetector
                    .disableJar( pack, first.firstJarName() );
            if ( ok ) {
                Logger.logStd( LocalizationManager.format( "log.launcherCore.preLaunchDisabledMod",
                                                           first.firstJarName(), first.secondJarName() ) );
                return true;
            }
            // Rename failed — most likely the file is locked or already
            // disabled. Surface that and bail to the main menu so the
            // user can resolve manually.
            com.micatechnologies.minecraft.launcher.utilities.NotificationManager.warn(
                    LocalizationManager.format( "notification.launch.disableModFailed.title",
                                                first.firstJarName() ),
                    LocalizationManager.get( "notification.launch.disableModFailed.body" ) );
            return false;
        }
        if ( response == 2 ) {
            Logger.logStd( LocalizationManager.format( "log.launcherCore.preLaunchLaunchAnyway",
                                                       first.firstJarName(), first.secondJarName() ) );
            return true;
        }
        return false; // Cancel
    }

    /** Set once the keyboard RGB and Discord presence follow the running games. */
    private static final AtomicBoolean followerInstalled = new AtomicBoolean( false );

    /**
     * Makes keyboard RGB and Discord presence follow the running games: they show the most
     * recently started game and return to the menu state only when the last game exits.
     * Installed on the first launch; idempotent.
     */
    private static void ensureRunningGameFollower() {
        if ( !followerInstalled.compareAndSet( false, true ) ) {
            return;
        }
        var registry = com.micatechnologies.minecraft.launcher.game.session.GameSessionRegistry.get();
        // One ordered thread, so a slow "show the game" (RGB samples the pack logo) can't land
        // after the "no game" of a game that ended quickly; an update already replaced by a
        // newer one skips itself.
        var updates = com.micatechnologies.minecraft.launcher.game.session.LatestUpdateRunner
                .onDaemonThread( "mmcl-running-game-follower" );
        var follower = new com.micatechnologies.minecraft.launcher.game.session.RunningGameFollower(
                new com.micatechnologies.minecraft.launcher.game.session.RunningGameFollower.Sink()
                {
                    @Override
                    public void showGame( com.micatechnologies.minecraft.launcher.game.session.GameSession s )
                    {
                        // RgbIntegration and Discord each bail when switched off, and contain
                        // their own failures.
                        updates.submit( () -> {
                            com.micatechnologies.minecraft.launcher.rgb.RgbIntegration.onPlayStarted( s.pack() );
                            DiscordRpcUtility.setGamePresence( s.pack() );
                        } );
                    }

                    @Override
                    public void showNoGame()
                    {
                        updates.submit( () -> {
                            com.micatechnologies.minecraft.launcher.rgb.RgbIntegration.onPlayEnded();
                            DiscordRpcUtility.setMenuPresence( LocalizationManager.get( "discordRpc.screen.selectingPack" ) );
                        } );
                    }
                } );
        runningGameFollower = follower;
        // Snapshot inside the follower's lock (update is synchronized on it): taken outside, two
        // changes on different threads (one game exiting as another starts) could apply their
        // snapshots out of order and leave Discord and RGB showing no game while one runs.
        registry.addListener( () -> {
            synchronized ( follower ) {
                follower.update( registry.sessions() );
            }
        } );
    }

    /** The follower {@link #ensureRunningGameFollower()} installed, or {@code null} before the first launch. */
    private static volatile com.micatechnologies.minecraft.launcher.game.session.RunningGameFollower
            runningGameFollower;

    /**
     * Brings back what an in-process restart tore down while games kept running: the Running
     * Games window (its tabs are rebuilt from the registry, with each game's log so far) and the
     * running game's Discord presence and keyboard effect. Called by the session once the main
     * window is up; does nothing when no game is launching or running.
     *
     * @since 2026.10
     */
    static void restoreRunningGamesAfterRestart() {
        var registry = com.micatechnologies.minecraft.launcher.game.session.GameSessionRegistry.get();
        if ( !registry.hasActive() ) {
            return;
        }
        if ( GameModeManager.isClient() && MCLauncherGuiController.shouldCreateGui() ) {
            com.micatechnologies.minecraft.launcher.gui.RunningGamesWindow.showWindow();
        }
        var follower = runningGameFollower;
        if ( follower != null ) {
            synchronized ( follower ) {
                follower.resync( registry.sessions() );
            }
        }
    }

    /**
     * Wraps up a game that has exited: records the play time, and on a crash notifies the
     * user, attaches the newest crash report to the session and brings its tab forward.
     *
     * @param session      the game's session
     * @param pack         the pack that ran
     * @param process      the exited process
     * @param launchStartMs when the game was started
     * @param gui          whether a GUI is showing
     */
    private static void onGameExited( com.micatechnologies.minecraft.launcher.game.session.GameSession session,
                                      GameModPack pack, Process process, long launchStartMs, boolean gui ) {
        pack.recordSessionEnd( System.currentTimeMillis() - launchStartMs );
        int exitCode;
        try {
            exitCode = process.exitValue();
        }
        catch ( IllegalThreadStateException e ) {
            exitCode = -1;
        }
        if ( exitCode == 0 ) {
            return;
        }
        Logger.logError( LocalizationManager.format( "log.launcherCore.gameCrashedExitCode", exitCode ) );
        NotificationManager.error(
                LocalizationManager.get( "notification.launch.gameCrashed.title" ),
                LocalizationManager.format( "notification.launch.gameCrashed.body",
                                            pack.getFriendlyName() != null ? pack.getFriendlyName() : "Minecraft",
                                            exitCode ) );
        try {
            session.setCrashReport( pack.getLatestCrashReport() );
        }
        catch ( RuntimeException e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.launcherCore.crashReportReadFailed",
                                                                 e.getClass().getSimpleName() ) );
        }
        if ( gui ) {
            com.micatechnologies.minecraft.launcher.gui.RunningGamesWindow.showSession( session );
        }
    }

    /**
     * Tells the user why a launch was refused: the pack is already running, its account is
     * already playing something else, or (while the GUI can follow only one game) another game
     * is active.
     *
     * @param decision the refusal
     * @param pack     the pack that was refused
     */
    private static void reportLaunchRefused( com.micatechnologies.minecraft.launcher.game.session.LaunchAdmission.Decision decision,
                                             GameModPack pack ) {
        var other = decision.conflicting();
        String otherPack = other == null ? "" : String.valueOf( other.packName() );
        String message = switch ( decision.outcome() ) {
            case PACK_ALREADY_RUNNING -> LocalizationManager.format( "launch.refused.packRunning", pack.getFriendlyName() );
            case ACCOUNT_BUSY -> LocalizationManager.format( "launch.refused.accountBusy",
                                                             other == null ? "" : String.valueOf( other.accountName() ),
                                                             otherPack );
            case ANOTHER_GAME_RUNNING -> LocalizationManager.format( "launch.refused.anotherGame", otherPack );
            case OK -> "";
        };
        Logger.logStd( LocalizationManager.format( "log.launcherCore.launchRefused", decision.outcome() ) );
        if ( MCLauncherGuiController.shouldCreateGui()
                && decision.outcome() == com.micatechnologies.minecraft.launcher.game.session.LaunchAdmission.Outcome.PACK_ALREADY_RUNNING
                && other != null ) {
            // Asking to play a pack that's already up means "show me that game".
            com.micatechnologies.minecraft.launcher.gui.RunningGamesWindow.showSession( other );
            return;
        }
        if ( MCLauncherGuiController.shouldCreateGui() ) {
            NotificationManager.warn( LocalizationManager.get( "launch.refused.title" ), message );
            GUIUtilities.JFXPlatformRun( MCLauncherGuiController::requestFocus );
        }
        else {
            Logger.logError( message );
        }
    }

    /**
     * Resolves the account a pack launches as, and handles the cases that block the launch.
     *
     * <ul>
     *   <li>The pack's override names an account that is no longer signed in: asks whether to
     *       play as the default account instead, and if so clears the stale override.</li>
     *   <li>The account's saved sign-in was rejected: offers to sign it in again.</li>
     *   <li>No account at all: reports it.</li>
     * </ul>
     *
     * <p>Blocks; call off the FX thread.</p>
     *
     * @param pack              the pack about to launch
     * @param forcedAccountUuid an account named for this launch only (MCP), or {@code null}
     *
     * @return the user to launch as, or {@code null} when the launch shouldn't go ahead
     */
    private static net.hycrafthd.minecraft_authenticator.login.User resolveLaunchUser( GameModPack pack,
                                                                                      String forcedAccountUuid ) {
        String key = pack.getSettingsKey();
        String override = forcedAccountUuid != null ? forcedAccountUuid : ConfigManager.getAccountOverrideForPack( key );
        try {
            return MCLauncherAuthManager.userForLaunch( override );
        }
        catch ( com.micatechnologies.minecraft.launcher.game.auth.LaunchAccountResolver.BlockedException blocked ) {
            var resolution = blocked.resolution();
            boolean gui = MCLauncherGuiController.shouldCreateGui();
            switch ( resolution.problem() ) {
                case OVERRIDE_MISSING -> {
                    Logger.logStd( LocalizationManager.get( "log.launcherCore.overrideAccountMissing" ) );
                    var fallback = MCLauncherAuthManager.getLoggedInUser();
                    if ( !gui || fallback == null || forcedAccountUuid != null ) {
                        // An account named for this one launch (MCP) that isn't signed in is
                        // simply refused; the pack's own setting isn't involved.
                        return null;
                    }
                    int answer = GUIUtilities.showQuestionMessage(
                            LocalizationManager.get( "launch.account.overrideMissing.title" ),
                            LocalizationManager.get( "launch.account.overrideMissing.header" ),
                            LocalizationManager.format( "launch.account.overrideMissing.body", pack.getFriendlyName() ),
                            LocalizationManager.format( "launch.account.overrideMissing.useDefault", fallback.name() ),
                            LocalizationManager.get( "dialog.button.cancel" ),
                            MCLauncherGuiController.getTopStageOrNull() );
                    if ( answer != 1 ) {
                        return null;
                    }
                    // The account is gone, so the override can never apply again.
                    ConfigManager.setAccountOverrideForPack( key, null );
                    try {
                        return MCLauncherAuthManager.userForLaunch( null );
                    }
                    catch ( com.micatechnologies.minecraft.launcher.game.auth.LaunchAccountResolver.BlockedException again ) {
                        return null;
                    }
                }
                case NEEDS_SIGN_IN -> {
                    Logger.logStd( LocalizationManager.get( "log.launcherCore.launchAccountNeedsSignIn" ) );
                    if ( gui ) {
                        int answer = GUIUtilities.showQuestionMessage(
                                LocalizationManager.get( "launch.account.needsSignIn.title" ),
                                LocalizationManager.format( "launch.account.needsSignIn.header",
                                                            resolution.accountName() == null ? "" : resolution.accountName() ),
                                LocalizationManager.get( "launch.account.needsSignIn.body" ),
                                LocalizationManager.get( "settings.accounts.signInAgain" ),
                                LocalizationManager.get( "dialog.button.cancel" ),
                                MCLauncherGuiController.getTopStageOrNull() );
                        if ( answer == 1 ) {
                            GUIUtilities.JFXPlatformRun( () -> com.micatechnologies.minecraft.launcher.gui.AddAccountDialog
                                    .show( MCLauncherGuiController.getTopStageOrNull() ) );
                        }
                    }
                    return null;
                }
                default -> {
                    Logger.logError( LocalizationManager.get( "log.launcherCore.noLaunchAccount" ) );
                    if ( gui ) {
                        GUIUtilities.showErrorMessage( LocalizationManager.get( "launch.account.none" ),
                                                       MCLauncherGuiController.getTopStageOrNull() );
                    }
                    return null;
                }
            }
        }
    }

    /**
     * Launches the specified mod pack for gameplay.
     *
     * @param gameModPack mod pack to launch/play
     *
     * @since 2.0
     */
    public static void play( GameModPack gameModPack ) {
        play( gameModPack, null );
    }

    /**
     * Launches the specified mod pack for gameplay, optionally running a callback once the
     * launch sequence finishes.
     *
     * <p>Awaits any cold-start-deferred auth refresh, runs the pre-launch mod-conflict scan
     * (client mode only), drives the launch-progress GUI + tracker, spawns the game JVM, and
     * either attaches the in-game console or blocks on the process until it exits. The
     * {@code after} callback is invoked after the game exits, but only when the in-game console
     * is <em>not</em> managing the UI lifecycle and the launch was not cancelled.</p>
     *
     * @param gameModPack mod pack to launch/play
     * @param after       optional callback to run once the launch sequence completes; may be
     *                    {@code null}. Not invoked when the in-game console is enabled or the
     *                    launch was cancelled.
     *
     * @since 2.0
     */
    public static void play( GameModPack gameModPack, Runnable after ) {
        play( gameModPack, after, null );
    }

    /**
     * Launches a pack as a specific signed-in account for this one launch, ignoring the pack's
     * own account setting. Used by MCP's {@code launch_modpack} when it names an account.
     *
     * @param gameModPack the pack to launch
     * @param accountUuid the account to play as, or {@code null} for the pack's usual account
     *
     * @since 2026.10
     */
    public static void playAs( GameModPack gameModPack, String accountUuid ) {
        play( gameModPack, null, accountUuid );
    }

    private static void play( GameModPack gameModPack, Runnable after, String forcedAccountUuid ) {
        // Pick the account this pack launches as (its override, else the default) and wait
        // for that account's token refresh if one is due. We're on a background thread
        // (callers spawn play() off the FX thread), so the wait doesn't freeze the UI.
        // Server mode has no accounts.
        final net.hycrafthd.minecraft_authenticator.login.User launchUser;
        if ( GameModeManager.isClient() ) {
            launchUser = resolveLaunchUser( gameModPack, forcedAccountUuid );
            if ( launchUser == null ) {
                return;  // blocked; the user has been told why
            }
        }
        else {
            launchUser = null;
        }

        // Pre-launch mod-conflict scan. Returns the first known-bad combo
        // we recognise (OptiFine+Sodium, JEI+REI); the prompt lets the user
        // disable one of the offending jars in-place and continue, or
        // cancel the launch entirely. Skipped for server mode (no GUI to
        // prompt with) and for packs with no mods/ folder (vanilla, fresh
        // installs, etc.) — ModConflictDetector.scan handles those as
        // empty results.
        if ( GameModeManager.isClient() && MCLauncherGuiController.shouldCreateGui() ) {
            java.util.List< com.micatechnologies.minecraft.launcher.game.modpack.ModConflictDetector.Conflict >
                    conflicts =
                    com.micatechnologies.minecraft.launcher.game.modpack.ModConflictDetector.scan( gameModPack );
            if ( !conflicts.isEmpty() ) {
                if ( !promptForConflicts( gameModPack, conflicts ) ) {
                    // User cancelled the launch (or chose "Open Mods Folder"
                    // and is going to manage it manually). Just return —
                    // no session was registered yet, so there is nothing to clean up.
                    return;
                }
            }
        }

        // Register this launch as its own session. Admission happens here, atomically: at
        // most one active game per pack and per account (and, until the GUI can show more
        // than one game, one in total). The session owns this launch's cancellation, bound
        // to this worker thread so cancel() can interrupt blocking downloads.
        final String packKey = com.micatechnologies.minecraft.launcher.game.session.GameSession.keyFor( gameModPack );
        final com.micatechnologies.minecraft.launcher.game.session.GameSession session =
                new com.micatechnologies.minecraft.launcher.game.session.GameSession(
                        gameModPack, packKey, gameModPack.getFriendlyName(),
                        launchUser == null ? null : launchUser.uuid(),
                        launchUser == null ? null : launchUser.name(),
                        System::currentTimeMillis );
        session.bindWorker( Thread.currentThread() );
        ensureRunningGameFollower();
        com.micatechnologies.minecraft.launcher.game.session.LaunchAdmission.Decision admission =
                com.micatechnologies.minecraft.launcher.game.session.GameSessionRegistry.get().tryRegister( session );
        if ( !admission.ok() ) {
            reportLaunchRefused( admission, gameModPack );
            return;
        }
        try {
        if ( gameModPack.getPackMinRAMGB() <= ConfigManager.getMaxRamInGb() ) {
            // The step tracker is per pack type: vanilla packs omit MODPACK_CONTENT and the
            // modloader rows; loaders without a post-install pipeline (Fabric) drop
            // FORGE_PROCESSORS rather than show a row that instantly completes.
            java.util.List< com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker.StepId > stepList =
                    new java.util.ArrayList<>();
            if ( !gameModPack.isVanillaVersion() ) {
                stepList.add( com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker.StepId.MODPACK_CONTENT );
                stepList.add( com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker.StepId.FORGE_LIBS );
            }
            stepList.add( com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker.StepId.MC_LIBS_ASSETS );
            stepList.add( com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker.StepId.JRE_INSTALL );
            if ( gameModPack.usesPostInstallSteps() ) {
                stepList.add( com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker.StepId.FORGE_PROCESSORS );
            }
            stepList.add( com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker.StepId.SECURITY_SCAN );
            com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker tracker =
                    com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker.forSteps(
                            stepList.toArray( new com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker.StepId[ 0 ] ) );
            com.micatechnologies.minecraft.launcher.game.modpack.LaunchTrackerProgressBridge progressBridge =
                    new com.micatechnologies.minecraft.launcher.game.modpack.LaunchTrackerProgressBridge( tracker );
            session.setTracker( tracker );

            final boolean gui = MCLauncherGuiController.shouldCreateGui();
            if ( gui ) {
                // The launch shows in its own tab of the Running Games window; the main window
                // stays where the user left it, free to launch something else.
                com.micatechnologies.minecraft.launcher.gui.RunningGamesWindow.showSession( session );
                tracker.addListener( step -> {
                    if ( !session.isCancelled() ) {
                        TaskbarProgressManager.setLaunchProgress( tracker, tracker.overallFraction() );
                    }
                } );
            }
            else {
                // Headless launch (server mode): log step transitions and throttled download
                // progress, so a long re-sync isn't indistinguishable from a hang.
                attachHeadlessTrackerLogging( tracker );
            }

            try {
                Logger.logDebug( LocalizationManager.LAUNCHING_MOD_PACK_TEXT + ": " + gameModPack.getFriendlyName() );
                final long progressStartMs = System.currentTimeMillis();
                // One-shot "ready" toast when the last step completes: after a long preparation,
                // or when the launcher isn't focused, so a user who tabbed away is pulled back.
                final AtomicBoolean readyToastFired = new AtomicBoolean( false );
                tracker.addListener( step -> {
                    if ( session.isCancelled() ) return;
                    if ( !allStepsCompleted( tracker ) ) return;
                    if ( !readyToastFired.compareAndSet( false, true ) ) return;
                    TaskbarProgressManager.endLaunchProgress( tracker );
                    long elapsedMs = System.currentTimeMillis() - progressStartMs;
                    if ( elapsedMs > 10_000L || !MCLauncherGuiController.isLauncherFocused() ) {
                        NotificationManager.success(
                                LocalizationManager.get( "notification.launch.ready.title" ),
                                gameModPack.getFriendlyName() != null
                                        ? LocalizationManager.format( "notification.launch.ready.bodyNamed",
                                                                      gameModPack.getFriendlyName() )
                                        : LocalizationManager.get( "notification.launch.ready.body" ) );
                    }
                } );
                gameModPack.setProgressProvider( progressBridge );

                // Download retries and live byte progress land as sub-text on whichever of
                // this launch's rows are running. Each launch adds its own listeners and
                // removes them below. The session is this launch's download scope: the worker's
                // downloads (and the pools it hands them to) carry it, and the listeners hear
                // only those, so packs preparing at once don't show each other's progress.
                com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities.setDownloadScope( session );
                final Runnable removeRetryListener =
                        com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities.addRetryNoticeListener(
                                session, notice -> {
                                    if ( session.isCancelled() ) return;
                                    for ( var s : tracker.runningSteps() ) {
                                        tracker.setSubText( s.id(), notice );
                                    }
                                } );
                final Runnable removeProgressListener =
                        com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities.addDownloadProgressListener(
                                session, notice -> {
                                    if ( session.isCancelled() ) return;
                                    for ( var s : tracker.runningSteps() ) {
                                        tracker.setSubText( s.id(), notice );
                                    }
                                } );
                Process spawned;
                try {
                    spawned = gameModPack.startGame( launchUser, session::isCancelled );
                }
                finally {
                    removeRetryListener.run();
                    removeProgressListener.run();
                    com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities.setDownloadScope( null );
                    // Release the progress provider now that preparation is done. Swap rather
                    // than set(null) so the cached launcher survives.
                    gameModPack.swapProgressProviderTransiently( null );
                }

                // Cancelled after the JVM was already spawned (the worker was deep in process
                // spawn and missed the interrupt): kill it, so no orphaned game window remains.
                if ( session.isCancelled() ) {
                    if ( spawned != null && spawned.isAlive() ) {
                        spawned.destroyForcibly();
                    }
                    Logger.logStd( LocalizationManager.get( "log.launcherCore.launchCancelledAfterSpawn" ) );
                    TaskbarProgressManager.endLaunchProgress( tracker );
                    return;
                }

                gameModPack.saveInstalledVersion();
                gameModPack.recordLaunchStart();
                final long launchStartMs = System.currentTimeMillis();

                // Refresh the OS-shell recent-modpacks surface (jump list / .desktop actions).
                SystemUtilities.spawnNewTask( com.micatechnologies.minecraft.launcher.utilities.JumpListManager::refresh );

                if ( spawned != null ) {
                    // Capture the game's output for its whole life, window or no window: an
                    // unread pipe fills within moments and stalls the game. Server mode
                    // inherits the terminal instead, and the TUI reads it itself.
                    if ( gui ) {
                        java.nio.file.Path logFile = com.micatechnologies.minecraft.launcher.game.session.GameLog.fileFor(
                                java.nio.file.Path.of( LocalPathManager.getLauncherLogFolderPath() ),
                                gameModPack.getPackName(),
                                new java.text.SimpleDateFormat( "yyyy-MM-dd--HH-mm-ss" ).format( new java.util.Date() ) );
                        com.micatechnologies.minecraft.launcher.game.session.GameLog log =
                                new com.micatechnologies.minecraft.launcher.game.session.GameLog( logFile );
                        log.attach( spawned );
                        session.setLog( log );
                    }
                    // RUNNING now; EXITED or CRASHED when the process ends.
                    session.attachProcess( spawned );
                    spawned.onExit().thenAccept( p -> onGameExited( session, gameModPack, p, launchStartMs, gui ) );
                    if ( gui && !ConfigManager.getInGameConsoleEnable() ) {
                        // "Show console on launch" off: the window showed the preparation; once
                        // the game is up it steps aside (a crash brings it back).
                        com.micatechnologies.minecraft.launcher.gui.RunningGamesWindow.hideUnlessPreparing();
                    }
                }
            }
            catch ( ModpackScanDetectionException e ) {
                // Scan-blocked message is a deliberately multi-line bulleted listing; keep its
                // structure in the popup.
                Logger.logErrorMultiline( e.getMessage() );
                Logger.logThrowable( e );
                TaskbarProgressManager.endLaunchProgress( tracker );
            }
            catch ( Exception e ) {
                // Cancellation exits via a thrown exception too (interrupt, or the launcher's
                // cancellation check); don't report a deliberate cancel as an error.
                if ( session.isCancelled() ) {
                    Logger.logStd( LocalizationManager.get( "log.launcherCore.launchCancelled" ) );
                }
                else {
                    Logger.logError( LocalizationManager.UNABLE_START_GAME_EXCEPTION_TEXT );
                    Logger.logThrowable( e );
                }
                TaskbarProgressManager.endLaunchProgress( tracker );
            }

            if ( after != null && !session.isCancelled() ) {
                after.run();
            }
        }
        else {
            Logger.logError( "[" +
                                     gameModPack.getFriendlyName() +
                                     "] " +
                                     LocalizationManager.REQUIRES_MIN_OF_TEXT +
                                     " " +
                                     gameModPack.getPackMinRAMGB() +
                                     " " +
                                     LocalizationManager.GB_OF_RAM_TEXT +
                                     ". " +
                                     LocalizationManager.MAX_RAM_SETTING_MUST_INCREASE_TEXT );
        }
        }
        finally {
            // Clear the interrupt flag in case cancellation set it but no blocking call
            // consumed it. Leaving the flag dirty on a worker thread that gets reused for
            // a later spawnNewTask() would cause that next task to misbehave (e.g. throw
            // InterruptedException out of an innocuous sleep call). A launch that never got
            // its game running ends here (cancelled or failed), releasing its pack and
            // account; a running game's session ends when its process exits.
            Thread.interrupted();
            session.endWithoutGame();
        }
    }

    /**
     * Performs mod pack selection using the specified desired mod pack, if present. In client mode, mod pack selection
     * displays the mod pack selection window with the specified mod pack preselected. In server mode, mod pack
     * selection launches the specified mod pack.
     *
     * @param modPackName         name of mod pack
     * @param initialErrorMessage error message to surface to the user before selection (logged
     *                            visibly in client mode, silently in server mode); may be
     *                            {@code null} or empty when there is no pending error
     *
     * @since 1.0
     */
    public static void doModpackSelection( String modPackName, String initialErrorMessage ) {
        // Create variable to store final resulting mod pack name
        GameModPack finalGameModPack = GameModPackManager.getInstalledModPackByName( modPackName );

        // Check if requested mod pack is installed
        if ( modPackName.length() > 0 && finalGameModPack == null ) {
            Logger.logError( modPackName + " " + LocalizationManager.PACK_NOT_INSTALLED_WILL_DEFAULT_TO_FIRST_TEXT );
        }

        // Show gui or start start
        if ( GameModeManager.isClient() ) {
            if ( initialErrorMessage != null && initialErrorMessage.length() > 0 ) {
                Logger.logError( initialErrorMessage );
            }

            // CLI auto-launch path: when a valid modpack name was passed on the command
            // line (typical desktop-shortcut flow), skip the main menu and kick off the
            // pack's launch pipeline directly. Mirrors the GUI Play-button code path in
            // MCLauncherMainGui.ModpackHeroCard.startPlay — set last-played + Discord
            // presence, then call play() with an after-callback that surfaces the main
            // GUI once the game exits so the user has somewhere to land.
            if ( finalGameModPack != null ) {
                final GameModPack autoPack = finalGameModPack;
                ConfigManager.setLastModPackSelected( autoPack.getPackName() );
                SystemUtilities.spawnNewTask( () -> {
                    SystemUtilities.spawnNewTask( () -> DiscordRpcUtility.setGamePresence( autoPack ) );
                    play( autoPack, () -> GUIUtilities.JFXPlatformRun( () -> {
                        try {
                            MCLauncherGuiController.goToMainGui();
                            MCLauncherGuiController.requestFocus();
                        }
                        catch ( IOException e ) {
                            Logger.logError( LocalizationManager.get( "log.launcherCore.mainGuiAfterAutoLaunchFailed" ) );
                            Logger.logThrowable( e );
                            closeApp();
                        }
                    } ) );
                } );
                return;
            }

            try {
                MCLauncherGuiController.goToMainGui();
            }
            catch ( IOException e ) {
                Logger.logError( LocalizationManager.get( "log.launcherCore.mainGuiLoadFailed" ) );
                Logger.logThrowable( e );
            }
        }
        else if ( GameModeManager.isServer() ) {
            if ( initialErrorMessage != null && initialErrorMessage.length() > 0 ) {
                Logger.logErrorSilent( initialErrorMessage );
            }
            if ( finalGameModPack != null ) {
                // Server mode: launch with auto-restart on crash
                final int maxRestarts = 3;
                int restartCount = 0;
                boolean shouldRestart = true;
                GameModPack launchPack = finalGameModPack;
                while ( shouldRestart ) {
                    // Re-check the manifest on every crash-restart. The pack reference resolved
                    // before the loop is a snapshot; a restart hours later would otherwise relaunch
                    // the exact same mod set even if a new manifest had been published in the
                    // meantime. Servers should always check for updates before (re)starting, so
                    // re-run the blocking load and re-resolve by name.
                    if ( restartCount > 0 ) {
                        try {
                            GameModPackManager.fetchInstalledModPacks( null, true );
                        }
                        catch ( Exception e ) {
                            Logger.logErrorSilent( "Could not refresh modpack manifests before restart; "
                                                           + "reusing the previously loaded pack." );
                            Logger.logThrowable( e );
                        }
                    }
                    GameModPack refreshed = GameModPackManager.getInstalledModPackByName( modPackName );
                    if ( refreshed != null && !refreshed.isFailedLoad() && !refreshed.isStub() ) {
                        launchPack = refreshed;
                    }
                    else if ( refreshed != null ) {
                        // Keep the last known-good pack rather than launching from a stub/sentinel,
                        // which buildClasspath would (correctly) refuse anyway.
                        Logger.logErrorSilent( "Refreshed modpack entry for \"" + modPackName
                                                       + "\" is not fully loaded; reusing the previously "
                                                       + "loaded pack for this launch." );
                    }

                    Logger.logStd( restartCount > 0
                                           ? LocalizationManager.format( "log.launcherCore.startingServerRestart",
                                                                         restartCount, maxRestarts )
                                           : LocalizationManager.get( "log.launcherCore.startingServer" ) );
                    Logger.logStd( "Launching \"" + launchPack.getPackName() + "\" version "
                                           + launchPack.getPackVersion() + "." );
                    play( launchPack );

                    Process proc = launchPack.getLastLaunchedProcess();
                    if ( proc != null ) {
                        try {
                            // No userspace stream draining needed here — GameModPackLauncher
                            // picks ChildIoMode.INHERIT in server mode, which hands the child
                            // JVM the launcher's own stdout/stderr file descriptors directly.
                            // The Minecraft server log lands on the operator's SSH terminal
                            // with kernel-managed ordering; we just block on waitFor() and
                            // surface the exit code + restart decision.
                            int exitCode = proc.waitFor();
                            Logger.logStd( LocalizationManager.format( "log.launcherCore.serverExited", exitCode ) );

                            if ( exitCode == 0 ) {
                                // Clean shutdown — don't restart
                                shouldRestart = false;
                            }
                            else if ( restartCount < maxRestarts ) {
                                restartCount++;
                                Logger.logStd( LocalizationManager.get( "log.launcherCore.serverCrashedRestarting" ) );
                                Thread.sleep( 5_000 );
                            }
                            else {
                                Logger.logErrorSilent( LocalizationManager.format(
                                        "log.launcherCore.serverCrashedGivingUp", maxRestarts ) );
                                shouldRestart = false;
                            }
                        }
                        catch ( InterruptedException e ) {
                            Logger.logErrorSilent( LocalizationManager.get( "log.launcherCore.serverWaitInterrupted" ) );
                            shouldRestart = false;
                        }
                    }
                    else {
                        shouldRestart = false;
                    }
                }
            }
            else {
                Logger.logError( LocalizationManager.NO_MOD_PACKS_INSTALLED_CANT_LAUNCH_SERVER_TEXT );
            }
            closeApp();
        }
    }

    /**
     * Configure the launcher application to use the logging utility class for output to file and console.
     *
     * @since 2.0
     */
    public static void configureLogger() {
        Timestamp logTimeStamp = new Timestamp( System.currentTimeMillis() );
        File logFile = SynchronizedFileManager.getSynchronizedFile( LocalPathManager.getLauncherLogFolderPath() +
                                                                            File.separator +
                                                                            LauncherConstants.LAUNCHER_APPLICATION_NAME_TRIMMED +
                                                                            "_" +
                                                                            GameModeManager.getCurrentGameMode()
                                                                                           .getStringName() +
                                                                            "_" +
                                                                            LocalPathConstants.LOG_FILE_NAME_DATE_FORMAT.format(
                                                                                    logTimeStamp ) +
                                                                            LocalPathConstants.LOG_FILE_EXTENSION );
        try {
            Logger.initLogSys( logFile );
        }
        catch ( IOException e ) {
            Logger.logError( LocalizationManager.ERROR_CONFIGURING_LOG_SYSTEM_TEXT );
            Logger.logThrowable( e );
        }
    }

    /**
     * Performs login when the launcher is in client mode. If a user is remembered, it will be loaded from memory and
     * logged in automatically. If a user is not remembered or cannot be logged in automatically, the login screen will
     * display.
     *
     * @param initialErrorMessage error message to display on the login screen (e.g. from a
     *                            failed auto-renewal); may be {@code null} or empty. May be
     *                            replaced internally with a refresh-failure message if a saved
     *                            account could not be renewed.
     *
     * @since 2.0
     */
    public static void performClientLogin( String initialErrorMessage ) {
        // Fast cold-start path: if a saved account exists AND we can read the
        // cached user info synchronously, populate the in-memory state from
        // disk + kick off a background token refresh. The main GUI paints
        // with the cached user immediately while the network round-trip to
        // the auth servers runs in parallel; the Play-click handler awaits
        // the pending refresh future (with a brief progress modal) before
        // launching the game.
        //
        // Why this works: the cached_user.json file holds uuid + display
        // name (everything the main GUI's player chip needs) plus an access
        // token. The token may be stale (>4h since last server contact) but
        // the user doesn't care until Play. The async refresh either lands
        // the new token in time (common case) or surfaces a session-expired
        // error at Play-time (rare).
        if ( MCLauncherAuthManager.hasExistingLogin() ) {
            net.hycrafthd.minecraft_authenticator.login.User cached =
                    MCLauncherAuthManager.loadCachedUserNow();
            if ( cached != null ) {
                Logger.logStd( LocalizationManager.format( "log.launcherCore.cachedSessionLoaded", cached.name() ) );
                MCLauncherAuthManager.renewExistingLoginAsync();
                return;
            }
            // Cache unreadable or missing fields → fall through to the legacy
            // sync renewal path. This is the cold-uninstalled, fresh-install,
            // or corrupt-cache case; rare in steady state but the existing
            // progress-GUI flow handles it cleanly.
            Logger.logStd( LocalizationManager.get( "log.launcherCore.cachedUserUnreadable" ) );

            MCLauncherProgressGui authProgressWindow = null;
            try {
                if ( MCLauncherGuiController.shouldCreateGui() ) {
                    authProgressWindow = MCLauncherGuiController.goToProgressGui();
                }
            }
            catch ( IOException e ) {
                Logger.logError( LocalizationManager.get( "log.launcherCore.authRenewalProgressGuiFailed" ) );
                Logger.logThrowable( e );
            }
            if ( authProgressWindow != null ) {
                authProgressWindow.setUpperLabelText( LocalizationManager.get( "auth.progress.signingIn" ) );
                authProgressWindow.setSectionText( LocalizationManager.get( "auth.progress.checkingSession" ) );
                authProgressWindow.setDetailText( "" );
            }

            MCLauncherProgressGui finalAuthProgressWindow = authProgressWindow;
            MCLauncherAuthManager.setStatusCallback( ( section, detail ) -> {
                if ( finalAuthProgressWindow != null ) {
                    finalAuthProgressWindow.setSectionText( section );
                    finalAuthProgressWindow.setDetailText( detail );
                }
            } );

            MCLauncherAuthResult authResult = MCLauncherAuthManager.renewExistingLogin();
            MCLauncherAuthManager.setStatusCallback( null );
            boolean authSuccess = AuthUtilities.checkAuthResponse( authResult );

            if ( authSuccess ) {
                Logger.logStd( "[" +
                                       authResult.getMinecraftUser().name() +
                                       "] " +
                                       LocalizationManager.WAS_LOGGED_IN_TO_LAUNCHER_TEXT );
                return;
            }
            else {
                Logger.logStd( LocalizationManager.get( "log.launcherCore.savedAccountNotRenewed" ) );
                MCLauncherAuthManager.logout();
                if ( initialErrorMessage == null || initialErrorMessage.isEmpty() ) {
                    initialErrorMessage = LocalizationManager.AUTH_UNABLE_TO_REFRESH_TEXT;
                }
            }
        }
        else {
            Logger.logStd( LocalizationManager.REMEMBERED_ACCOUNT_NOT_FOUND_SHOWING_LOGIN );
        }

        // Show login screen (either no saved account, or renewal failed)
        MCLauncherLoginGui loginWindow;
        try {
            loginWindow = MCLauncherGuiController.goToLoginGui();

            if ( initialErrorMessage != null && !initialErrorMessage.isEmpty() ) {
                Logger.logError( initialErrorMessage );
            }

            // Wait for login screen to complete
            try {
                loginWindow.waitForLoginSuccess();
            }
            catch ( InterruptedException e ) {
                Logger.logError( LocalizationManager.UNABLE_WAIT_PENDING_LOGIN_TEXT );
                Logger.logThrowable( e );
                closeApp();
            }
        }
        catch ( IOException e ) {
            Logger.logError( LocalizationManager.get( "log.launcherCore.loginGuiLoadFailed" ) );
            Logger.logThrowable( e );
            closeApp();
        }
    }

    /**
     * Parses the launcher application arguments and returns the initial mod pack selection name if specified.
     *
     * @param args launcher application arguments
     *
     * @return initial mod pack selection (if specified, else empty string)
     *
     * @since 2.0
     */
    /**
     * Headless diagnostic for the per-pack Minecraft library-manifest
     * memoization (see {@code GameModPack.getMinecraftLibraryManifest}). Fetches
     * the pack at {@code manifestUrl} without authenticating, resolves its
     * library manifest twice, and reports whether the second resolution returns
     * the same cached instance — i.e. whether the version's client.json is parsed
     * once rather than twice. Prints a PASS/FAIL line to the console and exits;
     * never authenticates or launches the game.
     *
     * @param manifestUrl a modpack manifest URL, or a {@code file:} URL to a
     *                    local manifest
     *
     * @since 2026.6
     */
    private static void runManifestDiagnostic( String manifestUrl ) {
        final java.io.PrintStream out = System.out;
        // Client mode so LocalPathManager / config resolve to the real launcher
        // folder rather than the server-mode (cwd) paths.
        GameModeManager.setCurrentGameMode( GameMode.CLIENT );
        out.println( "[manifest-diag] resolving manifest: " + manifestUrl );
        try {
            com.micatechnologies.minecraft.launcher.game.modpack.GameModPack pack =
                    com.micatechnologies.minecraft.launcher.game.modpack.GameModPackFetcher.get( manifestUrl, false );
            if ( pack == null ) {
                out.println( "[manifest-diag] FAILED: could not fetch / parse the manifest" );
                System.exit( 2 );
                return;
            }
            out.println( "[manifest-diag] pack: " + pack.getFriendlyName()
                                 + "  (MC " + pack.getMinecraftVersion() + ")" );

            long t0 = System.nanoTime();
            com.micatechnologies.minecraft.launcher.game.modpack.manifests.GameLibraryManifest m1 =
                    pack.getMinecraftLibraryManifest();
            long t1 = System.nanoTime();
            com.micatechnologies.minecraft.launcher.game.modpack.manifests.GameLibraryManifest m2 =
                    pack.getMinecraftLibraryManifest();
            long t2 = System.nanoTime();

            boolean memoized = ( m1 == m2 );
            out.printf( "[manifest-diag] 1st resolve: %.1f ms (downloads + parses client.json)%n",
                        ( t1 - t0 ) / 1e6 );
            out.printf( "[manifest-diag] 2nd resolve: %.3f ms%n", ( t2 - t1 ) / 1e6 );
            out.println( "[manifest-diag] same instance (memoized -> parsed once): " + memoized );
            out.println( memoized
                                 ? "[manifest-diag] RESULT: PASS - library manifest resolved once per pack"
                                 : "[manifest-diag] RESULT: FAIL - second resolve rebuilt the manifest (re-parse)" );
            System.exit( memoized ? LauncherConstants.EXIT_STATUS_CODE_GOOD : 1 );
        }
        catch ( Throwable t ) {
            out.println( "[manifest-diag] ERROR: " + t );
            t.printStackTrace( out );
            System.exit( 2 );
        }
    }

    public static String parseLauncherArgs( String[] args ) {
        // Parsing is a pure function (LauncherArgs.parse); this method only applies the
        // side effects it decides on. Keeping the grammar out of here is what makes it
        // testable — see LauncherArgsTest.
        LauncherArgs parsed = LauncherArgs.parse(
                args, com.micatechnologies.minecraft.launcher.tui.TuiMode.isEnabled() );

        // Stash any mmcl:// deep-link for the session to dispatch once the main GUI is up.
        // Deliberately not dispatched here: the user must still flow through auth and the
        // mod-pack-info fetch first (e.g. mmcl://add needs the installed list populated).
        if ( parsed.hasPendingUri() ) {
            setPendingLauncherUri( parsed.pendingUri() );
        }

        switch ( parsed.modeAction() ) {
            case CLIENT -> GameModeManager.setCurrentGameMode( GameMode.CLIENT );
            case SERVER -> GameModeManager.setCurrentGameMode( GameMode.SERVER );
            case INFER -> GameModeManager.inferGameMode();
            // NONE: the bare "launcher.jar <modpack_name>" form has never set a game mode.
            // Preserved as-is; see LauncherArgs.ModeAction.NONE.
            case NONE -> { }
        }

        if ( parsed.invalid() ) {
            Logger.logError( LocalizationManager.INVALID_ARGS_SPECIFIED_TEXT +
                                     "\n" +
                                     LocalizationManager.USAGE_TEXT +
                                     ": launcher.jar [ -s [modpack_name] | -c" +
                                     " " +
                                     "[modpack_name] | " +
                                     "modpack_name ]" );
            closeApp();
        }

        return parsed.modPackSelection();
    }

    /**
     * Applies the global JVM properties required for the launcher.
     *
     * @since 1.0
     */
    public static void applySystemProperties() {
        LauncherConstants.JVM_PROPERTIES.forEach( System::setProperty );

        // macOS-only: force the Metal prism pipeline. JFX 26 makes Metal the default
        // on macOS so this is technically redundant on the supported runtime, but
        // it's harmless belt-and-suspenders against accidental runs on an older
        // bundled JFX. The es2 (OpenGL) backend has long-standing transparent-
        // backbuffer bugs on Apple Silicon that produce alpha accumulation on every
        // translucent surface — exactly the symptom that disappeared once Metal took
        // over on the JFX 26 upgrade.
        //
        // prism.order is a comma-separated preference list; mtl,es2,sw means "try
        // Metal first, fall back to es2 or software if unavailable." Must be set
        // before any JFX init -- this method runs before Platform.startup.
        if ( SystemUtils.IS_OS_MAC ) {
            System.setProperty( "prism.order", "mtl,es2,sw" );
        }

        // Initialize user model ID
        try {
            if ( SystemUtils.IS_OS_WINDOWS ) {
                String appUserModelId = LauncherConstants.LAUNCHER_IS_DEV ?
                                        LauncherCore.class.getCanonicalName() + "DEV" :
                                        LauncherCore.class.getCanonicalName();
                Logger.logDebug( LocalizationManager.format( "log.launcherCore.settingAppUserModelId",
                                                             appUserModelId ) );
                WString appUserModelIdWString = new WString( appUserModelId );
                Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID( appUserModelIdWString );
            }
        }
        catch ( Exception e ) {
            Logger.logErrorSilent( LocalizationManager.get( "log.launcherCore.appUserModelIdFailed" ) );
            Logger.logThrowable( e );
        }

        // Register macOS application-menu handlers (About / Preferences / Quit). No-op on
        // Windows and Linux. Must happen before any GUI shows so the system menu reflects
        // these callbacks the moment the first window opens.
        com.micatechnologies.minecraft.launcher.gui.SystemMenuBarManager.installDesktopHandlers();

        // Idempotently register the mmcl:// URL scheme + .mmcjson file extension with the OS
        // so website "Open in Desktop Launcher" links and double-clicks on .mmcjson files
        // route through us. Async — Linux's optional update-desktop-database sub-process can
        // take a hundred ms and we'd rather not delay the splash. Dev mode / non-jpackage
        // launches are no-ops inside SchemeRegistrar.
        SystemUtilities.spawnNewTask( SchemeRegistrar::registerIfNeeded );
    }

    /**
     * Performs launcher closing/clean up tasks necessary for application shut down or restart. This method must be able
     * to be called and complete without waiting at all times.
     *
     * @since 2.0
     */
    public static void cleanupApp() {
        Logger.logStd( LocalizationManager.PERFORMING_APP_CLEANUP_TEXT );
        // Every step runs on its own: one that throws is logged and the rest still run, so a
        // failing RGB backend can no longer skip the config flush or keep the instance lock.
        //
        // Tear down the RGB subsystem first so backends paint their final black frames +
        // close sockets before the JVM exits, leaving the user's keyboard on a sensible state.
        // RGB, MCP and Discord call into vendor SDKs and sockets that can hang (an OpenRGB
        // socket write has no timeout), so each gets a bounded wait instead of being able to
        // hang the exit with the window still up.
        cleanupStepBounded( "rgb", com.micatechnologies.minecraft.launcher.rgb.RgbIntegration::shutdown );
        // Stop the MCP listener and delete its endpoint file. A file left behind would
        // point a client at a port this process no longer owns, carrying a bearer token
        // whatever now listens there never issued. Harmless when the server never started.
        cleanupStepBounded( "mcp", com.micatechnologies.minecraft.launcher.mcp.McpBootstrap::stop );
        cleanupStepBounded( "discord", DiscordRpcUtility::exit );
        // Release the shared taskbar wrapper before tearing down the GUI controller —
        // closing it after the stage is gone occasionally leaves the COM thread blocked
        // on a stale HWND lookup. Doing it here also clears the taskbar overlay so a
        // restart doesn't briefly inherit the previous session's progress state.
        cleanupStep( "taskbar", com.micatechnologies.minecraft.launcher.utilities.TaskbarProgressManager::shutdown );
        // Remove the notification tray icon. Without this, the icon would persist in the
        // tray after launcher exit until the user clicks it (Windows behavior).
        cleanupStep( "notifications", com.micatechnologies.minecraft.launcher.utilities.NotificationManager::shutdown );
        cleanupStep( "gui", MCLauncherGuiController::exit );
        // Tear down the help window's static singleton state so a subsequent
        // restartApp doesn't reuse a Stage whose Owner is now closed and a
        // WebView whose internal state was wired up against the previous
        // session's GUI window. Idempotent if the help window was never
        // opened. Must run AFTER MCLauncherGuiController.exit() because
        // the help window may transitively reference the main stage via
        // initOwner; tearing it down last keeps the close order stable.
        cleanupStep( "helpWindow", com.micatechnologies.minecraft.launcher.gui.MCLauncherHelpWindow::cleanup );
        // Drain in-flight background tasks (manifest cache writes, log flushes
        // queued by spawnNewTask, etc.) before tearing down the logger so any
        // last-second I/O actually lands. Bounded wait — daemon-thread semantics
        // clean up whatever is still running past the timeout.
        cleanupStep( "backgroundTasks", () -> SystemUtilities.shutdownBackgroundExecutor( 2_000 ) );
        // Explicit config flush BEFORE logger shutdown. The ConfigStore
        // shutdown hook is the original safety net, but on Windows the
        // sequence "System.exit → daemon-thread death races with the
        // shutdown-hook flush" has been observed to lose the user's
        // last-50ms config changes (added modpacks, wizard completion).
        // Calling here guarantees the pending debounced write lands
        // while logging is still alive and we have full control over
        // the timing.
        cleanupStep( "config", com.micatechnologies.minecraft.launcher.config.ConfigManager::flushPendingWrite );
        // Release the single-instance lock only once the config is on disk: a launcher started
        // the moment the lock frees (a quick relaunch) would otherwise read the config while
        // this one is still writing it.
        cleanupStep( "instanceLock", SingleInstanceLock::release );
        cleanupStep( "logging", Logger::shutdownLogSys );
        Logger.logStd( LocalizationManager.FINISHED_APP_CLEANUP_TEXT );
    }

    /** How long a cleanup step that calls into a vendor SDK or socket may take before shutdown
     *  moves on without it. */
    private static final long CLEANUP_STEP_TIMEOUT_MS = 3_000L;

    /** One step of {@link #cleanupApp()}; may throw, which is logged and skipped. */
    @FunctionalInterface
    private interface CleanupStep
    {
        void run() throws Exception;
    }

    /**
     * Runs one cleanup step, logging rather than propagating anything it throws, so the
     * remaining steps still run.
     *
     * @param name names the step in the log
     * @param step the step
     */
    private static void cleanupStep( String name, CleanupStep step ) {
        try {
            step.run();
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.launcherCore.cleanupStepFailed", name,
                                                                 String.valueOf( t ) ) );
        }
    }

    /**
     * Runs one cleanup step with a bounded wait (see {@link SystemUtilities#runBounded}), so a hung
     * vendor SDK or socket cannot hang the launcher's exit or restart.
     *
     * @param name names the step in the log
     * @param step the step
     */
    private static void cleanupStepBounded( String name, Runnable step ) {
        boolean finished = SystemUtilities.runBounded( name, step, CLEANUP_STEP_TIMEOUT_MS,
                t -> Logger.logWarningSilent( LocalizationManager.format( "log.launcherCore.cleanupStepFailed",
                                                                          name, String.valueOf( t ) ) ) );
        if ( !finished ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.launcherCore.cleanupStepTimedOut", name,
                                                                 CLEANUP_STEP_TIMEOUT_MS ) );
        }
    }

    /**
     * Performs launcher closing tasks and the restarts the launcher application. This method must be able to be called
     * and complete without waiting at all times.
     *
     * @since 2.0
     */
    public static void restartApp() {
        restartAppWithError( null );
    }

    /**
     * Performs launcher closing tasks and the restarts the launcher application with the specified error reason. This
     * method must be able to be called and complete without waiting at all times.
     *
     * @param restartErrorString error reason to carry into the restarted session, or
     *                           {@code null} for a clean restart with no error
     *
     * @since 2.0
     */
    public static void restartAppWithError( String restartErrorString ) {
        if ( !lifecycleTransition.compareAndSet( false, true ) ) {
            Logger.logDebug( LocalizationManager.get( "log.launcherCore.lifecycleTransitionIgnored" ) );
            return;
        }
        restartFlag = true;
        restartError = restartErrorString;
        // Same FX-thread hazard as closeApp() above: cleanupApp() makes AWT calls that
        // dispatch_sync to AppKit (Taskbar, SystemTray, Stage.close via JFXPlatformRun),
        // and on macOS the JavaFX Application Thread IS the AppKit main thread, so any
        // dispatch_sync to AppKit from the FX thread deadlocks. The Settings screen's
        // Logout button reproduced this exactly — clicking Confirm called restartApp()
        // straight from the FX-thread onAction handler, the launcher logged "Performing
        // application cleanup..." and froze until SIGTERM. Hop to a fresh thread when
        // called from FX so the dispatch_sync targets land on AppKit through the
        // normal cross-thread path. Background-pool callers hop too (cleanup shuts the
        // pool down); other threads run the restart inline.
        runLifecycleTransition( LauncherCore::restartAppNow, "Launcher-Restart" );
    }

    /**
     * Set while the launcher is closing, restarting or relaunching, so a second request (two
     * Exit clicks, Exit during a restart) is ignored instead of running cleanup twice. Cleared
     * at the top of each restart-loop iteration.
     */
    private static final AtomicBoolean lifecycleTransition = new AtomicBoolean( false );

    /**
     * Runs a close or restart. Callers on the FX thread or on a {@link SystemUtilities#spawnNewTask}
     * worker get a fresh dedicated thread: the FX thread because cleanup's AppKit calls deadlock
     * there on macOS, and a pool worker because cleanup shuts the pool down, which interrupts the
     * worker running it and aborts its interruptible config write. Any other caller (the main or
     * session thread) runs it inline, since those callers rely on the exit happening before they
     * carry on.
     *
     * @param work       the close or restart
     * @param threadName the dedicated thread's name
     */
    private static void runLifecycleTransition( Runnable work, String threadName ) {
        if ( javafx.application.Platform.isFxApplicationThread() || SystemUtilities.isBackgroundWorker() ) {
            Thread worker = new Thread( work, threadName );
            worker.setDaemon( false );
            worker.start();
            return;
        }
        work.run();
    }

    /**
     * Synchronously performs the restart: runs {@link #cleanupApp()} then releases the current
     * session's exit latch so the {@link #main(String[])} restart loop iterates. Expects
     * {@link #restartFlag} to already have been set by the caller. Invoked directly by
     * background-thread callers and on a dedicated thread for FX-thread callers (see
     * {@link #restartAppWithError(String)}).
     */
    private static void restartAppNow() {
        cleanupApp();
        currentSession.exitLatch.countDown();
    }

    /**
     * Fully relaunches the launcher in a <em>new</em> process. Required when a
     * change must be picked up by state that is bound at JVM class-load time and
     * cannot be reset in-process — specifically a language change:
     * {@link com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager}
     * caches its resource bundle and binds ~89 {@code static final} translation
     * fields the first time the class loads, against the launch-time locale. The
     * in-process {@link #restartApp()} loop reuses the same JVM, so those stay
     * stuck and the UI only partially re-localizes. A genuine process restart
     * re-runs class init and binds every string to the new locale.
     *
     * <p>Only possible when the launcher knows its own executable path — jpackage
     * installs expose it via the {@code jpackage.app-path} system property. When
     * that's unavailable (running from a raw JAR or the IDE), there's no stable
     * exe to respawn, so this falls back to the in-process {@link #restartApp()};
     * a developer iterating in the IDE keeps the documented static-final-locale
     * limitation and can do a real restart manually.</p>
     *
     * <p><b>Always</b> runs on a fresh dedicated thread — never the FX thread
     * (macOS AppKit dispatch-sync deadlock, same as {@link #restartApp}) and,
     * crucially, never a background-executor worker. The Settings handler calls
     * this from {@code SystemUtilities.spawnNewTask}, i.e. an executor thread;
     * {@link #cleanupApp()} shuts that pool down, and its {@code shutdownNow()}
     * would interrupt the calling thread, aborting the interruptible-FileChannel
     * config flush ({@code ConfigStore.writeNow}) before the spawned process
     * reads the new locale override from disk — which manifested as "the language
     * didn't change at all" after a relaunch. A dedicated thread is immune to
     * that pool shutdown.</p>
     *
     * @since 2026.6
     */
    public static void relaunchApp() {
        if ( !lifecycleTransition.compareAndSet( false, true ) ) {
            Logger.logDebug( LocalizationManager.get( "log.launcherCore.lifecycleTransitionIgnored" ) );
            return;
        }
        Thread relauncher = new Thread( LauncherCore::relaunchAppNow, "Launcher-Relaunch" );
        relauncher.setDaemon( false );
        relauncher.start();
    }

    /**
     * Performs the out-of-process relaunch on the dedicated relaunch thread: flushes config to
     * disk, resolves the installed executable path, and either spawns a fresh process and exits
     * the current JVM or — when no installed exe is available (dev / raw-JAR / IDE) — falls back
     * to the in-process {@link #restartAppNow()} path. On a post-cleanup spawn failure it
     * re-enters the in-process restart loop rather than leaving the user with a dead launcher.
     * See {@link #relaunchApp()} for the thread-confinement rationale.
     */
    private static void relaunchAppNow() {
        // Persist config to disk NOW, up front, on this un-interrupted dedicated
        // thread — the spawned process reads the locale override (and the rest of
        // config) from disk, so the write must be durable before anything is torn
        // down or respawned. (cleanupApp flushes too, but doing it here makes the
        // durability ordering explicit and independent of cleanup internals.)
        com.micatechnologies.minecraft.launcher.config.ConfigManager.flushPendingWrite();

        String exePath = SchemeRegistrar.resolveLauncherExePath();
        if ( exePath == null || exePath.isBlank() ) {
            // No installed exe to respawn (dev / raw-JAR / IDE run). Fall back to
            // the in-process restart — dynamic strings re-resolve on the rebuilt
            // GUI, but the static-final translation fields stay at the launch
            // locale until a real process restart (an accepted dev-only gap).
            Logger.logStd( LocalizationManager.get( "log.launcherCore.relaunchUnavailable" ) );
            restartFlag = true;
            restartAppNow();
            return;
        }
        // Installed app: tear this instance down (which releases the
        // single-instance lock and flushes config + logging), spawn a fresh
        // process, then exit. The new JVM re-runs class init so the changed
        // locale binds everywhere.
        Logger.logStd( LocalizationManager.format( "log.launcherCore.relaunchingProcess", exePath ) );
        cleanupApp();
        if ( spawnRelaunchProcess( exePath ) ) {
            System.exit( LauncherConstants.EXIT_STATUS_CODE_GOOD );
        }
        else {
            // Spawn failed after cleanup — don't leave the user with a dead
            // launcher. Re-enter the restart loop instead (Phase 2's loop-top
            // tryAcquire re-establishes the lock + IPC, and the new session
            // reconfigures logging).
            Logger.logError( LocalizationManager.get( "log.launcherCore.relaunchSpawnFailedFallback" ) );
            restartFlag = true;
            currentSession.exitLatch.countDown();
        }
    }

    /**
     * Resets the launcher: deletes its folder (config, accounts, packs, runtimes, logs) and starts
     * it again in a new process. Called from Settings, which first refuses while a game runs.
     *
     * <p>Two things make this safe where the old delete-then-restart-in-process was not. The
     * restart is a new JVM, so nothing held in memory (the config store, signed-in accounts) can
     * be written back over the reset. And the folder is first renamed aside in one step: on
     * Windows a file still open inside it makes the rename fail with nothing touched, instead of
     * the delete failing half-way and leaving a broken tree. A failed rename restarts the launcher
     * as it was and reports the failure on the next screen, since the GUI and log are already
     * torn down by then.</p>
     *
     * <p>Without an installed executable to start (a run from the IDE or a raw JAR), the launcher
     * says the reset is done and exits for the user to start it again.</p>
     *
     * @since 2026.10
     */
    public static void resetLauncherAndRelaunch() {
        if ( !lifecycleTransition.compareAndSet( false, true ) ) {
            Logger.logDebug( LocalizationManager.get( "log.launcherCore.lifecycleTransitionIgnored" ) );
            return;
        }
        // A dedicated thread for the same reasons as relaunchApp: never the FX thread, and never
        // a background-pool worker, whose pool cleanupApp shuts down.
        Thread resetter = new Thread( LauncherCore::resetLauncherNow, "Launcher-Reset" );
        resetter.setDaemon( false );
        resetter.start();
    }

    /** Does the work of {@link #resetLauncherAndRelaunch()} on its dedicated thread. */
    private static void resetLauncherNow() {
        // Closes the GUI, stops RGB/Discord/MCP, flushes the config and closes the log, so
        // nothing of this process still has a file open in the folder.
        cleanupApp();
        java.nio.file.Path root = java.nio.file.Paths.get( LocalPathManager.getLauncherLocalPath() )
                                                     .toAbsolutePath().normalize();
        java.nio.file.Path movedAside;
        try {
            movedAside = moveAsideForReset( root, System.currentTimeMillis() );
        }
        catch ( IOException | RuntimeException e ) {
            // Nothing was deleted, so the in-memory state still matches the disk and an
            // in-process restart is safe. The new session shows the error.
            restartFlag = true;
            restartError = LocalizationManager.format( "settings.resetLauncher.failed", String.valueOf( e ) );
            currentSession.exitLatch.countDown();
            return;
        }
        if ( movedAside != null ) {
            try {
                org.apache.commons.io.FileUtils.deleteDirectory( movedAside.toFile() );
            }
            catch ( IOException | RuntimeException e ) {
                // The launcher folder itself is already gone; a leftover renamed copy is harmless.
                Logger.logWarningSilent( LocalizationManager.format( "log.launcherCore.resetLeftover",
                                                                     movedAside.toString() ) );
            }
        }

        String exePath = SchemeRegistrar.resolveLauncherExePath();
        if ( exePath != null && !exePath.isBlank() && spawnRelaunchProcess( exePath ) ) {
            System.exit( LauncherConstants.EXIT_STATUS_CODE_GOOD );
            return;
        }
        // No installed executable, or it would not start. An in-process restart would keep the
        // old config and accounts in memory and write them back, so ask the user to start the
        // launcher again instead. The FX toolkit outlives the closed window, so the dialog shows.
        GUIUtilities.showWarningMessage( LocalizationManager.get( "settings.resetLauncher.startAgain" ), null );
        System.exit( LauncherConstants.EXIT_STATUS_CODE_GOOD );
    }

    /**
     * Renames the launcher folder to a sibling ({@code <name>.reset-<stamp>}) in one step, for the
     * reset to delete. A rename either moves the whole tree or nothing, so a file still in use
     * fails the reset cleanly instead of leaving it half-deleted.
     *
     * @param root  the launcher folder
     * @param stamp makes the sibling's name unique
     *
     * @return the renamed folder, or {@code null} when {@code root} does not exist
     *
     * @throws IOException when the folder cannot be renamed (on Windows, typically a file in use)
     *
     * @since 2026.10
     */
    static java.nio.file.Path moveAsideForReset( java.nio.file.Path root, long stamp ) throws IOException {
        if ( !java.nio.file.Files.exists( root ) ) {
            return null;
        }
        java.nio.file.Path aside = root.resolveSibling( root.getFileName() + ".reset-" + stamp );
        java.nio.file.Files.move( root, aside );
        return aside;
    }

    /**
     * Spawns a fresh, fully-independent launcher process for a relaunch.
     *
     * <p>Prefers the OS shell-execute path (Windows {@code cmd /c start} /
     * {@code Desktop.open}) so the new instance launches as a top-level process
     * exactly like a double-click — NOT as a console-inheriting child of this
     * dying JVM. The child-of-a-GUI-process spawn differs in foreground / window
     * activation / handle inheritance, and that left the Windows custom title-bar
     * chrome only partially applied on the relaunched window (the OS caption strip
     * showed through alongside our own navbar — a double title bar). Falls back to
     * a detached {@link ProcessBuilder} with discarded stdio if shell-execute is
     * unavailable.
     *
     * @param exePath absolute path to the installed launcher executable to respawn
     *
     * @return {@code true} if a new process was started
     */
    private static boolean spawnRelaunchProcess( String exePath ) {
        java.io.File exe = new java.io.File( exePath );
        java.io.File workingDir = exe.getParentFile();

        // Windows: relaunch via the shell so it's a true top-level launch. The empty
        // "" is start's mandatory title argument; ProcessBuilder quotes the path.
        if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
            try {
                ProcessBuilder pb = new ProcessBuilder( "cmd", "/c", "start", "", exePath );
                if ( workingDir != null && workingDir.isDirectory() ) {
                    pb.directory( workingDir );
                }
                pb.start();
                return true;
            }
            catch ( IOException e ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.launcherCore.cmdStartRelaunchFailed",
                                                                     e.getClass().getSimpleName() ) );
            }
            // Secondary: ShellExecute via AWT Desktop (also a double-click-equivalent launch).
            try {
                if ( java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop().isSupported( java.awt.Desktop.Action.OPEN ) ) {
                    java.awt.Desktop.getDesktop().open( exe );
                    return true;
                }
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.launcherCore.desktopOpenRelaunchFailed",
                                                                     t.getClass().getSimpleName() ) );
            }
        }

        // Non-Windows, or Windows shell paths unavailable: detached direct spawn with
        // discarded stdio so the child isn't tied to the dying parent's pipes.
        try {
            ProcessBuilder pb = new ProcessBuilder( exePath );
            if ( workingDir != null && workingDir.isDirectory() ) {
                pb.directory( workingDir );
            }
            pb.redirectOutput( ProcessBuilder.Redirect.DISCARD );
            pb.redirectError( ProcessBuilder.Redirect.DISCARD );
            pb.start();
            return true;
        }
        catch ( IOException e ) {
            Logger.logError( LocalizationManager.format( "log.launcherCore.relaunchSpawnFailed", e.getMessage() ) );
            return false;
        }
    }

    /**
     * Reports {@code true} when every step in {@code tracker} is in a terminal
     * state (DONE / FAILED / SKIPPED). Used by the launch progress listener to
     * decide when to fire the "ready to play" toast — the listener is invoked
     * once per row transition, but the toast only matters on the very last one.
     *
     * <p>Returns {@code false} if any row is still PENDING or RUNNING. A FAILED
     * row still counts as terminal because the launch is over either way — but
     * the toast text and downstream UX should also gate on "no failures" if
     * we ever want to suppress the success message on partial failures. Today
     * a failed step throws out of buildClasspath and the launch unwinds, so
     * the toast can't fire in that scenario anyway.</p>
     *
     * @param tracker the launch progress tracker to inspect; a {@code null} tracker
     *                reports as not-completed
     *
     * @return {@code true} when every step is terminal (DONE / FAILED / SKIPPED),
     *         {@code false} if any step is still PENDING or RUNNING
     */
    private static boolean allStepsCompleted(
            com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker tracker )
    {
        if ( tracker == null ) return false;
        for ( var step : tracker.steps() ) {
            switch ( step.state() ) {
                case PENDING, RUNNING -> { return false; }
                default -> { /* DONE / FAILED / SKIPPED — counts as terminal */ }
            }
        }
        return true;
    }

    /**
     * Attaches a Logger-backed listener to the launch tracker for headless launches (server mode,
     * or a client whose GUI failed to build). Without it the tracker's step transitions and
     * per-file download progress have no consumer, so the entire download/verify phase runs in
     * total silence — a large mod re-sync (e.g. the post-wipe recovery re-downloading every mod)
     * is indistinguishable in the log from a hung launcher.
     *
     * <p>State transitions (RUNNING / DONE / FAILED) always log. Sub-text detail (per-file
     * progress, retry notices) is throttled to one line per step per few seconds so a byte-level
     * progress feed doesn't flood the log.</p>
     *
     * @param tracker the launch tracker to mirror into the log
     *
     * @since 2026.7
     */
    private static void attachHeadlessTrackerLogging(
            com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker tracker )
    {
        final long detailIntervalMs = 3_000L;
        final java.util.concurrent.ConcurrentHashMap<
                com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker.StepId,
                com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker.State > lastState =
                new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.ConcurrentHashMap<
                com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker.StepId,
                java.util.concurrent.atomic.AtomicLong > lastDetailLogMs =
                new java.util.concurrent.ConcurrentHashMap<>();
        tracker.addListener( step -> {
            // State transitions always log — they're the launch's structural milestones.
            var prev = lastState.put( step.id(), step.state() );
            if ( prev != step.state() ) {
                if ( step.state() == com.micatechnologies.minecraft.launcher.game.modpack
                        .LaunchProgressTracker.State.FAILED ) {
                    Logger.logErrorSilent( LocalizationManager.format( "log.launch.headless.stepFailed",
                                                                       step.displayLabel(),
                                                                       step.errorMessage() != null
                                                                               ? step.errorMessage() : "" ) );
                }
                else {
                    Logger.logStd( LocalizationManager.format( "log.launch.headless.stepState",
                                                               step.displayLabel(), step.state() ) );
                }
                return;
            }
            // Same state — this is a progress/sub-text tick. Throttle per step so the
            // byte-level download feed becomes a readable heartbeat, not a flood.
            String sub = step.subText();
            if ( sub == null || sub.isEmpty() ) {
                return;
            }
            long now = System.currentTimeMillis();
            java.util.concurrent.atomic.AtomicLong last = lastDetailLogMs.computeIfAbsent(
                    step.id(), k -> new java.util.concurrent.atomic.AtomicLong( 0 ) );
            long prevMs = last.get();
            if ( now - prevMs >= detailIntervalMs && last.compareAndSet( prevMs, now ) ) {
                Logger.logStd( LocalizationManager.format( "log.launch.headless.stepDetail",
                                                           step.displayLabel(), sub ) );
            }
        } );
    }

    /**
     * Performs launcher closing tasks and then closes the launcher application. This method must be able to be called
     * and complete without waiting at all times.
     *
     * @since 1.1
     */
    public static void closeApp() {
        // When invoked from the FX thread, run cleanup on a fresh background thread.
        //
        // On macOS, the JavaFX Application Thread IS the AppKit main thread. Several
        // cleanup steps make AWT calls that dispatch_sync to AppKit:
        //   - MacOsDockManager.shutdown() → Taskbar.setProgressValue/-setMenu (lazy-init'd
        //     during the modpack-load progress updates, so it's live by cleanup time).
        //   - NotificationManager.shutdown() → SystemTray.remove(trayIcon).
        //   - Stage.close() → Glass MacWindow → NSWindow close on AppKit.
        // Running them on the FX/AppKit thread deadlocks the dispatch_sync to self.
        // A Platform.runLater hop just re-enters the same thread on a later tick — no
        // help. Off-thread, the dispatches go through normally; internal JFXPlatformRun
        // calls inside cleanup marshal back to FX for the parts (Stage.close) that need
        // it. Windows JFX thread isn't coupled to AppKit, so the same path works there.
        // A background-pool caller (requestQuit, the Settings close prompt) gets its own
        // thread too: cleanup shuts that pool down and would interrupt itself.
        if ( !lifecycleTransition.compareAndSet( false, true ) ) {
            Logger.logDebug( LocalizationManager.get( "log.launcherCore.lifecycleTransitionIgnored" ) );
            return;
        }
        runLifecycleTransition( LauncherCore::closeAppNow, "Launcher-Close" );
    }

    /**
     * Synchronously performs application shutdown: runs {@link #cleanupApp()}, releases the
     * current session's exit latch, logs the farewell line, and terminates the JVM with the
     * good exit status. Invoked directly by background-thread callers and on a dedicated thread
     * for FX-thread callers (see {@link #closeApp()}).
     */
    private static void closeAppNow() {
        cleanupApp();
        currentSession.exitLatch.countDown();
        Logger.logStd( LocalizationManager.SEE_YOU_SOON_TEXT );
        System.exit( LauncherConstants.EXIT_STATUS_CODE_GOOD );
    }
}
