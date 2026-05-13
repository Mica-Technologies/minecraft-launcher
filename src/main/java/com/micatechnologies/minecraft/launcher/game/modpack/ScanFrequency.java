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

package com.micatechnologies.minecraft.launcher.game.modpack;

/**
 * How often the security scan ({@code Nekodetector} + {@code SupplementalScanner})
 * should run for a given launch. Set globally in Settings → Advanced and
 * optionally overridden per-pack in the modpack-detail-modal Advanced
 * section.
 *
 * <p>The launcher's security scan is the only protection against malicious
 * mods between pack-install and pack-launch — it's load-bearing for the
 * trust model. Letting users dial frequency rather than always-on /
 * always-off acknowledges the perceived-perf-vs-safety tradeoff without
 * making the launcher's defenses optional in a way that hides risk.</p>
 *
 * <h3>Storage</h3>
 *
 * <p>Persisted as the {@link #name()} string in JSON so future additions to
 * the enum don't break existing configs. Per-pack overrides live in a
 * {@code packUrl → name} map; {@code null} / absent entries fall back to
 * the global default.</p>
 *
 * @since 2026.3
 */
public enum ScanFrequency
{
    /** Scan runs on every Play click. Strictest safety, slowest launches.
     *  Today's pre-3.4 behaviour. */
    EVERY_TIME( "Every time" ),

    /** Scan runs at most once per 24h, plus always when the manifest
     *  content has changed. Default for fresh installs — catches a
     *  malicious mod added via manifest update within seconds + within a
     *  day even for "manually-edited mods folder" scenarios. */
    DAILY( "Daily (and on changes)" ),

    /** Scan runs only when the manifest content hash differs from the
     *  hash that was scanned last time. Fast steady-state, but a mod
     *  dropped into the mods folder manually (not via manifest) never
     *  triggers a scan. Power-user choice. */
    ON_CHANGES_ONLY( "On changes only" ),

    /** Scan never runs. Fastest launches; no in-launcher malware
     *  protection. Surfaced in the UI so users who explicitly want to
     *  disable it can do so without the launcher pretending it's still
     *  protected. */
    DISABLED( "Disabled" );

    private final String displayLabel;

    ScanFrequency( String displayLabel ) { this.displayLabel = displayLabel; }

    /** Human-readable label rendered in the combo box. */
    public String displayLabel() { return displayLabel; }

    /** Default value for a fresh install — see the design discussion above.
     *  DAILY balances catching real risk within a day against not slowing
     *  every launch down. */
    public static final ScanFrequency DEFAULT = DAILY;

    /** Parses a stored name, falling back to {@link #DEFAULT} on any
     *  unknown / null value. Used during config reads so a downgraded
     *  launcher reading a newer config doesn't crash. */
    public static ScanFrequency fromNameSafe( String name )
    {
        if ( name == null ) return DEFAULT;
        try {
            return ScanFrequency.valueOf( name );
        }
        catch ( IllegalArgumentException e ) {
            return DEFAULT;
        }
    }

    /**
     * Decides whether the scan should run for this launch.
     *
     * @param frequency             the effective frequency for this pack
     * @param state                 the last-scan state from the sidecar, or null
     * @param currentManifestSha256 hex SHA-256 of the manifest body for this launch
     * @return {@code true} if the scan should run; {@code false} to skip it
     */
    public static boolean shouldScan( ScanFrequency frequency,
                                       VerifyState state,
                                       String currentManifestSha256 )
    {
        if ( frequency == null ) frequency = DEFAULT;
        switch ( frequency ) {
            case DISABLED:
                return false;
            case EVERY_TIME:
                return true;
            case ON_CHANGES_ONLY: {
                if ( state == null || state.lastScannedManifestSha256 == null ) return true;
                if ( currentManifestSha256 == null ) return true;
                return !currentManifestSha256.equalsIgnoreCase( state.lastScannedManifestSha256 );
            }
            case DAILY: {
                if ( state == null ) return true;
                // Manifest change always triggers a scan.
                if ( currentManifestSha256 != null
                        && ( state.lastScannedManifestSha256 == null
                                || !currentManifestSha256.equalsIgnoreCase( state.lastScannedManifestSha256 ) ) ) {
                    return true;
                }
                long age = System.currentTimeMillis() - state.lastScannedAt;
                return age < 0 || age > VerifyState.DEFAULT_TTL_MS;
            }
            default:
                return true;
        }
    }
}
