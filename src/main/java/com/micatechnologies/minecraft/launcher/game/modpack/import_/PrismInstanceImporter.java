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
import com.google.gson.JsonParser;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Properties;

/**
 * Imports a MultiMC / Prism Launcher instance folder into Mica. Reads
 * the instance's {@code instance.cfg} (INI-ish key=value pairs) and
 * {@code mmc-pack.json} (component list) to identify the pack name,
 * Minecraft version, modloader, and loader version, then generates a
 * minimal Mica manifest with {@code packImportedSkipSync=true} set so
 * the launcher's floating-mod cleanup leaves the imported {@code mods/}
 * folder alone. Finally copies the {@code .minecraft/} (or
 * {@code minecraft/}) subdirectory of the instance into the new Mica
 * pack folder so worlds / configs / mods / resourcepacks / shaderpacks
 * all carry over.
 *
 * <p>Modloader UID mapping:</p>
 * <ul>
 *   <li>{@code net.minecraftforge}        → Forge</li>
 *   <li>{@code net.neoforged}             → NeoForge</li>
 *   <li>{@code net.fabricmc.fabric-loader}→ Fabric</li>
 *   <li>{@code org.quiltmc.quilt-loader}  → unsupported (treated as
 *       Fabric-compatible; Quilt mods generally work under Fabric so
 *       the import is best-effort; user should validate manually)</li>
 * </ul>
 *
 * <p>The generated manifest carries:</p>
 * <ul>
 *   <li>{@code packName}, {@code packVersion="1.0.0"}, MC version</li>
 *   <li>{@code packModLoader} + {@code packModLoaderURL} (Maven/CDN
 *       URL derived from the modloader version)</li>
 *   <li>{@code packModLoaderHash=""} — the launcher accepts an empty
 *       hash and trust-downloads the installer on first launch</li>
 *   <li>{@code packImportedSkipSync=true} — leaves user-owned content
 *       untouched on subsequent launches</li>
 *   <li>Empty {@code packMods} / {@code packConfigs} / etc. — the
 *       physical files come from the .minecraft/ copy</li>
 * </ul>
 *
 * <p>This is a best-effort import: anything that isn't trivially
 * mappable (custom Java path, per-instance JVM args, Quilt) is logged
 * as a warning and dropped. The user can fix the pack post-import via
 * the modpack editor.</p>
 *
 * @since 2026.5
 */
public final class PrismInstanceImporter
{
    private PrismInstanceImporter() { /* static-only */ }

    public static final String IMPORTED_MANIFESTS_DIR = MrpackImporter.IMPORTED_MANIFESTS_DIR;

    /** Soft cap on instance.cfg / mmc-pack.json file sizes. Real files
     *  are <10 KB; a 5 MB cap is enormous slack but rejects a hostile
     *  multi-GB drop. */
    private static final long MAX_METADATA_BYTES = 5L * 1024 * 1024;

