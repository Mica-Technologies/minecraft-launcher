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
 * <p>The glyphs come from {@link LauncherIcons}, drawn in a 24 px box.
 *
 * @since 2026.10
 */
final class SettingsNavTile
{
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
                { LauncherIcons.ACCOUNT, "blue", "account" }, { LauncherIcons.GAME, "green", "game" },
                { LauncherIcons.APPEARANCE, "orange", "appearance" }, { LauncherIcons.ADVANCED, "yellow", "advanced" },
                { LauncherIcons.NETWORK, "blue", "network" }, { LauncherIcons.SECURITY, "cyan", "security" },
                { LauncherIcons.SYSTEM, "grey", "system" }, { LauncherIcons.DISCORD, "purple", "discord" },
                { LauncherIcons.RGB, "pink", "rgb" }, { LauncherIcons.ABOUT, "grey", "about" } };
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
