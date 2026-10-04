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

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * Material 3's motion tokens and the launcher's few standard transitions, so every animated
 * change moves the same way. Entering things decelerate (they arrive fast and settle); leaving
 * things accelerate (they start slow and get out of the way); both are short.
 *
 * <p>Settings › Appearance › Reduce motion ({@link #setReduceMotion}) makes these transitions
 * instant and stills the launcher's ambient animations (the wavy progress bar's wave, the loading
 * indicator's morphing, the progress screen's hopping blocks).
 *
 * @since 2026.10
 */
public final class Motion
{
    /** Material's standard easing, for changes within a screen. */
    public static final Interpolator STANDARD = Interpolator.SPLINE( 0.2, 0.0, 0.0, 1.0 );
    /** Material's emphasized decelerate, for things arriving. */
    public static final Interpolator EMPHASIZED_DECELERATE = Interpolator.SPLINE( 0.05, 0.7, 0.1, 1.0 );
    /** Material's emphasized accelerate, for things leaving. */
    public static final Interpolator EMPHASIZED_ACCELERATE = Interpolator.SPLINE( 0.3, 0.0, 0.8, 0.15 );

    /** Material's duration tokens, in milliseconds. */
    public static final double SHORT = 150;
    public static final double MEDIUM = 300;
    public static final double LONG = 450;

    private static final BooleanProperty REDUCE = new SimpleBooleanProperty( false );

    private Motion()
    {
    }

    /** @param reduce {@code true} to make transitions instant and still ambient animation */
    public static void setReduceMotion( boolean reduce )
    {
        REDUCE.set( reduce );
    }

    /** @return whether motion is reduced */
    public static boolean isReduced()
    {
        return REDUCE.get();
    }

    /**
     * A surface arriving (a modal, a dialog): fades in while growing from 96% to full size.
     *
     * @param node the node arriving
     */
    public static void enter( Node node )
    {
        if ( node == null ) {
            return;
        }
        if ( isReduced() ) {
            reset( node );
            return;
        }
        Duration d = Duration.millis( MEDIUM );
        FadeTransition fade = new FadeTransition( d, node );
        fade.setFromValue( 0 );
        fade.setToValue( 1 );
        ScaleTransition scale = new ScaleTransition( d, node );
        scale.setFromX( 0.96 );
        scale.setFromY( 0.96 );
        scale.setToX( 1 );
        scale.setToY( 1 );
        ParallelTransition all = new ParallelTransition( fade, scale );
        fade.setInterpolator( EMPHASIZED_DECELERATE );
        scale.setInterpolator( EMPHASIZED_DECELERATE );
        node.setOpacity( 0 );
        all.play();
    }

    /**
     * A surface leaving: fades out while shrinking to 96%, then runs {@code done}.
     *
     * @param node the node leaving
     * @param done what to do once it's gone, or {@code null}
     */
    public static void exit( Node node, Runnable done )
    {
        if ( node == null || isReduced() ) {
            if ( done != null ) {
                done.run();
            }
            return;
        }
        Duration d = Duration.millis( SHORT );
        FadeTransition fade = new FadeTransition( d, node );
        fade.setToValue( 0 );
        fade.setInterpolator( EMPHASIZED_ACCELERATE );
        ScaleTransition scale = new ScaleTransition( d, node );
        scale.setToX( 0.96 );
        scale.setToY( 0.96 );
        scale.setInterpolator( EMPHASIZED_ACCELERATE );
        ParallelTransition all = new ParallelTransition( fade, scale );
        all.setOnFinished( e -> {
            reset( node );
            if ( done != null ) {
                done.run();
            }
        } );
        all.play();
    }

    /**
     * Content replacing other content in place (a new screen, a settings category, a tab): fades in
     * while rising a few pixels, Material's fade-through.
     *
     * @param node the incoming content
     */
    public static void fadeThrough( Node node )
    {
        if ( node == null ) {
            return;
        }
        if ( isReduced() ) {
            reset( node );
            return;
        }
        Duration d = Duration.millis( 220 );
        FadeTransition fade = new FadeTransition( d, node );
        fade.setFromValue( 0 );
        fade.setToValue( 1 );
        fade.setInterpolator( EMPHASIZED_DECELERATE );
        TranslateTransition rise = new TranslateTransition( d, node );
        rise.setFromY( 8 );
        rise.setToY( 0 );
        rise.setInterpolator( EMPHASIZED_DECELERATE );
        node.setOpacity( 0 );
        new ParallelTransition( fade, rise ).play();
    }

    /** Puts a node back to rest: fully opaque, full size, not shifted. */
    private static void reset( Node node )
    {
        node.setOpacity( 1 );
        node.setScaleX( 1 );
        node.setScaleY( 1 );
        node.setTranslateY( 0 );
    }
}
