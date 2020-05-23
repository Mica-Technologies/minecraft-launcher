package com.micatechnologies.minecraft.forgelauncher.utilities;

import com.micatechnologies.minecraft.forgelauncher.LauncherConstants;
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
 * @version 1.0
 */
public class Logger {

    /**
     * Initializes the logging system
     *
     * @param logFile log file
     *
     * @throws FileNotFoundException if unable to find log file
     */
    public static void initLogSys( File logFile ) throws IOException {
        // Create log file if necessary
        if (!logFile.isFile()) {
            logFile.getParentFile().mkdirs();
            logFile.createNewFile();
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
     * Prefix for error logs
     */
    private static final String logErrorPrefix = "[" + LauncherConstants.LAUNCHER_APPLICATION_NAME + "/ERROR] ";

    /**
     * Log an error with its prefix.
     *
     * @param errorLog error to log
     */
    public static void logError( String errorLog ) {
        System.err.println( logErrorPrefix + errorLog );
    }

    /**
     * Log an error with its prefix and show a GUI error message
     *
     * @param errorLog error to log
     * @param jfxStage JavaFX stage to generate GUI error message from
     */
    public static void logError( String errorLog, Stage jfxStage ) {
        if ( jfxStage != null ) GUIUtils.showErrorMessage( errorLog, jfxStage );
        System.err.println( logErrorPrefix + errorLog );
    }

    /**
     * Prefix for standard logs
     */
    private static final String logStdPrefix = "[" + LauncherConstants.LAUNCHER_APPLICATION_NAME + "/STD] ";

    /**
     * Log a standard message with its prefix
     *
     * @param log message to log
     */
    public static void logStd( String log ) {
        System.out.println( logStdPrefix + log );
    }

    /**
     * Prefix for debug logs
     */
    private static final String logDebugPrefix = "[" + LauncherConstants.LAUNCHER_APPLICATION_NAME + "/DEBUG] ";

    /**
     * Log a debug message with its prefix
     *
     * @param debugLog debug message to log
     */
    public static void logDebug( String debugLog ) {
        System.out.println( logDebugPrefix + debugLog );
    }
}
