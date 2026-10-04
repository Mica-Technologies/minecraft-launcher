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


package com.micatechnologies.minecraft.launcher.gui;

import com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.PathElement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure geometry behind the progress screens: the wavy bar's wave, the blocky horizon's
 * column heights and the step badges' glyphs.
 *
 * @since 2026.10
 */
class ProgressVisualsTest
{
    @Test
    void waveStartsFlatStaysInBoundsAndEndsAtTheEnd()
    {
        List< PathElement > wave = WavyProgressBar.wavePath( 10, 210, 50, 0.7, WavyProgressBar.AMPLITUDE );
        MoveTo first = assertInstanceOf( MoveTo.class, wave.get( 0 ) );
        assertEquals( 10, first.getX(), 1e-9 );
        assertEquals( 50, first.getY(), 1e-9, "the wave ramps in from the centre line" );
        LineTo last = assertInstanceOf( LineTo.class, wave.get( wave.size() - 1 ) );
        assertEquals( 210, last.getX(), 1e-9 );
        for ( PathElement e : wave.subList( 1, wave.size() ) ) {
            double y = ( (LineTo) e ).getY();
            assertTrue( Math.abs( y - 50 ) <= WavyProgressBar.AMPLITUDE + 1e-9, "wave stays within its amplitude" );
        }
    }

    @Test
    void zeroAmplitudeIsFlat()
    {
        for ( PathElement e : WavyProgressBar.wavePath( 0, 100, 20, 1.3, 0 ) ) {
            double y = e instanceof MoveTo m ? m.getY() : ( (LineTo) e ).getY();
            assertEquals( 20, y, 1e-9 );
        }
    }

    @Test
    void horizonColumnsStayWithinTheirHeightAndRepeatExactly()
    {
        for ( int c = 0; c < 200; c++ ) {
            int h = BlockyHorizon.columnHeight( c, 0, 4 );
            assertTrue( h >= 1 && h <= 4, "column " + c + " is " + h );
            assertEquals( h, BlockyHorizon.columnHeight( c, 0, 4 ), "the skyline is deterministic" );
        }
    }

    @Test
    void everyStateButPendingHasAGlyph()
    {
        assertNull( StepBadge.glyphFor( LaunchProgressTracker.State.PENDING ) );
        for ( LaunchProgressTracker.State s : LaunchProgressTracker.State.values() ) {
            if ( s != LaunchProgressTracker.State.PENDING ) {
                assertNotNull( StepBadge.glyphFor( s ), s.name() );
            }
        }
    }
}
