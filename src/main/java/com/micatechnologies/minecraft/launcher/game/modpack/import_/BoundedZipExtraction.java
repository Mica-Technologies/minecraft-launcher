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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Decompression-bomb limits shared by the ZIP-based pack importers
 * ({@link ModpackZipImporter}, {@link TechnicServerZipImporter}). Both block
 * Zip-Slip already, but — unlike the hardened {@code ArchiveExtractor} /
 * {@code SystemUtilities.extractJarFile} — they streamed entries with an
 * unbounded {@code Files.copy}, so a crafted import ZIP could decompress to an
 * arbitrarily large tree and fill the disk (DoS). The caps here are generous
 * (these are user content packs), enforced against the ACTUAL bytes streamed
 * rather than the attacker-declared {@code ZipEntry.getSize()}.
 *
 * @since 2026.6
 */
final class BoundedZipExtraction
{
    private BoundedZipExtraction() { /* static-only */ }

    /** Max decompressed bytes for any single entry. */
    static final long MAX_ENTRY_BYTES = 1024L * 1024 * 1024;          // 1 GB

    /** Max cumulative decompressed bytes across the whole archive. */
    static final long MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024;      // 4 GB

    /** Max number of entries (guards against an inode-exhaustion / many-tiny-files bomb). */
    static final int MAX_ENTRIES = 200_000;

    /**
     * Copies {@code in} to {@code target}, enforcing the per-entry cap and the
     * remaining cumulative budget against the real streamed byte count.
     *
     * @param in             the entry's input stream
     * @param target         destination file (created / truncated)
     * @param remainingTotal cumulative bytes still allowed across the archive
     *
     * @return the number of bytes written for this entry
     *
     * @throws IOException if either cap is exceeded (or on I/O error)
     */
    static long copyCapped( InputStream in, Path target, long remainingTotal ) throws IOException
    {
        byte[] buf = new byte[ 64 * 1024 ];
        long written = 0;
        try ( OutputStream out = Files.newOutputStream( target,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE ) ) {
            int n;
            while ( ( n = in.read( buf ) ) != -1 ) {
                written += n;
                if ( written > MAX_ENTRY_BYTES ) {
                    throw new IOException( "Archive entry exceeds the per-entry size cap ("
                                                   + MAX_ENTRY_BYTES + " bytes): " + target.getFileName() );
                }
                if ( written > remainingTotal ) {
                    throw new IOException( "Archive exceeds the total decompressed-size cap ("
                                                   + MAX_TOTAL_BYTES + " bytes) — refusing to extract." );
                }
                out.write( buf, 0, n );
            }
        }
        return written;
    }
}
