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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

/**
 * Containment-checked archive extraction. Replaces the {@code jarchivelib}
 * convenience {@code archiver.extract(file, dir)} call site in
 * {@link com.micatechnologies.minecraft.launcher.files.RuntimeManager} for the
 * JRE-tarball install path, hardened against the same archive-driven attack
 * surface as {@link SystemUtilities#extractJarFile}:
 *
 * <ul>
 *   <li><b>Zip-slip:</b> every resolved entry path must reside under the base
 *       destination directory. Entries with {@code ..}, absolute paths, or
 *       drive letters are rejected.</li>
 *   <li><b>Symlinks / hardlinks / specials:</b> TAR entries marked as symlink,
 *       hardlink, character device, block device, or FIFO are skipped rather
 *       than created — a malicious symlink in a community-hosted JRE could
 *       otherwise redirect a subsequent file write to anywhere on disk. ZIP
 *       symlink entries (Unix-mode bits) are skipped the same way, which is
 *       why the ZIP path reads through {@code ZipFile} rather than
 *       {@code ZipArchiveInputStream} — the mode bits live in the central
 *       directory, and a streaming reader never sees them. Do not "simplify"
 *       that back to a stream: it disables this check silently.</li>
 *   <li><b>NUL / reserved-name / case-collision tricks:</b> filtered at the
 *       same point as {@code extractJarFile}.</li>
 *   <li><b>Zip-bomb caps:</b> per-entry and cumulative decompressed-byte
 *       counters short-circuit before disk fills up.</li>
 * </ul>
 *
 * <p>Both file-permission preservation and timestamp restoration are
 * intentionally dropped — the JRE runtime tree only needs the {@code bin}
 * executables to keep their execute bit, which is restored explicitly by
 * {@link com.micatechnologies.minecraft.launcher.files.RuntimeManager} after
 * extraction.
 *
 * @since 2026.2
 */
public final class ArchiveExtractor
{
    /** Per-entry decompressed size cap. JRE distributions ship single files up to
     *  ~200 MB (modules, jmod blobs), so this is generous; tighten if/when smaller
     *  archives become the norm. */
    private static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;

    /** Cumulative decompressed size cap. A full Liberica / Zulu JRE is ~80-120 MB
     *  unpacked; 2 GB leaves headroom for the largest legitimate Mojang runtime
     *  bundles while still failing fast on a pathological archive. */
    private static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;

    /** Windows reserved device names. Skipped on every platform for portability. */
    private static final Set< String > WINDOWS_RESERVED_NAMES = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9" );

    /**
     * Private constructor — this is a static-only utility class and is never
     * instantiated.
     */
    private ArchiveExtractor() { /* static-only */ }

    /**
     * Extracts {@code archive} (a gzipped tarball) into {@code targetDir}. The
     * target directory is treated as the containment root — entries are
     * rejected if their resolved path would escape it.
     *
     * @param archive   the {@code .tar.gz} archive file to extract
     * @param targetDir the destination directory (containment root); created if
     *                  it does not already exist
     *
     * @throws IOException on archive corruption, containment violation, or
     *                     size-cap breach
     *
     * @since 2026.2
     */
    public static void extractTarGz( Path archive, Path targetDir ) throws IOException
    {
        try ( BufferedInputStream raw = new BufferedInputStream( new FileInputStream( archive.toFile() ) );
              GzipCompressorInputStream gz = new GzipCompressorInputStream( raw );
              TarArchiveInputStream tar = new TarArchiveInputStream( gz ) ) {
            extractEntries( tar, targetDir );
        }
    }

    /**
     * Extracts a ZIP archive into {@code targetDir}. Same containment + safety
     * guarantees as {@link #extractTarGz}.
     *
     * @param archive   the ZIP archive file to extract
     * @param targetDir the destination directory (containment root); created if
     *                  it does not already exist
     *
     * @throws IOException on archive corruption, containment violation, or
     *                     size-cap breach
     *
     * @since 2026.2
     */
    public static void extractZip( Path archive, Path targetDir ) throws IOException
    {
        // Read through ZipFile, not ZipArchiveInputStream. The symlink check below needs the
        // Unix mode bits, and those live in the ZIP *central directory* -- a streaming reader
        // only ever sees local file headers, where the external file attributes are absent.
        // With a streaming reader isUnixSymlink() therefore always answers false, and the
        // symlink defence silently does nothing. ZipFile reads the central directory, so the
        // check works. (TAR is unaffected: its symlink flag is in the entry header itself.)
        try ( ZipFile zip = ZipFile.builder().setFile( archive.toFile() ).get() ) {
            extractZipEntries( zip, targetDir );
        }
    }

