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

public abstract class GameModPackProgressProvider
{

    public static final double PROGRESS_PERCENT_BASE = 100.0;

    private              double currPercent           = 0.0;

    private              String currSectionTitle      = "";

    private              String currDetailText        = "";

    private              double currSectionSize       = PROGRESS_PERCENT_BASE;

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

    void triggerUpdateHandler() {
        updateProgressHandler( getActualProgress(), currSectionTitle, currDetailText,
                               downloadTracker.getFormattedStatus() );
    }

    private double getActualProgress() {
        return currPercent + ( currSectionProgress * ( currSectionSize / PROGRESS_PERCENT_BASE ) );
    }

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

    void setCurrText( String detailText ) {
        this.currDetailText = detailText;
        triggerUpdateHandler();
    }

    void endProgressSection( String text ) {
        this.currPercent += currSectionSize;
        this.currSectionProgress = 0;
        this.currDetailText = text;
        this.currSectionSize = 0;

        triggerUpdateHandler();
    }

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
     * Called when progress updates. Implementors receive the section title (heading) and detail text (file-level info)
     * separately so they can be displayed in different UI positions.
     *
     * @param percent      overall progress percentage (0-100)
     * @param sectionTitle the current section heading (e.g. "Downloading mods...")
     * @param detailText   the current detail text (e.g. "Verified library jna-4.4.0.jar")
     */
    /**
     * Forces progress to 100% and triggers the handler. Used when the overall task is complete.
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

    /** Called once when the named launch step begins. */
    public void enterStep( LaunchProgressTracker.StepId id ) { /* no-op default */ }

    /** Called once when the named launch step completes successfully. */
    public void completeStep( LaunchProgressTracker.StepId id ) { /* no-op default */ }

    /** Called once when the named launch step throws. {@code errorMessage} is
     *  surfaced to the user in the failed-row sub-text. */
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
