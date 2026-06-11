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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the {@link MachineSecretCipher} key-fingerprint format. Two
 * properties matter for security + backwards compatibility:
 *
 * <ul>
 *   <li>The <b>current</b> fingerprint always mixes in the per-install secret
 *       (so the key can't be reconstructed from the locally-readable
 *       username / OS / hardware-UUID alone).</li>
 *   <li>The <b>legacy</b> fingerprint is byte-for-byte the pre-2026.6 format
 *       (UUID-only, or the install secret when no UUID) so the decrypt fallback
 *       still unlocks blobs written before the change.</li>
 * </ul>
 *
 * Pure — exercises the package-private {@code assembleFingerprint} seam with no
 * filesystem / hardware coupling.
 */
class MachineSecretCipherFingerprintTest
{
    @Test
    void legacyFingerprintMatchesPre2026_6FormatWithUuid()
    {
        // Old format with a hardware UUID present: user|os|uuid, secret unused.
        assertEquals( "alice|Windows 11|UUID-1234",
                MachineSecretCipher.assembleFingerprint( "alice", "Windows 11", "UUID-1234", "", true ) );
    }

    @Test
    void legacyFingerprintMatchesPre2026_6FormatWithoutUuid()
    {
        // Old format with no UUID fell back to the install secret.
        assertEquals( "alice|Linux|install:SECRET",
                MachineSecretCipher.assembleFingerprint( "alice", "Linux", "", "SECRET", true ) );
    }

    @Test
    void currentFingerprintAlwaysAppendsTheInstallSecret()
    {
        assertEquals( "alice|Windows 11|UUID-1234|install:SECRET",
                MachineSecretCipher.assembleFingerprint( "alice", "Windows 11", "UUID-1234", "SECRET", false ) );
        // No UUID → empty UUID slot, but the secret is still mixed in.
        assertEquals( "alice|Linux||install:SECRET",
                MachineSecretCipher.assembleFingerprint( "alice", "Linux", "", "SECRET", false ) );
    }

    @Test
    void currentFingerprintIncludesSecretWhereLegacyWithUuidDidNot()
    {
        String legacy = MachineSecretCipher.assembleFingerprint( "alice", "Windows 11", "UUID-1234", "SECRET", true );
        String current = MachineSecretCipher.assembleFingerprint( "alice", "Windows 11", "UUID-1234", "SECRET", false );

        // Legacy-with-UUID must NOT carry the secret (that's the weakness fixed);
        // current always must — and the two derive different keys.
        assertFalse( legacy.contains( "SECRET" ),
                "legacy UUID fingerprint must not include the install secret" );
        assertTrue( current.contains( "install:SECRET" ),
                "current fingerprint must always include the install secret" );
        assertNotEquals( legacy, current );
    }

    @Test
    void nullComponentsAreTreatedAsEmpty()
    {
        // Empty user, empty os, empty uuid → three separators, then the secret.
        assertEquals( "|||install:SECRET",
                MachineSecretCipher.assembleFingerprint( null, null, null, "SECRET", false ) );
    }
}
