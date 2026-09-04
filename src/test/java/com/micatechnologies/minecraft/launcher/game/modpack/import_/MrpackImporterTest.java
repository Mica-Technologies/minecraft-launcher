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

package com.micatechnologies.minecraft.launcher.game.modpack.import_;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security/robustness-boundary tests for {@link MrpackImporter}.
 *
 * <p>Unlike the other three importers in this package,
 * {@code MrpackImporter} does not do bulk ZIP extraction at all — per
 * its own class Javadoc, it reads exactly one named entry
 * ({@code modrinth.index.json}) out of the downloaded {@code .mrpack}
 * into a {@link String} and never touches the {@code overrides/}
 * tree. There is therefore no Zip-Slip surface in this class to test:
 * a hostile path inside a {@code .mrpack}'s {@code overrides/} folder
 * is simply never extracted to disk by this version of the importer.
 * (If a future change adds overrides extraction, that code path will
 * need the same guard-and-test treatment as
 * {@code ModpackZipImporter}/{@code TechnicServerZipImporter}.)</p>
 *
 * <p>The rest of {@link MrpackImporter#importMrpack} downloads over the
 * network on every non-trivial call (the {@code .mrpack} itself, then
 * the Forge/NeoForge installer to hash it, then optionally the project
 * icon), so it is out of scope for a network-free unit test beyond its
 * very first, IO-free validation check. What remains safely testable
 * in isolation is the pure version-string normalization helper.</p>
 *
 * @since 2026.5
 */
class MrpackImporterTest
{
    @Test
    void blankDownloadUrlIsRejectedWithoutAnyIO()
    {
        MrpackImporter.ImportException ex = assertThrows( MrpackImporter.ImportException.class,
                                                            () -> MrpackImporter.importMrpack( "", "slug", null ) );
        assertTrue( ex.getMessage().contains( "No download URL" ) );
    }

    @Test
    void nullDownloadUrlIsRejectedWithoutAnyIO()
    {
        MrpackImporter.ImportException ex = assertThrows( MrpackImporter.ImportException.class,
                                                            () -> MrpackImporter.importMrpack( null, "slug", null ) );
        assertTrue( ex.getMessage().contains( "No download URL" ) );
    }

    @Test
    void normalizeStripsLowercaseVPrefixBeforeDigit()
    {
        assertEquals( "43", MrpackImporter.normalizeImportedVersion( "v43" ) );
    }

    @Test
    void normalizeStripsUppercaseVPrefixBeforeDigit()
    {
        assertEquals( "2.1.0", MrpackImporter.normalizeImportedVersion( "V2.1.0" ) );
    }

    @Test
    void normalizeLeavesVersionsThatGenuinelyStartWithVAlone()
    {
        // "vintage-1.0" — the second character isn't a digit, so this must
        // NOT be treated as a cosmetic "v" prefix and mangled to "intage-1.0".
        assertEquals( "vintage-1.0", MrpackImporter.normalizeImportedVersion( "vintage-1.0" ) );
    }

    @Test
    void normalizeLeavesPlainVersionsUnchanged()
    {
        assertEquals( "1.2.3", MrpackImporter.normalizeImportedVersion( "1.2.3" ) );
    }

    @Test
    void normalizeTrimsSurroundingWhitespaceBeforeStripping()
    {
        assertEquals( "5", MrpackImporter.normalizeImportedVersion( "  v5  " ) );
    }

    @Test
    void normalizeReturnsDefaultForNull()
    {
        assertEquals( "1.0.0", MrpackImporter.normalizeImportedVersion( null ) );
    }

    @Test
    void normalizeReturnsDefaultForBlank()
    {
        assertEquals( "1.0.0", MrpackImporter.normalizeImportedVersion( "   " ) );
    }

    @Test
    void normalizeLeavesSingleCharacterVAlone()
    {
        // Too short for the "v" + digit check (length >= 2) to apply.
        assertEquals( "v", MrpackImporter.normalizeImportedVersion( "v" ) );
    }
}
