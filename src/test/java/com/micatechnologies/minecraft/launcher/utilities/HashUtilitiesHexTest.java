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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for the in-memory hex digest helpers added to {@link HashUtilities}.
 * Pins the well-known SHA-1 / SHA-256 vectors and — crucially — the
 * leading-zero behavior of {@link HashUtilities#bytesToHex(byte[])}, since the
 * whole point of the shared helper is that hand-rolled {@code %02x} loops and
 * the {@code File}-based {@code %0Nx} padding all render identically.
 *
 * @since 2026.6
 */
class HashUtilitiesHexTest
{
    @Test
    void bytesToHex_isLowerCaseTwoCharsPerByte_withLeadingZeros()
    {
        assertEquals( "000f10ff", HashUtilities.bytesToHex( new byte[] { 0x00, 0x0f, 0x10, (byte) 0xff } ) );
        assertEquals( "", HashUtilities.bytesToHex( new byte[ 0 ] ) );
    }

    @Test
    void sha256Hex_matchesKnownVectors()
    {
        assertEquals( "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                      HashUtilities.sha256Hex( "" ) );
        assertEquals( "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                      HashUtilities.sha256Hex( "abc" ) );
    }

    @Test
    void sha1Hex_matchesKnownVector()
    {
        assertEquals( "a9993e364706816aba3e25717850c26c9cd0d89d",
                      HashUtilities.sha1Hex( "abc".getBytes( java.nio.charset.StandardCharsets.UTF_8 ) ) );
    }

    @Test
    void sha256Hex_isAlways64HexChars()
    {
        // Regression guard against the leading-zero-stripping bug that a
        // BigInteger-without-padding implementation would introduce.
        for ( String s : new String[] { "", "a", "hello world", "éèê" } ) {
            assertEquals( 64, HashUtilities.sha256Hex( s ).length() );
        }
    }
}
