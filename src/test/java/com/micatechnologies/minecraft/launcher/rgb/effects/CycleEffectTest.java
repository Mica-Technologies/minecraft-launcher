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

package com.micatechnologies.minecraft.launcher.rgb.effects;

import com.micatechnologies.minecraft.launcher.rgb.RgbColor;
import com.micatechnologies.minecraft.launcher.rgb.RgbFrame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link CycleEffect}'s segment math. The effect is a pure
 * function of elapsed time, so we drive it with hand-picked
 * {@code elapsedMs} values that land exactly on segment boundaries and
 * midpoints — a regression in the segment index, ease curve, or wrap-
 * around logic shows up as a wrong color at one of these checkpoints.
 */
class CycleEffectTest
{
    private static final RgbColor RED   = new RgbColor( 255,   0,   0 );
    private static final RgbColor GREEN = new RgbColor(   0, 255,   0 );
    private static final RgbColor BLUE  = new RgbColor(   0,   0, 255 );

    @Test
    void emptyPaletteRejected()
    {
        assertThrows( IllegalArgumentException.class,
                () -> new CycleEffect( "x", List.of(), 1_000L ) );
    }

    @Test
    void nonPositivePeriodRejected()
    {
        assertThrows( IllegalArgumentException.class,
                () -> new CycleEffect( "x", List.of( RED ), 0L ) );
        assertThrows( IllegalArgumentException.class,
                () -> new CycleEffect( "x", List.of( RED ), -1L ) );
    }

    @Test
    void singleColorActsAsSolid()
    {
        CycleEffect e = new CycleEffect( "x", List.of( RED ), 1_000L );
        // Any elapsed time returns the same red — the degenerate case
        // shouldn't try to blend a color into itself.
        assertEquals( RgbFrame.solid( RED ), e.frameAt( 0L ) );
        assertEquals( RgbFrame.solid( RED ), e.frameAt( 500L ) );
        assertEquals( RgbFrame.solid( RED ), e.frameAt( 999L ) );
        assertEquals( RgbFrame.solid( RED ), e.frameAt( 12_345L ) );
    }

    @Test
    void segmentStartsAreExact()
    {
        // 3 colors, 3000 ms period → 1000 ms per segment.
        // Phase 0.0 → segment 0 start → pure RED.
        // Phase 1/3 → segment 1 start → pure GREEN.
        // Phase 2/3 → segment 2 start → pure BLUE.
        CycleEffect e = new CycleEffect( "x", List.of( RED, GREEN, BLUE ), 3_000L );
        assertEquals( RED,   solidColorAt( e, 0L ) );
        assertEquals( GREEN, solidColorAt( e, 1_000L ) );
        assertEquals( BLUE,  solidColorAt( e, 2_000L ) );
    }

    @Test
    void segmentMidpointsAreLinearBlend()
    {
        // The cosine ease should land exactly at t=0.5 at segment-phase
        // 0.5 — but cos(π/2) in IEEE 754 is ~6e-17 rather than 0, so the
        // computed t is barely under 0.5 and a half-rounded channel can
        // drift by one unit. ±1 per channel tolerance lets the assertion
        // still catch a real curve regression without flagging the
        // unavoidable floating-point gap.
        CycleEffect e = new CycleEffect( "x", List.of( RED, GREEN, BLUE ), 3_000L );
        assertColorApprox( RgbColor.blend( RED,   GREEN, 0.5 ), solidColorAt( e, 500L ) );
        assertColorApprox( RgbColor.blend( GREEN, BLUE,  0.5 ), solidColorAt( e, 1_500L ) );
        // Last segment wraps from BLUE back to RED.
        assertColorApprox( RgbColor.blend( BLUE,  RED,   0.5 ), solidColorAt( e, 2_500L ) );
    }

    /** Channel-wise ±1 tolerance — see segmentMidpointsAreLinearBlend
     *  for the floating-point rationale. */
    private static void assertColorApprox( RgbColor expected, RgbColor actual )
    {
        org.junit.jupiter.api.Assertions.assertTrue(
                Math.abs( expected.r() - actual.r() ) <= 1
                        && Math.abs( expected.g() - actual.g() ) <= 1
                        && Math.abs( expected.b() - actual.b() ) <= 1,
                "expected ≈ " + expected + " (±1/channel) but got " + actual );
    }

    @Test
    void wrapsCleanlyAcrossPeriodBoundary()
    {
        // Driving twice the period should produce the same frame as
        // driving 0 ms — the modulo arithmetic in frameAt is the only
        // thing managing the loop.
        CycleEffect e = new CycleEffect( "x", List.of( RED, GREEN, BLUE ), 3_000L );
        assertEquals( solidColorAt( e, 0L ),     solidColorAt( e, 3_000L ) );
        assertEquals( solidColorAt( e, 500L ),   solidColorAt( e, 3_500L ) );
        assertEquals( solidColorAt( e, 2_999L ), solidColorAt( e, 5_999L ) );
    }

    @Test
    void colorCountReflectsInput()
    {
        CycleEffect three = new CycleEffect( "x", List.of( RED, GREEN, BLUE ), 1_000L );
        assertEquals( 3, three.colorCount() );
    }

    /** Pull the background color out of a frame returned by a
     *  CycleEffect. CycleEffect always returns solid frames so this
     *  is safe; tests fail loudly if that assumption changes. */
    private static RgbColor solidColorAt( CycleEffect e, long ms )
    {
        return e.frameAt( ms ).background();
    }
}
