/*
 * Copyright (c) 2021 Mica Technologies
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

import com.micatechnologies.minecraft.launcher.utilities.DownloadTracker;

/**
 * Abstract progress sink for long-running mod pack operations (download, verification, Forge processing, launch).
 * <p>
 * The launch pipeline drives this provider as it works through a sequence of weighted <em>sections</em>. Each section is
 * opened with {@link #startProgressSection(String, double)} (declaring its share of the overall 0&ndash;100 scale),
 * advanced with {@link #submitProgress(String, double)}, and closed with {@link #endProgressSection(String)}. The
 * provider tracks the cumulative completed percentage plus the in-flight section's progress, derives an overall
 * percentage in {@link #getActualProgress()}, and forwards it to the subclass via the abstract
 * {@link #updateProgressHandler(double, String, String, String)} hook. Concrete subclasses bind that hook to a GUI
 * progress bar (or other UI), so this base class stays free of any presentation concern.
 * <p>
 * In addition to the percentage-based section model, the provider exposes step-aware lifecycle hooks
 * ({@link #enterStep}, {@link #completeStep}, {@link #failStep}, {@link #onError}) used by the multi-row launch progress
 * UI. These default to no-ops so legacy single-bar progress GUIs continue to work unchanged.
 *
 * @author Mica Technologies
 */
public abstract class GameModPackProgressProvider
{

    /**
     * The full-scale value of the progress percentage range. All section sizes and progress values are expressed
     * relative to this base (i.e. {@code 100.0} represents a fully complete task).
     */
    public static final double PROGRESS_PERCENT_BASE = 100.0;

    /**
     * Cumulative percentage (0&ndash;{@link #PROGRESS_PERCENT_BASE}) contributed by sections that have already
     * completed. The in-flight section's partial contribution is added on top in {@link #getActualProgress()}.
     */
    private              double currPercent           = 0.0;

    /**
     * Heading text for the section currently in progress (e.g. "Downloading mods..."). Forwarded to the subclass as the
     * {@code sectionTitle} argument of {@link #updateProgressHandler(double, String, String, String)}.
     */
    private              String currSectionTitle      = "";

    /**
     * File-level detail text for the most recent progress update (e.g. "Verified library jna-4.4.0.jar"). Forwarded to
     * the subclass as the {@code detailText} argument of {@link #updateProgressHandler(double, String, String, String)}.
     */
    private              String currDetailText        = "";

    /**
     * The share of the overall 0&ndash;{@link #PROGRESS_PERCENT_BASE} scale allotted to the current section, as
     * declared by {@link #startProgressSection(String, double)}. Used to weight {@link #currSectionProgress} when
     * computing the overall percentage.
     */
    private              double currSectionSize       = PROGRESS_PERCENT_BASE;

    /**
     * Progress within the current section, on a 0&ndash;{@link #PROGRESS_PERCENT_BASE} scale local to the section
     * (independent of the section's weight). Scaled by {@link #currSectionSize} when rolled into the overall total.
     */
    private              double currSectionProgress   = 0.0;

    /**
     * Shared download tracker for byte-level progress across all download sections.
     */
    private final DownloadTracker downloadTracker = new DownloadTracker();

    /**
     * Returns the shared download tracker for use in file downloads.
     *
     * @return the download tracker instance
     */
    public DownloadTracker getDownloadTracker()
    {
        return downloadTracker;
    }

    /**
     * Computes the current overall progress and fans it out to the subclass's
     * {@link #updateProgressHandler(double, String, String, String)} along with the current section title, detail text,
     * and the download tracker's formatted speed/ETA status. This is the single funnel through which every progress
     * notification reaches the UI.
     */
    void triggerUpdateHandler() {
        updateProgressHandler( getActualProgress(), currSectionTitle, currDetailText,
                               downloadTracker.getFormattedStatus() );
    }

