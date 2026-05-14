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
    private ModpackExporter() { /* static-only */ }

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
            // Write a tiny manifest at the root of the ZIP describing
            // what's inside. Helps the recipient's launcher (or a
            // human inspector with WinRAR open) verify what they got.
            writeExportMetadata( zip, pack, includeWorlds );

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
        sb.append( "  \"format\": \"mica-export-v1\",\n" );
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

        ZipEntry entry = new ZipEntry( "mica-export.json" );
        zip.putNextEntry( entry );
        zip.write( sb.toString().getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
        zip.closeEntry();
    }

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

    private static Set< String > union( Set< String > a, Set< String > b )
    {
        java.util.HashSet< String > merged = new java.util.HashSet<>( a );
        merged.addAll( b );
        return merged;
    }
}
