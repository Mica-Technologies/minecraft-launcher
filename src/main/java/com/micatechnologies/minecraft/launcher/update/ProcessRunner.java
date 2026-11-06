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

package com.micatechnologies.minecraft.launcher.update;

import java.io.IOException;
import java.util.List;

/**
 * Runs the external programs the update installers need ({@code pkexec}, {@code hdiutil},
 * {@code ditto}, the helper scripts), so tests can replace them with a fake that records the
 * commands.
 *
 * @since 2026.11
 */
public interface ProcessRunner
{
    /**
     * Runs a command to completion, its output discarded.
     *
     * @param command the program and its arguments
     *
     * @return the exit code
     *
     * @throws IOException when it cannot be started or the wait is interrupted
     */
    int run( List< String > command ) throws IOException;

    /**
     * Starts a command that outlives the launcher: nothing waits for it, and its output is
     * discarded so it holds none of the launcher's pipes.
     *
     * @param command the program and its arguments
     *
     * @throws IOException when it cannot be started
     */
    void startDetached( List< String > command ) throws IOException;

    /**
     * The runner backed by {@link ProcessBuilder}.
     *
     * @return the runner
     */
    static ProcessRunner system()
    {
        return new ProcessRunner()
        {
            @Override
            public int run( List< String > command ) throws IOException
            {
                Process process = new ProcessBuilder( command ).redirectOutput( ProcessBuilder.Redirect.DISCARD )
                                                               .redirectError( ProcessBuilder.Redirect.DISCARD )
                                                               .start();
                try {
                    return process.waitFor();
                }
                catch ( InterruptedException e ) {
                    Thread.currentThread().interrupt();
                    throw new IOException( "Interrupted while waiting for " + command.get( 0 ), e );
                }
            }

            @Override
            public void startDetached( List< String > command ) throws IOException
            {
                new ProcessBuilder( command ).redirectInput( ProcessBuilder.Redirect.from( nullDevice() ) )
                                             .redirectOutput( ProcessBuilder.Redirect.DISCARD )
                                             .redirectError( ProcessBuilder.Redirect.DISCARD )
                                             .start();
            }
        };
    }

    private static java.io.File nullDevice()
    {
        return new java.io.File( System.getProperty( "os.name", "" ).toLowerCase( java.util.Locale.ROOT )
                                       .startsWith( "windows" ) ? "NUL" : "/dev/null" );
    }
}
