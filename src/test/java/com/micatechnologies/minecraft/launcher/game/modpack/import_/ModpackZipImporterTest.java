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

import com.micatechnologies.minecraft.launcher.game.modpack.ModpackExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static com.micatechnologies.minecraft.launcher.consts.localization.LocalizedMessages.assertFromKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security-boundary tests for {@link ModpackZipImporter}, which ingests
 * an arbitrary, user-supplied ZIP file and extracts it into the
 * launcher's real modpack install folder. A user who imports a
 * "modpack export" ZIP downloaded from a Discord link or a shady
 * modpack-sharing site is trusting the launcher to keep that archive's
 * contents confined to the pack's own folder — a Zip-Slip regression
 * here would let a malicious archive plant files anywhere on disk the
 * launcher process can write to (autostart entries, other packs'
 * configs, etc.).
 *
 * <p>{@link ModpackZipImporter#importZip(File)} itself is NOT exercised
 * end-to-end here once the embedded manifest validates, because a
 * successful validation pass causes the real method to resolve its
 * install folder via {@code LocalPathManager.getLauncherModpackFolderPath()}
 * — the real per-user launcher data directory (or, in a JVM where
 * {@code GameModeManager} hasn't inferred client/server mode yet, the
 * process's current working directory) — and to call through to
 * {@code GameModPackManager.installModPackByURL}. Neither is safe to
 * touch from a unit test. The tests below split the method's behavior
 * into two safe surfaces instead:</p>
 * <ol>
 *   <li>The public {@link ModpackZipImporter#importZip(File)} entry
 *       point, exercised only for inputs that make it fail validation
 *       (missing marker, corrupt marker, unsupported format, missing /
 *       corrupt manifest, missing packName) — every one of those
 *       failure paths returns before the method ever consults
 *       {@code LocalPathManager}, so they're fully safe to call
 *       directly.</li>
 *   <li>The private {@code extractZipContents(ZipFile, Path)} method —
 *       where the actual Zip-Slip guard lives — called directly with a
 *       {@code @TempDir} destination, so the real extraction logic runs
 *       against a throwaway folder instead of a real install directory.
 *       {@code extractZipContents} is package-private specifically so this
 *       is possible without reflection, which the repo's testing
 *       conventions prefer avoiding.</li>
 * </ol>
 *
 * @since 2026.5
 */
class ModpackZipImporterTest
{
    @TempDir
    Path tempDir;

    // ===================================================================
    //  extractZipContents(ZipFile, Path) — Zip-Slip guard
    // ===================================================================

    @Test
    void relativeParentTraversalEntryIsRejected() throws IOException
    {
        File zip = createZip( "slip-relative.zip", entries( "../../evil.txt", "pwned" ) );
        Path dest = Files.createDirectory( tempDir.resolve( "extract-root-1" ) );
        try ( ZipFile zf = new ZipFile( zip ) ) {
            IOException ex = assertThrows( IOException.class, () -> invokeExtractZipContents( zf, dest ) );
            assertFromKey( "importer.error.entryEscapes", ex.getMessage() );
        }
    }

    @Test
    void deeplyNestedTraversalEntryIsRejected() throws IOException
    {
        // "a/../../../b" walks into "a", back out of it, then two levels
        // above the extraction root before landing on "b" — a shape a
        // naive single ".." check might miss.
        File zip = createZip( "slip-nested.zip", entries( "a/../../../b", "pwned" ) );
        Path dest = Files.createDirectory( tempDir.resolve( "extract-root-2" ) );
        try ( ZipFile zf = new ZipFile( zip ) ) {
            IOException ex = assertThrows( IOException.class, () -> invokeExtractZipContents( zf, dest ) );
            assertFromKey( "importer.error.entryEscapes", ex.getMessage() );
        }
    }

    @Test
    void absolutePathEntryIsRejectedAndNeverWritten() throws IOException
    {
        // The classic Zip-Slip variant: an entry whose "name" is itself an
        // absolute path. Path.resolve() returns an absolute argument
        // unchanged (ignoring the destination), so without the
        // startsWith(destNormalized) guard this would write exactly where
        // the entry says. The victim path is deliberately kept inside our
        // own @TempDir tree (a sibling of the extraction root, not inside
        // it) so that even a hypothetical guard failure can't touch
        // anything outside test-owned storage.
        Path victim = tempDir.resolve( "victim-outside-dest.txt" );
        String absoluteEntryName = victim.toAbsolutePath().toString();
        File zip = createZip( "slip-absolute.zip", entries( absoluteEntryName, "pwned" ) );
        Path dest = Files.createDirectory( tempDir.resolve( "extract-root-3" ) );
        try ( ZipFile zf = new ZipFile( zip ) ) {
            IOException ex = assertThrows( IOException.class, () -> invokeExtractZipContents( zf, dest ) );
            assertFromKey( "importer.error.entryEscapes", ex.getMessage() );
        }
        assertFalse( Files.exists( victim ), "Absolute-path ZIP entry must never be written to disk" );
    }

    @Test
    void wellFormedEntriesExtractInsideDestination() throws IOException
    {
        LinkedHashMap< String, String > entries = new LinkedHashMap<>();
        entries.put( "config/foo.txt", "hello" );
        entries.put( "nested/dir/file.txt", "world" );
        File zip = createZip( "well-formed.zip", entries );
        Path dest = Files.createDirectory( tempDir.resolve( "extract-root-4" ) );
        try ( ZipFile zf = new ZipFile( zip ) ) {
            invokeExtractZipContents( zf, dest );
        }
        assertEquals( "hello", Files.readString( dest.resolve( "config/foo.txt" ) ) );
        assertEquals( "world", Files.readString( dest.resolve( "nested/dir/file.txt" ) ) );
    }

    @Test
    void markerAndManifestEntriesAreSkippedDuringExtraction() throws IOException
    {
        // The marker + embedded manifest are book-keeping for the importer
        // itself (persisted separately under imported-manifests/); they
        // must not land inside the pack's install folder alongside the
        // real content.
        LinkedHashMap< String, String > entries = new LinkedHashMap<>();
        entries.put( ModpackExporter.MARKER_FILENAME, "{}" );
        entries.put( ModpackExporter.MANIFEST_FILENAME, "{}" );
        entries.put( "mods/example.jar", "jarbytes" );
        File zip = createZip( "with-marker.zip", entries );
        Path dest = Files.createDirectory( tempDir.resolve( "extract-root-5" ) );
        try ( ZipFile zf = new ZipFile( zip ) ) {
            invokeExtractZipContents( zf, dest );
        }
        assertFalse( Files.exists( dest.resolve( ModpackExporter.MARKER_FILENAME ) ) );
        assertFalse( Files.exists( dest.resolve( ModpackExporter.MANIFEST_FILENAME ) ) );
        assertTrue( Files.exists( dest.resolve( "mods/example.jar" ) ) );
    }

    @Test
    void emptyNamedEntryFailsWithIOExceptionRatherThanCorruptingDestination() throws IOException
    {
        // A zero-length entry name resolves (after normalize()) to the
        // destination directory itself, which is still "inside" the
        // destination so the traversal guard lets it through — but
        // writing a *file* on top of the destination *directory* then
        // fails at the filesystem level. Documenting this as the actual
        // observed behavior: it fails loudly with an IOException rather
        // than silently corrupting the destination or throwing an
        // unchecked exception.
        File zip = createZip( "empty-name.zip", entries( "", "pwned" ) );
        Path dest = Files.createDirectory( tempDir.resolve( "extract-root-6" ) );
        try ( ZipFile zf = new ZipFile( zip ) ) {
            assertThrows( IOException.class, () -> invokeExtractZipContents( zf, dest ) );
        }
        // The destination directory itself must survive as a directory.
        assertTrue( Files.isDirectory( dest ) );
    }

    // ===================================================================
    //  importZip(File) — validation failures that never reach LocalPathManager
    // ===================================================================

    @Test
    void nullFileIsRejected()
    {
        ModpackZipImporter.ImportException ex =
                assertThrows( ModpackZipImporter.ImportException.class, () -> ModpackZipImporter.importZip( null ) );
        assertFromKey( "importer.error.pickZipFile", ex.getMessage() );
    }

    @Test
    void nonExistentFileIsRejected()
    {
        File missing = tempDir.resolve( "does-not-exist.zip" ).toFile();
        ModpackZipImporter.ImportException ex =
                assertThrows( ModpackZipImporter.ImportException.class, () -> ModpackZipImporter.importZip( missing ) );
        assertFromKey( "importer.error.pickZipFile", ex.getMessage() );
    }

    @Test
    void directoryInsteadOfFileIsRejected() throws IOException
    {
        File dir = Files.createDirectory( tempDir.resolve( "a-directory.zip" ) ).toFile();
        ModpackZipImporter.ImportException ex =
                assertThrows( ModpackZipImporter.ImportException.class, () -> ModpackZipImporter.importZip( dir ) );
        assertFromKey( "importer.error.pickZipFile", ex.getMessage() );
    }

    @Test
    void emptyFileIsRejectedRatherThanThrowingRawException() throws IOException
    {
        File zeroByte = tempDir.resolve( "empty.zip" ).toFile();
        assertTrue( zeroByte.createNewFile() );
        ModpackZipImporter.ImportException ex =
                assertThrows( ModpackZipImporter.ImportException.class, () -> ModpackZipImporter.importZip( zeroByte ) );
        // The precise wording comes straight from ZipFile's IOException;
        // what matters for the security boundary is that it surfaces as
        // the package's own checked ImportException, not a raw IOException.
        assertTrue( ex.getMessage() != null && !ex.getMessage().isBlank() );
    }

    @Test
    void truncatedGarbageZipIsRejectedRatherThanThrowingRawException() throws IOException
    {
        File garbage = tempDir.resolve( "garbage.zip" ).toFile();
        try ( FileOutputStream fos = new FileOutputStream( garbage ) ) {
            fos.write( "this is not a zip file at all, just noise".getBytes( StandardCharsets.UTF_8 ) );
        }
        ModpackZipImporter.ImportException ex =
                assertThrows( ModpackZipImporter.ImportException.class, () -> ModpackZipImporter.importZip( garbage ) );
        assertTrue( ex.getMessage() != null && !ex.getMessage().isBlank() );
    }

    @Test
    void validEmptyZipWithNoMarkerIsRejected() throws IOException
    {
        File zip = createZip( "no-marker-empty.zip", new LinkedHashMap<>() );
        ModpackZipImporter.ImportException ex =
                assertThrows( ModpackZipImporter.ImportException.class, () -> ModpackZipImporter.importZip( zip ) );
        assertFromKey( "zipImporter.error.notMicaExport", ex.getMessage() );
    }

    @Test
    void unrelatedZipWithNoMarkerIsRejected() throws IOException
    {
        // A generic ZIP that isn't a Mica export and doesn't carry the
        // three Technic-server signals either (mods/ + top-level jar +
        // launch script) — must fail cleanly rather than extracting
        // anything.
        File zip = createZip( "unrelated.zip", entries( "readme.txt", "hello there" ) );
        ModpackZipImporter.ImportException ex =
                assertThrows( ModpackZipImporter.ImportException.class, () -> ModpackZipImporter.importZip( zip ) );
        assertFromKey( "zipImporter.error.notMicaExport", ex.getMessage() );
    }

    @Test
    void corruptMarkerJsonIsRejected() throws IOException
    {
        File zip = createZip( "corrupt-marker.zip", entries( ModpackExporter.MARKER_FILENAME, "{ not valid json" ) );
        ModpackZipImporter.ImportException ex =
                assertThrows( ModpackZipImporter.ImportException.class, () -> ModpackZipImporter.importZip( zip ) );
        assertFromKey( "zipImporter.error.markerCorrupt", ex.getMessage() );
    }

    @Test
    void unrecognizedExportFormatIsRejected() throws IOException
    {
        File zip = createZip( "bad-format.zip",
                               entries( ModpackExporter.MARKER_FILENAME, "{\"format\":\"some-other-tool-v1\"}" ) );
        ModpackZipImporter.ImportException ex =
                assertThrows( ModpackZipImporter.ImportException.class, () -> ModpackZipImporter.importZip( zip ) );
        assertFromKey( "zipImporter.error.unknownFormat", ex.getMessage() );
    }

    @Test
    void missingEmbeddedManifestIsRejected() throws IOException
    {
        File zip = createZip( "no-manifest.zip",
                               entries( ModpackExporter.MARKER_FILENAME,
                                        "{\"format\":\"" + ModpackExporter.EXPORT_FORMAT_V2 + "\"}" ) );
        ModpackZipImporter.ImportException ex =
                assertThrows( ModpackZipImporter.ImportException.class, () -> ModpackZipImporter.importZip( zip ) );
        assertFromKey( "zipImporter.error.noManifest", ex.getMessage() );
    }

    @Test
    void corruptEmbeddedManifestIsRejected() throws IOException
    {
        LinkedHashMap< String, String > entries = new LinkedHashMap<>();
        entries.put( ModpackExporter.MARKER_FILENAME, "{\"format\":\"" + ModpackExporter.EXPORT_FORMAT_V2 + "\"}" );
        entries.put( ModpackExporter.MANIFEST_FILENAME, "{ this is not json" );
        File zip = createZip( "corrupt-manifest.zip", entries );
        ModpackZipImporter.ImportException ex =
                assertThrows( ModpackZipImporter.ImportException.class, () -> ModpackZipImporter.importZip( zip ) );
        assertFromKey( "zipImporter.error.manifestCorrupt", ex.getMessage() );
    }

    @Test
    void manifestMissingPackNameIsRejected() throws IOException
    {
        LinkedHashMap< String, String > entries = new LinkedHashMap<>();
        entries.put( ModpackExporter.MARKER_FILENAME, "{\"format\":\"" + ModpackExporter.EXPORT_FORMAT_V2 + "\"}" );
        entries.put( ModpackExporter.MANIFEST_FILENAME, "{\"someOtherField\":true}" );
        File zip = createZip( "no-packname.zip", entries );
        ModpackZipImporter.ImportException ex =
                assertThrows( ModpackZipImporter.ImportException.class, () -> ModpackZipImporter.importZip( zip ) );
        assertFromKey( "zipImporter.error.noPackName", ex.getMessage() );
    }

    // ===================================================================
    //  test helpers
    // ===================================================================

    private static LinkedHashMap< String, String > entries( String name, String content )
    {
        LinkedHashMap< String, String > map = new LinkedHashMap<>();
        map.put( name, content );
        return map;
    }

    private File createZip( String filename, LinkedHashMap< String, String > entries ) throws IOException
    {
        File zipFile = tempDir.resolve( filename ).toFile();
        try ( ZipOutputStream zos = new ZipOutputStream( new FileOutputStream( zipFile ) ) ) {
            for ( var entry : entries.entrySet() ) {
                zos.putNextEntry( new ZipEntry( entry.getKey() ) );
                zos.write( entry.getValue().getBytes( StandardCharsets.UTF_8 ) );
                zos.closeEntry();
            }
        }
        return zipFile;
    }

    /** Calls the package-private {@code extractZipContents} directly so the real
     *  Zip-Slip guard runs against a {@code @TempDir} destination instead of the real
     *  install folder that {@link ModpackZipImporter#importZip(File)} would resolve via
     *  {@code LocalPathManager}. The method is package-private specifically to make this
     *  possible without reflection. */
    private static void invokeExtractZipContents( ZipFile zip, Path dest ) throws IOException
    {
        ModpackZipImporter.extractZipContents( zip, dest );
    }
}
