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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DragScrollTest
{
    @Test
    void aTapThatWobblesStillClicks()
    {
        assertFalse( DragScroll.startsScroll( 2, DragScroll.START_DISTANCE - 1 ) );
    }

    @Test
    void anUpOrDownSlideScrolls()
    {
        assertTrue( DragScroll.startsScroll( 3, DragScroll.START_DISTANCE ) );
        assertTrue( DragScroll.startsScroll( -3, -DragScroll.START_DISTANCE - 5 ) );
    }

    @Test
    void aSidewaysSlideDoesNot()
    {
        assertFalse( DragScroll.startsScroll( 40, 20 ) );
    }

    @Test
    void thePageFollowsTheFinger()
    {
        // 1000 px to scroll; the finger moves up 100 px, so the page scrolls down 100 px.
        assertEquals( 0.6, DragScroll.valueAfterDrag( 0.5, -100, 1000 ), 1e-9 );
        assertEquals( 0.4, DragScroll.valueAfterDrag( 0.5, 100, 1000 ), 1e-9 );
    }

    @Test
    void thePageStopsAtItsEnds()
    {
        assertEquals( 1.0, DragScroll.valueAfterDrag( 0.9, -500, 1000 ), 1e-9 );
        assertEquals( 0.0, DragScroll.valueAfterDrag( 0.1, 500, 1000 ), 1e-9 );
    }

    @Test
    void aFlickUpCarriesThePageDown()
    {
        assertEquals( 2 * DragScroll.FLING_MS, DragScroll.flingDistance( -2, 0 ), 1e-9 );
        assertEquals( -2 * DragScroll.FLING_MS, DragScroll.flingDistance( 2, 0 ), 1e-9 );
    }

    @Test
    void aWildFlickIsCapped()
    {
        assertEquals( DragScroll.MAX_FLING_SPEED * DragScroll.FLING_MS, DragScroll.flingDistance( -100, 0 ), 1e-9 );
    }

    @Test
    void aFingerThatRestedBeforeLiftingDoesNotFling()
    {
        assertEquals( 0, DragScroll.flingDistance( -3, DragScroll.FLING_IDLE_MS ), 1e-9 );
    }
}
