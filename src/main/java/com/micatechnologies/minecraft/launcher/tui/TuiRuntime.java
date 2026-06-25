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

import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared TUI session state: the map of currently-running games (keyed by manifest URL) and the
 * derived figures the status bar shows — running count and total playtime across all packs (with a
 * live delta for in-progress sessions). The launcher backend doesn't track "is this pack running",
 * so the TUI owns that bookkeeping via {@link RunningGame}.
 *
 * @since 2026.6
 */
public final class TuiRuntime
{
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private TuiRuntime() { /* static-only */ }

    /**
     * A map of currently-running games, keyed by manifest URL.
     */
    private static final Map< String, RunningGame > RUNNING = new ConcurrentHashMap<>();

    /**
     * Registers a freshly-launched game and starts its readers/watcher. When it exits it removes
     * itself and runs {@code onChange} so the UI can refresh.
     *
     * @param game the running game to register
     * @param onChange the runnable to execute when the game exits
     */
    static void register( RunningGame game, Runnable onChange )
    {
        String key = key( game.pack() );
        RUNNING.put( key, game );
        game.start( () -> {
            RUNNING.remove( key );
            if ( onChange != null ) {
                onChange.run();
            }
        } );
    }

    /**
     * Returns the count of currently running games.
     *
     * @return the number of running games
     */
    public static int runningCount()
    {
        return RUNNING.size();
    }

    /**
     * Checks if a specific game mod pack is currently running.
     *
     * @param pack the game mod pack to check
     * @return true if the game mod pack is running, false otherwise
     */
    public static boolean isRunning( GameModPack pack )
    {
        return pack != null && RUNNING.containsKey( key( pack ) );
    }

    /**
     * Retrieves the running game for a specific game mod pack.
     *
     * @param pack the game mod pack to retrieve the running game for
     * @return the running game, or null if the game mod pack is not running
     */
    public static RunningGame runningFor( GameModPack pack )
    {
        return pack == null ? null : RUNNING.get( key( pack ) );
    }

    /**
     * Returns a snapshot of currently-running games.
     *
     * @return a collection of running games
     */
    public static Collection< RunningGame > running()
    {
        return new ArrayList<>( RUNNING.values() );
    }

    /**
     * Calculates the total playtime across all installed packs plus the live elapsed time of running sessions.
     *
     * @return the total playtime in milliseconds
     */
    public static long liveTotalPlaytimeMs()
    {
        long total = 0;
        List< GameModPack > packs = GameModPackManager.getInstalledModPacks();
        for ( GameModPack p : packs ) {
            total += p.getTotalPlayTimeMs();
        }
        for ( RunningGame g : RUNNING.values() ) {
            total += g.elapsedMs();
        }
        return total;
    }

    /**
     * Generates a key for a game mod pack based on its manifest URL or pack name.
     *
     * @param pack the game mod pack to generate the key for
     * @return the generated key
     */
    private static String key( GameModPack pack )
    {
        String url = pack.getManifestUrl();
        return ( url != null && !url.isBlank() ) ? url : pack.getPackName();
    }
}