    /**
     * Computes the overall progress percentage by adding the in-flight section's weighted partial progress to the
     * cumulative percentage of already-completed sections.
     *
     * @return the overall progress on a 0&ndash;{@link #PROGRESS_PERCENT_BASE} scale
     */
    private double getActualProgress() {
        return currPercent + ( currSectionProgress * ( currSectionSize / PROGRESS_PERCENT_BASE ) );
    }

    /**
     * Advances the current section's progress by the given amount and updates the detail text, then (subject to the
     * ~20&nbsp;fps throttle described below) fires a progress notification.
     * <p>
     * The added {@code sectionProgress} is clamped so the section never reports more than
     * {@link #PROGRESS_PERCENT_BASE}. High-frequency per-file calls are coalesced to at most one handler fire every
     * {@code SUBMIT_PROGRESS_THROTTLE_MS}, except that a call which completes the section always fires immediately.
     *
     * @param detailText      the file-level detail text to display for this update
     * @param sectionProgress the amount of section-local progress to add (on a 0&ndash;{@link #PROGRESS_PERCENT_BASE}
     *                        scale)
     */
    public synchronized void submitProgress( String detailText, double sectionProgress ) {
        this.currDetailText = detailText;

        if ( sectionProgress + currSectionProgress > PROGRESS_PERCENT_BASE ) {
            currSectionProgress = PROGRESS_PERCENT_BASE;
        }
        else {
            currSectionProgress += sectionProgress;
        }

        // Coalesce per-file progress updates so the FX thread + the persistent
        // log don't pay the cost of 600 per-asset notifications on a fresh MC
        // version install. Each updateProgressHandler call fans out to four
        // JavaFX label updates (section / detail / speed / progress) and a
        // Logger.logStd line; firing once per file produced visible stutter
        // on the launch progress screen and a corresponding ~600-line log
        // burst per launch. 50 ms is enough that human-eye animation still
        // looks smooth (~20 fps) but pushes the per-asset cost into the noise.
        // The throttle only applies to submitProgress; explicit section
        // transitions (start/end/section change) and signalComplete still
        // fire immediately so structural events are never coalesced away.
        long now = System.currentTimeMillis();
        if ( now - lastSubmitFireMs >= SUBMIT_PROGRESS_THROTTLE_MS
                || currSectionProgress >= PROGRESS_PERCENT_BASE ) {
            lastSubmitFireMs = now;
            triggerUpdateHandler();
        }
    }

    /** Wall-clock of the last submitProgress-triggered handler fire. Used to
     *  throttle high-frequency per-file progress notifications down to ~20 fps. */
    private volatile long lastSubmitFireMs = 0;
    private static final long SUBMIT_PROGRESS_THROTTLE_MS = 50;

    /**
     * Updates the current detail text without changing any progress value, then fires a progress notification
     * immediately (not throttled).
     *
     * @param detailText the new file-level detail text to display
     */
    void setCurrText( String detailText ) {
        this.currDetailText = detailText;
        triggerUpdateHandler();
    }

    /**
     * Closes the current section: folds its full declared size into the cumulative completed percentage, resets the
     * in-section progress and size, sets the given closing detail text, and fires a progress notification immediately
     * (not throttled).
     *
     * @param text the detail text to display as the section closes
     */
    void endProgressSection( String text ) {
        this.currPercent += currSectionSize;
        this.currSectionProgress = 0;
        this.currDetailText = text;
        this.currSectionSize = 0;

        triggerUpdateHandler();
    }

    /**
     * Opens a new weighted progress section. Any in-flight section progress is first folded into the cumulative
     * completed percentage, then the new section's title and size are stored, the detail text and in-section progress
     * are reset, and a progress notification is fired immediately (not throttled).
     *
     * @param sectionTitle the heading to display for the new section (e.g. "Downloading mods...")
     * @param size         the new section's share of the overall 0&ndash;{@link #PROGRESS_PERCENT_BASE} scale
     */
    void startProgressSection( String sectionTitle, double size ) {
        // End open progress session (if exists)
        this.currPercent += this.currSectionProgress;

        // Store progress section information
        this.currSectionTitle = sectionTitle;
        this.currDetailText = "";
        this.currSectionSize = size;
        this.currSectionProgress = 0;

        triggerUpdateHandler();
    }

