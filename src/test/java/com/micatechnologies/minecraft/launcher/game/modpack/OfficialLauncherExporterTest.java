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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the pure-logic seams of {@link OfficialLauncherExporter}.
 * Skips the full {@code exportPack} happy-path because that touches
 * {@link com.micatechnologies.minecraft.launcher.config.ConfigManager}
 * (which reads / writes the launcher config singleton) and the pack's
 * runtime metadata — both runtime-resident state we keep out of the
 * unit-test surface per CLAUDE.md.
 *
 * <p>The tested seams cover the things most likely to regress:
 * version-ID derivation per loader, stable-key derivation across
 * re-exports, sanitization of pack names into folder names, and the
 * "is this version installed?" check against an in-temp-dir
 * {@code .minecraft/versions/<id>/<id>.json} layout.</p>
 */
class OfficialLauncherExporterTest
{
    // =========================================================================================
    //  Folder-name sanitization
    // =========================================================================================

    @Test
    void sanitizeFolderNameAllowsPlainStrings()
    {
        assertEquals( "Alto", OfficialLauncherExporter.sanitizeFolderName( "Alto" ) );
        assertEquals( "Sky Factory 4", OfficialLauncherExporter.sanitizeFolderName( "Sky Factory 4" ) );
    }

    @Test
    void sanitizeFolderNameStripsWindowsForbiddenCharacters()
    {
        // The full Windows forbidden set (< > : " / \ | ? *) becomes underscores.
        String dirty = "<bad>:\"/\\|?*name";
        String cleaned = OfficialLauncherExporter.sanitizeFolderName( dirty );
        for ( char forbidden : new char[]{ '<', '>', ':', '"', '/', '\\', '|', '?', '*' } ) {
            assertFalse( cleaned.indexOf( forbidden ) >= 0,
                    "Forbidden char remained in cleaned name: " + cleaned );
        }
        // Non-forbidden chars survive in place.
        assertTrue( cleaned.endsWith( "name" ) );
    }

    @Test
    void sanitizeFolderNameDropsControlChars()
    {
        // Control character (0x07 bell) should be removed, not replaced with underscore.
        String dirty = "BelName";
        assertEquals( "BelName", OfficialLauncherExporter.sanitizeFolderName( dirty ) );
    }

    @Test
    void sanitizeFolderNameFallsBackOnEmpty()
    {
        assertEquals( "modpack", OfficialLauncherExporter.sanitizeFolderName( "" ) );
        assertEquals( "modpack", OfficialLauncherExporter.sanitizeFolderName( null ) );
        // All-forbidden input collapses to underscores, then we still
        // accept that — but a truly empty result after stripping (e.g.
        // pure control chars) lands on the fallback.
        assertEquals( "modpack", OfficialLauncherExporter.sanitizeFolderName( "" ) );
    }

    @Test
    void sanitizeFolderNameTrimsLongNames()
    {
        String veryLong = "a".repeat( 200 );
        String cleaned = OfficialLauncherExporter.sanitizeFolderName( veryLong );
        assertTrue( cleaned.length() <= 80, "Long name should be truncated: " + cleaned.length() );
    }

    // =========================================================================================
    //  Version installed detection
    // =========================================================================================

    @Test
    void isVersionInstalledReturnsTrueWhenVersionJsonPresent( @TempDir Path dotMc ) throws Exception
    {
        Path versionDir = dotMc.resolve( "versions" ).resolve( "1.20.1-forge-47.3.0" );
        Files.createDirectories( versionDir );
        Files.writeString( versionDir.resolve( "1.20.1-forge-47.3.0.json" ),
                "{}", StandardCharsets.UTF_8 );

        assertTrue( OfficialLauncherExporter.isVersionInstalled( dotMc, "1.20.1-forge-47.3.0" ) );
    }

