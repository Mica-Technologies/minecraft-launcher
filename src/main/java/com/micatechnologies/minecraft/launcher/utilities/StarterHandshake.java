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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Tells the Windows native starter how far startup got, and names the executable to relaunch.
 *
 * <p>On Windows the installed {@code Mica Minecraft Launcher.exe} is a small native starter. It
 * runs the jpackage launcher ({@code ... Core.exe}), waits for it, and reports a failure the
 * launcher could not report itself: a JVM that never starts (a damaged install), or a crash. To
 * tell those apart from exits the launcher already explained, it creates a state file, passes its
 * path in {@value #STATE_ENV}, and reads it back after the launcher exits. This class writes the
 * stages into that file:
 * <ul>
 *   <li>{@code main}: Java started and reached {@code main()};</li>
 *   <li>{@code ready}: a launcher window is on screen, so the user can see what happens next;</li>
 *   <li>{@code handled}: the launcher reported the outcome itself (its own dialog, a console
 *       tool's output), so an unusual exit code needs nothing more.</li>
 * </ul>
 * A later stage never goes back to an earlier one. Without the starter (macOS, Linux, an IDE run,
 * a taskbar pin that starts the Core exe directly) the variable is unset and this does nothing.
 *
 * <p>The starter's path arrives in {@value #STARTER_ENV}. Anything that starts the launcher
 * again, or registers it with the OS, should use {@link #launcherExecutable()} so it goes
 * through the starter too.
 *
 * @since 2026.10
 */
public final class StarterHandshake
{
    /** Environment variable holding the starter's state file. */
    static final String STATE_ENV = "MMCL_STARTER_STATE";

    /** Environment variable holding the starter's own executable path. */
    static final String STARTER_ENV = "MMCL_STARTER_PATH";

    /** Startup stages, in order. */
    enum Stage
    {
        MAIN( "main" ), READY( "ready" ), HANDLED( "handled" );

        final String word;

        Stage( String word )
        {
            this.word = word;
        }
    }

    private static Stage written;

    private StarterHandshake() { /* static-only */ }

    /** Records that Java reached {@code main()}. */
    public static void reachedMain()
    {
        advance( Stage.MAIN );
    }

    /** Records that a launcher window is showing. Cheap after the first call. */
    public static void windowShown()
    {
        advance( Stage.READY );
    }

    /** Records that the launcher has told the user (or a console caller) how this run ends. */
    public static void reportedOutcome()
    {
        advance( Stage.HANDLED );
    }

    /**
     * The executable that starts this launcher: the native starter when there is one, else the
     * jpackage launcher.
     *
     * @return the path, or {@code null} outside a packaged install
     */
    public static String launcherExecutable()
    {
        return launcherExecutable( System.getenv( STARTER_ENV ), System.getProperty( "jpackage.app-path" ) );
    }

    /**
     * Picks the starter when it is given and exists, else the jpackage launcher.
     *
     * @param starter   the {@value #STARTER_ENV} value, or {@code null}
     * @param jpackage  the {@code jpackage.app-path} value, or {@code null}
     *
     * @return the path, or {@code null} when neither is usable
     */
    static String launcherExecutable( String starter, String jpackage )
    {
        if ( starter != null && !starter.isBlank() ) {
            try {
                if ( Files.isRegularFile( Path.of( starter ) ) ) {
                    return starter;
                }
            }
            catch ( InvalidPathException ignored ) {
                // Fall through to the jpackage launcher.
            }
        }
        return jpackage == null || jpackage.isBlank() ? null : jpackage;
    }

    private static synchronized void advance( Stage stage )
    {
        if ( written != null && written.ordinal() >= stage.ordinal() ) {
            return;
        }
        written = stage;
        write( System.getenv( STATE_ENV ), stage );
    }

    /**
     * Overwrites the starter's state file with the stage. The file must already exist: the
     * starter creates it, and this never creates one somewhere an inherited variable points.
     *
     * @param stateFile the {@value #STATE_ENV} value, or {@code null}
     * @param stage     the stage reached
     *
     * @return whether the file was written
     */
    static boolean write( String stateFile, Stage stage )
    {
        if ( stateFile == null || stateFile.isBlank() ) {
            return false;
        }
        try {
            Files.writeString( Path.of( stateFile ), stage.word, StandardCharsets.US_ASCII,
                               StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING );
            return true;
        }
        catch ( IOException | RuntimeException e ) {
            // Gone already, or not ours to write: the starter then simply knows less.
            return false;
        }
    }
}
