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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.import_.MrpackImporter;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import com.micatechnologies.minecraft.launcher.utilities.VersionUtilities;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catalog of installable Forge / NeoForge / Fabric loader versions
 * sourced from the three loaders' canonical version services
 * (Forge promotions JSON, NeoForge Maven metadata, Fabric meta
 * service). Drives the "Browse" GUI's loader-version filters and the
 * Modpack Editor's per-loader version pickers.
 *
 * <h3>How installation works</h3>
 *
 * <p>Installing a loader version writes a minimal modpack manifest
 * to {@code <config>/imported-manifests/} — the same directory the
 * Modrinth importer drops its translated packs into. The launcher's
 * boot scan picks them up like any other imported pack, so an
 * installed "Forge 1.20.1 (47.4.1)" empty pack:</p>
 *
 * <ul>
 *   <li>shows up in the main menu's hero card list;</li>
 *   <li>is editable in the Modpack Editor — the user can add mods,
 *       change the loader version, edit the description;</li>
 *   <li>uninstalls the same way any imported pack does (the
 *       launcher's uninstall path deletes the manifest file).</li>
 * </ul>
 *
 * <p>This is intentionally different from {@link VanillaVersionManager}
 * which stores installed versions in a simple {@code ConfigManager}
 * string list and synthesises a {@code GameModPack} on demand. The
 * vanilla pattern is fine for "play this version of MC" but
 * loader-version installs are meant to be a starting point for a
 * mod loadout — surfacing them through the normal manifest pipeline
 * gives them all the editing affordances modpacks already have.</p>
 *
 * @since 2026.5
 */
public final class LoaderVersionManager
{
    /**
     * Private no-op constructor. {@code LoaderVersionManager} exposes
     * only static members and must never be instantiated.
     *
     * @since 2026.5
     */
    private LoaderVersionManager() { /* static-only */ }

    /**
     * One installable loader-version entry. Carries everything needed
     * to render a card AND construct the synthetic manifest at install
     * time. {@code installerUrl} is the Forge / NeoForge installer JAR
     * URL or the Fabric profile-JSON URL — same interpretation as
     * {@code packModLoaderURL} in a manifest.
     *
     * @param loaderType    the loader family identifier — one of the
     *                      {@code ModPackConstants.MOD_LOADER_*} values
     *                      ({@code forge} / {@code neoforge} /
     *                      {@code fabric})
     * @param mcVersion     the Minecraft version this loader build
     *                      targets (e.g. {@code 1.20.1})
     * @param loaderVersion the loader's own version string (e.g.
     *                      {@code 47.4.1})
     * @param installerUrl  the Forge / NeoForge installer JAR URL or
     *                      the Fabric profile-JSON URL
     *
     * @since 2026.5
     */
    public record LoaderVersion(
            String loaderType,
            String mcVersion,
            String loaderVersion,
            String installerUrl )
    {
        /**
         * Display label used in the Browse GUI card title, of the form
         * {@code "<PrettyLoader> <mcVersion> (<loaderVersion>)"} (e.g.
         * {@code "Forge 1.20.1 (47.4.1)"}).
         *
         * @return the human-readable card title for this entry
         *
         * @since 2026.5
         */
        public String displayName() {
            return prettyLoaderName( loaderType ) + " " + mcVersion + " (" + loaderVersion + ")";
        }

        /**
         * Stable filename for the local manifest. Same {@code (loader,
         * mc, version)} → same file, so re-installs overwrite cleanly
         * and we can detect "already installed" by checking file
         * existence. Version components are run through {@link #sanitize}
         * so the name always survives the filesystem.
         *
         * @return the {@code loader-<type>-<mc>-<version>.json} filename
         *         for this entry's on-disk manifest
         *
         * @since 2026.5
         */
        public String manifestFilename() {
            String safeMc = sanitize( mcVersion );
            String safeLoader = sanitize( loaderVersion );
            return "loader-" + loaderType + "-" + safeMc + "-" + safeLoader + ".json";
        }
    }

    /** Lazily-populated cache of fetched Forge versions; {@code null}
     *  until {@link #getForgeVersions()} runs its first network fetch. */
    private static List< LoaderVersion > forgeCache;

    /** Lazily-populated cache of fetched NeoForge versions; {@code null}
     *  until {@link #getNeoForgeVersions()} runs its first network fetch. */
    private static List< LoaderVersion > neoForgeCache;

    /** Lazily-populated cache of fetched Fabric versions; {@code null}
     *  until {@link #getFabricVersions()} runs its first network fetch. */
    private static List< LoaderVersion > fabricCache;

    // ====================================================================
    // Forge
    // ====================================================================

    /**
     * Fetch + cache the Forge versions advertised by Forge's
     * promotions API. Returns recommended + latest per MC version
     * (newest MC first). Calls after the first warm-cached. Network or
     * parse failures are logged silently and yield whatever was
     * accumulated before the failure (possibly an empty list) rather
     * than throwing.
     *
     * @return the cached, newest-MC-first list of installable Forge
     *         loader versions; never {@code null}
     *
     * @since 2026.5
     */
    public static synchronized List< LoaderVersion > getForgeVersions()
    {
        if ( forgeCache != null ) return forgeCache;
        forgeCache = new ArrayList<>();
        try {
            String json = NetworkUtilities.downloadFileFromURL(
                    "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json" );
            JsonObject promos = JSONUtilities.getGson().fromJson( json, JsonObject.class )
                                              .getAsJsonObject( "promos" );
            java.util.Map< String, String > recommended = new java.util.HashMap<>();
            java.util.Map< String, String > latest = new java.util.HashMap<>();
            for ( java.util.Map.Entry< String, JsonElement > e : promos.entrySet() ) {
                String key = e.getKey();
                String forgeVer = e.getValue().getAsString();
                if ( key.endsWith( "-recommended" ) ) {
                    recommended.put( key.substring( 0, key.length() - "-recommended".length() ), forgeVer );
                }
                else if ( key.endsWith( "-latest" ) ) {
                    latest.put( key.substring( 0, key.length() - "-latest".length() ), forgeVer );
                }
            }
            List< String > mcVersions = new ArrayList<>( latest.keySet() );
            mcVersions.sort( ( a, b ) -> VersionUtilities.compareVersionNumbers( b, a ) );
            for ( String mcVer : mcVersions ) {
                // Recommended first, then latest (when distinct), so
                // the Browse-GUI list reads "stable, then bleeding-edge"
                // per MC version.
                if ( recommended.containsKey( mcVer ) ) {
                    String forgeVer = recommended.get( mcVer );
                    forgeCache.add( new LoaderVersion( ModPackConstants.MOD_LOADER_FORGE,
                                                       mcVer, forgeVer,
                                                       forgeInstallerUrl( mcVer, forgeVer ) ) );
                }
                if ( latest.containsKey( mcVer ) ) {
                    String forgeVer = latest.get( mcVer );
                    if ( recommended.containsKey( mcVer ) && recommended.get( mcVer ).equals( forgeVer ) ) {
                        continue; // already added above
                    }
                    forgeCache.add( new LoaderVersion( ModPackConstants.MOD_LOADER_FORGE,
                                                       mcVer, forgeVer,
                                                       forgeInstallerUrl( mcVer, forgeVer ) ) );
                }
            }
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "LoaderVersionManager: failed to fetch Forge promotions", e );
        }
        return forgeCache;
    }

    /**
     * Builds the canonical Forge installer JAR URL on Forge's Maven for
     * the given Minecraft + Forge version pair.
     *
     * @param mcVer    the Minecraft version (e.g. {@code 1.20.1})
     * @param forgeVer the Forge version (e.g. {@code 47.4.1})
     *
     * @return the {@code forge-<mc>-<forge>-installer.jar} download URL
     *
     * @since 2026.5
     */
    private static String forgeInstallerUrl( String mcVer, String forgeVer ) {
        return "https://maven.minecraftforge.net/net/minecraftforge/forge/"
                + mcVer + "-" + forgeVer + "/forge-" + mcVer + "-" + forgeVer + "-installer.jar";
    }

    // ====================================================================
    // NeoForge
    // ====================================================================

    /**
     * Fetch + cache the NeoForge versions advertised by the
     * project's Maven metadata. MC version is derived from the
     * NeoForge version prefix ({@code 21.1.x} → MC {@code 1.21.1}).
     * Entries whose version string doesn't parse to an MC version are
     * skipped. Network or parse failures are logged silently and yield
     * whatever was accumulated before the failure rather than throwing.
     *
     * @return the cached, newest-first list of installable NeoForge
     *         loader versions; never {@code null}
     *
     * @since 2026.5
     */
    public static synchronized List< LoaderVersion > getNeoForgeVersions()
    {
        if ( neoForgeCache != null ) return neoForgeCache;
        neoForgeCache = new ArrayList<>();
        try {
            String xml = NetworkUtilities.downloadFileFromURL(
                    "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml" );
            Pattern versionTag = Pattern.compile( "<version>([^<]+)</version>" );
            Matcher matcher = versionTag.matcher( xml );
            List< String > versions = new ArrayList<>();
            while ( matcher.find() ) versions.add( matcher.group( 1 ) );
            // Newest first.
            versions.sort( ( a, b ) -> VersionUtilities.compareVersionNumbers( b, a ) );
            for ( String version : versions ) {
                String mcVersion = neoForgeMcVersionFor( version );
                if ( mcVersion == null ) continue;
                neoForgeCache.add( new LoaderVersion( ModPackConstants.MOD_LOADER_NEOFORGE,
                                                       mcVersion, version,
                                                       neoForgeInstallerUrl( version ) ) );
            }
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "LoaderVersionManager: failed to fetch NeoForge versions", e );
        }
        return neoForgeCache;
    }

    /**
     * Builds the canonical NeoForge installer JAR URL on NeoForge's
     * Maven for the given NeoForge version.
     *
     * @param version the NeoForge version (e.g. {@code 21.1.0})
     *
     * @return the {@code neoforge-<version>-installer.jar} download URL
     *
     * @since 2026.5
     */
    private static String neoForgeInstallerUrl( String version ) {
        return "https://maven.neoforged.net/releases/net/neoforged/neoforge/"
                + version + "/neoforge-" + version + "-installer.jar";
    }

    /**
     * Maps a NeoForge {@code MAJOR.MINOR.PATCH} version to its target
     * Minecraft version {@code 1.MAJOR.MINOR} (or {@code 1.MAJOR} when
     * the minor component is {@code 0}). Mirrors the helper in
     * {@code MCLauncherModPackEditorGui} — copied here so the manager
     * has no dependency on a GUI class.
     *
     * @param neoForgeVersion the NeoForge version string to translate
     *
     * @return the derived Minecraft version, or {@code null} when the
     *         input is {@code null} or doesn't parse
     *
     * @since 2026.5
     */
    private static String neoForgeMcVersionFor( String neoForgeVersion ) {
        if ( neoForgeVersion == null ) return null;
        String[] parts = neoForgeVersion.split( "\\." );
        if ( parts.length < 2 ) return null;
        try {
            int major = Integer.parseInt( parts[ 0 ] );
            int minor = Integer.parseInt( parts[ 1 ] );
            return minor == 0 ? "1." + major : "1." + major + "." + minor;
        }
        catch ( NumberFormatException e ) {
            return null;
        }
    }

    // ====================================================================
    // Fabric
    // ====================================================================

    /**
     * Fetch + cache Fabric versions. Pairs each stable Minecraft
     * release with the latest stable Fabric loader (the official
     * "if it builds for this MC, this is the recommended loader"
     * shape — see meta.fabricmc.net's profile endpoints). Snapshot MC
     * releases are filtered out. Network or parse failures are logged
     * silently and yield whatever was accumulated before the failure
     * rather than throwing.
     *
     * @return the cached list of installable Fabric loader versions
     *         (one entry per stable MC release); never {@code null}
     *
     * @since 2026.5
     */
    public static synchronized List< LoaderVersion > getFabricVersions()
    {
        if ( fabricCache != null ) return fabricCache;
        fabricCache = new ArrayList<>();
        try {
            String gameJson = NetworkUtilities.downloadFileFromURL(
                    "https://meta.fabricmc.net/v2/versions/game" );
            String loaderJson = NetworkUtilities.downloadFileFromURL(
                    "https://meta.fabricmc.net/v2/versions/loader" );
            JsonArray games = JSONUtilities.getGson().fromJson( gameJson, JsonArray.class );
            JsonArray loaders = JSONUtilities.getGson().fromJson( loaderJson, JsonArray.class );

            // Latest stable loader — meta service returns loaders
            // newest-first, take the first stable hit.
            String latestLoader = null;
            for ( JsonElement el : loaders ) {
                JsonObject obj = el.getAsJsonObject();
                if ( obj.has( "stable" ) && obj.get( "stable" ).getAsBoolean() ) {
                    latestLoader = obj.get( "version" ).getAsString();
                    break;
                }
            }
            if ( latestLoader == null && loaders.size() > 0 ) {
                latestLoader = loaders.get( 0 ).getAsJsonObject().get( "version" ).getAsString();
            }
            if ( latestLoader == null ) return fabricCache;

            for ( JsonElement el : games ) {
                JsonObject obj = el.getAsJsonObject();
                // Only stable MC releases — snapshots are too noisy
                // for a Browse listing and Fabric supports far more
                // snapshots than the launcher would meaningfully run.
                if ( obj.has( "stable" ) && !obj.get( "stable" ).getAsBoolean() ) continue;
                String mcVersion = obj.get( "version" ).getAsString();
                fabricCache.add( new LoaderVersion( ModPackConstants.MOD_LOADER_FABRIC,
                                                    mcVersion, latestLoader,
                                                    fabricProfileUrl( mcVersion, latestLoader ) ) );
            }
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "LoaderVersionManager: failed to fetch Fabric versions", e );
        }
        return fabricCache;
    }

    /**
     * Builds the Fabric meta service profile-JSON URL for the given
     * Minecraft + Fabric loader version pair. This URL stands in for an
     * installer JAR in a Fabric {@link LoaderVersion}.
     *
     * @param mcVersion     the Minecraft version (e.g. {@code 1.20.1})
     * @param loaderVersion the Fabric loader version (e.g. {@code 0.16.0})
     *
     * @return the {@code .../loader/<mc>/<loader>/profile/json} URL
     *
     * @since 2026.5
     */
    private static String fabricProfileUrl( String mcVersion, String loaderVersion ) {
        return "https://meta.fabricmc.net/v2/versions/loader/" + mcVersion
                + "/" + loaderVersion + "/profile/json";
    }

    // ====================================================================
    // Install: write the manifest, register with GameModPackManager
    // ====================================================================

    /**
     * Install a loader version as an empty modpack. Writes a manifest
     * under {@code <config>/imported-manifests/} and registers it
     * with {@link GameModPackManager}. Idempotent — already-installed
     * versions return the existing manifest URL without re-writing.
     *
     * @param version the loader version to install as an empty modpack
     *
     * @return the {@code file:} URL of the on-disk manifest, which is
     *         what the rest of the launcher uses to identify it.
     *
     * @throws IOException if the manifest directory or file cannot be
     *                     created or written
     *
     * @since 2026.5
     */
    public static synchronized String installVersion( LoaderVersion version ) throws IOException
    {
        Path manifestPath = manifestPathFor( version );
        boolean preExisting = Files.exists( manifestPath );

        if ( !preExisting ) {
            JsonObject manifest = buildEmptyLoaderManifest( version );
            Files.createDirectories( manifestPath.getParent() );
            Files.writeString( manifestPath, JSONUtilities.getGson().toJson( manifest ),
                                StandardCharsets.UTF_8 );
            Logger.logStd( "LoaderVersionManager: wrote manifest for " + version.displayName()
                                   + " at " + manifestPath );
        }
        String manifestUrl = manifestPath.toUri().toString();
        // Register with the modpack manager so it shows in the
        // installed-packs list immediately. installModPackByURL is
        // idempotent — if the same URL is already in the list it's a
        // no-op.
        GameModPackManager.installModPackByURL( manifestUrl );
        return manifestUrl;
    }

    /**
     * Reports whether the given loader version has already been
     * installed, i.e. whether its manifest file already exists on disk
     * under {@code imported-manifests/}.
     *
     * @param version the loader version to check
     *
     * @return {@code true} if the manifest file for {@code version}
     *         already exists; {@code false} otherwise
     *
     * @since 2026.5
     */
    public static boolean isInstalled( LoaderVersion version )
    {
        return Files.exists( manifestPathFor( version ) );
    }

    /**
     * Resolves the on-disk path of the manifest file for the given
     * loader version, under the launcher config folder's
     * {@code imported-manifests/} directory.
     *
     * @param version the loader version whose manifest path to compute
     *
     * @return the absolute {@link Path} of this version's manifest file
     *
     * @since 2026.5
     */
    private static Path manifestPathFor( LoaderVersion version )
    {
        return Path.of( LocalPathManager.getLauncherConfigFolderPath(),
                         MrpackImporter.IMPORTED_MANIFESTS_DIR,
                         version.manifestFilename() );
    }

    /**
     * Build the minimal Mica-format manifest for an empty loader
     * pack. Field shape mirrors {@code MrpackImporter.buildMicaManifest}
     * — same keys, same defaults, but with empty mod / config / etc.
     * arrays and no pack-specific assets. Loader-loader URLs are left
     * unhashed (empty hash → "skip verification" in the managed-file
     * path) and the legacy {@code packForge*} fields are mirrored only
     * for Forge packs.
     *
     * @param v the loader version to materialise as a manifest
     *
     * @return the populated Mica manifest {@link JsonObject}
     *
     * @since 2026.5
     */
    private static JsonObject buildEmptyLoaderManifest( LoaderVersion v )
    {
        JsonObject m = new JsonObject();
        m.addProperty( "packName", v.displayName() );
        m.addProperty( "packVersion", v.loaderVersion );
        m.addProperty( "packURL", urlForLoader( v.loaderType ) );
        m.addProperty( "packLogoURL", ModPackConstants.MODPACK_DEFAULT_LOGO_URL );
        m.addProperty( "packBackgroundURL", ModPackConstants.MODPACK_DEFAULT_BG_URL );
        m.addProperty( "packMinRAMGB", "2" );
        m.addProperty( "packUnstable", false );
        m.addProperty( "packCustomDiscordRpc", false );
        m.addProperty( "packModLoader", v.loaderType );
        m.addProperty( "packModLoaderURL", v.installerUrl );
        // Fabric meta profiles aren't hash-pinned; Forge / NeoForge
        // installers have stable SHA-1s but computing them here would
        // mean downloading every installer at install time. The
        // launcher's ManagedGameFile path treats an empty hash as
        // "skip verification" — it'll happily download + use the
        // installer on first launch. Hash-pinning post-install would
        // be a nice-to-have for offline integrity but isn't required
        // for the empty-pack workflow.
        m.addProperty( "packModLoaderHash", "" );
        // Legacy back-compat fields. Only Forge mirrors them so a
        // pre-multi-loader build reading this manifest still gets a
        // sensible install path.
        boolean isForge = ModPackConstants.MOD_LOADER_FORGE.equals( v.loaderType );
        m.addProperty( "packForgeURL", isForge ? v.installerUrl : "" );
        m.addProperty( "packForgeHash", "" );
        m.add( "packScanExclusions", new JsonArray() );
        m.add( "packMods", new JsonArray() );
        m.add( "packConfigs", new JsonArray() );
        m.add( "packResourcePacks", new JsonArray() );
        m.add( "packShaderPacks", new JsonArray() );
        m.add( "packInitialFiles", new JsonArray() );
        return m;
    }

    /**
     * Resolves the canonical project homepage URL for a loader family,
     * stored as the {@code packURL} of a generated loader manifest.
     *
     * @param loaderType the loader family identifier ({@code forge} /
     *                   {@code neoforge} / {@code fabric})
     *
     * @return the project homepage URL, or the Minecraft site URL for
     *         an unrecognised loader type
     *
     * @since 2026.5
     */
    private static String urlForLoader( String loaderType )
    {
        return switch ( loaderType ) {
            case "forge"    -> "https://files.minecraftforge.net";
            case "neoforge" -> "https://neoforged.net";
            case "fabric"   -> "https://fabricmc.net";
            default          -> "https://minecraft.net";
        };
    }

    /**
     * Maps a loader family identifier to its display-cased name for use
     * in card titles.
     *
     * @param loaderType the loader family identifier ({@code forge} /
     *                   {@code neoforge} / {@code fabric})
     *
     * @return the pretty loader name, or the raw {@code loaderType} for
     *         an unrecognised value
     *
     * @since 2026.5
     */
    private static String prettyLoaderName( String loaderType )
    {
        return switch ( loaderType ) {
            case "forge"    -> "Forge";
            case "neoforge" -> "NeoForge";
            case "fabric"   -> "Fabric";
            default          -> loaderType;
        };
    }

    /**
     * Strip path-unsafe characters from version strings so the
     * computed filename always survives the filesystem. Forge / NeoForge
     * / Fabric version strings only contain {@code [A-Za-z0-9._-]} in
     * practice, but Forge's underscore-prefixed build-metadata
     * ({@code 14.23.5.2855_230729ER}) and Fabric's snapshot loader
     * ids could hit edge cases. Any character outside
     * {@code [A-Za-z0-9._-]} is replaced with an underscore.
     *
     * @param input the raw version string to sanitize
     *
     * @return the filesystem-safe form, or {@code "unknown"} when
     *         {@code input} is {@code null}
     *
     * @since 2026.5
     */
    private static String sanitize( String input )
    {
        if ( input == null ) return "unknown";
        return input.replaceAll( "[^A-Za-z0-9._-]", "_" );
    }
}
