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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import io.github.palexdev.materialfx.controls.MFXButton;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

import java.util.List;

/**
 * Turns a Settings sidebar button into a tile in the style of Android's settings list: a glyph in
 * a coloured circle, the category title and a one-line summary. Tiles sit in groups (a
 * {@code .settingsNavGroup} VBox); {@link #markGroupPositions} tags each tile first, middle, last
 * or only, so the stylesheet can round a group's outer corners and keep the inner ones tight.
 *
 * <p>The glyphs are path data from Google's Material Icons (Apache 2.0), drawn in a 24 px box.
 *
 * @since 2026.10
 */
final class SettingsNavTile
{
    /** Material Icons account_circle. */
    static final String ACCOUNT_ICON =
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 4c1.93 0 3.5 1.57 3.5 3.5S13.93 13 12 13s-3.5-1.57-3.5-3.5S10.07 6 12 6zm0 14c-2.03 0-4.43-.82-6.14-2.88C7.55 15.8 9.68 15 12 15s4.45.8 6.14 2.12C16.43 19.18 14.03 20 12 20z";

    /** Material Icons sports_esports. */
    static final String GAME_ICON =
            "M21.58,16.09l-1.09-7.66C20.21,6.46,18.52,5,16.53,5H7.47C5.48,5,3.79,6.46,3.51,8.43l-1.09,7.66 C2.2,17.63,3.39,19,4.94,19h0c0.68,0,1.32-0.27,1.8-0.75L9,16h6l2.25,2.25c0.48,0.48,1.13,0.75,1.8,0.75h0 C20.61,19,21.8,17.63,21.58,16.09z M11,11H9v2H8v-2H6v-1h2V8h1v2h2V11z M15,10c-0.55,0-1-0.45-1-1c0-0.55,0.45-1,1-1s1,0.45,1,1 C16,9.55,15.55,10,15,10z M17,13c-0.55,0-1-0.45-1-1c0-0.55,0.45-1,1-1s1,0.45,1,1C18,12.55,17.55,13,17,13z";

    /** Material Icons palette. */
    static final String APPEARANCE_ICON =
            "M12,2C6.49,2,2,6.49,2,12s4.49,10,10,10c1.38,0,2.5-1.12,2.5-2.5c0-0.61-0.23-1.2-0.64-1.67c-0.08-0.1-0.13-0.21-0.13-0.33 c0-0.28,0.22-0.5,0.5-0.5H16c3.31,0,6-2.69,6-6C22,6.04,17.51,2,12,2z M17.5,13c-0.83,0-1.5-0.67-1.5-1.5c0-0.83,0.67-1.5,1.5-1.5 s1.5,0.67,1.5,1.5C19,12.33,18.33,13,17.5,13z M14.5,9C13.67,9,13,8.33,13,7.5C13,6.67,13.67,6,14.5,6S16,6.67,16,7.5 C16,8.33,15.33,9,14.5,9z M5,11.5C5,10.67,5.67,10,6.5,10S8,10.67,8,11.5C8,12.33,7.33,13,6.5,13S5,12.33,5,11.5z M11,7.5 C11,8.33,10.33,9,9.5,9S8,8.33,8,7.5C8,6.67,8.67,6,9.5,6S11,6.67,11,7.5z";

    /** Material Icons tune. */
    static final String ADVANCED_ICON =
            "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z";

    /** Material Icons wifi. */
    static final String NETWORK_ICON =
            "M1 9l2 2c4.97-4.97 13.03-4.97 18 0l2-2C16.93 2.93 7.08 2.93 1 9zm8 8l3 3 3-3c-1.65-1.66-4.34-1.66-6 0zm-4-4l2 2c2.76-2.76 7.24-2.76 10 0l2-2C15.14 9.14 8.87 9.14 5 13z";

    /** Material Icons security. */
    static final String SECURITY_ICON =
            "M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 10.99h7c-.53 4.12-3.28 7.79-7 8.94V12H5V6.3l7-3.11v8.8z";

