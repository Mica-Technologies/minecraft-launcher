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

import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;

/**
 * Provides consistent tooltip creation and installation for all launcher UI controls. Tooltips use a shared style class
 * ({@code mcl-tooltip}) for uniform appearance across themes.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
public class TooltipManager
{
    /**
     * Default delay before a tooltip appears (milliseconds).
     */
    private static final double SHOW_DELAY_MS = 400;

    /**
     * Default duration a tooltip stays visible (milliseconds).
     */
    private static final double SHOW_DURATION_MS = 10_000;

    /**
     * Creates a styled tooltip with the given text.
     *
     * @param text the tooltip text
     *
     * @return a configured Tooltip instance
     */
    public static Tooltip create( String text )
    {
        Tooltip tooltip = new Tooltip( text );
        tooltip.getStyleClass().add( "mcl-tooltip" );
        tooltip.setShowDelay( Duration.millis( SHOW_DELAY_MS ) );
        tooltip.setShowDuration( Duration.millis( SHOW_DURATION_MS ) );
        tooltip.setWrapText( true );
        tooltip.setMaxWidth( 300 );
        return tooltip;
    }

    /**
     * Creates and installs a tooltip on the given node.
     *
     * @param node the UI node to attach the tooltip to
     * @param text the tooltip text
     */
    public static void install( Node node, String text )
    {
        Tooltip.install( node, create( text ) );
    }
}
