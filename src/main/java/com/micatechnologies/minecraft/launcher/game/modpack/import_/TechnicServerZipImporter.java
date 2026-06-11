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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

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

    /** Walks {@code zip}'s entries and returns the first top-level
     *  {@code .jar} filename, or {@code null} if there isn't one. Used
     *  as a fallback when the launch-script parser fails — many older
     *  Technic packs only ship a single root JAR so this is reliable
     *  in practice. */
    private static String findFirstTopLevelJar( ZipFile zip )
    {
        Enumeration< ? extends ZipEntry > entries = zip.entries();
        while ( entries.hasMoreElements() ) {
            String name = entries.nextElement().getName().replace( '\\', '/' );
            if ( !name.contains( "/" ) && name.toLowerCase().endsWith( ".jar" ) ) {
                return name;
            }
        }
        return null;
    }

    /** Reads {@code launch.bat} (preferred) or {@code launch.sh} from {@code zip}
     *  and extracts the server JAR filename from the {@code -jar <name>} argument.
     *  Falls back to {@code null} when no launch script is present or no
     *  {@code -jar} reference parses; the caller then walks top-level entries to
     *  find any candidate {@code .jar}. */
    private static String findServerJarFromLaunchScript( ZipFile zip )
    {
        Pattern jarArg = Pattern.compile( "-jar\\s+([^\\s\"]+\\.jar)", Pattern.CASE_INSENSITIVE );
        for ( String scriptName : new String[] { "launch.bat", "launch.sh" } ) {
            ZipEntry entry = zip.getEntry( scriptName );
            if ( entry == null ) continue;
            try ( InputStream in = zip.getInputStream( entry ) ) {
                String body = new String( in.readAllBytes(), StandardCharsets.UTF_8 );
                Matcher m = jarArg.matcher( body );
                if ( m.find() ) {
                    String jarName = m.group( 1 );
                    // The path inside the script may include directories (e.g.
                    // "./Tekkit.jar"); strip leading components so it matches
                    // the top-level entry name inside the ZIP.
                    int slash = Math.max( jarName.lastIndexOf( '/' ),
                                            jarName.lastIndexOf( '\\' ) );
                    return slash >= 0 ? jarName.substring( slash + 1 ) : jarName;
                }
            }
            catch ( IOException ignored ) {
                // Try the next script.
            }
        }
        return null;
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

            // Inspect the server JAR for loader + MC version signals. We
            // identify the server JAR via the launch script's -jar arg
            // (more reliable than "first top-level .jar"); the detection
            // routine below opens that entry's bytes and walks its inner
            // entries for the telltale FML / Forge / Fabric / NeoForge
            // markers. If we don't find anything definitive, loaderInfo
            // is null and the manifest ships with empty loader fields —
            // user fills those in via the modpack editor.
            String serverJarName = findServerJarFromLaunchScript( zip );
            Logger.logStd( "TechnicServerZipImporter: launch-script-derived server JAR = "
                                   + ( serverJarName != null ? serverJarName : "<none>" ) );
            // Fallback: if launch script didn't surface a JAR (missing /
            // unparseable / -jar uses a path that doesn't normalize to a
            // top-level entry), scan the ZIP for the first top-level .jar.
            // Many older Technic server packs ship just one root JAR so
            // this catches the common case without false positives.
            if ( serverJarName == null ) {
                serverJarName = findFirstTopLevelJar( zip );
                Logger.logStd( "TechnicServerZipImporter: fallback top-level JAR scan = "
                                       + ( serverJarName != null ? serverJarName : "<none>" ) );
            }
            LoaderInfo loaderInfo = serverJarName != null
                    ? detectLoader( zip, serverJarName ) : null;
            if ( loaderInfo != null ) {
                Logger.logStd( "TechnicServerZipImporter: detected " + loaderInfo.loader
                                       + " loader" + ( loaderInfo.mcVersion != null
                                                                ? " for MC " + loaderInfo.mcVersion : "" )
                                       + " from " + serverJarName );
            }
            else {
                Logger.logStd( "TechnicServerZipImporter: no loader markers found in "
                                       + ( serverJarName != null ? serverJarName : "any JAR" )
                                       + " — manifest will ship with empty loader / MC version fields." );
            }

            JsonObject manifest = buildManifest( packName, packVersion, modFilenames, loaderInfo );
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
        long totalBytes = 0;
        int entryCount = 0;
        while ( entries.hasMoreElements() ) {
            ZipEntry entry = entries.nextElement();
            if ( ++entryCount > BoundedZipExtraction.MAX_ENTRIES ) {
                throw new IOException( "ZIP has too many entries (>" + BoundedZipExtraction.MAX_ENTRIES
                                               + ") — refusing to extract." );
            }
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
            // Bounded copy (per-entry + cumulative caps) — decompression-bomb guard.
            try ( InputStream in = zip.getInputStream( entry ) ) {
                totalBytes += BoundedZipExtraction.copyCapped( in, target,
                        BoundedZipExtraction.MAX_TOTAL_BYTES - totalBytes );
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
     *  <p>{@code packModLoader} + {@code packMinecraftVersion} are
     *  pre-populated when {@code loaderInfo} is non-null (detector
     *  found markers in the server JAR). The loader installer URL is
     *  always left blank since deriving it would require pinning a
     *  specific Forge / Fabric / NeoForge build URL that the launcher
     *  doesn't host for every MC version — user fills that in via the
     *  modpack editor.</p> */
    private static JsonObject buildManifest( String packName, String packVersion,
                                              List< String > modFilenames,
                                              LoaderInfo loaderInfo )
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

        // Loader pre-populated from the detector when available, else
        // defaults to Forge. The installer URL stays empty regardless —
        // the user fills it in via the editor to point at the right
        // installer for their MC version.
        String loader = loaderInfo != null ? loaderInfo.loader : ModPackConstants.MOD_LOADER_FORGE;
        String mcVersion = loaderInfo != null && loaderInfo.mcVersion != null
                ? loaderInfo.mcVersion : "";
        manifest.addProperty( "packModLoader", loader );
        manifest.addProperty( "packModLoaderURL", "" );
        manifest.addProperty( "packModLoaderHash", "" );
        manifest.addProperty( "packForgeURL", "" );
        manifest.addProperty( "packForgeHash", "" );
        manifest.addProperty( "packMinecraftVersion", mcVersion );

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

    /** Detected mod loader (Forge / NeoForge / Fabric) + Minecraft version
     *  parsed from the server JAR. Either field may be null when the
     *  detector found one signal but not the other — e.g. a Forge JAR with
     *  no embedded version properties produces {@code loader=forge,
     *  mcVersion=null}. */
    private record LoaderInfo( String loader, String mcVersion ) {}

    /**
     * Inspects the {@code serverJarEntryName} entry inside {@code outerZip}
     * and tries to identify the mod loader + Minecraft version. Reads the
     * entry's bytes into memory (server JARs are typically &lt;15MB) and
     * streams through them with {@link ZipInputStream} — no temp file,
     * no JarFile open from a nested entry.
     *
     * <p>Detection priorities:</p>
     * <ol>
     *   <li>{@code fmlversion.properties} at root → old-era Forge
     *       (1.2.5-1.6 MCPC). Parses {@code fmlbuild.mcversion} for the
     *       Minecraft version. Highest priority because it's the most
     *       authoritative signal we get for that era.</li>
     *   <li>{@code net/neoforged/...} class paths → NeoForge.</li>
     *   <li>{@code fabric-server-launch.properties} or
     *       {@code net/fabricmc/...} class paths → Fabric.</li>
     *   <li>{@code net/minecraftforge/...} or {@code cpw/mods/fml/...}
     *       class paths → modern Forge.</li>
     * </ol>
     *
     * <p>Returns {@code null} when none of the markers are present;
     * caller falls back to a blank manifest loader field.</p>
     */
    private static LoaderInfo detectLoader( ZipFile outerZip, String serverJarEntryName )
    {
        ZipEntry entry = outerZip.getEntry( serverJarEntryName );
        if ( entry == null ) return null;

        byte[] jarBytes;
        try ( InputStream in = outerZip.getInputStream( entry ) ) {
            jarBytes = in.readAllBytes();
        }
        catch ( IOException ioe ) {
            Logger.logWarningSilent( "TechnicServerZipImporter: couldn't read " + serverJarEntryName
                                              + " for loader detection: " + ioe.getMessage() );
            return null;
        }

        boolean sawNeoforged    = false;
        boolean sawFabric       = false;
        boolean sawForgeModern  = false;
        boolean sawFml          = false;
        String fmlMcVersion = null;
        int entriesScanned = 0;

        try ( ZipInputStream zin = new ZipInputStream( new ByteArrayInputStream( jarBytes ) ) ) {
            ZipEntry inner;
            while ( ( inner = zin.getNextEntry() ) != null ) {
                entriesScanned++;
                String name = inner.getName();
                if ( name.equals( "fmlversion.properties" ) ) {
                    // Highest-priority signal — parse it inline. The
                    // resource is small (a few hundred bytes); zin's
                    // read() yields just this entry's content until
                    // closeEntry / getNextEntry.
                    Properties props = new Properties();
                    try { props.load( zin ); }
                    catch ( IOException ignored ) { /* fall through */ }
                    fmlMcVersion = props.getProperty( "fmlbuild.mcversion" );
                    sawFml = true;
                    Logger.logStd( "TechnicServerZipImporter: found fmlversion.properties — "
                                            + "mcversion=" + fmlMcVersion );
                    // Don't break — keep scanning to confirm whether this
                    // is also a modern Forge / NeoForge variant.
                    continue;
                }
                if ( name.startsWith( "net/neoforged/" ) ) sawNeoforged = true;
                else if ( name.startsWith( "net/fabricmc/" )
                        || name.equals( "fabric-server-launch.properties" )
                        || name.equals( "fabric.mod.json" ) ) sawFabric = true;
                else if ( name.startsWith( "net/minecraftforge/" )
                        || name.startsWith( "cpw/mods/fml/" ) ) sawForgeModern = true;
            }
        }
        catch ( IOException ioe ) {
            Logger.logWarningSilent( "TechnicServerZipImporter: error scanning " + serverJarEntryName
                                              + ": " + ioe.getMessage() );
        }
        Logger.logStd( "TechnicServerZipImporter: scanned " + entriesScanned + " entries in "
                               + serverJarEntryName + " — fml=" + sawFml
                               + " forgeModern=" + sawForgeModern
                               + " neoforged=" + sawNeoforged
                               + " fabric=" + sawFabric
                               + " fmlMcVersion=" + fmlMcVersion );

        // Apply detection priorities. NeoForge / Fabric win over generic
        // Forge signals because the NeoForge / Fabric loaders may still
        // include some net/minecraftforge/... compat classes.
        if ( sawNeoforged ) return new LoaderInfo( ModPackConstants.MOD_LOADER_NEOFORGE, fmlMcVersion );
        if ( sawFabric )    return new LoaderInfo( ModPackConstants.MOD_LOADER_FABRIC, fmlMcVersion );
        if ( sawForgeModern || sawFml ) {
            return new LoaderInfo( ModPackConstants.MOD_LOADER_FORGE, fmlMcVersion );
        }
        return null;
    }

    /** Mirrors the other importers' checked exception type so the GUI
     *  can catch a single import-failure type generically. */
    public static class ImportException extends Exception
    {
        public ImportException( String message ) { super( message ); }
    }
}