    /**
     * Imports the Prism / MultiMC instance rooted at {@code instanceDir}
     * into the launcher.
     *
     * @param instanceDir the instance folder — must contain
     *                    {@code instance.cfg} + {@code mmc-pack.json}
     *                    + a {@code .minecraft/} or {@code minecraft/}
     *                    subfolder
     * @return the {@code file:} URL of the on-disk Mica manifest the
     *         pack was registered under
     * @throws ImportException for any failure — missing files, malformed
     *         JSON, unsupported modloader, disk write failure. The
     *         message is safe to surface to end users.
     */
    public static String importInstance( File instanceDir ) throws ImportException
    {
        if ( instanceDir == null || !instanceDir.isDirectory() ) {
            throw new ImportException( "Pick a Prism Launcher / MultiMC instance folder." );
        }
        File instanceCfg = new File( instanceDir, "instance.cfg" );
        File mmcPackJson = new File( instanceDir, "mmc-pack.json" );
        if ( !instanceCfg.isFile() || !mmcPackJson.isFile() ) {
            throw new ImportException( "This folder doesn't look like a Prism / MultiMC instance "
                                               + "(missing instance.cfg or mmc-pack.json)." );
        }
        // .minecraft/ is the Prism convention; MultiMC legacy used
        // minecraft/. Accept either.
        File mcFolder = new File( instanceDir, ".minecraft" );
        if ( !mcFolder.isDirectory() ) {
            mcFolder = new File( instanceDir, "minecraft" );
        }
        if ( !mcFolder.isDirectory() ) {
            throw new ImportException( "Instance folder is missing the .minecraft/ subfolder." );
        }

        Properties instanceProps = readInstanceCfg( instanceCfg );
        String packName = instanceProps.getProperty( "name" );
        if ( packName == null || packName.isBlank() ) {
            packName = instanceDir.getName();
        }

        ParsedComponents components = parseMmcPackJson( mmcPackJson );

        // Build the Mica manifest.
        JsonObject manifest = new JsonObject();
        manifest.addProperty( "packName", packName );
        manifest.addProperty( "packVersion", "1.0.0" );
        manifest.addProperty( "packURL", "" );
        manifest.addProperty( "packMinRAMGB", "2" );
        manifest.addProperty( "packMinecraftVersion", components.mcVersion );
        // The Mica launcher reads modloader info via packModLoader +
        // packModLoaderURL (with packForgeURL as the back-compat
        // fallback for legacy Forge-only manifests).
        if ( components.loaderType != null ) {
            manifest.addProperty( "packModLoader", components.loaderType );
            manifest.addProperty( "packModLoaderURL", components.loaderInstallerUrl );
            manifest.addProperty( "packModLoaderHash", "" );
            // packForgeURL/Hash back-compat: when loader is Forge, set
            // these too so older code paths see something coherent.
            if ( ModPackConstants.MOD_LOADER_FORGE.equals( components.loaderType ) ) {
                manifest.addProperty( "packForgeURL", components.loaderInstallerUrl );
                manifest.addProperty( "packForgeHash", "" );
            }
        }
        else {
            // Vanilla import — no modloader, just MC + saves/shaders/rps.
            manifest.addProperty( "vanillaVersion", true );
        }
        // Empty lists so GSON deserializes them as empty rather than
        // null — keeps the pack lifecycle code's null-checks happy
        // without surprises.
        manifest.add( "packMods", new JsonArray() );
        manifest.add( "packConfigs", new JsonArray() );
        manifest.add( "packResourcePacks", new JsonArray() );
        manifest.add( "packShaderPacks", new JsonArray() );
        manifest.add( "packInitialFiles", new JsonArray() );
        // The load-bearing field: tells the launcher's verify pipeline
        // to leave the user-owned mods/ folder alone.
        manifest.addProperty( "packImportedSkipSync", true );

        // Persist manifest into imported-manifests/.
        Path manifestPath;
        try {
            Path dir = Path.of( LocalPathManager.getLauncherConfigFolderPath(), IMPORTED_MANIFESTS_DIR );
            Files.createDirectories( dir );
            String safeName = sanitize( packName );
            manifestPath = dir.resolve( "prism-" + safeName + ".json" );
            Files.writeString( manifestPath, manifest.toString(), StandardCharsets.UTF_8 );
        }
        catch ( IOException e ) {
            throw new ImportException( "Couldn't write the imported manifest: " + e.getMessage() );
        }

        String manifestUrl = manifestPath.toUri().toString();
        Logger.logStd( "Prism import: wrote manifest for \"" + packName + "\" at " + manifestPath );

        // Register through the standard install pipeline. installModPackByURL
        // is synchronous and creates the pack folder skeleton.
        try {
            GameModPackManager.installModPackByURL( manifestUrl );
        }
        catch ( Exception e ) {
            throw new ImportException( "Couldn't register the imported pack: " + e.getMessage() );
        }

        // Find the freshly-installed pack so we know its root folder.
        // installModPackByURL adds it to the installed list keyed by
        // manifestUrl; the manager's lookup-by-url is package-private
        // so we walk the installed list.
        GameModPack installedPack = null;
        for ( GameModPack p : GameModPackManager.getInstalledModPacks() ) {
            if ( manifestUrl.equals( p.getManifestUrl() ) ) {
                installedPack = p;
                break;
            }
        }
        if ( installedPack == null ) {
            throw new ImportException( "Install reported success but couldn't locate the pack to copy "
                                               + "content into." );
        }

        // Copy .minecraft/ contents into the pack root. Skip the
        // bin/ subfolder — Prism stores the launcher's vendored
        // Minecraft jar there, and Mica resolves its own via piston-
        // meta, so copying it would conflict with the launcher's
        // managed bin/.
        try {
            copyTreeSkipBin( mcFolder.toPath(), Path.of( installedPack.getPackRootFolder() ) );
        }
        catch ( IOException e ) {
            throw new ImportException( "Pack registered but couldn't copy .minecraft/ contents: "
                                               + e.getMessage() );
        }

        Logger.logStd( "Prism import: copied .minecraft/ contents for \"" + packName + "\"." );
        return manifestUrl;
    }

