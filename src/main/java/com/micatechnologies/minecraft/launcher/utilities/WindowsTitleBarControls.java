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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.apache.commons.lang3.SystemUtils;

/**
 * Windows-only top-bar adjustments for the {@link WindowsCustomChromeManager} title-bar inset. The
 * native title bar is gone (the subclass reclaims it so content fills to the top edge); this draws
 * the launcher's own chrome in its place:
 * <ul>
 *   <li><b>Caption cluster</b> — our own help / minimize / maximize / close buttons, sized to fill
 *       the full {@value WindowsCustomChromeManager#TITLE_BAR_HEIGHT}px title-bar height and themed
 *       to match (close gets a red hover). Minimize + close are wired in JavaFX; the maximize slot
 *       is left to the OS via {@code HTMAXBUTTON} so the Windows 11 snap-layouts flyout still fires
 *       on hover (see {@link WindowsCustomChromeManager}).</li>
 *   <li><b>Title-bar blend</b> — the top navbar is repainted in the window background colour
 *       (the {@code win-titlebar} style class) so the reclaimed strip reads as one seamless surface
 *       with the content below, instead of a lighter band.</li>
 *   <li><b>Main-menu top-bar arrangement</b> — the main menu keeps its controls in the title bar
 *       (like macOS / Linux), just like every other screen: the refresh icon is grouped next to the
 *       update icon, and a trailing spacer reserves room so the right-aligned controls clear the
 *       caption cluster. The bottom status bar is left as the FXML defines it (version + Exit).</li>
 * </ul>
 *
 * <p>All no-ops unless {@link WindowsCustomChromeManager#isActive()}, so nothing here touches the
 * UI on macOS / Linux or when the native subclass didn't install.</p>
 *
 * @since 3.5
 */
public final class WindowsTitleBarControls
{
    /** Marker class on the assembled caption cluster — used for the idempotency check. */
    private static final String CLUSTER_STYLE_CLASS = "winCaptionCluster";
    /** Id of the trailing spacer that keeps the navbar controls clear of the caption cluster. */
    private static final String CLEARANCE_ID = "winCaptionClearance";

    // Caption-button glyphs, drawn as 1px-stroked SVG paths in a ~10x10 box (themed + hover-coloured
    // via the .winCaptionGlyph CSS). Stroked, not filled, so they stay crisp and recolour cleanly.
    private static final String GLYPH_MIN     = "M0,5.5 H10";
    private static final String GLYPH_MAX     = "M0,0 H10 V10 H0 Z";
    private static final String GLYPH_RESTORE = "M0,2 H8 V10 H0 Z M2,2 V0 H10 V8 H8";
    private static final String GLYPH_CLOSE   = "M0,0 L10,10 M10,0 L0,10";

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
        // — the caption cluster's help button replaces it.
        for ( Node n : root.lookupAll( ".helpButton" ) ) {
            n.setVisible( false );
            n.setManaged( false );
        }

