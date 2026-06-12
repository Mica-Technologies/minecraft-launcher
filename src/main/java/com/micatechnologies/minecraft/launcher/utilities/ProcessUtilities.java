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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
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
                Logger.logStd( LocalizationManager.format( "log.processUtil.executingCommandRetry", retryNumber, redacted ) );
            }
            else {
                Logger.logStd( LocalizationManager.format( "log.processUtil.executingCommand", redacted ) );
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
                        LocalizationManager.get( "processUtil.gameCrashed.message" ),
                        LocalizationManager.get( "processUtil.gameCrashed.retry" ) );
            }

            if ( retry ) {
                retryNumber++;
            }
        }
    }

    /**
     * Convenience overload that leaves the child process's stdout/stderr as
     * default ({@link ProcessBuilder.Redirect#PIPE}) — the caller is responsible
     * for draining them. Equivalent to {@link #launchCommand(String, String, ChildIoMode)
     * launchCommand(command, workingDirectory, ChildIoMode.PIPE)}.
     *
     * @since 3.0
     */
    public static Process launchCommand( String command, String workingDirectory ) throws IOException
    {
        return launchCommand( command, workingDirectory, ChildIoMode.PIPE );
    }

    /**
     * How the spawned child's stdout / stderr should be wired before the
     * process starts. The three modes correspond to the three observable
     * behaviors the launcher needs across its different game-mode paths;
     * picking the wrong one for the context either silently drops the
     * output or stalls the child JVM on a full pipe buffer.
     *
     * @since 2026.05
     */
    public enum ChildIoMode
    {
        /**
         * Default. The child's stdout / stderr are wired to
         * {@link ProcessBuilder.Redirect#PIPE} and the caller MUST drain
         * {@link Process#getInputStream()} / {@link Process#getErrorStream()}
         * (typically by attaching the in-game console GUI) or the child
         * blocks on its next {@code println} once the kernel pipe buffer
         * fills — usually within a few hundred ms of Forge logging.
         */
        PIPE,

        /**
         * Wire the child's stdout / stderr to
         * {@link ProcessBuilder.Redirect#DISCARD}. The kernel sinks the
         * bytes itself; no userspace reader is required, and the child
         * never stalls on a full pipe buffer. Use when nothing on the
         * launcher side will consume the output — e.g. client mode with
         * the in-game console disabled and no crash-log tail attached.
         * Without this, the symptom is "JVM is in Task Manager but the
         * Minecraft window never appears."
         */
        DISCARD,

        /**
         * Wire the child's stdout / stderr to
         * {@link ProcessBuilder.Redirect#INHERIT} — the child JVM is
         * handed the launcher's own stdout / stderr file descriptors and
         * writes directly to whatever the operator's launcher is attached
         * to (typically an SSH terminal). Zero userspace copying, zero
         * Java reader threads, kernel-managed line ordering. Used in
         * server mode where the launcher itself has nothing useful to do
         * with the game's log stream beyond surfacing it to the operator.
         */
        INHERIT
    }

    /**
     * Launches a command as a new process without blocking.
     *
     * <p>The {@link ChildIoMode} parameter picks how the child's stdout /
     * stderr are wired before the process starts. See each enum constant's
     * Javadoc for the trade-off behind each mode.</p>
     *
     * @param command          the full command string
     * @param workingDirectory the working directory
     * @param ioMode           how to wire the child's stdout / stderr
     *
     * @return the started Process
     *
     * @throws IOException if the process cannot be started
     *
     * @since 2026.3
     */
    public static Process launchCommand( String command, String workingDirectory, ChildIoMode ioMode )
            throws IOException
    {
        return launchCommand( splitCommandLine( command ), workingDirectory, ioMode );
    }

    /**
     * Launches a child process from a pre-split argv list, bypassing the
     * hand-rolled {@link #splitCommandLine(String)} parser entirely. Preferred
     * over the string-based overload for callers that already know each
     * argument's boundaries — gives back the OS-level guarantee that an arg
     * containing a quote / space / control char crosses to the child as one
     * literal arg with no shell interpretation, and removes the brittle
     * {@code "}-toggle escaping the launcher used to need in the game-launch
     * command (pack-name injection used to live there; see commit {@code 32f58ca}).
     *
     * @param argv             the full argv, including the executable as element 0
     * @param workingDirectory directory the child runs in
     * @param ioMode           how to wire the child's stdout / stderr — see {@link ChildIoMode}
     * @return the spawned process (non-blocking; the caller owns lifecycle)
     *
     * @throws IOException if the child failed to start
     *
     * @since 2026.5
     */
    public static Process launchCommand( List< String > argv, String workingDirectory, ChildIoMode ioMode )
            throws IOException
    {
        ProcessBuilder processBuilder = new ProcessBuilder( argv )
                .redirectErrorStream( false )
                .directory( SynchronizedFileManager.getSynchronizedFile( workingDirectory ) );
        switch ( ioMode ) {
            case DISCARD -> {
                processBuilder.redirectOutput( ProcessBuilder.Redirect.DISCARD );
                processBuilder.redirectError( ProcessBuilder.Redirect.DISCARD );
            }
            case INHERIT -> {
                processBuilder.redirectOutput( ProcessBuilder.Redirect.INHERIT );
                processBuilder.redirectError( ProcessBuilder.Redirect.INHERIT );
            }
            case PIPE -> {
                /* default ProcessBuilder behavior; nothing to set */
            }
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
        // Joined back with spaces for the log line only; the actual spawn uses the List.
        Logger.logStd( LocalizationManager.format( "log.processUtil.launchingCommand", SensitiveDataRedactor.redact( String.join( " ", argv ) ) ) );
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
                    LocalizationManager.get( "processUtil.gameCrashed.message" ),
                    LocalizationManager.get( "processUtil.gameCrashed.retry" ) );
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
