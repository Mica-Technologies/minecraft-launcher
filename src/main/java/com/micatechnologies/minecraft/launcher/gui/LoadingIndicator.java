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

import javafx.animation.AnimationTimer;
import javafx.scene.layout.Region;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;

import java.util.ArrayList;
import java.util.List;

/**
 * Material 3 Expressive's loading indicator: a soft shape that keeps turning while it morphs from
 * one form to the next (a nine-lobed cookie, a rounded pentagon, a four-lobed cookie, a sunburst,
 * an oval, a hexagon, an eight-lobed cookie), each change landing with a small overshoot. It
 * replaces the indeterminate spinner. Size it with {@code setPrefSize}; colours come from
 * {@code ui-base.css} ({@code .loadingIndicator*}).
 *
 * <p>Each shape is a radius that varies with angle, {@code 1 + a cos(kθ)} for {@code k} lobes of
 * depth {@code a}, sampled at fixed angles, so any two shapes blend point for point. The animation
 * reads the clock, and runs only while the indicator can be seen ({@link AnimationGate}).
 *
 * @since 2026.10
 */
public class LoadingIndicator extends Region
{
    /** Lobe count and depth of each shape, in the order they morph through. */
    static final double[][] SHAPES = { { 9, 0.09 }, { 5, 0.13 }, { 4, 0.14 }, { 12, 0.05 }, { 2, 0.16 },
                                       { 6, 0.10 }, { 8, 0.07 } };
    /** How long each morph takes, including its settle. */
    private static final double MORPH_S = 0.65;
    /** One full turn of the base rotation. */
    private static final double TURN_S = 1.6;
    private static final int SAMPLES = 96;

    private final Path shape = new Path();
    private final AnimationTimer timer = new AnimationTimer()
    {
        @Override
        public void handle( long now )
        {
            draw( now / 1e9 );
        }
    };
    /** Runs {@link #timer} only while the indicator is seen; held here so its listeners live. */
    private final AnimationGate gate;

    /** Creates a 32 px indicator. */
    public LoadingIndicator()
    {
        getStyleClass().add( "loadingIndicator" );
        shape.getStyleClass().add( "loadingIndicatorShape" );
        shape.setManaged( false );
        shape.setStrokeWidth( 0 );
        getChildren().add( shape );
        setPrefSize( 32, 32 );
        setMinSize( USE_PREF_SIZE, USE_PREF_SIZE );
        setMaxSize( USE_PREF_SIZE, USE_PREF_SIZE );
        gate = AnimationGate.attach( this, timer );
    }

    @Override
    protected void layoutChildren()
    {
        draw( System.nanoTime() / 1e9 );
    }

    private void draw( double seconds )
    {
        double size = Math.min( getWidth(), getHeight() );
        if ( size <= 0 ) {
            return;
        }
        // Reduce motion keeps one shape and only turns it, slowly: still clearly busy.
        double clock = Motion.isReduced() ? 0 : seconds;
        shape.getElements().setAll( outline( clock, getWidth() / 2, getHeight() / 2, size / 2 ) );
        shape.setRotate( Motion.isReduced() ? ( seconds * 90 ) % 360 : 0 );
    }

    /**
     * The indicator's outline at a moment in time.
     *
     * @param seconds the clock, in seconds
     * @param cx      centre x
     * @param cy      centre y
     * @param radius  the outer radius
     *
     * @return the closed outline
     */
    static List< PathElement > outline( double seconds, double cx, double cy, double radius )
    {
        double cycle = seconds / MORPH_S;
        int index = (int) Math.floor( cycle );
        double p = cycle - index;
        double[] from = SHAPES[ Math.floorMod( index, SHAPES.length ) ];
        double[] to = SHAPES[ Math.floorMod( index + 1, SHAPES.length ) ];
        double e = easeOutBack( p );
        // Turn steadily, plus a quarter turn with each morph so the change reads as a motion.
        double rotation = seconds / TURN_S * Math.PI * 2 + ( index + e ) * Math.PI / 2;

        List< PathElement > els = new ArrayList<>( SAMPLES + 2 );
        for ( int i = 0; i < SAMPLES; i++ ) {
            double t = i * Math.PI * 2 / SAMPLES;
            double r = radius * ( shapeRadius( from, t ) * ( 1 - e ) + shapeRadius( to, t ) * e );
            double x = cx + Math.cos( t + rotation ) * r;
            double y = cy + Math.sin( t + rotation ) * r;
            els.add( i == 0 ? new MoveTo( x, y ) : new LineTo( x, y ) );
        }
        els.add( new ClosePath() );
        return els;
    }

    /** A shape's radius at angle t, scaled so its outermost point is 1. */
    static double shapeRadius( double[] shape, double t )
    {
        double lobes = shape[ 0 ];
        double depth = shape[ 1 ];
        return ( 1 + depth * Math.cos( lobes * t ) ) / ( 1 + depth );
    }

    /** Ease out with a small overshoot, so each morph lands with a little bounce. */
    static double easeOutBack( double p )
    {
        double c1 = 1.4;
        double c3 = c1 + 1;
        return 1 + c3 * Math.pow( p - 1, 3 ) + c1 * Math.pow( p - 1, 2 );
    }
}
