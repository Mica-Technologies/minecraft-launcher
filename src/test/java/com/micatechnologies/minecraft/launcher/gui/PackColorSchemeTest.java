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

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Colour from pack art: the seed follows a logo's dominant vivid hue, and every scheme it makes
 * reads at WCAG AA in dark and light themes.
 *
 * @since 2026.10
 */
class PackColorSchemeTest
{
    @Test
    void seedFollowsTheDominantVividHue()
    {
        int[] pixels = new int[ 400 ];
        for ( int i = 0; i < pixels.length; i++ ) {
            // Mostly orange, some grey noise and transparent pixels.
            pixels[ i ] = i % 5 == 0 ? 0xFF808080 : i % 7 == 0 ? 0x00000000 : 0xFFE87A1E;
        }
        OptionalInt seed = PackColorScheme.seedFrom( pixels );
        assertTrue( seed.isPresent() );
        double[] lab = PackColorScheme.oklab( seed.getAsInt() );
        double hue = Math.toDegrees( Math.atan2( lab[ 2 ], lab[ 1 ] ) );
        double orange = Math.toDegrees( Math.atan2( PackColorScheme.oklab( 0xE87A1E )[ 2 ],
                                                    PackColorScheme.oklab( 0xE87A1E )[ 1 ] ) );
        assertEquals( orange, hue, 3, "the seed keeps the logo's hue" );
    }

    @Test
    void aGreyLogoHasNoSeed()
    {
        int[] pixels = { 0xFF808080, 0xFF202020, 0xFFF0F0F0, 0xFF999999 };
        assertFalse( PackColorScheme.seedFrom( pixels ).isPresent() );
    }

    @Test
    void everySchemeReadsInBothThemes()
    {
        int[] seeds = { 0xE87A1E, 0x1E88E5, 0x43A047, 0x8E24AA, 0xE53935, 0xFDD835, 0x00ACC1, 0x6D4C41, 0xF06292 };
        for ( int seed : seeds ) {
            for ( boolean dark : new boolean[]{ true, false } ) {
                int surface = dark ? 0x222732 : 0xEDEEF2;
                Map< String, String > r = PackColorScheme.roles( seed, dark, surface );
                String where = String.format( "seed %06X %s", seed, dark ? "dark" : "light" );
                assertReads( r.get( "-md-on-primary" ), r.get( "-md-primary" ), where + " on-primary" );
                assertReads( r.get( "-md-on-primary-container" ), r.get( "-md-primary-container" ), where + " container" );
                assertReads( r.get( "-md-on-active-indicator" ), r.get( "-md-active-indicator" ), where + " indicator" );
                assertReads( r.get( "-md-text-primary" ), String.format( "#%06X", surface ), where + " text-primary" );
            }
        }
    }

    @Test
    void oklabRoundTrips()
    {
        for ( int rgb : new int[]{ 0x000000, 0xFFFFFF, 0x6FCF3D, 0x0668E1, 0xD257DB } ) {
            double[] lab = PackColorScheme.oklab( rgb );
            int back = PackColorScheme.srgb( lab[ 0 ], lab[ 1 ], lab[ 2 ] );
            for ( int shift = 0; shift <= 16; shift += 8 ) {
                assertEquals( ( rgb >> shift ) & 0xFF, ( back >> shift ) & 0xFF, 1 );
            }
        }
    }

    private static void assertReads( String fg, String bg, String what )
    {
        double ratio = PackColorScheme.contrast( Integer.parseInt( fg.substring( 1 ), 16 ),
                                                 Integer.parseInt( bg.substring( 1 ), 16 ) );
        assertTrue( ratio >= 4.5, what + ": " + fg + " on " + bg + " = " + String.format( "%.2f", ratio ) );
    }
}
