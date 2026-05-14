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

import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.ModPackUpdateLog;

import java.util.List;

/**
 * Shared sort-key helpers used by both {@link MCLauncherMainGui}'s
 * pack carousel and {@link MCLauncherGameLibraryGui}'s entry grid.
 * Each method returns a {@code long} suitable for a
 * {@link java.util.Comparator#comparingLong} sort — {@link Long#MIN_VALUE}
 * is the "no data" sentinel that sinks an entry to the bottom of a
 * descending sort rather than acting like 1970-01-01.
 *
 * <p>Both GUI controllers used to carry their own near-identical
 * versions of these helpers; A7 in the 2026-05-14 review plan
 * consolidated them here so a single bug fix lands in both.</p>
 *
 * @since 2026.5
 */
public final class LibrarySortKeys
{
    private LibrarySortKeys() { /* static-only */ }

    /** Last-played timestamp in epoch millis, or {@link Long#MIN_VALUE}
     *  when the pack is null or has never been launched. */
    public static long lastPlayed( GameModPack pack )
    {
        if ( pack == null ) return Long.MIN_VALUE;
        return pack.getLastPlayedMs();
    }

    /** Total play time in milliseconds, or {@link Long#MIN_VALUE}
     *  when the pack is null. Zero-play packs sort below ones with
     *  any history but above the no-pack sentinel. */
    public static long totalPlayed( GameModPack pack )
    {
        if ( pack == null ) return Long.MIN_VALUE;
        return pack.getTotalPlayTimeMs();
    }

    /** Newest update-log entry's timestamp in epoch millis, or
     *  {@link Long#MIN_VALUE} when the pack is null, the update log
     *  is empty, or reading the log threw. Useful for "Recently
     *  Updated" sorts.
     *
     *  <p>Reads the update log lazily on each call — fine for sort
     *  comparators which call the key function O(n log n) times in
     *  practice. Cache externally if a hotter use case needs it.</p>
     */
    public static long lastUpdate( GameModPack pack )
    {
        if ( pack == null ) return Long.MIN_VALUE;
        try {
            List< ModPackUpdateLog.Entry > log = ModPackUpdateLog.readEntries( pack );
            if ( log.isEmpty() ) return Long.MIN_VALUE;
            return log.get( 0 ).timestampMs();   // readEntries returns newest-first
        }
        catch ( Exception ignored ) {
            return Long.MIN_VALUE;
        }
    }
}
