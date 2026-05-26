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
    // Log-line prefixes are intentionally HARDCODED ASCII rather than
    // sourced from LocalizationManager.LOG_*_PREFIX. Logger is one of the
    // earliest classes to class-load during startup (RGB integration,
    // game-mode inference, FX prestart all log) and referencing
    // LocalizationManager.* fields in this class's static initializer
    // would trigger LocalizationManager class load BEFORE
    // LocaleBootstrap.apply runs — locking the resource bundle to the OS
    // default locale and ignoring the user's settings-override on the
    // next launcher restart. Diagnostic log prefixes are developer-facing
    // and don't need translation; the matching LOG_*_PREFIX keys in
    // DisplayStrings remain as legacy unused fields on LocalizationManager
    // (back-compat for the 89 static-final field surface).
    private static final String logErrorPrefix =
            "[" + LauncherConstants.LAUNCHER_APPLICATION_NAME + "/ERROR] ";

    private static final String logWarnPrefix =
            "[" + LauncherConstants.LAUNCHER_APPLICATION_NAME + "/WARN] ";

    private static final String logStdPrefix =
            "[" + LauncherConstants.LAUNCHER_APPLICATION_NAME + "/STD] ";

    private static final String logDebugPrefix =
            "[" + LauncherConstants.LAUNCHER_APPLICATION_NAME + "/DEBUG] ";

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

        // Tighten perms so a sibling user on a shared workstation can't read this user's
        // launcher log. Redaction (auth tokens etc.) is applied at log-write time, but
        // the launcher log also contains file paths, account user names, and other PII
        // that doesn't belong to other accounts on the machine. Best-effort — non-POSIX
        // FS without ACL support silently no-ops, which is no worse than today's behavior.
        com.micatechnologies.minecraft.launcher.utilities.FilePermissions.applyOwnerOnly(
                logFile.toPath() );

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
     * Variant of {@link #logError} for errors whose user-facing message was
     * deliberately structured across multiple lines (bullet lists, paragraph
     * breaks). Routes through {@link GUIUtilities#showErrorMessageMultiline}
     * so the newlines survive — the standard {@code showErrorMessage} path
     * collapses multi-line text to one line as a defense against accidental
     * stack-trace dumps.
     *
     * <p>File / stderr logging is unchanged; the structure is preserved
     * there as well so a debugger reads the same bullets the user sees.</p>
     *
     * @since 2026.3
     */
    public static void logErrorMultiline( String errorLog ) {
        Stage jfxStage = MCLauncherGuiController.getTopStageOrNull();
        if ( jfxStage != null ) {
            GUIUtilities.showErrorMessageMultiline( errorLog, jfxStage );
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

    /** Whether {@link #logDebug} is allowed to consult {@link ConfigManager}
     *  for the debug-logging toggle. Defaults to {@code false} so the
     *  earliest bootstrap-phase debug logs (e.g. from
     *  {@link com.micatechnologies.minecraft.launcher.utilities.SingleInstanceLock#tryAcquire})
     *  don't trigger a {@code ConfigStore.loadFromDisk} BEFORE
     *  {@code GameModeManager} has been initialized — which used to
     *  resolve the config path to the current working directory (server-
     *  mode fallback) and load a stale empty state into the in-memory
     *  JSON. Subsequent writes would land in the correct
     *  {@code ~/.MicaMinecraftLauncherDEV} folder while reads continued
     *  to use the stale cwd-loaded JSON — losing every config change
     *  the user made the previous session. The launcher flips this to
     *  true via {@link #enableConfigBackedDebugLogging} after
     *  {@code parseLauncherArgs} has run. In dev mode debug is also
     *  unconditionally enabled (matching {@code getDebugLogging}'s dev
     *  short-circuit), so visibility doesn't depend on the flag flip.
     */
    private static volatile boolean configBackedDebugReady = false;

    /**
     * Marks the launcher as past the game-mode bootstrap so {@link #logDebug}
     * is safe to consult {@link ConfigManager#getDebugLogging()}. Must be
     * called after {@code GameModeManager.setCurrentGameMode} so the
     * config-path resolver uses the right per-mode folder.
     */
    public static void enableConfigBackedDebugLogging() {
        configBackedDebugReady = true;
    }

    /**
     * Log a debug message with its prefix
     *
     * @param debugLog debug message to log
     *
     * @since 1.0
     */
    public static void logDebug( String debugLog ) {
        // Dev mode: always print debug (matches the long-standing
        // contract — the dev launcher is verbose by default). Production
        // mode: gate on the user-configured flag, but only after the
        // bootstrap has progressed far enough that consulting
        // ConfigManager is safe — see configBackedDebugReady doc above.
        if ( LauncherConstants.LAUNCHER_IS_DEV ) {
            System.out.println( logDebugPrefix + debugLog );
            return;
        }
        if ( !configBackedDebugReady ) {
            return;
        }
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

    /**
     * Log a silent warning with caller-supplied context plus the Throwable's
     * concrete class and message. Standardizes the "operation failed
     * (NPE): tkStage was null"-style format that catch blocks have been
     * spelling out by hand. Use over the plain string overload when logging
     * an exception so the class name is always present — easier to grep,
     * easier to recognise a recurring failure mode.
     *
     * <p>The stack trace itself is NOT printed here; pair this with
     * {@link #logThrowable(Throwable)} when the trace also matters.
     *
     * @param prefix    caller-supplied "what was being attempted" message —
     *                  e.g. {@code "MacOsVibrancy: NSWindow appearance set"}.
     *                  Must not be null; pass an empty string if there's
     *                  nothing useful to add beyond the exception itself.
     * @param throwable the caught exception; null falls back to logging
     *                  just the prefix.
     *
     * @since 3.5
     */
    public static void logWarningSilent( String prefix, Throwable throwable ) {
        if ( throwable == null ) {
            logWarningSilent( prefix );
            return;
        }
        String klass = throwable.getClass().getSimpleName();
        String msg = throwable.getMessage();
        if ( prefix == null || prefix.isEmpty() ) {
            logWarningSilent( klass + ( msg == null ? "" : ": " + msg ) );
        }
        else {
            logWarningSilent( prefix + " failed (" + klass
                                      + ( msg == null ? "" : "): " + msg )
                                      + ( msg == null ? ")" : "" ) );
        }
    }
}
