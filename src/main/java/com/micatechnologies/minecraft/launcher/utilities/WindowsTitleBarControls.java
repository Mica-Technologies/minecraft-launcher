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

import javafx.css.PseudoClass;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import org.apache.commons.lang3.SystemUtils;

/**
 * JavaFX side of the experimental Windows frameless title bar. Paints the three caption buttons
 * (minimize, maximize/restore, close) in the top-right corner of each screen and shifts the main
 * navbar so its trailing controls clear them.
 *
 * <p>The buttons are <b>drawn</b> here but <b>hit-tested natively</b> by
 * {@link WindowsCustomChromeManager}'s window procedure (which returns {@code HTMINBUTTON} /
 * {@code HTMAXBUTTON} / {@code HTCLOSE} for these rects — that is what gives the Windows 11
 * snap-layouts flyout on the maximize button). Because the OS owns those rects, JavaFX never
 * receives mouse events over them, so: the click action is performed by the WndProc, and hover
 * visuals are driven by the WndProc feeding a hovered-index back here via
 * {@link WindowsCustomChromeManager#setHoverListener(java.util.function.IntConsumer)}.</p>
 *
 * <p>Everything no-ops unless {@link WindowsCustomChromeManager#isActive()} — so a failed native
 * install leaves screens untouched.</p>
 *
 * @since 3.5
 */
public final class WindowsTitleBarControls
{
    private static final PseudoClass HOVER = PseudoClass.getPseudoClass( "hover" );
    private static final String CONTROLS_STYLE_CLASS = "winTitleBarControls";

    /** 10×10 glyph view box, stroked (themed via {@code -fx-stroke} in CSS). */
    private static final String GLYPH_MIN     = "M0,5 L10,5";
    private static final String GLYPH_MAX     = "M0.5,0.5 L9.5,0.5 L9.5,9.5 L0.5,9.5 Z";
    private static final String GLYPH_RESTORE = "M2.5,0.5 L9.5,0.5 L9.5,7.5 L7.5,7.5 M0.5,2.5 L7.5,2.5 L7.5,9.5 L0.5,9.5 Z";
    private static final String GLYPH_CLOSE   = "M0,0 L10,10 M10,0 L0,10";

    private WindowsTitleBarControls() { /* static-only */ }

    /**
     * Adds the caption-button cluster to the top-right of {@code root}. No-op unless the native
     * chrome installed and {@code root} is a {@link GridPane} (the launcher's screen roots are
     * GridPanes — same assumption the in-window help-button injection makes). Idempotent per
     * scene.
     *
     * @param root  the screen's inner root (so the buttons inherit its theme tokens)
     * @param stage the stage whose window the buttons drive
     */
    public static void addControls( Parent root, Stage stage )
    {
        if ( !SystemUtils.IS_OS_WINDOWS || stage == null || root == null
                || !WindowsCustomChromeManager.isActive() ) {
            return;
        }
        if ( root.lookup( "." + CONTROLS_STYLE_CLASS ) != null ) {
            return;   // already added to this scene
        }
        if ( !( root instanceof GridPane grid ) ) {
            return;   // unsupported root layout — window is still draggable/resizable natively
        }

        HBox controls = buildControls( stage );

        int col = grid.getColumnConstraints().size() - 1;
        if ( col < 0 ) {
            col = 0;
        }
        grid.add( controls, col, 0 );
        GridPane.setHalignment( controls, HPos.RIGHT );
        GridPane.setValignment( controls, VPos.TOP );
    }

    /**
     * On the main screen (which carries its own {@code .navBar} + account/help controls), pads the
     * navbar's right edge by the reserved caption-button width so its trailing items don't slide
     * under the buttons. No-op off Windows / when inactive / when there's no navbar.
     */
    public static void shiftNavbarForControls( Parent root, Stage stage )
    {
        if ( !SystemUtils.IS_OS_WINDOWS || root == null || stage == null
                || !WindowsCustomChromeManager.isActive() ) {
            return;
        }
        Node navBar = root.lookup( ".navBar" );
        if ( !( navBar instanceof Region bar ) ) {
            return;
        }
        double reserve = WindowsCustomChromeManager.reservedWidthLogical();
        Insets p = bar.getPadding();
        if ( p.getRight() >= reserve ) {
            return;   // already shifted (idempotent per scene)
        }
        bar.setPadding( new Insets( p.getTop(), p.getRight() + reserve, p.getBottom(), p.getLeft() ) );
    }

    /** Reserved top-right width (logical px) so callers (e.g. the injected help button on
     *  navbar-less screens) can offset themselves clear of the caption buttons. */
    public static double reservedWidthLogical()
    {
        return WindowsCustomChromeManager.reservedWidthLogical();
    }

    // -----------------------------------------------------------------------------------------

    private static HBox buildControls( Stage stage )
    {
        StackPane min   = captionCell( GLYPH_MIN, false );
        SVGPath  maxGlyph = glyph( stage.isMaximized() ? GLYPH_RESTORE : GLYPH_MAX );
        StackPane max   = captionCell( maxGlyph, false );
        StackPane close = captionCell( GLYPH_CLOSE, true );

        // Swap the maximize/restore glyph as the window state changes (native snap / our toggle).
        stage.maximizedProperty().addListener( ( obs, was, isMax ) ->
            maxGlyph.setContent( isMax ? GLYPH_RESTORE : GLYPH_MAX ) );

        HBox controls = new HBox( min, max, close );
        controls.getStyleClass().add( CONTROLS_STYLE_CLASS );
        controls.setSpacing( 0 );
        controls.setAlignment( Pos.CENTER );
        controls.setMouseTransparent( true );   // OS hit-tests these rects; FX never gets the events
        controls.setPickOnBounds( false );
        double w = (double) WindowsCustomChromeManager.BASE_BUTTON_WIDTH
                * WindowsCustomChromeManager.BUTTON_COUNT;
        double h = WindowsCustomChromeManager.BASE_CAPTION_HEIGHT;
        controls.setMinSize( w, h );
        controls.setPrefSize( w, h );
        controls.setMaxSize( w, h );

        // Drive themed hover visuals from the native WndProc's hover hit-testing.
        StackPane[] cells = { min, max, close };
        WindowsCustomChromeManager.setHoverListener( index -> {
            for ( int i = 0; i < cells.length; i++ ) {
                cells[ i ].pseudoClassStateChanged( HOVER, i == index );
            }
        } );

        return controls;
    }

    private static StackPane captionCell( String glyphPath, boolean isClose )
    {
        return captionCell( glyph( glyphPath ), isClose );
    }

    private static StackPane captionCell( SVGPath glyph, boolean isClose )
    {
        StackPane cell = new StackPane( glyph );
        cell.getStyleClass().add( "winCaptionBtn" );
        if ( isClose ) {
            cell.getStyleClass().add( "winCaptionBtn-close" );
        }
        cell.setMinSize( WindowsCustomChromeManager.BASE_BUTTON_WIDTH,
                         WindowsCustomChromeManager.BASE_CAPTION_HEIGHT );
        cell.setPrefSize( WindowsCustomChromeManager.BASE_BUTTON_WIDTH,
                          WindowsCustomChromeManager.BASE_CAPTION_HEIGHT );
        cell.setMaxSize( WindowsCustomChromeManager.BASE_BUTTON_WIDTH,
                         WindowsCustomChromeManager.BASE_CAPTION_HEIGHT );
        return cell;
    }

    private static SVGPath glyph( String path )
    {
        SVGPath svg = new SVGPath();
        svg.setContent( path );
        svg.getStyleClass().add( "glyph" );
        return svg;
    }
}
