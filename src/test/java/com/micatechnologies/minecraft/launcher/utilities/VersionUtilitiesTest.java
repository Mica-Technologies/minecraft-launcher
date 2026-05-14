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

/**
 * {@link VersionUtilities#compareVersionNumbers} is invoked on the FX
 * thread during main-menu card rebuilds — any exception thrown here
 * kills the entire menu render and leaves the launcher unable to
 * return from a game launch. The original implementation called
 * {@link Integer#parseInt} on each dot segment, which exploded on
 * Forge-style {@code "14.23.5.2855_230729ER"} build-metadata suffixes
 * and other non-numeric segments. This suite locks in the
 * non-throwing behaviour added for that regression.
 */
class VersionUtilitiesTest
{
    @Test
    void semverSuffixesAreStripped()
    {
        // "+build" SemVer build metadata is ignored.
        assertEquals( 0, VersionUtilities.compareVersionNumbers( "1.2.3", "1.2.3+abc" ) );
        // "-pre" SemVer prerelease tag is ignored at the strip layer.
        assertEquals( 0, VersionUtilities.compareVersionNumbers( "1.2.3", "1.2.3-rc1" ) );
        // Both forms together — dash applied before plus.
        assertEquals( 0, VersionUtilities.compareVersionNumbers(
                "2026.1.0-15-gabc1234.dirty", "2026.1.0" ) );
    }

    @Test
    void underscoreBuildMetadataIsStripped()
    {
        // Forge / modpack-tooling convention: build date suffix after
        // an underscore. The original regression report had
        // installed=remote="14.23.5.2855_230729ER" — both must strip
        // to "14.23.5.2855" and compare equal.
        assertEquals( 0, VersionUtilities.compareVersionNumbers(
                "14.23.5.2855_230729ER", "14.23.5.2855_230729ER" ) );
        // Same numeric prefix, different metadata suffix → still equal.
        assertEquals( 0, VersionUtilities.compareVersionNumbers(
                "14.23.5.2855_230729ER", "14.23.5.2855_231005AB" ) );
        // Newer numeric Forge build with metadata beats older without.
        assertEquals( -1, VersionUtilities.compareVersionNumbers(
                "14.23.5.2854", "14.23.5.2855_230729ER" ) );
    }

    @Test
    void leadingDigitsExtractedFromMixedSegments()
    {
        // If a segment slips through stripSuffixes (e.g. an inline
        // tag between numeric segments), the per-segment parser pulls
        // the leading digit run and uses that — never throws.
        assertEquals( 0, VersionUtilities.compareVersionNumbers( "1.2rc1.3", "1.2.3" ) );
        // Pure non-numeric segment falls back to 0.
        assertEquals( 0, VersionUtilities.compareVersionNumbers( "1.alpha.3", "1.0.3" ) );
    }

    @Test
    void basicNumericOrdering()
    {
        assertEquals(  0, VersionUtilities.compareVersionNumbers( "1.2.3", "1.2.3" ) );
        assertEquals( -1, VersionUtilities.compareVersionNumbers( "1.2.3", "1.2.4" ) );
        assertEquals(  1, VersionUtilities.compareVersionNumbers( "1.2.4", "1.2.3" ) );
        assertEquals( -1, VersionUtilities.compareVersionNumbers( "1.9.0", "1.10.0" ) );
        assertEquals(  1, VersionUtilities.compareVersionNumbers( "2.0",   "1.99.99" ) );
    }

    @Test
    void mismatchedSegmentCounts()
    {
        // Shorter string treated as having implicit trailing zeros.
        assertEquals(  0, VersionUtilities.compareVersionNumbers( "1.0",   "1.0.0" ) );
        assertEquals( -1, VersionUtilities.compareVersionNumbers( "1.0",   "1.0.1" ) );
        assertEquals(  1, VersionUtilities.compareVersionNumbers( "1.0.1", "1.0" ) );
    }

    @Test
    void emptyAndDegenerateInputs()
    {
        // stripSuffixes() returns "0" for empty input, so an empty
        // version compares equal to "0".
        assertEquals( 0, VersionUtilities.compareVersionNumbers( "", "0" ) );
        assertEquals( 0, VersionUtilities.compareVersionNumbers( "", "" ) );
        // Single "_" → stripped to empty → "0".
        assertEquals( 0, VersionUtilities.compareVersionNumbers( "_230729ER", "0" ) );
    }
}
