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

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the helpers the shutdown path relies on: {@link SystemUtilities#runBounded} (a hung vendor
 * SDK must not hang exit) and {@link SystemUtilities#isBackgroundWorker()} (shutdown must not run on
 * the pool it shuts down).
 */
class SystemUtilitiesShutdownHelpersTest
{
    @Test
    void runBoundedReportsAStepThatFinishes()
    {
        AtomicBoolean ran = new AtomicBoolean();
        assertTrue( SystemUtilities.runBounded( "quick", () -> ran.set( true ), 2_000, null ) );
        assertTrue( ran.get() );
    }

    @Test
    void runBoundedGivesUpOnAHungStepWithinTheBound()
    {
        CountDownLatch never = new CountDownLatch( 1 );
        long start = System.nanoTime();
        boolean finished = SystemUtilities.runBounded( "hung", () -> {
            try {
                never.await();
            }
            catch ( InterruptedException ignored ) {
                Thread.currentThread().interrupt();
            }
        }, 200, null );
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis( System.nanoTime() - start );
        assertFalse( finished );
        assertTrue( elapsedMs < 2_000, "waited " + elapsedMs + " ms" );
        never.countDown();
    }

    @Test
    void runBoundedPassesAThrowingStepsFailureOnAndCountsItAsFinished()
    {
        AtomicReference< Throwable > seen = new AtomicReference<>();
        assertTrue( SystemUtilities.runBounded( "throws", () -> {
            throw new IllegalStateException( "boom" );
        }, 2_000, seen::set ) );
        assertEquals( "boom", seen.get().getMessage() );
    }

    @Test
    void onlyPoolWorkersCountAsBackgroundWorkers() throws Exception
    {
        assertFalse( SystemUtilities.isBackgroundWorker() );
        CountDownLatch done = new CountDownLatch( 1 );
        AtomicBoolean onWorker = new AtomicBoolean();
        SystemUtilities.spawnNewTask( () -> {
            onWorker.set( SystemUtilities.isBackgroundWorker() );
            done.countDown();
        } );
        assertTrue( done.await( 5, TimeUnit.SECONDS ) );
        assertTrue( onWorker.get() );
    }
}
