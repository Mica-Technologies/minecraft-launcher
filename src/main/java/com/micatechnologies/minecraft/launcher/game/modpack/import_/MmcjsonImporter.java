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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Imports a standalone {@code .mmcjson} modpack manifest file into the
 * launcher. The file is validated as Mica modpack JSON, copied into the
 * launcher's {@code imported-manifests/} folder (so the install survives
 * the source file moving / being deleted / living on a USB stick that
 * gets unplugged), and registered through the standard
 * {@link GameModPackManager#installModPackByURL} pipeline using a
 * {@code file:} URL pointing at the copy.
 *
 * <p>Used by the macOS {@code Desktop.setOpenFileHandler} bridge in
 * {@link com.micatechnologies.minecraft.launcher.gui.SystemMenuBarManager}
 * (drag-drop onto the dock icon, double-click in Finder while the
 * launcher is already running) and intended to be reused by the
 * cold-start argv path on Windows / Linux when their double-click
 * delivery lands the file path as argv.</p>
 *
 * @since 2026.5
 */
public final class MmcjsonImporter
{
    private MmcjsonImporter() { /* static-only */ }

    /** Same folder Modrinth / ZIP / Technic-server / loader-version
     *  imports use, so the launcher's startup scan picks the manifest
     *  up consistently regardless of how it got here. */
    public static final String IMPORTED_MANIFESTS_DIR = MrpackImporter.IMPORTED_MANIFESTS_DIR;

    /** Soft cap on a single {@code .mmcjson} import. The launcher's
     *  manifest fetcher caps network manifests at 50 MiB; we apply the
     *  same here so a hostile drop can't OOM the launcher with a
     *  multi-gigabyte JSON. Real manifests run a few KiB to maybe
     *  hundreds of KiB. */
    private static final long MAX_MANIFEST_BYTES = 50L * 1024 * 1024;

    /**
     * Reads {@code mmcjsonFile}, validates it parses as a JSON object
     * with the minimum {@code packName} field, copies it into
     * {@code imported-manifests/mmcjson-<sanitized>.json}, and registers
     * it with {@link GameModPackManager#installModPackByURL}.
     *
     * @return the {@code file:} URL of the on-disk manifest the pack was
     *         registered under (the same URL the install index uses to
     *         identify it). Caller can use this to focus / reveal the
     *         freshly-installed pack in the UI.
     * @throws ImportException for any failure — file missing, file too
     *         large, JSON parse failure, missing {@code packName},
     *         disk write failure. The message is safe to surface to
     *         end users.
     */
    public static String importMmcjsonFile( File mmcjsonFile ) throws ImportException
    {
        if ( mmcjsonFile == null || !mmcjsonFile.isFile() ) {
            throw new ImportException( "Pick an existing .mmcjson file." );
        }
        if ( mmcjsonFile.length() > MAX_MANIFEST_BYTES ) {
            throw new ImportException( "Modpack manifest is suspiciously large ("
                                               + mmcjsonFile.length() + " bytes); refusing to import." );
        }

        String body;
        try {
            body = Files.readString( mmcjsonFile.toPath(), StandardCharsets.UTF_8 );
        }
        catch ( IOException e ) {
            throw new ImportException( "Couldn't read the .mmcjson file: " + e.getMessage() );
        }

        // Parse + minimal-shape check before we copy anything to disk.
        // We don't roundtrip through GameModPack here — that would lock
        // us into the current schema and drop any future / unknown
        // top-level fields the user's manifest might carry. The JsonObject
        // path keeps the file byte-identical through to imported-manifests/.
        JsonObject manifest;
        try {
            manifest = JsonParser.parseString( body ).getAsJsonObject();
        }
        catch ( Exception e ) {
            throw new ImportException( "This file isn't valid Mica modpack JSON." );
        }
        String packName = manifest.has( "packName" ) && manifest.get( "packName" ).isJsonPrimitive()
                          ? manifest.get( "packName" ).getAsString()
                          : null;
        if ( packName == null || packName.isBlank() ) {
            throw new ImportException( "Manifest is missing the required \"packName\" field." );
        }

        Path manifestPath;
        try {
            Path dir = Path.of( LocalPathManager.getLauncherConfigFolderPath(), IMPORTED_MANIFESTS_DIR );
            Files.createDirectories( dir );
            String safeName = sanitize( packName );
            manifestPath = dir.resolve( "mmcjson-" + safeName + ".json" );
            Files.writeString( manifestPath, body, StandardCharsets.UTF_8 );
        }
        catch ( IOException e ) {
            throw new ImportException( "Couldn't write the manifest into imported-manifests/: "
                                               + e.getMessage() );
        }

        String manifestUrl = manifestPath.toUri().toString();
        Logger.logStd( LocalizationManager.format( "log.mmcjsonImporter.wroteManifest",
                               manifestPath.toString(), packName ) );
        try {
            GameModPackManager.installModPackByURL( manifestUrl );
        }
        catch ( Exception e ) {
            throw new ImportException( "Couldn't register the imported pack: " + e.getMessage() );
        }
        return manifestUrl;
    }

    /** Path-component sanitizer matching the convention used by
     *  {@link MrpackImporter#sanitize}. Filenames inside
     *  {@code imported-manifests/} must be safe across Windows /
     *  macOS / Linux. */
    private static String sanitize( String raw )
    {
        if ( raw == null || raw.isBlank() ) return "imported";
        String s = raw.replaceAll( "[^a-zA-Z0-9._-]", "_" );
        return s.isEmpty() ? "imported" : s;
    }

    /** Mirrors {@link MrpackImporter.ImportException} /
     *  {@link ModpackZipImporter.ImportException} — checked exception so
     *  call sites can't forget to surface the failure. */
    public static class ImportException extends Exception
    {
        public ImportException( String message ) { super( message ); }
    }
}
