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

import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/**
 * Empty states: the large tonal badge with a glyph that sits above an empty screen's heading
 * (Library, Browse, Runtime Management). Styled by {@code .emptyStateBadge} in {@code ui-base.css}.
 *
 * @since 2026.10
 */
final class EmptyStates
{
    private EmptyStates()
    {
    }

    /**
     * @param icon SVG path data from {@link LauncherIcons}
     *
     * @return a 72 px tonal badge showing the glyph
     */
    static StackPane badge( String icon )
    {
        SVGPath glyph = new SVGPath();
        glyph.setContent( icon );
        glyph.getStyleClass().add( "emptyStateGlyph" );
        StackPane badge = new StackPane( glyph );
        badge.getStyleClass().add( "emptyStateBadge" );
        return badge;
    }
}
