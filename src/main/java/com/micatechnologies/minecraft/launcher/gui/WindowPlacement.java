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

import java.util.List;

/**
 * Decides whether a saved window position is still usable on the screens attached now, and
 * moves it onto a screen when it is not. Pure geometry, so it is tested without a toolkit.
 *
 * <p>Overlapping a screen is not enough: a window can overlap by a pixel and still have its
 * title bar above the visible area or off the side, where it cannot be grabbed. So the test is
 * that the middle half of the window's top strip (where the title bar is) lies inside one
 * screen's visual bounds, with enough of it showing to grab.</p>
 *
 * @since 2026.10
 */
final class WindowPlacement
{
    /** Height of the top strip treated as the title bar. */
    static final double TITLE_STRIP_HEIGHT = 40;

    /** How much of the title strip's middle must be on one screen for the user to grab it. */
    static final double MIN_GRAB_WIDTH = 100;

    private WindowPlacement() { /* static-only */ }

    /**
     * Whether a window at {@code bounds} would have a grabbable title bar on one of the screens.
     *
     * @param bounds  the window's bounds
     * @param screens each attached screen's visual bounds (excluding taskbars and menu bars)
     *
     * @return {@code true} when the middle of the window's top strip is on a screen
     *
     * @since 2026.10
     */
    static boolean titleBarOnScreen( Rectangle2D bounds, List< Rectangle2D > screens )
    {
        double stripHeight = Math.min( TITLE_STRIP_HEIGHT, bounds.getHeight() );
        double left = bounds.getMinX() + bounds.getWidth() * 0.25;
        double right = bounds.getMinX() + bounds.getWidth() * 0.75;
        double needed = Math.min( MIN_GRAB_WIDTH, right - left );
        for ( Rectangle2D screen : screens ) {
            boolean verticallyInside = bounds.getMinY() >= screen.getMinY()
                    && bounds.getMinY() + stripHeight <= screen.getMaxY();
            if ( !verticallyInside ) {
                continue;
            }
            double visible = Math.min( right, screen.getMaxX() ) - Math.max( left, screen.getMinX() );
            if ( visible >= needed ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Moves a window into a screen's visual bounds, keeping its size where it fits and shrinking
     * it where it does not.
     *
     * @param bounds the window's bounds
     * @param screen the target screen's visual bounds
     *
     * @return bounds lying wholly inside {@code screen}
     *
     * @since 2026.10
     */
    static Rectangle2D clampInto( Rectangle2D bounds, Rectangle2D screen )
    {
        double width = Math.min( bounds.getWidth(), screen.getWidth() );
        double height = Math.min( bounds.getHeight(), screen.getHeight() );
        double x = Math.min( Math.max( bounds.getMinX(), screen.getMinX() ), screen.getMaxX() - width );
        double y = Math.min( Math.max( bounds.getMinY(), screen.getMinY() ), screen.getMaxY() - height );
        return new Rectangle2D( x, y, width, height );
    }
}