    // -------------------------------------------------------------------
    //  instance.cfg + mmc-pack.json parsing
    // -------------------------------------------------------------------

    private static Properties readInstanceCfg( File cfg ) throws ImportException
    {
        if ( cfg.length() > MAX_METADATA_BYTES ) {
            throw new ImportException( "instance.cfg is suspiciously large; refusing to import." );
        }
        // instance.cfg is loose INI: optional [General] header then
        // key=value lines. Properties handles the key=value part
        // gracefully and ignores the [Section] line as a non-property.
        try {
            Properties p = new Properties();
            p.load( Files.newBufferedReader( cfg.toPath(), StandardCharsets.UTF_8 ) );
            return p;
        }
        catch ( IOException e ) {
            throw new ImportException( "Couldn't read instance.cfg: " + e.getMessage() );
        }
    }

    /** Subset of mmc-pack.json that the importer cares about. */
    private record ParsedComponents( String mcVersion,
                                     String loaderType,
                                     String loaderVersion,
                                     String loaderInstallerUrl ) {}

    private static ParsedComponents parseMmcPackJson( File f ) throws ImportException
    {
        if ( f.length() > MAX_METADATA_BYTES ) {
            throw new ImportException( "mmc-pack.json is suspiciously large; refusing to import." );
        }
        JsonObject root;
        try {
            String body = Files.readString( f.toPath(), StandardCharsets.UTF_8 );
            root = JsonParser.parseString( body ).getAsJsonObject();
        }
        catch ( Exception e ) {
            throw new ImportException( "mmc-pack.json isn't valid JSON: " + e.getMessage() );
        }
        if ( !root.has( "components" ) || !root.get( "components" ).isJsonArray() ) {
            throw new ImportException( "mmc-pack.json is missing the components array." );
        }

        String mcVersion = null;
        String loaderType = null;
        String loaderVersion = null;
        for ( JsonElement el : root.getAsJsonArray( "components" ) ) {
            if ( el == null || !el.isJsonObject() ) continue;
            JsonObject c = el.getAsJsonObject();
            String uid = c.has( "uid" ) && c.get( "uid" ).isJsonPrimitive()
                    ? c.get( "uid" ).getAsString() : null;
            String version = c.has( "version" ) && c.get( "version" ).isJsonPrimitive()
                    ? c.get( "version" ).getAsString() : null;
            if ( uid == null ) continue;

            switch ( uid ) {
                case "net.minecraft" -> mcVersion = version;
                case "net.minecraftforge" -> {
                    loaderType = ModPackConstants.MOD_LOADER_FORGE;
                    loaderVersion = version;
                }
                case "net.neoforged" -> {
                    loaderType = ModPackConstants.MOD_LOADER_NEOFORGE;
                    loaderVersion = version;
                }
                case "net.fabricmc.fabric-loader", "org.quiltmc.quilt-loader" -> {
                    // Quilt is largely Fabric-compatible at runtime;
                    // Mica doesn't ship a dedicated Quilt loader so we
                    // fall back to Fabric here. The user is warned via
                    // the import-result toast (caller-side).
                    loaderType = ModPackConstants.MOD_LOADER_FABRIC;
                    loaderVersion = version;
                }
                default -> { /* skip — auxiliary components like LWJGL, JNA */ }
            }
        }
        if ( mcVersion == null || mcVersion.isBlank() ) {
            throw new ImportException( "Couldn't find a Minecraft version in mmc-pack.json." );
        }

        String installerUrl = null;
        if ( loaderType != null && loaderVersion != null ) {
            installerUrl = buildLoaderInstallerUrl( loaderType, mcVersion, loaderVersion );
        }
        return new ParsedComponents( mcVersion, loaderType, loaderVersion, installerUrl );
    }

