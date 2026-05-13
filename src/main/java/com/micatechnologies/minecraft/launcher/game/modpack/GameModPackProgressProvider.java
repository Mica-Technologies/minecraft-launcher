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
}
