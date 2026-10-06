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


package com.micatechnologies.minecraft.launcher.game.modpack;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how a launch stops its parallel branches. Cancelling the branches' futures alone left
 * them running, so a cancelled launch kept downloading and a relaunch could overlap it.
 *
 * @since 2026.10
 */
class GameModPackLauncherBranchesTest
{
    @Test
    void abortInterruptsARunningBranchAndWaitsForIt() throws Exception
    {
        GameModPackLauncher.Branches branches = new GameModPackLauncher.Branches( 1, () -> false );
        CountDownLatch started = new CountDownLatch( 1 );
        AtomicBoolean sawInterrupt = new AtomicBoolean();
        branches.start( () -> {
            started.countDown();
            try {
                Thread.sleep( 60_000 );  // a blocking download stands in here
            }
            catch ( InterruptedException e ) {
                sawInterrupt.set( true );
            }
            return null;
        } );
        assertTrue( started.await( 5, TimeUnit.SECONDS ) );

        long begin = System.nanoTime();
        branches.abortAndWait();
        long tookMs = TimeUnit.NANOSECONDS.toMillis( System.nanoTime() - begin );

        assertTrue( sawInterrupt.get(), "the branch's blocking wait was interrupted" );
        assertTrue( tookMs < 5_000, "abort returned once the branch ended, not after a timeout: " + tookMs );
    }

    @Test
    void abortKeepsTheCallersInterruptStatus()
    {
        GameModPackLauncher.Branches branches = new GameModPackLauncher.Branches( 0, () -> false );
        Thread.currentThread().interrupt();
        branches.abortAndWait();
        assertTrue( Thread.interrupted(), "the cancelled launch's interrupt is still set for its caller" );
    }

    @Test
    void anUncancelledBranchRunsToItsResult() throws Exception
    {
        GameModPackLauncher.Branches branches = new GameModPackLauncher.Branches( 1, () -> false );
        CompletableFuture< String > result = branches.start( () -> "done" );
        assertEquals( "done", result.get( 5, TimeUnit.SECONDS ) );
        assertFalse( Thread.currentThread().isInterrupted() );
    }
}