    private static String buildLoaderInstallerUrl( String loaderType, String mcVersion, String loaderVersion )
    {
        return switch ( loaderType ) {
            case ModPackConstants.MOD_LOADER_FORGE ->
                    // Standard Forge maven layout: forge-<mc>-<loader>-installer.jar
                    "https://maven.minecraftforge.net/net/minecraftforge/forge/"
                            + mcVersion + "-" + loaderVersion
                            + "/forge-" + mcVersion + "-" + loaderVersion + "-installer.jar";
            case ModPackConstants.MOD_LOADER_NEOFORGE ->
                    "https://maven.neoforged.net/releases/net/neoforged/neoforge/"
                            + loaderVersion
                            + "/neoforge-" + loaderVersion + "-installer.jar";
            case ModPackConstants.MOD_LOADER_FABRIC ->
                    // Fabric's profile JSON for this MC + loader pair.
                    // GameModLoaderFabric expects this URL shape.
                    "https://meta.fabricmc.net/v2/versions/loader/"
                            + mcVersion + "/" + loaderVersion + "/profile/json";
            default -> null;
        };
    }

    // -------------------------------------------------------------------
    //  Directory copy
    // -------------------------------------------------------------------

    /** Recursively copies {@code src} → {@code dest}, skipping any
     *  top-level {@code bin/} folder (which holds Prism's vendored MC
     *  jar that Mica resolves separately). REPLACE_EXISTING so a
     *  partial import can be retried. */
    private static void copyTreeSkipBin( Path src, Path dest ) throws IOException
    {
        Files.createDirectories( dest );
        Path srcBin = src.resolve( "bin" );
        Files.walkFileTree( src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory( Path dir, BasicFileAttributes attrs ) throws IOException
            {
                if ( dir.equals( srcBin ) ) return FileVisitResult.SKIP_SUBTREE;
                Path rel = src.relativize( dir );
                Files.createDirectories( dest.resolve( rel ) );
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile( Path file, BasicFileAttributes attrs ) throws IOException
            {
                Path rel = src.relativize( file );
                Path target = dest.resolve( rel );
                Files.copy( file, target,
                            (CopyOption) StandardCopyOption.REPLACE_EXISTING );
                return FileVisitResult.CONTINUE;
            }
        } );
    }

    private static String sanitize( String raw )
    {
        if ( raw == null || raw.isBlank() ) return "imported";
        String s = raw.replaceAll( "[^a-zA-Z0-9._-]", "_" );
        return s.isEmpty() ? "imported" : s;
    }

    /** Checked exception mirroring the other importers — call sites
     *  can't forget to surface the failure. */
    public static class ImportException extends Exception
    {
        public ImportException( String message ) { super( message ); }
    }
}
