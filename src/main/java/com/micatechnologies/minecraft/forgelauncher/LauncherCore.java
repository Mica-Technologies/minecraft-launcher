/*
 * Copyright (c) 2020 Mica Technologies
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

package com.micatechnologies.minecraft.forgelauncher;

import com.micatechnologies.minecraft.forgelauncher.config.ConfigManager;
import com.micatechnologies.minecraft.forgelauncher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.forgelauncher.game.auth.AuthAccount;
import com.micatechnologies.minecraft.forgelauncher.game.auth.AuthService;
import com.micatechnologies.minecraft.forgelauncher.game.auth.AuthManager;
import com.micatechnologies.minecraft.forgelauncher.config.GameModeManager;
import com.micatechnologies.minecraft.forgelauncher.consts.LauncherConstants;
import com.micatechnologies.minecraft.forgelauncher.consts.LocalPathConstants;
import com.micatechnologies.minecraft.forgelauncher.exceptions.AuthException;
import com.micatechnologies.minecraft.forgelauncher.files.LocalPathManager;
import com.micatechnologies.minecraft.forgelauncher.files.RuntimeManager;
import com.micatechnologies.minecraft.forgelauncher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.forgelauncher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.forgelauncher.game.modpack.GameModPackProgressProvider;
import com.micatechnologies.minecraft.forgelauncher.gui.GUIController;
import com.micatechnologies.minecraft.forgelauncher.files.Logger;
import com.micatechnologies.minecraft.forgelauncher.gui.MainWindow;
import com.micatechnologies.minecraft.forgelauncher.utilities.objects.GameMode;
import com.micatechnologies.minecraft.forgelauncher.gui.LoginWindow;
import com.micatechnologies.minecraft.forgelauncher.utilities.NetworkUtilities;
import javafx.application.Platform;

import java.io.File;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.List;

/**
 * Launcher core class. This class is the main entry point of the Mica Forge Launcher, and handles the main processes
 * that are required by the launcher for full functionality, such as login, starting game, etc. Note that methods and
 * fields that were deprecated earlier than version 2.0 have been removed in version 2.0
 *
 * @author Mica Technologies
 * @version 2.0
 * @creator hawka97
 * @editors hawka97
 */
public class LauncherCore
{

    /**
     * Launcher application restart flag. This flag must be true for the application to start. If this flag is set when
     * the launcher closes, it will restart.
     */
    private static boolean restartFlag = true;

