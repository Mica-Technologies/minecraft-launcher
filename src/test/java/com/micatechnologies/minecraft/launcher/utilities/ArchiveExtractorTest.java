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

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link ArchiveExtractor} — the containment-checked replacement for
 * the {@code jarchivelib} convenience call {@code RuntimeManager} used to
 * unpack downloaded JRE tarballs. A JRE distribution comes from a
 * runtime-source URL baked into the launcher, but the same extraction code
 * path is the launcher's only defense if that source is ever compromised or
 * mirrors an untrusted archive: zip-slip, absolute paths, and TAR/ZIP symlink
 * entries are exactly the tricks a hostile "JRE" tarball would use to write
 * outside the runtime install directory. Each guard is exercised against both
 * the TAR (gzipped) and ZIP code paths since they share the containment logic
 * but not the archive-format-specific entry-type checks.
 *
 * <p><b>One of these guards turned out not to work.</b> See
 * {@link #zipSymlinkEntryIsNotDetectedAndIsExtractedAsARegularFile} — the ZIP symlink check
 * can never fire because the streaming reader {@link ArchiveExtractor} uses never sees the
 * central-directory bits it inspects. Pinned, not fixed; see that test's javadoc.</p>
 *
 * @since 3.0
 */
class ArchiveExtractorTest
{
    // =========================================================================================
    //  Happy path
    // =========================================================================================

    @Test
    void extractsTarGzEntriesUnderTargetDir( @TempDir Path tempDir ) throws Exception
    {
        Path archive = tempDir.resolve( "jre.tar.gz" );
        writeTarGz( archive, w -> {
            w.fileEntry( "bin/java", "binary-content" );
            w.fileEntry( "lib/modules", "module-data" );
        } );

        Path dest = tempDir.resolve( "runtime" );
        ArchiveExtractor.extractTarGz( archive, dest );

        assertArrayEquals( "binary-content".getBytes( StandardCharsets.UTF_8 ),
                            Files.readAllBytes( dest.resolve( "bin/java" ) ) );
        assertArrayEquals( "module-data".getBytes( StandardCharsets.UTF_8 ),
                            Files.readAllBytes( dest.resolve( "lib/modules" ) ) );
    }

    @Test
    void extractsZipEntriesUnderTargetDir( @TempDir Path tempDir ) throws Exception
    {
        Path archive = tempDir.resolve( "jre.zip" );
        writeZip( archive, w -> w.fileEntry( "bin/java.exe", "binary-content" ) );

        Path dest = tempDir.resolve( "runtime" );
        ArchiveExtractor.extractZip( archive, dest );

        assertArrayEquals( "binary-content".getBytes( StandardCharsets.UTF_8 ),
                            Files.readAllBytes( dest.resolve( "bin/java.exe" ) ) );
    }

    // =========================================================================================
    //  Zip-slip / absolute-path containment
    // =========================================================================================

    @Test
    void tarGzRejectsEntryThatEscapesTargetDirViaDotDot( @TempDir Path tempDir ) throws Exception
    {
        Path archive = tempDir.resolve( "evil.tar.gz" );
        writeTarGz( archive, w -> w.rawNameFileEntry( "../../evil.txt", true, "payload" ) );

        Path dest = tempDir.resolve( "runtime" );
        assertThrows( IOException.class, () -> ArchiveExtractor.extractTarGz( archive, dest ) );
        assertFalse( Files.exists( tempDir.resolve( "evil.txt" ) ) );
    }

    @Test
    void tarGzRejectsAbsoluteEntryPath( @TempDir Path tempDir ) throws Exception
    {
        Path archive = tempDir.resolve( "evil.tar.gz" );
        writeTarGz( archive, w -> w.rawNameFileEntry( "/etc/evil.txt", true, "payload" ) );

        Path dest = tempDir.resolve( "runtime" );
        assertThrows( IOException.class, () -> ArchiveExtractor.extractTarGz( archive, dest ) );
    }

    @Test
    void zipRejectsEntryThatEscapesTargetDirViaDotDot( @TempDir Path tempDir ) throws Exception
    {
        Path archive = tempDir.resolve( "evil.zip" );
        writeZip( archive, w -> w.fileEntry( "../../evil.txt", "payload" ) );

        Path dest = tempDir.resolve( "runtime" );
        assertThrows( IOException.class, () -> ArchiveExtractor.extractZip( archive, dest ) );
        assertFalse( Files.exists( tempDir.resolve( "evil.txt" ) ) );
    }

    @Test
    void zipRejectsEntryWithNulByteInName( @TempDir Path tempDir ) throws Exception
    {
        Path archive = tempDir.resolve( "evil.zip" );
        writeZip( archive, w -> w.fileEntry( "innocuous.txt\0.jpg", "payload" ) );

        Path dest = tempDir.resolve( "runtime" );
        assertThrows( IOException.class, () -> ArchiveExtractor.extractZip( archive, dest ) );
    }

    // =========================================================================================
    //  Symlink rejection
    // =========================================================================================

    @Test
    void tarGzSkipsSymlinkEntryWithoutFollowingIt( @TempDir Path tempDir ) throws Exception
    {
        Path archive = tempDir.resolve( "jre.tar.gz" );
        writeTarGz( archive, w -> {
            w.symlinkEntry( "bin/java", "/usr/bin/malicious" );
            w.fileEntry( "lib/modules", "module-data" );
        } );

        Path dest = tempDir.resolve( "runtime" );
        ArchiveExtractor.extractTarGz( archive, dest );

        assertFalse( Files.exists( dest.resolve( "bin/java" ) ), "symlink entry must not be materialized" );
        assertTrue( Files.exists( dest.resolve( "lib/modules" ) ), "subsequent regular entries must still extract" );
    }

    /**
     * <b>Pins a real bug, does not fix it.</b> {@link ArchiveExtractor}'s javadoc claims ZIP
     * symlink entries are skipped "the same way" as TAR symlinks, via
     * {@code ZipArchiveEntry#isUnixSymlink()}. That check can never fire in this code path:
     * {@code extractEntries} reads with {@link org.apache.commons.compress.archivers.zip.ZipArchiveInputStream},
     * a <em>streaming</em> reader that only sees each entry's local file header. The Unix mode
     * bits {@code isUnixSymlink()} inspects live in the <em>central directory's</em> external
     * file attributes, which a streaming reader never visits — so every entry it hands back has
     * {@code platform == PLATFORM_FAT} and {@code getUnixMode() == 0}, symlink or not.
     *
     * <p>Net effect: a malicious ZIP-packaged "JRE" containing a Unix symlink entry is extracted
     * as an ordinary file (its content becomes whatever bytes follow the local header — here, the
     * literal link-target string) instead of being skipped. The TAR/gzip path is unaffected:
     * {@link TarArchiveEntry#isSymbolicLink()} reads the link flag out of the TAR header itself,
     * which a streaming TAR reader does see. Reported rather than fixed here since closing this
     * would mean switching the ZIP path to a random-access {@code ZipFile} (or manually reading
     * the central directory first) — a real behavior change outside this test's remit.</p>
     */
    @Test
    void zipSymlinkEntryIsNotDetectedAndIsExtractedAsARegularFile( @TempDir Path tempDir ) throws Exception
    {
        Path archive = tempDir.resolve( "jre.zip" );
        writeZip( archive, w -> {
            w.symlinkEntry( "bin/java", "/usr/bin/malicious" );
            w.fileEntry( "lib/modules", "module-data" );
        } );

        Path dest = tempDir.resolve( "runtime" );
        ArchiveExtractor.extractZip( archive, dest );

        assertTrue( Files.exists( dest.resolve( "bin/java" ) ),
                    "current (buggy) behavior: the symlink entry is NOT skipped" );
        assertArrayEquals( "/usr/bin/malicious".getBytes( StandardCharsets.UTF_8 ),
                            Files.readAllBytes( dest.resolve( "bin/java" ) ),
                            "the materialized file's content is the raw link-target bytes" );
        assertTrue( Files.exists( dest.resolve( "lib/modules" ) ), "subsequent regular entries still extract" );
    }

    // =========================================================================================
    //  Reserved names
    // =========================================================================================

    @Test
    void tarGzSkipsWindowsReservedDeviceNameEntry( @TempDir Path tempDir ) throws Exception
    {
        Path archive = tempDir.resolve( "jre.tar.gz" );
        writeTarGz( archive, w -> w.fileEntry( "con.txt", "payload" ) );

        Path dest = tempDir.resolve( "runtime" );
        ArchiveExtractor.extractTarGz( archive, dest );

        assertFalse( Files.exists( dest.resolve( "con.txt" ) ) );
    }

    // =========================================================================================
    //  Fixture helpers
    // =========================================================================================

    private interface TarWriter
    {
        void write( TarBuilder builder ) throws IOException;
    }

    private interface ZipWriter
    {
        void write( ZipBuilder builder ) throws IOException;
    }

    private static void writeTarGz( Path archive, TarWriter writer ) throws IOException
    {
        try ( OutputStream fos = Files.newOutputStream( archive );
              GzipCompressorOutputStream gz = new GzipCompressorOutputStream( fos );
              TarArchiveOutputStream tar = new TarArchiveOutputStream( gz ) ) {
            writer.write( new TarBuilder( tar ) );
            tar.finish();
        }
    }

    private static void writeZip( Path archive, ZipWriter writer ) throws IOException
    {
        try ( OutputStream fos = Files.newOutputStream( archive );
              ZipArchiveOutputStream zip = new ZipArchiveOutputStream( fos ) ) {
            writer.write( new ZipBuilder( zip ) );
            zip.finish();
        }
    }

    /** Small fluent wrapper so each test method reads as a list of archive entries. */
    private static final class TarBuilder
    {
        private final TarArchiveOutputStream tar;

        TarBuilder( TarArchiveOutputStream tar )
        {
            this.tar = tar;
        }

        void fileEntry( String name, String content ) throws IOException
        {
            rawNameFileEntry( name, false, content );
        }

        /** {@code preserveAbsolutePath} must be {@code true} to keep a leading {@code /} or
         *  {@code ../} segment intact — otherwise commons-compress itself normalizes the name
         *  before {@link ArchiveExtractor} ever sees it, which would test the library instead
         *  of the extractor's own containment check. */
        void rawNameFileEntry( String name, boolean preserveAbsolutePath, String content ) throws IOException
        {
            byte[] bytes = content.getBytes( StandardCharsets.UTF_8 );
            TarArchiveEntry entry = new TarArchiveEntry( name, preserveAbsolutePath );
            entry.setSize( bytes.length );
            tar.putArchiveEntry( entry );
            tar.write( bytes );
            tar.closeArchiveEntry();
        }

        void symlinkEntry( String name, String linkTarget ) throws IOException
        {
            TarArchiveEntry entry = new TarArchiveEntry( name, TarConstants.LF_SYMLINK );
            entry.setLinkName( linkTarget );
            entry.setSize( 0 );
            tar.putArchiveEntry( entry );
            tar.closeArchiveEntry();
        }
    }

    /** Small fluent wrapper so each test method reads as a list of archive entries. */
    private static final class ZipBuilder
    {
        private final ZipArchiveOutputStream zip;

        ZipBuilder( ZipArchiveOutputStream zip )
        {
            this.zip = zip;
        }

        void fileEntry( String name, String content ) throws IOException
        {
            byte[] bytes = content.getBytes( StandardCharsets.UTF_8 );
            ZipArchiveEntry entry = new ZipArchiveEntry( name );
            entry.setSize( bytes.length );
            zip.putArchiveEntry( entry );
            zip.write( bytes );
            zip.closeArchiveEntry();
        }

        void symlinkEntry( String name, String linkTarget ) throws IOException
        {
            byte[] targetBytes = linkTarget.getBytes( StandardCharsets.UTF_8 );
            ZipArchiveEntry entry = new ZipArchiveEntry( name );
            entry.setUnixMode( UnixStat.LINK_FLAG | 0755 );
            entry.setSize( targetBytes.length );
            zip.putArchiveEntry( entry );
            zip.write( targetBytes );
            zip.closeArchiveEntry();
        }
    }
}
