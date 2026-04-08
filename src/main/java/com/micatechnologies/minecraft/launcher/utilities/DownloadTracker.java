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

package com.micatechnologies.minecraft.launcher.utilities;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks aggregate download progress across multiple concurrent downloads. Provides speed calculation (bytes/sec) and
 * ETA estimation based on a rolling window of recent throughput. Thread-safe for use with parallel download pools.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
public class DownloadTracker
{
    /**
     * Minimum interval (ms) between speed recalculations to avoid jitter.
     */
    private static final long SPEED_UPDATE_INTERVAL_MS = 500;

    /**
     * Total bytes downloaded across all tracked files since this tracker was created or last reset.
     */
    private final AtomicLong totalBytesDownloaded = new AtomicLong( 0 );

    /**
     * Total bytes expected across all tracked files. -1 means unknown.
     */
    private final AtomicLong totalBytesExpected = new AtomicLong( 0 );

    /**
     * Number of files currently being downloaded.
     */
    private final AtomicLong activeDownloads = new AtomicLong( 0 );

    /**
     * Number of active network downloads that have completed (for speed tracking).
     */
    private final AtomicLong completedDownloads = new AtomicLong( 0 );

    /**
     * Number of files that have been fully processed (verified or downloaded).
     */
    private final AtomicLong completedFiles = new AtomicLong( 0 );

    /**
     * Total number of files to process (set upfront via {@link #setTotalFileCount(long)}).
     */
    private final AtomicLong totalFiles = new AtomicLong( 0 );

    /**
     * Timestamp (ms) when the first download started.
     */
    private volatile long startTimeMs = 0;

    /**
     * Last calculated speed in bytes per second.
     */
    private volatile double speedBytesPerSec = 0;

    /**
     * Timestamp of the last speed recalculation.
     */
    private volatile long lastSpeedUpdateMs = 0;

    /**
     * Bytes downloaded at the time of the last speed recalculation (for rolling window).
     */
    private volatile long lastSpeedBytes = 0;

    /**
     * Resets all tracking state. Call before starting a new batch of downloads.
     */
    public void reset()
    {
        totalBytesDownloaded.set( 0 );
        totalBytesExpected.set( 0 );
        activeDownloads.set( 0 );
        completedDownloads.set( 0 );
        completedFiles.set( 0 );
        totalFiles.set( 0 );
        startTimeMs = 0;
        speedBytesPerSec = 0;
        lastSpeedUpdateMs = 0;
        lastSpeedBytes = 0;
    }

    /**
     * Pre-registers the total number of files that will be processed (downloaded or verified) in this batch. Call this
     * once before the download loop starts so the file counter shows the correct total from the beginning.
     *
     * @param count total number of files in this batch
     */
    public void setTotalFileCount( long count )
    {
        totalFiles.set( count );
    }

    /**
     * Registers a file that is about to start downloading. Does NOT increment totalFiles (use
     * {@link #setTotalFileCount(long)} before the batch starts).
     *
     * @param expectedBytes the expected file size in bytes, or -1 if unknown
     */
    public void registerDownload( long expectedBytes )
    {
        if ( expectedBytes > 0 ) {
            totalBytesExpected.addAndGet( expectedBytes );
        }
        long now = System.currentTimeMillis();
        if ( startTimeMs == 0 ) {
            startTimeMs = now;
            lastSpeedUpdateMs = now;
        }
        activeDownloads.incrementAndGet();
    }

    /**
     * Reports bytes downloaded for a single chunk. Called from the download stream loop.
     *
     * @param bytes number of bytes just read
     */
    public void addBytes( long bytes )
    {
        totalBytesDownloaded.addAndGet( bytes );
        recalculateSpeed();
    }

    /**
     * Marks an active network download as complete (for speed/active-download tracking).
     */
    public void completeDownload()
    {
        activeDownloads.decrementAndGet();
        completedDownloads.incrementAndGet();
    }

    /**
     * Marks a file as fully processed (verified or downloaded). Call after each file is done regardless of whether
     * it needed a network download.
     */
    public void completeFile()
    {
        completedFiles.incrementAndGet();
    }