        blendTitleBar( root );
        installCaptionButtons( root, helpAction );
        arrangeMainMenuTopBar( root );
        reserveCaptionClearance( root );
    }

    /**
     * Prepares the title bar <i>before</i> the scene is shown — blends the navbar (so it's never
     * painted at its default lighter colour first) and arranges the main-menu controls (so they're
     * already cleared into their final spots). Call this right before {@code stage.setScene(...)};
     * the full {@link #apply} still runs afterwards for the caption buttons (which need the stage).
     * No-op off Windows / when the chrome is inactive.
     *
     * @param root the screen's inner root (already attached to its Scene, CSS-resolvable)
     */
    public static void prePaintSetup( Parent root )
    {
        if ( !SystemUtils.IS_OS_WINDOWS || root == null
                || !WindowsCustomChromeManager.isActive() ) {
            return;
        }
        blendTitleBar( root );
        arrangeMainMenuTopBar( root );
        reserveCaptionClearance( root );
    }

    /**
     * Makes the title-bar navbar transparent so the rootPane's own background shows through it. The
     * row-0 navbar is the title bar; the filter-row navbar (row 2, on the main menu) is left alone.
     *
     * <p>Transparency (rather than matching a colour) means the strip is seamless in <i>every</i>
     * theme automatically: solid-colour themes show the rootPane's solid background, and the native
     * theme — whose rootPane is fully transparent so DWM Mica composites through — shows the same
     * Mica. It also needs no recomputation when the theme changes (the rootPane updates underneath),
     * so there's nothing to re-run off the FX thread. The default {@code .navBar} rule paints a
     * lighter solid colour (the band); the inline {@code transparent} deterministically overrides it
     * and a literal keyword always resolves inline (a {@code -color-bg} lookup does not).</p>
     */
    private static void blendTitleBar( Parent root )
    {
        for ( Node n : root.lookupAll( ".navBar" ) ) {
            Integer row = GridPane.getRowIndex( n );
            if ( row == null || row == 0 ) {
                if ( !n.getStyleClass().contains( "win-titlebar" ) ) {
                    n.getStyleClass().add( "win-titlebar" );
                }
                n.setStyle( "-fx-background-color: transparent; -fx-border-width: 0;" );
                return;
            }
        }
    }

    /** Builds the help / minimize / maximize / close caption cluster and anchors it to the
     *  top-right of the title bar, filling the bar height. */
    private static void installCaptionButtons( Parent root, Runnable helpAction )
    {
        if ( !( root instanceof GridPane grid ) ) {
            return;
        }
        if ( root.lookup( "." + CLUSTER_STYLE_CLASS ) != null ) {
            return;   // already added to this scene
        }
        final Stage stage = resolveStage( root );

        // Help "?" — a text glyph (blue hover), to the left of the window controls.
        Button help = captionButton( LocalizationManager.get( "titleBarControls.help" ), "winCaptionBtn-help" );
        help.setText( "?" );
        if ( helpAction != null ) {
            help.setOnAction( e -> helpAction.run() );
        }

        // Minimize — JavaFX-handled (HTCLIENT).
        Button min = captionButton( LocalizationManager.get( "titleBarControls.minimize" ), "winCaptionBtn-min" );
        min.setGraphic( glyph( GLYPH_MIN ) );
        min.setOnAction( e -> {
            if ( stage != null ) {
                stage.setIconified( true );
            }
        } );

        // Maximize / restore — the OS owns the click + snap-layouts flyout via HTMAXBUTTON, so the
        // JavaFX handler is only a harmless fallback. The glyph follows the maximized state.
        SVGPath maxGlyph = glyph( GLYPH_MAX );
        Button max = captionButton( LocalizationManager.get( "titleBarControls.maximize" ), "winCaptionBtn-max" );
        max.setGraphic( maxGlyph );
        max.setOnAction( e -> {
            if ( stage != null ) {
                stage.setMaximized( !stage.isMaximized() );
            }
        } );
        if ( stage != null ) {
            updateMaxGlyph( maxGlyph, stage.isMaximized() );
            stage.maximizedProperty().addListener( ( obs, was, is ) -> updateMaxGlyph( maxGlyph, is ) );
        }
        // The maximize button is owned by the OS (HTMAXBUTTON, for the snap-layouts flyout), so it
        // never receives JavaFX hover events. Drive a simulated hover from the native non-client
        // mouse tracking instead, so it lights up like the minimize/close buttons beside it.
        WindowsCustomChromeManager.setMaxButtonHoverSink( hovered -> {
            // Self-correcting: reconcile the class against the latest state every time, so a missed
            // leave / stale flag can't leave it stuck either way.
            boolean has = max.getStyleClass().contains( "win-hover" );
            if ( hovered && !has ) {
                max.getStyleClass().add( "win-hover" );
            }
            else if ( !hovered && has ) {
                max.getStyleClass().remove( "win-hover" );
            }
        } );

        // Close — JavaFX-handled (HTCLIENT). Fire WINDOW_CLOSE_REQUEST so each screen's own
        // close handler (e.g. the main menu's Exit → LauncherCore.closeApp) runs, matching the
        // behaviour of the OS close button it replaces.
        Button close = captionButton( LocalizationManager.get( "titleBarControls.close" ), "winCaptionBtn-close" );
        close.setGraphic( glyph( GLYPH_CLOSE ) );
        close.setOnAction( e -> {
            if ( stage != null ) {
                stage.fireEvent( new WindowEvent( stage, WindowEvent.WINDOW_CLOSE_REQUEST ) );
            }
        } );

        HBox cluster = new HBox( help, min, max, close );
        cluster.getStyleClass().add( CLUSTER_STYLE_CLASS );
        cluster.setAlignment( Pos.TOP_RIGHT );
        cluster.setFillHeight( true );
        cluster.setPickOnBounds( false );
        cluster.setMaxWidth( Region.USE_PREF_SIZE );
        cluster.setMaxHeight( WindowsCustomChromeManager.TITLE_BAR_HEIGHT );

        int col = grid.getColumnConstraints().size() - 1;
        if ( col < 0 ) {
            col = 0;
        }
        grid.add( cluster, col, 0 );
        GridPane.setHalignment( cluster, HPos.RIGHT );
        GridPane.setValignment( cluster, VPos.TOP );
    }

    /** Swaps the maximize button between the single-square (maximize) and overlapped-squares
     *  (restore) glyph to mirror the window state. */
    private static void updateMaxGlyph( SVGPath glyph, boolean maximized )
    {
        glyph.setContent( maximized ? GLYPH_RESTORE : GLYPH_MAX );
    }

    /** A flat, square caption button sized to one native button slot × the full title-bar height. */
    private static Button captionButton( String accessibleText, String... extraStyleClasses )
    {
        Button b = new Button();
        b.getStyleClass().add( "winCaptionBtn" );
        b.getStyleClass().addAll( extraStyleClasses );
        b.setFocusTraversable( false );
        b.setAccessibleText( accessibleText );
        b.setMinSize( WindowsCustomChromeManager.BUTTON_WIDTH, WindowsCustomChromeManager.TITLE_BAR_HEIGHT );
        b.setPrefSize( WindowsCustomChromeManager.BUTTON_WIDTH, WindowsCustomChromeManager.TITLE_BAR_HEIGHT );
        b.setMaxSize( WindowsCustomChromeManager.BUTTON_WIDTH, WindowsCustomChromeManager.TITLE_BAR_HEIGHT );
        return b;
    }

    /** A stroked (not filled) SVG glyph node, themed via the {@code .winCaptionGlyph} CSS class. */
    private static SVGPath glyph( String path )
    {
        SVGPath p = new SVGPath();
        p.setContent( path );
        p.getStyleClass().add( "winCaptionGlyph" );
        return p;
    }

    /** Resolves the owning Stage from a screen root, or {@code null} if not yet attached. */
    private static Stage resolveStage( Parent root )
    {
        Scene scene = root.getScene();
        if ( scene != null && scene.getWindow() instanceof Stage s ) {
            return s;
        }
        return null;
    }

    /**
     * Main-menu only: keeps the navbar's controls in the title bar (matching macOS / Linux and every
     * other screen) rather than banishing them to the bottom bar — groups the refresh icon next to
     * the update icon. The trailing clearance that keeps the right-aligned controls clear of the
     * caption cluster is reserved generically by {@link #reserveCaptionClearance} for every screen.
     *
     * <p>Identified by {@code #libraryBtn} living in an HBox navbar; other screens have no such
     * button, so this no-ops. Idempotent — the reorder is skipped once refresh already sits before
     * update.</p>
     */
    private static void arrangeMainMenuTopBar( Parent root )
    {
        Node browse = root.lookup( "#libraryBtn" );
        if ( browse == null || !( browse.getParent() instanceof HBox navBar ) ) {
            return;   // not the main menu
        }

        // Group the refresh icon next to the update icon (move refresh to just before update).
        Node refresh = root.lookup( "#refreshIcon" );
        Node update = root.lookup( "#updateImgView" );
        if ( refresh != null && update != null ) {
            var kids = navBar.getChildren();
            int ri = kids.indexOf( refresh );
            int ui = kids.indexOf( update );
            if ( ri >= 0 && ui >= 0 && ri != ui - 1 ) {
                kids.remove( refresh );
                kids.add( kids.indexOf( update ), refresh );
            }
        }
    }

    /**
     * Reserves trailing space in the title-bar navbar (row 0) so its right-aligned controls clear
     * the caption cluster (help + min/max/close) pinned to the top-right corner. Without this, any
     * right-anchored navbar content — e.g. the Game Console's "Running" status label or the main
     * menu's player identity — slides underneath the caption buttons and overlaps them.
     *
     * <p>A fixed-width spacer appended as the navbar's last child, not {@code -fx-padding} — the
     * {@code .navBar} CSS padding stomps a programmatic right-padding. Idempotent: the spacer is
     * added only once per scene. No-op for screens whose row-0 isn't an HBox navbar.</p>
     */
    private static void reserveCaptionClearance( Parent root )
    {
        for ( Node n : root.lookupAll( ".navBar" ) ) {
            Integer row = GridPane.getRowIndex( n );
            if ( ( row == null || row == 0 ) && n instanceof HBox navBar ) {
                if ( navBar.lookup( "#" + CLEARANCE_ID ) == null ) {
                    Region clearance = new Region();
                    clearance.setId( CLEARANCE_ID );
                    // help + minimize + maximize + close, each one button-slot wide.
                    double w = WindowsCustomChromeManager.BUTTON_WIDTH * 4.0;
                    clearance.setMinWidth( w );
                    clearance.setPrefWidth( w );
                    clearance.setMaxWidth( w );
                    clearance.setMouseTransparent( true );
                    navBar.getChildren().add( clearance );
                }
                return;
            }
        }
    }
}
