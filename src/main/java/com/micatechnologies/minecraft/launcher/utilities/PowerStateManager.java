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

    private static volatile boolean cachedOnBattery   = false;
    private static volatile long    cacheExpiryNanos  = 0L;

    /** Latches to "true" once we've engaged battery throttling at least once in this session.
     *  Lets a UI layer (Settings panel, future status chip) display "saver active" without
     *  re-querying oshi. */
    private static final AtomicBoolean throttledThisSession = new AtomicBoolean( false );

    private PowerStateManager() { /* static-only */ }

    /**
     * Returns true if the host is currently running on battery power. False when:
     * desktop with no battery; AC plugged in; oshi probe failed (safe-default to "not on battery"
     * so downloads don't get throttled because the probe blew up).
     */
    public static boolean isOnBattery()
    {
        long now = System.nanoTime();
        if ( now < cacheExpiryNanos ) {
            return cachedOnBattery;
        }
        boolean computed = computeOnBattery();
        cachedOnBattery = computed;
        cacheExpiryNanos = now + CACHE_TTL_NANOS;
        return computed;
    }

    /** Returns the remaining battery percentage (0..100) of the first detected internal battery
     *  (UPS sources are skipped — a desktop with a UPS isn't what this is for), or -1 if no
     *  battery is present / the probe fails. Not currently consumed by anything in-launcher;
     *  exposed for a future "battery low" UI cue. */
    public static int getBatteryPercentage()
    {
        try {
            List< PowerSource > sources = new SystemInfo().getHardware().getPowerSources();
            if ( sources == null || sources.isEmpty() ) {
                return -1;
            }
            for ( PowerSource ps : sources ) {
                if ( isInternalBattery( ps ) ) {
                    double pct = ps.getRemainingCapacityPercent();
                    return ( int ) Math.round( pct * 100.0 );
                }
            }
            return -1;
        }
        catch ( Exception | Error e ) {
            return -1;
        }
    }

    /** True if the user has enabled the throttle and the host is currently on battery. The actual
     *  throttling decision in {@link #maybeThrottle(int)}. */
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
        return isOnBattery();
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
            Logger.logDebug( "Battery saver: download throttle engaged at " +
                                     ConfigConstants.BATTERY_THROTTLE_BYTES_PER_SEC + " bytes/s/stream" );
        }
        long bps = ConfigConstants.BATTERY_THROTTLE_BYTES_PER_SEC;
        if ( bps <= 0 ) {
            return;
        }
        long sleepNanos = ( ( long ) chunkBytes * 1_000_000_000L ) / bps;
        if ( sleepNanos <= 0 ) {
            return;
        }
        try {
            long ms = sleepNanos / 1_000_000L;
            int rem = ( int ) ( sleepNanos % 1_000_000L );
            Thread.sleep( ms, rem );
        }
        catch ( InterruptedException ie ) {
            // Restore interrupt; let the caller's own loop notice and abort cleanly.
            Thread.currentThread().interrupt();
        }
    }

    private static boolean computeOnBattery()
    {
        try {
            List< PowerSource > sources = new SystemInfo().getHardware().getPowerSources();
            if ( sources == null || sources.isEmpty() ) {
                return false;
            }
            // Only count internal laptop batteries. A desktop with a UPS still appears here as
            // a PowerSource — but a UPS is for outage ride-through, not a "save juice" signal,
            // and on some platforms (notably Windows) oshi reports a UPS as !isPowerOnLine even
            // when it's plugged in and at 100%. Filter UPS sources out entirely.
            boolean sawBattery = false;
            for ( PowerSource ps : sources ) {
                if ( !isInternalBattery( ps ) ) {
                    continue;
                }
                sawBattery = true;
                // "On battery" requires the source to be actively discharging — not just
                // "not on AC line". On some hardware a fully-charged battery reports
                // !isPowerOnLine() during a momentary capacity-balance step even when the
                // adapter is plugged in; isDischarging() is the more reliable positive signal.
                if ( ps.isPowerOnLine() || ps.isCharging() || !ps.isDischarging() ) {
                    return false;
                }
            }
            return sawBattery;
        }
        catch ( Exception | Error e ) {
            // Probe failed — safest default is "not on battery" so we never throttle
            // unexpectedly because of an oshi quirk on an obscure platform.
            Logger.logWarningSilent( "Power-state probe failed: " + e.getMessage() );
            return false;
        }
    }

    /** True iff this source looks like an internal laptop battery — i.e. not a UPS or some
     *  other non-laptop power-source entry oshi happens to enumerate. oshi 6.3.1 doesn't
     *  expose a power-source type enum, so we fall back to a name-based check against the
     *  Windows-reported device name / display name and manufacturer. The check is fuzzy by
     *  design: false-negatives (a real laptop battery filtered out) just disable throttling,
     *  which is the safe direction. */
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

    /** Case-insensitive substring match for tokens that strongly indicate a UPS rather than
     *  an internal laptop battery. Names like "APC UPS" / "Back-UPS" / "CyberPower UPS" all
     *  trip the "UPS" check; the manufacturer-only entries cover units whose display name is
     *  just a model code. */
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