    /**
     * Recalculates speed using a rolling window to smooth out fluctuations.
     */
    private void recalculateSpeed()
    {
        long now = System.currentTimeMillis();
        long elapsed = now - lastSpeedUpdateMs;
        if ( elapsed >= SPEED_UPDATE_INTERVAL_MS ) {
            long currentBytes = totalBytesDownloaded.get();
            long bytesDelta = currentBytes - lastSpeedBytes;
            if ( elapsed > 0 ) {
                // Blend new measurement with previous speed for smoothing
                double instantSpeed = ( bytesDelta * 1000.0 ) / elapsed;
                speedBytesPerSec = ( speedBytesPerSec * 0.3 ) + ( instantSpeed * 0.7 );
            }
            lastSpeedBytes = currentBytes;
            lastSpeedUpdateMs = now;
        }
    }

    /**
     * Returns the current download speed in bytes per second.
     *
     * @return download speed (bytes/sec)
     */
    public double getSpeedBytesPerSec()
    {
        return speedBytesPerSec;
    }

    /**
     * Returns the estimated time remaining in seconds, or -1 if unknown. Uses file-count-based estimation (average
     * time per file * remaining files) which is more reliable than byte-based estimation since many servers don't
     * report Content-Length.
     *
     * @return ETA in seconds, or -1
     */
    public long getEtaSeconds()
    {
        long completed = completedFiles.get();
        long total = totalFiles.get();
        if ( completed < 2 || total <= 0 || completed >= total || startTimeMs == 0 ) {
            return -1;
        }
        long elapsedMs = System.currentTimeMillis() - startTimeMs;
        double msPerFile = ( double ) elapsedMs / completed;
        long remainingFiles = total - completed;
        return ( long ) ( ( msPerFile * remainingFiles ) / 1000.0 );
    }

    /**
     * Returns a human-readable speed string (e.g. "2.4 MB/s").
     *
     * @return formatted speed string
     */
    public String getFormattedSpeed()
    {
        double speed = speedBytesPerSec;
        if ( speed < 1.0 ) {
            return "";
        }
        else if ( speed < 1024 ) {
            return String.format( "%.0f B/s", speed );
        }
        else if ( speed < 1024 * 1024 ) {
            return String.format( "%.1f KB/s", speed / 1024 );
        }
        else {
            return String.format( "%.1f MB/s", speed / ( 1024 * 1024 ) );
        }
    }

    /**
     * Returns a human-readable ETA string (e.g. "3:42 remaining"), or empty string if unknown.
     *
     * @return formatted ETA string
     */
    public String getFormattedEta()
    {
        long eta = getEtaSeconds();
        if ( eta < 0 ) {
            return "";
        }
        else if ( eta < 60 ) {
            return eta + "s remaining";
        }
        else if ( eta < 3600 ) {
            return String.format( "%d:%02d remaining", eta / 60, eta % 60 );
        }
        else {
            return String.format( "%d:%02d:%02d remaining", eta / 3600, ( eta % 3600 ) / 60, eta % 60 );
        }
    }

    /**
     * Returns a combined status line suitable for display in the progress GUI.
     * Format: "2.4 MB/s -- 3:42 remaining" or just "2.4 MB/s" if ETA is unknown.
     *
     * @return formatted status string, or empty if no speed data yet
     */
    public String getFormattedStatus()
    {
        String speed = getFormattedSpeed();
        String eta = getFormattedEta();
        long completed = completedFiles.get();
        long total = totalFiles.get();
        String fileCount = ( total > 0 ) ? ( completed + "/" + total + " files" ) : "";
        if ( speed.isEmpty() ) {
            // Even without speed data, show file count if available
            return fileCount;
        }
        StringBuilder sb = new StringBuilder();
        sb.append( speed );
        if ( !eta.isEmpty() ) {
            sb.append( " \u2014 " );
            sb.append( eta );
        }
        if ( !fileCount.isEmpty() ) {
            sb.append( " \u2014 " );
            sb.append( fileCount );
        }
        return sb.toString();
    }

    /**
     * Returns the total number of bytes downloaded so far.
     *
     * @return total bytes downloaded
     */
    public long getTotalBytesDownloaded()
    {
        return totalBytesDownloaded.get();
    }

    /**
     * Returns the total number of bytes expected, or 0 if unknown.
     *
     * @return total expected bytes
     */
    public long getTotalBytesExpected()
    {
        return totalBytesExpected.get();
    }
}
