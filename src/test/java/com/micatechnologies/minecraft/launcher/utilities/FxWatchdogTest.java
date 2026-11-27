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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the FX watchdog's stall arithmetic and its thread dump, which is the only evidence a
 * frozen launcher leaves behind on a machine without a terminal.
 *
 * @since 2026.11
 */
class FxWatchdogTest
{
    private static final long MS = 1_000_000L;

    @Test
    void aGapShorterThanTheThresholdIsNoStall()
    {
        assertFalse( FxWatchdog.stalledFor( 0, 4_999 * MS, 5_000 ) );
    }

    @Test
    void aGapReachingTheThresholdIsAStall()
    {
        assertTrue( FxWatchdog.stalledFor( 0, 5_000 * MS, 5_000 ) );
        assertTrue( FxWatchdog.stalledFor( 100 * MS, 20_000 * MS, 10_000 ) );
    }

    @Test
    void theHandOffThresholdIsShorterThanTheDumpThreshold()
    {
        // A second launch should see "not responding" before the dump is even written.
        assertTrue( FxWatchdog.UNRESPONSIVE_MS < FxWatchdog.STALL_MS );
    }

    @Test
    void notStartedIsNeverUnresponsive()
    {
        assertFalse( FxWatchdog.isUnresponsive() );
    }

    @Test
    void theDumpHasEveryThreadWithFullStacks()
    {
        String dump = FxWatchdog.threadDump( 12_345 );
        assertTrue( dump.startsWith( "FX application thread unanswered for 12345 ms" ) );
        assertTrue( dump.contains( "\"" + Thread.currentThread().getName() + "\"" ) );
        assertTrue( dump.contains( "theDumpHasEveryThreadWithFullStacks" ) );
    }
}
