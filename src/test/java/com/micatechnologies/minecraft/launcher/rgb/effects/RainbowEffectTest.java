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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link RainbowEffect}'s HSL→RGB math + phase mapping. Frames
 * are pure functions of {@code elapsedMs}, so we drive checkpoints at
 * the six primary HSL hues (0°, 60°, 120°, 180°, 240°, 300°) — each
 * has a known pure-RGB representation at S=1, L=0.5 that the formula
 * must produce exactly.
 */
class RainbowEffectTest
{
    @Test
    void nonPositivePeriodRejected()
    {
        assertThrows( IllegalArgumentException.class,
                () -> new RainbowEffect( "x", 0L ) );
        assertThrows( IllegalArgumentException.class,
                () -> new RainbowEffect( "x", -1L ) );
    }

    @Test
    void zeroPhaseProducesRed()
    {
        // Phase 0 → hue 0° → pure red at S=1, L=0.5.
        RainbowEffect e = new RainbowEffect( "x", 6_000L );
        RgbColor c = e.frameAt( 0L ).background();
        assertEquals( 255, c.r() );
        assertEquals(   0, c.g() );
        assertEquals(   0, c.b() );
    }

    @Test
    void primaryHsLHuesProducePrimaryRgbColors()
    {
        // 6000 ms period → 1000 ms per 60° hue step.
        RainbowEffect e = new RainbowEffect( "x", 6_000L );
        // 0°   pure red
        assertColorApprox( 255,   0,   0, e.frameAt(     0L ).background() );
        // 60°  yellow
        assertColorApprox( 255, 255,   0, e.frameAt( 1_000L ).background() );
        // 120° pure green
        assertColorApprox(   0, 255,   0, e.frameAt( 2_000L ).background() );
        // 180° cyan
        assertColorApprox(   0, 255, 255, e.frameAt( 3_000L ).background() );
        // 240° pure blue
        assertColorApprox(   0,   0, 255, e.frameAt( 4_000L ).background() );
        // 300° magenta
        assertColorApprox( 255,   0, 255, e.frameAt( 5_000L ).background() );
    }

    @Test
    void wrapsAtPeriodBoundary()
    {
        RainbowEffect e = new RainbowEffect( "x", 6_000L );
        assertEquals( e.frameAt(     0L ).background(),
                       e.frameAt( 6_000L ).background() );
        assertEquals( e.frameAt( 1_500L ).background(),
                       e.frameAt( 7_500L ).background() );
    }

    @Test
    void hslToRgbDirectlyValidatesPrimaryStops()
    {
        // Same set of stops, called via the static helper — guards the
        // formula in isolation from the phase mapping. Useful if a
        // future change splits the math into a separate util class.
        assertColorApprox( 255,   0,   0, RainbowEffect.hslToRgb(   0.0, 1.0, 0.5 ) );
        assertColorApprox( 255, 255,   0, RainbowEffect.hslToRgb(  60.0, 1.0, 0.5 ) );
        assertColorApprox(   0, 255, 255, RainbowEffect.hslToRgb( 180.0, 1.0, 0.5 ) );
        // Mid-saturation, light tint — known formula output.
        // S=0.5, L=0.75 → c = 0.25, x varies; at hue 0 → R = l + c/2 = 0.875
        // The check tolerates ±1 for rounding so the formula doesn't
        // need to be exact to the bit.
        RgbColor mid = RainbowEffect.hslToRgb( 0.0, 0.5, 0.75 );
        assertTrue( mid.r() >= 220 && mid.r() <= 224,
                "Expected R ~= 223, got " + mid.r() );
        assertTrue( mid.g() == mid.b(),
                "Hue=0 must have G == B at any S, L; got G=" + mid.g() + " B=" + mid.b() );
    }

    /** Assert that {@code actual} matches the expected channel values
     *  within ±1 (rounding tolerance for the HSL→RGB conversion). */
    private static void assertColorApprox( int er, int eg, int eb, RgbColor actual )
    {
        assertTrue( Math.abs( actual.r() - er ) <= 1, "R: expected " + er + " got " + actual.r() );
        assertTrue( Math.abs( actual.g() - eg ) <= 1, "G: expected " + eg + " got " + actual.g() );
        assertTrue( Math.abs( actual.b() - eb ) <= 1, "B: expected " + eb + " got " + actual.b() );
    }
}
