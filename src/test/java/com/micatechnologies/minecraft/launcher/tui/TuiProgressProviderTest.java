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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TuiProgressProvider#updateProgressHandler(double, String, String, String)}
 * — the bridge that forwards the launcher's verify/download progress callbacks (fired on the
 * launch thread) to the TUI's launch progress dialog. If this forwarding drops a call or
 * mangles an argument, the user staring at the "Launching..." modal in the terminal UI sees
 * a stuck or wrong progress bar with no other feedback that anything is happening.
 *
 * <p>The class has no static/singleton dependencies — it is a pure adapter over a
 * caller-supplied {@link TuiProgressProvider.Listener} — so it is exercised directly with a
 * recording stub, no Lanterna terminal or launcher state required.</p>
 */
class TuiProgressProviderTest
{
    private record Call( double percent, String section, String detail, String status ) {}

    @Test
    void forwardsAllFourArgumentsToTheListenerUnchanged()
    {
        List< Call > calls = new ArrayList<>();
        TuiProgressProvider provider = new TuiProgressProvider(
                ( percent, section, detail, status ) -> calls.add( new Call( percent, section, detail, status ) ) );

        provider.updateProgressHandler( 42.5, "Downloading Assets", "config.json", "1.2 MB/s" );

        assertEquals( 1, calls.size() );
        Call c = calls.get( 0 );
        assertEquals( 42.5, c.percent() );
        assertEquals( "Downloading Assets", c.section() );
        assertEquals( "config.json", c.detail() );
        assertEquals( "1.2 MB/s", c.status() );
    }

    @Test
    void forwardsEveryCallInOrderForMultipleUpdates()
    {
        List< Double > percents = new ArrayList<>();
        TuiProgressProvider provider = new TuiProgressProvider(
                ( percent, section, detail, status ) -> percents.add( percent ) );

        provider.updateProgressHandler( 0, "a", null, null );
        provider.updateProgressHandler( 50, "b", null, null );
        provider.updateProgressHandler( 100, "c", null, null );

        assertEquals( List.of( 0.0, 50.0, 100.0 ), percents );
    }

    @Test
    void nullDetailAndStatusPassThroughAsNullRatherThanBeingCoerced()
    {
        // The provider itself does no null-handling — that's TuiApp's job on the receiving
        // end (see its lambda swapping null for ""). Pin down that this adapter is a
        // faithful pass-through, not a silent-default layer.
        List< Call > calls = new ArrayList<>();
        TuiProgressProvider provider = new TuiProgressProvider(
                ( percent, section, detail, status ) -> calls.add( new Call( percent, section, detail, status ) ) );

        provider.updateProgressHandler( 10, null, null, null );

        Call c = calls.get( 0 );
        assertEquals( null, c.section() );
        assertEquals( null, c.detail() );
        assertEquals( null, c.status() );
    }

    /**
     * A {@code null} listener (never happens in production — the TUI always supplies one —
     * but the field is a plain reference with no constructor guard) must not throw. A crash
     * here would happen on the launch thread, which is the same thread that just started the
     * child game process — the last place a NullPointerException should surface.
     */
    @Test
    void nullListenerIsToleratedRatherThanThrowing()
    {
        TuiProgressProvider provider = new TuiProgressProvider( null );
        assertDoesNotThrow( () -> provider.updateProgressHandler( 10, "section", "detail", "status" ) );
    }

    @Test
    void isASubtypeOfGameModPackProgressProvider()
    {
        TuiProgressProvider provider = new TuiProgressProvider( ( p, s, d, st ) -> { } );
        assertTrue( provider instanceof com.micatechnologies.minecraft.launcher.game.modpack.GameModPackProgressProvider );
    }
}
