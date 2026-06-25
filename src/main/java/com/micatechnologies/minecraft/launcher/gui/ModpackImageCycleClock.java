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

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A single, app-wide "tick" clock that drives every modpack logo / background image
 * cycle in lockstep (issue #43). Each on-screen surface that displays a pack with
 * multiple images — the main-menu hero cards and the detail modal — {@linkplain
 * #register registers} a tick callback; on every interval the clock fires them all so
 * the displayed images advance together. Centralizing the timer here (rather than one
 * {@code Timeline} per card) keeps the cost flat regardless of how many cards are
 * visible and gives the "synced" behavior for free.
 *
 * <p>The interval is read from {@link ConfigManager#getImageCycleInterval()} and can be
 * changed live from Settings via {@link #reconfigure()}. The {@code "never"} option
 * stops the clock entirely (surfaces then just show their first image); manual stepping
 * in the detail modal works independently of the clock, so the ◀ ▶ buttons still
 * function when cycling is disabled.</p>
 *
 * <p>All methods must be called on the JavaFX Application Thread — they are, since the
 * only callers are FX-thread UI code. The underlying {@link Timeline} runs its frames on
 * the FX thread, so tick callbacks fire there too and may touch the scene graph directly.</p>
 *
 * @author Mica Technologies
 * @since 3.6
 */
public final class ModpackImageCycleClock
{
    private static final ModpackImageCycleClock INSTANCE = new ModpackImageCycleClock();

    /**
     * Returns the shared, app-wide clock instance that every modpack image surface
     * subscribes to. There is exactly one clock per JVM so all surfaces advance in
     * lockstep.
     *
     * @return the singleton clock instance
     */
    public static ModpackImageCycleClock getInstance()
    {
        return INSTANCE;
    }

    /** Tick callbacks. Copy-on-write so a callback can unregister itself (or another)
     *  during a tick without a {@link java.util.ConcurrentModificationException}. */
    private final List< Runnable > listeners = new CopyOnWriteArrayList<>();

    /** The running timeline, or {@code null} when stopped (no listeners, or "never"). */
    private Timeline timeline;

    /**
     * Private constructor to enforce singleton pattern.
     */
    private ModpackImageCycleClock() { /* singleton */ }

    /**
     * Subscribes {@code onTick} to the clock and starts it if needed. The returned
     * handle removes the subscription (and stops the clock when the last listener
     * leaves) — call it from the surface's teardown (card recycle, modal close).
     *
     * @param onTick run on the FX thread on every interval; should advance + repaint
     *               the surface's current image
     * @return an unsubscribe handle; idempotent
     */
    public Runnable register( Runnable onTick )
    {
        if ( onTick == null ) {
            return () -> { };
        }
        listeners.add( onTick );
        ensureRunning();
        return new Runnable()
        {
            private boolean removed = false;

            @Override
            public void run()
            {
                if ( removed ) {
                    return;
                }
                removed = true;
                listeners.remove( onTick );
                if ( listeners.isEmpty() ) {
                    stop();
                }
            }
        };
    }

    /**
     * Re-reads the configured interval and rebuilds the timeline so a Settings change
     * takes effect immediately. A no-op shape-wise when the interval is unchanged, but
     * cheap enough to always rebuild.
     */
    public void reconfigure()
    {
        stop();
        ensureRunning();
    }

    /** (Re)starts the timeline if there are listeners and the interval isn't "never". */
    private void ensureRunning()
    {
        if ( timeline != null || listeners.isEmpty() ) {
            return;
        }
        Duration interval = tokenToDuration( ConfigManager.getImageCycleInterval() );
        if ( interval == null ) {
            return; // "never" — no cycling
        }
        timeline = new Timeline( new KeyFrame( interval, e -> fireTick() ) );
        timeline.setCycleCount( Animation.INDEFINITE );
        timeline.play();
    }

    /**
     * Stops the current timeline if it is running.
     */
    private void stop()
    {
        if ( timeline != null ) {
            timeline.stop();
            timeline = null;
        }
    }

    /**
     * Fires a tick event to all registered listeners.
     */
    private void fireTick()
    {
        for ( Runnable r : listeners ) {
            try {
                r.run();
            }
            catch ( Throwable ignored ) {
                // A misbehaving surface must not kill the shared clock for everyone else.
            }
        }
    }

    /**
     * Maps a canonical interval token (see {@link ConfigConstants#IMAGE_CYCLE_INTERVAL_OPTIONS})
     * to a concrete {@link Duration}, or {@code null} for {@code "never"} / anything
     * unrecognized (treated as "don't cycle").
     *
     * @param token the interval token to map
     * @return the corresponding duration, or {@code null} if the token is "never" or unrecognized
     */
    static Duration tokenToDuration( String token )
    {
        if ( token == null ) {
            return null;
        }
        return switch ( token ) {
            case "5s"  -> Duration.seconds( 5 );
            case "15s" -> Duration.seconds( 15 );
            case "30s" -> Duration.seconds( 30 );
            case "1m"  -> Duration.minutes( 1 );
            case "5m"  -> Duration.minutes( 5 );
            case "15m" -> Duration.minutes( 15 );
            case "30m" -> Duration.minutes( 30 );
            case "1h"  -> Duration.hours( 1 );
            case "6h"  -> Duration.hours( 6 );
            case "12h" -> Duration.hours( 12 );
            case "1d"  -> Duration.hours( 24 );
            case "7d"  -> Duration.hours( 24 * 7 );
            default    -> null; // "never" and unknown
        };
    }
}
