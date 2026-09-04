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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the file-based verification helpers in {@link HashUtilities} —
 * {@code verifySHA1}, {@code verifySHA256}, {@code verifyMD5} — which gate
 * every downloaded library, asset, and Forge installer JAR before the
 * launcher trusts it enough to put on the classpath or execute. A wrong
 * verdict here in either direction is a real problem: a false "match" would
 * let a tampered or corrupted file through the launcher's one integrity
 * check, and a false "no match" would make every launch fail against
 * perfectly good files. Also pins that the hex comparison underneath
 * (constant-time, case-folding) actually accepts mixed-case hashes, since
 * manifests are not consistent about hash casing.
 *
 * @since 3.0
 */
class HashUtilitiesFileTest
{
    private static Path writeFile( Path dir, String name, String content ) throws Exception
    {
        Path file = dir.resolve( name );
        Files.writeString( file, content, StandardCharsets.UTF_8 );
        return file;
    }

    @Test
    void verifySHA1_matchesKnownVector( @TempDir Path tempDir ) throws Exception
    {
        // SHA-1("abc") = a9993e364706816aba3e25717850c26c9cd0d89d
        File file = writeFile( tempDir, "a.txt", "abc" ).toFile();
        assertTrue( HashUtilities.verifySHA1( file, "a9993e364706816aba3e25717850c26c9cd0d89d" ) );
    }

    @Test
    void verifySHA1_acceptsMixedCaseExpectedHash( @TempDir Path tempDir ) throws Exception
    {
        File file = writeFile( tempDir, "a.txt", "abc" ).toFile();
        assertTrue( HashUtilities.verifySHA1( file, "A9993E364706816ABA3E25717850C26C9CD0D89D" ) );
    }

    @Test
    void verifySHA1_rejectsTamperedContent( @TempDir Path tempDir ) throws Exception
    {
        File file = writeFile( tempDir, "a.txt", "abcd" ).toFile();
        assertFalse( HashUtilities.verifySHA1( file, "a9993e364706816aba3e25717850c26c9cd0d89d" ) );
    }

    @Test
    void verifySHA1_returnsFalseForMissingFile( @TempDir Path tempDir )
    {
        File missing = tempDir.resolve( "does-not-exist.txt" ).toFile();
        assertFalse( HashUtilities.verifySHA1( missing, "a9993e364706816aba3e25717850c26c9cd0d89d" ) );
    }

    @Test
    void verifySHA256_matchesKnownVector( @TempDir Path tempDir ) throws Exception
    {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        File file = writeFile( tempDir, "a.txt", "abc" ).toFile();
        assertTrue( HashUtilities.verifySHA256(
                file, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" ) );
    }

    @Test
    void verifySHA256_rejectsWrongHash( @TempDir Path tempDir ) throws Exception
    {
        File file = writeFile( tempDir, "a.txt", "abc" ).toFile();
        assertFalse( HashUtilities.verifySHA256(
                file, "0000000000000000000000000000000000000000000000000000000000000000" ) );
    }

    @Test
    void verifyMD5_matchesKnownVector( @TempDir Path tempDir ) throws Exception
    {
        // MD5("abc") = 900150983cd24fb0d6963f7d28e17f72
        File file = writeFile( tempDir, "a.txt", "abc" ).toFile();
        assertTrue( HashUtilities.verifyMD5( file, "900150983cd24fb0d6963f7d28e17f72" ) );
    }

    @Test
    void getFileSHA1_producesLowerCase40CharHex( @TempDir Path tempDir ) throws Exception
    {
        File file = writeFile( tempDir, "a.txt", "hello world" ).toFile();
        String sha1 = HashUtilities.getFileSHA1( file );
        assertTrue( sha1.matches( "^[0-9a-f]{40}$" ), "expected lower-case 40-char hex, got: " + sha1 );
    }

    @Test
    void getFileSHA256_producesLowerCase64CharHex( @TempDir Path tempDir ) throws Exception
    {
        File file = writeFile( tempDir, "a.txt", "hello world" ).toFile();
        String sha256 = HashUtilities.getFileSHA256( file );
        assertTrue( sha256.matches( "^[0-9a-f]{64}$" ), "expected lower-case 64-char hex, got: " + sha256 );
    }

    @Test
    void getFileMD5_producesLowerCase32CharHex( @TempDir Path tempDir ) throws Exception
    {
        File file = writeFile( tempDir, "a.txt", "hello world" ).toFile();
        String md5 = HashUtilities.getFileMD5( file );
        assertTrue( md5.matches( "^[0-9a-f]{32}$" ), "expected lower-case 32-char hex, got: " + md5 );
    }

    @Test
    void verifySHA1_returnsFalseForDirectory( @TempDir Path tempDir )
    {
        // isFile() guard: a directory can never satisfy a hash check.
        assertFalse( HashUtilities.verifySHA1( tempDir.toFile(), "anything" ) );
    }
}
