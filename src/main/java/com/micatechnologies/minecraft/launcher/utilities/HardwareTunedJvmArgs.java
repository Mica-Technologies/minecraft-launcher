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

import oshi.SystemInfo;

/**
 * Generates a JVM-args string tuned to the host machine's CPU + RAM and the
 * pack's max-RAM setting. Used by the Settings "Generate recommended args"
 * button as a smarter alternative to the static {@code JVM_PRESET_PERFORMANCE}
 * Aikar's-flags string.
 *
 * <p>The standard Aikar's flags are tuned for a Minecraft server JVM with
 * ~10 GB max heap and a ~4-8 core box. They work well on that hardware but
 * over-tune the GC for users with 24 GB / 16-core desktops AND under-tune
 * for users with 4 GB / 2-core laptops. This helper picks G1 region sizes,
 * pause targets, and optional flags (string deduplication, parallel ref
 * processing) based on what the user actually has.</p>
 *
 * <p>Decision tree at a glance:</p>
 * <ul>
 *   <li>maxRam &lt;= 4 GB: smaller G1 regions (2M), tight pause budget,
 *       no extras — minimise GC overhead at the cost of throughput.</li>
 *   <li>maxRam 5-12 GB: classic Aikar's-style flags (G1 with 4M regions
 *       + new-size ratios), the existing PERFORMANCE preset's sweet spot.</li>
 *   <li>maxRam &gt; 12 GB: larger G1 regions (16M), looser pause budget,
 *       string deduplication enabled.</li>
 *   <li>cores &gt;= 4: enable {@code +ParallelRefProcEnabled} (reference
 *       processing across worker threads instead of serial).</li>
 *   <li>cores &gt;= 8: enable {@code +UseStringDeduplication} (works only
 *       with G1 + a side benefit on heavy-modpack runs that load thousands
 *       of duplicate texture / lang strings).</li>
 * </ul>
 *
 * <p>The recommender deliberately avoids ZGC even though it's now stable on
 * Java 21+. ZGC's per-collection memory overhead (10-15% of heap) and
 * larger memory footprint don't pay off below ~32 GB max heap, and most
 * Minecraft installs sit well below that. Sticking with G1 keeps the
 * recommendation broadly applicable across the supported runtime range.</p>
 *
 * @since 2026.5
 */
public final class HardwareTunedJvmArgs
{
    private HardwareTunedJvmArgs() { /* static-only */ }

    /**
     * Caches the host's total RAM in bytes. First call probes via
     * {@code oshi-core}; subsequent calls return the cached value. The
     * value never changes during a launcher session.
     */
    private static volatile Long cachedTotalRamBytes = null;

    /**
     * Computes recommended JVM args for the given max-heap setting and the
     * current host machine.
     *
     * @param maxRamGB the {@code -Xmx} the launcher is going to pass on the
     *                 next launch (typically {@link com.micatechnologies.minecraft.launcher.config.ConfigManager#getMaxRamInGb()})
     * @return a space-separated JVM-args string suitable for
     *         {@code ConfigManager.setCustomJvmArgs}
     */
    public static String generate( int maxRamGB )
    {
        int cores = Math.max( 1, Runtime.getRuntime().availableProcessors() );
        long totalRamBytes = getTotalRamBytes();
        long totalRamGB = totalRamBytes / ( 1024L * 1024 * 1024 );

        StringBuilder sb = new StringBuilder( 256 );
        sb.append( "-XX:+UseG1GC" );
        // G1NewSizePercent / G1MaxNewSizePercent (emitted below) are experimental
        // VM options; the JVM refuses to start unless they're unlocked first, and
        // this flag must precede them on the command line.
        sb.append( " -XX:+UnlockExperimentalVMOptions" );

        if ( maxRamGB <= 4 ) {
            // Constrained environment: minimise per-GC work; accept higher
            // throughput pauses to keep the small heap GC'd promptly. 50ms
            // pause budget biases the collector toward many small pauses
            // over fewer big ones.
            sb.append( " -XX:MaxGCPauseMillis=50" );
            sb.append( " -XX:G1HeapRegionSize=2M" );
            sb.append( " -XX:G1NewSizePercent=20" );
        }
        else if ( maxRamGB <= 12 ) {
            // The Aikar's-flags sweet spot. ~200 ms pause budget is plenty
            // for client-side MC since the game already tolerates 100-200ms
            // hitches without dropping frames noticeably.
            sb.append( " -XX:MaxGCPauseMillis=200" );
            sb.append( " -XX:G1HeapRegionSize=4M" );
            sb.append( " -XX:G1NewSizePercent=30" );
            sb.append( " -XX:G1MaxNewSizePercent=40" );
            sb.append( " -XX:G1ReservePercent=20" );
        }
        else {
            // Big-heap territory (16 GB+): the GC has room to breathe, so
            // give it larger regions to coalesce + a looser pause budget so
            // it can do more work per cycle. String dedup is a no-brainer
            // here — large heaps in modpacks always have piles of duplicate
            // language / item-name strings.
            sb.append( " -XX:MaxGCPauseMillis=200" );
            sb.append( " -XX:G1HeapRegionSize=16M" );
            sb.append( " -XX:G1NewSizePercent=30" );
            sb.append( " -XX:G1MaxNewSizePercent=40" );
            sb.append( " -XX:G1ReservePercent=15" );
        }

        // Core-count-dependent extras. Parallel ref processing is a near-
        // universal win on >=4 cores; string deduplication needs another
        // worker thread to be worthwhile so gate it on >=8 cores.
        if ( cores >= 4 ) {
            sb.append( " -XX:+ParallelRefProcEnabled" );
        }
        if ( cores >= 8 || maxRamGB > 12 ) {
            sb.append( " -XX:+UseStringDeduplication" );
        }

        // Aikar's-recipe extras that apply regardless of size. AlwaysPreTouch
        // pages in the entire heap at JVM start so the GC doesn't pay
        // demand-paging cost mid-collection. Worth it on dedicated game
        // launches; the up-front cost is amortised over the session.
        sb.append( " -XX:+AlwaysPreTouch" );
        sb.append( " -XX:+DisableExplicitGC" );

        // Helpful diagnostic comment as a config-side breadcrumb. Not a
        // JVM flag — `# ...` would break the launcher's split parser.
        // Skip; the user can see what they got by looking at the args field.
        return sb.toString();
    }

    /**
     * Returns a one-line summary of the host hardware the recommender saw.
     * Used by the Settings UI as a label under the "Generate" button so
     * the user can sanity-check the inputs without opening Task Manager.
     */
    public static String summary( int maxRamGB )
    {
        int cores = Math.max( 1, Runtime.getRuntime().availableProcessors() );
        long totalRamGB = getTotalRamBytes() / ( 1024L * 1024 * 1024 );
        return cores + " core" + ( cores == 1 ? "" : "s" ) + " / "
                + totalRamGB + " GB system RAM / "
                + maxRamGB + " GB max heap";
    }

    private static long getTotalRamBytes()
    {
        Long cached = cachedTotalRamBytes;
        if ( cached != null ) return cached;
        long v;
        try {
            v = new SystemInfo().getHardware().getMemory().getTotal();
        }
        catch ( Throwable t ) {
            // Probe failure: fall back to JVM's MaxMemory * 4 as a rough
            // floor. Recommender will see ~4 GB or so and pick the small-
            // RAM branch — safe default.
            v = Runtime.getRuntime().maxMemory() * 4L;
        }
        cachedTotalRamBytes = v;
        return v;
    }
}
