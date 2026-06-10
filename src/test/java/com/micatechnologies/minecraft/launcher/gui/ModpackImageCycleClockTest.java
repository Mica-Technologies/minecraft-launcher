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

package com.micatechnologies.minecraft.launcher.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;

import javafx.util.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ModpackImageCycleClock#tokenToDuration} — the pure mapping from
 * a Settings interval token to a concrete {@link Duration}. Exercises the seam without an
 * FX toolkit ({@code javafx.util.Duration} is a plain value class) so the test stays fast
 * and headless, matching the project's pure-logic testing convention.
 */
class ModpackImageCycleClockTest
{
    @Test
    void mapsEverySecondsAndMinutesToken()
    {
        assertEquals( Duration.seconds( 5 ), ModpackImageCycleClock.tokenToDuration( "5s" ) );
        assertEquals( Duration.seconds( 15 ), ModpackImageCycleClock.tokenToDuration( "15s" ) );
        assertEquals( Duration.seconds( 30 ), ModpackImageCycleClock.tokenToDuration( "30s" ) );
        assertEquals( Duration.minutes( 1 ), ModpackImageCycleClock.tokenToDuration( "1m" ) );
        assertEquals( Duration.minutes( 5 ), ModpackImageCycleClock.tokenToDuration( "5m" ) );
        assertEquals( Duration.minutes( 15 ), ModpackImageCycleClock.tokenToDuration( "15m" ) );
        assertEquals( Duration.minutes( 30 ), ModpackImageCycleClock.tokenToDuration( "30m" ) );
    }

    @Test
    void mapsHoursAndLongIntervals()
    {
        assertEquals( Duration.hours( 1 ), ModpackImageCycleClock.tokenToDuration( "1h" ) );
        assertEquals( Duration.hours( 6 ), ModpackImageCycleClock.tokenToDuration( "6h" ) );
        assertEquals( Duration.hours( 12 ), ModpackImageCycleClock.tokenToDuration( "12h" ) );
        assertEquals( Duration.hours( 24 ), ModpackImageCycleClock.tokenToDuration( "1d" ) );
        assertEquals( Duration.hours( 24 * 7 ), ModpackImageCycleClock.tokenToDuration( "7d" ) );
    }

    @Test
    void neverAndUnknownAndNullMapToNoCycling()
    {
        assertNull( ModpackImageCycleClock.tokenToDuration( "never" ) );
        assertNull( ModpackImageCycleClock.tokenToDuration( "bogus" ) );
        assertNull( ModpackImageCycleClock.tokenToDuration( null ) );
    }

    @Test
    void everyCanonicalOptionResolvesConsistently()
    {
        // Each option in the Settings list must map to a duration, EXCEPT "never"
        // which is the explicit "don't cycle" sentinel.
        for ( String token : ConfigConstants.IMAGE_CYCLE_INTERVAL_OPTIONS ) {
            Duration d = ModpackImageCycleClock.tokenToDuration( token );
            if ( "never".equals( token ) ) {
                assertNull( d, "'never' must not produce a duration" );
            }
            else {
                assertEquals( false, d == null, "token '" + token + "' should map to a duration" );
            }
        }
    }
}
