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

import com.micatechnologies.minecraft.launcher.security.SupplementalScanner;

/**
 * A modpack-manifest entry that silences a single known-OK security-scan
 * finding without disabling the scan or excluding the whole file. Sits
 * between the coarse {@code packScanExclusions} mechanism (skip an entire
 * folder/file) and the per-pack {@link ScanFrequency#DISABLED} option (skip
 * everything) — the file still gets scanned, but matching findings are
 * downgraded to acknowledged-and-logged instead of launch-blocking.
 *
 * <h3>Acknowledgement is heuristic-only</h3>
 *
 * <p>Acknowledgements only apply to findings from the launcher's heuristic
 * supplemental scanner ({@link SupplementalScanner}). Findings from the
 * malware-specific {@code Nekodetector} (e.g. Fractureiser stage 1 / stage 2
 * indicators) cannot be silenced via this mechanism — those are signature-
 * based detectors for known malware, and the right escape hatch if a
 * Nekodetector finding is somehow a false positive is to disable scanning
 * for that pack entirely via the per-pack scan-frequency setting.</p>
 *
 * <h3>Manifest shape</h3>
 *
 * <pre>
 * "packScanAcknowledgements": [
 *   {
 *     "innerSha256": "ab12cd34ef5678901234567890abcdef1234567890abcdef1234567890abcdef",
 *     "kind":        "LAUNCHER_CREDENTIAL_FILE_REF",
 *     "locator":     "optifine/Installer.updateLauncherJson",
 *     "reason":      "OptiFine's installer adds itself to launcher_profiles.json for the standalone Mojang launcher; dead code in third-party launchers."
 *   }
 * ]
 * </pre>
 *
 * <h3>Matching</h3>
 *
 * <p>An acknowledgement applies to a finding when {@code kind} matches and
 * <em>at least one</em> of the hash fields matches. The supported hash
 * fields are:</p>
 *
 * <ul>
 *   <li>{@code innerSha256} — hex SHA-256 of the inner element the finding
 *       points at (the JAR entry's bytes for filename-based findings, the
 *       {@code .class} bytecode for class-content findings). <strong>This is
 *       the recommended form</strong> because it survives outer-mod updates:
 *       repackaging City Super Mod with a new bundled patch leaves
 *       {@code marytts/machinelearning/GMMTrainer.exe} byte-identical, so an
 *       ack keyed on the GMMTrainer.exe inner hash stays valid across every
 *       City Super Mod release. The new content does get re-reviewed because
 *       any byte change to the offending element invalidates the ack —
 *       same trust property as {@link #fileSha256} but at the right scope.</li>
 *   <li>{@code fileSha256} — hex SHA-256 of the JAR file as a whole. Kept
 *       for backwards-compat with the original ack shape and for cases where
 *       a maintainer specifically wants the ack to invalidate on any outer-
 *       JAR change. The drawback: a repackage of the outer mod invalidates
 *       the ack even when the inner element is byte-identical, leading to
 *       the "re-ack the same finding every release" trap.</li>
 * </ul>
 *
 * <p>Setting both hashes is supported and operates as <strong>defense-in-
 * depth</strong>: both must match, so the ack stops applying as soon as
 * either the outer JAR or the inner element changes. Useful when a
 * maintainer wants to lock the ack to a specific outer-JAR build but doesn't
 * want a future build with the same outer hash but a tampered inner entry
 * to silently inherit trust.</p>
 *
 * <p>{@code kind} is required and matched case-insensitively against the
 * stable {@link SupplementalScanner.Kind} name. {@code locator} is optional
 * — when set, must match (case-insensitive). When blank, the ack applies to
 * every finding of the given {@code kind} that satisfies the hash match
 * inside the file.</p>
 *
 * <p>The {@code reason} field is documentation, not used for matching. It
 * surfaces in the launcher log when the acknowledgement fires so a user or
 * auditor can see the maintainer's stated rationale.</p>
 *
 * @since 2026.3
 */
