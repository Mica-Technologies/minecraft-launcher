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
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Imports a Technic Launcher "Server Download" ZIP. Since Technic's
 * Platform API gates programmatic clients, this ZIP-based import path
 * is the supported alternative — the user downloads the server pack
 * from a Technic project page's "Server Download" link and feeds it
 * into the launcher's existing Import ZIP action.
 *
 * <h3>ZIP layout (from a sample Tekkit server pack)</h3>
 * <pre>
 *   Tekkit.jar              ← server-side MCPC bukkit JAR (skipped)
 *   launch.bat / launch.sh  ← server startup scripts (skipped)
 *   mods/                   ← mixed .jar / .zip mod files (kept)
 *     SomeMod-1.0.jar
 *     RailCraft-x.y.z.zip
 *   config/                 ← per-mod config files (kept)
 *   buildcraft/, redpower/  ← vendor config dirs (kept)
 *   mod_EE.props            ← stray top-level config (kept)
 * </pre>
 *
 * <h3>What we do</h3>
 * <ol>
 *   <li>Derive pack name + version from the ZIP filename
 *       ({@code Tekkit_Server_3.1.2.zip} → name="Tekkit", version="3.1.2").</li>
 *   <li>Walk the {@code mods/} entries (one level deep — nested
 *       directories under {@code mods/} like {@code mods/ccSensors/}
 *       are kept as-is during extraction but not surfaced as individual
 *       {@code packMods} entries).</li>
 *   <li>Build a minimal Mica manifest with each {@code mods/*.jar} /
 *       {@code mods/*.zip} as a {@code packMods} entry whose
 *       {@code remote} is empty (local-file reference; the launcher's
 *       sync verifier will see the file already on disk).</li>
 *   <li>Extract everything except the top-level server JAR and the
 *       launch scripts into the pack's install folder.</li>
 *   <li>Leave {@code packMinecraftVersion} and {@code packModLoaderURL}
 *       blank — the user must fill these in via the modpack editor
 *       before launch. We surface that requirement in the success
 *       notification.</li>
 * </ol>
 *
 * @since 2026.5
 */
public final class TechnicServerZipImporter
{
    private TechnicServerZipImporter() { /* static-only */ }

    /** Where the imported manifest gets written. Same folder Modrinth /
     *  Mica-export imports use so the launcher's startup scan picks it
     *  up consistently. */
    public static final String IMPORTED_MANIFESTS_DIR = MrpackImporter.IMPORTED_MANIFESTS_DIR;

    /** Parses {@code <name>_Server_<version>.zip} into {(name, version)}.
     *  Matches the convention Technic Platform uses for server downloads. */
    private static final Pattern NAME_VERSION = Pattern.compile(
            "^(.+?)[_-]Server[_-](.+)\\.zip$", Pattern.CASE_INSENSITIVE );

    /**
     * Quick detection: does {@code zip} look like a Technic server pack?
     * Used by {@link ModpackZipImporter} to decide whether to fall through
     * to this importer when the Mica-export marker is missing. False
     * positives matter — a generic mods ZIP would extract messily if we
     * accepted it — so we require all three signals: top-level {@code mods/}
     * entry, a top-level server JAR, and at least one launch script.
     */
    public static boolean looksLikeTechnicServerZip( ZipFile zip )
    {
        boolean hasMods = false;
        boolean hasServerJar = false;
        boolean hasLaunchScript = false;
        Enumeration< ? extends ZipEntry > entries = zip.entries();
        while ( entries.hasMoreElements() ) {
            String name = entries.nextElement().getName();
            // Normalize to forward slashes; ZipEntry already does this on
            // every reasonable producer but the check is cheap.
            String n = name.replace( '\\', '/' );
            if ( n.startsWith( "mods/" ) ) hasMods = true;
            else if ( !n.contains( "/" ) && n.toLowerCase().endsWith( ".jar" ) ) hasServerJar = true;
            else if ( n.equalsIgnoreCase( "launch.bat" )
                    || n.equalsIgnoreCase( "launch.sh" ) ) hasLaunchScript = true;
            if ( hasMods && hasServerJar && hasLaunchScript ) return true;
        }
        return false;
    }

    /**
     * Imports {@code zipFile} as a Technic server pack. The caller (typically
     * {@link ModpackZipImporter}) has already verified
     * {@link #looksLikeTechnicServerZip(ZipFile)} returned true.
     *
     * @return the {@code file:} URL of the on-disk manifest the pack was
     *         registered under.
     * @throws ImportException for any failure during read / extract / install.
     */
    public static String importZip( File zipFile ) throws ImportException
    {
        if ( zipFile == null || !zipFile.isFile() ) {
            throw new ImportException( "Pick an existing ZIP file." );
        }

        // Derive name + version from the filename. Falls back to "TechnicPack"
        // + the file's basename when the _Server_ marker is absent (the user
        // may have renamed the download).
        String filename = zipFile.getName();
        String packName;
        String packVersion;
        Matcher m = NAME_VERSION.matcher( filename );
        if ( m.matches() ) {
            packName = m.group( 1 ).replace( '_', ' ' ).trim();
            packVersion = m.group( 2 );
        }
        else {
            String base = filename;
            int dot = base.lastIndexOf( '.' );
            if ( dot > 0 ) base = base.substring( 0, dot );
            packName = base.replace( '_', ' ' ).trim();
            packVersion = "imported";
        }

        // Compute the install folder using the same sanitization rule
        // GameModPack.getPackSanitizedName applies, so the regular install
        // pipeline's getPackRootFolder() resolves to where we extracted to.
        String sanitized = packName.replaceAll( "[^a-zA-Z0-9]", "" );
        if ( sanitized.isEmpty() ) sanitized = "TechnicPack";
        Path installFolder = Path.of( LocalPathManager.getLauncherModpackFolderPath(), sanitized );

        try ( ZipFile zip = new ZipFile( zipFile ) ) {
            Files.createDirectories( installFolder );
            List< String > modFilenames = new ArrayList<>();
            extractContents( zip, installFolder, modFilenames );

            JsonObject manifest = buildManifest( packName, packVersion, modFilenames );
            String manifestFilename = "technic-server-" + sanitize( sanitized ) + ".json";
            Path manifestPath = Path.of( LocalPathManager.getLauncherConfigFolderPath(),
                                            IMPORTED_MANIFESTS_DIR,
                                            manifestFilename );
            Files.createDirectories( manifestPath.getParent() );
            Files.writeString( manifestPath,
                                JSONUtilities.getGson().toJson( manifest ),
                                StandardCharsets.UTF_8 );
            Logger.logStd( "TechnicServerZipImporter: wrote manifest at " + manifestPath
                                   + " (" + modFilenames.size() + " mods) and extracted to "
                                   + installFolder );

            String manifestUrl = manifestPath.toUri().toString();
            GameModPackManager.installModPackByURL( manifestUrl );
            return manifestUrl;
        }
        catch ( IOException ioe ) {
            throw new ImportException( "Couldn't read the ZIP file: " + ioe.getMessage() );
        }
        catch ( Throwable t ) {
            Logger.logErrorSilent( "TechnicServerZipImporter: unexpected failure — " + t.getMessage() );
            throw new ImportException( "Unexpected error during import: " + t.getMessage() );
        }
    }

    /** Extracts every entry in {@code zip} into {@code dest} except the
     *  server-side bits we don't want on the client side: top-level
     *  {@code *.jar} (the server JAR), {@code launch.bat}, {@code launch.sh}.
     *  Records the names (without path) of files extracted into
     *  {@code mods/} at the top level so the manifest builder can list
     *  them as {@code packMods} entries.
     *
     *  <p>Path traversal is blocked by checking the resolved path stays
     *  inside {@code dest} — same guard {@link ModpackZipImporter} uses.</p> */
    private static void extractContents( ZipFile zip, Path dest, List< String > topLevelModNames )
            throws IOException
    {
        Path destNormalized = dest.toAbsolutePath().normalize();
        Enumeration< ? extends ZipEntry > entries = zip.entries();
        while ( entries.hasMoreElements() ) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName().replace( '\\', '/' );

            // Skip server-side cruft.
            if ( !name.contains( "/" ) && name.toLowerCase().endsWith( ".jar" ) ) continue;
            if ( name.equalsIgnoreCase( "launch.bat" )
                    || name.equalsIgnoreCase( "launch.sh" ) ) continue;

            Path target = destNormalized.resolve( name ).normalize();
            if ( !target.startsWith( destNormalized ) ) {
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

            // Record top-level mods/ entries so the manifest can list them.
            // Skip subdirectories (e.g. mods/ccSensors/api/foo.lua) — those
            // are mod-internal files, not standalone mod containers.
            if ( name.startsWith( "mods/" ) ) {
                String rel = name.substring( "mods/".length() );
                if ( !rel.isEmpty() && !rel.contains( "/" ) ) {
                    String lower = rel.toLowerCase();
                    if ( lower.endsWith( ".jar" ) || lower.endsWith( ".zip" ) ) {
                        topLevelModNames.add( rel );
                    }
                }
            }
        }
    }

    /** Builds the Mica manifest body. Mod entries reference local files
     *  by basename (under {@code mods/}); the launch-time sync verifier
     *  finds them on disk and skips the download step since the
     *  {@code remote} URL is empty.
     *
     *  <p>{@code packMinecraftVersion} + {@code packModLoaderURL} are
     *  left empty on purpose — the user must fill these in via the
     *  modpack editor before launching. We don't try to derive them
     *  from the server JAR because the heuristics are fragile across
     *  the wide range of Technic-era packs.</p> */
    private static JsonObject buildManifest( String packName, String packVersion,
                                              List< String > modFilenames )
    {
        JsonObject manifest = new JsonObject();
        manifest.addProperty( "manifestFormat", 2 );
        manifest.addProperty( "packName", packName );
        manifest.addProperty( "packVersion", packVersion );
        manifest.addProperty( "packURL", "" );
        manifest.addProperty( "packMinRAMGB", "3" );
        manifest.addProperty( "packUnstable", false );
        manifest.addProperty( "packCustomDiscordRpc", false );
        manifest.addProperty( "packLogoURL", ModPackConstants.MODPACK_DEFAULT_LOGO_URL );
        manifest.addProperty( "packBackgroundURL", ModPackConstants.MODPACK_DEFAULT_BG_URL );

        // Loader + MC version left empty. The user fills these in via the
        // editor; the surrounding UI surfaces the "needs editing" prompt
        // in the import-complete notification so they know to do that
        // before clicking Play.
        manifest.addProperty( "packModLoader", ModPackConstants.MOD_LOADER_FORGE );
        manifest.addProperty( "packModLoaderURL", "" );
        manifest.addProperty( "packModLoaderHash", "" );
        manifest.addProperty( "packForgeURL", "" );
        manifest.addProperty( "packForgeHash", "" );
        manifest.addProperty( "packMinecraftVersion", "" );

        JsonArray mods = new JsonArray();
        for ( String modName : modFilenames ) {
            JsonObject mod = new JsonObject();
            mod.addProperty( "name", stripExtension( modName ) );
            mod.addProperty( "remote", "" );
            mod.addProperty( "local", "mods/" + modName );
            mod.addProperty( "hashType", "none" );
            mod.addProperty( "hash", "" );
            mods.add( mod );
        }

        manifest.add( "packScanExclusions", new JsonArray() );
        manifest.add( "packMods", mods );
        manifest.add( "packConfigs", new JsonArray() );
        manifest.add( "packResourcePacks", new JsonArray() );
        manifest.add( "packShaderPacks", new JsonArray() );
        manifest.add( "packInitialFiles", new JsonArray() );
        return manifest;
    }

    private static String stripExtension( String filename )
    {
        int dot = filename.lastIndexOf( '.' );
        return dot > 0 ? filename.substring( 0, dot ) : filename;
    }

    private static String sanitize( String s )
    {
        if ( s == null ) return "unknown";
        return s.replaceAll( "[^A-Za-z0-9._-]", "_" );
    }

    /** Mirrors the other importers' checked exception type so the GUI
     *  can catch a single import-failure type generically. */
    public static class ImportException extends Exception
    {
        public ImportException( String message ) { super( message ); }
    }
}
