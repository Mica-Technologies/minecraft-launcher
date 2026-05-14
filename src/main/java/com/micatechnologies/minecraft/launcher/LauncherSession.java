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
import com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiController;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherProgressGui;
import com.micatechnologies.minecraft.launcher.utilities.AnnouncementManager;
import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;
import com.micatechnologies.minecraft.launcher.config.GameModeManager;

import java.io.IOException;
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
        // Parse launcher args and set game mode
        String initialModPackSelection = LauncherCore.parseLauncherArgs( args );

        // Bring up the RGB-integration subsystem if the user has it enabled
        // in Settings. Must be called AFTER parseLauncherArgs because the
        // bootstrap reads ConfigManager, which in turn picks the
        // client-vs-server config folder from GameModeManager. Before
        // parseLauncherArgs, GameModeManager.isClient() returns false (no
        // mode set yet) and the read would target the server-mode path (=
        // current working directory) instead of the launcher's per-user
        // config folder — see the comment in LauncherCore.main().
        com.micatechnologies.minecraft.launcher.rgb.RgbIntegration.bootstrap();

        // Apply system properties
        LauncherCore.applySystemProperties();

        // Configure logging
        LauncherCore.configureLogger();

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

        // Show a progress window while loading startup data
        MCLauncherProgressGui startupProgressWindow = null;
        try {
            if ( MCLauncherGuiController.shouldCreateGui() ) {
                startupProgressWindow = MCLauncherGuiController.goToProgressGui();
            }
        }
        catch ( IOException e ) {
            Logger.logError( "Unable to load progress GUI for startup." );
            Logger.logThrowable( e );
        }

        // Check network connectivity
        if ( startupProgressWindow != null ) {
            startupProgressWindow.setUpperLabelText( "Loading" );
            startupProgressWindow.setSectionText( "Checking network connectivity..." );
            startupProgressWindow.setDetailText( "" );
            startupProgressWindow.setProgress( 15 );
        }
        boolean online = com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities.checkNetworkAvailability();

        // Installed-modpack load on the critical path — fetchInstalledModPacks now
        // does a cache-first sync load and kicks off its own background revalidate,
        // so this completes in microseconds when the manifest cache is warm. First-
        // ever launch (no cache) still does the original synchronous network fetch
        // inside this call.
        //
        // Announcements: kicked off pure fire-and-forget. The main menu attaches a
        // whenComplete hook on AnnouncementManager.getCheckFuture() that pops the
        // banner the moment the JSON arrives — no reason to block first paint
        // waiting for it, since it's purely informational decoration. Login /
        // Settings / Library screens read whatever value is in the cache at the
        // point they render; on a slow network the value may be empty for the
        // first few seconds, which is the right trade vs. blocking the splash.
        if ( startupProgressWindow != null ) {
            startupProgressWindow.setSectionText( online ? "Loading mod packs..."
                                                          : "Loading cached mod pack data" );
            startupProgressWindow.setDetailText( "" );
            startupProgressWindow.setProgress( 30 );
        }

        if ( online ) {
            AnnouncementManager.startCheckAsync();
            // Opportunistic background token renewal — only fires if the cached
            // token is in the soft-refresh window (3-4h old). Lets us avoid the
            // synchronous server contact on the next cold start by piggybacking
            // the renewal on the current launch's idle pack-fetch window. No-op
            // for fresh tokens and (deliberately) for already-expired ones.
            com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager
                    .tryPreemptiveBackgroundRenewal();
        }
        java.util.concurrent.CompletableFuture< Void > installedFuture =
                java.util.concurrent.CompletableFuture.runAsync(
                        () -> GameModPackManager.fetchInstalledModPacks( null ) );

        try {
            installedFuture.get();
        }
        catch ( Exception e ) {
            Logger.logError( "Startup network task failed." );
            Logger.logThrowable( e );
        }

        // Available-modpacks fetch — fire-and-forget. Main menu shows a "loading available
        // packs" indicator while this runs; Library screen waits on its completion.
        if ( online ) {
            GameModPackManager.startAvailableModPacksFetchAsync();
        }

        // Kick off background prefetch of dominant-color gradients for every available modpack.
        // Async — the launcher doesn't wait for it before continuing to the main GUI. By the
        // time the user opens the Game Library, the cache is warm and gradients render
        // instantly instead of stalling the FX thread on per-card histogram work.
        com.micatechnologies.minecraft.launcher.gui.MCLauncherMainGui.prefetchAvailableModpackBackgrounds();

        if ( startupProgressWindow != null ) {
            startupProgressWindow.setSectionText( "Ready!" );
            startupProgressWindow.setDetailText( "" );
            startupProgressWindow.setProgress( 100 );
        }

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
