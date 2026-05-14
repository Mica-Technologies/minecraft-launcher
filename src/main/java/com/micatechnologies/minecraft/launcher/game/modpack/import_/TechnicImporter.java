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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports a Technic Launcher modpack via the Technic Solder API.
 *
 * <p>Technic's API exposes per-modpack metadata at
 * {@code https://api.technicpack.net/modpack/<slug>}, with a per-build
 * sub-resource at {@code .../<slug>/<build>} returning a JSON list of
 * mods (name, version, download URL, MD5) plus the Minecraft version
 * the build targets. We translate that into the Mica manifest schema:
 * the modpack metadata + each mod as a {@code packMods} entry with
 * the Technic-hosted URL and MD5 hash.</p>
 *
 * <p>Many Technic packs target older Minecraft versions (1.7.10 era
 * was where Technic peaked) and ship pre-bundled with their own mod
 * loader rather than vanilla Forge. We surface those packs as
 * Forge-loader packs with the build's reported Minecraft version
 * since the user-facing launch path expects a known loader value;
 * authors of MC 1.5-era technic packs that don't fit cleanly into
 * the Forge / NeoForge / Fabric trio will get a "couldn't find
 * Forge for 1.5.2" failure on first launch — at which point the
 * imported manifest can be hand-edited.</p>
 *
 * @since 2026.5
 */
public final class TechnicImporter
{
    private TechnicImporter() { /* static-only */ }

    /** Where the imported manifest gets written. Same folder Modrinth /
     *  loader-version imports use so the launcher's existing
     *  imported-manifests scan picks it up at startup. */
    public static final String IMPORTED_MANIFESTS_DIR = MrpackImporter.IMPORTED_MANIFESTS_DIR;

    private static final String API_BASE = "https://api.technicpack.net/modpack/";

    /** Result of {@link #fetchSummary} — drives the import preview
     *  dialog. {@code builds} is newest-first when the API returns
     *  it that way (most do); {@code recommended} is the build the
     *  Technic pack's author has marked as stable. */
    public record ProjectSummary(
            String slug,
            String displayName,
            String description,
            String iconUrl,
            String recommended,
            String latest,
            List< String > builds )
    {}

    /** Fetches the project summary for {@code slug}. Returns null on
     *  any network / parse failure (logged silently) — caller falls
     *  back to a generic "couldn't load Technic pack" message in the
     *  preview UI. */
    public static ProjectSummary fetchSummary( String slug )
    {
        if ( slug == null || slug.isBlank() ) return null;
        try {
            String raw = NetworkUtilities.downloadFileFromURL( API_BASE + slug );
            JsonObject obj = JSONUtilities.getGson().fromJson( raw, JsonObject.class );
            if ( obj == null ) return null;
            String displayName = optString( obj, "display_name" );
            String description = optString( obj, "description" );
            String iconUrl = null;
            if ( obj.has( "icon" ) && obj.get( "icon" ).isJsonObject() ) {
                iconUrl = optString( obj.getAsJsonObject( "icon" ), "url" );
            }
            String recommended = optString( obj, "recommended" );
            String latest = optString( obj, "latest" );
            List< String > builds = new ArrayList<>();
            if ( obj.has( "builds" ) && obj.get( "builds" ).isJsonArray() ) {
                for ( JsonElement el : obj.getAsJsonArray( "builds" ) ) {
                    if ( el != null && el.isJsonPrimitive() ) builds.add( el.getAsString() );
                }
            }
            return new ProjectSummary( slug, displayName, description, iconUrl,
                                       recommended, latest, builds );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "TechnicImporter: project fetch failed for " + slug
                                             + " — " + t.getClass().getSimpleName()
                                             + ": " + t.getMessage() );
            return null;
        }
    }

    /**
     * Imports the given Technic build. Fetches the per-build manifest,
     * translates it to the Mica schema, writes it to
     * {@code <config>/imported-manifests/} and registers it with
     * {@link com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager}.
     *
     * @return the {@code file:} URL of the on-disk manifest that the
     *         rest of the launcher uses to identify it.
     * @throws ImportException when the API call fails or the build
     *                         manifest is missing required fields.
     */
    public static String importBuild( ProjectSummary summary, String build ) throws ImportException
    {
        if ( summary == null ) throw new ImportException( "Project summary is null." );
        if ( build == null || build.isBlank() ) {
            build = summary.recommended() != null && !summary.recommended().isBlank()
                    ? summary.recommended() : summary.latest();
        }
        if ( build == null || build.isBlank() ) {
            throw new ImportException( "No build to import — Technic pack has no recommended or latest build." );
        }
        JsonObject buildObj;
        try {
            String raw = NetworkUtilities.downloadFileFromURL(
                    API_BASE + summary.slug() + "/" + build );
            buildObj = JSONUtilities.getGson().fromJson( raw, JsonObject.class );
        }
        catch ( Exception ex ) {
            throw new ImportException( "Couldn't fetch Technic build " + build + ": " + ex.getMessage() );
        }
        if ( buildObj == null ) {
            throw new ImportException( "Technic build " + build + " returned an empty response." );
        }
        String mcVersion = optString( buildObj, "minecraft" );
        if ( mcVersion == null || mcVersion.isBlank() ) {
            throw new ImportException( "Technic build " + build + " has no Minecraft version." );
        }

        JsonArray mods = new JsonArray();
        if ( buildObj.has( "mods" ) && buildObj.get( "mods" ).isJsonArray() ) {
            for ( JsonElement el : buildObj.getAsJsonArray( "mods" ) ) {
                if ( !el.isJsonObject() ) continue;
                JsonObject src = el.getAsJsonObject();
                String name = optString( src, "name" );
                String url = optString( src, "url" );
                String md5 = optString( src, "md5" );
                String version = optString( src, "version" );
                if ( url == null || url.isBlank() ) continue;

                JsonObject mod = new JsonObject();
                mod.addProperty( "name", name == null ? "" : name );
                mod.addProperty( "remote", url );
                // Technic mod URLs end in .zip — the launcher's mod-sync
                // pipeline expects each entry to land at a stable local
                // path. Use the basename of the URL so we don't clobber
                // mods that happen to share the same display name.
                String localName = url.contains( "/" )
                        ? url.substring( url.lastIndexOf( '/' ) + 1 ) : name + ".zip";
                mod.addProperty( "local", localName );
                mod.addProperty( "hashType", "md5" );
                mod.addProperty( "hash", md5 == null ? "" : md5 );
                if ( version != null && !version.isBlank() ) {
                    mod.addProperty( "version", version );
                }
                mods.add( mod );
            }
        }

        JsonObject manifest = new JsonObject();
        manifest.addProperty( "manifestFormat", 2 );
        manifest.addProperty( "packName", summary.displayName() != null
                                                ? summary.displayName() : summary.slug() );
        manifest.addProperty( "packVersion", build );
        manifest.addProperty( "packURL", "https://www.technicpack.net/modpack/" + summary.slug() );
        manifest.addProperty( "packMinRAMGB", "4" );
        manifest.addProperty( "packUnstable", false );
        manifest.addProperty( "packCustomDiscordRpc", false );
        manifest.addProperty( "packLogoURL", summary.iconUrl() != null && !summary.iconUrl().isBlank()
                                                    ? summary.iconUrl()
                                                    : ModPackConstants.MODPACK_DEFAULT_LOGO_URL );
        manifest.addProperty( "packBackgroundURL", ModPackConstants.MODPACK_DEFAULT_BG_URL );

        // Technic packs don't ship loader metadata in their build
        // manifest the same way Modrinth does. Most Technic builds
        // bundle Forge into their downloads.zip or one of the mod
        // entries; the launcher's launch path expects a known loader
        // value so we default to Forge. Users importing a build that
        // bundles its own loader (Tekkit Classic / old industry-craft
        // era packs) can edit the manifest after import to point at
        // the right loader installer for their MC version.
        manifest.addProperty( "packModLoader", ModPackConstants.MOD_LOADER_FORGE );
        manifest.addProperty( "packModLoaderURL", "" );
        manifest.addProperty( "packModLoaderHash", "" );
        manifest.addProperty( "packForgeURL", "" );
        manifest.addProperty( "packForgeHash", "" );
        manifest.addProperty( "packMinecraftVersion", mcVersion );
        manifest.add( "packScanExclusions", new JsonArray() );
        manifest.add( "packMods", mods );
        manifest.add( "packConfigs", new JsonArray() );
        manifest.add( "packResourcePacks", new JsonArray() );
        manifest.add( "packShaderPacks", new JsonArray() );
        manifest.add( "packInitialFiles", new JsonArray() );

        // Write to imported-manifests/<slug>-<build>.json so re-imports
        // overwrite cleanly and the file is greppable.
        String filename = "technic-" + sanitize( summary.slug() ) + "-" + sanitize( build ) + ".json";
        Path manifestPath = Path.of( LocalPathManager.getLauncherConfigFolderPath(),
                                       IMPORTED_MANIFESTS_DIR,
                                       filename );
        try {
            Files.createDirectories( manifestPath.getParent() );
            Files.writeString( manifestPath,
                                JSONUtilities.getGson().toJson( manifest ),
                                StandardCharsets.UTF_8 );
        }
        catch ( IOException ex ) {
            throw new ImportException( "Failed to write imported Technic manifest: " + ex.getMessage() );
        }
        Logger.logStd( "TechnicImporter: wrote manifest for " + summary.slug() + " build " + build
                               + " (" + mods.size() + " mods) at " + manifestPath );

        String manifestUrl = manifestPath.toUri().toString();
        com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager
                .installModPackByURL( manifestUrl );
        return manifestUrl;
    }

    private static String optString( JsonObject obj, String key )
    {
        if ( obj == null || !obj.has( key ) ) return null;
        JsonElement el = obj.get( key );
        if ( el == null || el.isJsonNull() ) return null;
        if ( el.isJsonPrimitive() ) return el.getAsString();
        return null;
    }

    private static String sanitize( String s )
    {
        if ( s == null ) return "unknown";
        return s.replaceAll( "[^A-Za-z0-9._-]", "_" );
    }

    /** Mirrors {@link MrpackImporter.ImportException} so callers can
     *  catch a single type across both importer paths. Lightweight
     *  checked exception — no rich error model needed for the
     *  preview UI's "show the message in a label" use case. */
    public static class ImportException extends Exception
    {
        public ImportException( String message ) { super( message ); }
    }
}
