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

        // Opt the process into Per-Monitor DPI Awareness V2 before anything else.
        // JavaFX's Glass backend internally sets only V1, which scales the JavaFX
        // client area per-monitor but leaves the OS-managed title bar sized at the
        // system DPI — so on a mixed-DPI multi-monitor setup the launcher's chrome
        // ends up tiny on the higher-DPI monitor. V2 has to be set before any
        // window is created or DPI API is queried; later upgrades fail silently.
        WindowsDpiAwareness.enablePerMonitorV2();

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
            Logger.logStd( "Awaiting deferred auth refresh before launch..." );
            long startNs = System.nanoTime();
            MCLauncherAuthResult result = pending.get( 60, java.util.concurrent.TimeUnit.SECONDS );
            long waitMs = ( System.nanoTime() - startNs ) / 1_000_000L;
            if ( AuthUtilities.checkAuthResponse( result ) ) {
                Logger.logStd( "Auth refresh settled (" + waitMs + " ms wait); proceeding with launch." );
            }
            else {
                Logger.logWarningSilent(
                        "Deferred auth refresh returned non-success after " + waitMs +
                                " ms; launching with cached token." );
            }
        }
        catch ( java.util.concurrent.TimeoutException e ) {
            Logger.logWarningSilent( "Deferred auth refresh timed out at 60 s; launching with cached token." );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "Deferred auth refresh await failed (" + t.getClass().getSimpleName()
                                             + "); launching with cached token." );
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
                Logger.logError( "Unable to load launch progress GUI due to an incomplete response from the GUI subsystem." );
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
                            Logger.logErrorSilent( "Unable to return to main GUI after launch cancel." );
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
                    long elapsedMs = System.currentTimeMillis() - progressStartMs;
                    if ( elapsedMs > 10_000L ) {
                        NotificationManager.success(
                                "Ready to play",
                                gameModPack.getFriendlyName() != null
                                        ? gameModPack.getFriendlyName() + " is starting."
                                        : "Your modpack is ready and starting." );
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
                    Logger.logStd( "Launch cancelled by user after process spawn — killed game process." );
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
                                                "Game crashed",
                                                ( gameModPack.getFriendlyName() != null
                                                        ? gameModPack.getFriendlyName()
                                                        : "Minecraft" )
                                                        + " exited with code " + exitCode + "." );
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
                            Logger.logError( "Unable to open in-game console GUI." );
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
                                Logger.logError( "Game crashed with exit code " + exitCode );
                                NotificationManager.error(
                                        "Game crashed",
                                        ( gameModPack.getFriendlyName() != null
                                                ? gameModPack.getFriendlyName()
                                                : "Minecraft" )
                                                + " exited with code " + exitCode + "." );
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
                                    Logger.logError( "Unable to show crash report GUI." );
                                }
                            }
                        }
                        catch ( InterruptedException e ) {
                            Logger.logError( "Game process wait was interrupted." );
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
                    Logger.logStd( "Launch cancelled by user." );
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
                            Logger.logError( "Unable to load main GUI after auto-launched pack exited." );
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
                Logger.logError( "Unable to load main GUI due to an incomplete response from the GUI subsystem." );
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
                    Logger.logStd( "Starting server" + ( restartCount > 0
                                           ? " (restart " + restartCount + "/" + maxRestarts + ")" : "" ) + "..." );
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
                            Logger.logStd( "Server exited with code " + exitCode );

                            if ( exitCode == 0 ) {
                                // Clean shutdown — don't restart
                                shouldRestart = false;
                            }
                            else if ( restartCount < maxRestarts ) {
                                restartCount++;
                                Logger.logStd( "Server crashed, restarting in 5 seconds..." );
                                Thread.sleep( 5_000 );
                            }
                            else {
                                Logger.logErrorSilent(
                                        "Server crashed " + maxRestarts + " times, not restarting." );
                                shouldRestart = false;
                            }
                        }
                        catch ( InterruptedException e ) {
                            Logger.logErrorSilent( "Server wait interrupted." );
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
                Logger.logStd( "[" + cached.name() + "] cached session loaded; refreshing token in background." );
                MCLauncherAuthManager.renewExistingLoginAsync();
                return;
            }
            // Cache unreadable or missing fields → fall through to the legacy
            // sync renewal path. This is the cold-uninstalled, fresh-install,
            // or corrupt-cache case; rare in steady state but the existing
            // progress-GUI flow handles it cleanly.
            Logger.logStd( "Saved account present but cached user info unreadable; falling back to sync renewal." );

            MCLauncherProgressGui authProgressWindow = null;
            try {
                if ( MCLauncherGuiController.shouldCreateGui() ) {
                    authProgressWindow = MCLauncherGuiController.goToProgressGui();
                }
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to load progress GUI for auth renewal." );
                Logger.logThrowable( e );
            }
            if ( authProgressWindow != null ) {
                authProgressWindow.setUpperLabelText( "Signing In" );
                authProgressWindow.setSectionText( "Checking session..." );
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
                Logger.logStd( "Saved account could not be renewed. Showing login screen." );
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
            Logger.logError( "Unable to load login GUI due to an incomplete response from the GUI subsystem." );
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
                Logger.logDebug( "Setting app user model ID: " + appUserModelId );
                WString appUserModelIdWString = new WString( appUserModelId );
                Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID( appUserModelIdWString );
            }
        }
        catch ( Exception e ) {
            Logger.logErrorSilent( "Unable to set up user model ID for application. If you are using Windows, this " +
                                           "may result in your taskbar showing a separate icon for the launcher than " +
                                           "the currently pinned icon, if present." );
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
                Logger.logErrorSilent( "Unable to return to main GUI after launch error." );
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
