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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verify mode and audit target are per prepare run, not global. Two packs prepare at once and
 * share one download pool, so a launcher-wide verify mode let pack A's FAST_PATH skip hashing
 * in pack B's FULL verify (and B then recorded a full verify that never hashed anything).
 */
class LaunchPrepareContextTest
{
    private static final byte[] HELLO_BYTES = "hello\n".getBytes();
    private static final String WRONG_SHA1  = "0000000000000000000000000000000000000000";

    private static final LaunchPrepareContext FAST =
            LaunchPrepareContext.forLaunch( LaunchVerifyMode.FAST_PATH, null, -1 );
    private static final LaunchPrepareContext FULL =
            LaunchPrepareContext.forLaunch( LaunchVerifyMode.FULL, null, -1 );

    @Test
    void noneHashesAndRecordsNothing()
    {
        assertEquals( LaunchVerifyMode.FULL, LaunchPrepareContext.NONE.verifyMode() );
        assertFalse( LaunchPrepareContext.NONE.isAuditing() );
        assertEquals( LaunchVerifyMode.FULL, LaunchPrepareContext.forLaunch( null, null, 1 ).verifyMode() );
        assertSame( LaunchPrepareContext.NONE, LaunchPrepareContext.fullVerify() );
    }

    @Test
    void eachCheckFollowsTheContextItIsGiven( @TempDir Path tmp ) throws Exception
    {
        ManagedGameFile corrupt = corruptFile( tmp );

        assertTrue( corrupt.verifyLocalFileForTest( FAST ), "FAST_PATH accepts on existence alone" );
        assertFalse( corrupt.verifyLocalFileForTest( FULL ), "FULL hashes and rejects the mismatch" );
        assertFalse( corrupt.verifyLocalFileForTest(), "no context means FULL" );
    }

    @Test
    void oneLaunchesFastPathNeverReachesAnotherLaunchesFullVerify( @TempDir Path tmp ) throws Exception
    {
        ManagedGameFile corrupt = corruptFile( tmp );
        ExecutorService pool = Executors.newFixedThreadPool( 8 );
        try {
            List< Future< Boolean > > packA = new ArrayList<>();
            List< Future< Boolean > > packB = new ArrayList<>();
            for ( int i = 0; i < 200; i++ ) {
                packA.add( pool.submit( () -> corrupt.verifyLocalFileForTest( FAST ) ) );
                packB.add( pool.submit( () -> corrupt.verifyLocalFileForTest( FULL ) ) );
            }
            for ( Future< Boolean > f : packA ) {
                assertTrue( f.get() );
            }
            for ( Future< Boolean > f : packB ) {
                assertFalse( f.get(), "pack B's FULL verify must hash even while pack A fast-paths" );
            }
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    void eachPackCarriesItsOwnContext()
    {
        GameModPack a = new GameModPack();
        GameModPack b = new GameModPack();
        assertSame( LaunchPrepareContext.NONE, a.getPrepareContext() );

        a.setPrepareContext( FAST );
        assertSame( FAST, a.getPrepareContext() );
        assertSame( LaunchPrepareContext.NONE, b.getPrepareContext(), "another pack is untouched" );

        a.setPrepareContext( null );
        assertSame( LaunchPrepareContext.NONE, a.getPrepareContext() );
    }

    /** A file on disk whose declared SHA-1 does not match its bytes. */
    private static ManagedGameFile corruptFile( Path tmp ) throws Exception
    {
        Path file = tmp.resolve( "hello.txt" );
        Files.write( file, HELLO_BYTES );
        ManagedGameFile f = new ManagedGameFile( "https://e/x.jar", file.getFileName().toString(),
                                                 WRONG_SHA1, null, null );
        f.setLocalPathPrefix( file.getParent().toString() + java.io.File.separator );
        return f;
    }
}
