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

import javafx.scene.control.Label;
import javafx.scene.layout.RowConstraints;

/**
 * Shows or collapses a screen's announcement banner: a {@code .banner-announcement} label in its
 * own grid row. With text, the row is 40 px; without, the label and row collapse to nothing.
 *
 * @since 2026.10
 */
final class AnnouncementBanners
{
    /** Height of a showing banner row; the banner card is inset within it. */
    static final double ROW_HEIGHT = 40;

    private AnnouncementBanners()
    {
    }

    /**
     * @param banner the banner label
     * @param row    the grid row holding it
     * @param text   the announcement, or {@code null}/blank for none
     */
    static void show( Label banner, RowConstraints row, String text )
    {
        if ( banner == null || row == null ) {
            return;
        }
        boolean shown = text != null && !text.isBlank();
        banner.setText( shown ? text : "" );
        banner.setVisible( shown );
        double h = shown ? ROW_HEIGHT : 0;
        banner.setMinHeight( h );
        banner.setPrefHeight( h );
        banner.setMaxHeight( h );
        row.setMinHeight( h );
        row.setPrefHeight( h );
        row.setMaxHeight( h );
    }
}
