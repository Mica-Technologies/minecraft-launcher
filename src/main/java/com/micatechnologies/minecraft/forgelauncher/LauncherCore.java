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

import com.micatechnologies.minecraft.forgelauncher.game.GameMode;
import com.micatechnologies.minecraft.forgelauncher.utilities.Logger;
import com.micatechnologies.minecraft.forgelauncher.utilities.NetworkUtils;

import java.awt.*;
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
     * Current launcher game mode. This value is updated on start up in {@link #main(String[])}.
     *
     * @since 1.0
     */
    private static GameMode gameMode;

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
            // Apply system properties
            applySystemProperties();

            // Configure logging
            configureLogger();

            // Set game mode from parameters. If unspecified, infer game mode from presence of graphics environment
            String initialModPackSelection = parseLauncherArgs( args );

            // Check for internet connection. Close if unable to connect
            if ( NetworkUtils.isMojangAuthReachable() ) {
                Logger.logError(
                        "Unable to reach the Mojang authentication servers! Cannot start launcher. Try again later or contact support." );
                closeApp();
            }

            // If client, do login

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
        SimpleDateFormat logFileNameTimeStampFormat = new SimpleDateFormat( "yyyy-MM-dd--HH-mm-ss" );
        File logFile = new File(
                getLogFolderPath() + File.separator + LauncherConstants.LAUNCHER_APPLICATION_NAME_TRIMMED + "_" +
                        getGameMode().getStringName() + "_" +
                        logFileNameTimeStampFormat.format( logTimeStamp ) + ".log" );
        try {
            Logger.initLogSys( logFile );
        }
        catch ( IOException e ) {
            e.printStackTrace();
            System.err.println( "An error was encountered while configuring the application logging system." );
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
        if ( args.length == 0 && GraphicsEnvironment.isHeadless() ) {
            gameMode = GameMode.SERVER;
        }
        else if ( args.length == 0 && !GraphicsEnvironment.isHeadless() ) {
            gameMode = GameMode.CLIENT;
        }
        else if ( args.length == 1 && args[ 0 ].equalsIgnoreCase( LauncherConstants.PROGRAM_ARG_CLIENT_MODE ) ) {
            gameMode = GameMode.CLIENT;
        }
        else if ( args.length == 1 && args[ 0 ].equalsIgnoreCase( LauncherConstants.PROGRAM_ARG_SERVER_MODE ) ) {
            gameMode = GameMode.SERVER;
        }
        else if ( args.length == 1 ) {
            initialModPackSelection = args[ 0 ];
        }
        else if ( args.length == 2 && args[ 0 ].equalsIgnoreCase( LauncherConstants.PROGRAM_ARG_CLIENT_MODE ) ) {
            gameMode = GameMode.CLIENT;
            initialModPackSelection = args[ 1 ];
        }
        else if ( args.length == 2 && args[ 0 ].equalsIgnoreCase( LauncherConstants.PROGRAM_ARG_SERVER_MODE ) ) {
            gameMode = GameMode.SERVER;
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
    }

    /**
     * Gets the current launcher game mode.
     *
     * @return game mode
     *
     * @since 1.0
     */
    public synchronized static GameMode getGameMode() {
        return gameMode;
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
        if ( getGameMode() == GameMode.SERVER ) {
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
        cleanupApp();
        restartFlag = true;

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
