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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link LaunchProgressTracker}'s listener-notification locking
 * contract: listeners must be invoked <em>outside</em> the tracker's monitor.
 *
 * <p>The original implementation fired listeners from inside the {@code synchronized}
 * mutators, so a listener that blocked waiting on another thread — e.g. one marshalling
 * to the JavaFX thread with a latch — held the tracker monitor while it waited. Any other
 * thread then calling a tracker mutator (another download branch, or the FX thread
 * itself) blocked on the monitor, completing a cross-thread deadlock that froze the
 * launch progress GUI and, with the FX thread involved, the whole app.</p>
 */
class LaunchProgressTrackerNotifyTest
{
    /**
     * A listener that blocks until a second thread has successfully entered a
     * synchronized tracker mutator. If notifications fire while the tracker monitor is
     * held, the second thread can never enter the mutator and this times out —
     * reproducing the deadlock.
     */
    @Test
    void listenersAreNotifiedOutsideTheTrackerMonitor() throws Exception
    {
        LaunchProgressTracker tracker = LaunchProgressTracker.forSteps(
                LaunchProgressTracker.StepId.MODPACK_CONTENT,
                LaunchProgressTracker.StepId.FORGE_LIBS );

        CountDownLatch listenerEntered = new CountDownLatch( 1 );
        CountDownLatch secondMutatorDone = new CountDownLatch( 1 );
        AtomicBoolean firstNotification = new AtomicBoolean( true );

        tracker.addListener( step -> {
            // Only block on the very first notification — the second thread's own
            // mutation also notifies, and blocking there would self-deadlock the test.
            if ( firstNotification.compareAndSet( true, false ) ) {
                listenerEntered.countDown();
                try {
                    // Wait (bounded) for the second thread to get through a synchronized
                    // mutator while THIS listener callback is still on the stack.
                    secondMutatorDone.await( 5, TimeUnit.SECONDS );
                }
                catch ( InterruptedException ignored ) {
                    Thread.currentThread().interrupt();
                }
            }
        } );

        Thread second = new Thread( () -> {
            try {
                listenerEntered.await( 5, TimeUnit.SECONDS );
                // With notify-under-lock this call would block forever on the tracker
                // monitor (held by the thread still inside the listener above).
                tracker.setSubText( LaunchProgressTracker.StepId.FORGE_LIBS, "entered" );
                secondMutatorDone.countDown();
            }
            catch ( InterruptedException ignored ) {
                Thread.currentThread().interrupt();
            }
        }, "tracker-second-mutator" );
        second.setDaemon( true );
        second.start();

        Thread first = new Thread(
                () -> tracker.markRunning( LaunchProgressTracker.StepId.MODPACK_CONTENT ),
                "tracker-first-mutator" );
        first.setDaemon( true );
        first.start();

        assertTrue( secondMutatorDone.await( 10, TimeUnit.SECONDS ),
                    "A tracker mutator on a second thread never completed while a listener "
                            + "callback was in flight — listeners are being notified while "
                            + "the tracker monitor is held" );
        first.join( 5_000 );
        second.join( 5_000 );
        assertEquals( "entered",
                      tracker.step( LaunchProgressTracker.StepId.FORGE_LIBS ).subText() );
    }

    /** Every mutation still produces exactly one listener notification, in order. */
    @Test
    void mutationsProduceOneNotificationEach()
    {
        LaunchProgressTracker tracker = LaunchProgressTracker.forSteps(
                LaunchProgressTracker.StepId.MC_LIBS_ASSETS );
        List< String > events = new ArrayList<>();
        tracker.addListener( step -> events.add( step.id() + ":" + step.state() ) );

        tracker.markRunning( LaunchProgressTracker.StepId.MC_LIBS_ASSETS );
        tracker.submitProgress( LaunchProgressTracker.StepId.MC_LIBS_ASSETS, 0.5, "halfway" );
        tracker.markDone( LaunchProgressTracker.StepId.MC_LIBS_ASSETS );

        assertEquals( 3, events.size() );
        assertEquals( "MC_LIBS_ASSETS:RUNNING", events.get( 0 ) );
        assertEquals( "MC_LIBS_ASSETS:RUNNING", events.get( 1 ) );
        assertEquals( "MC_LIBS_ASSETS:DONE", events.get( 2 ) );
        assertEquals( 1.0, tracker.step( LaunchProgressTracker.StepId.MC_LIBS_ASSETS ).progress() );
    }
}
