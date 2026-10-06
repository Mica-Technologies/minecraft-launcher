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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The game log's line handling and search ("3 of 41"): splitting text into rows, copying rows
 * back out, finding the next or previous match across lines, and numbering it.
 *
 * @since 2026.10
 */
class GameSessionPaneSearchTest
{
    private static final List< String > LOG = List.of( "warn a", "info b", "warn c", "error d", "warn e" );

    @Test
    void countsEveryMatchAndNumbersTheCurrentOne()
    {
        assertArrayEquals( new int[]{ 1, 3 }, SessionLogView.matchPosition( LOG, "warn", 0, 0 ) );
        assertArrayEquals( new int[]{ 2, 3 }, SessionLogView.matchPosition( LOG, "warn", 2, 0 ) );
        assertArrayEquals( new int[]{ 3, 3 }, SessionLogView.matchPosition( LOG, "warn", 4, 0 ) );
    }

    @Test
    void matchesDoNotOverlap()
    {
        assertArrayEquals( new int[]{ 2, 2 }, SessionLogView.matchPosition( List.of( "aaaa" ), "aa", 0, 2 ) );
    }

    @Test
    void aSingleMatchIsOneOfOne()
    {
        assertArrayEquals( new int[]{ 1, 1 }, SessionLogView.matchPosition( LOG, "error", 3, 0 ) );
    }

    @Test
    void searchIgnoresCase()
    {
        assertArrayEquals( new int[]{ 3, 0 }, SessionLogView.findMatch( LOG, "ERROR", 0, 0, true ) );
        assertArrayEquals( new int[]{ 3, 3 }, SessionLogView.matchPosition( LOG, "WARN", 4, 0 ) );
    }

    @Test
    void forwardFindsTheNextMatchAndWrapsToTheStart()
    {
        // From just past the match on line 0, the next is line 2.
        assertArrayEquals( new int[]{ 2, 0 }, SessionLogView.findMatch( LOG, "warn", 0, 4, true ) );
        // From past the last match, it wraps to the first.
        assertArrayEquals( new int[]{ 0, 0 }, SessionLogView.findMatch( LOG, "warn", 4, 4, true ) );
        // Several matches in one line are visited in turn.
        assertArrayEquals( new int[]{ 0, 3 }, SessionLogView.findMatch( List.of( "ab ab" ), "ab", 0, 2, true ) );
    }

    @Test
    void backwardFindsThePreviousMatchAndWrapsToTheEnd()
    {
        assertArrayEquals( new int[]{ 2, 0 }, SessionLogView.findMatch( LOG, "warn", 4, -1, false ) );
        assertArrayEquals( new int[]{ 4, 0 }, SessionLogView.findMatch( LOG, "warn", 0, -1, false ) );
        assertArrayEquals( new int[]{ 0, 0 }, SessionLogView.findMatch( List.of( "ab ab" ), "ab", 0, 2, false ) );
    }

    @Test
    void noMatchIsNull()
    {
        assertNull( SessionLogView.findMatch( LOG, "fatal", 0, 0, true ) );
        assertNull( SessionLogView.findMatch( List.of(), "warn", 0, 0, true ) );
        assertArrayEquals( new int[]{ 1, 0 }, SessionLogView.matchPosition( LOG, "fatal", 0, 0 ) );
    }

    @Test
    void splitsTextIntoLinesWithoutATrailingEmptyOne()
    {
        assertEquals( List.of( "a", "b" ), SessionLogView.splitLines( "a\nb\n" ) );
        assertEquals( List.of( "a", "b" ), SessionLogView.splitLines( "a\r\nb" ) );
        assertEquals( List.of( "a", "", "b" ), SessionLogView.splitLines( "a\n\nb\n" ) );
        assertEquals( List.of(), SessionLogView.splitLines( "" ) );
        assertEquals( List.of(), SessionLogView.splitLines( null ) );
    }

    @Test
    void copiedTextEndsEveryLineWithABreak()
    {
        assertEquals( "a\nb\n", SessionLogView.joinLines( List.of( "a", "b" ) ) );
        assertEquals( "", SessionLogView.joinLines( List.of() ) );
        assertEquals( "x\ny\n", SessionLogView.joinLines( SessionLogView.splitLines( "x\ny\n" ) ) );
    }
}
