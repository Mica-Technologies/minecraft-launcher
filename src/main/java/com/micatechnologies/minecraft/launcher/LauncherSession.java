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

        // Load announcements
        if ( startupProgressWindow != null ) {
            startupProgressWindow.setUpperLabelText( "Loading" );
            startupProgressWindow.setSectionText( "Checking for announcements..." );
            startupProgressWindow.setDetailText( "Contacting announcement server" );
            startupProgressWindow.setProgress( 25 );
        }
        AnnouncementManager.checkAnnouncements();

        // Load mod pack information
        if ( startupProgressWindow != null ) {
            startupProgressWindow.setSectionText( "Loading mod packs..." );
            startupProgressWindow.setDetailText( "Fetching installed and available mod packs" );
            startupProgressWindow.setProgress( 60 );
        }
        GameModPackManager.fetchModPackInfo();

        if ( startupProgressWindow != null ) {
            startupProgressWindow.setSectionText( "Ready!" );
            startupProgressWindow.setDetailText( "" );
            startupProgressWindow.setProgress( 100 );
        }

        // Show main (mod pack selection) window
        LauncherCore.doModpackSelection( initialModPackSelection, previousRestartError );

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
