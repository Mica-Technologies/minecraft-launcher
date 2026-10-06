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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LogTrimPolicy} — the bounded-growth arithmetic behind the game
 * console's two log buffers.
 *
 * <p>Why this matters: these caps are the only thing stopping a long modded session from
 * filling the heap with log text. There is an open report of the launcher dying or
 * disappearing after 45+ minutes of gameplay, and unbounded buffer growth is a leading
 * hypothesis. The tests below pin the trim points, the line-boundary behaviour, and — most
 * importantly — the fact that selecting the "unlimited" preset disables display trimming
 * entirely.</p>
 */
class LogTrimPolicyTest
{
    // =========================================================================
    //  Unlimited handling
    // =========================================================================

    /**
     * {@code 0} is the user-selectable "unlimited" preset. This test documents that
     * choosing it turns off display trimming for the whole session, which is the
     * configuration most likely to reproduce the long-session memory growth report.
     */
    @Test
    void zeroMeansUnlimitedAndDisablesTrimming()
    {
        assertTrue( LogTrimPolicy.isUnlimited( 0 ) );
        assertFalse( LogTrimPolicy.shouldTrimDisplay( 10_000_000, 0 ),
                     "the unlimited preset must never trim, no matter how large the buffer" );
    }

    @Test
    void negativeCapsAreTreatedAsUnlimited()
    {
        assertTrue( LogTrimPolicy.isUnlimited( -1 ) );
        assertFalse( LogTrimPolicy.shouldTrimDisplay( 999_999, -1 ) );
    }

    @Test
    void positiveCapIsNotUnlimited()
    {
        assertFalse( LogTrimPolicy.isUnlimited( 1 ) );
    }

    // =========================================================================
    //  Slack
    // =========================================================================

    @Test
    void slackIsAFifthOfTheCapForLargeCaps()
    {
        assertEquals( 2_000, LogTrimPolicy.slackFor( 10_000 ) );
    }

    /**
     * Without the floor, a small cap would give so little slack that the O(n) trim scan
     * would run on nearly every 150 ms flush once the buffer sat at the limit.
     */
    @Test
    void slackIsFlooredForSmallCaps()
    {
        assertEquals( LogTrimPolicy.MIN_SLACK, LogTrimPolicy.slackFor( 100 ) );
        assertEquals( LogTrimPolicy.MIN_SLACK, LogTrimPolicy.slackFor( 0 ) );
    }

    // =========================================================================
    //  Display trim threshold
    // =========================================================================

    @Test
    void doesNotTrimAtOrBelowTheCap()
    {
        assertFalse( LogTrimPolicy.shouldTrimDisplay( 10_000, 10_000 ) );
        assertFalse( LogTrimPolicy.shouldTrimDisplay( 9_999, 10_000 ) );
    }

    @Test
    void doesNotTrimWithinTheSlackWindow()
    {
        // 10_000 cap + 2_000 slack: trimming starts only past 12_000.
        assertFalse( LogTrimPolicy.shouldTrimDisplay( 12_000, 10_000 ) );
    }

    @Test
    void trimsOncePastTheSlackWindow()
    {
        assertTrue( LogTrimPolicy.shouldTrimDisplay( 12_001, 10_000 ) );
    }

    @Test
    void smallCapUsesTheFlooredSlack()
    {
        assertFalse( LogTrimPolicy.shouldTrimDisplay( 150, 100 ) );
        assertTrue( LogTrimPolicy.shouldTrimDisplay( 151, 100 ) );
    }

    // =========================================================================
    //  Display drop offset
    // =========================================================================

    @Test
    void dropOffsetLandsJustAfterTheNthNewline()
    {
        String text = "a\nb\nc\nd\n";
        assertEquals( 2, LogTrimPolicy.displayDropOffset( text, 1 ) );
        assertEquals( 4, LogTrimPolicy.displayDropOffset( text, 2 ) );
        assertEquals( 6, LogTrimPolicy.displayDropOffset( text, 3 ) );
    }

    @Test
    void dropOffsetIsZeroWhenNothingShouldBeDropped()
    {
        assertEquals( 0, LogTrimPolicy.displayDropOffset( "a\nb\n", 0 ) );
        assertEquals( 0, LogTrimPolicy.displayDropOffset( "a\nb\n", -5 ) );
    }

    @Test
    void dropOffsetHandlesNullAndEmptyText()
    {
        assertEquals( 0, LogTrimPolicy.displayDropOffset( null, 3 ) );
        assertEquals( 0, LogTrimPolicy.displayDropOffset( "", 3 ) );
    }

    /**
     * A final line with no trailing newline must not cause the whole buffer to be dropped;
     * the scan stops at the last newline it can find.
     */
    @Test
    void dropOffsetStopsAtTheLastNewlineWhenAskedForMoreLinesThanExist()
    {
        String text = "a\nb\nc";
        int off = LogTrimPolicy.displayDropOffset( text, 99 );
        assertEquals( 4, off );
        assertTrue( off <= text.length(), "offset must never exceed the buffer length" );
    }

    @Test
    void dropOffsetOnTextWithNoNewlinesDropsNothing()
    {
        assertEquals( 0, LogTrimPolicy.displayDropOffset( "single line", 1 ) );
    }

