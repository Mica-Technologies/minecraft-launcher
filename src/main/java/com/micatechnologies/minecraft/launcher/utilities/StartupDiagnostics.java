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


package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A small log for the part of startup that runs before the launcher's own log exists, and for
 * anything uncaught after it. The main log is only created once the session starts, so a launch
 * that ends inside {@code main()} (another copy holding the single-instance port, a failed
 * hand-off to it, an exception before the session) used to leave no trace at all: the user saw
 * a busy cursor and nothing else. Every launch appends a line here first, so a missing line also
 * says something: the process never reached {@code main()}.
 *
 * <p>The file is {@code ~/.MicaMinecraftLauncher[DEV]/logs/startup.log}, anchored to the client
 * folder because the game mode isn't known yet this early (a mode-dependent path would resolve
 * to the working directory). It rolls over to {@code startup.log.1} past {@link #MAX_BYTES}.
 *
 * <p>Lines are English on purpose: they are written before {@code LocaleBootstrap} runs, and
 * loading {@code LocalizationManager} that early would pin its legacy fields to the wrong locale.
 * Writing never throws; a diagnostic log must not be a new way for startup to fail.
 *
 * @since 2026.10
 */
public final class StartupDiagnostics
{
    /** Size past which the log rolls over to {@code startup.log.1}. */
    static final long MAX_BYTES = 256 * 1024;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern( "yyyy-MM-dd HH:mm:ss.SSS" );

    private StartupDiagnostics() { /* static-only */ }

    /** The startup log's path, independent of the game mode. */
    public static Path logPath()
    {
        return Path.of( LocalPathConstants.CLIENT_MODE_LAUNCHER_FOLDER_PATH + LocalPathConstants.LOG_FOLDER,
                        "startup.log" );
    }

    /**
     * Appends one timestamped line, tagged with this process's id.
     *
     * @param message the line to record
     */
    public static void record( String message )
    {
        append( logPath(), message );
    }

    /**
     * Records an exception with its stack trace.
     *
     * @param message what was happening
     * @param error   the exception
     */
    public static void record( String message, Throwable error )
    {
        StringWriter trace = new StringWriter();
        error.printStackTrace( new PrintWriter( trace ) );
        record( message + System.lineSeparator() + trace.toString().stripTrailing() );
    }

    /**
     * Records this launch: version, platform and the argument count (not the arguments, which can
     * carry deep-link URLs).
     *
     * @param args the launcher's arguments
     */
    public static void recordLaunch( String[] args )
    {
        record( "Start " + LauncherConstants.LAUNCHER_APPLICATION_VERSION
                + ( LauncherConstants.LAUNCHER_IS_DEV ? " (dev)" : "" )
                + " on " + System.getProperty( "os.name" ) + " " + System.getProperty( "os.version" )
                + ", Java " + Runtime.version() + ", " + args.length + " argument(s)" );
    }

    /**
     * Installs a default handler that records any uncaught exception here before printing it to
     * stderr as the JVM's own handler would. Nothing else in the launcher sets one, and before the
     * main log exists stderr goes nowhere in the packaged app.
     */
    public static void installUncaughtExceptionHandler()
    {
        Thread.setDefaultUncaughtExceptionHandler( ( thread, error ) -> {
            record( "Uncaught exception on thread \"" + thread.getName() + "\"", error );
            System.err.print( "Exception in thread \"" + thread.getName() + "\" " );
            error.printStackTrace();
        } );
    }

    /** Appends a line to {@code file}, rolling it over first if it has grown past the limit. */
    static synchronized void append( Path file, String message )
    {
        try {
            Files.createDirectories( file.getParent() );
            if ( Files.isRegularFile( file ) && Files.size( file ) > MAX_BYTES ) {
                Files.move( file, file.resolveSibling( file.getFileName() + ".1" ),
                            StandardCopyOption.REPLACE_EXISTING );
            }
            String line = LocalDateTime.now().format( TIME ) + " [pid " + ProcessHandle.current().pid() + "] "
                          + message + System.lineSeparator();
            Files.writeString( file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                               StandardOpenOption.APPEND );
        }
        catch ( IOException | RuntimeException ignored ) {
            // Best-effort: never let the diagnostic log stop the launcher starting.
        }
    }
}
