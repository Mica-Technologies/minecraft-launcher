package com.micatechnologies.minecraft.forgemodpacklib;

public abstract class MCForgeModpackProgressProvider {

    private static final double PROGRESS_PERCENT_BASE = 100.0;

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

    void submitProgress( String text, double sectionProgress ) {
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
