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

            // Start process and wait for finish — redact auth tokens before logging.
            String redacted = SensitiveDataRedactor.redact( command );
            if ( retryNumber > 0 ) {
                Logger.logStd( "Executing command (retry " + retryNumber + "): " + redacted );
            }
            else {
                Logger.logStd( "Executing command: " + redacted );
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
     * Convenience overload that leaves the child process's stdout/stderr as
     * default ({@link ProcessBuilder.Redirect#PIPE}) — the caller is responsible
     * for draining them. Equivalent to {@link #launchCommand(String, String, boolean)
     * launchCommand(command, workingDirectory, false)}.
     *
     * @since 3.0
     */
    public static Process launchCommand( String command, String workingDirectory ) throws IOException
    {
        return launchCommand( command, workingDirectory, false );
    }

    /**
     * Launches a command as a new process without blocking.
     *
     * <p>When {@code discardOutput} is {@code true}, the child's stdout and
     * stderr are wired to {@link ProcessBuilder.Redirect#DISCARD} <em>before</em>
     * the process starts — the kernel sinks the bytes itself and the JVM can't
     * stall waiting for someone to read its pipes. Use this when nothing on the
     * launcher side will consume the output (in-game console disabled, no
     * crash-log tail, etc.). Without it, the OS pipe buffer fills within a few
     * hundred ms of Forge logging and the child JVM blocks on its next
     * {@code System.out.println} — visible as "JVM is in Task Manager but the
     * Minecraft window never appears."</p>
     *
     * <p>When {@code discardOutput} is {@code false}, the child uses the
     * default {@code PIPE} redirect; the caller MUST drain
     * {@link Process#getInputStream()} and {@link Process#getErrorStream()}
     * (typically by attaching the in-game console GUI) or the child will
     * block as described above.</p>
     *
     * @param command          the full command string
     * @param workingDirectory the working directory
     * @param discardOutput    true → kernel-level DISCARD on stdout/stderr;
     *                         false → default PIPE for caller-side draining
     *
     * @return the started Process
     *
     * @throws IOException if the process cannot be started
     *
     * @since 2026.3
     */
    public static Process launchCommand( String command, String workingDirectory, boolean discardOutput )
            throws IOException
    {
        ProcessBuilder processBuilder = new ProcessBuilder( splitCommandLine( command ) )
                .redirectErrorStream( false )
                .directory( SynchronizedFileManager.getSynchronizedFile( workingDirectory ) );
        if ( discardOutput ) {
            processBuilder.redirectOutput( ProcessBuilder.Redirect.DISCARD );
            processBuilder.redirectError( ProcessBuilder.Redirect.DISCARD );
        }
        // Filter the inherited environment before handing it to Minecraft / Forge / mods
        // before spawning the game. Mods are unsandboxed JVM code; if the user happened to
        // have AWS_SECRET_KEY / OPENAI_API_KEY / similar in their shell when they launched
        // the game, any mod can read it via System.getenv(). We can't full-whitelist
        // because per-platform mod dependencies vary too much (graphics drivers, locale,
        // audio, XDG dirs, custom LWJGL paths). Pattern-based deny gets the realistic
        // risk without the compatibility tail.
        stripSensitiveEnv( processBuilder.environment() );
        // Redact auth tokens before logging — see SensitiveDataRedactor for the patterns.
        Logger.logStd( "Launching command: " + SensitiveDataRedactor.redact( command ) );
        return processBuilder.start();
    }

    /** Case-insensitive substrings that suggest an env var holds a credential or
     *  account-linked secret. Any var name containing any of these is removed from
     *  the spawned process's environment. Conservative — false positives just mean
     *  the game subprocess can't see a variable the user explicitly named with the
     *  word "TOKEN" or similar. */
    private static final String[] SENSITIVE_ENV_NAME_PARTS = {
            "TOKEN", "SECRET", "PASSWORD", "PASSWD", "PASSPHRASE",
            "API_KEY", "APIKEY", "PRIVATE_KEY", "ACCESS_KEY",
            "CREDENTIAL", "COOKIE", "SESSION_KEY", "AUTH_KEY"
    };

    /** Specific env-var prefixes for well-known cloud / service credentials. Matched
     *  by case-insensitive prefix. */
    private static final String[] SENSITIVE_ENV_NAME_PREFIXES = {
            "AWS_", "AZURE_", "GCP_", "GCLOUD_", "GOOGLE_APPLICATION_",
            "GITHUB_TOKEN", "GH_TOKEN", "NPM_TOKEN", "PYPI_TOKEN",
            "OPENAI_", "ANTHROPIC_", "STRIPE_", "TWILIO_", "SENDGRID_"
    };

    /**
     * Removes credential-ish env vars from the given environment map in place. Called
     * on the {@link ProcessBuilder#environment()} view, which mutates the builder's
     * env for the spawn — the launcher's own process environment is unaffected.
     *
     * <p>Visible to package code mainly for diagnostic / test access — the production
     * caller is {@link #launchCommand} above.
     */
    static void stripSensitiveEnv( java.util.Map< String, String > env )
    {
        if ( env == null || env.isEmpty() ) {
            return;
        }
        env.keySet().removeIf( ProcessUtilities::looksSensitive );
    }

    private static boolean looksSensitive( String name )
    {
        if ( name == null ) {
            return false;
        }
        String upper = name.toUpperCase( java.util.Locale.ROOT );
        for ( String part : SENSITIVE_ENV_NAME_PARTS ) {
            if ( upper.contains( part ) ) {
                return true;
            }
        }
        for ( String prefix : SENSITIVE_ENV_NAME_PREFIXES ) {
            if ( upper.startsWith( prefix ) ) {
                return true;
            }
        }
        return false;
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
