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

import javafx.beans.InvalidationListener;
import javafx.beans.WeakInvalidationListener;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.util.List;

/**
 * A window's minimum size at the interface scale, kept within the screen it is on.
 *
 * <p>Minimums are in layout units, so they grow with the scale. Unclamped, a tall screen at a
 * large scale asks for more than a small display has (the login screen's 760 px minimum is 950 px
 * at 125 %, taller than a 768 px laptop), and the window's bottom, with its buttons, ends up off
 * screen where it can't be reached. The minimum is therefore capped to the visual bounds of the
 * window's screen, less a small margin.</p>
 *
 * @since 2026.10
 */
final class ScaledMinSize
{
    /** Room left between a minimum-size window and the edges of its screen's usable area. */
    static final double SCREEN_MARGIN = 24;

    private ScaledMinSize() { }

    /**
     * A minimum dimension at a scale, capped to the space available. Pure, for testing.
     *
     * @param base      the minimum in layout units
     * @param scale     the interface scale
     * @param available the screen's usable size along that axis
     *
     * @return the minimum to set, never negative
     */
    static double clamp( double base, double scale, double available )
    {
        double wanted = Math.max( 0, base * scale );
        if ( !( available > 0 ) ) {
            return wanted;
        }
        return Math.min( wanted, Math.max( 0, available - SCREEN_MARGIN ) );
    }

    /**
     * Sets a stage's minimum size from base minimums at the current scale, capped to its screen.
     *
     * @param stage      the stage
     * @param baseWidth  minimum width in layout units
     * @param baseHeight minimum height in layout units
     */
    static void apply( Stage stage, double baseWidth, double baseHeight )
    {
        Rectangle2D bounds = visualBoundsFor( stage );
        double scale = UiScale.get();
        stage.setMinWidth( clamp( baseWidth, scale, bounds.getWidth() ) );
        stage.setMinHeight( clamp( baseHeight, scale, bounds.getHeight() ) );
    }

    /**
     * Applies the minimum now, again whenever the window is shown (when its screen is known),
     * and whenever the scale changes for as long as the stage exists. The scale listener is weak
     * and its strong reference is held by the stage, so it doesn't keep a closed window alive.
     *
     * @param stage      the stage
     * @param baseWidth  minimum width in layout units
     * @param baseHeight minimum height in layout units
     */
    static void follow( Stage stage, double baseWidth, double baseHeight )
    {
        apply( stage, baseWidth, baseHeight );
        InvalidationListener onScale = o -> apply( stage, baseWidth, baseHeight );
        stage.getProperties().put( ScaledMinSize.class, onScale );
        UiScale.scaleProperty().addListener( new WeakInvalidationListener( onScale ) );
        stage.addEventHandler( WindowEvent.WINDOW_SHOWN, e -> apply( stage, baseWidth, baseHeight ) );
    }

    /**
     * The usable area of the screen a stage is on: the screen under its centre, else any screen
     * it overlaps, else the primary screen (a stage not yet shown or sized).
     *
     * @param stage the stage
     *
     * @return that screen's visual bounds
     */
    static Rectangle2D visualBoundsFor( Stage stage )
    {
        double w = stage.getWidth();
        double h = stage.getHeight();
        if ( w > 0 && h > 0 && !Double.isNaN( stage.getX() ) && !Double.isNaN( stage.getY() ) ) {
            List< Screen > screens = Screen.getScreensForRectangle( stage.getX() + w / 2, stage.getY() + h / 2, 1, 1 );
            if ( screens.isEmpty() ) {
                screens = Screen.getScreensForRectangle( stage.getX(), stage.getY(), w, h );
            }
            if ( !screens.isEmpty() ) {
                return screens.get( 0 ).getVisualBounds();
            }
        }
        return Screen.getPrimary().getVisualBounds();
    }
}
