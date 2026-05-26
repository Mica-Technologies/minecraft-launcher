/*
 * Copyright (c) 2026 Mica Technologies
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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.utilities.AnnouncementManager;
import com.micatechnologies.minecraft.launcher.utilities.ColdStartProfiler;
import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;
import com.micatechnologies.minecraft.launcher.config.GameModeManager;

import java.util.concurrent.CountDownLatch;

/**
 * Encapsulates one lifecycle run of the launcher. The launcher's {@code main()} loop creates a new session on each
 * iteration (including restarts). A session handles: argument parsing, login, modpack loading, and entering the
 * modpack selection screen. It then waits on an exit latch until the user (or the system) signals exit or restart.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
class LauncherSession
{
    private final String[] args;
    private final String previousRestartError;
    final CountDownLatch exitLatch = new CountDownLatch( 1 );

    LauncherSession( String[] args, String previousRestartError )
    {
        this.args = args;
        this.previousRestartError = previousRestartError;
    }

    /**
     * Runs one full lifecycle of the launcher: parse args, login, load modpacks, show UI, and wait for exit.
     */
    void run()
    {
        ColdStartProfiler.mark( "session_start" );

        // Parse launcher args and set game mode
        String initialModPackSelection = LauncherCore.parseLauncherArgs( args );

        // Game mode is now set, so it's safe for Logger.logDebug to consult
        // ConfigManager (which transitively reads the config file at a
        // game-mode-dependent path). Before this flip, any logDebug call
        // would have triggered an early ConfigStore.loadFromDisk against the
        // server-mode fallback path (= the current working directory)
        // because GameModeManager.isClient() returned false, locking the
        // in-memory JSON to a stale empty state that no amount of subsequent
        // user activity could persist correctly. See the Logger doc.
        Logger.enableConfigBackedDebugLogging();

        // Bring up the RGB-integration subsystem if the user has it enabled
        // in Settings. Must be called AFTER parseLauncherArgs because the
        // bootstrap reads ConfigManager, which in turn picks the
        // client-vs-server config folder from GameModeManager. Before
        // parseLauncherArgs, GameModeManager.isClient() returns false (no
        // mode set yet) and the read would target the server-mode path (=
        // current working directory) instead of the launcher's per-user
        // config folder — see the comment in LauncherCore.main().
        com.micatechnologies.minecraft.launcher.rgb.RgbIntegration.bootstrap();

        // Resolve + apply the effective UI locale (user override > OS
        // detect > en-US) BEFORE any code touches LocalizationManager. The
        // 89 static-final translation fields in that class initialize at
        // class load, so they need Locale.getDefault() to already be
        // correct by the time they fire. Calling LocaleBootstrap from a
        // standalone class avoids triggering LocalizationManager class
        // load here. See LocaleBootstrap class docs for the rationale.
        com.micatechnologies.minecraft.launcher.consts.localization.LocaleBootstrap.apply(
                com.micatechnologies.minecraft.launcher.config.ConfigManager.getLocaleOverride() );
        ColdStartProfiler.mark( "locale_ready" );

        // Apply system properties
        LauncherCore.applySystemProperties();

        // Configure logging
        LauncherCore.configureLogger();
        ColdStartProfiler.mark( "logger_ready" );

        // Kick off JavaFX platform startup + the GUI-window construction on a
        // background daemon thread so their combined ~1.0 s of bootstrap work
        // (Prism + Glass + font loading + MCLauncherGuiWindow.start +
        // placeholder Scene setup + DWM chrome / Mica backdrop wiring)
        // overlaps with the auth fast-path + cache-first pack load on the
        // session thread. By the time the session thread reaches
        // doModpackSelection -> goToMainGui -> startGui, both Platform and
        // guiWindow are usually ready, so startGui's synchronized check sees
        // a non-null guiWindow and returns immediately.
        //
        // Idempotent: Platform.startup is a one-shot, MCLauncherGuiController.startGui
        // is synchronized + null-checked, and subsequent JFXPlatformRun calls
        // just see a ready toolkit. Server mode skips both — headless launches
        // don't touch the JavaFX scene graph at all.
        if ( GameModeManager.getCurrentGameMode() == GameMode.CLIENT ) {
            Thread fxPrestart = new Thread( () -> {
                try {
                    javafx.application.Platform.startup( () -> {
                        // No-op initializer; the first real scene swap happens
                        // later from the session thread via goToMainGui.
                    } );
                }
                catch ( IllegalStateException already ) {
                    // Toolkit was already started (test harness, re-init).
                    // Safe to swallow; the launcher will use the existing one.
                }
                catch ( Throwable t ) {
                    Logger.logWarningSilent( "JavaFX pre-start (toolkit) failed: "
                                                     + t.getClass().getSimpleName() + " — "
                                                     + "falling back to lazy init in goToMainGui." );
                    return; // Don't try to pre-start the window if the toolkit didn't come up.
                }
                try {
                    com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiController.prestartGui();
                }
                catch ( Throwable t ) {
                    Logger.logWarningSilent( "JavaFX pre-start (gui window) failed: "
                                                     + t.getClass().getSimpleName() + " — "
                                                     + "falling back to lazy init in goToMainGui." );
                }
            }, "mmcl-fx-prestart" );
            fxPrestart.setDaemon( true );
            fxPrestart.start();
        }

        // Log startup
        Logger.logDebug( "Logging configured. Application arguments parsed: " );
        for ( String arg : args ) {
            Logger.logDebug( "  " + arg );
        }

        // Log development mode
        if ( com.micatechnologies.minecraft.launcher.consts.LauncherConstants.LAUNCHER_IS_DEV ) {
            Logger.logDebug( "[NOTICE] Development Mode is Enabled! Bugs may be present and not all features " +
                                     "may function as intended." );
        }

        // If client, do login
        if ( GameModeManager.getCurrentGameMode() == GameMode.CLIENT ) {
            Logger.logDebug( LocalizationManager.LAUNCHER_CLIENT_MODE_STARTING_LOGIN_TEXT );
            LauncherCore.performClientLogin( previousRestartError );
            Logger.logDebug( LocalizationManager.LOGIN_PROCESS_FINISHED_TEXT );
        }
        else {
            Logger.logDebug( LocalizationManager.LAUNCHER_NOT_CLIENT_MODE_SKIPPING_LOGIN_TEXT );
        }
        ColdStartProfiler.mark( "auth_done" );

        // No cold-start progress GUI: the heavy lifting that used to sit
        // between auth and the main menu (sync OAuth refresh, 5s network
        // probe, per-manifest network fetches) is all either deferred to
        // the background or running off the critical path now, so the
        // progress screen would flash past in well under a second with
        // text the user can't read — pure visual noise. Going straight
        // to the main GUI also avoids paying the cost of loading two
        // FXML scenes back to back during cold start (the progress GUI's
        // first paint was the bulk of the gap between auth_done and
        // packs_loaded — JavaFX's first-scene init in a JVM is expensive).
        //
        // Connectivity is resolved lazily — the old synchronous probe was
        // a 5-second TCP connect to launchermeta.mojang.com:443 sitting on
        // the critical path. Kick it off async so NetworkUtilities.offlineMode
        // is populated for anything that actually needs to read it; the
        // OfflineIndicator label updates reactively when downstream
        // operations hit the network.
        java.util.concurrent.CompletableFuture.runAsync(
                () -> com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities.checkNetworkAvailability() );

        // Announcements + opportunistic preemptive token renewal: both
        // fire-and-forget, both already handle their own network failures.
        // Don't gate on a connectivity boolean we don't have anymore.
        AnnouncementManager.startCheckAsync();
        com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager
                .tryPreemptiveBackgroundRenewal();

        // Installed-modpack load. Cache-first under the hood (one index file +
        // per-manifest cache files); the in-method background revalidate hooks
        // pick up any out-of-date entries within seconds of cold start. Runs
        // synchronously here because we need installedGameModPacks populated
        // before the main GUI's setup reads it — the cache-only path is fast
        // enough that the extra thread hop wasn't earning anything.
        GameModPackManager.fetchInstalledModPacks( null );
        ColdStartProfiler.mark( "packs_loaded" );

        // Wire the background-task error listener so the available-modpacks fetch
        // and installed-pack revalidate (both fire-and-forget below) surface their
        // failures as a notification toast instead of vanishing into the log.
        GameModPackManager.setBackgroundErrorListener( ( message, cause ) ->
                com.micatechnologies.minecraft.launcher.utilities.NotificationManager.warn(
                        "Background task failed", message ) );
        ColdStartProfiler.mark( "bg_listener_set" );

        GameModPackManager.startAvailableModPacksFetchAsync();
        ColdStartProfiler.mark( "available_fetch_kicked" );

        com.micatechnologies.minecraft.launcher.gui.MCLauncherMainGui.prefetchAvailableModpackBackgrounds();
        ColdStartProfiler.mark( "prefetch_kicked" );

        // Show main (mod pack selection) window
        LauncherCore.doModpackSelection( initialModPackSelection, previousRestartError );

        // Deferred mmcl:// dispatch. If the launcher was cold-started via the scheme handler
        // (the OS pushed `mmcl://add?url=...` into argv), the parser stashed the URI; now that
        // auth + the modpack list are ready, dispatch the action. Cross-platform — the
        // already-running case is handled separately by Desktop.setOpenURIHandler on macOS
        // and (eventually) by IPC through the SingleInstanceLock channel on Win/Linux.
        String pendingUri = LauncherCore.consumePendingLauncherUri();
        if ( pendingUri != null ) {
            com.micatechnologies.minecraft.launcher.utilities.LauncherUriHandler.handle( pendingUri );
        }

        // Deferred .mmcjson import. Symmetric to the URI dispatch above — when the macOS
        // Desktop.setOpenFileHandler bridge fires before the main GUI is up (cold-start
        // drag-drop / Finder double-click), files get queued via
        // LauncherCore.addPendingMmcjsonFile and drained here. Each import runs on a
        // worker thread so the session thread doesn't block on disk + install pipeline.
        java.util.List< java.io.File > pendingFiles = LauncherCore.consumePendingMmcjsonFiles();
        for ( java.io.File f : pendingFiles ) {
            com.micatechnologies.minecraft.launcher.utilities.SystemUtilities.spawnNewTask(
                    () -> com.micatechnologies.minecraft.launcher.gui.SystemMenuBarManager
                                  .dispatchMmcjsonImport( f ) );
        }

        // Wait for exit latch
        try {
            exitLatch.await();
        }
        catch ( InterruptedException e ) {
            Logger.logError( "The main thread was interrupted before receiving an exit signal. The application " +
                                     "will be unable to restart!" );
            Logger.logThrowable( e );
        }
    }
}
