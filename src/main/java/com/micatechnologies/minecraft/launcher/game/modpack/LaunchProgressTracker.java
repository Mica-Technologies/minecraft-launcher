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

package com.micatechnologies.minecraft.launcher.game.modpack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-step launch progress model that drives the new step-list launch GUI. Each
 * launch stage gets one {@link Step} row with an independent state machine
 * ({@link State}), an optional 0&nbsp;-&nbsp;1 progress reading, and an
 * optional sub-text line for current-activity detail.
 *
 * <p>The old {@link GameModPackProgressProvider} carried a single active
 * "section" at a time. That model worked for the serial pipeline but can't
 * represent four download branches making progress in parallel — which is what
 * the launch path is being restructured into. The tracker here lets every
 * branch update its own row concurrently while the GUI fans listener
 * notifications out to JavaFX nodes.</p>
 *
 * <p>Thread safety: all state-mutating methods synchronize on the tracker
 * instance, so concurrent updates from different launch branches serialize
 * cleanly. Listener notifications happen <em>outside</em> the lock (via a
 * {@link CopyOnWriteArrayList} snapshot) so a listener that re-enters the
 * tracker (e.g. reads another step's state to decide what to render) can't
 * deadlock. Listeners are expected to marshal to the FX thread themselves —
 * the tracker has no GUI dependency.</p>
 *
 * @since 2026.3
 */
public final class LaunchProgressTracker
{
    /**
     * Stable identifier for each launch stage. New stages get added here as
     * the pipeline grows; existing IDs are part of the GUI's row ordering
     * contract so renaming is more annoying than just sticking with what's
     * there.
     */
    public enum StepId
    {
        /** Downloading + verifying the pack's mods, configs, resources, and
         *  cached images — the first launch phase. */
        MODPACK_CONTENT( "Modpack content" ),
        // "Modloader libraries" rather than "Forge libraries" so the
        // label reads correctly for Fabric and NeoForge packs too. The
        // enum names stay FORGE_* for source-stability — only the user-
        // facing displayLabel changed.
        /** Resolving + downloading the modloader's (Forge / NeoForge / Fabric)
         *  libraries. */
        FORGE_LIBS( "Modloader libraries" ),
        /** Downloading + verifying the vanilla Minecraft libraries and asset index. */
        MC_LIBS_ASSETS( "Minecraft libraries & assets" ),
        /** Verifying / installing the Java runtime the pack requires. */
        JRE_INSTALL( "Java runtime" ),
        // "Game patching" matches the existing "Patching game files..."
        // log line + reads correctly for every loader that has a post-
        // install pipeline (modern Forge, NeoForge, future "patching"
        // loaders). Loaders without a post-install pipeline (Fabric)
        // are excluded from the step list entirely via
        // {@link GameModPack#usesPostInstallSteps()}, so no row renders.
        /** Running the loader's post-install patching pipeline (modern Forge /
         *  NeoForge processors). Loaders without a post-install pipeline are
         *  excluded from the step list entirely. */
        FORGE_PROCESSORS( "Game patching" ),
        /** Final jarscanner security pass over the assembled install. */
        SECURITY_SCAN( "Security scan" );

        /** User-facing label shown next to this step's status icon. */
        private final String displayLabel;

        /**
         * @param displayLabel human-readable label rendered for this step
         */
        StepId( String displayLabel ) { this.displayLabel = displayLabel; }

        /** Human-readable label rendered next to the step's status icon. */
        public String displayLabel() { return displayLabel; }
    }

    /**
     * Render state for one row.
     *
     * <ul>
     *   <li>{@link #PENDING} — declared but not yet running. Icon: ○.</li>
     *   <li>{@link #RUNNING} — branch is in flight. Icon: ●. Progress bar
     *       visible.</li>
     *   <li>{@link #DONE} — completed successfully. Icon: ✓.</li>
     *   <li>{@link #FAILED} — branch threw. Icon: ✗. Error message exposed
     *       via {@link Step#errorMessage()}.</li>
     *   <li>{@link #SKIPPED} — not applicable to this pack type (e.g.
     *       Forge stages on a vanilla launch). The tracker omits these
     *       from {@link #steps()} entirely so the GUI can hide the row
     *       rather than render a greyed-out placeholder.</li>
     * </ul>
     */
    public enum State { PENDING, RUNNING, DONE, FAILED, SKIPPED }

    /**
     * Snapshot view of one tracker row. Fields read consistently with
     * what the tracker last persisted, but updates can land between
     * field reads — callers that need a fully-consistent view should
     * grab the {@link Step} reference once and read its fields against
     * the same instance rather than calling {@link #step(StepId)} per
     * field.
     */
    public static final class Step
    {
        private final StepId id;
        private volatile State state = State.PENDING;
        private volatile double progress = 0.0;
        private volatile String subText = "";
        private volatile String errorMessage = null;

        /**
         * @param id the stage this row represents
         */
        Step( StepId id ) { this.id = id; }

        /** @return the stable stage identifier for this row */
        public StepId id() { return id; }

        /** @return the human-readable label for this row, from {@link StepId#displayLabel()} */
        public String displayLabel() { return id.displayLabel(); }

        /** @return the row's current render state */
        public State state() { return state; }

        /** Progress within the row's RUNNING state, in [0, 1]. Meaningless
         *  outside RUNNING; the GUI hides the bar in those states. */
        public double progress() { return progress; }

        /** Optional one-line activity detail under the progress bar (e.g.
         *  "Verified jna-4.4.0.jar"). Empty string when nothing to show. */
        public String subText() { return subText; }

        /** Populated when {@link #state()} is {@link State#FAILED}; null
         *  otherwise. */
        public String errorMessage() { return errorMessage; }
    }

    /**
     * Listener invoked once per state change. The supplied {@link Step}
     * reference is the same instance the tracker holds internally — its
     * fields will reflect future updates by the time the listener inspects
     * them, but the snapshot at notification time matches the cause of
     * this callback.
     */
    @FunctionalInterface
    public interface Listener
    {
        /**
         * Invoked once per state, progress, or sub-text change on a row.
         *
         * @param step the row that changed; the same internal instance the
         *             tracker holds, so its fields stay live after the call
         */
        void onStepChanged( Step step );
    }

    private final List< Step > orderedSteps;
    private final EnumMap< StepId, Step > byId;
    private final List< Listener > listeners = new CopyOnWriteArrayList<>();

    /**
     * @param orderedSteps the rows to render, in display order; wrapped
     *                     unmodifiable and indexed by {@link StepId}
     */
    private LaunchProgressTracker( List< Step > orderedSteps )
    {
        this.orderedSteps = Collections.unmodifiableList( orderedSteps );
        this.byId = new EnumMap<>( StepId.class );
        for ( Step s : orderedSteps ) {
            byId.put( s.id, s );
        }
    }

    /**
     * Builds a tracker rendering exactly the given step IDs, in the supplied
     * order. Steps that aren't included don't appear in {@link #steps()} at
     * all — the GUI renders nothing for them rather than a SKIPPED-style
     * placeholder, which matches the "hide non-applicable rows on vanilla"
     * UX decision.
     *
     * @param ids the stages to render, in display order
     * @return a new tracker holding one row per supplied ID
     */
    public static LaunchProgressTracker forSteps( StepId... ids )
    {
        List< Step > built = new ArrayList<>( ids.length );
        for ( StepId id : ids ) {
            built.add( new Step( id ) );
        }
        return new LaunchProgressTracker( built );
    }

    /** @return immutable ordered view of every active row */
    public List< Step > steps() { return orderedSteps; }

    /** Snapshot of every row currently in {@link State#RUNNING}. Used by the
     *  launch flow's NetworkUtilities retry listener so cross-cutting events
     *  (a retry, an HTTP 429, a hash-mismatch re-download) can update every
     *  in-flight row's sub-text without the caller having to identify which
     *  branch the affected download belongs to.
     *
     *  @return a fresh list of the rows currently in {@link State#RUNNING} */
    public List< Step > runningSteps()
    {
        List< Step > out = new ArrayList<>();
        for ( Step s : orderedSteps ) {
            if ( s.state == State.RUNNING ) out.add( s );
        }
        return out;
    }

    /** Returns the row for the given ID, or {@code null} if this tracker
     *  doesn't include it.
     *
     *  @param id the stage to look up
     *  @return the matching row, or {@code null} when not part of this tracker */
    public Step step( StepId id ) { return byId.get( id ); }

    /** Subscribe to state-change notifications. The listener is invoked
     *  on whatever thread mutated the row; UI listeners should marshal
     *  to the FX thread themselves.
     *
     *  @param l the listener to add; {@code null} is ignored */
    public void addListener( Listener l )
    {
        if ( l != null ) listeners.add( l );
    }

    /** Unsubscribes a previously-added listener.
     *
     *  @param l the listener to remove; {@code null} (and listeners never
     *           added) are ignored */
    public void removeListener( Listener l )
    {
        if ( l != null ) listeners.remove( l );
    }

    // ---- state mutation ----

    /** Marks the given step {@link State#RUNNING} and clears any prior error,
     *  then notifies listeners.
     *
     *  @param id the step to transition
     *  @throws IllegalArgumentException if the step isn't part of this tracker */
    public synchronized void markRunning( StepId id )
    {
        Step s = require( id );
        s.state = State.RUNNING;
        s.errorMessage = null;
        notifyChanged( s );
    }

    /** Marks the given step {@link State#DONE}, snaps its progress to 1.0, and
     *  notifies listeners.
     *
     *  @param id the step to transition
     *  @throws IllegalArgumentException if the step isn't part of this tracker */
    public synchronized void markDone( StepId id )
    {
        Step s = require( id );
        s.state = State.DONE;
        s.progress = 1.0;
        notifyChanged( s );
    }

    /** Marks the given step {@link State#FAILED}, records the error message for
     *  {@link Step#errorMessage()}, and notifies listeners.
     *
     *  @param id           the step to transition
     *  @param errorMessage the failure detail to surface on the row
     *  @throws IllegalArgumentException if the step isn't part of this tracker */
    public synchronized void markFailed( StepId id, String errorMessage )
    {
        Step s = require( id );
        s.state = State.FAILED;
        s.errorMessage = errorMessage;
        notifyChanged( s );
    }

    /** Marks the step skipped. Useful when the caller wants the row to remain
     *  in the listing for a moment with a "not applicable" indicator — most
     *  often, though, the GUI elects to omit the step from the tracker
     *  entirely via {@link #forSteps(StepId...)}, which renders no row at all.
     *
     *  @param id the step to mark skipped
     *  @throws IllegalArgumentException if the step isn't part of this tracker */
    public synchronized void markSkipped( StepId id )
    {
        Step s = require( id );
        s.state = State.SKIPPED;
        notifyChanged( s );
    }

    /** Updates the RUNNING-state progress reading. Clamped to [0, 1].
     *
     *  @param id       the step to update
     *  @param progress the new progress fraction; values outside [0, 1] are clamped
     *  @throws IllegalArgumentException if the step isn't part of this tracker */
    public synchronized void setProgress( StepId id, double progress )
    {
        Step s = require( id );
        s.progress = Math.max( 0.0, Math.min( 1.0, progress ) );
        notifyChanged( s );
    }

    /** Updates the row's sub-text without changing its state or progress.
     *  Use for current-activity detail (filename being processed, retry
     *  attempt count, etc.). Empty string clears the sub-text.
     *
     *  @param id      the step to update
     *  @param subText the activity detail; {@code null} is coerced to an empty string
     *  @throws IllegalArgumentException if the step isn't part of this tracker */
    public synchronized void setSubText( StepId id, String subText )
    {
        Step s = require( id );
        s.subText = subText == null ? "" : subText;
        notifyChanged( s );
    }

    /** Convenience for the common pattern "increment a known fraction +
     *  set the sub-text in one call". Equivalent to two separate calls
     *  but produces a single listener notification instead of two.
     *
     *  @param id       the step to update
     *  @param progress the new progress fraction; values outside [0, 1] are clamped
     *  @param subText  the activity detail; {@code null} is coerced to an empty string
     *  @throws IllegalArgumentException if the step isn't part of this tracker */
    public synchronized void submitProgress( StepId id, double progress, String subText )
    {
        Step s = require( id );
        s.progress = Math.max( 0.0, Math.min( 1.0, progress ) );
        s.subText = subText == null ? "" : subText;
        notifyChanged( s );
    }

    /** Looks up a row, throwing if this tracker doesn't include it.
     *
     *  @param id the step to resolve
     *  @return the matching row, never {@code null}
     *  @throws IllegalArgumentException if the step isn't part of this tracker */
    private Step require( StepId id )
    {
        Step s = byId.get( id );
        if ( s == null ) {
            throw new IllegalArgumentException( "Step " + id + " is not part of this tracker" );
        }
        return s;
    }

    /** Fans the change out to every registered listener, outside the tracker
     *  lock semantics of {@link CopyOnWriteArrayList}, swallowing any listener
     *  fault so one bad listener can't starve the rest.
     *
     *  @param s the row that changed */
    private void notifyChanged( Step s )
    {
        // CopyOnWriteArrayList iteration is safe even if a listener removes
        // itself mid-fire; the snapshot was taken at the start of iteration.
        for ( Listener l : listeners ) {
            try {
                l.onStepChanged( s );
            }
            catch ( Throwable ignored ) {
                // Listener faults shouldn't poison the tracker — every
                // remaining listener still gets its notification.
            }
        }
    }
}
