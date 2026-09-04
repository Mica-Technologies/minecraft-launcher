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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link ModpackImportClassifier#classify(String)} — the routing
 * decision behind the launcher's "Add Modpack by URL" field. Get this wrong
 * and a user pasting a Modrinth or CurseForge project link either gets
 * routed into the wrong platform's importer (garbage slug extraction, wrong
 * API called) or falls through to the legacy Mica-manifest path and sees a
 * confusing "not a valid manifest" error instead of the platform-specific
 * import flow they reasonably expected to work.
 *
 * <p>Every branch is a pure regex match with no network access, so this
 * class enumerates the real URL shapes read out of the four
 * {@code Pattern} constants in the production class rather than guessed
 * ones — anchored scheme, host (with/without {@code www.}), fixed path
 * segment, optional version/file suffix, optional trailing slash, and
 * optional query string.</p>
 */
class ModpackImportClassifierTest
{
    // ===== null / blank input =====

    @Test
    void nullUrlIsUnknown()
    {
        assertSame( ModpackImportClassifier.Classification.UNKNOWN, ModpackImportClassifier.classify( null ) );
    }

    @Test
    void emptyUrlIsUnknown()
    {
        assertSame( ModpackImportClassifier.Classification.UNKNOWN, ModpackImportClassifier.classify( "" ) );
    }

    @Test
    void blankUrlIsUnknown()
    {
        assertSame( ModpackImportClassifier.Classification.UNKNOWN, ModpackImportClassifier.classify( "   " ) );
    }

    // ===== MODRINTH =====

    @Test
    void modrinthBareProjectUrlHasNullVersionId()
    {
        ModpackImportClassifier.Classification c =
                ModpackImportClassifier.classify( "https://modrinth.com/modpack/shenanigans" );
        assertEquals( ModpackImportSource.MODRINTH, c.source() );
        assertEquals( "shenanigans", c.slug() );
        assertNull( c.versionId() );
    }

    @Test
    void modrinthUrlWithWwwAndVersionExtractsBoth()
    {
        ModpackImportClassifier.Classification c =
                ModpackImportClassifier.classify( "https://www.modrinth.com/modpack/shenanigans/version/abc123" );
        assertEquals( ModpackImportSource.MODRINTH, c.source() );
        assertEquals( "shenanigans", c.slug() );
        assertEquals( "abc123", c.versionId() );
    }

    @Test
    void modrinthUrlWithTrailingSlashAndQueryStillMatches()
    {
        ModpackImportClassifier.Classification c =
                ModpackImportClassifier.classify( "http://modrinth.com/modpack/shenanigans/?utm_source=x" );
        assertEquals( ModpackImportSource.MODRINTH, c.source() );
        assertEquals( "shenanigans", c.slug() );
    }

    @Test
    void modrinthNonModpackProjectTypeIsUnknown()
    {
        // Fixed "modpack" path segment excludes mods/resourcepacks/shaders on purpose.
        ModpackImportClassifier.Classification c =
                ModpackImportClassifier.classify( "https://modrinth.com/mod/some-mod" );
        assertSame( ModpackImportClassifier.Classification.UNKNOWN, c );
    }

    // ===== CURSEFORGE =====

    @Test
    void curseforgeBareProjectUrlHasNullVersionId()
    {
        ModpackImportClassifier.Classification c =
                ModpackImportClassifier.classify( "https://www.curseforge.com/minecraft/modpacks/rlcraft" );
        assertEquals( ModpackImportSource.CURSEFORGE, c.source() );
        assertEquals( "rlcraft", c.slug() );
        assertNull( c.versionId() );
    }

    @Test
    void curseforgeFilesUrlExtractsFileId()
    {
        ModpackImportClassifier.Classification c =
                ModpackImportClassifier.classify( "https://www.curseforge.com/minecraft/modpacks/rlcraft/files/12345" );
        assertEquals( ModpackImportSource.CURSEFORGE, c.source() );
        assertEquals( "rlcraft", c.slug() );
        assertEquals( "12345", c.versionId() );
    }

    @Test
    void curseforgeDownloadUrlExtractsFileId()
    {
        ModpackImportClassifier.Classification c =
                ModpackImportClassifier.classify( "https://curseforge.com/minecraft/modpacks/rlcraft/download/12345" );
        assertEquals( ModpackImportSource.CURSEFORGE, c.source() );
        assertEquals( "12345", c.versionId() );
    }

    @Test
    void curseforgeSingularModpackSegmentIsUnknown()
    {
        // Pattern requires the plural "modpacks" segment.
        ModpackImportClassifier.Classification c =
                ModpackImportClassifier.classify( "https://www.curseforge.com/minecraft/modpack/rlcraft" );
        assertSame( ModpackImportClassifier.Classification.UNKNOWN, c );
    }

    // ===== TECHNIC =====

    @Test
    void technicWebsiteUrlExtractsSlugAndHasNullVersionId()
    {
        ModpackImportClassifier.Classification c =
                ModpackImportClassifier.classify( "https://www.technicpack.net/modpack/tekkit.552560" );
        assertEquals( ModpackImportSource.TECHNIC, c.source() );
        assertEquals( "tekkit.552560", c.slug() );
        assertNull( c.versionId() );
    }

    @Test
    void technicApiUrlAlsoMatches()
    {
        ModpackImportClassifier.Classification c =
                ModpackImportClassifier.classify( "https://api.technicpack.net/modpack/tekkit.552560" );
        assertEquals( ModpackImportSource.TECHNIC, c.source() );
        assertEquals( "tekkit.552560", c.slug() );
    }

    @Test
    void technicUrlWithExtraTabSegmentStillMatches()
    {
        ModpackImportClassifier.Classification c =
                ModpackImportClassifier.classify( "https://www.technicpack.net/modpack/tekkit.552560/mods" );
        assertEquals( ModpackImportSource.TECHNIC, c.source() );
        assertEquals( "tekkit.552560", c.slug() );
    }

    // ===== MICA (fallback .json manifest) =====

    @Test
    void plainJsonUrlIsMicaWithNullSlugAndVersion()
    {
        ModpackImportClassifier.Classification c =
                ModpackImportClassifier.classify( "https://example.com/manifests/my-pack.json" );
        assertEquals( ModpackImportSource.MICA, c.source() );
        assertNull( c.slug() );
        assertNull( c.versionId() );
    }

    @Test
    void jsonUrlWithQueryStringIsMica()
    {
        ModpackImportClassifier.Classification c =
                ModpackImportClassifier.classify( "http://cdn.example.com/manifest.json?v=2" );
        assertEquals( ModpackImportSource.MICA, c.source() );
    }

    // ===== UNKNOWN fallthrough =====

    @Test
    void nonJsonArbitraryUrlIsUnknown()
    {
        ModpackImportClassifier.Classification c =
                ModpackImportClassifier.classify( "https://example.com/some/page.html" );
        assertSame( ModpackImportClassifier.Classification.UNKNOWN, c );
    }

    @Test
    void malformedUrlIsUnknownNotAnException()
    {
        // Regex matching never throws on garbage input — it just fails to match.
        ModpackImportClassifier.Classification c = ModpackImportClassifier.classify( "not a url at all" );
        assertSame( ModpackImportClassifier.Classification.UNKNOWN, c );
    }

    @Test
    void leadingAndTrailingWhitespaceIsTolerated()
    {
        ModpackImportClassifier.Classification c =
                ModpackImportClassifier.classify( "  https://modrinth.com/modpack/shenanigans  " );
        assertEquals( ModpackImportSource.MODRINTH, c.source() );
        assertEquals( "shenanigans", c.slug() );
    }
}
