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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the {@link MachineSecretCipher} key-fingerprint format
 * ({@code user|os|uuid|install:secret}). The per-install secret is always mixed
 * in, so the key can't be reconstructed from the locally-readable
 * username / OS / hardware-UUID alone.
 *
 * <p>The pre-2026.6 UUID-only fingerprint and its decrypt fallback were removed
 * (the fallback cost a second PBKDF2 derivation on every failed decrypt); blobs
 * written by those builds no longer decrypt and the user re-acquires the secret
 * (re-login / re-enter the CF key).</p>
 *
 * <p>Pure — exercises the package-private {@code assembleFingerprint} seam with
 * no filesystem / hardware coupling.</p>
 */
class MachineSecretCipherFingerprintTest
{
    @Test
    void fingerprintAlwaysAppendsTheInstallSecret()
    {
        assertEquals( "alice|Windows 11|UUID-1234|install:SECRET",
                MachineSecretCipher.assembleFingerprint( "alice", "Windows 11", "UUID-1234", "SECRET" ) );
        // No UUID → empty UUID slot, but the secret is still mixed in.
        assertEquals( "alice|Linux||install:SECRET",
                MachineSecretCipher.assembleFingerprint( "alice", "Linux", "", "SECRET" ) );
    }

    @Test
    void fingerprintIncludesTheInstallSecret()
    {
        String fp = MachineSecretCipher.assembleFingerprint( "alice", "Windows 11", "UUID-1234", "SECRET" );
        assertTrue( fp.contains( "install:SECRET" ),
                "fingerprint must always include the install secret" );
    }

    @Test
    void nullComponentsAreTreatedAsEmpty()
    {
        // Empty user, empty os, empty uuid → three separators, then the secret.
        assertEquals( "|||install:SECRET",
                MachineSecretCipher.assembleFingerprint( null, null, null, "SECRET" ) );
    }
}
