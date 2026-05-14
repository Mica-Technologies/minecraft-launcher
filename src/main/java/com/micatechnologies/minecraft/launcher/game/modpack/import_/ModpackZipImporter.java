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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.game.modpack.ModpackExporter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reverse of {@link ModpackExporter}: takes a Mica-export ZIP file and
 * installs the pack contained inside.
 *
 * <h3>Validation</h3>
 * The importer rejects ZIPs that don't carry a {@link ModpackExporter#MARKER_FILENAME}
 * marker entry at the root, or whose marker {@code format} field doesn't start
 * with {@code mica-export-v}. This is the line of defense against the user
 * pointing the import action at an arbitrary ZIP (an unrelated archive, a
 * generic Forge installer ZIP, a mod author's prerelease) — without the
 * marker check, the importer would happily extract a random tree of files
 * into the launcher's modpack folder.
 *
 * <h3>Install layout</h3>
 * Files are extracted into the pack's install folder, derived from the
 * embedded manifest's {@code packName} using the same sanitization
 * {@code GameModPack.getPackSanitizedName} applies (non-alphanumerics
 * stripped). The marker and the embedded {@code manifest.json} are not
 * copied into the install folder — the manifest is written separately to
 * {@code imported-manifests/zip-&lt;slug&gt;.json} so the regular
 * {@code installModPackByURL} pipeline finds it via the same file: URL
 * scheme the Modrinth / Technic imports use.
 *
 * <h3>Mod sync</h3>
 * After extraction, the regular install pipeline still runs SHA / hash
 * verification on every {@code packMods} entry. For ZIP-imported packs
 * whose manifest references local files, this means the verifier finds
 * the mods already on disk and skips redownload. For packs whose mods
 * are HTTP/S URLs, the verifier may redownload mismatched copies — by
 * design, since the embedded manifest is authoritative.
 *
 * @since 2026.5
 */
public final class ModpackZipImporter
{
    private ModpackZipImporter() { /* static-only */ }

    /** Where the imported manifest gets written. Same folder Modrinth /
     *  loader-version imports use so the launcher's startup scan picks
     *  it up consistently. */
    public static final String IMPORTED_MANIFESTS_DIR = MrpackImporter.IMPORTED_MANIFESTS_DIR;

    /**
     * Imports the pack contained in {@code zipFile}. Validates the marker,
     * extracts contents into the pack's install folder, writes the embedded
     * manifest to {@code imported-manifests/}, and registers the pack with
     * {@link GameModPackManager#installModPackByURL}.
     *
     * @return the {@code file:} URL of the on-disk manifest the pack was
     *         registered under (same one {@link GameModPackManager} uses
     *         to identify it).
     * @throws ImportException for any failure — invalid ZIP, missing
     *         marker, manifest unreadable, extract IO error, install
     *         failure. The message is safe to surface to end users.
     */
    public static String importZip( File zipFile ) throws ImportException
    {
        if ( zipFile == null || !zipFile.isFile() ) {
            throw new ImportException( "Pick an existing ZIP file." );
        }

        try ( ZipFile zip = new ZipFile( zipFile ) ) {
            // Step 1: validate marker. Reject any ZIP that doesn't carry
            // a recognizable mica-export marker — that's how we tell a
            // Mica modpack export apart from any other random ZIP a user
            // might point this importer at.
            ZipEntry markerEntry = zip.getEntry( ModpackExporter.MARKER_FILENAME );
            if ( markerEntry == null ) {
                throw new ImportException( "This ZIP isn't a Mica modpack export — no "
                                                    + ModpackExporter.MARKER_FILENAME
                                                    + " marker file found at the archive root." );
            }
            String markerJson;
            try ( InputStream in = zip.getInputStream( markerEntry ) ) {
                markerJson = new String( in.readAllBytes(), StandardCharsets.UTF_8 );
            }
            String format;
            try {
                JsonObject markerObj = JsonParser.parseString( markerJson ).getAsJsonObject();
                format = markerObj.has( "format" ) ? markerObj.get( "format" ).getAsString() : null;
            }
            catch ( Throwable t ) {
                throw new ImportException( "Marker file is corrupt: " + t.getMessage() );
            }
            if ( format == null || !format.startsWith( "mica-export-v" ) ) {
                throw new ImportException( "Unrecognized export format: " + format
                                                    + ". This launcher only knows how to import "
                                                    + ModpackExporter.EXPORT_FORMAT_V2 + " or compatible ZIPs." );
            }

            // Step 2: read the embedded manifest. v1 exports didn't include
            // the manifest body — surface a friendly upgrade message instead
            // of silently failing partway through extraction.
            ZipEntry manifestEntry = zip.getEntry( ModpackExporter.MANIFEST_FILENAME );
            if ( manifestEntry == null ) {
                throw new ImportException( "This ZIP doesn't include a pack manifest. "
                                                    + "Older exports (mica-export-v1) didn't embed one — "
                                                    + "ask the sender to re-export with the current launcher." );
            }
            String manifestBody;
            try ( InputStream in = zip.getInputStream( manifestEntry ) ) {
                manifestBody = new String( in.readAllBytes(), StandardCharsets.UTF_8 );
            }
            JsonObject manifestObj;
            String packName;
            try {
                manifestObj = JsonParser.parseString( manifestBody ).getAsJsonObject();
                packName = manifestObj.has( "packName" )
                        ? manifestObj.get( "packName" ).getAsString() : null;
            }
            catch ( Throwable t ) {
                throw new ImportException( "Embedded manifest is corrupt: " + t.getMessage() );
            }
            if ( packName == null || packName.isBlank() ) {
                throw new ImportException( "Embedded manifest has no packName — can't determine install folder." );
            }

            // Step 3: compute install folder. Mirrors GameModPack.getPackSanitizedName
            // and GameModPackMetadata.getPackRootFolder. Done here (rather than
            // delegating to GameModPack) so we can extract before the manager
            // sees the manifest — the regular install path verifies mod files
            // exist, and they need to be in place by then.
            String sanitized = packName.replaceAll( "[^a-zA-Z0-9]", "" );
            if ( sanitized.isEmpty() ) sanitized = "ImportedPack";
            Path installFolder = Path.of( LocalPathManager.getLauncherModpackFolderPath(), sanitized );
            Files.createDirectories( installFolder );

            // Step 4: extract everything except the marker + embedded manifest
            // (those are book-keeping, not pack content). The standard pack
            // sync pipeline owns mods/, config/, etc. at runtime — having the
            // files on disk lets the SHA verifier short-circuit redownloads.
            extractZipContents( zip, installFolder );

            // Step 5: write the embedded manifest to imported-manifests/ and
            // register through the standard add-by-URL path. installModPackByURL
            // resolves the pack root using getPackRootFolder() — derived from
            // packName via getPackSanitizedName — which matches the folder we
            // just extracted into.
            String manifestFilename = "zip-" + sanitize( sanitized ) + ".json";
            Path manifestPath = Path.of( LocalPathManager.getLauncherConfigFolderPath(),
                                            IMPORTED_MANIFESTS_DIR,
                                            manifestFilename );
            Files.createDirectories( manifestPath.getParent() );
            Files.writeString( manifestPath, manifestBody, StandardCharsets.UTF_8 );
            Logger.logStd( "ZIP import: wrote manifest at " + manifestPath
                                   + " and extracted pack contents to " + installFolder );

            String manifestUrl = manifestPath.toUri().toString();
            GameModPackManager.installModPackByURL( manifestUrl );
            return manifestUrl;
        }
        catch ( ImportException ie ) {
            throw ie;
        }
        catch ( IOException ioe ) {
            throw new ImportException( "Couldn't read the ZIP file: " + ioe.getMessage() );
        }
        catch ( Throwable t ) {
            Logger.logErrorSilent( "ZIP import unexpected failure: " + t.getMessage() );
            throw new ImportException( "Unexpected error during import: " + t.getMessage() );
        }
    }

    /** Streams every entry in {@code zip} into {@code dest}, skipping the
     *  two top-level book-keeping entries (marker + manifest). Path
     *  traversal is blocked by checking the resolved path stays inside
     *  {@code dest} — a malicious ZIP containing {@code ../../etc/passwd}
     *  entries would otherwise extract outside the install folder. */
    private static void extractZipContents( ZipFile zip, Path dest ) throws IOException
    {
        Path destNormalized = dest.toAbsolutePath().normalize();
        Enumeration< ? extends ZipEntry > entries = zip.entries();
        while ( entries.hasMoreElements() ) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            // Skip the two book-keeping entries; they belong with the
            // manifest in imported-manifests/, not in the pack root.
            if ( name.equals( ModpackExporter.MARKER_FILENAME )
                    || name.equals( ModpackExporter.MANIFEST_FILENAME ) ) {
                continue;
            }
            Path target = destNormalized.resolve( name ).normalize();
            if ( !target.startsWith( destNormalized ) ) {
                // Path traversal attempt — bail loudly rather than silently
                // skip so the user knows something is off with the ZIP.
                throw new IOException( "ZIP entry escapes target folder: " + name );
            }
            if ( entry.isDirectory() ) {
                Files.createDirectories( target );
                continue;
            }
            Path parent = target.getParent();
            if ( parent != null ) Files.createDirectories( parent );
            try ( InputStream in = zip.getInputStream( entry ) ) {
                Files.copy( in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING );
            }
        }
    }

    private static String sanitize( String s )
    {
        if ( s == null ) return "unknown";
        return s.replaceAll( "[^A-Za-z0-9._-]", "_" );
    }

    /** Mirrors {@link MrpackImporter.ImportException} / {@link TechnicImporter.ImportException}
     *  so callers can catch a single import-failure type if they import
     *  generically across all three paths. */
    public static class ImportException extends Exception
    {
        public ImportException( String message ) { super( message ); }
    }
}
