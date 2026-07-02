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

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared, bounded thread pool for file download + verification work across the
 * launch pipeline.
 *
 * <p>A single launch fans out three branches concurrently
 * ({@code doModpackContent}, {@code doForgeLibs}, {@code doMcLibsThenJre}) on the
 * orchestration pool, and each download stage used to spin up its <em>own</em>
 * {@code newFixedThreadPool(~max(12, cores))}. That meant a launch could run
 * ~36-48 download threads at once, oversubscribing the network and disk (too
 * many concurrent connections contend rather than parallelize, and each thread
 * holds a multi-hundred-KB transfer buffer). Routing every stage through one
 * bounded pool caps total download concurrency to a sane ceiling regardless of
 * how many stages run at once, and removes the per-launch pool create/destroy
 * churn.</p>
 *
 * <p>This pool is deliberately <strong>separate</strong> from the launch
 * orchestration pool: the orchestration branches block waiting on this pool's
 * futures, so sharing one pool could starve it. The tasks submitted here never
 * submit back into this pool, so there is no nested-dependency deadlock.</p>
 *
 * @author Mica Technologies
 * @since 3.7
 */
public final class DownloadExecutor
{
    /**
     * Private constructor to prevent instantiation of the utility class.
     */
    private DownloadExecutor() { }

    /**
     * Pool size. IO-bound work, so sized above the core count to keep the CDN
     * pipe full, but bounded so concurrent launch stages can't collectively
     * oversubscribe the network/disk.
     */
    private static final int POOL_SIZE = Math.max( 16, Runtime.getRuntime().availableProcessors() * 2 );

    /**
     * The shared pool. Daemon threads so an in-flight download can't keep the
     * JVM alive after the user quits; never shut down (it lives for the JVM and
     * is reused across launches).
     */
    private static final ExecutorService POOL = Executors.newFixedThreadPool( POOL_SIZE, r -> {
        Thread t = new Thread( r, "mmcl-download" );
        t.setDaemon( true );
        return t;
    } );

    /**
     * Submits a download/verify task to the shared pool.
     *
     * @param task the task to run
     * @param <T>  the task result type
     *
     * @return a future for the task result
     */
    public static < T > Future< T > submit( Callable< T > task )
    {
        return POOL.submit( task );
    }

    /**
     * Waits for every future in {@code futures}, enforcing an overall
     * {@code timeoutMs} deadline across the whole set. On interruption, timeout,
     * or a task failure, cancels every still-pending future first (interrupting
     * running tasks — the shared-pool analog of {@code shutdownNow}) so a failed
     * batch doesn't leave siblings running against the shared pool.
     *
     * @param futures   the futures to drain
     * @param timeoutMs the overall deadline in milliseconds
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws ExecutionException   if any task threw
     * @throws TimeoutException     if the deadline elapsed before all tasks finished
     */
    public static void awaitAll( List< ? extends Future< ? > > futures, long timeoutMs )
    throws InterruptedException, ExecutionException, TimeoutException
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean stuckDumped = false;
        try {
            for ( Future< ? > f : futures ) {
                // Wait in bounded slices rather than one long get() so a batch that has
                // silently frozen (observed: every download-pool task wedged pre-first-byte
                // with no timeout firing) self-diagnoses: a full slice with ZERO newly
                // completed futures across the whole batch dumps the download/launch
                // thread stacks to the log once, then waiting continues to the deadline.
                while ( true ) {
                    long remaining = deadline - System.currentTimeMillis();
                    if ( remaining <= 0L ) {
                        throw new TimeoutException(
                                "Download tasks did not complete within the allotted time." );
                    }
                    long doneBefore = countDone( futures );
                    try {
                        f.get( Math.min( remaining, STUCK_PROBE_INTERVAL_MS ), TimeUnit.MILLISECONDS );
                        break;
                    }
                    catch ( TimeoutException sliceTimeout ) {
                        if ( deadline - System.currentTimeMillis() <= 0L ) {
                            throw sliceTimeout;   // genuine overall deadline — handled below
                        }
                        if ( !stuckDumped && countDone( futures ) == doneBefore ) {
                            stuckDumped = true;
                            dumpWorkerThreads( futures );
                        }
                        // Slice elapsed but time remains — keep waiting on the same future.
                    }
                }
            }
        }
        catch ( InterruptedException e ) {
            cancelAll( futures );
            Thread.currentThread().interrupt();
            throw e;
        }
        catch ( ExecutionException | TimeoutException e ) {
            cancelAll( futures );
            throw e;
        }
    }

    /**
     * Interval between batch-progress probes inside {@link #awaitAll}. A batch that completes
     * nothing for one full interval is considered stuck and triggers a one-shot thread dump.
     */
    private static final long STUCK_PROBE_INTERVAL_MS = 2 * 60 * 1000L;

    /** Maximum stack frames captured per thread in the stuck-batch dump. */
    private static final int STUCK_DUMP_MAX_FRAMES = 14;

    /**
     * Counts the futures in the batch that have reached a terminal state.
     *
     * @param futures the batch to inspect
     * @return how many futures are done (completed, failed, or cancelled)
     */
    private static long countDone( List< ? extends Future< ? > > futures )
    {
        long done = 0;
        for ( Future< ? > f : futures ) {
            if ( f.isDone() ) {
                done++;
            }
        }
        return done;
    }

    /**
     * Logs the current stack of every download-pool ({@code mmcl-download}) and launch-branch
     * ({@code mica-launch-io}) thread. Fired once per {@link #awaitAll} batch when a full probe
     * interval passes with no batch progress — the resulting log section shows exactly where
     * each worker is parked, turning an otherwise-silent freeze into a diagnosable report.
     *
     * @param futures the stuck batch (for the done/total summary in the header)
     */
    private static void dumpWorkerThreads( List< ? extends Future< ? > > futures )
    {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append( countDone( futures ) ).append( '/' ).append( futures.size() )
              .append( " tasks done" );
            for ( var entry : Thread.getAllStackTraces().entrySet() ) {
                Thread t = entry.getKey();
                String name = t.getName();
                if ( !name.startsWith( "mmcl-download" ) && !name.startsWith( "mica-launch-io" ) ) {
                    continue;
                }
                sb.append( '\n' ).append( name ).append( " [" ).append( t.getState() ).append( ']' );
                StackTraceElement[] frames = entry.getValue();
                int limit = Math.min( frames.length, STUCK_DUMP_MAX_FRAMES );
                for ( int i = 0; i < limit; i++ ) {
                    sb.append( "\n    at " ).append( frames[ i ] );
                }
                if ( frames.length > limit ) {
                    sb.append( "\n    ... " ).append( frames.length - limit ).append( " more" );
                }
            }
            com.micatechnologies.minecraft.launcher.files.Logger.logWarningSilent(
                    com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager
                            .format( "log.downloadExecutor.noProgressDump",
                                     STUCK_PROBE_INTERVAL_MS / 1000, sb.toString() ) );
        }
        catch ( Throwable t ) {
            // Diagnostics must never break the wait path itself.
        }
    }

    /**
     * Cancels all the futures in the provided list.
     *
     * @param futures the futures to cancel
     */
    private static void cancelAll( List< ? extends Future< ? > > futures )
    {
        for ( Future< ? > f : futures ) {
            f.cancel( true );
        }
    }
}
