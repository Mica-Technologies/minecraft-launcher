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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MachineSecretCipher#filterAndSortStableMacs}.
 *
 * <p>The filter is the load-bearing seam that keeps the machine-bound
 * fingerprint stable across launches on macOS, where awdl0 / llw0 /
 * bridge interfaces carry randomized, locally-administered MACs that
 * rotate over time. If this filter ever stops rejecting the LA bit or
 * stops sorting, the symptom is a fleet-wide AEADBadTagException on
 * cached auth files after the first interface reshuffle.</p>
 */
class MachineSecretCipherMacFilterTest
{
    /** Real OUI-administered MAC (LA bit clear, multicast bit clear). */
    private static final byte[] REAL_A = { 0x00, 0x1A, (byte) 0xC0, (byte) 0xFF, (byte) 0xEE, 0x01 };
    private static final byte[] REAL_B = { (byte) 0xA4, (byte) 0x83, (byte) 0xE7, 0x12, 0x34, 0x56 };

    /** Locally-administered MAC (first-octet bit 0x02 set) — the shape awdl0 / llw0 / VM NICs use. */
    private static final byte[] LOCALLY_ADMINISTERED = { 0x02, 0x00, 0x00, 0x00, 0x00, 0x01 };
    private static final byte[] LOCALLY_ADMINISTERED_2 = { 0x76, 0x12, 0x34, 0x56, 0x78, (byte) 0x9A };

    @Test
    void rejectsLocallyAdministeredMacs()
    {
        List< String > result = MachineSecretCipher.filterAndSortStableMacs(
                Arrays.asList( LOCALLY_ADMINISTERED, LOCALLY_ADMINISTERED_2, REAL_A ) );
        assertEquals( List.of( "001ac0ffee01" ), result );
    }

    @Test
    void rejectsMulticastFirstOctet()
    {
        byte[] multicast = { 0x01, 0x00, 0x5E, 0x00, 0x00, 0x01 };
        List< String > result = MachineSecretCipher.filterAndSortStableMacs(
                Arrays.asList( multicast, REAL_A ) );
        assertEquals( List.of( "001ac0ffee01" ), result );
    }

    @Test
    void rejectsAllZeroMac()
    {
        byte[] zeros = new byte[ 6 ];
        List< String > result = MachineSecretCipher.filterAndSortStableMacs(
                Arrays.asList( zeros, REAL_A ) );
        assertEquals( List.of( "001ac0ffee01" ), result );
    }

    @Test
    void rejectsShortAndNullEntries()
    {
        byte[] tooShort = { 0x00, 0x1A, (byte) 0xC0 };
        List< String > result = MachineSecretCipher.filterAndSortStableMacs(
                Arrays.asList( tooShort, null, REAL_A ) );
        assertEquals( List.of( "001ac0ffee01" ), result );
    }

    @Test
    void sortIsDeterministicAcrossInputOrder()
    {
        List< String > forward = MachineSecretCipher.filterAndSortStableMacs(
                Arrays.asList( REAL_A, REAL_B ) );
        List< String > reversed = MachineSecretCipher.filterAndSortStableMacs(
                Arrays.asList( REAL_B, REAL_A ) );
        assertEquals( forward, reversed );
        // Lowest sorts first — this is what deriveMachineKey() consumes as
        // index 0, so it has to be stable regardless of OS enumeration order.
        assertEquals( "001ac0ffee01", forward.get( 0 ) );
    }

    @Test
    void emptyOrNullInputReturnsEmpty()
    {
        assertTrue( MachineSecretCipher.filterAndSortStableMacs( null ).isEmpty() );
        assertTrue( MachineSecretCipher.filterAndSortStableMacs( Collections.emptyList() ).isEmpty() );
    }

    @Test
    void acceptsMultipleRealMacsInOrder()
    {
        List< String > result = MachineSecretCipher.filterAndSortStableMacs(
                Arrays.asList( REAL_B, REAL_A ) );
        assertEquals( List.of( "001ac0ffee01", "a483e7123456" ), result );
    }
}
