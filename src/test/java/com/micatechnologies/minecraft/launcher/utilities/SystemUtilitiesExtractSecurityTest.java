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

import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the archive-driven-attack defenses in
 * {@link SystemUtilities#extractJarFile}, which the existing
 * {@code SystemUtilitiesExtractTest} does not exercise (that class covers the
 * atomic-publish behavior only). This is the extraction path used on <em>every
 * launch</em> to unpack LWJGL natives from a Forge-installer-embedded JAR — a
 * source that is only as trustworthy as the modpack manifest that pointed at
 * it. If any of these guards regressed, a hostile or compromised modpack could
 * write outside the natives directory (zip-slip), plant a file at a
 * NUL-terminated or absolute path, or trip a Windows device-name collision —
 * all without the launcher raising so much as a warning.
 *
 * @since 3.0
 */
class SystemUtilitiesExtractSecurityTest
{
    @Test
    void rejectsEntryThatEscapesBaseDirViaDotDot( @TempDir Path tempDir ) throws Exception
    {
        Path jar = singleRawEntryJar( tempDir, "../../evil.txt", "payload" );
        Path dest = tempDir.resolve( "out" );
        Files.createDirectories( dest );

        try ( JarFile jf = new JarFile( jar.toFile() ) ) {
            assertThrows( ModpackException.class, () -> SystemUtilities.extractJarFile( jf, dest.toString() ) );
        }

        // Confirm nothing was written outside the destination directory.
        assertFalse( Files.exists( tempDir.resolve( "evil.txt" ) ) );
    }

    @Test
    void rejectsAbsoluteUnixStyleEntryPath( @TempDir Path tempDir ) throws Exception
    {
        Path jar = singleRawEntryJar( tempDir, "/etc/evil.txt", "payload" );
        Path dest = tempDir.resolve( "out" );
        Files.createDirectories( dest );

        try ( JarFile jf = new JarFile( jar.toFile() ) ) {
            assertThrows( ModpackException.class, () -> SystemUtilities.extractJarFile( jf, dest.toString() ) );
        }
    }

    @Test
    void rejectsEntryNameContainingNulByte( @TempDir Path tempDir ) throws Exception
    {
        Path jar = singleRawEntryJar( tempDir, "innocuous.txt\0.jpg", "payload" );
        Path dest = tempDir.resolve( "out" );
        Files.createDirectories( dest );

        try ( JarFile jf = new JarFile( jar.toFile() ) ) {
            assertThrows( ModpackException.class, () -> SystemUtilities.extractJarFile( jf, dest.toString() ) );
        }
    }

    @Test
    void skipsWindowsReservedDeviceNameEntryWithoutThrowing( @TempDir Path tempDir ) throws Exception
    {
        // Reserved names are silently skipped (logged), not rejected outright —
        // the archive as a whole may still contain legitimate entries.
        Path jar = singleRawEntryJar( tempDir, "con.txt", "payload" );
        Path dest = tempDir.resolve( "out" );
        Files.createDirectories( dest );

        try ( JarFile jf = new JarFile( jar.toFile() ) ) {
            SystemUtilities.extractJarFile( jf, dest.toString() );
        }

        assertFalse( Files.exists( dest.resolve( "con.txt" ) ), "reserved device name must not be materialized" );
    }

    @Test
    void reservedNameCheckIsCaseInsensitiveAndExtensionStripped( @TempDir Path tempDir ) throws Exception
    {
        Path jar = singleRawEntryJar( tempDir, "COM1.dat", "payload" );
        Path dest = tempDir.resolve( "out" );
        Files.createDirectories( dest );

        try ( JarFile jf = new JarFile( jar.toFile() ) ) {
            SystemUtilities.extractJarFile( jf, dest.toString() );
        }

        assertFalse( Files.exists( dest.resolve( "COM1.dat" ) ) );
    }

    @Test
    void skipsMetaInfEntriesWithoutError( @TempDir Path tempDir ) throws Exception
    {
        Path jar = singleRawEntryJar( tempDir, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n" );
        Path dest = tempDir.resolve( "out" );
        Files.createDirectories( dest );

        try ( JarFile jf = new JarFile( jar.toFile() ) ) {
            SystemUtilities.extractJarFile( jf, dest.toString() );
        }

        assertTrue( Files.list( dest ).findAny().isEmpty(), "META-INF entries must not be extracted" );
    }

    /**
     * Builds a single-entry JAR whose entry name is exactly {@code entryName} —
     * including names that {@link JarEntry}'s own constructor would otherwise
     * reject or normalize away (a leading {@code /}, a NUL byte, {@code ..}
     * segments). Writing raw local-file-header bytes with Commons Compress-free
     * plain {@code java.util.zip} lets the malicious name reach
     * {@link SystemUtilities#extractJarFile} exactly as a hostile archive would
     * present it, rather than being sanitized by the JDK's own {@code ZipEntry}
     * validation before the test even starts.
     */
    private static Path singleRawEntryJar( Path dir, String entryName, String content ) throws IOException
    {
        Path jar = dir.resolve( "raw-" + Integer.toHexString( entryName.hashCode() ) + ".jar" );
        try ( JarOutputStream jos = new JarOutputStream( Files.newOutputStream( jar ) ) ) {
            JarEntry entry = new JarEntry( entryName );
            jos.putNextEntry( entry );
            jos.write( content.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
            jos.closeEntry();
        }
        return jar;
    }
}