    // =========================================================================
    //  Full-log capture buffer
    // =========================================================================

    @Test
    void fullLogDoesNotTrimBelowTheTrigger()
    {
        StringBuilder buf = new StringBuilder( "a\nb\nc\n" );
        assertEquals( 0, LogTrimPolicy.fullLogDropOffset( buf, 100, 50 ) );
    }

    @Test
    void fullLogDoesNotTrimExactlyAtTheTrigger()
    {
        StringBuilder buf = new StringBuilder();
        while ( buf.length() < 100 ) {
            buf.append( 'x' );
        }
        assertEquals( 0, LogTrimPolicy.fullLogDropOffset( buf, 100, 50 ) );
    }

    /**
     * Past the trigger, the drop point advances forward to the next line boundary so the
     * retained buffer never begins mid-line — a half-line at the top of a crash log is
     * actively misleading.
     */
    @Test
    void fullLogTrimAdvancesToTheNextLineBoundary()
    {
        // 20 chars, newline at index 9 and 19.
        StringBuilder buf = new StringBuilder( "aaaaaaaaa\nbbbbbbbbb\n" );
        // trigger 10, retain 5 -> raw dropTo = 20 - 5 = 15; next newline is at 19.
        assertEquals( 20, LogTrimPolicy.fullLogDropOffset( buf, 10, 5 ) );
    }

    @Test
    void fullLogFallsBackToTheRawOffsetWhenNoNewlineFollows()
    {
        StringBuilder buf = new StringBuilder( "aaaaaaaaaaaaaaaaaaaa" );
        // No newline anywhere: drop exactly to the raw offset rather than retaining more.
        assertEquals( 15, LogTrimPolicy.fullLogDropOffset( buf, 10, 5 ) );
    }

    @Test
    void fullLogHandlesNullBuffer()
    {
        assertEquals( 0, LogTrimPolicy.fullLogDropOffset( null, 10, 5 ) );
    }

    /**
     * Guards the shipped constants: retain must be strictly below trigger, or the buffer
     * would trim on every single appended line once it crossed the threshold.
     */
    @Test
    void retainBelowTriggerYieldsForwardProgress()
    {
        StringBuilder buf = new StringBuilder();
        for ( int i = 0; i < 200; i++ ) {
            buf.append( "line\n" );
        }
        int off = LogTrimPolicy.fullLogDropOffset( buf, 500, 400 );
        assertTrue( off > 0, "a buffer past the trigger must drop something" );
        assertTrue( buf.length() - off <= 400 + 5,
                    "post-trim length must be at or below the retain size (plus one line boundary)" );
    }

    @Test
    void tailLinesKeepsTheLastLinesOnly()
    {
        assertEquals( "c\nd\n", LogTrimPolicy.tailLines( "a\nb\nc\nd\n", 2 ) );
        assertEquals( "c\nd", LogTrimPolicy.tailLines( "a\nb\nc\nd", 2 ), "an unterminated last line counts" );
    }

    @Test
    void tailLinesLeavesShortOrUnlimitedTextAlone()
    {
        String text = "a\nb\n";
        assertEquals( text, LogTrimPolicy.tailLines( text, 2 ) );
        assertEquals( text, LogTrimPolicy.tailLines( text, 10 ) );
        assertEquals( text, LogTrimPolicy.tailLines( text, 0 ) );
        assertEquals( "", LogTrimPolicy.tailLines( null, 5 ) );
        assertEquals( "", LogTrimPolicy.tailLines( "", 5 ) );
    }

    @Test
    void tailStartFindsTheLastLinesFromTheEnd()
    {
        assertEquals( 4, LogTrimPolicy.tailStart( "a\nb\nc\nd\n", 2 ) );
        assertEquals( 4, LogTrimPolicy.tailStart( "a\nb\nc\nd", 2 ), "an unterminated last line counts" );
        assertEquals( 1, LogTrimPolicy.tailStart( "\n\n\n", 2 ) );
        assertEquals( 0, LogTrimPolicy.tailStart( "a\nb\n", 2 ) );
        assertEquals( 0, LogTrimPolicy.tailStart( "a\nb\n", 0 ), "unlimited keeps everything" );
        assertEquals( 0, LogTrimPolicy.tailStart( null, 3 ) );
    }

    @Test
    void paragraphDropOffsetCountsEachDroppedLineAndItsBreak()
    {
        // A text control holding "ab\nc\n\nd\n" has these paragraphs.
        List< String > paragraphs = List.of( "ab", "c", "", "d", "" );
        assertEquals( 3, LogTrimPolicy.paragraphDropOffset( paragraphs, 1 ) );
        assertEquals( 6, LogTrimPolicy.paragraphDropOffset( paragraphs, 3 ) );
        assertEquals( 8, LogTrimPolicy.paragraphDropOffset( paragraphs, 99 ), "never past the text" );
        assertEquals( 0, LogTrimPolicy.paragraphDropOffset( paragraphs, 0 ) );
        assertEquals( 0, LogTrimPolicy.paragraphDropOffset( List.of(), 2 ) );
    }
}