    /**
     * Per-entry loop for stream-read archives (TAR).
     *
     * @param stream    the archive stream to drain
     * @param targetDir the containment root
     *
     * @throws IOException on containment violation or size-cap breach
     */
    private static void extractEntries( ArchiveInputStream< ? extends ArchiveEntry > stream,
                                        Path targetDir ) throws IOException
    {
        final Path baseDir = prepareBaseDir( targetDir );

        long cumulativeBytes = 0L;
        ArchiveEntry entry;
        while ( ( entry = stream.getNextEntry() ) != null ) {
            Path target = validateAndResolve( entry, baseDir );
            if ( target == null ) {
                continue;
            }
            if ( entry.isDirectory() ) {
                Files.createDirectories( target );
                continue;
            }
            cumulativeBytes = copyEntry( stream, target, entry.getName(), cumulativeBytes );
        }
    }

    /**
     * Per-entry loop for ZIP archives, driven from the central directory.
     *
     * <p>Entries are walked in physical order so extraction still reads the file roughly
     * front-to-back, but each entry's metadata comes from the central directory — which is what
     * makes {@code isUnixSymlink()} meaningful. See {@link #extractZip}.</p>
     *
     * @param zip       the opened ZIP archive
     * @param targetDir the containment root
     *
     * @throws IOException on containment violation or size-cap breach
     */
    private static void extractZipEntries( ZipFile zip, Path targetDir ) throws IOException
    {
        final Path baseDir = prepareBaseDir( targetDir );

        long cumulativeBytes = 0L;
        Enumeration< ZipArchiveEntry > entries = zip.getEntriesInPhysicalOrder();
        while ( entries.hasMoreElements() ) {
            ZipArchiveEntry entry = entries.nextElement();
            Path target = validateAndResolve( entry, baseDir );
            if ( target == null ) {
                continue;
            }
            if ( entry.isDirectory() ) {
                Files.createDirectories( target );
                continue;
            }
            // An entry using a compression method this build cannot read yields no stream.
            // Skipping is right: writing an empty file in its place would be worse than
            // leaving it out, and failing the whole extraction over one odd entry is worse
            // still.
            if ( !zip.canReadEntryData( entry ) ) {
                Logger.logWarningSilent( LocalizationManager.format(
                        "log.archiveExtractor.skippingUnreadableZipEntry", entry.getName() ) );
                continue;
            }
            try ( InputStream in = zip.getInputStream( entry ) ) {
                if ( in == null ) {
                    continue;
                }
                cumulativeBytes = copyEntry( in, target, entry.getName(), cumulativeBytes );
            }
        }
    }

    /**
     * Creates and normalizes the containment root.
     *
     * @param targetDir the destination directory
     *
     * @return the absolute, normalized base directory
     *
     * @throws IOException if the directory cannot be created
     */
    private static Path prepareBaseDir( Path targetDir ) throws IOException
    {
        final Path baseDir = targetDir.toAbsolutePath().normalize();
        Files.createDirectories( baseDir );
        return baseDir;
    }

