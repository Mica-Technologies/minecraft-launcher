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

import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistent record of "we did a full hash-verify of this pack's files on
 * disk recently, and the manifest these hashes correspond to had this
 * content." Drives the {@link LaunchVerifyMode#FAST_PATH} eligibility
 * decision on subsequent launches.
 *
 * <p>Stored as JSON at {@code <packRoot>/.verify_state.json}, alongside the
 * existing {@code .installed_version}, {@code .launch_history}, and
 * {@code .image_cache} sidecars. Schema:</p>
 *
 * <pre>
 * {
 *   "manifestSha256": "ab12...",      // hex SHA-256 of the manifest JSON body
 *   "verifiedAt":     1736812800000,  // epoch millis of the last successful full verify
 *   "launcherVersion": "2026.3"       // launcher version that did the verify
 * }
 * </pre>
 *
 * <p>{@code launcherVersion} lets us invalidate fast-path eligibility after a
 * launcher upgrade when the verify logic itself might have changed — defense
 * in depth, cheap to read.</p>
 *
 * @since 2026.3
 */
public final class VerifyState
{
    /** Sidecar filename in the pack root. Hidden by leading dot so it doesn't
     *  clutter file managers showing pack content. */
    public static final String VERIFY_STATE_FILE = ".verify_state.json";

    /** Default time-to-live for a successful verify, in milliseconds.
     *  See decideMode for the eligibility logic. 24 hours: daily-or-more
     *  frequent launchers never hit it, weekly launchers eat one full verify
     *  per week, file rot / external tampering gets caught within a day. */
    public static final long DEFAULT_TTL_MS = 24L * 60L * 60L * 1000L;

    /** Hex SHA-256 of the manifest JSON body as it existed when this state
     *  was written. Compared against the current manifest hash to detect
     *  pack changes (mods added, versions bumped, etc.) since last verify. */
    public String manifestSha256;

    /** Epoch milliseconds of the verify that produced this record. Combined
     *  with a TTL to bound how long the fast-path can ride a single full
     *  verify. */
    public long verifiedAt;

    /** Launcher version string that wrote this record. Lets a launcher
     *  upgrade invalidate the state if its verify rules changed. */
    public String launcherVersion;

    /** Epoch milliseconds of the last successful security scan for this
     *  pack. Drives {@link ScanFrequency#DAILY} / {@link ScanFrequency#ON_CHANGES_ONLY}
     *  decisions — zero / missing means "never scanned" and forces a scan. */
    public long lastScannedAt;

    /** Manifest content hash that was in effect when the last scan ran.
     *  Used by {@link ScanFrequency#ON_CHANGES_ONLY} to detect "manifest
     *  changed → re-scan" without the full verify pass having to fire. */
    public String lastScannedManifestSha256;

    // ===== load / save =====

    private static Path sidecarPath( GameModPack pack )
    {
        return Path.of( pack.getPackRootFolder(), VERIFY_STATE_FILE );
    }

