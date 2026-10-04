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

import javafx.scene.control.ToggleButton;
import javafx.scene.shape.SVGPath;

/**
 * A Material 3 filter chip: a small toggle that narrows a list. Unselected it is outlined;
 * selected it fills with the navigation active-indicator tone and shows a leading check.
 * Colours and shape come from {@code ui-base.css} ({@code .filterChip}).
 *
 * <p>A {@link ToggleButton}, so it offers the same {@code selectedProperty} as the checkbox it
 * replaces, and is usable from FXML.
 *
 * @since 2026.10
 */
public class FilterChip extends ToggleButton
{
    private final SVGPath check = new SVGPath();

    /** Creates a chip with no text (FXML sets it). */
    public FilterChip()
    {
        this( "" );
    }

    /**
     * Creates a chip.
     *
     * @param text the chip's label
     */
    public FilterChip( String text )
    {
        super( text );
        getStyleClass().add( "filterChip" );
        check.setContent( LauncherIcons.CHECK );
        check.getStyleClass().add( "filterChipCheck" );
        // 24 px icon drawn at 16 px, Material's chip icon size.
        check.setScaleX( 16.0 / 24 );
        check.setScaleY( 16.0 / 24 );
        setGraphicTextGap( 2 );
        selectedProperty().addListener( ( o, was, now ) -> setGraphic( now ? check : null ) );
    }
}