    /**
     * Forces overall progress to {@link #PROGRESS_PERCENT_BASE} (100%), clears any in-flight section state, sets the
     * given closing section title, and fires a final progress notification immediately. Call this when the overall task
     * has finished.
     *
     * @param sectionTitle the heading to display alongside the completed state (e.g. "Done")
     */
    public void signalComplete( String sectionTitle ) {
        this.currPercent = PROGRESS_PERCENT_BASE;
        this.currSectionProgress = 0;
        this.currSectionSize = 0;
        this.currSectionTitle = sectionTitle;
        this.currDetailText = "";
        triggerUpdateHandler();
    }

    /**
     * Called when progress updates.
     *
     * @param percent        overall progress percentage (0-100)
     * @param sectionTitle   the current section heading (e.g. "Downloading mods...")
     * @param detailText     the current detail text (e.g. "Verified library jna-4.4.0.jar")
     * @param downloadStatus formatted download speed/ETA string (e.g. "2.4 MB/s -- 3:42 remaining"), or empty
     */
    abstract public void updateProgressHandler( double percent, String sectionTitle, String detailText,
                                                 String downloadStatus );

    // =========================================================================
    //  Step-aware lifecycle hooks
    // =========================================================================
    //
    // The launch pipeline calls these to signal "this step of the launch is
    // about to start / has finished / has thrown." The base implementation
    // is a no-op so legacy single-bar progress GUIs that don't track per-step
    // state keep working unchanged. {@link LaunchTrackerProgressBridge}
    // overrides them to drive its associated LaunchProgressTracker, which in
    // turn drives the multi-row launch progress GUI.

    /**
     * Called once when the named launch step begins. The base implementation is a no-op.
     *
     * @param id identifier of the launch step that is starting
     */
    public void enterStep( LaunchProgressTracker.StepId id ) { /* no-op default */ }

    /**
     * Called once when the named launch step completes successfully. The base implementation is a no-op.
     *
     * @param id identifier of the launch step that completed
     */
    public void completeStep( LaunchProgressTracker.StepId id ) { /* no-op default */ }

    /**
     * Called once when the named launch step throws. The base implementation is a no-op.
     *
     * @param id           identifier of the launch step that failed
     * @param errorMessage human-readable failure message; surfaced to the user in the failed-row sub-text
     */
    public void failStep( LaunchProgressTracker.StepId id, String errorMessage ) { /* no-op default */ }

    /**
     * Called when work tracked by this provider hits a recoverable error
     * outside the formal step lifecycle — e.g. a per-file download retry
     * that gave up, a non-fatal manifest fetch that fell back to cached
     * data, or a mod-scan that couldn't read one entry of many. Lets
     * provider implementations surface a user-facing notification rather
     * than relying on the silent log line that's all the launcher had
     * before this hook existed.
     *
     * <p>The base implementation is a no-op so legacy progress GUIs that
     * don't care keep working unchanged. {@link LaunchTrackerProgressBridge}
     * overrides this to emit a {@code NotificationManager.warn} toast so
     * mid-launch hiccups aren't silently swallowed when the user has
     * minimised the launcher to the system tray during install.</p>
     *
     * @param message short human-readable summary of the failure (becomes the
     *                toast body / failed-row sub-text). Should not include
     *                stack-trace text — that goes to the log.
     * @param cause   the originating throwable, or {@code null} when the
     *                error is synthetic (deliberate abort, validation
     *                failure with no exception).
     */
    public void onError( String message, Throwable cause ) { /* no-op default */ }
}
