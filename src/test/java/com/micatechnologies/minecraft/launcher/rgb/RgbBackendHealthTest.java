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

package com.micatechnologies.minecraft.launcher.rgb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State-machine tests for {@link RgbBackendHealth}. The breaker drives
 * whether the controller dispatches frames to a backend, so its
 * transitions need to be airtight — a regression here would silently
 * turn a fixable transient outage into a permanent dead-backend status
 * (or vice versa, a real driver break into infinite retries).
 *
 * <p>Thresholds are private constants in the production class. The
 * tests cross-check them by counting calls rather than reading them, so
 * a future tuning bump to either threshold breaks the test in an
 * obvious place rather than silently passing on a wrong assumption.</p>
 */
class RgbBackendHealthTest
{
    @Test
    void initialStateIsHealthyAndCanCall()
    {
        RgbBackendHealth h = new RgbBackendHealth();
        assertEquals( RgbBackendHealth.State.HEALTHY, h.state() );
        assertTrue( h.canCall( 0L ) );
        assertEquals( 0, h.consecutiveFailures() );
    }

    @Test
    void twoFailuresStillHealthy()
    {
        // DEGRADED_THRESHOLD = 3, so two failures don't trip the breaker.
        RgbBackendHealth h = new RgbBackendHealth();
        h.recordFailure( 0L );
        h.recordFailure( 1L );
        assertEquals( RgbBackendHealth.State.HEALTHY, h.state() );
        assertTrue( h.canCall( 2L ) );
    }

    @Test
    void thirdFailureFlipsToDegraded()
    {
        RgbBackendHealth h = new RgbBackendHealth();
        h.recordFailure( 100L );
        h.recordFailure( 200L );
        h.recordFailure( 300L );
        assertEquals( RgbBackendHealth.State.DEGRADED, h.state() );

        // Immediately after entering DEGRADED, the backoff clock has just
        // been set — canCall is false until the retry time elapses.
        assertFalse( h.canCall( 300L ) );
        assertFalse( h.canCall( 30_000L ) );

        // Once we pass the 30s initial backoff, one probe is allowed.
        assertTrue( h.canCall( 30_301L ) );
    }

    @Test
    void successFromDegradedRestoresHealthy()
    {
        RgbBackendHealth h = new RgbBackendHealth();
        h.recordFailure( 0L );
        h.recordFailure( 0L );
        h.recordFailure( 0L );  // now DEGRADED
        h.recordSuccess();
        assertEquals( RgbBackendHealth.State.HEALTHY, h.state() );
        assertEquals( 0, h.consecutiveFailures() );
        assertTrue( h.canCall( 0L ) );
    }

    @Test
    void backoffDoublesOnEachDegradedFailure()
    {
        // 3rd failure → DEGRADED, retry at 30s. 4th → 60s. 5th → 120s.
        // We can't read the backoff directly, but we can observe canCall
        // returning false past one boundary and true past the next.
        RgbBackendHealth h = new RgbBackendHealth();
        long t = 0L;
        h.recordFailure( t );          // 1
        h.recordFailure( t );          // 2
        h.recordFailure( t );          // 3 → DEGRADED, retry at +30s
        h.recordFailure( t + 30_001 ); // 4 → DEGRADED, retry at +60s
        // We must still wait the new backoff window.
        assertFalse( h.canCall( t + 30_001 + 59_999 ) );
        assertTrue(  h.canCall( t + 30_001 + 60_001 ) );
    }

    @Test
    void tenFailuresEnterDeadAndStayThere()
    {
        // DEAD_THRESHOLD = 10. Past that, canCall is always false and
        // recordSuccess does NOT revive — that's intentional, see the
        // production class's javadoc on the one-way behavior.
        RgbBackendHealth h = new RgbBackendHealth();
        for ( int i = 0; i < 10; i++ ) h.recordFailure( i );
        assertEquals( RgbBackendHealth.State.DEAD, h.state() );
        assertFalse( h.canCall( 0L ) );
        assertFalse( h.canCall( Long.MAX_VALUE ) );

        h.recordSuccess();
        assertEquals( RgbBackendHealth.State.DEAD, h.state(),
                       "DEAD must be one-way — a stray success after a"
                       + " session-long outage shouldn't re-enable the backend." );
        assertFalse( h.canCall( 0L ) );
    }

    @Test
    void recordFailurePastDeadStaysDead()
    {
        // Idempotent: piling on more failures after DEAD doesn't
        // produce a state change or any other observable effect (aside
        // from incrementing the counter).
        RgbBackendHealth h = new RgbBackendHealth();
        for ( int i = 0; i < 20; i++ ) h.recordFailure( i );
        assertEquals( RgbBackendHealth.State.DEAD, h.state() );
        assertEquals( 20, h.consecutiveFailures() );
    }
}
