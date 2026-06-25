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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.rgb.backends.NoOpBackend;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton entry point for the RGB-integration subsystem.
 *
 * <p>Owns the dedicated {@code mica-rgb} worker thread, the bounded
 * frame queue, the active backend list, and the per-backend circuit
 * breakers. Callers ({@code LauncherCore.play}, the main GUI, effect
 * engine) interact only with {@link #submitFrame} and the lifecycle
 * methods; they never touch a {@link RgbBackend} directly.</p>
 *
 * <h3>Multi-backend operation</h3>
 *
 * <p>Each {@link #start(List)} call activates every backend in the
 * list whose {@code isAvailable()} probe succeeds and whose
 * {@code start()} doesn't throw. Per-frame, the worker dispatches the
 * same frame to every healthy backend in turn — so a user with both
 * Razer Chroma fans and a Windows-Dynamic-Lighting keyboard gets both
 * ecosystems lit from one effect render. Each backend has its own
 * {@link RgbBackendHealth} circuit breaker, so one vendor blowing up
 * doesn't take the others down with it.</p>
 *
 * <h3>Error isolation</h3>
 *
 * <p>The launcher must continue functioning even if every RGB backend
 * blows up — driver updates, removed devices, network glitches, vendor
 * SDK protocol breaks. Containment layers in play:</p>
 *
 * <ol>
 *   <li><b>Lazy probe.</b> Backends are only instantiated when RGB is
 *       enabled in Settings. {@link #start(List)} bails immediately
 *       when given an empty list.</li>
 *   <li><b>{@link #safelyInvoke} wrapper.</b> Every call into a backend
 *       is wrapped in catch-{@link Throwable} — covers {@link Error}s
 *       like {@code UnsatisfiedLinkError} from a missing DLL post
 *       driver update.</li>
 *   <li><b>Per-backend circuit breaker</b> via {@link RgbBackendHealth}.
 *       Three states (HEALTHY → DEGRADED → DEAD) with exponential
 *       backoff; a dead backend stops being called for the rest of the
 *       session, but its siblings keep running.</li>
 *   <li><b>Bounded queue + drop-on-full.</b> The frame queue caps at
 *       4 entries; {@link #submitFrame} returns immediately and drops
 *       frames silently rather than queueing them when the worker is
 *       stalled.</li>
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

    /**
     * Returns the process-wide controller singleton, lazily creating it
     * on first call using double-checked locking. The created instance
     * is inert until {@link #start(List)} (or {@link #start(RgbBackend)})
     * activates it, so calling this from cold paths is cheap.
     *
     * @return the shared {@link RgbController} instance; never {@code null}
     *
     * @since 2026.5
     */
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

    /** Pairs a running backend with its own circuit breaker. The worker
     *  iterates this list per frame and gives each slot independent
     *  health tracking — one vendor going DEAD doesn't poison the
     *  others. */
    private static final class BackendSlot
    {
        final RgbBackend backend;
        final RgbBackendHealth health;

        /**
         * Constructs a new {@link BackendSlot} with the specified backend and its associated health.
         *
         * @param backend the RGB backend to be paired with a circuit breaker
         */
        BackendSlot( RgbBackend backend )
        {
            this.backend = backend;
            this.health = new RgbBackendHealth();
        }
    }

    /** Active slots. CopyOnWriteArrayList so the worker can iterate
     *  without locking even while {@link #start} or
     *  {@link #safelyInvoke}'s DEAD-state demotion swaps entries. */
    private final CopyOnWriteArrayList< BackendSlot > slots = new CopyOnWriteArrayList<>();

    /** Backend types most recently asked for via {@link #start(List)}. 
     *  Compared against the next start() call to decide whether to
     *  no-op or rebuild. This list is what the CALLER requested — not
     *  the subset that actually started — so a transient backend
     *  failure (e.g. Windows Dynamic Lighting's PowerShell handshake
     *  timing out) doesn't trigger a full teardown of the working
     *  siblings every time the user toggles a Settings checkbox. */
    private List< Class< ? extends RgbBackend > > lastRequestedTypes = List.of();

    /** Drives the worker loop. Flipped to false in {@link #stop}; the
     *  worker checks it after each queue poll and exits cleanly. */
    private volatile boolean running = false;
    private Thread worker;

    /** Lazily-created effect engine. Null until the first
     *  {@link #setEffect} call needs it; nulled back out on
     *  {@link #stop} so a subsequent restart gets a fresh scheduler. */
    private RgbEffectEngine effectEngine;

    /**
     * Private constructor for the singleton pattern.
     */
    private RgbController() { /* singleton */ }

    /**
     * Single-backend convenience overload. {@code null} or a
     * {@link NoOpBackend} starts an empty session (no real backends).
     *
     * @param backend the lone backend to activate, or {@code null} /
     *                a {@link NoOpBackend} to start an empty session
     *
     * @since 2026.5
     */
    public synchronized void start( RgbBackend backend )
    {
        if ( backend == null || backend instanceof NoOpBackend ) {
            start( Collections.< RgbBackend >emptyList() );
        }
        else {
            start( Collections.singletonList( backend ) );
        }
    }

    /**
     * Activate the subsystem with the given backend list. Idempotent
     * against the same backend types — restarting with the same set is
     * a no-op when every existing slot is still healthy. A different
     * set tears down the current slots and rebuilds with the new ones.
     * Safe to call from any thread.
     *
     * <p>For each backend in {@code backends}, the controller probes
     * {@code isAvailable()} and calls {@code start()}; either failing
     * (or throwing) drops just that backend from the active list. The
     * launcher continues with whatever subset succeeded — including
     * none.</p>
     *
     * @param backends the backends to probe and activate; {@code null}
     *                 is treated as an empty list, {@link NoOpBackend}
     *                 entries are filtered out
     *
     * @since 2026.5
     */
    public synchronized void start( List< RgbBackend > backends )
    {
        List< RgbBackend > requested = backends == null
                ? Collections.< RgbBackend >emptyList() : backends;

        // Filter NoOps — they're never worth a slot. The single-backend
        // overload uses them as a sentinel, so callers can pass one
        // without meaning "spin up an empty no-op session".
        List< RgbBackend > real = new ArrayList<>( requested.size() );
        for ( RgbBackend b : requested ) {
            if ( b != null && !( b instanceof NoOpBackend ) ) real.add( b );
        }

        // Already running this exact set of backend TYPES (as last
        // requested by a caller — NOT as currently surviving in slots)
        // and every surviving slot is still healthy → leave it alone.
        //
        // The "as requested" part matters: WinDL fails its handshake
        // sometimes, so a request for {ChromaNative, WinDL} surfaces
        // as slots={ChromaNative}. The Settings UI may then call start()
        // again with the same {ChromaNative, WinDL} request after a
        // user toggle elsewhere — comparing to slots would see "size
        // changed!" and tear down the working ChromaNative just to try
        // WinDL again. Comparing to the prior request avoids that.
        //
        // Vendor SDKs (notably Razer Synapse) don't tolerate rapid
        // session churn — this is defense in depth on top of the
        // Settings UI's idempotent listeners.
        if ( running && sameRequestAndAllSlotsHealthy( real ) ) {
            Logger.logDebug( LocalizationManager.format( "log.rgb.controller.sameBackendNoOp", describeSlots() ) );
            return;
        }

        if ( running ) {
            stopWorkerAndShutdownSlots();
        }

        slots.clear();
        // Remember the new request even before we know how many slots
        // will successfully start. A follow-up start() with the same
        // request list short-circuits via the check above.
        List< Class< ? extends RgbBackend > > newTypes = new ArrayList<>( real.size() );
        for ( RgbBackend b : real ) newTypes.add( b.getClass() );
        lastRequestedTypes = Collections.unmodifiableList( newTypes );

        // Probe + start each backend independently. One failing doesn't
        // stop the rest.
        for ( RgbBackend backend : real ) {
            boolean available;
            try {
                available = backend.isAvailable();
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.rgb.controller.backendIsAvailableThrew", safeName( backend ) ), t );
                continue;
            }
            if ( !available ) {
                Logger.logDebug( LocalizationManager.format( "log.rgb.controller.backendNotAvailable", safeName( backend ) ) );
                continue;
            }

            try {
                backend.start();
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.rgb.controller.backendStartThrew", safeName( backend ) ), t );
                continue;
            }

            slots.add( new BackendSlot( backend ) );
        }

        if ( slots.isEmpty() ) {
            Logger.logDebug( LocalizationManager.get( "log.rgb.controller.noBackendsStarted" ) );
            running = false;
            // No backends came up on this (re)start — tear down any stray effect
            // engine left from a prior run so its scheduler thread doesn't tick
            // forever against a stopped controller (submitFrame would just drop
            // every frame). Mirrors stop()'s engine teardown.
            if ( effectEngine != null ) {
                effectEngine.shutdown();
                effectEngine = null;
            }
            return;
        }

        running = true;
        worker = new Thread( this::workerLoop, "mica-rgb" );
        worker.setDaemon( true );
        worker.start();
        Logger.logStd( LocalizationManager.format( "log.rgb.controller.subsystemStarted", describeSlots() ) );
    }

    /**
     * Stop the subsystem and release every active backend's resources.
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
        stopWorkerAndShutdownSlots();
        slots.clear();
        lastRequestedTypes = List.of();
    }

    /**
     * Push a frame for the active backends to render. Non-blocking —
     * if the queue is full (worker stalled on a slow SDK call) the
     * frame is dropped silently. Returns immediately on every thread
     * including the FX thread.
     *
     * @param frame the frame to enqueue for rendering; {@code null} is
     *             ignored, as is any frame submitted while the
     *             controller is not running
     *
     * @since 2026.5
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
     *
     * @param effect the effect to drive, or {@code null} to stop the
     *              current effect (painting one final black frame)
     *
     * @since 2026.5
     */
    public void setEffect( RgbEffect effect )
    {
        if ( !running && effect != null ) return;
        RgbEffectEngine eng;
        synchronized ( this ) {
            // Stopping an effect when no engine exists is a no-op — don't lazily
            // spin one up just to idle it. Doing so would leak the engine's
            // scheduler thread, since stop() early-returns while !running and
            // never tears a stray engine down.
            if ( effectEngine == null && effect == null ) {
                return;
            }
            if ( effectEngine == null ) {
                effectEngine = new RgbEffectEngine( this );
            }
            eng = effectEngine;
        }
        eng.setEffect( effect );
    }

    /**
     * Convenience for {@code setEffect(null)} — stops the active effect.
     *
     * @since 2026.5
     */
    public void stopEffect()
    {
        setEffect( null );
    }

    /**
     * Returns the currently-active effect (for the Settings status
     * chip). Null when no effect or when the engine hasn't been
     * created yet.
     *
     * @return the active {@link RgbEffect}, or {@code null} when none is
     *         set or the lazy effect engine has not been created
     *
     * @since 2026.5
     */
    public RgbEffect activeEffect()
    {
        RgbEffectEngine eng;
        synchronized ( this ) {
            eng = effectEngine;
        }
        return eng == null ? null : eng.activeEffect();
    }

    /**
     * Per-backend snapshot for the Settings status chip and any
     * future telemetry. {@code name} is the backend's
     * {@link RgbBackend#name()} string, {@code health} is the circuit
     * breaker state, and {@code consecutiveFailures} is the current
     * failure run length (useful for "Degraded (3 errors)" displays).
     *
     * @param name                the backend's {@link RgbBackend#name()}
     *                            display string
     * @param health              the circuit-breaker state of this backend
     * @param consecutiveFailures the current consecutive-failure run length
     *
     * @since 2026.5
     */
    public record BackendStatus( String name, RgbBackendHealth.State health,
                                 int consecutiveFailures ) {}

    /** Snapshot of subsystem state for the Settings status chip. Safe
     *  from any thread. {@code backends} is in dispatch order; the
     *  first entry is the "primary" displayed in compact status
     *  surfaces. Empty when nothing is running.
     *
     * @param backends per-backend status snapshots in dispatch order;
     *                 the first entry is the "primary" backend
     * @param running  whether the controller's worker is currently active
     *
     * @since 2026.5
     */
    public record Status( List< BackendStatus > backends, boolean running )
    {
        /**
         * Convenience accessor for legacy chip code that only renders
         * one backend. Returns {@code "None"} when empty.
         *
         * @return the name of the primary (first) backend, or
         *         {@code "None"} when no backends are present
         *
         * @since 2026.5
         */
        public String primaryName()
        {
            return backends.isEmpty() ? "None" : backends.get( 0 ).name();
        }

        /**
         * Worst health across all slots — HEALTHY &gt; DEGRADED &gt; DEAD.
         * Lets the chip show DEAD when any one backend is dead even if
         * others are fine, which is the right thing for "is everything
         * working" status.
         *
         * @return the worst (least-healthy) state across all backends;
         *         {@link RgbBackendHealth.State#HEALTHY} when empty
         *
         * @since 2026.5
         */
        public RgbBackendHealth.State worstHealth()
        {
            RgbBackendHealth.State worst = RgbBackendHealth.State.HEALTHY;
            for ( BackendStatus b : backends ) {
                if ( b.health() == RgbBackendHealth.State.DEAD ) return RgbBackendHealth.State.DEAD;
                if ( b.health() == RgbBackendHealth.State.DEGRADED ) worst = RgbBackendHealth.State.DEGRADED;
            }
            return worst;
        }
    }

    public Status status()
    {
        if ( slots.isEmpty() ) {
            return new Status( List.of(), running );
        }
        List< BackendStatus > out = new ArrayList<>( slots.size() );
        for ( BackendSlot slot : slots ) {
            out.add( new BackendStatus( safeName( slot.backend ),
                                        slot.health.state(),
                                        slot.health.consecutiveFailures() ) );
        }
        return new Status( Collections.unmodifiableList( out ), running );
    }

    // =========================================================================
    //  Worker
    // =========================================================================

    private void workerLoop()
    {
        Logger.logDebug( LocalizationManager.get( "log.rgb.controller.workerStarted" ) );
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

            long now = System.currentTimeMillis();
            for ( BackendSlot slot : slots ) {
                if ( !slot.health.canCall( now ) ) continue;
                renderTo( slot, frame );
            }
        }
        Logger.logDebug( LocalizationManager.get( "log.rgb.controller.workerExiting" ) );
    }

    /** Render the frame to a single slot under its own circuit
     *  breaker. Failures are isolated to this slot — the iterator in
     *  {@link #workerLoop} keeps going. */
    private void renderTo( BackendSlot slot, RgbFrame frame )
    {
        try {
            slot.backend.renderFrame( frame );
            slot.health.recordSuccess();
        }
        catch ( Throwable t ) {
            long now = System.currentTimeMillis();
            slot.health.recordFailure( now );
            RgbBackendHealth.State newState = slot.health.state();
            Logger.logWarningSilent( LocalizationManager.format( "log.rgb.controller.renderFrameThrew",
                                             safeName( slot.backend ), newState, slot.health.consecutiveFailures() ), t );
            if ( newState == RgbBackendHealth.State.DEAD ) {
                // Permanent for the rest of the session. Shut the backend down to
                // release its resources, but KEEP the slot (marked DEAD) in the list:
                // the worker skips it via canCall()==false so siblings keep running,
                // while status()/worstHealth() and the start() short-circuit — all of
                // which already test for DEAD — can finally see it. Dropping the slot
                // here is what made those DEAD checks unreachable and let a dead
                // backend silently vanish from the status chip.
                Logger.logError( LocalizationManager.format( "log.rgb.controller.backendMarkedDead", safeName( slot.backend ) ) );
                safelyShutdown( slot.backend );
                if ( slots.stream().allMatch( s -> s.health.state() == RgbBackendHealth.State.DEAD ) ) {
                    Logger.logStd( LocalizationManager.get( "log.rgb.controller.allBackendsFailed" ) );
                }
            }
        }
    }

    /** Wrap a generic shutdown / lifecycle call. Catches Throwable
     *  (not just Exception) so Errors like UnsatisfiedLinkError don't
     *  kill the launcher's overall shutdown sequence. */
    private void safelyInvoke( String op, BackendOp body )
    {
        try {
            body.run();
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.rgb.controller.opThrew", op ), t );
        }
    }

    @FunctionalInterface
    private interface BackendOp
    {
        void run() throws Throwable;
    }

    private void stopWorkerAndShutdownSlots()
    {
        running = false;
        Thread w = worker;
        if ( w != null ) {
            w.interrupt();
            try { w.join( 2_000L ); }
            catch ( InterruptedException ie ) { Thread.currentThread().interrupt(); }
            worker = null;
        }
        for ( BackendSlot slot : slots ) {
            safelyShutdown( slot.backend );
        }
        frameQueue.clear();
    }

    private void safelyShutdown( RgbBackend backend )
    {
        safelyInvoke( "shutdown on " + safeName( backend ), backend::shutdown );
    }

    /** True when {@code requested} matches the last-requested set
     *  ({@link #lastRequestedTypes}) 1:1 by backend class AND every
     *  surviving slot is still HEALTHY/DEGRADED. Order matters within
     *  the request — same set in a different order counts as a change
     *  so the primary-display ordering reflects the new request.
     *
     *  <p>A DEAD slot needs replacing even if the caller asked for the
     *  same set, so its presence in {@link #slots} fails the check.
     *  Backends that the caller asked for but which never made it into
     *  {@link #slots} (e.g. failed isAvailable/start) do NOT fail the
     *  check — that's the point of comparing to the request, not the
     *  surviving slots.</p>
     */
    private boolean sameRequestAndAllSlotsHealthy( List< RgbBackend > requested )
    {
        if ( requested.size() != lastRequestedTypes.size() ) return false;
        for ( int i = 0; i < requested.size(); i++ ) {
            if ( requested.get( i ).getClass() != lastRequestedTypes.get( i ) ) return false;
        }
        for ( BackendSlot slot : slots ) {
            if ( slot.health.state() == RgbBackendHealth.State.DEAD ) return false;
        }
        return true;
    }

    private String describeSlots()
    {
        if ( slots.isEmpty() ) return "<none>";
        List< String > names = new ArrayList<>( slots.size() );
        for ( BackendSlot slot : slots ) names.add( safeName( slot.backend ) );
        return String.join( ", ", names );
    }

    private static String safeName( RgbBackend b )
    {
        if ( b == null ) return "<null>";
        try { return b.name(); }
        catch ( Throwable t ) { return b.getClass().getSimpleName(); }
    }
}
