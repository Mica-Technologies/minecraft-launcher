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

package com.micatechnologies.minecraft.launcher.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the pure text-formatting helpers in {@link TuiApp}: {@code formatDuration(long)}
 * (playtime shown in the status bar and Library rows) and {@code truncate(String, int)}
 * (used everywhere a server-supplied pack name / version is laid out in a fixed-width
 * terminal column). Both are plain string/arithmetic logic with no Lanterna terminal,
 * launcher config, or other static singleton behind them, so they are exercised directly.
 *
 * <p>{@code truncate} matters beyond cosmetics: every caller in {@code TuiApp} builds a
 * fixed-width row with {@code String.format("%-Ns", ...)}, so a truncation regression that
 * returns a string longer than {@code max} would silently blow out that column and misalign
 * the whole Library/Browse list — exactly the "the terminal UI looks broken" bug report a
 * regression here would cause.</p>
 */
class TuiAppFormattingTest
{
    // ===================================================================== formatDuration

    @Test
    void zeroOrNegativeMillisecondsFormatsAsZeroMinutes()
    {
        assertEquals( "0m", TuiApp.formatDuration( 0 ) );
        assertEquals( "0m", TuiApp.formatDuration( -1000 ) );
    }

    @Test
    void subMinuteDurationsFormatAsLessThanOneMinute()
    {
        assertEquals( "<1m", TuiApp.formatDuration( 1 ) );
        assertEquals( "<1m", TuiApp.formatDuration( 59_999 ) );
    }

    @Test
    void wholeMinutesUnderAnHourFormatWithoutHours()
    {
        assertEquals( "1m", TuiApp.formatDuration( 60_000 ) );
        assertEquals( "45m", TuiApp.formatDuration( 45L * 60_000 ) );
        assertEquals( "59m", TuiApp.formatDuration( 59L * 60_000 ) );
    }

    @Test
    void durationsAtOrOverAnHourIncludeBothUnits()
    {
        assertEquals( "1h 0m", TuiApp.formatDuration( 60L * 60_000 ) );
        assertEquals( "2h 30m", TuiApp.formatDuration( 150L * 60_000 ) );
    }

    /**
     * Documents the actual (slightly surprising) rounding: partial seconds within the
     * current minute are simply truncated away by integer division, not rounded up — a
     * 90 500 ms session (1m 30.5s) reads as "1m", not "2m".
     */
    @Test
    void partialSecondsWithinAMinuteAreTruncatedNotRounded()
    {
        assertEquals( "1m", TuiApp.formatDuration( 90_500 ) );
    }

    // ========================================================================== truncate

    @Test
    void nullInputReturnsEmptyString()
    {
        assertEquals( "", TuiApp.truncate( null, 10 ) );
    }

    @Test
    void stringAtOrUnderMaxLengthPassesThroughUnchanged()
    {
        assertEquals( "hello", TuiApp.truncate( "hello", 5 ) );
        assertEquals( "hi", TuiApp.truncate( "hi", 10 ) );
    }

    @Test
    void stringOverMaxLengthIsShortenedWithAnEllipsis()
    {
        // "abcdefghij" (10 chars) truncated to max=5 -> first 4 chars + ellipsis.
        assertEquals( "abcd…", TuiApp.truncate( "abcdefghij", 5 ) );
    }

    @Test
    void resultNeverExceedsMaxLength()
    {
        String longName = "A Very Long Modpack Name That Keeps Going";
        String truncated = TuiApp.truncate( longName, 12 );
        assertEquals( 12, truncated.length() );
    }

    /**
     * A {@code max} of zero (or a value too small to fit even the ellipsis) does not throw;
     * {@code Math.max(0, max-1)} clamps the substring bound, so the result degrades to just
     * the ellipsis character rather than raising a {@link StringIndexOutOfBoundsException}.
     */
    @Test
    void zeroMaxLengthDegradesToJustTheEllipsis()
    {
        assertEquals( "…", TuiApp.truncate( "anything", 0 ) );
    }
}
