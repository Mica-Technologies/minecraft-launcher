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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static com.micatechnologies.minecraft.launcher.consts.localization.LocalizedMessages.assertFromKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link BoundedZipExtraction#copyCapped}, the decompression-
 * bomb guard shared by {@link ModpackZipImporter} and
 * {@link TechnicServerZipImporter}. Both of those importers stream
 * entries from a ZIP that came from an arbitrary user download; without
 * this cap, a maliciously (or just badly) crafted archive with a
 * modest compressed size but an enormous decompressed size could fill
 * the user's disk before either importer's own per-entry-count or
 * marker-validation checks ever get a chance to reject the archive.
 *
 * <p>{@link BoundedZipExtraction#MAX_ENTRY_BYTES} (1 GB) is not
 * exercised by writing an actual gigabyte of data — that would make
 * this test slow for no extra confidence, since the check is a simple
 * numeric comparison against the running byte count. Instead, the
 * cumulative-budget behavior is exercised through the {@code
 * remainingTotal} parameter the caller passes in (which is what
 * actually varies per-call in the importers, as each entry consumes
 * from the same shrinking budget) — the constants themselves are
 * pinned with a direct value assertion so a future accidental change
 * to their magnitude doesn't slip by unnoticed.</p>
 *
 * @since 2026.6
 */
class BoundedZipExtractionTest
{
    @TempDir
    Path tempDir;

    @Test
    void capConstantsMatchDocumentedValues()
    {
        assertEquals( 1024L * 1024 * 1024, BoundedZipExtraction.MAX_ENTRY_BYTES );
        assertEquals( 4L * 1024 * 1024 * 1024, BoundedZipExtraction.MAX_TOTAL_BYTES );
        assertEquals( 200_000, BoundedZipExtraction.MAX_ENTRIES );
    }

    @Test
    void copiesWithinBudgetSucceedsAndReturnsExactByteCount() throws IOException
    {
        byte[] data = "hello world".getBytes( StandardCharsets.UTF_8 );
        Path target = tempDir.resolve( "ok.txt" );
        long written = BoundedZipExtraction.copyCapped( new ByteArrayInputStream( data ), target, 1_000_000L );
        assertEquals( data.length, written );
        assertEquals( "hello world", Files.readString( target ) );
    }

    @Test
    void copyExactlyAtRemainingBudgetSucceeds() throws IOException
    {
        // The guard is "written > remainingTotal" — exactly-at-budget must
        // NOT throw.
        byte[] data = new byte[ 1000 ];
        Arrays.fill( data, (byte) 'x' );
        Path target = tempDir.resolve( "exact.bin" );
        long written = BoundedZipExtraction.copyCapped( new ByteArrayInputStream( data ), target, 1000L );
        assertEquals( 1000L, written );
    }

    @Test
    void exceedingRemainingBudgetByOneByteThrows()
    {
        byte[] data = new byte[ 1001 ];
        Arrays.fill( data, (byte) 'x' );
        Path target = tempDir.resolve( "over.bin" );
        IOException ex = assertThrows( IOException.class,
                                        () -> BoundedZipExtraction.copyCapped(
                                                new ByteArrayInputStream( data ), target, 1000L ) );
        assertFromKey( "importer.error.archiveTooLarge", ex.getMessage() );
    }

    @Test
    void budgetIsEnforcedAcrossMultipleInternalReadChunks() throws IOException
    {
        // copyCapped reads in 64 KiB chunks. A stream considerably larger
        // than one chunk, with a remaining budget smaller than the stream
        // but larger than one chunk, proves the cumulative counter (not
        // just a single-read check) is what trips the guard.
        int totalSize = 200 * 1024; // ~3 chunks
        byte[] data = new byte[ totalSize ];
        Arrays.fill( data, (byte) 'z' );
        Path target = tempDir.resolve( "chunked.bin" );
        long budget = 150 * 1024L; // trips partway through the 3rd chunk
        IOException ex = assertThrows( IOException.class,
                                        () -> BoundedZipExtraction.copyCapped(
                                                new ByteArrayInputStream( data ), target, budget ) );
        assertFromKey( "importer.error.archiveTooLarge", ex.getMessage() );
    }

    @Test
    void ioErrorFromSourceStreamPropagates()
    {
        InputStream explodingStream = new InputStream()
        {
            @Override
            public int read()
            {
                throw new RuntimeException( "should not be called; read(byte[]) is used" );
            }

            @Override
            public int read( byte[] b, int off, int len ) throws IOException
            {
                throw new IOException( "synthetic read failure" );
            }
        };
        Path target = tempDir.resolve( "boom.bin" );
        IOException ex = assertThrows( IOException.class,
                                        () -> BoundedZipExtraction.copyCapped( explodingStream, target, 1000L ) );
        assertEquals( "synthetic read failure", ex.getMessage() );
    }
}
