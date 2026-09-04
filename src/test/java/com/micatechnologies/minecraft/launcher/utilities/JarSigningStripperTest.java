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
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link JarSigningStripper} — the workaround that lets pre-1.6
 * {@code minecraft.jar} launch under launchwrapper's class-transforming
 * classloader without tripping {@code JarVerifier}. Two failure directions
 * matter equally here: stripping a jar that {@code isStripRequiredFor}
 * actually gates <em>out</em> (a modern, Forge-1.6+ jar) would be caught by
 * {@code FMLSanityChecker}'s "CRITICAL TAMPERING" abort in-game — a launch
 * failure that would look unrelated to this code. Failing to actually remove
 * every {@code .SF}/{@code .DSA}/{@code .RSA} entry (or the now-invalid
 * {@code Signature-Version} manifest attribute) would leave the JAR still
 * signed, reproducing the original {@code StringTranslate} NPE bug this class
 * exists to fix.
 *
 * @since 3.0
 */
class JarSigningStripperTest
{
    // =========================================================================================
    //  isStripRequiredFor — version gate
    // =========================================================================================

    @Test
    void requiresStripForPre1_6Releases()
    {
        assertTrue( JarSigningStripper.isStripRequiredFor( "1.5.2" ) );
        assertTrue( JarSigningStripper.isStripRequiredFor( "1.0" ) );
        assertTrue( JarSigningStripper.isStripRequiredFor( "1.5" ) );
    }

    @Test
    void doesNotRequireStripFor1_6AndLater()
    {
        assertFalse( JarSigningStripper.isStripRequiredFor( "1.6" ) );
        assertFalse( JarSigningStripper.isStripRequiredFor( "1.6.4" ) );
        assertFalse( JarSigningStripper.isStripRequiredFor( "1.12.2" ) );
        assertFalse( JarSigningStripper.isStripRequiredFor( "1.21" ) );
    }

    @Test
    void treatsSnapshotAndOddballStringsAsModern()
    {
        assertFalse( JarSigningStripper.isStripRequiredFor( "20w28a" ) );
        assertFalse( JarSigningStripper.isStripRequiredFor( "1.21-pre3" ) );
    }

    @Test
    void treatsNullAndEmptyAsNotRequiringStrip()
    {
        assertFalse( JarSigningStripper.isStripRequiredFor( null ) );
        assertFalse( JarSigningStripper.isStripRequiredFor( "" ) );
    }

    @Test
    void treatsVersionWithoutLeadingOneDotAsModern()
    {
        assertFalse( JarSigningStripper.isStripRequiredFor( "2.0" ) );
        assertFalse( JarSigningStripper.isStripRequiredFor( "b1.7.3" ) );
    }

    // =========================================================================================
    //  stripSigning — actual JAR rewrite
    // =========================================================================================

    @Test
    void stripSigning_removesSignatureFilesAndPreservesOtherEntries( @TempDir Path tempDir ) throws Exception
    {
        File jar = signedJar( tempDir, "signed.jar" );

        boolean rewritten = JarSigningStripper.stripSigning( jar );
        assertTrue( rewritten, "a signed jar should be reported as rewritten" );

        try ( JarFile result = new JarFile( jar ) ) {
            assertNull( result.getJarEntry( "META-INF/MOJANG_C.SF" ), "signature file must be removed" );
            assertNull( result.getJarEntry( "META-INF/MOJANG_C.DSA" ), "signature file must be removed" );

            JarEntry classEntry = result.getJarEntry( "net/minecraft/client/Minecraft.class" );
            assertEquals( "class-bytes", new String(
                    result.getInputStream( classEntry ).readAllBytes(), StandardCharsets.UTF_8 ) );

            JarEntry langEntry = result.getJarEntry( "lang/en_US.lang" );
            assertEquals( "hello=world", new String(
                    result.getInputStream( langEntry ).readAllBytes(), StandardCharsets.UTF_8 ) );
        }
    }

    @Test
    void stripSigning_removesSignatureVersionManifestAttribute( @TempDir Path tempDir ) throws Exception
    {
        File jar = signedJar( tempDir, "signed.jar" );

        JarSigningStripper.stripSigning( jar );

        try ( JarFile result = new JarFile( jar ) ) {
            var manifest = result.getManifest();
            assertNull( manifest.getMainAttributes().getValue( "Signature-Version" ) );
        }
    }

    @Test
    void stripSigning_returnsFalseAndLeavesUnsignedJarUntouched( @TempDir Path tempDir ) throws Exception
    {
        File jar = unsignedJar( tempDir, "unsigned.jar" );
        byte[] before = Files.readAllBytes( jar.toPath() );

        boolean rewritten = JarSigningStripper.stripSigning( jar );

        assertFalse( rewritten );
        assertArrayEquals( before, Files.readAllBytes( jar.toPath() ), "unsigned jar bytes must be untouched" );
    }

    @Test
    void stripSigning_returnsFalseForMissingFile( @TempDir Path tempDir ) throws Exception
    {
        File missing = tempDir.resolve( "does-not-exist.jar" ).toFile();
        assertFalse( JarSigningStripper.stripSigning( missing ) );
    }

    @Test
    void stripSigning_returnsFalseForNull() throws Exception
    {
        assertFalse( JarSigningStripper.stripSigning( null ) );
    }

    @Test
    void stripSigning_leavesNoTempFileBehindOnSuccess( @TempDir Path tempDir ) throws Exception
    {
        File jar = signedJar( tempDir, "signed.jar" );
        JarSigningStripper.stripSigning( jar );

        assertFalse( new File( jar.getAbsolutePath() + ".unsigned.tmp" ).exists() );
    }

    /** Builds a minimal JAR carrying {@code META-INF/MOJANG_C.SF} + {@code .DSA} signature
     *  entries, a {@code Signature-Version} manifest attribute, and two ordinary entries
     *  that must survive the strip untouched. */
    private static File signedJar( Path dir, String name ) throws Exception
    {
        Path jarPath = dir.resolve( name );
        try ( JarOutputStream jos = new JarOutputStream( Files.newOutputStream( jarPath ) ) ) {
            putEntry( jos, "META-INF/MANIFEST.MF",
                      "Manifest-Version: 1.0\nSignature-Version: 1.0\n" );
            putEntry( jos, "META-INF/MOJANG_C.SF", "signature-file-content" );
            putEntry( jos, "META-INF/MOJANG_C.DSA", "signature-block-content" );
            putEntry( jos, "net/minecraft/client/Minecraft.class", "class-bytes" );
            putEntry( jos, "lang/en_US.lang", "hello=world" );
        }
        return jarPath.toFile();
    }

    /** Builds a JAR with no signature entries at all. */
    private static File unsignedJar( Path dir, String name ) throws Exception
    {
        Path jarPath = dir.resolve( name );
        try ( JarOutputStream jos = new JarOutputStream( Files.newOutputStream( jarPath ) ) ) {
            putEntry( jos, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n" );
            putEntry( jos, "net/minecraft/client/Minecraft.class", "class-bytes" );
        }
        return jarPath.toFile();
    }

    private static void putEntry( JarOutputStream jos, String name, String content ) throws Exception
    {
        JarEntry entry = new JarEntry( name );
        jos.putNextEntry( entry );
        jos.write( content.getBytes( StandardCharsets.UTF_8 ) );
        jos.closeEntry();
    }
}
