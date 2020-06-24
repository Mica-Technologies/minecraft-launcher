package com.micatechnologies.minecraft.forgelauncher.utilities;

import com.micatechnologies.minecraft.forgelauncher.LauncherConstants;
import com.micatechnologies.minecraft.forgelauncher.gui.GUIController;
import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.ClientAndServer;
import javafx.stage.Stage;
import org.apache.commons.io.output.TeeOutputStream;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;

/**
 * Log management class for the Mica Forge Launcher application.
 *
 * @author Mica Technologies
 * @editors hawka97
 * @creator hawka97
 * @version 1.0
 * @since 2.0
 */
@ClientAndServer
public class LogUtils
{
    /**
     * Prefix for error logs
     *
     * @since 1.0
     */
    private static final String logErrorPrefix = "[" + LauncherConstants.LAUNCHER_APPLICATION_NAME + "/ERROR] ";

    /**
     * Prefix for standard logs
     *
     * @since 1.0
     */
    private static final String logStdPrefix = "[" + LauncherConstants.LAUNCHER_APPLICATION_NAME + "/STD] ";

    /**
     * Prefix for debug logs
     *
     * @since 1.0
     */
    private static final String logDebugPrefix = "[" + LauncherConstants.LAUNCHER_APPLICATION_NAME + "/DEBUG] ";

    /**
     * Initializes the logging system
     *
     * @param logFile log file
     *
     * @throws FileNotFoundException if unable to find log file
     * @since 1.0
     */
    @ClientAndServer
    public static void initLogSys( File logFile ) throws IOException {
        // Create parent directory(ies) if necessary
        final var mkdirs = logFile.getParentFile().mkdirs();
        if ( !mkdirs ) {
            LogUtils.logDebug(
                    "The log file directory (or parent) was not created. It may already exist, or access may have been denied." );
        }

        // Create a new log file
        var newFile = logFile.createNewFile();
        if ( !newFile ) {
            LogUtils.logError( "A log file was not created. The logging subsystem may not recorded logs to file." );
        }

        /*
         * File print stream
         */
        PrintStream file = new PrintStream( logFile );

        /*
         * Original console (System.out) print stream
         */
        PrintStream console = System.out;

        /*
         * Original error console (System.err) print stream
         */
        PrintStream consoleErr = System.err;

        // Create new tee print stream for System.out and System.err
        PrintStream sysOut = new PrintStream( new TeeOutputStream( console, file ) );
        PrintStream sysErr = new PrintStream( new TeeOutputStream( consoleErr, file ) );

        // Assign tee-d print streams
        System.setOut( sysOut );
        System.setErr( sysErr );
    }

    /**
     * Log an error with its prefix.
     *
     * @param errorLog error to log
     *
     * @since 1.0
     */
    @ClientAndServer
    public static void logError( String errorLog ) {
        // Show error on GUI, if GUI available
        Stage jfxStage = GUIController.getTopStageOrNull();
        if ( jfxStage != null ) {
            GuiUtils.showErrorMessage( errorLog, jfxStage );
        }

        System.err.println( logErrorPrefix + errorLog );
    }


    /**
     * Log a standard message with its prefix
     *
     * @param log message to log
     *
     * @since 1.0
     */
    @ClientAndServer
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
    @ClientAndServer
    public static void logDebug( String debugLog ) {
        System.out.println( logDebugPrefix + debugLog );
    }
}
