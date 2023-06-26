package me.cortex.jarscanner;

import java.util.List;

/**
 * Class for storing Nekodetector scan progress.
 * <p>ORIGINAL SOURCE: https://github.com/MCRcortex/nekodetector</p>
 *
 * @author mica-alex (https://github.com/mica-alex)
 */
public class Progress
{

    /**
     * Current number of files processed.
     */
    private double filesProcessed = 0.0;

    /**
     * Total number of files to process.
     */
    private double totalFiles = 0.0;

    /**
     * Creates a new instance of Progress with the given number of files processed and total number of files.
     *
     * @param filesProcessed Current number of files processed.
     * @param totalFiles     Total number of files to process.
     */
    public Progress( double filesProcessed, double totalFiles ) {
        this.filesProcessed = filesProcessed;
        this.totalFiles = totalFiles;
    }

    /**
     * Returns the current number of files processed.
     *
     * @return Current number of files processed.
     */
    public double getFilesProcessed() {
        return filesProcessed;
    }

    /**
     * Returns the total number of files to process.
     *
     * @return Total number of files to process.
     */
    public double getTotalFiles() {
        return totalFiles;
    }
}
