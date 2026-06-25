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

package com.micatechnologies.minecraft.launcher.security;

import com.micatechnologies.minecraft.launcher.files.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Policy gate for modpack-manifest-supplied security-scan exclusions
 * ({@code packScanExclusions}). The exclusion list is attacker-controllable —
 * it deserializes straight from the modpack JSON — and both scan passes
 * (the Nekodetector pass and the {@link SupplementalScanner} heuristics)
 * honor it by skipping matching subtrees. Without this gate, a malicious
 * pack could declare {@code "packScanExclusions": ["mods"]} (or {@code "."})
 * and exempt exactly the folders pack-shipped malware lives in from the
 * launcher's primary malware defense.
 *
 * <p>The policy rejects any exclusion that covers the pack root, contains a
 * path-traversal segment, or whose first path segment is one of the
 * {@linkplain #PROTECTED_ROOTS protected content roots}. Exclusions under
 * launcher-controlled, hash-verified trees ({@code libraries/}, {@code bin/},
 * {@code runtime/}) and pack-custom folders remain allowed — the legitimate
 * use case for the manifest field is silencing false positives on bundled
 * tooling, not exempting mod content.</p>
 *
 * <p>Complementary to (not a replacement for) {@code SupplementalScanner}'s
 * {@code BUILT_IN_EXCLUSIONS}, which ADD launcher-mandated exclusions the
 * manifest can't remove; this class REMOVES manifest exclusions the launcher
 * refuses to honor.</p>
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2026.6
 */
public final class ScanExclusionPolicy
{
    /**
     * Pack-root-relative folders whose contents arrive unverified from the
     * modpack itself and are loaded as code (or as code-adjacent script /
     * datapack content) by the game. A manifest-supplied exclusion is never
     * allowed to cover these — they are exactly where pack-shipped malware
     * lives, so exempting them would let a pack disarm its own scan.
     */
    private static final Set< String > PROTECTED_ROOTS = Set.of(
            "mods",
            "config",
            "scripts",
            "resourcepacks",
            "shaderpacks",
            "kubejs",
            "defaultconfigs" );

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ScanExclusionPolicy() {
    }

    /**
     * Filters an untrusted (manifest-supplied) scan-exclusion list down to the
     * entries the launcher is willing to honor. Rejected entries are logged by
     * name so pack authors notice instead of silently losing their exclusion.
     * Allowed entries pass through in their original form — the scanners apply
     * their own normalization downstream.
     *
     * @param untrusted raw manifest-supplied exclusion list (may be null)
     *
     * @return the subset of {@code untrusted} that is safe to honor
     *
     * @since 1.0
     */
    public static List< String > filterUntrusted( List< String > untrusted )
    {
        if ( untrusted == null || untrusted.isEmpty() ) {
            return Collections.emptyList();
        }
        List< String > allowed = new ArrayList<>( untrusted.size() );
        for ( String exclusion : untrusted ) {
            String rejection = rejectionReason( exclusion );
            if ( rejection != null ) {
                Logger.logWarningSilent(
                        "Ignoring manifest scan exclusion \"" + exclusion + "\": " + rejection );
                continue;
            }
            allowed.add( exclusion );
        }
        return allowed;
    }

    /**
     * Returns a human-readable reason this exclusion must be rejected, or
     * {@code null} when it is safe to honor. Package-private so the unit
     * test can exercise the decision table directly.
     *
     * @param exclusion raw manifest-supplied exclusion entry
     *
     * @return rejection reason, or {@code null} if allowed
     *
     * @since 1.0
     */
    static String rejectionReason( String exclusion )
    {
        String normalized = exclusion == null ? "" : exclusion.trim().replace( '\\', '/' );
        normalized = normalized.toLowerCase( Locale.ROOT );

        // Split into segments, dropping empty ("//", leading/trailing "/")
        // and current-dir (".") segments so "./mods/" and "mods" decide
        // identically.
        List< String > segments = new ArrayList<>();
        for ( String segment : normalized.split( "/" ) ) {
            if ( segment.isEmpty() || segment.equals( "." ) ) {
                continue;
            }
            segments.add( segment );
        }

        if ( segments.isEmpty() ) {
            return "it covers the entire pack root";
        }
        if ( segments.contains( ".." ) ) {
            return "it contains a path traversal segment";
        }
        if ( PROTECTED_ROOTS.contains( segments.get( 0 ) ) ) {
            return "it covers the protected content folder \"" + segments.get( 0 ) + "\"";
        }
        return null;
    }
}
