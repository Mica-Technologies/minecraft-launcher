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
 *     "fileSha256": "ab12cd34ef5678901234567890abcdef1234567890abcdef1234567890abcdef",
 *     "kind":       "LAUNCHER_CREDENTIAL_FILE_REF",
 *     "locator":    "optifine/Installer.updateLauncherJson",
 *     "reason":     "OptiFine's installer adds itself to launcher_profiles.json for the standalone Mojang launcher; dead code in third-party launchers."
 *   }
 * ]
 * </pre>
 *
 * <h3>Matching</h3>
 *
 * <p>An acknowledgement applies to a finding when all three identity fields
 * match exactly (case-insensitive, after trimming):</p>
 *
 * <ul>
 *   <li>{@code fileSha256} — hex SHA-256 of the JAR. Required. A mod update
 *       that changes the JAR's bytes (new version, repackaged) produces a
 *       different hash and the ack stops applying — the new content gets
 *       re-reviewed rather than silently inheriting trust.</li>
 *   <li>{@code kind} — stable rule identifier from
 *       {@link SupplementalScanner.Kind}. Required. Renaming the user-facing
 *       wording of a finding message has no effect on matching because the
 *       kind name is part of the scanner's stable API.</li>
 *   <li>{@code locator} — inner identifier ({@code <class>.<method>} for
 *       content findings, the inner JAR entry path for filename-based
 *       findings). Optional. An empty/missing locator means "any locator
 *       within this file with this kind", which is the right shape for
 *       packs that want to silence every instance of a rule firing inside
 *       a specific JAR.</li>
 * </ul>
 *
 * <p>The {@code reason} field is documentation, not used for matching. It
 * surfaces in the launcher log when the acknowledgement fires so a user or
 * auditor can see the maintainer's stated rationale.</p>
 *
 * @since 2026.3
 */
public final class ScanAcknowledgement
{
    /** Hex SHA-256 of the JAR file this acknowledgement applies to.
     *  Case-insensitive on match. Required: a null/blank value never
     *  matches anything. */
    public String fileSha256;

    /** Stable rule identifier matching one of {@link SupplementalScanner.Kind}
     *  names. Case-insensitive. Required: a null/blank value never matches. */
    public String kind;

    /** Optional inner locator — {@code <class>.<method>} for content
     *  findings, inner JAR entry path for filename-based ones. When blank,
     *  the ack applies to every finding of {@code kind} in the JAR with
     *  {@code fileSha256}. */
    public String locator;

    /** Free-form note from the pack maintainer explaining why this finding
     *  is acceptable. Surfaced in the launcher log when the acknowledgement
     *  fires. Optional. */
    public String reason;

    /**
     * Returns {@code true} when this acknowledgement applies to the given
     * supplemental-scanner finding.
     */
    public boolean matches( SupplementalScanner.Finding finding )
    {
        if ( finding == null ) return false;
        if ( fileSha256 == null || fileSha256.isBlank() ) return false;
        if ( kind == null || kind.isBlank() ) return false;

        String findingSha = finding.fileSha256();
        if ( findingSha == null || findingSha.isBlank() ) return false;
        if ( !fileSha256.trim().equalsIgnoreCase( findingSha.trim() ) ) return false;

        SupplementalScanner.Kind findingKind = finding.kind();
        if ( findingKind == null ) return false;
        if ( !kind.trim().equalsIgnoreCase( findingKind.name() ) ) return false;

        if ( locator != null && !locator.isBlank() ) {
            String findingLoc = finding.locator();
            if ( findingLoc == null ) return false;
            return locator.trim().equalsIgnoreCase( findingLoc.trim() );
        }
        return true;
    }
}
