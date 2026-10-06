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
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.ObservableList;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.scene.shape.StrokeLineCap;

import java.util.ArrayList;
import java.util.List;

/**
 * Material 3 Expressive's wavy linear progress indicator: the filled part is a moving sine wave,
 * the rest a flat track after a small gap, with a stop dot marking the end. A negative progress
 * (as {@link javafx.scene.control.ProgressBar#INDETERMINATE_PROGRESS}) sweeps a wavy segment
 * across the track.
 *
 * <p>The wave's phase comes from the clock rather than from each bar, so a bar that is rebuilt
 * (the Running Games pane recreates its step rows on every update) keeps moving smoothly instead
 * of restarting. The animation only runs while the bar can be seen ({@link AnimationGate}). Settings ›
 * Appearance can turn the wave off ({@link #setWavyEnabled}); bars then draw flat. Colours come
 * from {@code ui-base.css} ({@code .wavy-progress .wavy-*}).
 *
 * @since 2026.10
 */
public class WavyProgressBar extends Region
{
    /** Stroke thickness of the wave and the track. */
    static final double STROKE = 4;
    /** Wave height above and below the centre line. */
    static final double AMPLITUDE = 3;
    /** Distance between wave crests. */
    static final double WAVELENGTH = 28;
    /** Space between the end of the wave and the start of the track. */
    static final double GAP = 4;
    /** One full wave cycle every this many seconds. */
    private static final double WAVE_PERIOD_S = 1.2;
    /** Indeterminate sweep: one pass every this many seconds, covering this share of the width. */
    private static final double SWEEP_PERIOD_S = 1.8;
    private static final double SWEEP_LENGTH = 0.38;

    /** Whether bars draw the wave (Settings › Appearance); off draws them flat. Shared by every
     *  bar, so changing it restyles the bars already on screen. */
    private static final BooleanProperty WAVY = new SimpleBooleanProperty( true );

    private final DoubleProperty progress = new SimpleDoubleProperty( this, "progress", 0 );
    private final Path wave = new Path();
    private final Path track = new Path();
    private final Circle stop = new Circle( STROKE / 2 );
    private final PathWriter waveOut = new PathWriter( wave );
    private final PathWriter trackOut = new PathWriter( track );
    /** The progress currently drawn; eases toward {@link #progress} so jumps animate. */
    private double shown;

    private final AnimationTimer timer = new AnimationTimer()
    {
        @Override
        public void handle( long now )
        {
            redraw( now );
        }
    };
    /** Runs {@link #timer} only while the bar is seen; held here so its listeners live. */
    private final AnimationGate gate;

    /** Creates a bar at zero progress. */
    public WavyProgressBar()
    {
        this( 0 );
    }

    /**
     * Creates a bar.
     *
     * @param initial the starting progress, 0 to 1, or negative for indeterminate
     */
    public WavyProgressBar( double initial )
    {
        getStyleClass().add( "wavy-progress" );
        wave.getStyleClass().add( "wavy-active" );
        track.getStyleClass().add( "wavy-track" );
        stop.getStyleClass().add( "wavy-stop" );
        for ( Path p : new Path[]{ wave, track } ) {
            p.setStrokeWidth( STROKE );
            p.setStrokeLineCap( StrokeLineCap.ROUND );
            p.setManaged( false );
        }
        stop.setManaged( false );
        getChildren().addAll( track, wave, stop );
        setProgress( initial );
        shown = Math.max( 0, initial );
        setMinHeight( AMPLITUDE * 2 + STROKE + 2 );
        setPrefHeight( AMPLITUDE * 2 + STROKE + 2 );
        setMaxHeight( AMPLITUDE * 2 + STROKE + 2 );
        setPrefWidth( 240 );

        // Animate only while seen: visible, in a scene, in a showing window.
        gate = AnimationGate.attach( this, timer );
    }

    /**
     * Turns the wave on or off for every bar. Off draws the filled part flat, keeping the gap and
     * the stop dot: Material's standard linear indicator.
     *
     * @param wavy {@code true} for the wave
     */
    public static void setWavyEnabled( boolean wavy )
    {
        WAVY.set( wavy );
    }

    /** @return whether bars draw the wave */
    public static boolean isWavyEnabled()
    {
        return WAVY.get();
    }

    /** @return the progress property: 0 to 1, or negative for indeterminate */
    public DoubleProperty progressProperty()
    {
        return progress;
    }

    /** @return the progress, 0 to 1, or negative for indeterminate */
    public double getProgress()
    {
        return progress.get();
    }

    /** @param value the progress, 0 to 1, or negative for indeterminate */
    public void setProgress( double value )
    {
        progress.set( value );
    }

    @Override
    protected void layoutChildren()
    {
        redraw( System.nanoTime() );
    }

