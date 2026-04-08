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

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import jodd.io.StreamGobbler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for process creation, execution, and lifecycle management. Extracted from {@link SystemUtilities} to
 * separate process-management concerns from general system utilities.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
public class ProcessUtilities
{
    /**
     * Executes the specified string command in the specified working directory. If the process exits with a non-zero
     * code, the user is prompted to retry. The method blocks until the process completes (or all retries are
     * exhausted).
     *
     * @param command          command to execute
     * @param workingDirectory working directory to execute in
     *
     * @throws IOException          if unable to start execution
     * @throws InterruptedException if unable to wait for execution to complete
     * @since 1.0
     */
    public static void executeStringCommand( String command, String workingDirectory )
    throws IOException, InterruptedException
    {
        boolean retry = true;
        int retryNumber = 0;

        while ( retry ) {
            // Reset retry flag
            retry = false;

            // Build process call directly to avoid cmd.exe line-length limits on Windows.
            ProcessBuilder processBuilder = new ProcessBuilder( splitCommandLine( command ) ).inheritIO()
                                                                                              .directory(
                                                                                                      SynchronizedFileManager.getSynchronizedFile(
                                                                                                              workingDirectory ) );

            // Start process and wait for finish
            if ( retryNumber > 0 ) {
                Logger.logStd( "Executing command (retry " + retryNumber + "): " + command );
            }
            else {
                Logger.logStd( "Executing command: " + command );
            }
            Process mcProcess = processBuilder.start();

            // Read output stream
            StreamGobbler outGobbler = null;
            StreamGobbler errGobbler = null;
            if ( ConfigManager.getEnhancedLogging() ) {
                outGobbler = new StreamGobbler( mcProcess.getInputStream(), System.out );
                outGobbler.start();
                errGobbler = new StreamGobbler( mcProcess.getErrorStream(), System.err );
                errGobbler.start();
            }

            // Wait for process to finish
            int returnCode = mcProcess.waitFor();
            if ( outGobbler != null ) {
                outGobbler.waitFor();
            }
            if ( errGobbler != null ) {
                errGobbler.waitFor();
            }

            // Check return code
            if ( returnCode != 0 ) {
                retry = Logger.logErrorConfirmRetry(
                        "Oops - The game has crashed! Try again, and check the log files if the issue persists.",
                        "Reload" );
            }

            if ( retry ) {
                retryNumber++;
            }
        }
    }

    /**
     * Launches a command as a new process without blocking. The process does NOT inherit IO, so its streams can be
     * captured by the caller (e.g., for the in-game console).
     *
     * @param command          the full command string
     * @param workingDirectory the working directory
     *
     * @return the started Process
     *
     * @throws IOException if the process cannot be started
     *
     * @since 3.0
     */
    public static Process launchCommand( String command, String workingDirectory ) throws IOException
    {
        ProcessBuilder processBuilder = new ProcessBuilder( splitCommandLine( command ) )
                .redirectErrorStream( false )
                .directory( SynchronizedFileManager.getSynchronizedFile( workingDirectory ) );
        Logger.logStd( "Launching command: " + command );
        return processBuilder.start();
    }

    /**
     * Waits for a process to complete, with optional retry on non-zero exit.
     *
     * @param process the process to wait for
     *
     * @throws InterruptedException if interrupted while waiting
     *
     * @since 3.0
     */
    public static void waitForProcess( Process process ) throws InterruptedException
    {
        int returnCode = process.waitFor();
        if ( returnCode != 0 ) {
            Logger.logErrorConfirmRetry(
                    "Oops - The game has crashed! Try again, and check the log files if the issue persists.",
                    "Reload" );
        }
    }

    /**
     * Splits a command-line string into individual arguments, respecting quoted segments.
     *
     * @param commandLine the command line to split
     *
     * @return list of individual arguments
     *
     * @since 2.0
     */
    public static List< String > splitCommandLine( String commandLine )
    {
        List< String > args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for ( int i = 0; i < commandLine.length(); i++ ) {
            char c = commandLine.charAt( i );
            if ( c == '"' ) {
                inQuotes = !inQuotes;
                continue;
            }

            if ( Character.isWhitespace( c ) && !inQuotes ) {
                if ( current.length() > 0 ) {
                    args.add( current.toString() );
                    current.setLength( 0 );
                }
            }
            else {
                current.append( c );
            }
        }

        if ( current.length() > 0 ) {
            args.add( current.toString() );
        }
        return args;
    }
}
