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

package com.micatechnologies.minecraft.launcher.tui;

import java.io.InputStream;
import java.io.PrintStream;

/**
 * Process-wide flag + captured console streams for the full-screen terminal UI mode (launched with
 * {@code --cli} / {@code --tui}). The launcher runs as a normal CLIENT (same config/paths as the
 * GUI) but renders a Lanterna TUI instead of the JavaFX window.
 *
 * <p>The <b>real</b> {@code System.out}/{@code System.in} are captured at the very start of
 * {@code main()} — before the logger reassigns {@code System.out} to a file stream — because the TUI
 * must render to the actual terminal while launcher logging is routed to a file so it can't corrupt
 * the screen. See {@code LauncherCore.main} / {@code configureLogger}.</p>
 *
 * @since 2026.6
 */
public final class TuiMode
{
    private TuiMode() { /* static-only */ }

    private static volatile boolean     enabled = false;
    private static volatile PrintStream realOut = null;
    private static volatile InputStream realIn  = null;

    /** @return whether {@code --cli}/{@code --tui} appears anywhere in the program arguments. */
    public static boolean requestedIn( String[] args )
    {
        if ( args == null ) {
            return false;
        }
        for ( String a : args ) {
            if ( "--cli".equals( a ) || "--tui".equals( a ) ) {
                return true;
            }
        }
        return false;
    }

    /** Activates TUI mode and stashes the real console streams for Lanterna to render against. */
    public static void enable( PrintStream realOut, InputStream realIn )
    {
        TuiMode.realOut = realOut;
        TuiMode.realIn = realIn;
        TuiMode.enabled = true;
    }

    public static boolean isEnabled()
    {
        return enabled;
    }

    /** The real terminal stdout captured before logging redirection (or {@code System.out} fallback). */
    public static PrintStream realOut()
    {
        return realOut != null ? realOut : System.out;
    }

    /** The real terminal stdin captured before logging redirection (or {@code System.in} fallback). */
    public static InputStream realIn()
    {
        return realIn != null ? realIn : System.in;
    }
}
