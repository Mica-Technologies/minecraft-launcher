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
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthResult;
import com.micatechnologies.minecraft.launcher.config.GameModeManager;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.RuntimeManager;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackProgressProvider;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiController;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherLoginGui;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherMainGui;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherProgressGui;
import com.micatechnologies.minecraft.launcher.utilities.AuthUtilities;
import com.micatechnologies.minecraft.launcher.utilities.DiscordRpcUtility;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;

import java.io.File;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.concurrent.CountDownLatch;

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
     * Launcher application restart flag. This flag must be true for the application to start. If this flag is set when
     * the launcher closes, it will restart.
     */
    private static boolean restartFlag = true;

    private static CountDownLatch exitLatch;

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
            exitLatch = new CountDownLatch( 1 );

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
                Logger.logError( LocalizationManager.UNABLE_TO_REACH_MOJANG_CANT_START_TEXT );
                closeApp();
            }

            // If client, do login
            if ( GameModeManager.getCurrentGameMode() == GameMode.CLIENT ) {
                Logger.logDebug( LocalizationManager.LAUNCHER_CLIENT_MODE_STARTING_LOGIN_TEXT );
                performClientLogin();
                Logger.logDebug( LocalizationManager.LOGIN_PROCESS_FINISHED_TEXT );
            }
            else {
                Logger.logDebug( LocalizationManager.LAUNCHER_NOT_CLIENT_MODE_SKIPPING_LOGIN_TEXT );
            }

            // Load mod pack information
            GameModPackManager.fetchModPackInfo();

            // Get local JDK
            RuntimeManager.verifyJre8();

            // Show main (mod pack selection) window
            doModpackSelection( initialModPackSelection );

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
        if ( gameModPack.getPackMinRAMGB() <= ConfigManager.getMaxRamInGb() ) {
            MCLauncherProgressGui playProgressWindow = null;
            try {
                if ( MCLauncherGuiController.shouldCreateGui() ) {
                    playProgressWindow = MCLauncherGuiController.goToProgressGui();
                }
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to load progress GUI due to an incomplete response from the GUI subsystem." );
                Logger.logThrowable( e );
            }

            if ( playProgressWindow != null ) {
                playProgressWindow.setLabelTexts( LocalizationManager.LAUNCHING_MOD_PACK_TEXT,
                                                  gameModPack.getFriendlyName() );
            }

            try {
                Logger.logDebug( LocalizationManager.LAUNCHING_MOD_PACK_TEXT + ": " + gameModPack.getFriendlyName() );
                MCLauncherProgressGui finalPlayProgressWindow = playProgressWindow;
                gameModPack.setProgressProvider( new GameModPackProgressProvider()
                {
                    @Override
                    public void updateProgressHandler( double percent, String text ) {
                        Logger.logStd( text + " - " + percent );

                        if ( finalPlayProgressWindow != null ) {
                            finalPlayProgressWindow.setUpperLabelText( "Launching: " + gameModPack.getPackName() );
                            finalPlayProgressWindow.setLowerLabelText( text );
                            finalPlayProgressWindow.setProgress( percent );
                            if ( percent >= 100.0 ) {
                                finalPlayProgressWindow.setLowerLabelText( "Starting Minecraft..." );
                                SystemUtilities.spawnNewTask( () -> {
                                    try {
                                        Thread.sleep( 3000 );
                                    }
                                    catch ( InterruptedException ignored ) {
                                    }
                                    finalPlayProgressWindow.hideStage();
                                } );

                            }
                        }
                    }
                } );
                gameModPack.startGame();
            }
            catch ( Exception e ) {
                Logger.logError( LocalizationManager.UNABLE_START_GAME_EXCEPTION_TEXT );
                Logger.logThrowable( e );
            }

            // If after runnable present, run it
            if ( after != null ) {
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
            Logger.logError( modPackName + " " + LocalizationManager.PACK_NOT_INSTALLED_WILL_DEFAULT_TO_FIRST_TEXT );
        }

        // Show gui or start start
        if ( GameModeManager.isClient() ) {
            MCLauncherMainGui mainWindow = null;
            try {
                mainWindow = MCLauncherGuiController.goToMainGui();
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to load main GUI due to an incomplete response from the GUI subsystem." );
                Logger.logThrowable( e );
            }
            if ( finalGameModPack != null && mainWindow != null ) {
                mainWindow.selectModpack( finalGameModPack );
            }
        }
        else if ( GameModeManager.isServer() ) {
            if ( finalGameModPack != null ) {
                play( finalGameModPack );
            }
            else {
                Logger.logError( LocalizationManager.NO_MOD_PACKS_INSTALLED_CANT_LAUNCH_SERVER_TEXT );
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
        File logFile = SynchronizedFileManager.getSynchronizedFile( LocalPathManager.getLauncherLogFolderPath() +
                                                                            File.separator +
                                                                            LauncherConstants.LAUNCHER_APPLICATION_NAME_TRIMMED +
                                                                            "_" +
                                                                            GameModeManager.getCurrentGameMode()
                                                                                           .getStringName() +
                                                                            "_" +
                                                                            LocalPathConstants.LOG_FILE_NAME_DATE_FORMAT
                                                                                    .format( logTimeStamp ) +
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
    public static void performClientLogin() {
        // If no saved account, show message and login screen, otherwise continue.
        if ( !MCLauncherAuthManager.hasExistingLogin() ) {
            Logger.logStd( LocalizationManager.REMEMBERED_ACCOUNT_NOT_FOUND_SHOWING_LOGIN );

            // Show login screen
            MCLauncherLoginGui loginWindow;
            try {
                loginWindow = MCLauncherGuiController.goToLoginGui();

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
        else {
            // Attempt to load/renew saved user account
            MCLauncherAuthResult authResult = MCLauncherAuthManager.renewExistingLogin();

            // Check login renewal result
            boolean authSuccess = AuthUtilities.checkAuthResponse( authResult );

            if ( authSuccess ) {
                Logger.logStd( "[" +
                                       authResult.getMinecraftUser().getName() +
                                       "] " +
                                       LocalizationManager.WAS_LOGGED_IN_TO_LAUNCHER_TEXT );
            }
            else {
                Logger.logError( LocalizationManager.AUTH_UNABLE_TO_REFRESH_TEXT );
                MCLauncherAuthManager.logout();
                restartFlag = true;
                restartApp();
            }
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
            DiscordRpcUtility.exit();
            MCLauncherGuiController.exit();
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
        restartFlag = true;
        cleanupApp();
        exitLatch.countDown();
    }

    /**
     * Performs launcher closing tasks and then closes the launcher application. This method must be able to be called
     * and complete without waiting at all times.
     *
     * @since 1.1
     */
    public static void closeApp() {
        cleanupApp();
        exitLatch.countDown();
        Logger.logStd( LocalizationManager.SEE_YOU_SOON_TEXT );
        System.exit( LauncherConstants.EXIT_STATUS_CODE_GOOD );
    }
}