    @Test
    void isVersionInstalledReturnsFalseWhenDirectoryMissing( @TempDir Path dotMc )
    {
        // Directory exists but no <id>/<id>.json — the canonical "Mojang
        // launcher has discovered this version" marker is absent.
        assertFalse( OfficialLauncherExporter.isVersionInstalled( dotMc, "1.20.1-forge-47.3.0" ) );
    }

    @Test
    void isVersionInstalledReturnsFalseWhenJsonMissing( @TempDir Path dotMc ) throws Exception
    {
        // Empty version directory (no <id>.json) doesn't count — the
        // launcher requires the manifest to know what to run.
        Path versionDir = dotMc.resolve( "versions" ).resolve( "1.21.4" );
        Files.createDirectories( versionDir );

        assertFalse( OfficialLauncherExporter.isVersionInstalled( dotMc, "1.21.4" ) );
    }

    @Test
    void isVersionInstalledReturnsFalseForNullOrBlankId( @TempDir Path dotMc )
    {
        assertFalse( OfficialLauncherExporter.isVersionInstalled( dotMc, null ) );
        assertFalse( OfficialLauncherExporter.isVersionInstalled( dotMc, "" ) );
        assertFalse( OfficialLauncherExporter.isVersionInstalled( dotMc, "   " ) );
    }

    // =========================================================================================
    //  Stable profile key derivation
    // =========================================================================================

    @Test
    void stableProfileKeyDeterministicAcrossReExports() throws Exception
    {
        FakePack a = new FakePack( "Alto", "https://example/alto.json", false, "1.12.2", "forge", "14.23.5.2855" );
        FakePack b = new FakePack( "Alto", "https://example/alto.json", false, "1.12.2", "forge", "14.23.5.2855" );

        String keyA = OfficialLauncherExporter.stableProfileKey( a );
        String keyB = OfficialLauncherExporter.stableProfileKey( b );

        assertEquals( keyA, keyB,
                "Two packs with the same manifest URL must produce identical profile keys "
                        + "so re-export updates the same Mojang launcher entry." );
    }

    @Test
    void stableProfileKeyDistinctAcrossPacks() throws Exception
    {
        FakePack a = new FakePack( "Alto", "https://example/alto.json", false, "1.12.2", "forge", "14.23.5.2855" );
        FakePack b = new FakePack( "Crow", "https://example/crow.json", false, "1.12.2", "forge", "14.23.5.2855" );

        assertNotEquals(
                OfficialLauncherExporter.stableProfileKey( a ),
                OfficialLauncherExporter.stableProfileKey( b ),
                "Different manifest URLs must produce different profile keys." );
    }

    @Test
    void stableProfileKeyVanillaDistinctFromModpack() throws Exception
    {
        FakePack vanilla = new FakePack( "1.21.4", null, true, "1.21.4", null, null );
        FakePack modpack = new FakePack( "1.21.4", "https://example/m.json", false, "1.21.4", "fabric", "0.16.5" );

        assertNotEquals(
                OfficialLauncherExporter.stableProfileKey( vanilla ),
                OfficialLauncherExporter.stableProfileKey( modpack ),
                "Vanilla and modpack profiles for the same MC version must not collide." );
    }

    // =========================================================================================
    //  resolveDotMinecraft
    // =========================================================================================

    @Test
    void resolveDotMinecraftReturnsPlatformConventionalPath()
    {
        // We can't reliably exercise every platform from a single test
        // host, but the result must be non-null, absolute (or at least
        // resolvable), and end with one of the three known basenames.
        Path resolved = OfficialLauncherExporter.resolveDotMinecraft();
        assertNotNull( resolved );
        String basename = resolved.getFileName() == null ? "" : resolved.getFileName().toString();
        assertTrue( basename.equals( ".minecraft" ) || basename.equals( "minecraft" ),
                "Expected .minecraft or minecraft basename, got: " + basename );
    }

    // =========================================================================================
    //  Phase 4 — version-ID computation
    // =========================================================================================