    private void redraw( long nanos )
    {
        double w = getWidth() - snappedLeftInset() - snappedRightInset();
        if ( w <= 0 ) {
            return;
        }
        double x0 = snappedLeftInset();
        double cy = snappedTopInset() + ( getHeight() - snappedTopInset() - snappedBottomInset() ) / 2;
        double seconds = nanos / 1e9;
        // Reduce motion holds the wave still; the indeterminate sweep (which shows that work is
        // happening) keeps moving.
        double phase = Motion.isReduced() ? 0 : ( seconds % WAVE_PERIOD_S ) / WAVE_PERIOD_S * Math.PI * 2;

        double amplitude = WAVY.get() ? AMPLITUDE : 0;
        double target = getProgress();
        // Runs every frame while animating: the paths' existing elements are moved rather than
        // replaced, so a frame allocates nothing unless the element count grows.
        waveOut.begin();
        trackOut.begin();
        if ( target < 0 ) {
            // Indeterminate: a wavy segment sweeps left to right; the track shows either side.
            double t = ( seconds % SWEEP_PERIOD_S ) / SWEEP_PERIOD_S;
            double len = w * SWEEP_LENGTH;
            double start = -len + ( w + len ) * t;
            double a = Math.max( 0, start );
            double b = Math.min( w, start + len );
            if ( b > a ) {
                wave( waveOut, x0 + a, x0 + b, cy, phase, amplitude );
            }
            if ( a - GAP > STROKE ) {
                trackOut.point( x0 + STROKE / 2, cy, true );
                trackOut.point( x0 + a - GAP, cy, false );
            }
            if ( w - ( b + GAP ) > STROKE ) {
                trackOut.point( x0 + b + GAP, cy, true );
                trackOut.point( x0 + w - STROKE / 2, cy, false );
            }
            stop.setVisible( false );
        }
        else {
            // Ease the drawn value toward the target so progress jumps glide.
            shown += ( Math.min( 1, target ) - shown ) * 0.2;
            if ( Math.abs( shown - target ) < 0.001 ) {
                shown = Math.min( 1, target );
            }
            double end = x0 + STROKE / 2 + ( w - STROKE ) * shown;
            if ( shown > 0 ) {
                wave( waveOut, x0 + STROKE / 2, end, cy, phase, amplitude );
            }
            double trackStart = shown > 0 ? end + GAP + STROKE : x0 + STROKE / 2;
            double trackEnd = x0 + w - STROKE / 2;
            if ( trackEnd - trackStart > 0 ) {
                trackOut.point( trackStart, cy, true );
                trackOut.point( trackEnd, cy, false );
            }
            stop.setVisible( shown < 0.999 );
            stop.setCenterX( trackEnd );
            stop.setCenterY( cy );
        }
        waveOut.end();
        trackOut.end();
    }

    /**
     * The wave from {@code from} to {@code to} as path elements. Its height ramps in over the first
     * wavelength, so a short wave grows from a flat start instead of beginning mid-crest.
     * An amplitude of zero gives a flat line.
     */
    static List< PathElement > wavePath( double from, double to, double cy, double phase, double amplitude )
    {
        List< PathElement > els = new ArrayList<>();
        wave( ( x, y, move ) -> els.add( move ? new MoveTo( x, y ) : new LineTo( x, y ) ),
              from, to, cy, phase, amplitude );
        return els;
    }

    /** Receives a polyline's points; {@code move} marks the first point of a stroke. */
    @FunctionalInterface
    interface PointSink
    {
        void point( double x, double y, boolean move );
    }

    /** Writes the points of {@link #wavePath} to a sink. */
    private static void wave( PointSink out, double from, double to, double cy, double phase, double amplitude )
    {
        double step = 2;
        boolean first = true;
        for ( double x = from; x <= to + 0.001; x += step ) {
            double xx = Math.min( x, to );
            double ramp = Math.min( 1, ( xx - from ) / WAVELENGTH );
            double y = cy + Math.sin( xx / WAVELENGTH * Math.PI * 2 - phase ) * amplitude * ramp;
            out.point( xx, y, first );
            first = false;
        }
    }

    /** Rewrites a path's elements in place, reusing those already there. */
    private static final class PathWriter implements PointSink
    {
        private final Path path;
        private       int  count;

        PathWriter( Path path )
        {
            this.path = path;
        }

        void begin()
        {
            count = 0;
        }

        @Override
        public void point( double x, double y, boolean move )
        {
            ObservableList< PathElement > els = path.getElements();
            if ( count < els.size() ) {
                PathElement e = els.get( count );
                if ( move && e instanceof MoveTo m ) {
                    m.setX( x );
                    m.setY( y );
                }
                else if ( !move && e instanceof LineTo l ) {
                    l.setX( x );
                    l.setY( y );
                }
                else {
                    els.set( count, move ? new MoveTo( x, y ) : new LineTo( x, y ) );
                }
            }
            else {
                els.add( move ? new MoveTo( x, y ) : new LineTo( x, y ) );
            }
            count++;
        }

        void end()
        {
            ObservableList< PathElement > els = path.getElements();
            if ( els.size() > count ) {
                els.remove( count, els.size() );
            }
        }
    }
}