    /**
     * Launcher application main method/entry point.
     *
     * @param args launcher arguments
     *
     * @since 1.0
     */
    public static void main( String[] args ) {
        while ( restartFlag ) {
            // Reset restart flag to false
            restartFlag = false;

            /*
             * Parse launcher args and set game mode from parameters if present,
             * otherwise infer by detection of graphics environment.
             */
            String initialModPackSelection = parseLauncherArgs( args );

            // Apply system properties
            applySystemProperties();

            // Configure logging
            configureLogger();

            // Check for internet connection. Close if unable to connect
            if ( !NetworkUtilities.isMojangAuthReachable() ) {
                Logger.logError(
                        "Unable to reach the Mojang authentication servers! Cannot start launcher. Try again later or contact support." );
                closeApp();
            }

            // If client, do login
            if ( GameModeManager.getCurrentGameMode() == GameMode.CLIENT ) {
                Logger.logDebug( "Launcher is running in client game mode. Starting login..." );
                performClientLogin();
                Logger.logDebug( "The login process has finished." );
            }
            else {
                Logger.logDebug( "Launcher is not in client game mode. Skipping authentication/login handler." );
            }

            // Load mod pack information
            GameModPackManager.fetchModPackInfo();

            // Get local JDK
            RuntimeManager.verifyJre8();

            // Show main (mod pack selection) window
            doModpackSelection( initialModPackSelection );
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
        if ( gameModPack.getPackMinRAMGB() >= ConfigManager.getMaxRamInGb() ) {
            try {
                Logger.logDebug( "Launching mod pack: " + gameModPack.getFriendlyName() );
                gameModPack.setProgressProvider( new GameModPackProgressProvider()
                {
                    @Override
                    public void updateProgressHandler( double percent, String text ) {
                        Logger.logStd( text + " - " + percent );
                    }
                } );
                gameModPack.startGame();
            }
            catch ( Exception e ) {
                Logger.logError( "Unable to start the game. An exception occurred!" );
                e.printStackTrace();
            }
        }
        else {
            Logger.logError( "The mod pack [" + gameModPack.getFriendlyName() +
                                     "] requires a minimum of GB of RAM. The maximum RAM setting must be increased." );
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
    public static void doModpackSelection( String modPackName ) {
        // Create variable to store final resulting mod pack name
        GameModPack finalGameModPack = GameModPackManager.getInstalledModPackByName( modPackName );

        // Check if requested mod pack is installed
        if ( modPackName.length() > 0 && finalGameModPack == null ) {
            Logger.logError( "The mod pack [" + modPackName + "] is not installed! Will default to first mod pack." );
        }
        // Show message if using first mod pack by default
        else if ( modPackName.length() == 0 && finalGameModPack == null ) {
            Logger.logStd( "No mod pack specified. Will default to first mod pack." );
        }

        // Select first mod pack by default
        if ( finalGameModPack == null ) {
            // Check for installed mod packs
            final List< GameModPack > installedGameModPacks = GameModPackManager.getInstalledModPacks();
            if ( installedGameModPacks.size() == 0 ) {
                Logger.logStd( "No mod packs are installed. Cannot automatically select first mod pack." );
            }
            else {
                finalGameModPack = installedGameModPacks.get( 0 );
            }
        }

        // Show gui or start start
        if ( GameModeManager.isClient() ) {
            MainWindow mainWindow = new MainWindow();
            if ( finalGameModPack != null ) {
                mainWindow.show( finalGameModPack );
            }
            else {
                mainWindow.show();
            }
            try {
                mainWindow.closedLatch.await();
            }
            catch ( InterruptedException e ) {
                Logger.logError(
                        "An error is preventing GUI completion handling. The login screen may not appear after logout." );
                e.printStackTrace();
            }
        }
        else if ( GameModeManager.isServer() ) {
            if ( finalGameModPack != null ) {
                play( finalGameModPack );
            }
            else {
                Logger.logError(
                        "There are no mod packs installed. Cannot launch server unless a mod pack is installed to start." );
            }
        }
    }

    /**
     * Configure the launcher application to use the logging utility class for output to file and console.
     *
     * @since 2.0
     */
    public static void configureLogger() {
        Timestamp logTimeStamp = new Timestamp( System.currentTimeMillis() );
        File logFile = SynchronizedFileManager.getSynchronizedFile(
                LocalPathManager.getLauncherLogFolderPath() + File.separator +
                        LauncherConstants.LAUNCHER_APPLICATION_NAME_TRIMMED + "_" +
                        GameModeManager.getCurrentGameMode().getStringName() + "_" +
                        LocalPathConstants.LOG_FILE_NAME_DATE_FORMAT.format( logTimeStamp ) + ".log" );
        try {
            Logger.initLogSys( logFile );
        }
        catch ( IOException e ) {
            e.printStackTrace();
            System.err.println( "An error was encountered while configuring the application logging system." );
        }
    }

    /**
     * Performs login when the launcher is in client mode. If a user is remembered, it will be loaded from memory and
     * logged in automatically. If a user is not remembered or cannot be logged in automatically, the login screen will
     * display.
     *
     * @since 2.0
     */
    public static void performClientLogin() {
        // Check for and load saved user from disk
        AuthAccount authAccount = AuthManager.getLoggedInAccount();

        // If no saved account, show message and login screen, otherwise continue.
        if ( authAccount == null ) {
            Logger.logStd( "A remembered user account was not found on disk. Showing login screen..." );

            // Show login screen
            LoginWindow loginWindow = new LoginWindow();
            loginWindow.show();

            // Wait for login screen to complete
            try {
                loginWindow.waitForLoginSuccess();
            }
            catch ( InterruptedException e ) {
                Logger.logError( "Unable to wait for pending login task." );
                closeApp();
            }

            // Close login screen once complete
            loginWindow.close();
        }
        else {
            // Renew token of saved account
            try {
                boolean authRefreshed = AuthService.refreshAuth( authAccount );
                AuthManager.writeAccountToDiskIfRemembered();
                if ( !authRefreshed ) {
                    Logger.logError(
                            "The authentication of the loaded user account was not refreshed. Try again later!" );
                }
            }
            catch ( AuthException e1 ) {
                e1.printStackTrace();
                Logger.logError(
                        "Unable to refresh the authentication of the remembered user account. Returning to login." );
                AuthManager.logout();
                restartFlag = true;
                restartApp();
            }
            Logger.logStd( "[" + authAccount.getFriendlyName() + "] was logged in to the launcher." );
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
            Logger.logError(
                    "Invalid arguments specified.\nUsage: launcher.jar [ -s [modpack_name] | -c [modpack_name] | modpack_name ]" );
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
        System.setProperty( "prism.lcdtext", "false" );
        System.setProperty( "prism.text", "t2k" );
        System.setProperty( "prism.order", "sw" );
    }

    /**
     * Performs launcher closing/clean up tasks necessary for application shut down or restart. This method must be able
     * to be called and complete without waiting at all times.
     *
     * @since 2.0
     */
    public static void cleanupApp() {
        Logger.logStd( "Performing application cleanup..." );
        GUIController.closeAllWindows();
        Logger.logStd( "Finished application cleanup" );
    }

    /**
     * Performs launcher closing tasks and the restarts the launcher application. This method must be able to be called
     * and complete without waiting at all times.
     *
     * @since 2.0
     */
    public static void restartApp() {
        restartFlag = true;
        cleanupApp();
    }

    /**
     * Performs launcher closing tasks and then closes the launcher application. This method must be able to be called
     * and complete without waiting at all times.
     *
     * @since 1.1
     */
    public static void closeApp() {
        Platform.setImplicitExit( true );
        cleanupApp();
        Logger.logStd( "See you soon!" );
        System.exit( 0 );
    }
}