    // For Forge / NeoForge: getLoaderVersion() returns the full version.json
    // `id` field — which IS the version directory name the installer creates.
    // The version ID we compute must equal it directly; an earlier bug
    // double-prefixed it ("1.12.2-forge-" + "1.12.2-forge-14.23.5.2860") and
    // produced an unresolvable lastVersionId in launcher_profiles.json,
    // surfacing as "failed to find assets" when the user clicked Play in
    // Mojang's launcher.

    @Test
    void versionIdForLegacyForgeUsesLoaderVersionDirectly() throws Exception
    {
        // Legacy Forge (1.12.2-) version.json id format includes mc-forge-loader.
        FakePack pack = new FakePack( "Alto", "https://example/alto.json", false,
                "1.12.2", "forge", "1.12.2-forge-14.23.5.2860" );
        assertEquals( "1.12.2-forge-14.23.5.2860",
                OfficialLauncherExporter.computeVersionId( pack ) );
    }

    @Test
    void versionIdForModernForgeUsesLoaderVersionDirectly() throws Exception
    {
        // Modern Forge (1.13+) installer also stamps a fully-qualified id.
        FakePack pack = new FakePack( "Modern", "https://example/m.json", false,
                "1.20.1", "forge", "1.20.1-forge-47.3.0" );
        assertEquals( "1.20.1-forge-47.3.0",
                OfficialLauncherExporter.computeVersionId( pack ) );
    }

    @Test
    void versionIdForNeoForgeUsesLoaderVersionDirectly() throws Exception
    {
        // NeoForge's installer version.json id is "neoforge-<ver>".
        FakePack pack = new FakePack( "TestPack", "https://example/p.json", false,
                "1.21.1", "neoforge", "neoforge-21.1.95" );
        assertEquals( "neoforge-21.1.95",
                OfficialLauncherExporter.computeVersionId( pack ) );
    }

    @Test
    void versionIdForFabricReconstructsFromLoaderAndMc() throws Exception
    {
        // Fabric's getLoaderVersion() parses out just the loader number
        // ("0.16.10") from the profile JSON's id; reconstruct the full
        // "fabric-loader-<ver>-<mc>" form here.
        FakePack pack = new FakePack( "FabricPack", "https://example/fp.json", false,
                "1.21.5", "fabric", "0.16.10" );
        assertEquals( "fabric-loader-0.16.10-1.21.5",
                OfficialLauncherExporter.computeVersionId( pack ) );
    }

    @Test
    void versionIdForVanillaIsRawMcVersion() throws Exception
    {
        FakePack pack = new FakePack( "1.21.4", null, true, "1.21.4", null, null );
        assertEquals( "1.21.4",
                OfficialLauncherExporter.computeVersionId( pack ) );
    }

    // =========================================================================================
    //  Test helpers
    // =========================================================================================

    /** Minimal GameModPack stub for the unit tests. Real GameModPack
     *  pulls in too much runtime state (manifest fetcher, config singleton,
     *  filesystem I/O on construction) for a pure-logic test surface. */
    private static class FakePack extends GameModPack
    {
        private final String name;
        private final String packUrlOverride;
        private final boolean isVanilla;
        private final String mcVersion;
        private final String loaderType;
        private final String loaderVersion;

        FakePack( String name, String packUrl, boolean isVanilla,
                  String mcVersion, String loaderType, String loaderVersion )
        {
            this.name = name;
            this.packUrlOverride = packUrl;
            this.isVanilla = isVanilla;
            this.mcVersion = mcVersion;
            this.loaderType = loaderType;
            this.loaderVersion = loaderVersion;
        }

        @Override public String getPackName() { return name; }
        @Override public String getPackURL() { return packUrlOverride; }
        @Override public boolean isVanillaVersion() { return isVanilla; }
        @Override public String getMinecraftVersion() { return mcVersion; }
        @Override public String getModLoaderType() { return loaderType; }
        @Override public String getLoaderVersion() { return loaderVersion; }
    }
}