    /** Material Icons settings. */
    static final String SYSTEM_ICON =
            "M19.14,12.94c0.04-0.3,0.06-0.61,0.06-0.94c0-0.32-0.02-0.64-0.07-0.94l2.03-1.58c0.18-0.14,0.23-0.41,0.12-0.61 l-1.92-3.32c-0.12-0.22-0.37-0.29-0.59-0.22l-2.39,0.96c-0.5-0.38-1.03-0.7-1.62-0.94L14.4,2.81c-0.04-0.24-0.24-0.41-0.48-0.41 h-3.84c-0.24,0-0.43,0.17-0.47,0.41L9.25,5.35C8.66,5.59,8.12,5.92,7.63,6.29L5.24,5.33c-0.22-0.08-0.47,0-0.59,0.22L2.74,8.87 C2.62,9.08,2.66,9.34,2.86,9.48l2.03,1.58C4.84,11.36,4.8,11.69,4.8,12s0.02,0.64,0.07,0.94l-2.03,1.58 c-0.18,0.14-0.23,0.41-0.12,0.61l1.92,3.32c0.12,0.22,0.37,0.29,0.59,0.22l2.39-0.96c0.5,0.38,1.03,0.7,1.62,0.94l0.36,2.54 c0.05,0.24,0.24,0.41,0.48,0.41h3.84c0.24,0,0.44-0.17,0.47-0.41l0.36-2.54c0.59-0.24,1.13-0.56,1.62-0.94l2.39,0.96 c0.22,0.08,0.47,0,0.59-0.22l1.92-3.32c0.12-0.22,0.07-0.47-0.12-0.61L19.14,12.94z M12,15.6c-1.98,0-3.6-1.62-3.6-3.6 s1.62-3.6,3.6-3.6s3.6,1.62,3.6,3.6S13.98,15.6,12,15.6z";

    /** Material Icons forum. */
    static final String DISCORD_ICON =
            "M21 6h-2v9H6v2c0 .55.45 1 1 1h11l4 4V7c0-.55-.45-1-1-1zm-4 6V3c0-.55-.45-1-1-1H3c-.55 0-1 .45-1 1v14l4-4h10c.55 0 1-.45 1-1z";

    /** Material Icons lightbulb. */
    static final String RGB_ICON =
            "M9 21c0 .5.4 1 1 1h4c.6 0 1-.5 1-1v-1H9v1zm3-19C8.1 2 5 5.1 5 9c0 2.4 1.2 4.5 3 5.7V17c0 .5.4 1 1 1h6c.6 0 1-.5 1-1v-2.3c1.8-1.3 3-3.4 3-5.7 0-3.9-3.1-7-7-7z";

    /** Material Icons info. */
    static final String ABOUT_ICON =
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z";

    /** Icon colour roles generated per theme ({@code -md-icon-<hue>}). */
    static final List< String > HUES = List.of( "blue", "cyan", "green", "yellow", "orange", "pink", "purple", "grey" );

    /** Position classes {@link #markGroupPositions} sets. */
    static final List< String > POSITIONS = List.of( "tileOnly", "tileFirst", "tileMiddle", "tileLast" );

    private SettingsNavTile()
    {
    }

