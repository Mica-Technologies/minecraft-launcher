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

import com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

import java.util.Locale;

/**
 * The round status badge in front of each launch or verify step: an outlined ring while
 * pending, a tonal disc with a dot while running, a filled primary disc with a check when done,
 * the error colour with a cross when failed, a muted disc with a dash when skipped. Colours come
 * from {@code ui-base.css} ({@code .stepBadge-*}).
 *
 * @since 2026.10
 */
final class StepBadge extends StackPane
{
    /** Material Icons "check", "close" and "remove", 24 px box. */
    private static final String CHECK = "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z";
    private static final String CROSS = "M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z";
    private static final String DASH = "M19 13H5v-2h14v2z";
    /** A 6 px dot, centred in the 24 px box. */
    private static final String DOT = "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z";

    private final SVGPath glyph = new SVGPath();

    /**
     * Creates a badge.
     *
     * @param state the step's state
     */
    StepBadge( LaunchProgressTracker.State state )
    {
        getStyleClass().add( "stepBadge" );
        glyph.getStyleClass().add( "stepBadgeGlyph" );
        // 24 px artwork drawn at 14 px inside the 22 px badge.
        glyph.setScaleX( 14.0 / 24 );
        glyph.setScaleY( 14.0 / 24 );
        getChildren().add( glyph );
        setMinSize( 22, 22 );
        setMaxSize( 22, 22 );
        setState( state );
    }

    /**
     * Shows a state.
     *
     * @param state the step's state
     */
    void setState( LaunchProgressTracker.State state )
    {
        getStyleClass().removeIf( c -> c.startsWith( "stepBadge-" ) );
        getStyleClass().add( "stepBadge-" + state.name().toLowerCase( Locale.ROOT ) );
        String path = glyphFor( state );
        glyph.setContent( path == null ? "" : path );
        glyph.setVisible( path != null );
    }

    /**
     * The glyph for a state, or {@code null} for none.
     *
     * @param state the step's state
     *
     * @return SVG path data in a 24 px box, or {@code null}
     */
    static String glyphFor( LaunchProgressTracker.State state )
    {
        return switch ( state ) {
            case PENDING -> null;
            case RUNNING -> DOT;
            case DONE -> CHECK;
            case FAILED -> CROSS;
            case SKIPPED -> DASH;
        };
    }
}
