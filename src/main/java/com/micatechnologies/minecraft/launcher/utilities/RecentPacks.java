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

import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Helper for resolving the user's recently-played modpacks. Used by the
 * OS-shell integration surfaces (Windows jump list, Linux {@code .desktop}
 * {@code Actions=} field) that surface a "Recent Modpacks" affordance
 * outside the launcher window.
 *
 * <p>"Recent" here means installed modpacks with a non-zero last-played
 * timestamp, sorted descending — the same ordering the main menu uses
 * for its default Last-Played sort. Never-played packs are excluded so
 * the jump list doesn't fill with every installed entry on a fresh
 * machine.</p>
 *
 * @since 2026.5
 */
public final class RecentPacks
{
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private RecentPacks() { /* static-only */ }

    /**
     * Returns up to {@code max} installed modpacks ordered by most-recently-
     * played first. Packs that have never been launched are filtered out.
     *
     * <p>Lock-free: pulls a snapshot from {@link GameModPackManager#getInstalledModPacks()}
     * which is itself lock-free against in-flight background fetches, then
     * sorts the snapshot in this caller's thread. Safe to call from any
     * thread.</p>
     *
     * @param max maximum number of packs to return; values {@code <= 0} return an empty list
     * @return immutable list, newest first, with size {@code <= max}
     */
    public static List< GameModPack > getRecent( int max )
    {
        if ( max <= 0 ) return List.of();
        List< GameModPack > installed = GameModPackManager.getInstalledModPacks();
        if ( installed == null || installed.isEmpty() ) return List.of();
        List< GameModPack > played = new ArrayList<>();
        for ( GameModPack p : installed ) {
            if ( p == null ) continue;
            try {
                if ( p.getLastPlayedMs() > 0L ) {
                    played.add( p );
                }
            }
            catch ( Throwable ignored ) {
                // Metadata read can fail on a half-loaded pack stub; skip
                // gracefully rather than letting one bad entry tank the
                // whole jump-list refresh.
            }
        }
        played.sort( Comparator.comparingLong(
                ( GameModPack p ) -> p.getLastPlayedMs() ).reversed() );
        if ( played.size() > max ) {
            return List.copyOf( played.subList( 0, max ) );
        }
        return List.copyOf( played );
    }
}
