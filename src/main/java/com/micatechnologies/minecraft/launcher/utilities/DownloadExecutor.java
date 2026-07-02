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
        try {
            for ( Future< ? > f : futures ) {
                long remaining = deadline - System.currentTimeMillis();
                if ( remaining <= 0L ) {
                    throw new TimeoutException( "Download tasks did not complete within the allotted time." );
                }
                f.get( remaining, TimeUnit.MILLISECONDS );
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
