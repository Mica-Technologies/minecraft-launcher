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

/**
 * The docked Running Games view's resize arithmetic: the height share a drag of its top edge
 * produces, and the bounds a saved share is held to.
 *
 * @since 2026.10
 */
class RunningGamesDockResizeTest
{
    private static final double WINDOW = 1000;
    private static final double ROOM   = 600;

    @Test
    void draggingUpGrowsTheDockAndDownShrinksIt()
    {
        assertEquals( 0.52, RunningGamesWindow.dragShare( 420, 100, WINDOW, ROOM ), 1e-9 );
        assertEquals( 0.32, RunningGamesWindow.dragShare( 420, -100, WINDOW, ROOM ), 1e-9 );
    }

    @Test
    void theDockNeverTakesMoreThanTheScreenAboveCanSpare()
    {
        // Past the room, further dragging changes nothing, so the grip stays under the pointer
        // on the way back down.
        assertEquals( 0.60, RunningGamesWindow.dragShare( 420, 400, WINDOW, ROOM ), 1e-9 );
    }

    @Test
    void theShareStaysWithinItsBounds()
    {
        assertEquals( RunningGamesWindow.DOCK_SHARE_MIN, RunningGamesWindow.dragShare( 420, -900, WINDOW, ROOM ) );
        assertEquals( RunningGamesWindow.DOCK_SHARE_MAX,
                      RunningGamesWindow.dragShare( 420, 900, WINDOW, Double.MAX_VALUE ) );
        assertEquals( RunningGamesWindow.DOCK_SHARE_MAX, RunningGamesWindow.clampShare( 5 ) );
        assertEquals( RunningGamesWindow.DOCK_SHARE_MIN, RunningGamesWindow.clampShare( -1 ) );
    }

    @Test
    void anUnusableValueFallsBackToTheDefault()
    {
        assertEquals( 0.42, RunningGamesWindow.clampShare( Double.NaN ), 1e-9 );
        assertEquals( 0.42, RunningGamesWindow.dragShare( 420, 50, 0, ROOM ), 1e-9 );
    }
}
