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

import io.github.palexdev.materialfx.controls.MFXToggleButton;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.event.EventHandler;
import javafx.geometry.HPos;
import javafx.geometry.VPos;
import javafx.scene.control.Label;
import javafx.scene.control.SkinBase;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

/**
 * A Material 3 switch for MaterialFX toggle buttons, drawn the way Android 16 draws its switch with
 * icons: an outlined track with a handle carrying an X when off; a filled primary track with a
 * handle carrying a check mark when on. The handle is the same size in both states. The label sits on the left and the switch at the right edge, as in Android's settings
 * rows. The handle slides with Material's emphasized easing, its icon cross-fading from the X to the
 * check, and swells while pressed.
 * Sized at three quarters of Material's mobile switch (52 x 32), a better fit for a desktop window.
 *
 * <p>{@code ui-base.css} installs it on every {@code MFXToggleButton} with {@code -fx-skin}, so no
 * FXML or controller changes. A skin set from CSS replaces the control's own input handling, so
 * this one fires the toggle on a click and on Space or Enter. Colours come from the theme's
 * Material roles in {@code ui-base.css} ({@code .m3-switch-*}).
 *
 * @since 2026.10
 */
public class MaterialSwitchSkin extends SkinBase< MFXToggleButton >
{
    static final double TRACK_WIDTH = 40;
    static final double TRACK_HEIGHT = 24;
    private static final double GAP = 16;
    private static final double HANDLE = 18;
    private static final double HANDLE_PRESSED = 21;
    private static final double HALO = 30;

    private final Label label = new Label();
    private final StackPane track = new StackPane();
    private final Region halo = new Region();
    private final StackPane handle = new StackPane();
    private final SVGPath check = new SVGPath();
    private final SVGPath cross = new SVGPath();

    /** 0 = off, 1 = on, animated between. */
    private final DoubleProperty position = new SimpleDoubleProperty();
    private Timeline animation;
    private boolean armed;

    private final EventHandler< MouseEvent > pressed = e -> {
        if ( e.isPrimaryButtonDown() && !getSkinnable().isDisabled() ) {
            armed = true;
            getSkinnable().requestFocus();
            pseudo( "pressed-handle", true );
            requestLayout();
        }
    };
    private final EventHandler< MouseEvent > released = e -> {
        boolean fire = armed && getSkinnable().contains( e.getX(), e.getY() );
        armed = false;
        pseudo( "pressed-handle", false );
        requestLayout();
        if ( fire ) {
            getSkinnable().fire();
        }
    };
    private final EventHandler< KeyEvent > keys = e -> {
        if ( e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.ENTER ) {
            getSkinnable().fire();
            e.consume();
        }
    };

    /**
     * Creates the skin. Public with this signature so {@code -fx-skin} in CSS can instantiate it.
     *
     * @param control the toggle to draw
     */
    public MaterialSwitchSkin( MFXToggleButton control )
    {
        super( control );
        label.textProperty().bind( control.textProperty() );
        label.setWrapText( true );
        label.setMinWidth( 0 );
        label.getStyleClass().add( "m3-switch-label" );

        track.getStyleClass().add( "m3-switch-track" );
        track.setManaged( false );
        halo.getStyleClass().add( "m3-switch-halo" );
        halo.setManaged( false );
        halo.setMouseTransparent( true );
        handle.getStyleClass().add( "m3-switch-handle" );
        handle.setManaged( false );
        check.setContent( LauncherIcons.CHECK );
        check.getStyleClass().add( "m3-switch-icon" );
        cross.setContent( LauncherIcons.CLOSE );
        cross.getStyleClass().add( "m3-switch-icon-off" );
        handle.getChildren().addAll( cross, check );

        getChildren().setAll( label, track, halo, handle );

        position.set( control.isSelected() ? 1 : 0 );
        position.addListener( ( o, a, b ) -> getSkinnable().requestLayout() );
        registerChangeListener( control.selectedProperty(), o -> animateTo( control.isSelected() ? 1 : 0 ) );
        registerChangeListener( control.hoverProperty(), o -> requestLayout() );
        registerChangeListener( control.focusedProperty(), o -> requestLayout() );

        control.addEventHandler( MouseEvent.MOUSE_PRESSED, pressed );
        control.addEventHandler( MouseEvent.MOUSE_RELEASED, released );
        control.addEventHandler( KeyEvent.KEY_PRESSED, keys );
    }