public final class ScanAcknowledgement
{
    /** Hex SHA-256 of the JAR file this acknowledgement applies to. Optional
     *  on new manifests, but at least one of {@code fileSha256} /
     *  {@link #innerSha256} must be set or the ack never matches. Kept for
     *  back-compat and for maintainers who specifically want any outer-JAR
     *  change to invalidate the ack. Case-insensitive. */
    public String fileSha256;

    /** Hex SHA-256 of the inner element the finding points at — the JAR
     *  entry's bytes for filename-based findings, the {@code .class}
     *  bytecode for class-content findings. <strong>Recommended over
     *  {@link #fileSha256}</strong> because it survives outer-mod updates as
     *  long as the embedded artifact / class itself didn't change.
     *  Case-insensitive. May be left blank when the maintainer wants the
     *  strict outer-only behavior. */
    public String innerSha256;

    /** Stable rule identifier matching one of {@link SupplementalScanner.Kind}
     *  names. Case-insensitive. Required: a null/blank value never matches. */
    public String kind;

    /** Optional inner locator — {@code <class>.<method>} for content
     *  findings, inner JAR entry path for filename-based ones. When blank,
     *  the ack applies to every finding of {@code kind} in the JAR that
     *  satisfies the hash match. */
    public String locator;

    /** Free-form note from the pack maintainer explaining why this finding
     *  is acceptable. Surfaced in the launcher log when the acknowledgement
     *  fires. Optional. */
    public String reason;

    /**
     * Returns {@code true} when this acknowledgement applies to the given
     * supplemental-scanner finding. Matching requires the {@code kind} to
     * equal the finding's kind (case-insensitive) and at least one set hash
     * field to match the corresponding finding hash; when both hashes are set
     * both must match. An optional non-blank {@code locator} must also match
     * the finding's locator.
     *
     * @param finding the supplemental-scanner finding to test; {@code null}
     *                never matches
     * @return {@code true} when this acknowledgement silences {@code finding},
     *         {@code false} otherwise
     *
     * @since 2026.3
     */
    public boolean matches( SupplementalScanner.Finding finding )
    {
        if ( finding == null ) return false;

        // Kind is required — drives the "is this the same rule firing?" question.
        if ( kind == null || kind.isBlank() ) return false;
        SupplementalScanner.Kind findingKind = finding.kind();
        if ( findingKind == null ) return false;
        if ( !kind.trim().equalsIgnoreCase( findingKind.name() ) ) return false;

        // At least one hash field must be set on the ack. If both are present
        // (defense-in-depth), both must match — see class-level doc.
        boolean hasFileHash  = fileSha256  != null && !fileSha256.isBlank();
        boolean hasInnerHash = innerSha256 != null && !innerSha256.isBlank();
        if ( !hasFileHash && !hasInnerHash ) return false;

        if ( hasFileHash && !equalsIgnoreCaseTrim( fileSha256, finding.fileSha256() ) ) {
            return false;
        }
        if ( hasInnerHash && !equalsIgnoreCaseTrim( innerSha256, finding.innerSha256() ) ) {
            return false;
        }

        // Locator is optional. When set, it must match the finding's locator.
        if ( locator != null && !locator.isBlank() ) {
            String findingLoc = finding.locator();
            if ( findingLoc == null ) return false;
            return locator.trim().equalsIgnoreCase( findingLoc.trim() );
        }
        return true;
    }

    /** Helper for nullsafe trim+case-insensitive hash comparison. Returns
     *  {@code false} when {@code findingValue} is null/blank (the finding
     *  couldn't compute that hash) so a blanket-null finding can never
     *  satisfy an ack with a concrete hash on the corresponding field. */
    private static boolean equalsIgnoreCaseTrim( String ackValue, String findingValue )
    {
        if ( findingValue == null || findingValue.isBlank() ) return false;
        return ackValue.trim().equalsIgnoreCase( findingValue.trim() );
    }
}
