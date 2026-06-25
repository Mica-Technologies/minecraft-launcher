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

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import oshi.SystemInfo;
import oshi.hardware.PowerSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cross-platform power-state probe and download throttle. Wraps {@code oshi-core}'s
 * {@link PowerSource} discovery to detect whether the host is currently on battery, and
 * exposes a {@link #maybeThrottle(int)} hook that download streams call after each chunk
 * to slow themselves to the configured per-stream cap when on battery.
 *
 * <p>Desktop hosts (no enumerable power source, or all sources reporting AC) always read as
 * "not on battery" and the throttle is dormant. Laptop hosts on AC power are also dormant.
 * Only when every detected source reports {@code !isPowerOnLine()} does the throttle engage,
 * and only if the user hasn't disabled it via
 * {@link ConfigManager#getBatteryThrottleEnable()}.</p>
 *
 * <p>The on-battery state is cached for {@value #CACHE_TTL_NANOS}ns (~5 s) so that
 * {@link #maybeThrottle(int)} firing once per 8 KiB chunk doesn't hammer the underlying OS
 * power-source query. Five seconds is short enough that plugging in mid-download is felt
 * within a couple of progress-bar ticks.</p>
 */
public final class PowerStateManager
{
    /** Cache TTL for the on-battery probe — five seconds. Long enough to keep per-chunk overhead
     *  trivial, short enough that "I just plugged in" registers within a couple progress-bar pulses. */
    private static final long CACHE_TTL_NANOS = 5L * 1_000_000_000L;

    /**
     * Cached flag indicating whether the host is currently on battery power.
     */
    private static volatile boolean cachedOnBattery   = false;

    /**
     * Cached battery percentage of the first detected internal battery.
     */
    private static volatile int     cachedBatteryPct  = -1;

    /**
     * Expiry time for the cached power state in nanoseconds.
     */
    private static volatile long    cacheExpiryNanos  = 0L;

    /** Latches to "true" once we've engaged battery throttling at least once in this session.
     *  Lets a UI layer (Settings panel, future status chip) display "saver active" without
     *  re-querying oshi. */
    private static final AtomicBoolean throttledThisSession = new AtomicBoolean( false );

    /** Don't bother sleeping for a deficit smaller than this — short sleeps are dominated by OS
     *  timer granularity and wakeup latency (worst on battery, where the powersave governor and
     *  timer coalescing inflate every {@code Thread.sleep}). Below the floor we let the deficit
     *  accumulate into a later chunk so each sleep we *do* issue is long enough to be accurate. */
    private static final long MIN_SLEEP_NANOS = 12L * 1_000_000L;

    /** If a stream hasn't called in for longer than this, treat its accounting window as stale
     *  (paused mid-download, or throttling was disengaged and re-engaged) and start fresh rather
     *  than trying to "catch up" on a gap that wasn't real download time. */
    private static final long STALE_WINDOW_NANOS = 2L * 1_000_000_000L;

    /** Per-stream throttle accounting, indexed by the calling download thread. Each entry is
     *  {@code [windowStartNanos, bytesInWindow]}: a sliding ~1-second window used to compute how
     *  far ahead of the target rate this stream is running. Per-thread because the throttle is
     *  per-stream and threads each drive their own download. */
    private static final ThreadLocal< long[] > THROTTLE_STATE =
            ThreadLocal.withInitial( () -> new long[]{ 0L, 0L } );

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private PowerStateManager() { /* static-only */ }

    /**
     * Returns true if the host is currently running on battery power. False when:
     * desktop with no battery; AC plugged in; oshi probe failed (safe-default to "not on battery"
     * so downloads don't get throttled because the probe blew up).
     *
     * @return true if the host is on battery, false otherwise
     */
    public static boolean isOnBattery()
    {
        refreshCacheIfStale();
        return cachedOnBattery;
    }

    /** Returns the remaining battery percentage (0..100) of the first detected internal battery
     *  (UPS sources are skipped — a desktop with a UPS isn't what this is for), or -1 if no
     *  battery is present / the probe fails. */
    public static int getBatteryPercentage()
    {
        refreshCacheIfStale();
        return cachedBatteryPct;
    }

    /** True if the user has enabled the throttle, the host is on battery, AND the battery is
     *  low enough that saving juice actually matters. Above
     *  {@link ConfigConstants#BATTERY_THROTTLE_PCT_THRESHOLD}% there's plenty of charge to
     *  finish an install, so we don't strangle downloads. If the percentage can't be read we
     *  fall back to the historical "throttle whenever on battery" behavior. */
    public static boolean shouldThrottleDownloads()
    {
        try {
            if ( !ConfigManager.getBatteryThrottleEnable() ) {
                return false;
            }
        }
        catch ( Exception e ) {
            // Config not initialized yet (very early in startup) — assume default-on.
        }
        if ( !isOnBattery() ) {
            return false;
        }
        int pct = getBatteryPercentage();
        if ( pct < 0 ) {
            return true;
        }
        return pct < ConfigConstants.BATTERY_THROTTLE_PCT_THRESHOLD;
    }

    /** Reports whether the throttle has fired at least once in this launcher session. Lets a UI
     *  layer surface "Battery saver active" without re-running the probe. */
    public static boolean wasThrottledThisSession()
    {
        return throttledThisSession.get();
    }

    /**
     * Per-chunk hook called by {@code NetworkUtilities} byte-tracking download. If throttling is
     * engaged, sleeps just long enough that the calling stream averages
     * {@link ConfigConstants#BATTERY_THROTTLE_BYTES_PER_SEC} per second.
     *
     * <p>This is a per-stream throttle — total launcher bandwidth on battery is roughly
     * {@code (parallel-downloads) × cap}. With the typical 4-8 parallel library/asset downloads
     * during a modpack install, that's 2-4 MiB/s aggregate, which is the design intent.</p>
     *
     * <p>Cheap when not throttling: one config read + one volatile read + one nanoTime call.
     * The expensive oshi probe behind {@link #isOnBattery()} runs at most once per
     * {@value #CACHE_TTL_NANOS}ns regardless of chunk frequency.</p>
     *
     * @param chunkBytes number of bytes just written by the caller
     */
    public static void maybeThrottle( int chunkBytes )
    {
        if ( chunkBytes <= 0 ) {
            return;
        }
        if ( !shouldThrottleDownloads() ) {
            return;
        }
        if ( throttledThisSession.compareAndSet( false, true ) ) {
            Logger.logDebug( LocalizationManager.format( "log.powerState.throttleEngaged",
                                     ConfigConstants.BATTERY_THROTTLE_BYTES_PER_SEC ) );
        }
        long bps = ConfigConstants.BATTERY_THROTTLE_BYTES_PER_SEC;
        if ( bps <= 0 ) {
            return;
        }

        long now       = System.nanoTime();
        long sleepNanos = nextThrottleSleepNanos( THROTTLE_STATE.get(), now, chunkBytes, bps );
        if ( sleepNanos > 0 ) {
            try {
                Thread.sleep( sleepNanos / 1_000_000L, ( int ) ( sleepNanos % 1_000_000L ) );
            }
            catch ( InterruptedException ie ) {
                // Restore interrupt; let the caller's own loop notice and abort cleanly.
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Pure decision core of the deadline (closed-loop) throttle, split out from
     * {@link #maybeThrottle(int)} so it can be unit-tested with a synthetic clock — it issues no
     * {@link System#nanoTime()} or {@link Thread#sleep} calls of its own.
     *
     * <p>Given a per-stream accounting window {@code st} (a two-element array
     * {@code [windowStartNanos, bytesInWindow]}), the current clock reading, the chunk just
     * written, and the per-stream byte/sec cap, it updates the window in place and returns how
     * long the caller should sleep to hold the stream at or below {@code bps} (0 = don't sleep).</p>
     *
     * <p>We compare the <em>target</em> elapsed time for the bytes pushed so far against the
     * <em>actual</em> elapsed time and sleep only the real deficit. This self-corrects: when a
     * prior {@code Thread.sleep} overshoots — which it routinely does on battery, where timer
     * coalescing and the powersave governor inflate every sleep — the actual clock is already
     * ahead, so the next deficits shrink or vanish and the average converges back to the cap. A
     * blind {@code sleep(chunkBytes / bps)} per chunk can't do this: it pays the full overshoot on
     * every one of the ~64 chunks/sec and collapses the stream to a crawl.</p>
     *
     * <p>Sub-{@link #MIN_SLEEP_NANOS} deficits return 0 and roll into a later chunk, so every sleep
     * we actually issue is coarse enough to ride out OS timer granularity. The window is reset on
     * first use, after a {@link #STALE_WINDOW_NANOS} gap (paused / re-engaged), and every ~1 second
     * of data — the last bound keeps the byte counter from overflowing on multi-GB downloads and
     * sheds accumulated rounding so a mid-download rate change re-converges within a second.</p>
     *
     * @param st         per-stream window {@code [windowStartNanos, bytesInWindow]}, mutated in place
     * @param nowNanos   current monotonic clock reading (e.g. {@link System#nanoTime()})
     * @param chunkBytes bytes just written by the caller
     * @param bps        per-stream cap in bytes/second
     *
     * @return nanoseconds the caller should sleep, or 0 to proceed without sleeping
     */
    static long nextThrottleSleepNanos( long[] st, long nowNanos, int chunkBytes, long bps )
    {
        if ( bps <= 0 || chunkBytes <= 0 ) {
            return 0L;
        }
        if ( st[ 0 ] == 0L || nowNanos - st[ 0 ] > STALE_WINDOW_NANOS ) {
            // Fresh or stale window — start counting from here.
            st[ 0 ] = nowNanos;
            st[ 1 ] = 0L;
        }
        st[ 1 ] += chunkBytes;

        long targetElapsedNanos = ( st[ 1 ] * 1_000_000_000L ) / bps;
        long deficitNanos       = targetElapsedNanos - ( nowNanos - st[ 0 ] );
        long sleepNanos         = deficitNanos >= MIN_SLEEP_NANOS ? deficitNanos : 0L;

        if ( st[ 1 ] >= bps ) {
            // Project past the sleep we're about to advise so the next window's baseline lines up
            // with when the caller actually resumes reading.
            st[ 0 ] = nowNanos + sleepNanos;
            st[ 1 ] = 0L;
        }
        return sleepNanos;
    }

    /**
     * Refreshes the cached power state if it has expired.
     */
    private static void refreshCacheIfStale()
    {
        long now = System.nanoTime();
        if ( now < cacheExpiryNanos ) {
            return;
        }
        probeAndCache();
        cacheExpiryNanos = now + CACHE_TTL_NANOS;
    }

    /**
     * Probes the system for power sources and caches the results.
     */
    private static void probeAndCache()
    {
        boolean onBattery = false;
        int pct = -1;
        try {
            List< PowerSource > sources = new SystemInfo().getHardware().getPowerSources();
            if ( sources != null && !sources.isEmpty() ) {
                // Only count internal laptop batteries. A desktop with a UPS still appears here as
                // a PowerSource — but a UPS is for outage ride-through, not a "save juice" signal,
                // and on some platforms (notably Windows) oshi reports a UPS as !isPowerOnLine even
                // when it's plugged in and at 100%. Filter UPS sources out entirely.
                boolean sawBattery = false;
                boolean anyOnAc = false;
                for ( PowerSource ps : sources ) {
                    if ( !isInternalBattery( ps ) ) {
                        continue;
                    }
                    sawBattery = true;
                    if ( pct < 0 ) {
                        try {
                            pct = ( int ) Math.round( ps.getRemainingCapacityPercent() * 100.0 );
                        }
                        catch ( Exception | Error ignored ) {
                            // pct stays -1
                        }
                    }
                    // "On battery" requires the source to be actively discharging — not just
                    // "not on AC line". On some hardware a fully-charged battery reports
                    // !isPowerOnLine() during a momentary capacity-balance step even when the
                    // adapter is plugged in; isDischarging() is the more reliable positive signal.
                    if ( ps.isPowerOnLine() || ps.isCharging() || !ps.isDischarging() ) {
                        anyOnAc = true;
                    }
                }
                onBattery = sawBattery && !anyOnAc;
            }
        }
        catch ( Exception | Error e ) {
            // Probe failed — safest default is "not on battery" so we never throttle
            // unexpectedly because of an oshi quirk on an obscure platform.
            Logger.logWarningSilent( LocalizationManager.format( "log.powerState.probeFailed", e.getMessage() ) );
            onBattery = false;
            pct = -1;
        }
        cachedOnBattery = onBattery;
        cachedBatteryPct = pct;
    }

    /**
     * Determines if a power source is an internal laptop battery.
     *
     * @param ps the power source to check
     * @return true if the power source is an internal battery, false otherwise
     */
    private static boolean isInternalBattery( PowerSource ps )
    {
        try {
            if ( containsUpsSignal( ps.getName() ) ) {
                return false;
            }
            if ( containsUpsSignal( ps.getDeviceName() ) ) {
                return false;
            }
            if ( containsUpsSignal( ps.getManufacturer() ) ) {
                return false;
            }
            return true;
        }
        catch ( Exception | Error e ) {
            // If we can't classify, conservatively treat it as NOT an internal battery so
            // we don't throttle a desktop user because of an unknown source.
            return false;
        }
    }

    /**
     * Checks if the given string contains any UPS-related signals.
     *
     * @param s the string to check
     * @return true if the string contains UPS signals, false otherwise
     */
    private static boolean containsUpsSignal( String s )
    {
        if ( s == null || s.isEmpty() ) {
            return false;
        }
        String upper = s.toUpperCase();
        return upper.contains( "UPS" )
                || upper.contains( "APC " )
                || upper.contains( "CYBERPOWER" )
                || upper.contains( "TRIPP LITE" )
                || upper.contains( "TRIPPLITE" )
                || upper.contains( "EATON" )
                || upper.contains( "MINUTEMAN" );
    }
}