    private void pseudo( String name, boolean on )
    {
        getSkinnable().pseudoClassStateChanged( javafx.css.PseudoClass.getPseudoClass( name ), on );
    }

    private void requestLayout()
    {
        getSkinnable().requestLayout();
    }

    private void animateTo( double target )
    {
        if ( animation != null ) {
            animation.stop();
        }
        // Material's emphasized-decelerate curve, short duration for a small control.
        animation = new Timeline( new KeyFrame( Duration.millis( 150 ),
                new KeyValue( position, target, Interpolator.SPLINE( 0.05, 0.7, 0.1, 1.0 ) ) ) );
        animation.play();
    }

    @Override
    protected void layoutChildren( double x, double y, double w, double h )
    {
        boolean hasText = label.getText() != null && !label.getText().isEmpty();
        label.setVisible( hasText );
        double switchX = x + w - TRACK_WIDTH;
        if ( hasText ) {
            layoutInArea( label, x, y, Math.max( 0, w - TRACK_WIDTH - GAP ), h, 0, HPos.LEFT, VPos.CENTER );
        }
        else {
            switchX = x;
        }
        double trackY = y + ( h - TRACK_HEIGHT ) / 2;
        track.resizeRelocate( switchX, trackY, TRACK_WIDTH, TRACK_HEIGHT );

        double p = position.get();
        double size = armed ? HANDLE_PRESSED : HANDLE;
        // Handle centre runs from the left end of the track (off) to the right end (on).
        double cx = switchX + TRACK_HEIGHT / 2 + ( TRACK_WIDTH - TRACK_HEIGHT ) * p;
        double cy = trackY + TRACK_HEIGHT / 2;
        handle.resizeRelocate( cx - size / 2, cy - size / 2, size, size );
        // 24 px icons drawn at 12 px inside the handle, the X shrinking out as the check grows in.
        showIcon( check, p );
        showIcon( cross, 1 - p );
        halo.resizeRelocate( cx - HALO / 2, cy - HALO / 2, HALO, HALO );
        halo.setVisible( !getSkinnable().isDisabled()
                         && ( getSkinnable().isHover() || getSkinnable().isFocused() || armed ) );

        boolean on = p >= 0.5;
        track.pseudoClassStateChanged( javafx.css.PseudoClass.getPseudoClass( "on" ), on );
        handle.pseudoClassStateChanged( javafx.css.PseudoClass.getPseudoClass( "on" ), on );
        halo.pseudoClassStateChanged( javafx.css.PseudoClass.getPseudoClass( "on" ), on );
    }

    private static void showIcon( SVGPath icon, double amount )
    {
        icon.setOpacity( amount );
        icon.setScaleX( 12.0 / 24 * Math.max( amount, 0.01 ) );
        icon.setScaleY( 12.0 / 24 * Math.max( amount, 0.01 ) );
    }

    @Override
    protected double computePrefWidth( double height, double top, double right, double bottom, double left )
    {
        boolean hasText = label.getText() != null && !label.getText().isEmpty();
        return left + ( hasText ? label.prefWidth( -1 ) + GAP : 0 ) + TRACK_WIDTH + right;
    }

    @Override
    protected double computePrefHeight( double width, double top, double right, double bottom, double left )
    {
        double labelWidth = width < 0 ? -1 : Math.max( 0, width - left - right - TRACK_WIDTH - GAP );
        return top + Math.max( TRACK_HEIGHT, label.prefHeight( labelWidth ) ) + bottom;
    }

    @Override
    protected double computeMinHeight( double width, double top, double right, double bottom, double left )
    {
        return computePrefHeight( width, top, right, bottom, left );
    }

    @Override
    protected double computeMinWidth( double height, double top, double right, double bottom, double left )
    {
        return left + TRACK_WIDTH + right;
    }

    @Override
    public void dispose()
    {
        MFXToggleButton control = getSkinnable();
        if ( control != null ) {
            control.removeEventHandler( MouseEvent.MOUSE_PRESSED, pressed );
            control.removeEventHandler( MouseEvent.MOUSE_RELEASED, released );
            control.removeEventHandler( KeyEvent.KEY_PRESSED, keys );
        }
        if ( animation != null ) {
            animation.stop();
        }
        super.dispose();
    }
}
