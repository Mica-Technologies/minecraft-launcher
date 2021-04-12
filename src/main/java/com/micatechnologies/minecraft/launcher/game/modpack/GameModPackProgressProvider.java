/*
 * Copyright (c) 2020 Mica Technologies
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

    private              String currSectionText       = "<null>";

    private              double currSectionSize       = PROGRESS_PERCENT_BASE;

    private              double currSectionProgress   = 0.0;

    void triggerUpdateHandler() {
        updateProgressHandler( getActualProgress(), currSectionText );
    }

    private double getActualProgress() {
        return currPercent + ( currSectionProgress * ( currSectionSize / PROGRESS_PERCENT_BASE ) );
    }

    public void submitProgress( String text, double sectionProgress ) {
        // Store updated text
        this.currSectionText = text;

        // Add progress to section progress
        if ( sectionProgress + currSectionProgress > PROGRESS_PERCENT_BASE ) {
            currSectionProgress = PROGRESS_PERCENT_BASE;
        }
        else {
            currSectionProgress += sectionProgress;
        }

        // Trigger update progress handler
        triggerUpdateHandler();
    }

    void setCurrText( String text ) {
        this.currSectionText = text;

        // Trigger update progress handler
        triggerUpdateHandler();
    }

    void endProgressSection( String text ) {
        // Add final percentage to main percentage and reset section info
        this.currPercent += currSectionSize;
        this.currSectionProgress = 0;
        this.currSectionText = text;
        this.currSectionSize = 0;

        // Trigger update progress handler
        triggerUpdateHandler();
    }

    void startProgressSection( String text, double size ) {
        // End open progress session (if exists)
        this.currPercent += this.currSectionProgress;

        // Store progress section information
        this.currSectionText = text;
        this.currSectionSize = size;
        this.currSectionProgress = 0;

        // Trigger update progress handler
        triggerUpdateHandler();
    }

    abstract public void updateProgressHandler( double percent, String text );
}
