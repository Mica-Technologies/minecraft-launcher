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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.HashUtilities;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * End-to-end Modrinth {@code .mrpack} → Mica-format manifest pipeline.
 *
 * <p>The import preview path hands this class the URL of the primary
 * {@code .mrpack} file. From there:</p>
 * <ol>
 *   <li>Download the {@code .mrpack} to a temp file (bounded fetch).</li>
 *   <li>Open as a ZIP archive and extract {@code modrinth.index.json}.</li>
 *   <li>Parse it into a {@link ModrinthIndex}.</li>
 *   <li>Verify the mod loader is supported (Forge only for v1 — Fabric /
 *       NeoForge / Quilt return a clear failure that the GUI surfaces).</li>
 *   <li>Translate every file entry into a Mica-format manifest section
 *       (packMods / packConfigs / packResourcePacks / packShaderPacks
 *       depending on the {@code path} prefix).</li>
 *   <li>Derive the Forge installer URL from the dependencies block and
 *       fetch + hash it so the manifest carries both
 *       {@code packForgeURL} and {@code packForgeHash}.</li>
 *   <li>Write the resulting Mica manifest to
 *       {@code <launcher>/imported-manifests/<slug>-<versionId>.json}.</li>
 *   <li>Return the local {@code file://} URL so the caller can hand it
 *       to {@code GameModPackManager.installModPackByURL} and reuse the
 *       launcher's normal install pipeline.</li>
 * </ol>
 *
 * <p>Note: the {@code overrides/} folder inside the {@code .mrpack} — used
 * for config files, resource packs, etc. that aren't fetched from CDN URLs
 * — is currently NOT extracted to the pack root. v1 ships with mod-list
 * translation only; the overrides handoff to the launcher's pack-folder
 * lifecycle is a follow-up. Most packs work without overrides on first
 * launch; users may need to manually copy any config tweaks they want.</p>
 *
 * @since 2026.3
 */
public final class MrpackImporter
{
    /** Hard cap on the .mrpack download size. Real-world packs are typically
     *  1–20 MB (it's just the index + overrides; the mod jars live on
     *  separate CDN URLs). 200 MB is comfortable overhead headroom without
     *  letting a hostile or malformed response OOM the launcher. */
    private static final long MAX_MRPACK_BYTES = 200L * 1024 * 1024;

    /** Subdirectory under the launcher config dir where translated Mica
     *  manifests for imported modpacks live. Separate from
     *  {@code manifest_cache} (which holds copies of remote manifests for
     *  offline use) since these ARE the canonical source — there's no
     *  remote URL to revalidate against. */
    public static final String IMPORTED_MANIFESTS_DIR = "imported-manifests";

    /** Mod loaders the launcher's existing pack pipeline understands. v1
     *  imports Forge only; the others surface as an explicit failure so
     *  users don't get a confusing partial install. */
    private static final java.util.Set< String > SUPPORTED_LOADERS = java.util.Set.of( "forge" );

    private MrpackImporter() { /* static-only */ }

    /**
     * Outcome of an import attempt. {@code localManifestUrl} is the
     * {@code file://...} URL the caller hands to
     * {@code installModPackByURL}; {@code index} is the parsed Modrinth
     * index for the confirmation-dialog mod-list display.
     */
    public record Result( String localManifestUrl, ModrinthIndex index, int modCount )
    {
    }

    /**
     * Thrown when the import can't proceed for a user-meaningful reason
     * (unsupported loader, bad file, network failure during download).
     * The GUI catches this and surfaces {@link #getMessage} in the failure
     * notification — keep the wording user-readable.
     */
    public static final class ImportException extends Exception
    {
        public ImportException( String message ) { super( message ); }
        public ImportException( String message, Throwable cause ) { super( message, cause ); }
    }

    // ===== entry point =====

    /**
     * Runs the full download + parse + translate pipeline. Synchronous;
     * caller is expected to invoke this off the FX thread.
     *
     * @param mrpackDownloadUrl  CDN URL of the {@code .mrpack} archive
     *                           (typically from Modrinth's
     *                           {@code v2/version/{id}} response)
     * @param projectSlug        Modrinth slug, used for the local manifest
     *                           filename so subsequent imports of the same
     *                           pack overwrite cleanly
     */
    /**
     * @param mrpackDownloadUrl  CDN URL of the {@code .mrpack} archive
     * @param projectSlug        Modrinth slug for the local manifest filename
     * @param iconUrl            Optional Modrinth-hosted project icon URL; when
     *                           {@code null} the imported manifest falls back
     *                           to the bundled-default logo
     */
    public static Result importMrpack( String mrpackDownloadUrl, String projectSlug, String iconUrl ) throws ImportException
    {
        if ( mrpackDownloadUrl == null || mrpackDownloadUrl.isBlank() ) {
            throw new ImportException( "No download URL was provided for the pack." );
        }

        Path tempMrpack = null;
        try {
            Logger.logStd( "Modrinth import: starting for slug=" + projectSlug
                                   + " from " + mrpackDownloadUrl );

            // (1) Download the .mrpack to a temp file.
            Logger.logStd( "Modrinth import: downloading .mrpack archive…" );
            tempMrpack = downloadMrpack( mrpackDownloadUrl );
            Logger.logStd( "Modrinth import: archive downloaded to "
                                   + tempMrpack + " (" + tempMrpack.toFile().length() + " bytes)" );

            // (2) Open as ZIP + (3) parse modrinth.index.json.
            Logger.logStd( "Modrinth import: parsing modrinth.index.json…" );
            ModrinthIndex index = parseIndex( tempMrpack );
            if ( index == null ) {
                throw new ImportException( "The downloaded archive doesn't look like a Modrinth modpack "
                                                   + "(no modrinth.index.json inside)." );
            }
            Logger.logStd( "Modrinth import: parsed pack=\"" + index.name
                                   + "\" version=" + index.versionId
                                   + " files=" + ( index.files == null ? 0 : index.files.size() ) );

            // (4) Loader check — refuse anything other than Forge for v1.
            String loader = pickLoader( index );
            if ( loader == null ) {
                Logger.logStd( "Modrinth import: refusing pack — unsupported loader. "
                                       + "Dependencies: " + index.dependencies );
                throw new ImportException( "This pack uses a mod loader the launcher can't import yet. "
                                                   + "Supported in this version: Forge." );
            }
            String loaderVersion = index.dependencies.get( loader );
            String mcVersion = index.dependencies.get( "minecraft" );
            if ( mcVersion == null || mcVersion.isBlank() ) {
                throw new ImportException( "Pack manifest is missing a Minecraft version." );
            }
            Logger.logStd( "Modrinth import: target MC=" + mcVersion
                                   + " " + loader + "=" + loaderVersion );

            // (5) + (6) Build Mica manifest JSON.
            Logger.logStd( "Modrinth import: building Mica manifest + fetching Forge installer for hash…" );
            JsonObject manifest = buildMicaManifest( index, mcVersion, loaderVersion, iconUrl );
            Logger.logStd( "Modrinth import: manifest built" );

            // (7) Write the manifest to disk.
            Path manifestPath = writeManifestToDisk( manifest, projectSlug, index.versionId );
            Logger.logStd( "Modrinth import: wrote translated Mica manifest to " + manifestPath );

            // (8) Return the file URL.
            String fileUrl = manifestPath.toUri().toString();
            int modCount = countMods( index );
            Logger.logStd( "Modrinth import: success — " + modCount + " mods staged, file URL " + fileUrl );
            return new Result( fileUrl, index, modCount );
        }
        catch ( ImportException e ) {
            throw e;
        }
        catch ( Throwable t ) {
            throw new ImportException( "Import failed: " + t.getClass().getSimpleName()
                                               + " — " + t.getMessage(), t );
        }
        finally {
            if ( tempMrpack != null ) {
                try { Files.deleteIfExists( tempMrpack ); } catch ( IOException ignored ) {}
            }
        }
    }

    // ===== stages =====

    /** Streams the {@code .mrpack} into a temp file alongside the launcher
     *  config dir. Bounded to {@link #MAX_MRPACK_BYTES} so a hostile or
     *  malformed CDN response can't OOM the launcher. */
    private static Path downloadMrpack( String url ) throws ImportException
    {
        try {
            Path dest = Files.createTempFile( "mica-mrpack-", ".mrpack" );
            try ( InputStream is = new URL( url ).openStream() ) {
                long copied = Files.copy( is, dest, StandardCopyOption.REPLACE_EXISTING );
                if ( copied > MAX_MRPACK_BYTES ) {
                    Files.deleteIfExists( dest );
                    throw new ImportException( "Pack archive exceeded the " + MAX_MRPACK_BYTES
                                                       + "-byte cap; aborting." );
                }
            }
            return dest;
        }
        catch ( ImportException e ) {
            throw e;
        }
        catch ( IOException e ) {
            throw new ImportException( "Could not download the pack archive: " + e.getMessage(), e );
        }
    }

    /** Opens the {@code .mrpack} as a ZIP, locates
     *  {@code modrinth.index.json}, parses it with Gson. */
    private static ModrinthIndex parseIndex( Path mrpack ) throws ImportException
    {
        try ( ZipFile zip = new ZipFile( mrpack.toFile() ) ) {
            ZipEntry entry = zip.getEntry( "modrinth.index.json" );
            if ( entry == null ) return null;
            try ( InputStream is = zip.getInputStream( entry ) ) {
                String body = new String( is.readAllBytes(), StandardCharsets.UTF_8 );
                ModrinthIndex parsed = JSONUtilities.getGson().fromJson( body, ModrinthIndex.class );
                if ( parsed != null && parsed.files == null ) parsed.files = new ArrayList<>();
                return parsed;
            }
        }
        catch ( IOException e ) {
            throw new ImportException( "Couldn't read modrinth.index.json from the archive: "
                                               + e.getMessage(), e );
        }
    }

    /** Returns the first dependency key that names a supported mod loader,
     *  or {@code null} when the pack uses an unsupported loader. */
    private static String pickLoader( ModrinthIndex index )
    {
        if ( index == null || index.dependencies == null ) return null;
        for ( String key : index.dependencies.keySet() ) {
            if ( SUPPORTED_LOADERS.contains( key ) ) return key;
        }
        return null;
    }

    /** Translates the parsed Modrinth index into a Mica-format manifest
     *  JsonObject. Files under {@code mods/} become packMods; everything
     *  else (config, resourcepacks, shaderpacks) goes into the matching
     *  Mica section. The Forge installer URL is derived from the loader
     *  version + Minecraft version; its SHA-1 is computed by downloading
     *  the installer here so the manifest the launcher consumes is
     *  fully self-contained. */
    private static JsonObject buildMicaManifest( ModrinthIndex index,
                                                  String mcVersion,
                                                  String forgeVersion,
                                                  String iconUrl ) throws ImportException
    {
        JsonObject manifest = new JsonObject();
        // Pack-level metadata. Names match the Mica manifest schema 1:1 so
        // GSON deserializes back cleanly when the launcher loads the file.
        String packName = ( index.name != null && !index.name.isBlank() )
                ? index.name : "Imported Modrinth Pack";
        manifest.addProperty( "packName", packName );
        // Pack authors often cosmetically prefix the version with a "v" (e.g.
        // "v43", "V2.1.0"); the launcher always prepends its own "v" when
        // rendering versions in the UI, so leaving an author-supplied "v" in
        // place would produce "vv43" on cards. Strip a single leading v/V
        // when followed by a digit so we don't accidentally maul versions
        // that legitimately start with the letter (e.g. "vintage-1.0").
        manifest.addProperty( "packVersion", normalizeImportedVersion( index.versionId ) );
        manifest.addProperty( "packURL", "https://modrinth.com/" );
        manifest.addProperty( "packMinRAMGB", "4" );
        // Eagerly stage the Modrinth-hosted project icon into the launcher's
        // shared metadata folder under its content-addressed <sha1>.png slot.
        // Doing the download here — synchronously, while the import flow is
        // already running with user-visible progress — guarantees the icon
        // file exists on disk by the time the imported manifest is written,
        // and emitting packLogoSha1 alongside packLogoURL means the
        // launcher's cacheImages() lands on the SHA-1 fast path on every
        // subsequent load (no re-fetch, no transient-blank window on cold
        // start). When Modrinth returns no icon, or the fetch fails, the
        // manifest falls back to the bundled default URL with no SHA-1, and
        // the card renders the launcher logo from the classpath resource.
        String logoUrl;
        String logoSha1 = stageImportedLogo( iconUrl );
        if ( logoSha1 != null ) {
            logoUrl = iconUrl;
            manifest.addProperty( "packLogoSha1", logoSha1 );
            Logger.logStd( "Modrinth import: staged project icon to metadata cache (sha1=" + logoSha1 + ")" );
        }
        else {
            logoUrl = com.micatechnologies.minecraft.launcher.consts.ModPackConstants.MODPACK_DEFAULT_LOGO_URL;
            Logger.logStd( "Modrinth import: no project icon staged (iconUrl=" + iconUrl
                                   + "); using bundled default logo" );
        }
        manifest.addProperty( "packLogoURL", logoUrl );
        manifest.addProperty( "packBackgroundURL",
                              com.micatechnologies.minecraft.launcher.consts.ModPackConstants.MODPACK_DEFAULT_BG_URL );

        // Forge installer. Maven-coords URL is well-known; we compute the
        // SHA-1 of the actual installer bytes so the launcher's later
        // hash-verify pass sees a match. A failure here is fatal — we'd
        // ship a manifest the launcher would reject on install.
        String forgeUrl = buildForgeInstallerUrl( mcVersion, forgeVersion );
        String forgeHash = computeForgeInstallerSha1( forgeUrl );
        if ( forgeHash == null ) {
            throw new ImportException( "Couldn't fetch the Forge installer at " + forgeUrl
                                               + " to compute its hash. Check the Minecraft / Forge "
                                               + "versions in the pack." );
        }
        manifest.addProperty( "packForgeURL", forgeUrl );
        manifest.addProperty( "packForgeHash", forgeHash );

        // Empty scan-exclusions list — pack authors can edit the local file
        // if they want to whitelist anything specific. Acknowledgements
        // similarly empty by default.
        manifest.add( "packScanExclusions", new JsonArray() );
        manifest.add( "packScanAcknowledgements", new JsonArray() );

        // File translation. Each .mrpack file becomes a packMod /
        // packConfig / packResourcePack / packShaderPack depending on
        // the path prefix. Mica's schema sorts files into separate
        // top-level arrays for these, vs Modrinth's single flat list.
        JsonArray packMods         = new JsonArray();
        JsonArray packConfigs      = new JsonArray();
        JsonArray packResourcePacks = new JsonArray();
        JsonArray packShaderPacks  = new JsonArray();

        for ( ModrinthIndex.File f : index.files ) {
            if ( f == null || f.path == null || f.downloads == null || f.downloads.isEmpty() ) continue;
            // Drop files whose env is "unsupported" on the client. The
            // launcher is a client-side tool; server-only files have no
            // home on disk under our install layout.
            if ( f.env != null && "unsupported".equalsIgnoreCase( f.env.client ) ) continue;

            String entryPath = f.path.replace( '\\', '/' );
            String filename = filenameOf( entryPath );
            String url = f.downloads.get( 0 );
            String sha1 = ( f.hashes != null ) ? f.hashes.sha1 : null;
            if ( sha1 == null || sha1.isBlank() ) {
                // Mica's pack pipeline requires SHA-1 for verification. Skip
                // any file Modrinth didn't ship a sha1 for — exceedingly rare
                // (their CDN always exposes both sha1 + sha512).
                Logger.logWarningSilent( "Skipping mrpack entry without sha1: " + entryPath );
                continue;
            }
            boolean clientReq = f.env == null || !"unsupported".equalsIgnoreCase( f.env.client );
            boolean serverReq = f.env != null && "required".equalsIgnoreCase( f.env.server );

            JsonObject node = new JsonObject();
            node.addProperty( "name", filename );
            node.addProperty( "remote", url );
            node.addProperty( "local", filename );
            node.addProperty( "sha1", sha1 );
            node.addProperty( "clientReq", clientReq );
            node.addProperty( "serverReq", serverReq );

            String lowerPath = entryPath.toLowerCase( java.util.Locale.ROOT );
            if ( lowerPath.startsWith( "mods/" ) ) packMods.add( node );
            else if ( lowerPath.startsWith( "config/" ) ) packConfigs.add( node );
            else if ( lowerPath.startsWith( "resourcepacks/" ) ) packResourcePacks.add( node );
            else if ( lowerPath.startsWith( "shaderpacks/" ) ) packShaderPacks.add( node );
            else {
                // Unknown path prefix — treat as a mod by default. Most
                // alternative layouts mean it, and at worst Mica's installer
                // will refuse to drop it somewhere broken.
                packMods.add( node );
            }
        }

        manifest.add( "packMods", packMods );
        manifest.add( "packConfigs", packConfigs );
        manifest.add( "packResourcePacks", packResourcePacks );
        manifest.add( "packShaderPacks", packShaderPacks );
        manifest.add( "packInitialFiles", new JsonArray() );

        return manifest;
    }

    /** Pings the Forge Maven coordinate that the version implies, then
     *  computes a SHA-1 of the downloaded installer. Returns the hex hash
     *  on success, or {@code null} on any error (which the caller surfaces
     *  as a user-readable import failure). */
    private static String computeForgeInstallerSha1( String installerUrl )
    {
        try {
            Path tempInstaller = Files.createTempFile( "mica-forge-installer-", ".jar" );
            try {
                try ( InputStream is = new URL( installerUrl ).openStream() ) {
                    Files.copy( is, tempInstaller, StandardCopyOption.REPLACE_EXISTING );
                }
                return HashUtilities.getFileSHA1( tempInstaller.toFile() );
            }
            finally {
                Files.deleteIfExists( tempInstaller );
            }
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "Could not fetch / hash Forge installer at "
                                             + installerUrl + ": " + t.getMessage() );
            return null;
        }
    }

    /** Downloads the Modrinth-hosted project icon (when available) into the
     *  launcher's shared metadata folder under {@code <sha1>.png}, mirroring
     *  the on-disk layout {@code GameModPackEnvironment#cacheImages} would
     *  produce on first load. Returning the hex SHA-1 lets the importer
     *  emit {@code packLogoSha1} in the manifest, which short-circuits the
     *  launcher's image-cache step to the SHA-1 fast path and avoids the
     *  cold-start "blank logo" window we were seeing when imported packs
     *  hadn't yet been through a successful cacheImages run.
     *
     *  Modrinth's upload pipeline transcodes most project icons to WebP for
     *  CDN efficiency, but JavaFX 26's {@code Image} class only natively
     *  decodes BMP / GIF / JPEG / PNG. The TwelveMonkeys imageio-webp
     *  plugin on the classpath gives {@code ImageIO} a WebP reader, so
     *  {@link ImageFormatUtilities#ensureJavaFxDecodable} can transcode
     *  WebP to PNG in place before we hash + stage the file. The SHA-1 is
     *  computed on the post-transcode PNG bytes so the runtime's image
     *  cache finds the same file we wrote.
     *
     *  Returns {@code null} (rather than throwing) on any failure — no
     *  iconUrl, non-https URL, network error, hash failure, unsupported
     *  format. Callers are expected to fall back to the bundled-default
     *  logo URL in that case. */
    private static String stageImportedLogo( String iconUrl )
    {
        if ( iconUrl == null || iconUrl.isBlank() ) return null;
        // Only http(s) icons are safe to fetch from a third-party manifest —
        // the launcher's later requireHttps check would reject anything else
        // anyway, but we screen here to avoid wasting a download attempt on
        // a clearly-broken URL.
        String lower = iconUrl.toLowerCase( java.util.Locale.ROOT );
        if ( !lower.startsWith( "https://" ) && !lower.startsWith( "http://" ) ) return null;

        Path tempIcon = null;
        try {
            tempIcon = Files.createTempFile( "mica-imported-logo-", ".png" );
            File tempFile = tempIcon.toFile();
            NetworkUtilities.downloadFileFromURL( new URL( iconUrl ), tempFile );

            // Transcode WebP / anything-non-JavaFX-native to PNG in place.
            // No-op when the bytes are already PNG / JPEG / GIF / BMP.
            if ( !com.micatechnologies.minecraft.launcher.utilities.ImageFormatUtilities
                    .ensureJavaFxDecodable( tempFile ) ) {
                Logger.logStd( "Modrinth import: project icon at " + iconUrl
                                       + " is in a format ImageIO can't decode; "
                                       + "falling back to the bundled-default logo." );
                return null;
            }

            String sha1 = HashUtilities.getFileSHA1( tempFile );
            if ( sha1 == null || sha1.isBlank() ) return null;

            // Mirror the destination GameModPackEnvironment#computedImageFile
            // produces — <metadata>/<sha1>.png — so the launcher's
            // packLogoSha1 fast path finds the file we just wrote without
            // any path translation.
            Path metadataDir = Path.of( LocalPathManager.getLauncherMetadataFolderPath() );
            Files.createDirectories( metadataDir );
            Path dest = metadataDir.resolve( sha1 + ".png" );
            if ( !Files.exists( dest ) ) {
                Files.move( tempIcon, dest, StandardCopyOption.REPLACE_EXISTING );
                tempIcon = null; // ownership transferred — skip the finally-block delete
            }
            return sha1;
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "Could not stage imported pack logo from "
                                             + iconUrl + ": " + t.getClass().getSimpleName()
                                             + " — " + t.getMessage() );
            return null;
        }
        finally {
            if ( tempIcon != null ) {
                try { Files.deleteIfExists( tempIcon ); } catch ( IOException ignored ) {}
            }
        }
    }

    /** Builds the Forge Maven URL for the given MC + Forge version pair.
     *  Mojang and Forge both publish under this stable Maven coordinate
     *  shape, so this is a deterministic construction. */
    private static String buildForgeInstallerUrl( String mcVersion, String forgeVersion )
    {
        return String.format(
                "https://maven.minecraftforge.net/net/minecraftforge/forge/%s-%s/forge-%s-%s-installer.jar",
                mcVersion, forgeVersion, mcVersion, forgeVersion );
    }

    /** Writes the Mica-format manifest into {@code <config>/imported-manifests/}
     *  with a filename that's stable per (slug, version-id) so re-imports of
     *  the same pack overwrite cleanly. */
    private static Path writeManifestToDisk( JsonObject manifest, String slug, String versionId ) throws ImportException
    {
        try {
            Path dir = Path.of( LocalPathManager.getLauncherConfigFolderPath(), IMPORTED_MANIFESTS_DIR );
            Files.createDirectories( dir );
            String safeSlug = sanitize( slug, "imported" );
            String safeVersion = sanitize( versionId, "unknown" );
            Path manifestPath = dir.resolve( "modrinth-" + safeSlug + "-" + safeVersion + ".json" );
            Files.writeString( manifestPath, JSONUtilities.getGson().toJson( manifest ),
                                StandardCharsets.UTF_8 );
            return manifestPath;
        }
        catch ( IOException e ) {
            throw new ImportException( "Couldn't write the translated manifest to disk: "
                                               + e.getMessage(), e );
        }
    }

    /** Cleans the version string pulled from {@code modrinth.index.json}'s
     *  {@code versionId} field before writing it to {@code packVersion}.
     *  Pack authors frequently prefix a cosmetic "v"/"V" ("v43", "V2.1.0",
     *  etc.) but the launcher's card UI prepends its own "v" at render time,
     *  so leaving the prefix in produces "vv43". The strip is only applied
     *  when the next character is a digit so we don't damage versions that
     *  genuinely start with the letter v (think "vintage-1.0"). Empty /
     *  null inputs degrade to "1.0.0" — same as the historical behavior. */
    static String normalizeImportedVersion( String raw )
    {
        if ( raw == null || raw.isBlank() ) return "1.0.0";
        String trimmed = raw.trim();
        if ( trimmed.length() >= 2
                && ( trimmed.charAt( 0 ) == 'v' || trimmed.charAt( 0 ) == 'V' )
                && Character.isDigit( trimmed.charAt( 1 ) ) ) {
            return trimmed.substring( 1 );
        }
        return trimmed;
    }

    /** Quick path-component sanitizer. Filenames inside imported-manifests/
     *  must be safe across Windows/macOS/Linux. */
    private static String sanitize( String raw, String fallback )
    {
        if ( raw == null || raw.isBlank() ) return fallback;
        String s = raw.replaceAll( "[^a-zA-Z0-9._-]", "_" );
        return s.isEmpty() ? fallback : s;
    }

    private static String filenameOf( String path )
    {
        if ( path == null ) return null;
        int slash = path.lastIndexOf( '/' );
        return slash < 0 ? path : path.substring( slash + 1 );
    }

    private static int countMods( ModrinthIndex index )
    {
        int count = 0;
        if ( index == null || index.files == null ) return 0;
        for ( ModrinthIndex.File f : index.files ) {
            if ( f != null && f.path != null
                    && f.path.toLowerCase( java.util.Locale.ROOT ).startsWith( "mods/" ) ) {
                count++;
            }
        }
        return count;
    }
}
