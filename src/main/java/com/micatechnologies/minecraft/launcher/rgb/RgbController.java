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
import com.micatechnologies.minecraft.launcher.rgb.backends.NoOpBackend;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Singleton entry point for the RGB-integration subsystem.
 *
 * <p>Owns the dedicated {@code mica-rgb} worker thread, the bounded
 * frame queue, the active backend reference, and the per-backend
 * circuit breaker. Callers ({@code LauncherCore.play}, the main GUI,
 * effect engine — Phase 4) interact only with {@link #submitFrame} and
 * the lifecycle methods; they never touch a {@link RgbBackend} directly.
 *
 * <h3>Error isolation</h3>
 *
 * <p>The launcher must continue functioning even if every RGB backend
 * blows up — driver updates, removed devices, network glitches, vendor
 * SDK protocol breaks. Five containment layers are in play here:</p>
 *
 * <ol>
 *   <li><b>Lazy probe.</b> Backends are only instantiated when RGB is
 *       enabled in Settings. {@link #start()} bails immediately if
 *       config says off.</li>
 *   <li><b>{@link #safelyInvoke} wrapper.</b> Every call into a backend
 *       is wrapped in catch-{@link Throwable} — covers {@link Error}s
 *       like {@code UnsatisfiedLinkError} from a missing DLL post
 *       driver update.</li>
 *   <li><b>Per-backend circuit breaker</b> via {@link RgbBackendHealth}.
 *       Three states (HEALTHY → DEGRADED → DEAD) with exponential
 *       backoff; a dead backend stops being called for the rest of the
 *       session.</li>
 *   <li><b>Bounded queue + drop-on-full.</b> The frame queue caps at
 *       4 entries; {@link #submitFrame} returns immediately and drops
 *       frames silently rather than queueing them when the backend
 *       worker is stalled.</li>
 *   <li><b>NoOp fallback.</b> When the configured real backend goes
 *       DEAD, the active reference flips to {@link NoOpBackend} —
 *       callers can keep submitting frames without checking state.</li>
 * </ol>
 *
 * <h3>Threading model</h3>
 *
 * <p>All backend method calls happen on the single {@code mica-rgb}
 * worker thread. The FX thread, launch worker threads, and effect
 * engine threads only ever interact with the controller through
 * thread-safe entry points ({@link #submitFrame}, {@link #status()},
 * {@link #start}, {@link #stop}).</p>
 *
 * @since 2026.5
 */
public final class RgbController
{
    /** Maximum frames the queue holds before {@link #submitFrame} starts
     *  dropping. Set low because a stuck backend means the keyboard is
     *  already out of sync with reality; queueing 30+ stale frames just
     *  amplifies the visible glitch when the backend recovers. */
    private static final int FRAME_QUEUE_CAPACITY = 4;

    /** Maximum time the worker waits on the queue before checking the
     *  {@link #running} flag. Shutdown latency is bounded by this. */
    private static final long QUEUE_POLL_TIMEOUT_MS = 500L;

    private static volatile RgbController INSTANCE;

    public static RgbController getInstance()
    {
        RgbController local = INSTANCE;
        if ( local == null ) {
            synchronized ( RgbController.class ) {
                local = INSTANCE;
                if ( local == null ) {
                    local = new RgbController();
                    INSTANCE = local;
                }
            }
        }
        return local;
    }

    private final LinkedBlockingQueue< RgbFrame > frameQueue =
            new LinkedBlockingQueue<>( FRAME_QUEUE_CAPACITY );

    /** The active backend. Reads are unsynchronized via {@code volatile} —
     *  callers see a consistent reference even when {@link #start} or a
     *  DEAD-state flip swaps it. */
    private volatile RgbBackend activeBackend = new NoOpBackend();
    private volatile RgbBackendHealth health = new RgbBackendHealth();

    /** Drives the worker loop. Flipped to false in {@link #stop}; the
     *  worker checks it after each queue poll and exits cleanly. */
    private volatile boolean running = false;
    private Thread worker;

    /** Lazily-created effect engine. Null until the first
     *  {@link #setEffect} call needs it; nulled back out on
     *  {@link #stop} so a subsequent restart gets a fresh scheduler. */
    private RgbEffectEngine effectEngine;

    private RgbController() { /* singleton */ }

    /**
     * Activate the subsystem with the given backend. Idempotent — calling
     * start with the same backend again is a no-op; calling with a
     * different backend stops the current one and swaps in the new one.
     * Safe to call from any thread.
     *
     * <p>If {@code backend.isAvailable()} returns false, or {@code start()}
     * throws, the controller falls back to {@link NoOpBackend} silently.
     * The launcher continues; subsequent {@link #submitFrame} calls are
     * harmless no-ops.</p>
     */
    public synchronized void start( RgbBackend backend )
    {
        if ( backend == null ) backend = new NoOpBackend();

        // Already running this exact backend instance — leave it alone.
        if ( running && activeBackend == backend ) return;

        // Also short-circuit on same backend TYPE — RgbBackendRegistry
        // creates a fresh instance every resolve, so a "restart with the
        // same backend type" from a Settings UI listener that fired
        // spuriously would otherwise tear down a perfectly healthy
        // session and open a new one. Vendor SDKs (notably Razer
        // Synapse) don't tolerate rapid session churn — the prior fix
        // here was per-listener idempotency in the Settings UI; this
        // adds defense in depth so a future caller from anywhere else
        // doesn't repro the bug.
        if ( running && activeBackend != null
                && activeBackend.getClass() == backend.getClass()
                && health.state() != RgbBackendHealth.State.DEAD ) {
            Logger.logDebug( "RGB: same backend type already running ("
                                     + safeName( backend ) + ") — no-op restart." );
            return;
        }

        if ( running ) {
            stopWorkerAndShutdownBackend();
        }

        // Probe before we commit. A failing probe routes to NoOp without
        // ever spinning up the worker thread.
        boolean available;
        try {
            available = backend.isAvailable();
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "RGB backend " + safeName( backend )
                                             + " isAvailable() threw — falling back to NoOp", t );
            available = false;
        }

        if ( !available ) {
            Logger.logDebug( "RGB backend " + safeName( backend )
                                     + " not available on this system; using NoOp." );
            activeBackend = new NoOpBackend();
            health = new RgbBackendHealth();
            return;
        }

        // Probe passed — try to start the real backend.
        try {
            backend.start();
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "RGB backend " + safeName( backend )
                                             + " start() threw — falling back to NoOp", t );
            activeBackend = new NoOpBackend();
            health = new RgbBackendHealth();
            return;
        }

        activeBackend = backend;
        health = new RgbBackendHealth();
        running = true;
        worker = new Thread( this::workerLoop, "mica-rgb" );
        worker.setDaemon( true );
        worker.start();
        Logger.logStd( "RGB subsystem started on backend: " + safeName( backend ) );
    }

    /**
     * Stop the subsystem and release the active backend's resources.
     * Idempotent. Safe to call from any thread including shutdown hooks.
     */
    public synchronized void stop()
    {
        if ( !running ) return;
        // Tear down the effect engine first so it stops pushing frames
        // into the queue while we're trying to drain it.
        if ( effectEngine != null ) {
            effectEngine.shutdown();
            effectEngine = null;
        }
        stopWorkerAndShutdownBackend();
        activeBackend = new NoOpBackend();
        health = new RgbBackendHealth();
    }

    /**
     * Push a frame for the active backend to render. Non-blocking — if
     * the queue is full (worker stalled on a slow SDK call) the frame
     * is dropped silently. Returns immediately on every thread including
     * the FX thread.
     */
    public void submitFrame( RgbFrame frame )
    {
        if ( !running || frame == null ) return;
        // offer (not put) — never block the caller. A full queue means
        // the worker is wedged; queueing more frames just delays the
        // recovery. Drop and move on.
        frameQueue.offer( frame );
    }

    /**
     * Switch the active effect. {@code null} stops the effect engine
     * (one final black frame, scheduler idles). Safe to call from any
     * thread. No-op when the controller isn't running — callers don't
     * need to guard with {@link #status} checks.
     *
     * <p>The effect engine is created lazily on first use, so a launcher
     * session that never enables RGB never spins up the
     * {@code mica-rgb-effects} scheduler thread.</p>
     */
    public void setEffect( RgbEffect effect )
    {
        if ( !running && effect != null ) return;
        RgbEffectEngine eng;
        synchronized ( this ) {
            if ( effectEngine == null ) {
                effectEngine = new RgbEffectEngine( this );
            }
            eng = effectEngine;
        }
        eng.setEffect( effect );
    }

    /** Convenience for {@code setEffect(null)}. */
    public void stopEffect()
    {
        setEffect( null );
    }

    /** Returns the currently-active effect (for the Settings status
     *  chip). Null when no effect or when the engine hasn't been
     *  created yet. */
    public RgbEffect activeEffect()
    {
        RgbEffectEngine eng;
        synchronized ( this ) {
            eng = effectEngine;
        }
        return eng == null ? null : eng.activeEffect();
    }

    /** Snapshot of subsystem state for the Settings status chip. Safe
     *  from any thread. */
    public Status status()
    {
        return new Status( safeName( activeBackend ), health.state(),
                            health.consecutiveFailures(), running );
    }

    public record Status( String backendName, RgbBackendHealth.State health,
                          int consecutiveFailures, boolean running ) {}

    // =========================================================================
    //  Worker
    // =========================================================================

    private void workerLoop()
    {
        Logger.logDebug( "mica-rgb worker thread started." );
        while ( running ) {
            RgbFrame frame;
            try {
                frame = frameQueue.poll( QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS );
            }
            catch ( InterruptedException ie ) {
                // Shutdown signal — re-set the flag and exit the loop.
                Thread.currentThread().interrupt();
                break;
            }
            if ( frame == null ) continue;
            if ( !health.canCall( System.currentTimeMillis() ) ) continue;

            safelyInvoke( "renderFrame", () -> activeBackend.renderFrame( frame ) );
        }
        Logger.logDebug( "mica-rgb worker thread exiting." );
    }

    /** Wrap a backend call. Catches Throwable (not just Exception) so
     *  Errors like UnsatisfiedLinkError land in the circuit breaker
     *  rather than killing the worker thread. */
    private void safelyInvoke( String op, BackendOp body )
    {
        try {
            body.run();
            health.recordSuccess();
        }
        catch ( Throwable t ) {
            long now = System.currentTimeMillis();
            health.recordFailure( now );
            RgbBackendHealth.State newState = health.state();
            Logger.logWarningSilent( "RGB " + op + " on " + safeName( activeBackend )
                                             + " threw (health=" + newState + ", failures="
                                             + health.consecutiveFailures() + ")", t );
            if ( newState == RgbBackendHealth.State.DEAD ) {
                // Permanent for the rest of the session. Demote to NoOp so
                // subsequent submitFrame calls don't bother the dead backend.
                Logger.logError( "RGB backend " + safeName( activeBackend )
                                         + " marked DEAD after repeated failures — falling back to NoOp." );
                safelyShutdownActive();
                activeBackend = new NoOpBackend();
            }
        }
    }

    @FunctionalInterface
    private interface BackendOp
    {
        void run() throws Throwable;
    }

    private void stopWorkerAndShutdownBackend()
    {
        running = false;
        Thread w = worker;
        if ( w != null ) {
            w.interrupt();
            try { w.join( 2_000L ); }
            catch ( InterruptedException ie ) { Thread.currentThread().interrupt(); }
            worker = null;
        }
        safelyShutdownActive();
        frameQueue.clear();
    }

    private void safelyShutdownActive()
    {
        try { activeBackend.shutdown(); }
        catch ( Throwable t ) {
            // Backends' shutdown should never throw, but if a vendor SDK
            // produces an Error during teardown we don't want it to mask
            // the actual launcher shutdown sequence.
            Logger.logWarningSilent( "RGB shutdown on " + safeName( activeBackend )
                                             + " threw — ignoring", t );
        }
    }

    private static String safeName( RgbBackend b )
    {
        if ( b == null ) return "<null>";
        try { return b.name(); }
        catch ( Throwable t ) { return b.getClass().getSimpleName(); }
    }
}
