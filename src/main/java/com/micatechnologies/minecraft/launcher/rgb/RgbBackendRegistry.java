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

package com.micatechnologies.minecraft.launcher.rgb;

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.rgb.backends.NoOpBackend;
import com.micatechnologies.minecraft.launcher.rgb.backends.chroma.ChromaRestBackend;
import com.micatechnologies.minecraft.launcher.rgb.backends.chromanative.ChromaNativeBackend;
import com.micatechnologies.minecraft.launcher.rgb.backends.dynamiclighting.WindowsDynamicLightingBackend;
import com.micatechnologies.minecraft.launcher.rgb.backends.openrgb.OpenRgbBackend;

/**
 * Resolves the user's selected backend identifier
 * ({@code "auto"}/{@code "openrgb"}/{@code "chroma"}/{@code "none"})
 * into an actual {@link RgbBackend} instance.
 *
 * <p>Lives in its own class — not on {@link RgbController} — so the
 * controller stays free of vendor-specific imports. Adding a future
 * backend (Logitech, Corsair, Windows Dynamic Lighting) means editing
 * this file plus shipping the new backend class; the controller and
 * effect engine don't change.</p>
 *
 * <h3>Auto-probe order</h3>
 *
 * <p>When the user picks {@code "auto"} the registry probes backends in
 * priority order and returns the first one whose {@link
 * RgbBackend#isAvailable()} returns true. The order intentionally favors
 * OpenRGB over Razer Chroma even on Razer-only systems — OpenRGB usually
 * supports Razer devices via its built-in detector, runs cross-platform,
 * and breaks less often on vendor driver updates. A user who specifically
 * wants Razer's own SDK can pick {@code "chroma"} explicitly.</p>
 *
 * @since 2026.5
 */
public final class RgbBackendRegistry
{
    private RgbBackendRegistry() { /* static-only */ }

    /**
     * Convenience entry point: read the master enable + backend choice
     * from {@link ConfigManager} and return the resolved backend
     * instance. When the master toggle is off, returns {@link NoOpBackend}
     * without probing anything — keeping the launcher quiet on systems
     * the user hasn't opted in for RGB.
     */
    public static RgbBackend resolveFromConfig()
    {
        if ( !ConfigManager.getRgbEnable() ) {
            return new NoOpBackend();
        }
        return resolve( ConfigManager.getRgbBackend() );
    }

    /**
     * Returns the backend implementation for {@code choice}. Probes for
     * availability when {@code choice} is {@code "auto"}; explicit
     * choices skip the probe so a user who picks Razer specifically gets
     * a Razer attempt even if OpenRGB is also running on the system.
     *
     * <p>An unrecognized {@code choice} string falls back to NoOp — the
     * launcher logs the unrecognized value and stays silent on the
     * hardware. {@link ConfigManager#getRgbBackend()} already filters
     * unknown values, but a defensive default here keeps the contract
     * tight if someone calls this method directly.</p>
     */
    public static RgbBackend resolve( String choice )
    {
        String c = choice == null ? "" : choice;
        return switch ( c ) {
            case ConfigConstants.RGB_BACKEND_AUTO          -> probeAuto();
            case ConfigConstants.RGB_BACKEND_OPENRGB       -> new OpenRgbBackend();
            case ConfigConstants.RGB_BACKEND_CHROMA        -> new ChromaRestBackend();
            case ConfigConstants.RGB_BACKEND_CHROMA_NATIVE -> new ChromaNativeBackend();
            case ConfigConstants.RGB_BACKEND_WINDOWS_DL    -> new WindowsDynamicLightingBackend();
            case ConfigConstants.RGB_BACKEND_NONE          -> new NoOpBackend();
            default -> {
                Logger.logWarningSilent( "Unrecognized RGB backend choice '" + c
                                                 + "' — falling back to NoOp." );
                yield new NoOpBackend();
            }
        };
    }

    /**
     * Probe each known backend in priority order; return the first
     * available, or NoOp if nothing answers. Each probe is bounded by
     * the backend's own {@link RgbBackend#isAvailable} timeout, so the
     * worst-case auto-resolve time is the sum of those — currently
     * ~1s total for the two V1 backends, both at 500ms.
     */
    private static RgbBackend probeAuto()
    {
        // Order matters. OpenRGB first because it covers the most vendors
        // cross-platform and is the easiest "just works" path. Then the
        // native Chroma SDK — same DLL Fortnite/Overwatch use, fully
        // supported by current Synapse, lights up the user's Razer
        // hardware. The REST Chroma backend goes LAST as a fallback for
        // older Synapse installs where the native SDK might not be
        // installed but the REST surface is still responsive.
        RgbBackend[] candidates = {
                new OpenRgbBackend(),
                new ChromaNativeBackend(),
                new WindowsDynamicLightingBackend(),
                new ChromaRestBackend()
        };
        for ( RgbBackend candidate : candidates ) {
            boolean available;
            try {
                available = candidate.isAvailable();
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( "RGB auto-probe: " + candidate.name()
                                                 + " isAvailable() threw — skipping", t );
                continue;
            }
            if ( available ) {
                Logger.logStd( "RGB auto-probe: selected " + candidate.name() );
                return candidate;
            }
        }
        Logger.logStd( "RGB auto-probe: no backend available on this system." );
        return new NoOpBackend();
    }
}
