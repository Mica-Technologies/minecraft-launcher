/*
 * Copyright (c) 2026 Mica Technologies
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DownloadExecutor#awaitAll} replaced the per-stage
 * {@code awaitTermination}/{@code shutdownNow} bookkeeping when the launch
 * download stages moved onto one shared pool. Because the shared pool can't be
 * shut down to cancel a failed batch, awaitAll must instead cancel still-pending
 * siblings itself on timeout / failure / interrupt — otherwise a failed stage
 * would leave orphaned downloads running against the shared pool. This suite
 * locks in that contract using a local pool (so it never touches the shared one).
 *
 * @author Mica Technologies
 */
class DownloadExecutorTest
{
    private final ExecutorService pool = Executors.newFixedThreadPool( 4 );

    @AfterEach
    void tearDown()
    {
        pool.shutdownNow();
    }

    @Test
    void awaitAll_returnsWhenEveryTaskCompletes() throws Exception
    {
        List< Future< ? > > futures = List.of(
                pool.submit( () -> 1 ),
                pool.submit( () -> 2 ),
                pool.submit( () -> 3 ) );

        // Should drain cleanly without throwing.
        DownloadExecutor.awaitAll( futures, 5_000L );
        for ( Future< ? > f : futures ) {
            assertTrue( f.isDone() );
        }
    }

    @Test
    void awaitAll_propagatesTaskFailureAndCancelsSiblings()
    {
        // One task fails fast; a sibling blocks until interrupted so we can assert
        // awaitAll cancels it rather than leaving it running.
        AtomicBoolean siblingInterrupted = new AtomicBoolean( false );
        CountDownLatch siblingStarted = new CountDownLatch( 1 );

        Future< ? > failing = pool.submit( () -> { throw new IllegalStateException( "boom" ); } );
        Future< ? > blocking = pool.submit( () -> {
            siblingStarted.countDown();
            try {
                Thread.sleep( 60_000L );
            }
            catch ( InterruptedException e ) {
                siblingInterrupted.set( true );
                throw e;
            }
            return 0;
        } );

        // Order matters: put the blocking task first so awaitAll is mid-wait when the
        // failing task's get() would surface — but either ordering must still cancel.
        List< Future< ? > > futures = List.of( failing, blocking );

        assertThrows( ExecutionException.class, () -> DownloadExecutor.awaitAll( futures, 5_000L ) );
        assertTrue( blocking.isCancelled() || siblingInterrupted.get(),
                    "awaitAll must cancel the still-pending sibling on failure" );
    }

    @Test
    void awaitAll_throwsTimeoutAndCancelsPending() throws Exception
    {
        CountDownLatch started = new CountDownLatch( 1 );
        Future< ? > slow = pool.submit( () -> {
            started.countDown();
            Thread.sleep( 60_000L );
            return 0;
        } );
        started.await( 2, TimeUnit.SECONDS );

        assertThrows( TimeoutException.class,
                      () -> DownloadExecutor.awaitAll( List.of( slow ), 100L ) );
        assertTrue( slow.isCancelled(), "awaitAll must cancel the pending task on timeout" );
        assertFalse( Thread.currentThread().isInterrupted(),
                     "a timeout must not leave the caller's interrupt flag set" );
    }
}
