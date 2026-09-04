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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TuiMode#requestedIn(String[])} — the very first decision the launcher
 * makes about whether to render Lanterna's full-screen terminal UI instead of the JavaFX
 * window. It runs before the logger reassigns {@code System.out}, so a regression here
 * either strands a user who passed {@code --cli}/{@code --tui} in the normal GUI flow (no
 * window pops up on a headless SSH session) or, the opposite failure, silently switches a
 * normal desktop launch into the terminal UI.
 *
 * <p>This class deliberately never calls {@link TuiMode#enable(java.io.PrintStream,
 * java.io.InputStream)}: {@code enabled} is a one-way {@code volatile boolean} with no
 * reset method, so flipping it here would leak into every other test class that shares
 * this JVM for the rest of the run. The default-state assertions below only hold as long
 * as nothing in the suite calls {@code enable()} — true today, since {@code enable()} is
 * only ever invoked from {@code LauncherCore.main}.</p>
 */
class TuiModeTest
{
    @Test
    void nullArgsIsNotRequested()
    {
        assertFalse( TuiMode.requestedIn( null ) );
    }

    @Test
    void emptyArgsIsNotRequested()
    {
        assertFalse( TuiMode.requestedIn( new String[ 0 ] ) );
    }

    @Test
    void cliFlagAloneIsRequested()
    {
        assertTrue( TuiMode.requestedIn( new String[] { "--cli" } ) );
    }

    @Test
    void tuiFlagAloneIsRequested()
    {
        assertTrue( TuiMode.requestedIn( new String[] { "--tui" } ) );
    }

    @Test
    void flagAnywhereAmongOtherArgsIsRequested()
    {
        assertTrue( TuiMode.requestedIn( new String[] { "-s", "MyPack", "--cli" } ) );
        assertTrue( TuiMode.requestedIn( new String[] { "--tui", "-c" } ) );
    }

    @Test
    void unrelatedArgsAreNotRequested()
    {
        assertFalse( TuiMode.requestedIn( new String[] { "-c", "MyPack" } ) );
        assertFalse( TuiMode.requestedIn( new String[] { "--help" } ) );
    }

    /**
     * The check is an exact, case-sensitive {@code equals} — a near-miss like {@code --CLI}
     * or a value with trailing content is not recognized. Pinning this down matters because
     * a user mistyping the flag should fall through to the normal GUI/server flow rather than
     * silently matching.
     */
    @Test
    void nearMissesAreNotRequested()
    {
        assertFalse( TuiMode.requestedIn( new String[] { "--CLI" } ) );
        assertFalse( TuiMode.requestedIn( new String[] { "--cli " } ) );
        assertFalse( TuiMode.requestedIn( new String[] { "-cli" } ) );
    }

    /**
     * Before {@link TuiMode#enable} is ever called, the real-stream accessors fall back to
     * the JVM's current {@code System.out}/{@code System.in} rather than returning
     * {@code null} — callers (e.g. a fallback error print in {@code TuiApp}) can use these
     * accessors unconditionally.
     */
    @Test
    void realOutFallsBackToCurrentSystemOutWhenNotEnabled()
    {
        assertFalse( TuiMode.isEnabled(),
                     "this test suite must never call TuiMode.enable() — see class javadoc" );
        assertSame( System.out, TuiMode.realOut() );
    }

    @Test
    void realInFallsBackToCurrentSystemInWhenNotEnabled()
    {
        assertFalse( TuiMode.isEnabled(),
                     "this test suite must never call TuiMode.enable() — see class javadoc" );
        assertSame( System.in, TuiMode.realIn() );
    }
}
