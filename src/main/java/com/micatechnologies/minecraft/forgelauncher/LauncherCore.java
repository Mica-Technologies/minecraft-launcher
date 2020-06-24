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

import com.micatechnologies.minecraft.forgelauncher.auth.AuthAccount;
import com.micatechnologies.minecraft.forgelauncher.auth.AuthService;
import com.micatechnologies.minecraft.forgelauncher.auth.AuthManager;
import com.micatechnologies.minecraft.forgelauncher.config.GameModeManager;
import com.micatechnologies.minecraft.forgelauncher.consts.FileConstants;
import com.micatechnologies.minecraft.forgelauncher.exceptions.AuthException;
import com.micatechnologies.minecraft.forgelauncher.utilities.objects.GameMode;
import com.micatechnologies.minecraft.forgelauncher.gui.LoginWindow;
import com.micatechnologies.minecraft.forgelauncher.utilities.LogUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.NetworkUtils;

import java.io.File;
import java.io.IOException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;

/**
 * Launcher core class. This class is the main entry point of the Mica Forge Launcher, and handles the main processes
 * that are required by the launcher for full functionality, such as login, starting game, etc.
 *
 * @author Mica Technologies/hawka97
 * @version 2.0
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

            // Set game mode from parameters. If unspecified, infer game mode from presence of graphics environment
            String initialModPackSelection = parseLauncherArgs( args );

            // Apply system properties
            applySystemProperties();

            // Configure logging
            configureLogger();

            // Check for internet connection. Close if unable to connect
            if ( !NetworkUtils.isMojangAuthReachable() ) {
                LogUtils.logError(
                        "Unable to reach the Mojang authentication servers! Cannot start launcher. Try again later or contact support." );
                closeApp();
            }

            // If client, do login
            if ( GameModeManager.getCurrentGameMode() == GameMode.CLIENT ) {
                LogUtils.logDebug( "Launcher is running in client game mode. Starting login..." );
                performClientLogin();
                LogUtils.logDebug( "The login process has finished." );
            }
            else {
                LogUtils.logDebug( "Launcher is not in client game mode. Skipping authentication/login handler." );
            }

            // Load mod pack information

            // Get local JDK

            // Show mod pack selection
        }
    }

    /**
     * Configure the launcher application to use the logging utility class for output to file and console.
     *
     * @since 2.0
     */
    public static void configureLogger() {
        Timestamp logTimeStamp = new Timestamp( System.currentTimeMillis() );
        File logFile = new File(
                getLogFolderPath() + File.separator + LauncherConstants.LAUNCHER_APPLICATION_NAME_TRIMMED + "_" +
                        GameModeManager.getCurrentGameMode().getStringName() + "_" +
                        FileConstants.LOG_FILE_NAME_DATE_FORMAT.format( logTimeStamp ) + ".log" );
        try {
            LogUtils.initLogSys( logFile );
        }
        catch ( IOException e ) {
            e.printStackTrace();
            System.err.println( "An error was encountered while configuring the application logging system." );
        }
    }

    public static void performClientLogin() {
        // Check for and load saved user from disk
        AuthAccount authAccount = AuthManager.getLoggedInAccount();

        // If no saved account, show message and login screen, otherwise continue.
        if ( authAccount == null ) {
            LogUtils.logStd( "A remembered user account was not found on disk. Showing login screen..." );

            // Show login screen
            LoginWindow loginWindow = new LoginWindow();
            loginWindow.show();

            // Wait for login screen to complete
            try {
                loginWindow.waitForLoginSuccess();
            }
            catch ( InterruptedException e ) {
                LogUtils.logError( "Unable to wait for pending login task." );
                closeApp();
            }

            // Close login screen once complete
            loginWindow.close();
        }
        else {
            // Renew token of saved account
            try {
                AuthService.refreshAuth( authAccount, LauncherApp.getClientToken() );
            }
            catch ( AuthException e1 ) {
                e1.printStackTrace();
                LogUtils.logError(
                        "Unable to refresh the authentication of the remembered user account. Returning to login." );
                try {
                    AuthManager.logout();
                }
                catch ( IOException e2 ) {
                    e2.printStackTrace();
                }
                restartFlag = true;
                restartApp();
            }
            LogUtils.logStd( "[" + authAccount.getFriendlyName() + "] was logged in to the launcher." );
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
            LogUtils.logError(
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
    }

    /**
     * Gets the install path of the launcher.
     *
     * @return launcher install path
     *
     * @since 1.0
     */
    public static String getLauncherInstallPath() {
        String launcherInstallPath = LauncherConstants.LAUNCHER_CLIENT_INSTALLATION_DIRECTORY;
        if ( GameModeManager.getCurrentGameMode() == GameMode.SERVER ) {
            launcherInstallPath = LauncherConstants.LAUNCHER_SERVER_INSTALLATION_DIRECTORY;
        }
        return launcherInstallPath;
    }

    /**
     * Gets the log folder path of the launcher.
     *
     * @return launcher log folder path
     *
     * @since 1.0
     */
    public static String getLogFolderPath() {
        return getLauncherInstallPath() + File.separator + LauncherConstants.LOG_FOLDER_NAME;
    }

    /**
     * Performs launcher closing/clean up tasks necessary for application shut down or restart. This method must be able
     * to be called and complete without waiting at all times.
     *
     * @since 2.0
     */
    public static void cleanupApp() {

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
        cleanupApp();
        System.exit( 0 );
    }
}
