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
import com.micatechnologies.minecraft.launcher.gui.MCLauncherGameConsoleGui;
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

        // Enforce single instance. If another instance is already running:
        //   - and we have a mmcl:// URI in argv, forward it to the running instance and exit
        //     silently (the running instance brings itself to focus and dispatches the action).
        //   - otherwise, show the existing "already running" popup so the user understands
        //     why nothing happened.
        if ( !SingleInstanceLock.tryAcquire() ) {
            for ( String arg : args ) {
                if ( LauncherUriHandler.isLauncherUri( arg ) ) {
                    boolean forwarded = SingleInstanceLock.forwardToRunningInstance( arg );
                    System.exit( forwarded ? 0 : 1 );
                    return;
                }
            }
            // Terminal mode: a Swing popup would be wrong (and may not show over SSH). Print to the
            // real console and exit cleanly.
            if ( com.micatechnologies.minecraft.launcher.tui.TuiMode.isEnabled() ) {
                com.micatechnologies.minecraft.launcher.tui.TuiMode.realOut()
                        .println( LocalizationManager.get( "tui.alreadyRunning" ) );
                System.exit( 0 );
                return;
            }
            javax.swing.SwingUtilities.invokeLater( () -> {
                javax.swing.JOptionPane.showMessageDialog( null,
                        "Mica Minecraft Launcher is already running.",
                        LauncherConstants.LAUNCHER_APPLICATION_NAME,
                        javax.swing.JOptionPane.INFORMATION_MESSAGE );
                System.exit( 0 );
            } );
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
     * Tracks the currently-running launch so the progress GUI's Cancel button can ask
     * for it to abort. Volatile because cancel() is invoked from the JavaFX thread
     * while {@link #play(GameModPack, Runnable)} runs on a background worker; null
     * whenever no launch is in flight.
     */
    private static volatile LaunchSession currentLaunch = null;

    /**
     * Per-launch cancellation token. Holds a reference to the worker thread that's
     * running {@link #play(GameModPack, Runnable)} so {@link #cancel()} can interrupt
     * blocking downloads + flips a flag that {@code play()} checks at its exit points
     * to short-circuit the rest of the launch pipeline.
     *
     * @since 3.4
     */
    public static final class LaunchSession
    {
        private final Thread thread;
        private final AtomicBoolean cancelled = new AtomicBoolean( false );

        private LaunchSession( Thread thread )
        {
            this.thread = thread;
        }

        /**
         * Marks this launch as cancelled and interrupts the worker thread. Interrupt
         * is best-effort — some HTTP reads / native calls won't respond to it, but the
         * flag is enough for the play() pipeline to abort at its next checkpoint
         * regardless of whether the in-flight blocking call broke. Idempotent: a
         * second cancel() call is a no-op.
         */
        public void cancel()
        {
            if ( cancelled.compareAndSet( false, true ) && thread != null ) {
                thread.interrupt();
            }
        }

        /** @return true once {@link #cancel()} has been called for this session. */
        public boolean isCancelled()
        {
            return cancelled.get();
        }
    }

    /**
     * Returns the in-flight launch session, or {@code null} if no launch is currently
     * running. Used by the progress GUI's Cancel button to request abort.
     */
    public static LaunchSession getCurrentLaunch()
    {
        return currentLaunch;
    }

    /**
     * Game JVMs spawned by {@link #play(GameModPack, Runnable)} that may still be alive.
     * Tracked separately from {@link #currentLaunch} because the in-game-console path
     * clears {@code currentLaunch} as soon as it hands the freshly-spawned process off to
     * the console window — the JVM keeps running well past that point. The console-disabled
     * path, by contrast, blocks on {@code waitFor()} so {@code currentLaunch} alone already
     * covers it. Pruned lazily on each {@link #isGameRunning()} query. A {@link java.util.Set}
     * keyed on identity is fine — a handful of entries at most.
     *
     * @since 3.5
     */
    private static final java.util.Set< Process > liveGameProcesses =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Registers a freshly-spawned game process so {@link #isGameRunning()} continues to
     * report {@code true} for the lifetime of the JVM even after {@code currentLaunch} is
     * cleared (in-game-console path). No-op for a null / already-dead process.
     *
     * @param process the spawned game process
     *
     * @since 3.5
     */
    private static void trackGameProcess( Process process )
    {
        if ( process != null && process.isAlive() ) {
            liveGameProcesses.add( process );
        }
    }

    /**
     * Indicates whether a game is currently launching or running. True when a launch
     * pipeline is in flight ({@link #currentLaunch} non-null) OR a previously-spawned
     * game JVM is still alive. Used by the {@code mmcl://} deep-link handler to refuse a
     * second launch while one is already going — the launcher only drives a single game
     * session at a time and silently stacking a second launch on top would clobber the
     * progress GUI and risk two JVMs fighting over the same install directory.
     *
     * @return {@code true} if a launch is in progress or a game JVM is alive
     *
     * @since 3.5
     */
    public static boolean isGameRunning()
    {
        if ( currentLaunch != null ) {
            return true;
        }
        liveGameProcesses.removeIf( p -> !p.isAlive() );
        return !liveGameProcesses.isEmpty();
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

    /**
     * Blocks the caller until the cold-start-deferred auth token refresh has
     * settled. No-op when no refresh is pending or the refresh has already
     * completed.
     *
     * <p>Capped at 60 s so a hung MS auth server can't pin the calling thread
     * forever — the underlying renewal already has its own timeout, but this
     * defense-in-depth wrapper keeps Play responsive even if the refresh
     * executor itself wedges.</p>
     *
     * <p>Failure of the refresh is logged but not propagated: the launch
     * proceeds with whatever access token is in memory (the cached one
     * loaded by {@link MCLauncherAuthManager#loadCachedUserNow()}). If that
     * token is stale enough to be rejected by Mojang's session servers, the
     * launch will fail downstream with the same user-facing error the
     * legacy sync-renewal path would have produced.</p>
     */
    private static void awaitPendingAuthRefresh()
    {
        java.util.concurrent.CompletableFuture< MCLauncherAuthResult > pending =
                MCLauncherAuthManager.getPendingRefreshFuture();
        if ( pending == null || pending.isDone() ) {
            return;
        }
        try {
            Logger.logStd( LocalizationManager.get( "log.launcherCore.awaitingAuthRefresh" ) );
            long startNs = System.nanoTime();
            MCLauncherAuthResult result = pending.get( 60, java.util.concurrent.TimeUnit.SECONDS );
            long waitMs = ( System.nanoTime() - startNs ) / 1_000_000L;
            if ( AuthUtilities.checkAuthResponse( result ) ) {
                Logger.logStd( LocalizationManager.format( "log.launcherCore.authRefreshSettled", waitMs ) );
            }
            else {
                Logger.logWarningSilent( LocalizationManager.format(
                        "log.launcherCore.authRefreshNonSuccess", waitMs ) );
            }
        }
        catch ( java.util.concurrent.TimeoutException e ) {
            Logger.logWarningSilent( LocalizationManager.get( "log.launcherCore.authRefreshTimedOut" ) );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.launcherCore.authRefreshAwaitFailed",
                                                                 t.getClass().getSimpleName() ) );
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
     * Launches the specified mod pack for gameplay.
     *
     * @param gameModPack mod pack to launch/play
     *
     * @since 2.0
     */
    public static void play( GameModPack gameModPack, Runnable after ) {
        // Cold-start deferred the auth refresh; if the user clicked Play before
        // it landed, await it here. We're on a background thread (callers spawn
        // play() off the FX thread via SystemUtilities.spawnNewTask) so the
        // block doesn't freeze the UI — the launch-progress window stays
        // responsive, just sits on the first step a few hundred ms longer than
        // it otherwise would. If the refresh fails or times out, the launch
        // still proceeds with whatever access token is in memory: it may be
        // stale but it's the same token the legacy sync-renewal path would
        // have ended up using on a server-contact failure, and the worst case
        // (server rejects launch) just routes the user back through login.
        awaitPendingAuthRefresh();

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
                    // currentLaunch wasn't set yet, no cleanup to do.
                    return;
                }
            }
        }

        // Register this launch as the cancellable one. Captures the calling thread so
        // cancel() can interrupt blocking downloads. The session is cleared in the
        // finally below — even if play() throws, currentLaunch never leaks past the
        // pipeline boundary.
        final LaunchSession session = new LaunchSession( Thread.currentThread() );
        currentLaunch = session;
        try {
        if ( gameModPack.getPackMinRAMGB() <= ConfigManager.getMaxRamInGb() ) {
            // Build the step-list launch progress GUI + tracker + bridge. The tracker's
            // step set is per-pack-type: vanilla packs omit MODPACK_CONTENT (no mods/
            // configs/resources to sync) and the two Forge stages (no Forge to set up).
            // Mojang piston-meta libs/assets + JRE install + security scan still apply.
            com.micatechnologies.minecraft.launcher.gui.MCLauncherLaunchProgressGui playProgressWindow = null;
            try {
                if ( MCLauncherGuiController.shouldCreateGui() ) {
                    playProgressWindow = MCLauncherGuiController.goToLaunchProgressGui();
                }
            }
            catch ( IOException e ) {
                Logger.logError( LocalizationManager.get( "log.launcherCore.launchProgressGuiLoadFailed" ) );
                Logger.logThrowable( e );
            }

            // Steps are tailored per-pack: vanilla skips the modded
            // rows entirely; Fabric (and any future post-install-less
            // loader) drops FORGE_PROCESSORS — keeping that row would
            // show a step that instantly completes with no work, which
            // reads as misleading rather than helpful.
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
            com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker.StepId[] applicableSteps =
                    stepList.toArray( new com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker.StepId[ 0 ] );
            com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker tracker =
                    com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker.forSteps( applicableSteps );
            com.micatechnologies.minecraft.launcher.game.modpack.LaunchTrackerProgressBridge progressBridge =
                    new com.micatechnologies.minecraft.launcher.game.modpack.LaunchTrackerProgressBridge( tracker );

            if ( playProgressWindow != null ) {
                playProgressWindow.setTitle( LocalizationManager.format( "window.launchProgress.title",
                                                                          gameModPack.getPackName() ) );
                playProgressWindow.attachToTracker( tracker );

                // Wire the Cancel button to this launch's session. Clicking it interrupts
                // the worker thread + flips the cancellation flag, which the catch / late-
                // cancellation checks below pick up and route back to the main GUI.
                final com.micatechnologies.minecraft.launcher.gui.MCLauncherLaunchProgressGui cancellable =
                        playProgressWindow;
                cancellable.setCancelHandler( () -> {
                    session.cancel();
                    // Clear the OS-level progress overlay IMMEDIATELY rather than waiting
                    // for the worker thread's catch / finally to run. The worker might
                    // still be wedged in a non-interruptible HTTP read for several seconds
                    // after cancel; leaving the taskbar partial-progress sitting there
                    // makes the cancelled launch look like it's still going.
                    TaskbarProgressManager.stop();
                    // Navigate back optimistically — even if the worker thread is wedged in
                    // a non-interruptible HTTP read, the user gets their UI back NOW.
                    SystemUtilities.spawnNewTask( () -> GUIUtilities.JFXPlatformRun( () -> {
                        try {
                            MCLauncherGuiController.goToMainGui();
                        }
                        catch ( IOException ioe ) {
                            Logger.logErrorSilent( LocalizationManager.get( "log.launcherCore.returnMainGuiAfterCancelFailed" ) );
                        }
                    } ) );
                } );
            }

            try {
                Logger.logDebug( LocalizationManager.LAUNCHING_MOD_PACK_TEXT + ": " + gameModPack.getFriendlyName() );
                final com.micatechnologies.minecraft.launcher.gui.MCLauncherLaunchProgressGui finalPlayProgressWindow =
                        playProgressWindow;
                final long progressStartMs = System.currentTimeMillis();
                // The "all rows green → fire the ready-to-play toast" handler is wired
                // via the tracker listener rather than the old percent>=100 callback.
                // One-shot latch (compareAndSet) keeps re-fires off — the listener fires
                // once per individual step transition, but only the final transition
                // (the one that flips the LAST pending row to DONE) should trigger the
                // toast + GUI hide.
                final java.util.concurrent.atomic.AtomicBoolean readyToastFired =
                        new java.util.concurrent.atomic.AtomicBoolean( false );
                tracker.addListener( step -> {
                    if ( session.isCancelled() ) return;
                    if ( !allStepsCompleted( tracker ) ) return;
                    if ( !readyToastFired.compareAndSet( false, true ) ) return;

                    TaskbarProgressManager.stop();
                    // Toast that the pack is ready when it was a long prep (>10s) OR when the
                    // launcher isn't focused — a user who tabbed away during the install/launch
                    // gets pulled back even on a quick prep, while someone watching the progress
                    // window on a fast prep isn't toasted redundantly.
                    long elapsedMs = System.currentTimeMillis() - progressStartMs;
                    if ( elapsedMs > 10_000L || !MCLauncherGuiController.isLauncherFocused() ) {
                        NotificationManager.success(
                                LocalizationManager.get( "notification.launch.ready.title" ),
                                gameModPack.getFriendlyName() != null
                                        ? LocalizationManager.format( "notification.launch.ready.bodyNamed",
                                                                      gameModPack.getFriendlyName() )
                                        : LocalizationManager.get( "notification.launch.ready.body" ) );
                    }
                    if ( !ConfigManager.getInGameConsoleEnable() && finalPlayProgressWindow != null ) {
                        SystemUtilities.spawnNewTask( () -> {
                            try { Thread.sleep( 3000 ); }
                            catch ( InterruptedException ignored ) {}
                            finalPlayProgressWindow.hideStage();
                        } );
                    }
                } );
                gameModPack.setProgressProvider( progressBridge );

                // Wire the network retry listener so silent NetworkUtilities retries
                // surface as "Retrying (1/3) jna-4.4.0.jar"-style sub-text on whichever
                // rows are currently RUNNING. A retry can fire on a thread spawned from
                // parallelStream deep inside a sub-call, so the listener pushes to all
                // running rows rather than trying to identify the specific row that
                // owned the failed download.
                final com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker
                        retryTrackerRef = tracker;
                com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities.setRetryNoticeListener(
                        notice -> {
                            if ( session.isCancelled() ) return;
                            for ( var s : retryTrackerRef.runningSteps() ) {
                                retryTrackerRef.setSubText( s.id(), notice );
                            }
                        } );
                try {
                    gameModPack.startGame();
                }
                finally {
                    // Always clear the listener so subsequent background activity
                    // (manifest revalidates, the next launch attempt) doesn't push
                    // notices into a torn-down GUI.
                    com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities
                            .setRetryNoticeListener( null );
                    // Release the launch progress provider (and the progress-window
                    // labels it captures) now that progress reporting is done. Swap
                    // rather than set(null) so the cached launcher — and its
                    // lastLaunchedProcess, read below — survives.
                    gameModPack.swapProgressProviderTransiently( null );
                }

                // Late cancellation: if the user clicked Cancel after the JVM was already
                // spawned by startGame() (the worker thread was deep in process-spawn and
                // didn't notice the interrupt), kill the freshly-spawned game process now
                // so we don't end up with an orphaned Minecraft window and the launcher
                // back on the main screen.
                if ( session.isCancelled() ) {
                    Process orphan = gameModPack.getLastLaunchedProcess();
                    if ( orphan != null && orphan.isAlive() ) {
                        orphan.destroyForcibly();
                    }
                    Logger.logStd( LocalizationManager.get( "log.launcherCore.launchCancelledAfterSpawn" ) );
                    TaskbarProgressManager.stop();
                    returnToMainGuiOnError();
                    return;
                }

                gameModPack.saveInstalledVersion();
                gameModPack.recordLaunchStart();
                final long launchStartMs = System.currentTimeMillis();

                // Refresh the OS-shell recent-modpacks surface (Windows jump
                // list; Linux .desktop Actions=) so this pack rises to the
                // top of the right-click recents next time. Fire-and-forget
                // off a worker thread: the I/O is cheap on Linux (rewriting
                // one .desktop file) but the path is short of the game-
                // process spawn, and any failure is contained inside
                // JumpListManager.refresh.
                com.micatechnologies.minecraft.launcher.utilities.SystemUtilities.spawnNewTask(
                        com.micatechnologies.minecraft.launcher.utilities.JumpListManager::refresh );

                Process gameProcess = gameModPack.getLastLaunchedProcess();
                if ( gameProcess != null ) {
                    // Track the live JVM so isGameRunning() stays accurate even after the
                    // in-game-console path clears currentLaunch (it hands the process off to
                    // the console and returns, but the game is still up).
                    trackGameProcess( gameProcess );
                    // RGB: now that the JVM is spawned and we know which pack is
                    // running, swap the keyboard to the in-game effect (pack-color
                    // gradient + Minecraft-key highlights). Safe to call
                    // unconditionally — RgbIntegration internally bails when
                    // the master toggle is off, and any failure is contained.
                    com.micatechnologies.minecraft.launcher.rgb.RgbIntegration.onPlayStarted( gameModPack );

                    // Brief beat so the user sees the all-rows-green state on the launch
                    // progress screen before it dissolves into the game console or the
                    // launcher's main GUI. Without the pause the row layout transitions
                    // in the same frame it filled out, which reads as a flicker rather
                    // than a "ready, going" beat.
                    try { Thread.sleep( 400 ); }
                    catch ( InterruptedException ignored ) { Thread.currentThread().interrupt(); }

                    if ( ConfigManager.getInGameConsoleEnable() ) {
                        // Console enabled: show console and attach to process
                        try {
                            MCLauncherGameConsoleGui consoleGui = MCLauncherGuiController.goToGameConsoleGui();
                            if ( consoleGui != null ) {
                                consoleGui.attachToProcess( gameProcess, gameModPack.getPackName(),
                                                             exitCode -> {
                                    // Record session duration
                                    gameModPack.recordSessionEnd(
                                            System.currentTimeMillis() - launchStartMs );
                                    // RGB: game exited — drop the in-game effect.
                                    com.micatechnologies.minecraft.launcher.rgb.RgbIntegration.onPlayEnded();
                                    // On crash, find and display crash report; also toast so a
                                    // user who tabbed away mid-session notices.
                                    if ( exitCode != 0 ) {
                                        NotificationManager.error(
                                                LocalizationManager.get( "notification.launch.gameCrashed.title" ),
                                                LocalizationManager.format( "notification.launch.gameCrashed.body",
                                                        gameModPack.getFriendlyName() != null
                                                                ? gameModPack.getFriendlyName()
                                                                : "Minecraft",
                                                        exitCode ) );
                                        String crashReport = gameModPack.getLatestCrashReport();
                                        if ( crashReport != null ) {
                                            // Pack-aware overload so CrashReportAnalyzer can build
                                            // pack-specific suggestions (Open Mods Folder, etc.).
                                            consoleGui.showCrashReport( crashReport, gameModPack, exitCode );
                                        }
                                    }
                                } );
                            }
                        }
                        catch ( IOException e ) {
                            Logger.logError( LocalizationManager.get( "log.launcherCore.inGameConsoleGuiFailed" ) );
                            Logger.logThrowable( e );
                        }
                    }
                    else {
                        // Console disabled: stdout/stderr are routed to kernel-level DISCARD
                        // at spawn time (see GameModPackLauncher.launch's launchCommand call),
                        // so the JVM never stalls on an unread pipe. No drain threads needed
                        // here. Wait for game to exit, then check for crash.
                        try {
                            int exitCode = gameProcess.waitFor();
                            // Record session duration
                            gameModPack.recordSessionEnd( System.currentTimeMillis() - launchStartMs );
                            // RGB: game exited — drop the in-game effect.
                            com.micatechnologies.minecraft.launcher.rgb.RgbIntegration.onPlayEnded();
                            if ( exitCode != 0 ) {
                                Logger.logError( LocalizationManager.format( "log.launcherCore.gameCrashedExitCode",
                                                                             exitCode ) );
                                NotificationManager.error(
                                        LocalizationManager.get( "notification.launch.gameCrashed.title" ),
                                        LocalizationManager.format( "notification.launch.gameCrashed.body",
                                                gameModPack.getFriendlyName() != null
                                                        ? gameModPack.getFriendlyName()
                                                        : "Minecraft",
                                                exitCode ) );
                                // Show crash console even when console setting is off
                                String crashReport = gameModPack.getLatestCrashReport();
                                try {
                                    MCLauncherGameConsoleGui crashGui =
                                            MCLauncherGuiController.goToGameConsoleGui();
                                    if ( crashGui != null ) {
                                        // Pack-aware overload so CrashReportAnalyzer can build
                                        // pack-specific suggestions (Open Mods Folder, etc.).
                                        crashGui.showCrashOnly( gameModPack.getPackName(), exitCode,
                                                                 crashReport, null, gameModPack );
                                    }
                                }
                                catch ( IOException e ) {
                                    Logger.logError( LocalizationManager.get( "log.launcherCore.crashReportGuiFailed" ) );
                                }
                            }
                        }
                        catch ( InterruptedException e ) {
                            Logger.logError( LocalizationManager.get( "log.launcherCore.gameWaitInterrupted" ) );
                        }
                    }
                }
            }
            catch ( ModpackScanDetectionException e ) {
                // Scan-blocked message is a deliberately multi-line bulleted listing;
                // route through the structure-preserving error path so the popup doesn't
                // collapse it into a one-line blob.
                Logger.logErrorMultiline( e.getMessage() );
                Logger.logThrowable( e );
                returnToMainGuiOnError();
            }
            catch ( Exception e ) {
                // Cancellation exits via thrown exception (interrupt → InterruptedException
                // or downstream IOException) — distinguish from a real failure so the user
                // doesn't see an error log for a deliberate cancel.
                if ( session.isCancelled() ) {
                    Logger.logStd( LocalizationManager.get( "log.launcherCore.launchCancelled" ) );
                    TaskbarProgressManager.stop();
                    returnToMainGuiOnError();
                }
                else {
                    Logger.logError( LocalizationManager.UNABLE_START_GAME_EXCEPTION_TEXT );
                    Logger.logThrowable( e );
                    returnToMainGuiOnError();
                }
            }

            // If after runnable present, run it -- but NOT when the in-game console is managing
            // the UI lifecycle (it will return to main GUI via its own Close button).
            // Skipped on cancellation too — the after-callback usually navigates to the main
            // GUI which is already where the cancel handler put the user.
            if ( after != null && !ConfigManager.getInGameConsoleEnable() && !session.isCancelled() ) {
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
            // InterruptedException out of an innocuous sleep call). currentLaunch is
            // cleared too so the progress GUI's Cancel button no-ops once the launch
            // exits, no matter how it exited.
            Thread.interrupted();
            currentLaunch = null;
        }
    }

    /**
     * Performs mod pack selection using the specified desired mod pack, if present. In client mode, mod pack selection
     * displays the mod pack selection window with the specified mod pack preselected. In server mode, mod pack
     * selection launches the specified mod pack.
     *
     * @param modPackName name of mod pack
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
                while ( shouldRestart ) {
                    Logger.logStd( restartCount > 0
                                           ? LocalizationManager.format( "log.launcherCore.startingServerRestart",
                                                                         restartCount, maxRestarts )
                                           : LocalizationManager.get( "log.launcherCore.startingServer" ) );
                    play( finalGameModPack );

                    Process proc = finalGameModPack.getLastLaunchedProcess();
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
    public static String parseLauncherArgs( String[] args ) {
        // mmcl:// deep-link from the website / OS scheme handler. The OS hands us the URI as
        // argv when the launcher cold-starts via the scheme. Stash it for the session to
        // dispatch once the main GUI is up — we deliberately don't dispatch from here so the
        // user still flows through auth + mod-pack-info-fetch normally before the URI action
        // fires (e.g. for mmcl://add, the installed-list needs to be populated first).
        for ( int i = 0; i < args.length; i++ ) {
            if ( LauncherUriHandler.isLauncherUri( args[ i ] ) ) {
                setPendingLauncherUri( args[ i ] );
                GameModeManager.setCurrentGameMode( GameMode.CLIENT );
                return "";
            }
        }

        // --cli / --tui: full-screen TUI mode (the flag was already captured in main()). It runs as
        // a normal CLIENT (same config/paths as the GUI); the only other meaningful token is an
        // optional modpack name to pre-select. Strip the mode flags and take the last bare token.
        if ( com.micatechnologies.minecraft.launcher.tui.TuiMode.isEnabled() ) {
            GameModeManager.setCurrentGameMode( GameMode.CLIENT );
            String tuiSelection = "";
            for ( String a : args ) {
                if ( "--cli".equals( a ) || "--tui".equals( a )
                        || LauncherConstants.PROGRAM_ARG_CLIENT_MODE.equalsIgnoreCase( a )
                        || LauncherConstants.PROGRAM_ARG_SERVER_MODE.equalsIgnoreCase( a ) ) {
                    continue;
                }
                tuiSelection = a;
            }
            return tuiSelection;
        }

        String initialModPackSelection = "";
        if ( args.length == 0 ) {
            GameModeManager.inferGameMode();
        }
        else if ( args.length == 1 && args[ 0 ].equalsIgnoreCase( LauncherConstants.PROGRAM_ARG_CLIENT_MODE ) ) {
            GameModeManager.setCurrentGameMode( GameMode.CLIENT );
        }
        else if ( args.length == 1 && args[ 0 ].equalsIgnoreCase( LauncherConstants.PROGRAM_ARG_SERVER_MODE ) ) {
            GameModeManager.setCurrentGameMode( GameMode.SERVER );
        }
        else if ( args.length == 1 ) {
            initialModPackSelection = args[ 0 ];
        }
        else if ( args.length == 2 && args[ 0 ].equalsIgnoreCase( LauncherConstants.PROGRAM_ARG_CLIENT_MODE ) ) {
            GameModeManager.setCurrentGameMode( GameMode.CLIENT );
            initialModPackSelection = args[ 1 ];
        }
        else if ( args.length == 2 && args[ 0 ].equalsIgnoreCase( LauncherConstants.PROGRAM_ARG_SERVER_MODE ) ) {
            GameModeManager.setCurrentGameMode( GameMode.SERVER );
            initialModPackSelection = args[ 1 ];
        }
        else {
            Logger.logError( LocalizationManager.INVALID_ARGS_SPECIFIED_TEXT +
                                     "\n" +
                                     LocalizationManager.USAGE_TEXT +
                                     ": launcher.jar [ -s [modpack_name] | -c" +
                                     " " +
                                     "[modpack_name] | " +
                                     "modpack_name ]" );
            closeApp();
        }
        return initialModPackSelection;
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
        try {
            // Tear down the RGB subsystem first so backends paint their
            // final black frames + close sockets before the JVM exits.
            // Synchronous on purpose — we don't want JVM exit to race
            // with backend cleanup and leave the user's keyboard stuck
            // on whatever the last effect was.
            com.micatechnologies.minecraft.launcher.rgb.RgbIntegration.shutdown();
            DiscordRpcUtility.exit();
            // Release the shared taskbar wrapper before tearing down the GUI controller —
            // closing it after the stage is gone occasionally leaves the COM thread blocked
            // on a stale HWND lookup. Doing it here also clears the taskbar overlay so a
            // restart doesn't briefly inherit the previous session's progress state.
            com.micatechnologies.minecraft.launcher.utilities.TaskbarProgressManager.shutdown();
            // Remove the notification tray icon. Without this, the icon would persist in the
            // tray after launcher exit until the user clicks it (Windows behavior).
            com.micatechnologies.minecraft.launcher.utilities.NotificationManager.shutdown();
            MCLauncherGuiController.exit();
            // Tear down the help window's static singleton state so a subsequent
            // restartApp doesn't reuse a Stage whose Owner is now closed and a
            // WebView whose internal state was wired up against the previous
            // session's GUI window. Idempotent if the help window was never
            // opened. Must run AFTER MCLauncherGuiController.exit() because
            // the help window may transitively reference the main stage via
            // initOwner; tearing it down last keeps the close order stable.
            com.micatechnologies.minecraft.launcher.gui.MCLauncherHelpWindow.cleanup();
            SingleInstanceLock.release();
            // Drain in-flight background tasks (manifest cache writes, log flushes
            // queued by spawnNewTask, etc.) before tearing down the logger so any
            // last-second I/O actually lands. Bounded wait — daemon-thread semantics
            // clean up whatever is still running past the timeout.
            SystemUtilities.shutdownBackgroundExecutor( 2_000 );
            // Explicit config flush BEFORE logger shutdown. The ConfigStore
            // shutdown hook is the original safety net, but on Windows the
            // sequence "System.exit → daemon-thread death races with the
            // shutdown-hook flush" has been observed to lose the user's
            // last-50ms config changes (added modpacks, wizard completion).
            // Calling here guarantees the pending debounced write lands
            // while logging is still alive and we have full control over
            // the timing.
            com.micatechnologies.minecraft.launcher.config.ConfigManager.flushPendingWrite();
            Logger.shutdownLogSys();
        }
        catch ( Exception ignored ) {
        }
        Logger.logStd( LocalizationManager.FINISHED_APP_CLEANUP_TEXT );
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
     * @since 2.0
     */
    public static void restartAppWithError( String restartErrorString ) {
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
        // normal cross-thread path. Background-thread callers (e.g. Reset Launcher,
        // which already wraps itself in SystemUtilities.spawnNewTask) fall straight
        // through to the synchronous path.
        if ( javafx.application.Platform.isFxApplicationThread() ) {
            Thread restarter = new Thread( LauncherCore::restartAppNow, "Launcher-Restart" );
            restarter.setDaemon( false );
            restarter.start();
            return;
        }
        restartAppNow();
    }

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
        Thread relauncher = new Thread( LauncherCore::relaunchAppNow, "Launcher-Relaunch" );
        relauncher.setDaemon( false );
        relauncher.start();
    }

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
     * Attempts to return to the main GUI after a game launch error. If the main GUI cannot be loaded, the error is
     * logged silently (the user can still close the app via the window X button).
     *
     * @since 2.0
     */
    private static void returnToMainGuiOnError() {
        if ( MCLauncherGuiController.shouldCreateGui() ) {
            try {
                MCLauncherGuiController.goToMainGui();
            }
            catch ( IOException e ) {
                Logger.logErrorSilent( LocalizationManager.get( "log.launcherCore.returnMainGuiAfterErrorFailed" ) );
            }
        }
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
        if ( javafx.application.Platform.isFxApplicationThread() ) {
            Thread closer = new Thread( LauncherCore::closeAppNow, "Launcher-Close" );
            closer.setDaemon( false );
            closer.start();
            return;
        }
        closeAppNow();
    }

    private static void closeAppNow() {
        cleanupApp();
        currentSession.exitLatch.countDown();
        Logger.logStd( LocalizationManager.SEE_YOU_SOON_TEXT );
        System.exit( LauncherConstants.EXIT_STATUS_CODE_GOOD );
    }
}
