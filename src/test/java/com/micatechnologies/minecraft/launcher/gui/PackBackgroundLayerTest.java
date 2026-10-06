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

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The card background's sizing arithmetic: which part of an image covers the card (scaled to
 * cover it and centred), and the width it is decoded at.
 *
 * @since 2026.10
 */
class PackBackgroundLayerTest
{
    @Test
    void aWideImageIsCroppedEquallyLeftAndRight()
    {
        // A panorama into a 360x150 card: scaled to the height (150/500), 1200 px of width shows.
        Rectangle2D v = PackBackgroundLayer.coverViewport( 3000, 500, 360, 150 );
        assertEquals( 500, v.getHeight(), 1e-6 );
        assertEquals( 1200, v.getWidth(), 1e-6 );
        assertEquals( 900, v.getMinX(), 1e-6 );
        assertEquals( 0, v.getMinY(), 1e-6 );
    }

    @Test
    void aTallImageIsCroppedEquallyTopAndBottom()
    {
        // 1920x1080 into a 360x150 card: scaled to the width, 1920 * 150/360 = 800 px tall.
        Rectangle2D v = PackBackgroundLayer.coverViewport( 1920, 1080, 360, 150 );
        assertEquals( 1920, v.getWidth(), 1e-6 );
        assertEquals( 800, v.getHeight(), 1e-6 );
        assertEquals( 140, v.getMinY(), 1e-6 );
        assertEquals( 0, v.getMinX(), 1e-6 );
    }

    @Test
    void anImageOfTheBoxsShapeShowsWhole()
    {
        Rectangle2D v = PackBackgroundLayer.coverViewport( 720, 300, 360, 150 );
        assertEquals( new Rectangle2D( 0, 0, 720, 300 ), v );
    }

    @Test
    void decodeWidthCoversTheBoxInDevicePixelsRoundedUp()
    {
        assertEquals( 384, PackBackgroundLayer.decodeWidth( 360, 1 ) );
        assertEquals( 768, PackBackgroundLayer.decodeWidth( 360, 2 ) );
        assertEquals( 576, PackBackgroundLayer.decodeWidth( 360, 1.5 ) );
        assertEquals( 64, PackBackgroundLayer.decodeWidth( 1, 1 ), "never below one step" );
    }
}
