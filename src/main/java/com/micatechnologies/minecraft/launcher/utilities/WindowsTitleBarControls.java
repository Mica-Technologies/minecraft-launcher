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

package com.micatechnologies.minecraft.launcher.utilities;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.RowConstraints;
import org.apache.commons.lang3.SystemUtils;

/**
 * Windows-only top-bar adjustments for the {@link WindowsCustomChromeManager} title-bar inset.
 * The OS draws the real minimize / maximize / close at the far top-right; this:
 * <ul>
 *   <li>replaces each screen's in-window help "?" with a native-caption-styled one immediately
 *       left of the OS buttons (blue hover), and</li>
 *   <li>on the <b>main menu</b> — the only screen with other controls in the top-right — relocates
 *       its refresh / browse / settings / update / account controls down into the bottom status
 *       bar (which grows to a control-bar height), moving the smaller version label to just left
 *       of the Exit button. This keeps the top-right clear of the native window controls.</li>
 * </ul>
 *
 * <p>All no-ops unless {@link WindowsCustomChromeManager#isActive()}, so nothing here touches the
 * UI on macOS / Linux or when the native subclass didn't install. The help button is a real
 * JavaFX control; {@code WindowsCustomChromeManager}'s WM_NCHITTEST returns {@code HTCLIENT} over
 * interactive controls in the caption strip, so it stays clickable next to the native buttons.</p>
 *
 * @since 3.5
 */
public final class WindowsTitleBarControls
{
    private static final String HELP_STYLE_CLASS = "winCaptionBtn-help";
    /** Height the bottom status bar grows to once it hosts the relocated controls. */
    private static final double BOTTOM_BAR_HEIGHT = 52.0;

    private WindowsTitleBarControls() { /* static-only */ }

    /**
     * Applies the Windows title-bar adjustments to a screen. No-op off Windows / when the chrome
     * is inactive. Idempotent per scene.
     *
     * @param root       the screen's inner root (a GridPane for the launcher's screens)
     * @param helpAction what the help button does (typically open the screen's help topic)
     */
    public static void apply( Parent root, Runnable helpAction )
    {
        if ( !SystemUtils.IS_OS_WINDOWS || root == null
                || !WindowsCustomChromeManager.isActive() ) {
            return;
        }
        // Hide any existing in-window help affordance (the navbar's "?" or an injected corner one)
        // — the caption help button replaces it.
        for ( Node n : root.lookupAll( ".helpButton" ) ) {
            n.setVisible( false );
            n.setManaged( false );
        }

        installHelpButton( root, helpAction );
        relocateMainMenuControls( root );
    }

    /** Adds the caption-styled help button to the top-right, immediately left of the native
     *  buttons. */
    private static void installHelpButton( Parent root, Runnable helpAction )
    {
        if ( root.lookup( "." + HELP_STYLE_CLASS ) != null ) {
            return;   // already added to this scene
        }
        if ( !( root instanceof GridPane grid ) ) {
            return;
        }
        Button help = new Button( "?" );
        help.getStyleClass().addAll( "winCaptionBtn", HELP_STYLE_CLASS );
        help.setMinSize( WindowsCustomChromeManager.BUTTON_WIDTH, WindowsCustomChromeManager.CAPTION_HEIGHT );
        help.setPrefSize( WindowsCustomChromeManager.BUTTON_WIDTH, WindowsCustomChromeManager.CAPTION_HEIGHT );
        help.setMaxSize( WindowsCustomChromeManager.BUTTON_WIDTH, WindowsCustomChromeManager.CAPTION_HEIGHT );
        help.setFocusTraversable( false );
        if ( helpAction != null ) {
            help.setOnAction( e -> helpAction.run() );
        }

        int col = grid.getColumnConstraints().size() - 1;
        if ( col < 0 ) {
            col = 0;
        }
        grid.add( help, col, 0 );
        GridPane.setHalignment( help, HPos.RIGHT );
        GridPane.setValignment( help, VPos.TOP );
        // Sit immediately left of the native min/max/close.
        GridPane.setMargin( help, new Insets( 0, WindowsCustomChromeManager.reservedButtonsWidthLogical(), 0, 0 ) );
    }

    /**
     * Main-menu only: moves the top navbar's refresh / browse / settings / update / divider /
     * account controls into the left of the bottom status bar, grows that bar, and reseats the
     * (smaller) version label just left of Exit. Identified by the presence of {@code #libraryBtn}
     * + {@code #exitBtn}; other screens have neither, so this no-ops. Idempotent — once the browse
     * button lives in the bottom bar it's left alone.
     */
    private static void relocateMainMenuControls( Parent root )
    {
        Node exit = root.lookup( "#exitBtn" );
        Node browse = root.lookup( "#libraryBtn" );
        if ( exit == null || browse == null || !( exit.getParent() instanceof HBox bottomBar ) ) {
            return;   // not the main menu
        }
        if ( browse.getParent() == bottomBar ) {
            return;   // already relocated
        }

        // Move the top-right controls (in left-to-right order) to the start of the bottom bar.
        Node[] toMove = {
                root.lookup( "#refreshIcon" ),
                root.lookup( "#libraryBtn" ),
                root.lookup( "#settingsBtn" ),
                root.lookup( "#updateImgView" ),
                root.lookup( ".navDivider" ),
                root.lookup( "#userImage" ),
                root.lookup( "#playerLabel" ),
        };
        int insert = 0;
        for ( Node n : toMove ) {
            if ( n == null ) {
                continue;
            }
            if ( n.getParent() instanceof Pane src ) {
                src.getChildren().remove( n );
            }
            bottomBar.getChildren().add( insert++, n );
        }

        // Version label: a touch smaller, reseated just left of the Exit button.
        Node version = root.lookup( "#versionLabel" );
        if ( version != null ) {
            bottomBar.getChildren().remove( version );
            int exitIdx = bottomBar.getChildren().indexOf( exit );
            if ( exitIdx < 0 ) {
                exitIdx = bottomBar.getChildren().size();
            }
            bottomBar.getChildren().add( exitIdx, version );
            version.setStyle( "-fx-font-size: 11px;" );
        }

        // Grow the bottom bar so the relocated controls fit comfortably (matches the taller
        // control bars on the other screens).
        Integer rowIdx = GridPane.getRowIndex( bottomBar );
        if ( root instanceof GridPane grid && rowIdx != null && rowIdx < grid.getRowConstraints().size() ) {
            RowConstraints rc = grid.getRowConstraints().get( rowIdx );
            rc.setMinHeight( BOTTOM_BAR_HEIGHT );
            rc.setPrefHeight( BOTTOM_BAR_HEIGHT );
            rc.setMaxHeight( BOTTOM_BAR_HEIGHT );
        }
    }
}
