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

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests for {@link ScaledMinSize#clamp}: minimums scale, but never past the display. */
class ScaledMinSizeTest
{
    @Test
    void scalesTheMinimumWhenItFits()
    {
        assertEquals( 950, ScaledMinSize.clamp( 760, 1.25, 1400 ), 1e-9 );
        assertEquals( 380, ScaledMinSize.clamp( 760, 0.5, 728 ), 1e-9 );
    }

    @Test
    void capsTheMinimumToTheDisplayLessAMargin()
    {
        // The login screen at 125 % on a 768 px laptop (728 px usable above the taskbar).
        assertEquals( 728 - ScaledMinSize.SCREEN_MARGIN, ScaledMinSize.clamp( 760, 1.25, 728 ), 1e-9 );
    }

    @Test
    void unknownOrTinyDisplaysDegradeSafely()
    {
        assertEquals( 950, ScaledMinSize.clamp( 760, 1.25, 0 ), 1e-9, "no bounds: leave the minimum alone" );
        assertEquals( 950, ScaledMinSize.clamp( 760, 1.25, Double.NaN ), 1e-9 );
        assertEquals( 0, ScaledMinSize.clamp( 760, 1.25, 10 ), 1e-9, "never negative" );
        assertEquals( 0, ScaledMinSize.clamp( -1, 1.0, 1000 ), 1e-9 );
    }
}
