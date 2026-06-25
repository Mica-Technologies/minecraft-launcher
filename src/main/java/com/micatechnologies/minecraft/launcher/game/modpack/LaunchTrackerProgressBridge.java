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
 * Adapter wedged between the legacy {@link GameModPackProgressProvider} API
 * (which sub-call sites still use — {@code progressProvider.submitProgress},
 * {@code setCurrText}, {@code startProgressSection}, {@code endProgressSection})
 * and the new multi-step {@link LaunchProgressTracker} that the launch progress
 * GUI subscribes to.
 *
 * <p>Wiring: {@link com.micatechnologies.minecraft.launcher.LauncherCore#play}
 * constructs the tracker + bridge for each launch attempt and hands the bridge
 * off to {@code pack.setProgressProvider(...)} so {@link GameModPackLauncher}
 * and every {@code fetchLatestX} / {@code buildXClasspath} sub-call sees what
 * looks like the same old progress provider — but their calls translate to
 * row updates on the active step rather than into a single-section progress
 * accumulator.</p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link GameModPackLauncher} calls {@link #enterStep(StepId)} just
 *       before each pre-launch phase. The bridge marks the step running and
 *       remembers it as the active step.</li>
 *   <li>Sub-calls inside that phase reach for the existing
 *       {@link GameModPackProgressProvider} surface (this bridge). Their
 *       {@code submitProgress} / {@code setCurrText} calls translate to
 *       {@code tracker.submitProgress / setSubText} against the active step.</li>
 *   <li>{@link GameModPackLauncher} calls {@link #completeStep(StepId)} when
 *       the phase returns successfully, or {@link #failStep(StepId, String)}
 *       on exception.</li>
 * </ol>
 *
 * <h3>Sub-section handling within one step</h3>
 *
 * <p>Phase 1 of the legacy launch ({@link StepId#MODPACK_CONTENT}) contains
 * multiple {@code startProgressSection / endProgressSection} pairs internally
 * (one each for mods, then configs/resources/shaderpacks/initial files +
 * image cache). The bridge treats those as <em>sub-sections</em> within a
 * single tracker step:</p>
 * <ul>
 *   <li>{@link #startProgressSection(String, double)} resets the row's
 *       progress to 0 and surfaces the section title as the row sub-text.
 *       The user sees the bar restart with a new label, communicating "we
 *       just moved to the next sub-task of this step."</li>
 *   <li>{@link #submitProgress(String, double)} accumulates within the
 *       current sub-section the same way the legacy provider did, then
 *       pushes the running fraction to the tracker.</li>
 *   <li>{@link #endProgressSection(String)} sets the sub-text to the
 *       completion message but leaves the step's state at RUNNING — the
 *       parent step is closed by {@link #completeStep(StepId)}, not by a
 *       sub-section's end.</li>
 * </ul>
 *
 * <p>{@link #updateProgressHandler} is a no-op because the bridge already
 * pushes to the tracker directly from each entry point; there's no separate
 * UI-callback indirection.</p>
 *
 * <p>Threading: all state-changing methods synchronize on the bridge so
 * concurrent updates from parallel branches in step 3+ of the 3.2 refactor
 * serialize cleanly. The active-step pointer is volatile so the locked
 * methods see the freshest value without re-reading through a field.</p>
 *
 * @since 2026.3
 */
public final class LaunchTrackerProgressBridge extends GameModPackProgressProvider
{
    private final LaunchProgressTracker tracker;

    /** Step that subsequent setCurrText / submitProgress calls update. Set by
     *  {@link #enterStep(StepId)}, cleared by {@link #completeStep(StepId)} /
     *  {@link #failStep(StepId, String)}. */
    private volatile StepId activeStep;

    /** Accumulator for the current sub-section's progress, in 0..100. Reset on
     *  every {@link #startProgressSection(String, double)} call. */
    private double currentSectionProgress = 0.0;

    /**
     * @param tracker the multi-step tracker this bridge translates legacy
     *                progress-provider calls into
     */
    public LaunchTrackerProgressBridge( LaunchProgressTracker tracker )
    {
        this.tracker = tracker;
    }

    /** Exposes the tracker so the GUI / launch core can attach listeners or
     *  query state.
     *
     *  @return the backing tracker */
    public LaunchProgressTracker tracker() { return tracker; }

    /**
     * Builds a {@link StepProgressHandle} bound to the given step. Used by the
     * parallel pre-launch orchestrator to give each branch its own progress
     * provider so concurrent submitProgress / setCurrText calls don't race
     * over the single bridge-level activeStep.
     *
     * @param stepId the step the returned handle should drive
     * @return a per-step progress handle bound to {@code stepId}
     */
    public StepProgressHandle handleFor( StepId stepId )
    {
        return new StepProgressHandle( tracker, stepId );
    }

    // =========================================================================
    //  Step-aware lifecycle (overrides of the base class no-op defaults)
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Records {@code id} as the active step, resets the sub-section
     * accumulator, and marks the step running on the tracker.</p>
     *
     * @param id the step now entering
     */
    @Override
    public synchronized void enterStep( StepId id )
    {
        this.activeStep = id;
        this.currentSectionProgress = 0.0;
        tracker.markRunning( id );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Clears the active-step pointer (when it matches) and marks the step
     * done on the tracker.</p>
     *
     * @param id the step that finished successfully
     */
    @Override
    public synchronized void completeStep( StepId id )
    {
        if ( activeStep == id ) {
            activeStep = null;
            currentSectionProgress = 0.0;
        }
        tracker.markDone( id );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Clears the active-step pointer (when it matches) and marks the step
     * failed on the tracker with the supplied message.</p>
     *
     * @param id           the step that failed
     * @param errorMessage the failure detail to surface on the row
     */
    @Override
    public synchronized void failStep( StepId id, String errorMessage )
    {
        if ( activeStep == id ) {
            activeStep = null;
            currentSectionProgress = 0.0;
        }
        tracker.markFailed( id, errorMessage );
    }

    // =========================================================================
    //  Legacy GameModPackProgressProvider surface
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Accumulates {@code sectionProgress} into the current sub-section
     * (capped at 100) and pushes the running fraction plus detail text to the
     * active step's tracker row. A no-op when no step is active.</p>
     *
     * @param detailText      current-activity detail for the row sub-text
     * @param sectionProgress increment to add to the current sub-section, in 0..100
     */
    @Override
    public synchronized void submitProgress( String detailText, double sectionProgress )
    {
        StepId step = activeStep;
        if ( step == null ) {
            return;
        }
        currentSectionProgress = Math.min( 100.0, currentSectionProgress + sectionProgress );
        tracker.submitProgress( step, currentSectionProgress / 100.0, detailText );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sets the active step's sub-text. A no-op when no step is active.</p>
     *
     * @param text the activity detail to display
     */
    @Override
    synchronized void setCurrText( String text )
    {
        StepId step = activeStep;
        if ( step != null ) {
            tracker.setSubText( step, text );
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Begins a sub-section within the active step: resets the in-row bar to
     * zero and surfaces {@code title} as the row sub-text. The parent step
     * stays {@code RUNNING}.</p>
     *
     * @param title the sub-section label shown as the row sub-text
     * @param size  the legacy sub-section size hint (unused by the tracker bridge)
     */
    @Override
    synchronized void startProgressSection( String title, double size )
    {
        // Treat as a sub-section within the active step. Reset the in-row bar
        // and surface the section title as the row sub-text. The parent step
        // stays RUNNING — only the row's display refreshes for the new sub-task.
        currentSectionProgress = 0.0;
        StepId step = activeStep;
        if ( step != null ) {
            tracker.setProgress( step, 0.0 );
            if ( title != null && !title.isEmpty() ) {
                tracker.setSubText( step, title );
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Ends a sub-section by setting the active step's sub-text to the
     * completion message. The parent step is closed by {@link #completeStep},
     * not here, so sequential sub-sections within one step keep the row
     * {@code RUNNING}.</p>
     *
     * @param text the sub-section completion message
     */
    @Override
    synchronized void endProgressSection( String text )
    {
        // Sub-section complete. The parent step is closed by completeStep, not
        // by this — multiple sub-sections may run sequentially within one step.
        StepId step = activeStep;
        if ( step != null && text != null && !text.isEmpty() ) {
            tracker.setSubText( step, text );
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>No-op: this bridge pushes straight to the tracker from each entry
     * point above, so there is no separate render callback to honour. Present
     * only to satisfy the abstract base class.</p>
     *
     * @param percent        ignored
     * @param sectionTitle   ignored
     * @param detailText     ignored
     * @param downloadStatus ignored
     */
    @Override
    public void updateProgressHandler( double percent, String sectionTitle,
                                        String detailText, String downloadStatus )
    {
        // Unused: the bridge pushes to the tracker directly from each public
        // entry point above, so there's no separate "render this" callback.
        // Required because the base class is abstract.
    }
}
