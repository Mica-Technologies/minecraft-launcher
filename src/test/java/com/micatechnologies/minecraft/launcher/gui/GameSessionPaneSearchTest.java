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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * The game log's search counter ("3 of 41"): which match the search landed on, and how many
 * there are.
 *
 * @since 2026.10
 */
class GameSessionPaneSearchTest
{
    private static final String LOG = "warn a\ninfo b\nwarn c\nerror d\nwarn e\n";

    @Test
    void countsEveryMatchAndNumbersTheCurrentOne()
    {
        assertArrayEquals( new int[]{ 1, 3 }, GameSessionPane.matchPosition( LOG, "warn", LOG.indexOf( "warn a" ) ) );
        assertArrayEquals( new int[]{ 2, 3 }, GameSessionPane.matchPosition( LOG, "warn", LOG.indexOf( "warn c" ) ) );
        assertArrayEquals( new int[]{ 3, 3 }, GameSessionPane.matchPosition( LOG, "warn", LOG.indexOf( "warn e" ) ) );
    }

    @Test
    void matchesDoNotOverlap()
    {
        assertArrayEquals( new int[]{ 2, 2 }, GameSessionPane.matchPosition( "aaaa", "aa", 2 ) );
    }

    @Test
    void aSingleMatchIsOneOfOne()
    {
        assertArrayEquals( new int[]{ 1, 1 }, GameSessionPane.matchPosition( LOG, "error", LOG.indexOf( "error" ) ) );
    }
}
