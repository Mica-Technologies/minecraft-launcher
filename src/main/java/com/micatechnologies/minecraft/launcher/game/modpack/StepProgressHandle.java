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

import com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker.StepId;

/**
 * Progress provider locked to a single {@link StepId} in a tracker. Each
 * parallel pre-launch branch gets its own handle so concurrent
 * {@code submitProgress} / {@code setCurrText} calls from different branches
 * land on the correct row without an "active step" race.
 *
 * <p>Compared to {@link LaunchTrackerProgressBridge} (which carries a single
 * mutable {@code activeStep} field for the serial pipeline), a handle is
 * immutable in its step binding — its constructor sets the step, and every
 * progress call routes to that one. That's what makes parallel-safe usage
 * possible: three handles for three branches, each updating its own row in
 * the same shared tracker.</p>
 *
 * <p>Sub-call ergonomics: every sub-call inside the launch pipeline
 * ({@code fetchLatestMods}, {@code buildForgeClasspath},
 * {@code buildMinecraftClasspath}, {@code runForgeProcessors},
 * {@code scanModPackRootFolder}) already takes — or reads from
 * {@code pack.getProgressProvider()} — a {@link GameModPackProgressProvider}.
 * Handles are subclasses of that, so sub-call signatures don't change.</p>
 *
 * @since 2026.3
 */
public final class StepProgressHandle extends GameModPackProgressProvider
{
    private final LaunchProgressTracker tracker;
    private final StepId stepId;

    /** Accumulator for the current sub-section's progress within this step,
     *  in 0..100. Same model as the bridge — multiple sub-sections within a
     *  step each restart from 0 (the visual bar resets) and accumulate
     *  toward 100 as individual files are verified. */
    private double currentSectionProgress = 0.0;

    /** Wall-clock of the last tracker.submitProgress fire. Used to throttle
     *  per-file updates down to ~20 fps so the bar render can actually catch
     *  up — without this, fetchLatest* fans out so fast across parallelStream
     *  workers that the bar gets new setProgress targets faster than it can
     *  animate between them, which on screen reads as a stuttering empty bar. */
    private volatile long lastFireMs = 0;
    private static final long FIRE_THROTTLE_MS = 50;

    public StepProgressHandle( LaunchProgressTracker tracker, StepId stepId )
    {
        this.tracker = tracker;
        this.stepId = stepId;
    }

    /** Underlying step this handle is bound to. */
    public StepId stepId() { return stepId; }

    /** Marks the handle's step running on the underlying tracker. Call at the
     *  start of the branch that owns this handle. */
    public synchronized void markRunning()
    {
        currentSectionProgress = 0.0;
        tracker.markRunning( stepId );
    }

    /** Marks the handle's step done. Call when the branch finishes
     *  successfully. */
    public synchronized void markDone()
    {
        tracker.markDone( stepId );
    }

    /** Marks the handle's step failed with the given error message. Call from
     *  the catch path of the branch that owns this handle. */
    public synchronized void markFailed( String errorMessage )
    {
        tracker.markFailed( stepId, errorMessage );
    }

    /** Marks the handle's step skipped with the given reason text. Call when
     *  policy short-circuits the step's work (e.g. scan-frequency policy
     *  saying "scan not due this launch"). The reason becomes the sub-text
     *  on the row so the user can see why nothing ran. */
    public synchronized void markSkipped( String reason )
    {
        if ( reason != null && !reason.isEmpty() ) {
            tracker.setSubText( stepId, reason );
        }
        tracker.markSkipped( stepId );
    }

    // =========================================================================
    //  GameModPackProgressProvider overrides — all routed to this step.
    // =========================================================================

    @Override
    public synchronized void submitProgress( String detailText, double sectionProgress )
    {
        currentSectionProgress = Math.min( 100.0, currentSectionProgress + sectionProgress );
        // Coalesce per-file updates so the bar render keeps up. Bypass the throttle
        // when we've hit 100% so the final "section complete" frame always lands —
        // the very next event is usually a markDone or a new sub-section's
        // startProgressSection, and we want the bar visually filled by then.
        long now = System.currentTimeMillis();
        if ( now - lastFireMs >= FIRE_THROTTLE_MS || currentSectionProgress >= 100.0 ) {
            lastFireMs = now;
            tracker.submitProgress( stepId, currentSectionProgress / 100.0, detailText );
        }
    }

    @Override
    synchronized void setCurrText( String text )
    {
        tracker.setSubText( stepId, text );
    }

    @Override
    synchronized void startProgressSection( String title, double size )
    {
        currentSectionProgress = 0.0;
        tracker.setProgress( stepId, 0.0 );
        if ( title != null && !title.isEmpty() ) {
            tracker.setSubText( stepId, title );
        }
    }

    @Override
    synchronized void endProgressSection( String text )
    {
        if ( text != null && !text.isEmpty() ) {
            tracker.setSubText( stepId, text );
        }
    }

    @Override
    public void updateProgressHandler( double percent, String sectionTitle,
                                        String detailText, String downloadStatus )
    {
        // Unused: the handle pushes to the tracker directly from each public
        // entry point above. Required because the base class is abstract.
    }
}
