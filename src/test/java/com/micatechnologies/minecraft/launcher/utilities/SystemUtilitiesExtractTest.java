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

package com.micatechnologies.minecraft.launcher.utilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the atomic-publish behavior of
 * {@link SystemUtilities#extractJarFile}. The launcher extracts LWJGL
 * natives on every launch by overwriting files at their final paths;
 * a partial write from an interrupted launch or AV-scan interference
 * used to leave a truncated DLL on disk, which Windows
 * {@code LoadLibrary} surfaces as "DLL initialization routine failed"
 * (process exit 0xC0000005). The extractor now writes each entry to a
 * sibling temp file and atomic-moves it into place, so the destination
 * is always either the previous-good content or the new-complete
 * content — never partial.
 */
class SystemUtilitiesExtractTest
{
    @Test
    void extractsContentExactly( @TempDir Path tempDir ) throws Exception
    {
        byte[] payload = randomPayload( 17 * 1024 );
        Path jar = makeJar( tempDir, "natives.jar", "lwjgl64.dll", payload );
        Path dest = tempDir.resolve( "out" );
        Files.createDirectories( dest );

        try ( JarFile jf = new JarFile( jar.toFile() ) ) {
            SystemUtilities.extractJarFile( jf, dest.toString() );
        }

        byte[] actual = Files.readAllBytes( dest.resolve( "lwjgl64.dll" ) );
        assertArrayEquals( payload, actual,
                           "extracted file content should match the JAR entry byte-for-byte" );
    }

    @Test
    void reExtractOverwritesAndLeavesNoTempFiles( @TempDir Path tempDir ) throws Exception
    {
        Path dest = tempDir.resolve( "out" );
        Files.createDirectories( dest );

        // First extract: payload A.
        byte[] payloadA = randomPayload( 4 * 1024 );
        Path jarA = makeJar( tempDir, "a.jar", "lwjgl64.dll", payloadA );
        try ( JarFile jf = new JarFile( jarA.toFile() ) ) {
            SystemUtilities.extractJarFile( jf, dest.toString() );
        }

        // Second extract: payload B, same entry name. The atomic move
        // path means the destination flips from A to B with no
        // partial state in between.
        byte[] payloadB = randomPayload( 6 * 1024 );
        Path jarB = makeJar( tempDir, "b.jar", "lwjgl64.dll", payloadB );
        try ( JarFile jf = new JarFile( jarB.toFile() ) ) {
            SystemUtilities.extractJarFile( jf, dest.toString() );
        }

        byte[] actual = Files.readAllBytes( dest.resolve( "lwjgl64.dll" ) );
        assertArrayEquals( payloadB, actual,
                           "second extract should fully replace the first payload" );

        // No `.tmp-<uuid>` files should be left behind once the
        // atomic move completes — leftovers would accumulate forever
        // since the destination folder isn't wiped between launches.
        try ( Stream< Path > children = Files.list( dest ) ) {
            long tempCount = children
                    .map( p -> p.getFileName().toString() )
                    .filter( name -> name.contains( ".tmp-" ) )
                    .count();
            assertEquals( 0L, tempCount,
                          "no temp files should remain in the destination after extract" );
        }
    }

    @Test
    void handlesMultipleEntries( @TempDir Path tempDir ) throws Exception
    {
        Path jar = tempDir.resolve( "multi.jar" );
        byte[] dll = randomPayload( 8 * 1024 );
        byte[] txt = "hello world\n".getBytes();
        try ( JarOutputStream jos = new JarOutputStream(
                Files.newOutputStream( jar ) ) ) {
            putEntry( jos, "lwjgl64.dll", dll );
            putEntry( jos, "subdir/openal.dll", txt );
        }
        Path dest = tempDir.resolve( "out" );
        Files.createDirectories( dest );

        try ( JarFile jf = new JarFile( jar.toFile() ) ) {
            SystemUtilities.extractJarFile( jf, dest.toString() );
        }

        assertArrayEquals( dll, Files.readAllBytes( dest.resolve( "lwjgl64.dll" ) ) );
        assertArrayEquals( txt, Files.readAllBytes( dest.resolve( "subdir/openal.dll" ) ) );
    }

    private static Path makeJar( Path dir, String name, String entryName, byte[] content )
            throws IOException
    {
        Path jar = dir.resolve( name );
        try ( JarOutputStream jos = new JarOutputStream( Files.newOutputStream( jar ) ) ) {
            putEntry( jos, entryName, content );
        }
        return jar;
    }

    private static void putEntry( JarOutputStream jos, String name, byte[] content )
            throws IOException
    {
        JarEntry entry = new JarEntry( name );
        jos.putNextEntry( entry );
        jos.write( content );
        jos.closeEntry();
    }

    private static byte[] randomPayload( int size )
    {
        byte[] out = new byte[ size ];
        // Deterministic per-test payload so failures are reproducible
        // — `size` doubles as the seed.
        java.util.Random rng = new java.util.Random( size );
        rng.nextBytes( out );
        return out;
    }

}
