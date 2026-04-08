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

package com.micatechnologies.minecraft.launcher.files;

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiController;
import com.micatechnologies.minecraft.launcher.gui.GUIUtilities;
import javafx.stage.Stage;
import org.apache.commons.io.output.TeeOutputStream;

import java.io.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Class for managing the log output of the application at the applicable logging level.
 *
 * @author Mica Technologies
 * @version 1.1
 * @since 1.0
 */
public class Logger
{
    /**
     * Prefix for error logs
     *
     * @since 1.0
     */
    private static final String logErrorPrefix = "[" +
            LauncherConstants.LAUNCHER_APPLICATION_NAME +
            "/" +
            LocalizationManager.LOG_ERROR_PREFIX +
            "] ";

    /**
     * Prefix for warning logs
     *
     * @since 1.1
     */
    private static final String logWarnPrefix = "[" +
            LauncherConstants.LAUNCHER_APPLICATION_NAME +
            "/" +
            LocalizationManager.LOG_WARNING_PREFIX +
            "] ";

    /**
     * Prefix for standard logs
     *
     * @since 1.0
     */
    private static final String logStdPrefix = "[" +
            LauncherConstants.LAUNCHER_APPLICATION_NAME +
            "/" +
            LocalizationManager.LOG_STANDARD_PREFIX +
            "] ";

    /**
     * Prefix for debug logs
     *
     * @since 1.0
     */
    private static final String logDebugPrefix = "[" +
            LauncherConstants.LAUNCHER_APPLICATION_NAME +
            "/" +
            LocalizationManager.LOG_DEBUG_PREFIX +
            "] ";

    /**
     * Buffered output stream used for writing to the log file.
     *
     * @since 1.1
     */
    private static volatile BufferedOutputStream fileBufferedOutputStream = null;

    /**
     * Scheduled executor for periodic log flushing. Stored so it can be shut down cleanly.
     *
     * @since 2.0
     */
    private static ScheduledExecutorService logFlushScheduler = null;

    /**
     * Shuts down the logging system by flushing and closing any open {@link OutputStream}s.
     *
     * @throws IOException if unable to flush or close an {@link OutputStream}
     * @since 1.1
     */
    public static void shutdownLogSys() throws IOException {
        if ( logFlushScheduler != null ) {
            logFlushScheduler.shutdownNow();
            logFlushScheduler = null;
        }
        if ( fileBufferedOutputStream != null ) {
            fileBufferedOutputStream.flush();
            fileBufferedOutputStream.close();
        }
    }

    /**
     * Initializes the logging system
     *
     * @param logFile log file
     *
     * @throws FileNotFoundException if unable to find log file
     * @since 1.0
     */
    /**
     * Maximum log file size before rotation (10 MB).
     */
    private static final long MAX_LOG_FILE_SIZE = 10L * 1024L * 1024L;

    /**
     * Maximum number of rotated backup log files to retain.
     */
    private static final int MAX_LOG_BACKUPS = 3;

    public static void initLogSys( File logFile ) throws IOException {
        // Create parent directory(ies) if necessary
        final var mkdirs = logFile.getParentFile().mkdirs();
        if ( !mkdirs && !logFile.getParentFile().exists() ) {
            Logger.logDebug( LocalizationManager.LOG_FILE_DIR_NOT_CREATED_TEXT );
        }

        // Rotate existing log file if it exceeds the size threshold
        if ( logFile.exists() && logFile.length() > MAX_LOG_FILE_SIZE ) {
            rotateLogFiles( logFile );
        }

        // Create a new log file
        var newFile = logFile.createNewFile();
        if ( !newFile ) {
            Logger.logError( LocalizationManager.LOG_FILE_NOT_CREATED_TEXT );
        }

        /*
         * File print stream
         */
        boolean scheduled = false;
        FileOutputStream fileOutputStream = new FileOutputStream( logFile );
        fileBufferedOutputStream = new BufferedOutputStream( fileOutputStream );
        try {
            logFlushScheduler = Executors.newScheduledThreadPool( 1, r -> {
                Thread t = new Thread( r, "log-flush" );
                t.setDaemon( true );
                return t;
            } );
            logFlushScheduler.scheduleAtFixedRate( () -> {
                try {
                    fileBufferedOutputStream.flush();
                }
                catch ( IOException e ) {
                    Logger.logError( "Unable to flush log stream to file!" );
                }
            }, 5, 5, TimeUnit.SECONDS );
            scheduled = true;
        }
        catch ( Exception e ) {
            Logger.logError( "Unable to schedule log stream flush!" );
        }

        /*
         * Original console (System.out) print stream
         */
        PrintStream console = System.out;

        /*
         * Original error console (System.err) print stream
         */
        PrintStream consoleErr = System.err;

        // Create new tee print stream for System.out and System.err
        PrintStream sysOut;
        PrintStream sysErr;
        if ( scheduled ) {
            sysOut = new PrintStream( new TeeOutputStream( console, fileBufferedOutputStream ) );
            sysErr = new PrintStream( new TeeOutputStream( consoleErr, fileBufferedOutputStream ) );
        }
        else {
            sysOut = new PrintStream( new TeeOutputStream( console, fileOutputStream ) );
            sysErr = new PrintStream( new TeeOutputStream( consoleErr, fileOutputStream ) );
            Logger.logErrorSilent( "Falling back to non-buffered log stream. Performance may be degraded!" );
        }

        // Assign tee-d print streams
        System.setOut( sysOut );
        System.setErr( sysErr );
        Logger.logStd( LocalizationManager.LOG_SYSTEM_INITIALIZED_TEXT );
    }

