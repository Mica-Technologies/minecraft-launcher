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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.rgb.backends.NoOpBackend;
import com.micatechnologies.minecraft.launcher.rgb.backends.asusaura.AsusAuraBackend;
import com.micatechnologies.minecraft.launcher.rgb.backends.chroma.ChromaRestBackend;
import com.micatechnologies.minecraft.launcher.rgb.backends.chromanative.ChromaNativeBackend;
import com.micatechnologies.minecraft.launcher.rgb.backends.corsair.CorsairBackend;
import com.micatechnologies.minecraft.launcher.rgb.backends.dynamiclighting.WindowsDynamicLightingBackend;
import com.micatechnologies.minecraft.launcher.rgb.backends.openrgb.OpenRgbBackend;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the user's RGB-backend configuration into the concrete
 * {@link RgbBackend} instances {@link RgbController} should drive.
 *
 * <p>Lives in its own class — not on {@link RgbController} — so the
 * controller stays free of vendor-specific imports. Adding a future
 * backend (Logitech G HUB, Corsair iCUE, etc.) means editing this
 * file plus shipping the new backend class; the controller and
 * effect engine don't change.</p>
 *
 * <h3>Auto-mode (multi-backend)</h3>
 *
 * <p>When {@code rgbBackend = "auto"} the registry returns every
 * backend whose per-backend enable flag is {@code true} AND whose
 * {@link RgbBackend#isAvailable()} probe succeeds. This is the
 * "mixed-vendor rig" path — a user with Razer Chroma fans plus a
 * Windows-Dynamic-Lighting keyboard gets all of it lit at once
 * instead of having to pick one ecosystem.</p>
 *
 * <p>The per-backend enable flags default true for everything except
 * the Razer Chroma REST backend (deprecated in favor of the native
 * SDK on any working Synapse install). The Settings tab exposes one
 * toggle per backend so users can suppress an ecosystem they don't
 * want the launcher reaching.</p>
 *
 * <h3>Manual mode (single-backend)</h3>
 *
 * <p>An explicit {@code rgbBackend} pick (e.g. "openrgb", "chroma-native")
 * skips the per-backend toggles entirely and returns just that one
 * backend. This is the escape hatch for users debugging a particular
 * SDK or who want guaranteed single-vendor behavior regardless of
 * what else is connected.</p>
 *
 * @since 2026.5
 */
public final class RgbBackendRegistry
{
    private RgbBackendRegistry() { /* static-only */ }

    /**
     * Reads the master-enable flag plus the backend choice from config
     * and returns the list of backends {@link RgbController} should
     * run simultaneously.
     *
     * <p>Returns an empty list when the master toggle is off — the
     * controller then idles without probing any backend. In Auto mode
     * the list may have 0–N entries; in Manual mode it has 0 or 1.</p>
     */
    public static List< RgbBackend > resolveBackendsFromConfig()
    {
        if ( !ConfigManager.getRgbEnable() ) {
            return List.of();
        }

        String choice = ConfigManager.getRgbBackend();
        String c = choice == null ? "" : choice;

        return switch ( c ) {
            case ConfigConstants.RGB_BACKEND_AUTO          -> probeAutoEnabled();
            case ConfigConstants.RGB_BACKEND_OPENRGB       -> List.of( new OpenRgbBackend() );
            case ConfigConstants.RGB_BACKEND_CHROMA        -> List.of( new ChromaRestBackend() );
            case ConfigConstants.RGB_BACKEND_CHROMA_NATIVE -> List.of( new ChromaNativeBackend() );
            case ConfigConstants.RGB_BACKEND_WINDOWS_DL    -> List.of( new WindowsDynamicLightingBackend() );
            case ConfigConstants.RGB_BACKEND_CORSAIR       -> List.of( new CorsairBackend() );
            case ConfigConstants.RGB_BACKEND_ASUS_AURA     -> List.of( new AsusAuraBackend() );
            case ConfigConstants.RGB_BACKEND_NONE          -> List.of();
            default -> {
                Logger.logWarningSilent( LocalizationManager.format( "log.rgb.registry.unrecognizedChoiceAuto", c ) );
                yield probeAutoEnabled();
            }
        };
    }

    /**
     * Legacy single-backend entry point. Picks the first available
     * backend the new multi-backend resolver would have returned, or
     * NoOp when nothing is available. Kept for back-compat with any
     * call site still expecting one backend.
     *
     * @deprecated Use {@link #resolveBackendsFromConfig()} and pass
     *             the resulting list to
     *             {@link RgbController#start(List)} so mixed-vendor
     *             rigs get all of their devices lit instead of just
     *             the first that probes.
     */
    @Deprecated
    public static RgbBackend resolveFromConfig()
    {
        List< RgbBackend > backends = resolveBackendsFromConfig();
        return backends.isEmpty() ? new NoOpBackend() : backends.get( 0 );
    }

    /**
     * Legacy by-name resolver. Kept for tests / debug entry points; the
     * controller no longer reaches this path. Unrecognized names fall
     * back to NoOp with a warning. {@code "auto"} returns the first
     * backend Auto mode would pick — which loses the "run several at
     * once" property, so production code should call
     * {@link #resolveBackendsFromConfig()} instead.
     */
    public static RgbBackend resolve( String choice )
    {
        String c = choice == null ? "" : choice;
        return switch ( c ) {
            case ConfigConstants.RGB_BACKEND_AUTO -> {
                List< RgbBackend > auto = probeAutoEnabled();
                yield auto.isEmpty() ? new NoOpBackend() : auto.get( 0 );
            }
            case ConfigConstants.RGB_BACKEND_OPENRGB       -> new OpenRgbBackend();
            case ConfigConstants.RGB_BACKEND_CHROMA        -> new ChromaRestBackend();
            case ConfigConstants.RGB_BACKEND_CHROMA_NATIVE -> new ChromaNativeBackend();
            case ConfigConstants.RGB_BACKEND_WINDOWS_DL    -> new WindowsDynamicLightingBackend();
            case ConfigConstants.RGB_BACKEND_CORSAIR       -> new CorsairBackend();
            case ConfigConstants.RGB_BACKEND_ASUS_AURA     -> new AsusAuraBackend();
            case ConfigConstants.RGB_BACKEND_NONE          -> new NoOpBackend();
            default -> {
                Logger.logWarningSilent( LocalizationManager.format( "log.rgb.registry.unrecognizedChoiceNoOp", c ) );
                yield new NoOpBackend();
            }
        };
    }

    /**
     * Probe every backend whose per-backend enable flag is on; return
     * the ones whose {@code isAvailable()} succeeds. Each probe is
     * bounded by the backend's own {@code isAvailable} timeout (~500 ms
     * each), so worst-case auto-resolve time is the sum across enabled
     * backends.
     *
     * <p>Probe order matters even though everything available will run:
     * the FIRST entry in the returned list is what the Settings status
     * chip names as the "primary" connected backend and the order frame
     * dispatch iterates. Order chosen so the most broadly-supported /
     * most reliable backend leads — OpenRGB, then the native Chroma
     * SDK, then Windows Dynamic Lighting, then the deprecated Chroma
     * REST as a last-resort fallback.</p>
     */
    private static List< RgbBackend > probeAutoEnabled()
    {
        List< Candidate > candidates = List.of(
                new Candidate( new OpenRgbBackend(),                 ConfigManager.getRgbEnableOpenRgb() ),
                new Candidate( new ChromaNativeBackend(),            ConfigManager.getRgbEnableChromaNative() ),
                new Candidate( new WindowsDynamicLightingBackend(),  ConfigManager.getRgbEnableWindowsDl() ),
                new Candidate( new CorsairBackend(),                 ConfigManager.getRgbEnableCorsair() ),
                new Candidate( new AsusAuraBackend(),                ConfigManager.getRgbEnableAsusAura() ),
                new Candidate( new ChromaRestBackend(),              ConfigManager.getRgbEnableChromaRest() )
        );
        return filterCandidates( candidates );
    }

    /** One enable-flag-paired backend candidate. Package-private so the
     *  unit tests can build candidate lists from stub backends without
     *  pulling in the real vendor SDK classes. */
    record Candidate( RgbBackend backend, boolean enabled ) {}

    /**
     * Pure filter pass over {@code candidates}: keep the backend when
     * its enable flag is true AND its {@code isAvailable()} succeeds.
     * Order is preserved. {@code isAvailable()} throwing is treated
     * the same as returning false (skip + log).
     *
     * <p>Package-private to give the unit tests a seam they can drive
     * with stub backends instead of having to install real vendor SDKs
     * on the test runner.</p>
     */
    static List< RgbBackend > filterCandidates( List< Candidate > candidates )
    {
        List< RgbBackend > selected = new ArrayList<>( candidates.size() );
        for ( Candidate cand : candidates ) {
            if ( !cand.enabled() ) {
                Logger.logDebug( LocalizationManager.format( "log.rgb.registry.probeSkippedDisabled", cand.backend().name() ) );
                continue;
            }
            boolean available;
            try {
                available = cand.backend().isAvailable();
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.rgb.registry.probeIsAvailableThrew", cand.backend().name() ), t );
                continue;
            }
            if ( available ) {
                Logger.logStd( LocalizationManager.format( "log.rgb.registry.probeIncluding", cand.backend().name() ) );
                selected.add( cand.backend() );
            }
        }
        if ( selected.isEmpty() ) {
            Logger.logStd( LocalizationManager.get( "log.rgb.registry.probeNoneAvailable" ) );
        }
        return selected;
    }
}
