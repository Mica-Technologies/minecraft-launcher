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


package com.micatechnologies.minecraft.launcher.game.session;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatestUpdateRunnerTest
{
    /** A manually drained, in-order executor. */
    private final Queue< Runnable >  queue   = new ArrayDeque<>();
    private final LatestUpdateRunner runner  = new LatestUpdateRunner( queue::add );
    private final List< String >     applied = new ArrayList<>();

    private void drain()
    {
        Runnable r;
        while ( ( r = queue.poll() ) != null ) {
            r.run();
        }
    }

    @Test
    void appliesUpdatesInOrderWhenEachRunsBeforeTheNext()
    {
        runner.submit( () -> applied.add( "game" ) );
        drain();
        runner.submit( () -> applied.add( "none" ) );
        drain();
        assertEquals( List.of( "game", "none" ), applied );
    }

    @Test
    void skipsAnUpdateAlreadyReplaced()
    {
        // A game starts and ends before "show the game" gets its turn: only "no game" lands.
        runner.submit( () -> applied.add( "game" ) );
        runner.submit( () -> applied.add( "none" ) );
        drain();
        assertEquals( List.of( "none" ), applied );
    }

    @Test
    void newestUpdateLandsLastEvenWhenAnEarlierOneIsSlow() throws InterruptedException
    {
        LatestUpdateRunner real = LatestUpdateRunner.onDaemonThread( "test-follower" );
        List< String > seen = Collections.synchronizedList( new ArrayList<>() );
        CountDownLatch slowStarted = new CountDownLatch( 1 );
        CountDownLatch release = new CountDownLatch( 1 );
        CountDownLatch done = new CountDownLatch( 1 );
        real.submit( () -> {
            slowStarted.countDown();
            try {
                release.await( 5, TimeUnit.SECONDS );
            }
            catch ( InterruptedException e ) {
                Thread.currentThread().interrupt();
            }
            seen.add( "game" );
        } );
        assertTrue( slowStarted.await( 5, TimeUnit.SECONDS ) );
        real.submit( () -> {
            seen.add( "none" );
            done.countDown();
        } );
        release.countDown();
        assertTrue( done.await( 5, TimeUnit.SECONDS ) );
        assertEquals( List.of( "game", "none" ), seen );
    }
}
