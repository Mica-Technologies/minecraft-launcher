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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link PlayTimeFormatting} — the bucketing behind the library's "last played"
 * and "total play time" labels.
 *
 * <p>Why this matters: these labels are on every card in the game library, so an
 * off-by-one at a unit boundary is immediately visible ("60 minutes ago" instead of "1
 * hour ago"), and picking a singular key for a plural count reads as broken in the
 * languages that inflect. Neither could be tested before, because the bucketing was
 * interleaved with {@code LocalizationManager} lookups and a live clock.</p>
 *
 * <p>These tests assert <b>keys</b>, never rendered text, so they hold in every locale.
 * That is the point of the split — testing the rendered string would pin the suite to
 * English and defeat the localization work.</p>
 */
class PlayTimeFormattingTest
{
    private static final long MINUTE = 60_000L;
    private static final long HOUR   = 60 * MINUTE;
    private static final long DAY    = 24 * HOUR;

    /** Arbitrary fixed "now" so no test reads the real clock. */
    private static final long NOW = 1_700_000_000_000L;

    private static String lastPlayedKey( long ago )
    {
        return PlayTimeFormatting.lastPlayed( NOW - ago, NOW ).key();
    }

    private static double lastPlayedAmount( long ago )
    {
        return PlayTimeFormatting.lastPlayed( NOW - ago, NOW ).amount();
    }

    // =========================================================================
    //  Last played — sentinel
    // =========================================================================

    /**
     * A zero timestamp is the never-launched sentinel. {@code GameModPackMetadata}
     * exposes {@code isNeverPlayed()} precisely so callers test this condition instead of
     * string-comparing the rendered label, which only works in English.
     */
    @Test
    void neverPlayedUsesTheNeverKey()
    {
        assertEquals( "metadata.lastPlayed.never",
                      PlayTimeFormatting.lastPlayed( 0L, NOW ).key() );
    }

    // =========================================================================
    //  Last played — boundaries
    // =========================================================================

    @Test
    void underOneMinuteIsJustNow()
    {
        assertEquals( "metadata.lastPlayed.justNow", lastPlayedKey( 0 ) );
        assertEquals( "metadata.lastPlayed.justNow", lastPlayedKey( MINUTE - 1 ) );
    }

    @Test
    void exactlyOneMinuteCrossesIntoTheMinutesBucket()
    {
        assertEquals( "metadata.lastPlayed.minuteAgo", lastPlayedKey( MINUTE ) );
        assertEquals( 1, lastPlayedAmount( MINUTE ) );
    }

    @Test
    void twoMinutesUsesThePluralKey()
    {
        assertEquals( "metadata.lastPlayed.minutesAgo", lastPlayedKey( 2 * MINUTE ) );
        assertEquals( 2, lastPlayedAmount( 2 * MINUTE ) );
    }

    @Test
    void justUnderAnHourIsStillMinutes()
    {
        assertEquals( "metadata.lastPlayed.minutesAgo", lastPlayedKey( HOUR - 1 ) );
        assertEquals( 59, lastPlayedAmount( HOUR - 1 ) );
    }

    @Test
    void exactlyOneHourCrossesIntoTheHoursBucket()
    {
        assertEquals( "metadata.lastPlayed.hourAgo", lastPlayedKey( HOUR ) );
        assertEquals( 1, lastPlayedAmount( HOUR ) );
    }

    @Test
    void severalHoursUsesThePluralKey()
    {
        assertEquals( "metadata.lastPlayed.hoursAgo", lastPlayedKey( 5 * HOUR ) );
        assertEquals( 5, lastPlayedAmount( 5 * HOUR ) );
    }

    @Test
    void justUnderADayIsStillHours()
    {
        assertEquals( "metadata.lastPlayed.hoursAgo", lastPlayedKey( DAY - 1 ) );
        assertEquals( 23, lastPlayedAmount( DAY - 1 ) );
    }

    @Test
    void exactlyOneDayCrossesIntoTheDaysBucket()
    {
        assertEquals( "metadata.lastPlayed.dayAgo", lastPlayedKey( DAY ) );
        assertEquals( 1, lastPlayedAmount( DAY ) );
    }

    @Test
    void manyDaysUsesThePluralKeyAndDoesNotSaturate()
    {
        assertEquals( "metadata.lastPlayed.daysAgo", lastPlayedKey( 400 * DAY ) );
        assertEquals( 400, lastPlayedAmount( 400 * DAY ) );
    }

    /**
     * A future timestamp (clock skew, or a config synced from a machine running ahead)
     * yields a negative elapsed time, which lands in the "just now" bucket rather than
     * producing a negative day count. Pinned because the alternative — rendering
     * "-3 days ago" on a library card — is the visible failure.
     */
    @Test
    void futureTimestampDegradesToJustNowRatherThanANegativeCount()
    {
        assertEquals( "metadata.lastPlayed.justNow",
                      PlayTimeFormatting.lastPlayed( NOW + DAY, NOW ).key() );
    }

    // =========================================================================
    //  Total play time
    // =========================================================================

    @Test
    void zeroPlayTimeUsesTheZeroKey()
    {
        assertEquals( "gameModPackMetadata.totalPlayTime.zero",
                      PlayTimeFormatting.totalPlayTime( 0 ).key() );
    }

    /**
     * Under one minute of accumulated play truncates to zero minutes but is not the zero
     * sentinel — the pack HAS been played. Pinned because the two are easy to conflate.
     */
    @Test
    void subMinutePlayTimeIsNotTheZeroSentinel()
    {
        PlayTimeFormatting.Label label = PlayTimeFormatting.totalPlayTime( 30_000L );
        assertEquals( "gameModPackMetadata.totalPlayTime.minutes", label.key() );
        assertEquals( 0, label.amount() );
    }

    @Test
    void oneMinuteUsesTheSingularKey()
    {
        assertEquals( "gameModPackMetadata.totalPlayTime.minute",
                      PlayTimeFormatting.totalPlayTime( MINUTE ).key() );
    }

    @Test
    void severalMinutesUsesThePluralKey()
    {
        PlayTimeFormatting.Label label = PlayTimeFormatting.totalPlayTime( 45 * MINUTE );
        assertEquals( "gameModPackMetadata.totalPlayTime.minutes", label.key() );
        assertEquals( 45, label.amount() );
    }

    @Test
    void justUnderAnHourIsStillMinutesBucket()
    {
        assertEquals( "gameModPackMetadata.totalPlayTime.minutes",
                      PlayTimeFormatting.totalPlayTime( HOUR - 1 ).key() );
    }

    @Test
    void exactlyOneHourCrossesIntoTheHoursBucketWithAFraction()
    {
        PlayTimeFormatting.Label label = PlayTimeFormatting.totalPlayTime( HOUR );
        assertEquals( "gameModPackMetadata.totalPlayTime.hours", label.key() );
        assertEquals( 1.0, label.amount() );
    }

    @Test
    void hoursBucketKeepsTheFractionalPart()
    {
        assertEquals( 3.5, PlayTimeFormatting.totalPlayTime( 3 * HOUR + 30 * MINUTE ).amount() );
    }

    @Test
    void totalJustUnderADayIsStillHours()
    {
        assertEquals( "gameModPackMetadata.totalPlayTime.hours",
                      PlayTimeFormatting.totalPlayTime( DAY - MINUTE ).key() );
    }

    @Test
    void totalExactlyOneDayCrossesIntoTheDaysBucket()
    {
        PlayTimeFormatting.Label label = PlayTimeFormatting.totalPlayTime( DAY );
        assertEquals( "gameModPackMetadata.totalPlayTime.days", label.key() );
        assertEquals( 1.0, label.amount() );
    }

    @Test
    void daysBucketKeepsTheFractionalPart()
    {
        assertEquals( 2.5, PlayTimeFormatting.totalPlayTime( 2 * DAY + 12 * HOUR ).amount() );
    }
}
