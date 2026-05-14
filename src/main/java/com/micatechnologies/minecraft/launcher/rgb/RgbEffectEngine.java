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

package com.micatechnologies.minecraft.launcher.rgb;

import com.micatechnologies.minecraft.launcher.files.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Drives the active {@link RgbEffect} at a fixed cadence, pushing the
 * resulting frames into {@link RgbController}.
 *
 * <p>Lives on a dedicated daemon thread (the
 * {@code mica-rgb-effects} scheduler) — separate from the controller's
 * own backend thread. The split lets the engine tick at a predictable
 * cadence regardless of how long any single backend render takes; if
 * the backend is wedged on a slow socket, the engine keeps producing
 * frames and the controller's bounded queue drops the surplus until
 * the backend recovers.</p>
 *
 * <p>Effects are swapped via {@link #setEffect(RgbEffect)} from any
 * thread. The change takes effect on the next tick; there's no
 * cross-fade in V1 (each effect just starts emitting frames from
 * elapsed time zero). When no effect is active the engine pauses its
 * scheduler — no work happens at all.</p>
 *
 * @since 2026.5
 */
public final class RgbEffectEngine
{
    /** Engine tick interval. 33ms ≈ 30fps; matches what most RGB SDKs
     *  comfortably accept without queueing latency, and is plenty
     *  smooth for the breathe / pulse animations the V1 effects
     *  produce. */
    private static final long TICK_INTERVAL_MS = 33L;

    private final RgbController controller;
    private final ScheduledExecutorService scheduler;

    private final Object lock = new Object();
    private RgbEffect activeEffect;
    private long effectStartMs;
    private ScheduledFuture< ? > tickFuture;

    public RgbEffectEngine( RgbController controller )
    {
        this.controller = controller;
        this.scheduler = Executors.newSingleThreadScheduledExecutor( r -> {
            Thread t = new Thread( r, "mica-rgb-effects" );
            t.setDaemon( true );
            return t;
        } );
    }

    /**
     * Switch to {@code effect}. Pass {@code null} to stop the engine
     * (equivalent to {@link #stop()}). Idempotent — setting the same
     * effect instance again is a no-op and does NOT restart its
     * elapsed-time clock.
     */
    public void setEffect( RgbEffect effect )
    {
        synchronized ( lock ) {
            if ( activeEffect == effect ) return;
            activeEffect = effect;
            effectStartMs = System.currentTimeMillis();
            if ( effect == null ) {
                cancelTickFuture();
                // Push one final black frame so the keyboard returns
                // to a known state instead of holding whatever the
                // last effect's color was when it was switched away.
                controller.submitFrame( RgbFrame.solid( RgbColor.BLACK ) );
                return;
            }
            // Lazy schedule — start ticking only when there's actually
            // an effect to render.
            if ( tickFuture == null ) {
                tickFuture = scheduler.scheduleAtFixedRate( this::tick,
                                                              0,
                                                              TICK_INTERVAL_MS,
                                                              TimeUnit.MILLISECONDS );
            }
        }
    }

    /** Stops the engine (no current effect, no ticking, one final
     *  black frame pushed). The scheduler stays alive so a subsequent
     *  setEffect call can re-arm it cheaply. */
    public void stop()
    {
        setEffect( null );
    }

    /** Returns the currently-active effect, or {@code null} if none.
     *  Provided mainly for the Settings status chip. */
    public RgbEffect activeEffect()
    {
        synchronized ( lock ) {
            return activeEffect;
        }
    }

    /**
     * Shut down the scheduler entirely. Called from the launcher's
     * cleanup path on exit; after this the engine cannot be reused.
     */
    public void shutdown()
    {
        synchronized ( lock ) {
            activeEffect = null;
            cancelTickFuture();
        }
        scheduler.shutdownNow();
    }

    // =========================================================================
    //  Internals
    // =========================================================================

    private void cancelTickFuture()
    {
        if ( tickFuture != null ) {
            tickFuture.cancel( false );
            tickFuture = null;
        }
    }

    private void tick()
    {
        RgbEffect effect;
        long start;
        synchronized ( lock ) {
            effect = activeEffect;
            start = effectStartMs;
        }
        if ( effect == null ) return;
        try {
            long elapsed = System.currentTimeMillis() - start;
            RgbFrame frame = effect.frameAt( elapsed );
            if ( frame != null ) {
                controller.submitFrame( frame );
            }
        }
        catch ( Throwable t ) {
            // An effect that throws is logged silently and skipped for
            // this tick; the next tick re-asks. We deliberately don't
            // demote the effect — a one-off exception in a colour
            // calculation shouldn't kill the active effect for the
            // whole session. Persistent failures will spam the log
            // and the user can pick a different effect; that's
            // better UX than silently dropping back to a no-op.
            Logger.logWarningSilent( "RGB effect '" + safeName( effect )
                                             + "' threw at tick", t );
        }
    }

    private static String safeName( RgbEffect e )
    {
        if ( e == null ) return "<null>";
        try { return e.name(); }
        catch ( Throwable t ) { return e.getClass().getSimpleName(); }
    }
}
