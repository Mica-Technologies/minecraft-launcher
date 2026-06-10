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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import com.micatechnologies.minecraft.launcher.utilities.StringOrArray;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for the install-index concurrency bug: {@code fetchInstalledModPacks}
 * fans manifest loads out over a {@code parallelStream}, so the index is mutated from many
 * threads at once. The original {@code load() / upsert() / save()} sequence on separate
 * instances raced two ways — lost updates (two loaders share a snapshot, the later save
 * wins) and a corrupt file (two saves interleaved writes to a single shared temp name,
 * publishing garbled JSON that the next {@code load()} rejected with a
 * {@code JsonSyntaxException}). {@link InstallIndex#upsertAndSave} serializes the whole
 * read-modify-write and writes a unique temp per save; this test pounds it from many
 * threads and asserts neither failure mode occurs.
 *
 * <p>Uses the {@code testPathOverride} seam so it targets a {@link TempDir} instead of the
 * real launcher folder — no network, no FX, finishes well under a second.</p>
 */
class InstallIndexConcurrencyTest
{
    @TempDir
    Path tmpDir;

    private Path indexFile;

    @BeforeEach
    void redirectIndexToTempDir()
    {
        indexFile = tmpDir.resolve( "install_index.json" );
        InstallIndex.testPathOverride = indexFile;
    }

    @AfterEach
    void clearOverride()
    {
        InstallIndex.testPathOverride = null;
    }

    private static GameModPack pack( int id )
    {
        GameModPack p = new GameModPack();
        p.packName = "Pack " + id;
        p.packVersion = "1." + id;
        p.packMinRAMGB = "2";
        p.packLogoURL = StringOrArray.of( "https://example.com/logo" + id + ".png" );
        p.packBackgroundURL = StringOrArray.of( "https://example.com/bg" + id + ".png" );
        return p;
    }

    @Test
    void concurrentWritesNeitherCorruptNorLoseEntries() throws Exception
    {
        // High thread count (contention is what reproduced the corruption) but a small
        // per-thread count keeps total writes — and thus the O(n^2) re-parse-the-growing-
        // file cost — low enough that the test stays well under a second.
        final int threads = 16;
        final int perThread = 5;
        final int expected = threads * perThread;

        ExecutorService pool = Executors.newFixedThreadPool( threads );
        CountDownLatch startGun = new CountDownLatch( 1 );
        List< Future< ? > > futures = new ArrayList<>();

        for ( int t = 0; t < threads; t++ ) {
            final int threadIndex = t;
            futures.add( pool.submit( () -> {
                try {
                    startGun.await(); // release all threads at once to maximize contention
                }
                catch ( InterruptedException e ) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for ( int k = 0; k < perThread; k++ ) {
                    int id = threadIndex * perThread + k;
                    InstallIndex.upsertAndSave( "https://host/pack/" + id, pack( id ) );
                }
            } ) );
        }

        startGun.countDown();
        for ( Future< ? > f : futures ) {
            f.get( 30, TimeUnit.SECONDS );
        }
        pool.shutdown();

        // 1) No corruption: load() returns the real index, not the empty fallback it
        //    produces when the on-disk bytes fail to parse (JsonSyntaxException).
        InstallIndex idx = InstallIndex.load();
        assertEquals( expected, idx.packs.size(),
                      "every concurrent upsert should survive (no lost updates, no corrupt-then-empty reload)" );

        // 2) No lost updates: each distinct URL is present.
        for ( int id = 0; id < expected; id++ ) {
            assertNotNull( idx.get( "https://host/pack/" + id ), "missing entry for id " + id );
        }

        // 3) The raw bytes on disk parse as JSON — i.e. no torn write was ever published.
        String raw = Files.readString( indexFile, StandardCharsets.UTF_8 );
        assertDoesNotThrow( () -> JSONUtilities.getGson().fromJson( raw, InstallIndex.class ) );

        // 4) No stray per-write temp files left behind.
        try ( var stream = Files.list( tmpDir ) ) {
            long temps = stream.filter( p -> p.getFileName().toString().contains( ".tmp." ) ).count();
            assertEquals( 0, temps, "all unique temp files should have been moved into place" );
        }
    }
}
