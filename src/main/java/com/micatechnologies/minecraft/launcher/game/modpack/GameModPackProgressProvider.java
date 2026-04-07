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

public abstract class GameModPackProgressProvider
{

    public static final double PROGRESS_PERCENT_BASE = 100.0;

    private              double currPercent           = 0.0;

    private              String currSectionTitle      = "";

    private              String currDetailText        = "";

    private              double currSectionSize       = PROGRESS_PERCENT_BASE;

    private              double currSectionProgress   = 0.0;

    void triggerUpdateHandler() {
        updateProgressHandler( getActualProgress(), currSectionTitle, currDetailText );
    }

    private double getActualProgress() {
        return currPercent + ( currSectionProgress * ( currSectionSize / PROGRESS_PERCENT_BASE ) );
    }

    public void submitProgress( String detailText, double sectionProgress ) {
        this.currDetailText = detailText;

        if ( sectionProgress + currSectionProgress > PROGRESS_PERCENT_BASE ) {
            currSectionProgress = PROGRESS_PERCENT_BASE;
        }
        else {
            currSectionProgress += sectionProgress;
        }

        triggerUpdateHandler();
    }

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

    abstract public void updateProgressHandler( double percent, String sectionTitle, String detailText );
}