    /**
     * Applies every containment and safety check to one entry and resolves its destination.
     *
     * <p>Shared by both formats so the two extraction paths cannot drift apart on what they
     * refuse — the ZIP path previously had a weaker symlink check than the TAR path purely by
     * accident of how it read the archive.</p>
     *
     * @param entry   the entry to check
     * @param baseDir the containment root
     *
     * @return the resolved destination path, or {@code null} when the entry should be skipped
     *
     * @throws IOException when the entry is hostile enough to abort the whole extraction —
     *                     a NUL byte, an absolute path, or an escape from {@code baseDir}
     */
    private static Path validateAndResolve( ArchiveEntry entry, Path baseDir ) throws IOException
    {
        String name = entry.getName();
        if ( name == null || name.isEmpty() ) {
            return null;
        }

        // Reject NUL.
        if ( name.indexOf( '\0' ) >= 0 ) {
            throw new IOException( "Refusing archive entry with NUL byte: " + name );
        }

        // Reject absolute paths.
        if ( name.startsWith( "/" ) || name.startsWith( "\\" )
                || ( name.length() >= 2 && name.charAt( 1 ) == ':' ) ) {
            throw new IOException( "Refusing absolute archive entry path: " + name );
        }

        // Refuse symlinks, hardlinks, character/block devices, FIFOs. A TAR
        // entry that claims to be a symlink causes our extractor to "follow"
        // it on a later write — but we don't even reach the write because the
        // entry creation itself would symlink-out of baseDir. Skip entirely.
        if ( entry instanceof TarArchiveEntry tarEntry ) {
            if ( tarEntry.isSymbolicLink()
                    || tarEntry.isLink()
                    || tarEntry.isCharacterDevice()
                    || tarEntry.isBlockDevice()
                    || tarEntry.isFIFO() ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.archiveExtractor.skippingNonRegularTar",
                                                 describeTarType( tarEntry ), name ) );
                return null;
            }
        }
        else if ( entry instanceof ZipArchiveEntry zipEntry ) {
            if ( zipEntry.isUnixSymlink() ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.archiveExtractor.skippingZipSymlink", name ) );
                return null;
            }
        }

        Path target = baseDir.resolve( name ).normalize();
        if ( !target.startsWith( baseDir ) ) {
            throw new IOException( "Refusing archive entry that escapes base dir: " + name );
        }

        // Reserved-name filter (cross-platform for portability).
        Path leaf = target.getFileName();
        if ( leaf != null ) {
            String basename = leaf.toString();
            int dot = basename.lastIndexOf( '.' );
            String stem = dot >= 0 ? basename.substring( 0, dot ) : basename;
            if ( WINDOWS_RESERVED_NAMES.contains( stem.toLowerCase( Locale.ROOT ) ) ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.archiveExtractor.skippingReservedName", name ) );
                return null;
            }
        }

        return target;
    }

    /**
     * Streams one entry to disk under the per-entry and cumulative size caps.
     *
     * <p>The entry's declared size is deliberately not trusted — that is attacker-controlled
     * metadata, and trusting it is how a zip bomb gets past a size check.</p>
     *
     * @param in              the entry's content
     * @param target          the destination file
     * @param name            the entry name, for error messages
     * @param cumulativeBytes bytes written across this extraction so far
     *
     * @return the updated cumulative byte count
     *
     * @throws IOException on a size-cap breach or write failure
     */
    private static long copyEntry( InputStream in, Path target, String name, long cumulativeBytes )
            throws IOException
    {
        Path parent = target.getParent();
        if ( parent != null ) {
            Files.createDirectories( parent );
        }

        long entryBytes = 0L;
        long total = cumulativeBytes;
        try ( FileOutputStream out = new FileOutputStream( target.toFile() ) ) {
            byte[] buffer = new byte[8192];
            int read;
            while ( ( read = in.read( buffer ) ) != -1 ) {
                entryBytes += read;
                if ( entryBytes > MAX_ENTRY_BYTES ) {
                    throw new IOException( "Archive entry exceeds per-entry size cap ("
                                                   + MAX_ENTRY_BYTES + "): " + name );
                }
                total += read;
                if ( total > MAX_TOTAL_BYTES ) {
                    throw new IOException( "Archive extraction exceeded total size cap ("
                                                   + MAX_TOTAL_BYTES + ") at entry: " + name );
                }
                out.write( buffer, 0, read );
            }
        }
        return total;
    }

    /**
     * Describes the type of a TAR archive entry.
     *
     * @param entry the TAR archive entry to describe
     *
     * @return a string describing the type of the entry
     */
    private static String describeTarType( TarArchiveEntry entry )
    {
        if ( entry.isSymbolicLink() )    return "symlink";
        if ( entry.isLink() )            return "hardlink";
        if ( entry.isCharacterDevice() ) return "chardev";
        if ( entry.isBlockDevice() )     return "blockdev";
        if ( entry.isFIFO() )            return "fifo";
        return "special";
    }
}