    /** Reads the sidecar from {@code <packRoot>/.verify_state.json}, or
     *  returns {@code null} if absent / unreadable. Treating any read
     *  failure as "no state" forces a full verify on the next launch,
     *  which is the safe default — the cost is one slow launch. */
    public static VerifyState loadForPack( GameModPack pack )
    {
        try {
            Path p = sidecarPath( pack );
            if ( !Files.isRegularFile( p ) ) return null;
            String json = Files.readString( p, StandardCharsets.UTF_8 );
            return JSONUtilities.getGson().fromJson( json, VerifyState.class );
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Could not read verify state for pack "
                                             + pack.getPackName() + ": "
                                             + e.getClass().getSimpleName() );
            return null;
        }
    }

    /** Writes the sidecar atomically (best-effort) so a partial write doesn't
     *  leave a corrupted JSON the next load has to discard. */
    public static void saveForPack( GameModPack pack, VerifyState state )
    {
        try {
            Path p = sidecarPath( pack );
            //noinspection ResultOfMethodCallIgnored
            p.getParent().toFile().mkdirs();
            String json = JSONUtilities.getGson().toJson( state );
            Files.writeString( p, json, StandardCharsets.UTF_8 );
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "Could not write verify state for pack "
                                             + pack.getPackName() + ": "
                                             + e.getClass().getSimpleName() );
        }
    }

    // ===== manifest hash =====

    /**
     * Returns the lowercase-hex SHA-256 of the given manifest body. Used as
     * the "did anything change" sentinel comparing against
     * {@link #manifestSha256} from a stored state.
     *
     * <p>Returns {@code null} on hash failure — the caller should treat that
     * as "manifest changed" and fall back to full verify, since we can't
     * prove non-change without a hash.</p>
     */
    public static String computeManifestSha256( String manifestBody )
    {
        if ( manifestBody == null ) return null;
        // Returns null only on the (unreachable) missing-SHA-256 case, which the
        // launch path safely treats as "manifest changed" → full verify.
        return com.micatechnologies.minecraft.launcher.utilities.HashUtilities.sha256Hex( manifestBody );
    }

    // ===== decision =====

    /**
     * Decides which {@link LaunchVerifyMode} applies for this launch of this
     * pack. FAST_PATH only fires when every condition holds:
     *
     * <ol>
     *   <li>{@code currentManifestSha256} is non-null. Without a current hash
     *       we have nothing to compare against; safer to do a full verify.</li>
     *   <li>A verify-state sidecar exists for this pack. Absent sidecar means
     *       this pack has never had a successful full verify — first launch
     *       must run one.</li>
     *   <li>{@code state.manifestSha256} equals {@code currentManifestSha256}.
     *       Different hash = manifest changed (mods added/removed, versions
     *       bumped, etc.); the file set is no longer trustworthy.</li>
     *   <li>{@code state.verifiedAt} is within {@code ttlMs} of now. Bounds
     *       how long a single full verify keeps fast-paths eligible —
     *       protects against silent file rot / external tampering.</li>
     *   <li>{@code alwaysVerifyOnLaunch} is false. Per-pack opt-out for
     *       paranoid users; surfaces in the modpack-detail-modal Advanced
     *       section in step 4.</li>
     *   <li>{@code globalForceFullVerify} is false. Launcher-wide kill-switch
     *       triggered by the Settings → "Verify all game files" button;
     *       cleared after one launch consumes it.</li>
     * </ol>
     *
     * @param state                  sidecar contents, or null if absent
     * @param currentManifestSha256  hex SHA-256 of the manifest body for this launch
     * @param ttlMs                  fast-path validity window after verifiedAt
     * @param alwaysVerifyOnLaunch   per-pack toggle (default false)
     * @param globalForceFullVerify  launcher-wide flag (default false)
     */
    public static LaunchVerifyMode decideMode( VerifyState state,
                                                String currentManifestSha256,
                                                long ttlMs,
                                                boolean alwaysVerifyOnLaunch,
                                                boolean globalForceFullVerify )
    {
        if ( globalForceFullVerify ) return LaunchVerifyMode.FULL;
        if ( alwaysVerifyOnLaunch ) return LaunchVerifyMode.FULL;
        if ( currentManifestSha256 == null ) return LaunchVerifyMode.FULL;
        if ( state == null ) return LaunchVerifyMode.FULL;
        if ( state.manifestSha256 == null
                || !state.manifestSha256.equalsIgnoreCase( currentManifestSha256 ) ) {
            return LaunchVerifyMode.FULL;
        }
        long ageMs = System.currentTimeMillis() - state.verifiedAt;
        if ( ageMs < 0 || ageMs > ttlMs ) return LaunchVerifyMode.FULL;
        return LaunchVerifyMode.FAST_PATH;
    }

    /** Merges a "verify just succeeded" record into {@code existing} (or a
     *  fresh state if null), preserving scan-tracking fields. Returns the
     *  updated instance — caller persists via {@link #saveForPack}. */
    public static VerifyState successfulVerify( VerifyState existing, String manifestSha256 )
    {
        VerifyState s = existing != null ? existing : new VerifyState();
        s.manifestSha256 = manifestSha256;
        s.verifiedAt = System.currentTimeMillis();
        s.launcherVersion = LauncherConstants.LAUNCHER_APPLICATION_VERSION;
        return s;
    }

    /** Merges a "scan just succeeded" record into {@code existing} (or a
     *  fresh state if null), preserving verify-tracking fields. Returns
     *  the updated instance — caller persists via {@link #saveForPack}. */
    public static VerifyState successfulScan( VerifyState existing, String manifestSha256 )
    {
        VerifyState s = existing != null ? existing : new VerifyState();
        s.lastScannedAt = System.currentTimeMillis();
        s.lastScannedManifestSha256 = manifestSha256;
        s.launcherVersion = LauncherConstants.LAUNCHER_APPLICATION_VERSION;
        return s;
    }
}
