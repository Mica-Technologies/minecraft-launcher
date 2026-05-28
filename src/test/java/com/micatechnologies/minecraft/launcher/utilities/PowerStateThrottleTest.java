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

import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the battery download-throttle regression fix. The throttle only runs on battery, and
 * the original implementation slept a <em>fixed</em> {@code chunkBytes / cap} after every 8 KiB
 * chunk. That assumes each {@link Thread#sleep} lands exactly as requested — but on battery the
 * powersave CPU governor and (on Windows) timer coalescing inflate every short sleep, so a
 * requested ~15.6 ms sleep routinely took 30-50 ms. Because the fixed-sleep model recomputed the
 * same delay every chunk with no feedback, it paid that overshoot ~64×/second and throughput
 * collapsed to a crawl — the "throttles to nothing on battery" bug.
 *
 * <p>The fix in {@link PowerStateManager#nextThrottleSleepNanos(long[], long, int, long)} is a
 * closed-loop deadline throttle: it sleeps only the real deficit between target and actual elapsed
 * time, so an overshoot on one sleep is absorbed by skipping the next. These tests drive the pure
 * seam with a synthetic clock — no real sleeping, no oshi, no network — and confirm the achieved
 * rate stays near the cap even under heavy sleep overshoot, while a fixed-sleep model does not.</p>
 */
class PowerStateThrottleTest
{
    /** Per-stream cap under test — the production constant. */
    private static final long CAP_BPS = ConfigConstants.BATTERY_THROTTLE_BYTES_PER_SEC;

    /** Download read buffer size — mirrors {@code NetworkUtilities.DOWNLOAD_BUFFER_SIZE} (8 KiB),
     *  the chunk the throttle hook is actually called with in production. */
    private static final int CHUNK = 8192;

    /** Microseconds-worth of nanos modelled as the read+write cost of one chunk. */
    private static final long IO_NANOS_PER_CHUNK = 50_000L;

    /**
     * Simulates downloading {@code totalBytes} through the deadline seam with a synthetic clock,
     * modelling each advised sleep as actually taking {@code overshoot}× as long (battery-style
     * timer inflation). Returns the achieved rate in bytes/second.
     */
    private static double simulateDeadlineRate( long totalBytes, double overshoot )
    {
        long[] st        = new long[]{ 0L, 0L };
        long   clock     = 0L;
        long   delivered = 0L;
        while ( delivered < totalBytes ) {
            clock += IO_NANOS_PER_CHUNK;
            long sleep = PowerStateManager.nextThrottleSleepNanos( st, clock, CHUNK, CAP_BPS );
            clock += ( long ) ( sleep * overshoot );
            delivered += CHUNK;
        }
        return delivered / ( clock / 1_000_000_000.0 );
    }

    /** The discredited model the fix replaced: a blind fixed sleep of {@code chunkBytes / cap}
     *  every chunk, with the same overshoot applied. Kept here only to prove the contrast. */
    private static double simulateFixedSleepRate( long totalBytes, double overshoot )
    {
        long clock     = 0L;
        long delivered = 0L;
        while ( delivered < totalBytes ) {
            clock += IO_NANOS_PER_CHUNK;
            long sleep = ( ( long ) CHUNK * 1_000_000_000L ) / CAP_BPS;
            clock += ( long ) ( sleep * overshoot );
            delivered += CHUNK;
        }
        return delivered / ( clock / 1_000_000_000.0 );
    }

    @Test
    void convergesToCapWhenSleepsLandExactly()
    {
        // Ideal clock (no overshoot): the deadline throttle should track the cap closely.
        double rate = simulateDeadlineRate( 64L * 1024 * 1024, 1.0 );
        assertTrue( rate <= CAP_BPS * 1.05,
                    "deadline throttle must not exceed the cap; got " + rate + " B/s" );
        assertTrue( rate >= CAP_BPS * 0.90,
                    "deadline throttle should reach near the cap; got " + rate + " B/s" );
    }

    @Test
    void convergesToCapDespiteHeavySleepOvershoot()
    {
        // 5x overshoot models a powersave/battery scheduler badly inflating every short sleep.
        // The closed-loop throttle absorbs it by skipping subsequent sleeps and stays near the cap.
        double rate = simulateDeadlineRate( 64L * 1024 * 1024, 5.0 );
        assertTrue( rate >= CAP_BPS * 0.90,
                    "deadline throttle should hold near the cap under 5x sleep overshoot; got "
                            + rate + " B/s (" + ( 100 * rate / CAP_BPS ) + "% of cap)" );
        assertTrue( rate <= CAP_BPS * 1.05,
                    "deadline throttle must never run faster than the cap; got " + rate + " B/s" );
    }

    @Test
    void fixedSleepModelCollapsesUnderOvershoot()
    {
        // Documents the regression: the old fixed-per-chunk sleep loses roughly a factor of the
        // overshoot, because it can't compensate. At 5x it delivers well under half the cap —
        // exactly the "throttles to nothing" symptom that prompted the fix.
        double fixed    = simulateFixedSleepRate( 64L * 1024 * 1024, 5.0 );
        double deadline = simulateDeadlineRate( 64L * 1024 * 1024, 5.0 );
        assertTrue( fixed < CAP_BPS * 0.40,
                    "fixed-sleep model should collapse under overshoot; got " + fixed + " B/s" );
        assertTrue( deadline > fixed * 2,
                    "deadline throttle should beat the fixed-sleep model by a wide margin" );
    }

    @Test
    void subFloorDeficitDoesNotSleep()
    {
        // A tiny chunk's fair share is far below the granularity floor, so the seam advises no
        // sleep and lets the deficit accumulate into a later chunk instead of issuing a sleep too
        // short to be accurate.
        long[] st = new long[]{ 0L, 0L };
        assertEquals( 0L, PowerStateManager.nextThrottleSleepNanos( st, 1_000L, 1, CAP_BPS ) );
    }

    @Test
    void disabledOrEmptyChunkNeverSleeps()
    {
        long[] st = new long[]{ 0L, 0L };
        // Zero/negative cap (throttle effectively off) -> never sleep.
        assertEquals( 0L, PowerStateManager.nextThrottleSleepNanos( st, 1_000L, CHUNK, 0L ) );
        // Zero/negative chunk (nothing written) -> never sleep.
        assertEquals( 0L, PowerStateManager.nextThrottleSleepNanos( st, 1_000L, 0, CAP_BPS ) );
    }

    @Test
    void staleGapResetsWindowInsteadOfCatchingUp()
    {
        // First chunk seeds the window.
        long[] st = new long[]{ 0L, 0L };
        PowerStateManager.nextThrottleSleepNanos( st, 0L, CHUNK, CAP_BPS );
        // A multi-second gap (download paused, or throttle disengaged then re-engaged) must NOT be
        // treated as real download time to "catch up" on — the window resets to a single chunk.
        PowerStateManager.nextThrottleSleepNanos( st, 5L * 1_000_000_000L, CHUNK, CAP_BPS );
        assertEquals( CHUNK, st[ 1 ],
                      "a stale gap should reset the accounting window to the current chunk" );
    }
}
