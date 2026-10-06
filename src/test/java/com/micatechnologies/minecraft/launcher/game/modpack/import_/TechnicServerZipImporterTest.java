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
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static com.micatechnologies.minecraft.launcher.consts.localization.LocalizedMessages.assertFromKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security-boundary tests for {@link TechnicServerZipImporter}, the
 * importer for Technic "Server Download" ZIPs. Since Technic gates its
 * Platform API, this is the launcher's only path for that pack format,
 * and the ZIP comes straight from a URL the user pasted from a random
 * project page — nothing about it is vetted before it reaches this
 * class. A Zip-Slip regression here has the same blast radius as in
 * {@link ModpackZipImporter}: arbitrary-file-write anywhere the
 * launcher process can reach.
 *
 * <p>{@link TechnicServerZipImporter#importZip(File)} is NOT exercised
 * directly in these tests: unlike {@link ModpackZipImporter#importZip},
 * it resolves and creates its real install folder (via
 * {@code LocalPathManager.getLauncherModpackFolderPath()}) as the very
 * first statement inside its ZIP-open block, before any validation of
 * the archive's contents happens. There is no calling shape that
 * reaches this method without touching that real, non-{@code @TempDir}
 * directory, so it is entirely out of scope for a safe unit test. The
 * classification helper ({@link TechnicServerZipImporter#looksLikeTechnicServerZip})
 * and the package-private extraction method (called directly, mirroring the
 * approach in {@code ModpackZipImporterTest}) are exercised instead —
 * they cover the two genuinely pure/isolatable pieces of this class's
 * behavior.</p>
 *
 * @since 2026.5
 */
class TechnicServerZipImporterTest
{
    @TempDir
    Path tempDir;

    // ===================================================================
    //  looksLikeTechnicServerZip(ZipFile) — pure classification helper
    // ===================================================================

    @Test
    void allThreeSignalsPresentClassifiesAsTechnicServerZip() throws IOException
    {
        LinkedHashMap< String, String > entries = new LinkedHashMap<>();
        entries.put( "mods/SomeMod.jar", "modbytes" );
        entries.put( "Tekkit.jar", "serverbytes" );
        entries.put( "launch.bat", "java -jar Tekkit.jar" );
        File zip = createZip( "technic-shaped.zip", entries );
        try ( ZipFile zf = new ZipFile( zip ) ) {
            assertTrue( TechnicServerZipImporter.looksLikeTechnicServerZip( zf ) );
        }
    }

    @Test
    void missingModsFolderIsNotClassifiedAsTechnicServerZip() throws IOException
    {
        LinkedHashMap< String, String > entries = new LinkedHashMap<>();
        entries.put( "Tekkit.jar", "serverbytes" );
        entries.put( "launch.bat", "java -jar Tekkit.jar" );
        File zip = createZip( "no-mods.zip", entries );
        try ( ZipFile zf = new ZipFile( zip ) ) {
            assertFalse( TechnicServerZipImporter.looksLikeTechnicServerZip( zf ) );
        }
    }

    @Test
    void missingTopLevelJarIsNotClassifiedAsTechnicServerZip() throws IOException
    {
        LinkedHashMap< String, String > entries = new LinkedHashMap<>();
        entries.put( "mods/SomeMod.jar", "modbytes" );
        entries.put( "launch.bat", "java -jar Tekkit.jar" );
        File zip = createZip( "no-server-jar.zip", entries );
        try ( ZipFile zf = new ZipFile( zip ) ) {
            assertFalse( TechnicServerZipImporter.looksLikeTechnicServerZip( zf ) );
        }
    }

    @Test
    void missingLaunchScriptIsNotClassifiedAsTechnicServerZip() throws IOException
    {
        LinkedHashMap< String, String > entries = new LinkedHashMap<>();
        entries.put( "mods/SomeMod.jar", "modbytes" );
        entries.put( "Tekkit.jar", "serverbytes" );
        File zip = createZip( "no-launch-script.zip", entries );
        try ( ZipFile zf = new ZipFile( zip ) ) {
            assertFalse( TechnicServerZipImporter.looksLikeTechnicServerZip( zf ) );
        }
    }

    @Test
    void emptyZipIsNotClassifiedAsTechnicServerZip() throws IOException
    {
        File zip = createZip( "empty.zip", new LinkedHashMap<>() );
        try ( ZipFile zf = new ZipFile( zip ) ) {
            assertFalse( TechnicServerZipImporter.looksLikeTechnicServerZip( zf ) );
        }
    }

    // ===================================================================
    //  extractContents(ZipFile, Path, List<String>) — Zip-Slip guard
    // ===================================================================

    @Test
    void relativeParentTraversalEntryIsRejected() throws IOException
    {
        File zip = createZip( "slip-relative.zip", entry( "config/../../evil.txt", "pwned" ) );
        Path dest = Files.createDirectory( tempDir.resolve( "extract-root-1" ) );
        try ( ZipFile zf = new ZipFile( zip ) ) {
            IOException ex = assertThrows( IOException.class, () -> invokeExtractContents( zf, dest, new ArrayList<>() ) );
            assertFromKey( "importer.error.entryEscapes", ex.getMessage() );
        }
    }

    @Test
    void deeplyNestedTraversalEntryIsRejected() throws IOException
    {
        File zip = createZip( "slip-nested.zip", entry( "a/../../../b", "pwned" ) );
        Path dest = Files.createDirectory( tempDir.resolve( "extract-root-2" ) );
        try ( ZipFile zf = new ZipFile( zip ) ) {
            IOException ex = assertThrows( IOException.class, () -> invokeExtractContents( zf, dest, new ArrayList<>() ) );
            assertFromKey( "importer.error.entryEscapes", ex.getMessage() );
        }
    }

    @Test
    void absolutePathEntryIsRejectedAndNeverWritten() throws IOException
    {
        Path victim = tempDir.resolve( "victim-outside-dest.txt" );
        File zip = createZip( "slip-absolute.zip", entry( victim.toAbsolutePath().toString(), "pwned" ) );
        Path dest = Files.createDirectory( tempDir.resolve( "extract-root-3" ) );
        try ( ZipFile zf = new ZipFile( zip ) ) {
            IOException ex = assertThrows( IOException.class, () -> invokeExtractContents( zf, dest, new ArrayList<>() ) );
            assertFromKey( "importer.error.entryEscapes", ex.getMessage() );
        }
        assertFalse( Files.exists( victim ), "Absolute-path ZIP entry must never be written to disk" );
    }

    @Test
    void backslashEncodedTraversalIsNormalizedAndRejected() throws IOException
    {
        // extractContents() explicitly normalizes '\' to '/' before
        // resolving against the destination (unlike ModpackZipImporter,
        // which relies on the platform path provider to do so). This
        // confirms that normalization actually runs: a name that is only
        // a traversal once backslashes become separators must still be
        // caught, independent of which OS the test runs on.
        File zip = createZip( "slip-backslash.zip", entry( "..\\..\\evil.txt", "pwned" ) );
        Path dest = Files.createDirectory( tempDir.resolve( "extract-root-4" ) );
        try ( ZipFile zf = new ZipFile( zip ) ) {
            IOException ex = assertThrows( IOException.class, () -> invokeExtractContents( zf, dest, new ArrayList<>() ) );
            assertFromKey( "importer.error.entryEscapes", ex.getMessage() );
        }
    }

    @Test
    void wellFormedEntriesExtractInsideDestination() throws IOException
    {
        LinkedHashMap< String, String > entries = new LinkedHashMap<>();
        entries.put( "config/foo.txt", "hello" );
        entries.put( "buildcraft/settings.cfg", "world" );
        File zip = createZip( "well-formed.zip", entries );
        Path dest = Files.createDirectory( tempDir.resolve( "extract-root-5" ) );
        try ( ZipFile zf = new ZipFile( zip ) ) {
            invokeExtractContents( zf, dest, new ArrayList<>() );
        }
        assertEquals( "hello", Files.readString( dest.resolve( "config/foo.txt" ) ) );
        assertEquals( "world", Files.readString( dest.resolve( "buildcraft/settings.cfg" ) ) );
    }

    @Test
    void serverJarAndLaunchScriptsAreSkippedDuringExtraction() throws IOException
    {
        LinkedHashMap< String, String > entries = new LinkedHashMap<>();
        entries.put( "Tekkit.jar", "server-side-bytes" );
        entries.put( "launch.bat", "java -jar Tekkit.jar" );
        entries.put( "launch.sh", "#!/bin/sh\njava -jar Tekkit.jar" );
        entries.put( "mods/SomeMod.jar", "modbytes" );
        File zip = createZip( "with-server-cruft.zip", entries );
        Path dest = Files.createDirectory( tempDir.resolve( "extract-root-6" ) );
        try ( ZipFile zf = new ZipFile( zip ) ) {
            invokeExtractContents( zf, dest, new ArrayList<>() );
        }
        assertFalse( Files.exists( dest.resolve( "Tekkit.jar" ) ), "top-level server jar must not be extracted" );
        assertFalse( Files.exists( dest.resolve( "launch.bat" ) ) );
        assertFalse( Files.exists( dest.resolve( "launch.sh" ) ) );
        assertTrue( Files.exists( dest.resolve( "mods/SomeMod.jar" ) ) );
    }

    @Test
    void topLevelModEntriesAreRecordedButNestedModEntriesAreNot() throws IOException
    {
        LinkedHashMap< String, String > entries = new LinkedHashMap<>();
        entries.put( "mods/SomeMod.jar", "a" );
        entries.put( "mods/RailCraft.zip", "b" );
        entries.put( "mods/ccSensors/api/foo.lua", "c" );
        entries.put( "config/unrelated.cfg", "d" );
        File zip = createZip( "mod-listing.zip", entries );
        Path dest = Files.createDirectory( tempDir.resolve( "extract-root-7" ) );
        List< String > modNames = new ArrayList<>();
        try ( ZipFile zf = new ZipFile( zip ) ) {
            invokeExtractContents( zf, dest, modNames );
        }
        assertEquals( 2, modNames.size() );
        assertTrue( modNames.contains( "SomeMod.jar" ) );
        assertTrue( modNames.contains( "RailCraft.zip" ) );
    }

    // ===================================================================
    //  test helpers
    // ===================================================================

    private static LinkedHashMap< String, String > entry( String name, String content )
    {
        LinkedHashMap< String, String > map = new LinkedHashMap<>();
        map.put( name, content );
        return map;
    }

    private File createZip( String filename, LinkedHashMap< String, String > entries ) throws IOException
    {
        File zipFile = tempDir.resolve( filename ).toFile();
        try ( ZipOutputStream zos = new ZipOutputStream( new FileOutputStream( zipFile ) ) ) {
            for ( var e : entries.entrySet() ) {
                zos.putNextEntry( new ZipEntry( e.getKey() ) );
                zos.write( e.getValue().getBytes( StandardCharsets.UTF_8 ) );
                zos.closeEntry();
            }
        }
        return zipFile;
    }

    /** Calls the package-private {@code extractContents} directly so the real Zip-Slip
     *  guard runs against a {@code @TempDir} destination instead of the real install
     *  folder. The method is package-private specifically to make this possible without
     *  reflection. */
    private static void invokeExtractContents( ZipFile zip, Path dest, List< String > modNames )
            throws IOException
    {
        TechnicServerZipImporter.extractContents( zip, dest, modNames );
    }
}
