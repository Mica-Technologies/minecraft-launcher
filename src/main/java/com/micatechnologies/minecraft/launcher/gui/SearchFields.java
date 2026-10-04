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

import io.github.palexdev.materialfx.controls.MFXTextField;
import javafx.scene.shape.SVGPath;

/**
 * Styles a text field as Material 3's search bar: a full pill with a leading magnifier, darker
 * than the surface around it ({@code ui-base.css}, {@code .searchPill}).
 *
 * @since 2026.10
 */
final class SearchFields
{
    private SearchFields()
    {
    }

    /**
     * Turns a field into a search pill.
     *
     * @param field the search field
     */
    static void decorate( MFXTextField field )
    {
        if ( field == null ) {
            return;
        }
        field.getStyleClass().add( "searchPill" );
        SVGPath icon = new SVGPath();
        icon.setContent( LauncherIcons.SEARCH );
        icon.getStyleClass().add( "searchPillIcon" );
        // 24 px icon drawn at 18 px.
        icon.setScaleX( 18.0 / 24 );
        icon.setScaleY( 18.0 / 24 );
        field.setLeadingIcon( icon );
    }
}