    /**
     * Decorates the whole sidebar: each category's icon, colour and summary, then the group
     * positions. The buttons come in the sidebar's order (Account, Game, Appearance, Advanced,
     * Network, Security, System, Discord, RGB, About), each inside its group container.
     *
     * @param buttons the ten sidebar buttons, in order
     */
    static void decorateSidebar( MFXButton[] buttons )
    {
        String[][] categories = {
                { ACCOUNT_ICON, "blue", "account" }, { GAME_ICON, "green", "game" },
                { APPEARANCE_ICON, "orange", "appearance" }, { ADVANCED_ICON, "yellow", "advanced" },
                { NETWORK_ICON, "blue", "network" }, { SECURITY_ICON, "cyan", "security" },
                { SYSTEM_ICON, "grey", "system" }, { DISCORD_ICON, "purple", "discord" },
                { RGB_ICON, "pink", "rgb" }, { ABOUT_ICON, "grey", "about" } };
        if ( buttons.length != categories.length ) {
            throw new IllegalArgumentException( "Expected " + categories.length + " sidebar buttons" );
        }
        java.util.List< javafx.scene.Parent > groups = new java.util.ArrayList<>();
        for ( int i = 0; i < buttons.length; i++ ) {
            decorate( buttons[ i ], categories[ i ][ 0 ], categories[ i ][ 1 ],
                      "settings.nav." + categories[ i ][ 2 ] + ".summary" );
            if ( !groups.contains( buttons[ i ].getParent() ) ) {
                groups.add( buttons[ i ].getParent() );
            }
        }
        markGroupPositions( groups );
    }

    /**
     * Gives a sidebar button its icon and summary. The button keeps its text (the category name,
     * from FXML) as the tile's title and as what screen readers announce, with the summary added.
     *
     * @param button     the sidebar button
     * @param iconPath   SVG path data in a 24 px box
     * @param hue        one of {@link #HUES}
     * @param summaryKey the localization key of the one-line summary
     */
    static void decorate( MFXButton button, String iconPath, String hue, String summaryKey )
    {
        if ( !HUES.contains( hue ) ) {
            throw new IllegalArgumentException( "Unknown icon hue: " + hue );
        }
        SVGPath glyph = new SVGPath();
        glyph.setContent( iconPath );
        glyph.getStyleClass().add( "settingsNavGlyph" );
        // 24 px artwork drawn at 20 px inside the 36 px circle, Material's icon-in-container ratio.
        glyph.setScaleX( 20.0 / 24 );
        glyph.setScaleY( 20.0 / 24 );
        StackPane circle = new StackPane( glyph );
        circle.getStyleClass().addAll( "settingsNavIcon", "icon-" + hue );
        circle.setMinSize( 36, 36 );
        circle.setMaxSize( 36, 36 );

        Label title = new Label( button.getText() );
        title.getStyleClass().add( "settingsNavTitle" );
        Label summary = new Label( LocalizationManager.get( summaryKey ) );
        summary.getStyleClass().add( "settingsNavSummary" );
        summary.setTextOverrun( OverrunStyle.ELLIPSIS );
        summary.setMinWidth( 0 );
        VBox text = new VBox( 1, title, summary );
        text.setMinWidth( 0 );
        HBox.setHgrow( text, Priority.ALWAYS );

        HBox row = new HBox( 14, circle, text );
        row.setAlignment( javafx.geometry.Pos.CENTER_LEFT );
        row.setMaxWidth( Double.MAX_VALUE );
        button.setGraphic( row );
        button.setContentDisplay( ContentDisplay.GRAPHIC_ONLY );
        button.setAccessibleText( button.getText() + ", " + summary.getText() );
    }

    /**
     * Tags every tile in each group with its position, replacing any earlier tag.
     *
     * @param groups the {@code .settingsNavGroup} containers
     */
    static void markGroupPositions( List< ? extends javafx.scene.Parent > groups )
    {
        for ( javafx.scene.Parent group : groups ) {
            List< Node > tiles = group.getChildrenUnmodifiable();
            for ( int i = 0; i < tiles.size(); i++ ) {
                Node tile = tiles.get( i );
                tile.getStyleClass().removeAll( POSITIONS );
                tile.getStyleClass().add( positionClass( i, tiles.size() ) );
            }
        }
    }

    /**
     * The position class for a tile.
     *
     * @param index the tile's index in its group
     * @param count the number of tiles in the group
     *
     * @return one of {@link #POSITIONS}
     */
    static String positionClass( int index, int count )
    {
        if ( count == 1 ) {
            return "tileOnly";
        }
        if ( index == 0 ) {
            return "tileFirst";
        }
        return index == count - 1 ? "tileLast" : "tileMiddle";
    }
}