    /**
     * Rotates log files by renaming the current log to {@code .1}, shifting existing backups ({@code .1} &rarr;
     * {@code .2}, etc.), and deleting backups beyond {@link #MAX_LOG_BACKUPS}.
     *
     * @param logFile the current log file to rotate
     */
    private static void rotateLogFiles( File logFile )
    {
        String basePath = logFile.getAbsolutePath();

        // Delete the oldest backup if it exists
        File oldest = new File( basePath + "." + MAX_LOG_BACKUPS );
        if ( oldest.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            oldest.delete();
        }

        // Shift existing backups: .2 -> .3, .1 -> .2, etc.
        for ( int i = MAX_LOG_BACKUPS - 1; i >= 1; i-- ) {
            File from = new File( basePath + "." + i );
            File to = new File( basePath + "." + ( i + 1 ) );
            if ( from.exists() ) {
                //noinspection ResultOfMethodCallIgnored
                from.renameTo( to );
            }
        }

        // Rename current log file to .1
        //noinspection ResultOfMethodCallIgnored
        logFile.renameTo( new File( basePath + ".1" ) );
    }

    /**
     * Log an error with its prefix.
     *
     * @param errorLog error to log
     *
     * @since 1.0
     */
    public static void logError( String errorLog ) {
        // Show error on GUI, if GUI available
        Stage jfxStage = MCLauncherGuiController.getTopStageOrNull();
        if ( jfxStage != null ) {
            GUIUtilities.showErrorMessage( errorLog, jfxStage );
        }

        logErrorSilent( errorLog );
    }

    /**
     * Log an error with its prefix and confirm for retry.
     *
     * @param errorLog error to log
     *
     * @return true if retry, false otherwise
     *
     * @since 1.0
     */
    public static boolean logErrorConfirmRetry( String errorLog, String retryText ) {
        // Show error on GUI, if GUI available
        Stage jfxStage = MCLauncherGuiController.getTopStageOrNull();
        boolean retry = false;
        if ( jfxStage != null ) {
            retry = GUIUtilities.showErrorMessageRetry( errorLog, jfxStage, retryText );
        }

        logErrorSilent( errorLog );
        return retry;
    }

    /**
     * Log a silent error with its prefix.
     *
     * @param errorLog silent error to log
     *
     * @since 1.0
     */
    public static void logErrorSilent( String errorLog ) {
        System.err.println( logErrorPrefix + errorLog );
    }

    /**
     * Log a standard message with its prefix
     *
     * @param log message to log
     *
     * @since 1.0
     */
    public static void logStd( String log ) {
        System.out.println( logStdPrefix + log );
    }

    /**
     * Log a debug message with its prefix
     *
     * @param debugLog debug message to log
     *
     * @since 1.0
     */
    public static void logDebug( String debugLog ) {
        if ( ConfigManager.getDebugLogging() ) {
            System.out.println( logDebugPrefix + debugLog );
        }
    }

    /**
     * Log a throwable.
     *
     * @param throwable error to log
     *
     * @since 1.0
     */
    public static void logThrowable( Throwable throwable ) {
        throwable.printStackTrace( System.err );
    }

    /**
     * Log a warning with its prefix.
     *
     * @param warningLog warning to log
     *
     * @since 1.0
     */
    public static void logWarning( String warningLog ) {
        // Show warning on GUI, if GUI available
        Stage jfxStage = MCLauncherGuiController.getTopStageOrNull();
        if ( jfxStage != null ) {
            GUIUtilities.showWarningMessage( warningLog, jfxStage );
        }

        logWarningSilent( warningLog );
    }

    /**
     * Log a silent warning with its prefix.
     *
     * @param warningLog warning to log
     *
     * @since 1.0
     */
    public static void logWarningSilent( String warningLog ) {
        System.err.println( logWarnPrefix + warningLog );
    }
}
