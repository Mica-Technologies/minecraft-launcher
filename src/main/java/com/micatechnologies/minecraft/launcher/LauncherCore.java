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
import com.micatechnologies.minecraft.launcher.gui.MCLauncherGameConsoleGui;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiController;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherLoginGui;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherMainGui;
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

    /**
     * String error reason for the application restarting. Null if there is no error.
     */
    private static String restartError = null;

    /**
     * The current launcher session. Each iteration of the restart loop creates a new session.
     */
    private static LauncherSession currentSession;

    /**
     * Launcher application main method/entry point.
     *
     * @param args launcher arguments
     *
     * @since 1.0
     */
    public static void main( String[] args ) {
        // Enforce single instance -- if another instance is already running, notify and exit
        if ( !SingleInstanceLock.tryAcquire() ) {
            javax.swing.SwingUtilities.invokeLater( () -> {
                javax.swing.JOptionPane.showMessageDialog( null,
                        "Mica Minecraft Launcher is already running.",
                        LauncherConstants.LAUNCHER_APPLICATION_NAME,
                        javax.swing.JOptionPane.INFORMATION_MESSAGE );
                System.exit( 0 );
            } );
            return;
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
                playProgressWindow.setUpperLabelText( "Launching: " + gameModPack.getPackName() );
                playProgressWindow.setSectionText( "Preparing..." );
                playProgressWindow.setDetailText( "" );
            }

            try {
                Logger.logDebug( LocalizationManager.LAUNCHING_MOD_PACK_TEXT + ": " + gameModPack.getFriendlyName() );
                MCLauncherProgressGui finalPlayProgressWindow = playProgressWindow;
                gameModPack.setProgressProvider( new GameModPackProgressProvider()
                {
                    @Override
                    public void updateProgressHandler( double percent, String sectionTitle, String detailText,
                                                        String downloadStatus ) {
                        Logger.logStd( sectionTitle + ": " + detailText + " - " + ( int ) percent + "%" );

                        if ( finalPlayProgressWindow != null ) {
                            // Section title goes in the middle (between title and progress bar)
                            if ( sectionTitle != null && !sectionTitle.isEmpty() ) {
                                finalPlayProgressWindow.setSectionText( sectionTitle );
                            }
                            // Detail text goes below the progress bar (smaller, dimmer)
                            finalPlayProgressWindow.setDetailText(
                                    detailText != null ? detailText : "" );
                            // Download speed/ETA below the detail text
                            finalPlayProgressWindow.setSpeedText(
                                    downloadStatus != null ? downloadStatus : "" );
                            finalPlayProgressWindow.setProgress( percent );
                            if ( percent >= 100.0 ) {
                                finalPlayProgressWindow.setSectionText( "Starting Minecraft..." );
                                finalPlayProgressWindow.setDetailText( "" );
                                finalPlayProgressWindow.setSpeedText( "" );
                                // Only hide if the in-game console won't be taking over the stage
                                if ( !ConfigManager.getInGameConsoleEnable() ) {
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
                    }
                } );
                gameModPack.startGame();
                gameModPack.saveInstalledVersion();
                gameModPack.recordLaunchStart();
                final long launchStartMs = System.currentTimeMillis();

                Process gameProcess = gameModPack.getLastLaunchedProcess();
                if ( gameProcess != null ) {
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
                                    // On crash, find and display crash report
                                    if ( exitCode != 0 ) {
                                        String crashReport = gameModPack.getLatestCrashReport();
                                        if ( crashReport != null ) {
                                            consoleGui.showCrashReport( crashReport );
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
                        // Console disabled: drain stdout/stderr in background threads to prevent
                        // the game process from blocking when OS pipe buffers fill up.
                        Thread stdoutDrain = new Thread( () -> {
                            try ( var is = gameProcess.getInputStream() ) {
                                is.transferTo( java.io.OutputStream.nullOutputStream() );
                            }
                            catch ( IOException ignored ) {}
                        }, "game-stdout-drain" );
                        Thread stderrDrain = new Thread( () -> {
                            try ( var is = gameProcess.getErrorStream() ) {
                                is.transferTo( java.io.OutputStream.nullOutputStream() );
                            }
                            catch ( IOException ignored ) {}
                        }, "game-stderr-drain" );
                        stdoutDrain.setDaemon( true );
                        stderrDrain.setDaemon( true );
                        stdoutDrain.start();
                        stderrDrain.start();

                        // Wait for game to exit, then check for crash
                        try {
                            int exitCode = gameProcess.waitFor();
                            // Record session duration
                            gameModPack.recordSessionEnd( System.currentTimeMillis() - launchStartMs );
                            if ( exitCode != 0 ) {
                                Logger.logError( "Game crashed with exit code " + exitCode );
                                // Show crash console even when console setting is off
                                String crashReport = gameModPack.getLatestCrashReport();
                                try {
                                    MCLauncherGameConsoleGui crashGui =
                                            MCLauncherGuiController.goToGameConsoleGui();
                                    if ( crashGui != null ) {
                                        crashGui.showCrashOnly( gameModPack.getPackName(), exitCode,
                                                                 crashReport, null );
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
                Logger.logError( e.getMessage() );
                Logger.logThrowable( e );
                returnToMainGuiOnError();
            }
            catch ( Exception e ) {
                Logger.logError( LocalizationManager.UNABLE_START_GAME_EXCEPTION_TEXT );
                Logger.logThrowable( e );
                returnToMainGuiOnError();
            }

            // If after runnable present, run it -- but NOT when the in-game console is managing
            // the UI lifecycle (it will return to main GUI via its own Close button)
            if ( after != null && !ConfigManager.getInGameConsoleEnable() ) {
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
    public static void doModpackSelection( String modPackName, String initialErrorMessage ) {
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

            if ( initialErrorMessage != null && initialErrorMessage.length() > 0 ) {
                Logger.logError( initialErrorMessage );
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
                            // Pipe server output to launcher console (stdout/stderr)
                            Thread stdoutPipe = new Thread( () -> {
                                try ( var is = proc.getInputStream();
                                      var reader = new java.io.BufferedReader(
                                              new java.io.InputStreamReader( is ) ) ) {
                                    String line;
                                    while ( ( line = reader.readLine() ) != null ) {
                                        System.out.println( "[SERVER] " + line );
                                    }
                                }
                                catch ( IOException ignored ) {}
                            }, "server-stdout-pipe" );
                            Thread stderrPipe = new Thread( () -> {
                                try ( var is = proc.getErrorStream();
                                      var reader = new java.io.BufferedReader(
                                              new java.io.InputStreamReader( is ) ) ) {
                                    String line;
                                    while ( ( line = reader.readLine() ) != null ) {
                                        System.err.println( "[SERVER/ERR] " + line );
                                    }
                                }
                                catch ( IOException ignored ) {}
                            }, "server-stderr-pipe" );
                            stdoutPipe.setDaemon( true );
                            stderrPipe.setDaemon( true );
                            stdoutPipe.start();
                            stderrPipe.start();

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
        // If a saved account exists, try to renew it first
        if ( MCLauncherAuthManager.hasExistingLogin() ) {
            // Show a progress indicator while contacting auth servers
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

            // Wire up progress callback so auth status updates appear in the progress GUI
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
                // Auth renewal failed -- clear saved account and fall through to login screen
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
            SingleInstanceLock.release();
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
        restartError = null;
        cleanupApp();
        currentSession.exitLatch.countDown();
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
        cleanupApp();
        currentSession.exitLatch.countDown();
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
        cleanupApp();
        currentSession.exitLatch.countDown();
        Logger.logStd( LocalizationManager.SEE_YOU_SOON_TEXT );
        System.exit( LauncherConstants.EXIT_STATUS_CODE_GOOD );
    }
}
