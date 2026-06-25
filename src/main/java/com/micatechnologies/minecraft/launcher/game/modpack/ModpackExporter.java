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

import com.micatechnologies.minecraft.launcher.files.Logger;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs an installed modpack into a portable ZIP for sharing /
 * backup. The reverse of the existing mrpack import flow — produces a
 * single ZIP file the user can hand to a friend or upload somewhere,
 * containing the manifest + mods + configs + (optionally) worlds and
 * everything else the pack needs to come back to life on another
 * machine.
 *
 * <h3>What's included</h3>
 * <ul>
 *   <li>Everything under the pack's install folder</li>
 *   <li>...minus regeneratable / huge content under
 *       {@link #DEFAULT_EXCLUSIONS} — the JRE, downloaded Minecraft
 *       libraries, asset cache, logs (regenerated next launch),
 *       crash-reports (large + privacy-sensitive),
 *       {@code launcher_profiles.json} (machine-specific paths)</li>
 *   <li>By default the {@code saves/} folder is excluded too —
 *       worlds can be huge (GB-scale for explored worlds) and the
 *       caller can opt in via {@link #exportToZip(GameModPack, File, boolean, LongConsumer)}.</li>
 * </ul>
 *
 * <h3>What's not included</h3>
 * <p>The launcher's own per-user auth / config state lives under
 * {@code <config>/} (sibling of the pack root), not the pack root,
 * so an exported ZIP carries zero personally-identifiable
 * information beyond what the user put in the modpack themselves
 * (e.g. screenshot files they might have taken in-game).</p>
 *
 * @since 2026.5
 */
public final class ModpackExporter
{
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ModpackExporter() { /* static-only */ }

    /** Marker file embedded at the root of every Mica modpack export. The
     *  import path uses its presence + the {@code format} field to confirm
     *  a ZIP actually came from this launcher before extracting anything —
     *  unrecognized ZIPs surface a clear "not a Mica modpack export" message
     *  rather than scattering files into a half-formed pack folder. */
    public static final String MARKER_FILENAME = "mica-export.json";

    /** Pack manifest body embedded alongside the marker file. This is the
     *  same JSON the launcher reads at install time, so the import path
     *  can simply drop it into {@code imported-manifests/} and call
     *  {@code installModPackByURL} — no re-derivation needed. */
    public static final String MANIFEST_FILENAME = "manifest.json";

    /** Current export marker version. Bumped from v1 (metadata-only) to
     *  v2 when we started embedding the full pack manifest body in the
     *  archive — import-by-ZIP requires v2+ so v1 ZIPs (no manifest)
     *  produce a friendly upgrade-needed message rather than silently
     *  failing later in the extract path. */
    public static final String EXPORT_FORMAT_V2 = "mica-export-v2";

    /**
     * Which sharing mode makes sense for a given pack. Drives the
     * smart-export UI: a pack installed from a remote URL can be
     * shared by URL; a locally-imported pack whose mods are all
     * fetched from HTTPS URLs can be shared as raw JSON; everything
     * else (local-file-reference mods, no manifest body) needs the
     * full ZIP roundtrip.
     */
    public enum ExportMode
    {
        /** Manifest URL is HTTP/S — recipient pastes the URL into Add by URL. */
        SHARE_URL,
        /** Manifest is local but every {@code packMods.remote} is an HTTP/S
         *  URL, so the JSON alone is sufficient for the recipient to install. */
        SHARE_MANIFEST_JSON,
        /** Pack has local-file-reference mods (or no usable manifest) — full
         *  archive needed. */
        EXPORT_ZIP
    }

    /**
     * Inspects {@code pack} and decides which export modes apply. Cheap —
     * reads the manifest body from the on-disk cache, no network. Returns
     * a non-null mode; callers can still offer the lower-tier modes
     * (every pack supports EXPORT_ZIP, and SHARE_URL implies the JSON is
     * also self-contained) but the returned value is the "best" / most
     * lightweight mode available.
     *
     * @param pack the pack to classify; a {@code null} pack maps to
     *             {@link ExportMode#EXPORT_ZIP}
     * @return the best available {@link ExportMode} for the pack, never {@code null}
     */
    public static ExportMode classifyExport( GameModPack pack )
    {
        if ( pack == null ) return ExportMode.EXPORT_ZIP;
        String url = pack.getManifestUrl();
        if ( url != null && ( url.startsWith( "http://" ) || url.startsWith( "https://" ) ) ) {
            return ExportMode.SHARE_URL;
        }
        // Read the manifest body so we can inspect each mod's remote field.
        // A file:// URL pointing at imported-manifests/ is the common case
        // for Modrinth / Technic imports; their packMods are all HTTPS-backed
        // so the JSON is portable on its own.
        if ( url != null && url.startsWith( "file:" ) ) {
            String body = GameModPackFetcher.loadManifestText( url );
            if ( body != null && allPackModsRemoteHttp( body ) ) {
                return ExportMode.SHARE_MANIFEST_JSON;
            }
        }
        return ExportMode.EXPORT_ZIP;
    }

    /** Returns the raw manifest body for {@code pack}, or {@code null} when
     *  no readable manifest exists (vanilla / failed packs). Convenience
     *  for the Share-Manifest path; identical to
     *  {@link GameModPackFetcher#loadManifestText(String)} but takes a
     *  pack so callers don't have to thread the URL through.
     *
     *  @param pack the pack whose manifest body to load; {@code null} yields {@code null}
     *  @return the raw manifest JSON, or {@code null} when no readable manifest exists */
    public static String loadManifestText( GameModPack pack )
    {
        if ( pack == null ) return null;
        return GameModPackFetcher.loadManifestText( pack.getManifestUrl() );
    }

    /**
     * Checks if all mods in the given manifest JSON have remote URLs that start with "http://" or "https://".
     *
     * @param manifestJson the JSON string of the pack's manifest
     * @return true if all mods have remote URLs starting with "http://" or "https://", false otherwise
     */
    private static boolean allPackModsRemoteHttp( String manifestJson )
    {
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString( manifestJson )
                    .getAsJsonObject();
            if ( !obj.has( "packMods" ) || !obj.get( "packMods" ).isJsonArray() ) {
                // No mods at all — nothing to download, JSON is trivially portable.
                return true;
            }
            for ( com.google.gson.JsonElement el : obj.getAsJsonArray( "packMods" ) ) {
                if ( !el.isJsonObject() ) return false;
                com.google.gson.JsonObject mod = el.getAsJsonObject();
                if ( !mod.has( "remote" ) || !mod.get( "remote" ).isJsonPrimitive() ) return false;
                String remote = mod.get( "remote" ).getAsString();
                if ( remote == null || remote.isBlank() ) return false;
                String lower = remote.toLowerCase( java.util.Locale.ROOT );
                if ( !lower.startsWith( "http://" ) && !lower.startsWith( "https://" ) ) return false;
            }
            return true;
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "ModpackExporter: manifest parse failed during mode classification — "
                                              + t.getClass().getSimpleName() + ": " + t.getMessage() );
            return false;
        }
    }

    /** Folder names under the pack root that are excluded from
     *  exports by default. The launcher recreates these on next
     *  launch, and including them would balloon the ZIP from MB to
     *  GB without giving the recipient anything useful. */
    private static final Set< String > DEFAULT_EXCLUSIONS = Set.of(
            "libraries",       // Minecraft + loader libraries (re-downloaded)
            "bin",             // assembled bin/forge*.jar etc. (rebuilt)
            "assets",          // Minecraft asset cache (re-downloaded)
            "runtime",         // bundled JRE (if any)
            "logs",            // game logs (regenerated)
            "crash-reports",   // privacy + size — recipient doesn't need these
            "natives",         // platform-specific natives (re-downloaded)
            "versions"         // Mojang's vanilla version folder cache
    );

    /** When this set is augmented with {@code "saves"}, worlds are
     *  excluded too. Default {@link #exportToZip(GameModPack, File)}
     *  uses the worlds-excluded path; the 3-arg overload exposes the
     *  toggle. */
    private static final Set< String > WORLDS_FOLDER = Set.of( "saves" );

    /**
     * Export the pack with worlds excluded. Convenience for the
     * common case.
     *
     * @param pack           the installed pack to export
     * @param destinationZip output file; overwritten if it exists
     * @throws IOException if the pack has no install folder, its folder is
     *                     missing, or writing the archive fails
     */
    public static void exportToZip( GameModPack pack, File destinationZip ) throws IOException
    {
        exportToZip( pack, destinationZip, false, null );
    }

    /**
     * Export the pack to {@code destinationZip}.
     *
     * @param pack            the installed pack to export
     * @param destinationZip  output file; overwritten if it exists
     * @param includeWorlds   when true, also includes the pack's
     *                        {@code saves/} folder
     * @param progress        optional callback receiving bytes-written
     *                        running total; useful for UI progress
     *                        indicators
     * @throws IllegalArgumentException if {@code pack} is {@code null}
     * @throws IOException              if the pack has no install folder, its
     *                                 folder is missing, or writing the archive fails
     */
    public static void exportToZip( GameModPack pack, File destinationZip,
                                     boolean includeWorlds, LongConsumer progress ) throws IOException
    {
        if ( pack == null ) throw new IllegalArgumentException( "pack is null" );
        String rootStr = pack.getPackRootFolder();
        if ( rootStr == null ) throw new IOException( "Pack has no install folder." );
        Path root = Paths.get( rootStr );
        if ( !Files.isDirectory( root ) ) {
            throw new IOException( "Pack install folder is missing: " + rootStr );
        }
        Set< String > excludes = includeWorlds
                ? DEFAULT_EXCLUSIONS
                : union( DEFAULT_EXCLUSIONS, WORLDS_FOLDER );

        // Ensure parent dir exists.
        File parent = destinationZip.getParentFile();
        if ( parent != null && !parent.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }

        final long[] bytesWritten = { 0L };
        try ( ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream( new FileOutputStream( destinationZip ) ) ) )
        {
            // Write the marker file (mica-export.json) + the full pack
            // manifest body (manifest.json) at the ZIP root. The marker
            // is what import uses to validate the ZIP actually came from
            // this launcher; the manifest is what gets registered with
            // GameModPackManager on the recipient's side.
            writeExportMetadata( zip, pack, includeWorlds );
            writeManifestBody( zip, pack );

            Files.walkFileTree( root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory( Path dir, BasicFileAttributes attrs ) {
                    if ( dir.equals( root ) ) return FileVisitResult.CONTINUE;
                    Path rel = root.relativize( dir );
                    String topLevel = rel.getName( 0 ).toString();
                    return excludes.contains( topLevel )
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile( Path file, BasicFileAttributes attrs ) throws IOException {
                    Path rel = root.relativize( file );
                    // Use forward slashes inside the ZIP regardless of OS
                    // so the archive is portable.
                    String entryName = rel.toString().replace( File.separatorChar, '/' );
                    ZipEntry entry = new ZipEntry( entryName );
                    entry.setTime( attrs.lastModifiedTime().toMillis() );
                    zip.putNextEntry( entry );
                    Files.copy( file, zip );
                    zip.closeEntry();
                    bytesWritten[ 0 ] += attrs.size();
                    if ( progress != null ) progress.accept( bytesWritten[ 0 ] );
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed( Path file, IOException exc ) {
                    Logger.logWarningSilent( "Skipping unreadable file in export: " + file
                                                     + " (" + exc.getMessage() + ")" );
                    return FileVisitResult.CONTINUE;
                }
            } );
        }
    }

    /** Inline metadata blob at the root of the ZIP. Plain JSON so
     *  it's easy to inspect with any text editor; matches roughly
     *  the shape of an mrpack manifest minus the URL-list bits since
     *  this export already includes the binaries. */
    private static void writeExportMetadata( ZipOutputStream zip, GameModPack pack,
                                              boolean worldsIncluded ) throws IOException
    {
        StringBuilder sb = new StringBuilder( "{\n" );
        sb.append( "  \"format\": \"" ).append( EXPORT_FORMAT_V2 ).append( "\",\n" );
        sb.append( "  \"packName\": " ).append( jsonString( pack.getPackName() ) ).append( ",\n" );
        try {
            sb.append( "  \"packVersion\": " ).append( jsonString( pack.getPackVersion() ) ).append( ",\n" );
        }
        catch ( Exception ignored ) { /* optional */ }
        try {
            sb.append( "  \"minecraftVersion\": " ).append( jsonString( pack.getMinecraftVersion() ) ).append( ",\n" );
        }
        catch ( Exception ignored ) { /* optional */ }
        try {
            sb.append( "  \"loader\": " ).append( jsonString( pack.getModLoaderType() ) ).append( ",\n" );
        }
        catch ( Exception ignored ) { /* optional */ }
        sb.append( "  \"worldsIncluded\": " ).append( worldsIncluded ).append( ",\n" );
        sb.append( "  \"exportedAtMs\": " ).append( System.currentTimeMillis() ).append( "\n" );
        sb.append( "}\n" );

        ZipEntry entry = new ZipEntry( MARKER_FILENAME );
        zip.putNextEntry( entry );
        zip.write( sb.toString().getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
        zip.closeEntry();
    }

    /** Embeds the pack's manifest JSON at the ZIP root. The import path
     *  reads this back verbatim, writes it into {@code imported-manifests/},
     *  and hands the resulting {@code file:} URL to
     *  {@code GameModPackManager.installModPackByURL} — so the full mod
     *  list / configs / loader settings round-trip through the archive.
     *
     *  <p>Falls back gracefully when the manifest body can't be read
     *  (vanilla packs, failed-load placeholders): the ZIP still gets
     *  the marker file + pack contents, but import will reject it as
     *  "no manifest in archive" instead of completing into an unusable
     *  state.</p> */
    private static void writeManifestBody( ZipOutputStream zip, GameModPack pack ) throws IOException
    {
        String body = GameModPackFetcher.loadManifestText( pack.getManifestUrl() );
        if ( body == null || body.isBlank() ) {
            Logger.logWarningSilent( "ModpackExporter: no manifest body available for "
                                              + pack.getPackName() + " — ZIP will lack "
                                              + MANIFEST_FILENAME + " entry." );
            return;
        }
        ZipEntry entry = new ZipEntry( MANIFEST_FILENAME );
        zip.putNextEntry( entry );
        zip.write( body.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
        zip.closeEntry();
    }

    /**
     * Escapes special characters in a string to make it safe for JSON.
     *
     * @param s the string to escape
     * @return the escaped string
     */
    private static String jsonString( String s )
    {
        if ( s == null ) return "null";
        StringBuilder out = new StringBuilder( "\"" );
        for ( int i = 0; i < s.length(); i++ ) {
            char c = s.charAt( i );
            switch ( c ) {
                case '\\' -> out.append( "\\\\" );
                case '"'  -> out.append( "\\\"" );
                case '\n' -> out.append( "\\n" );
                case '\r' -> out.append( "\\r" );
                case '\t' -> out.append( "\\t" );
                default -> out.append( c );
            }
        }
        return out.append( "\"" ).toString();
    }

    /**
     * Returns the union of two sets.
     *
     * @param a the first set
     * @param b the second set
     * @return a new set containing all elements from both sets
     */
    private static Set< String > union( Set< String > a, Set< String > b )
    {
        java.util.HashSet< String > merged = new java.util.HashSet<>( a );
        merged.addAll( b );
        return merged;
    }
}
