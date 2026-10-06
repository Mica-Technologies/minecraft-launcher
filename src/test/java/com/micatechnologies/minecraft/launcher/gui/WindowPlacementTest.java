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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link WindowPlacement}: a restored window must come back with a grabbable title bar, not
 * merely overlapping a screen by a pixel.
 */
class WindowPlacementTest
{
    /** A 1920x1080 screen with a 40 px taskbar at the bottom. */
    private static final Rectangle2D PRIMARY = new Rectangle2D( 0, 0, 1920, 1040 );

    /** A second screen to the right of the primary. */
    private static final Rectangle2D RIGHT = new Rectangle2D( 1920, 0, 2560, 1400 );

    @Test
    void aWindowWellInsideAScreenIsKept()
    {
        assertTrue( WindowPlacement.titleBarOnScreen( new Rectangle2D( 100, 100, 1200, 800 ), List.of( PRIMARY ) ) );
    }

    @Test
    void aOnePixelOverlapIsNotEnough()
    {
        // Only the window's bottom-right corner reaches the screen.
        assertFalse( WindowPlacement.titleBarOnScreen( new Rectangle2D( -1199, -799, 1200, 800 ), List.of( PRIMARY ) ) );
    }

    @Test
    void aTitleBarAboveTheVisibleAreaIsRejected()
    {
        assertFalse( WindowPlacement.titleBarOnScreen( new Rectangle2D( 100, -20, 1200, 800 ), List.of( PRIMARY ) ) );
    }

    @Test
    void aTitleBarBehindTheTaskbarIsRejected()
    {
        assertFalse( WindowPlacement.titleBarOnScreen( new Rectangle2D( 100, 1030, 1200, 800 ), List.of( PRIMARY ) ) );
    }

    @Test
    void aWindowOnADisconnectedScreenIsRejected()
    {
        assertFalse( WindowPlacement.titleBarOnScreen( new Rectangle2D( 2200, 100, 1200, 800 ), List.of( PRIMARY ) ) );
        assertTrue( WindowPlacement.titleBarOnScreen( new Rectangle2D( 2200, 100, 1200, 800 ), List.of( PRIMARY, RIGHT ) ) );
    }

    @Test
    void aWindowHangingPartlyOffTheSideIsKeptWhileItsTitleBarCanBeGrabbed()
    {
        // Left 400 px off-screen; the middle of the title bar is still visible.
        assertTrue( WindowPlacement.titleBarOnScreen( new Rectangle2D( -400, 100, 1200, 800 ), List.of( PRIMARY ) ) );
        // Almost all the way off: only 50 px of the title strip's middle half shows.
        assertFalse( WindowPlacement.titleBarOnScreen( new Rectangle2D( -850, 100, 1200, 800 ), List.of( PRIMARY ) ) );
    }

    @Test
    void clampingKeepsTheSizeAndMovesTheWindowInside()
    {
        Rectangle2D clamped = WindowPlacement.clampInto( new Rectangle2D( 2200, -50, 1200, 800 ), PRIMARY );
        assertEquals( new Rectangle2D( 720, 0, 1200, 800 ), clamped );
    }

    @Test
    void clampingShrinksAWindowLargerThanTheScreen()
    {
        Rectangle2D clamped = WindowPlacement.clampInto( new Rectangle2D( -100, -100, 2560, 1400 ), PRIMARY );
        assertEquals( PRIMARY, clamped );
    }
}
