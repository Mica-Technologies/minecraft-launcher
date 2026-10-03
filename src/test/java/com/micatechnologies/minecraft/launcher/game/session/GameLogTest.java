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


package com.micatechnologies.minecraft.launcher.game.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GameLog}: a game's output is captured, redacted, written to its file and
 * handed to viewers, including a viewer that subscribes halfway through.
 */
class GameLogTest
{
    @TempDir
    Path tempDir;

    private static ByteArrayInputStream lines( String... lines )
    {
        String text = lines.length == 0 ? "" : String.join( "\n", lines ) + "\n";
        return new ByteArrayInputStream( text.getBytes( StandardCharsets.UTF_8 ) );
    }

    @Test
    void capturesBothStreamsIntoMemoryAndTheFile() throws Exception
    {
        Path file = tempDir.resolve( "logs" ).resolve( "game.log" );
        GameLog log = new GameLog( file );
        log.attach( lines( "out one", "out two" ), lines( "err one" ) );

        assertTrue( log.awaitClosed( 5_000 ) );
        String text = log.text();
        assertTrue( text.contains( "out one\n" ) && text.contains( "out two\n" ) && text.contains( "err one\n" ) );
        List< String > written = Files.readAllLines( file );
        assertEquals( 3, written.size() );
        assertTrue( written.containsAll( List.of( "out one", "out two", "err one" ) ) );
    }

    @Test
    void tokensAreRedactedBeforeTheyAreStoredOrShown() throws Exception
    {
        Path file = tempDir.resolve( "game.log" );
        GameLog log = new GameLog( file );
        String token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.c2lnbmF0dXJlLXZhbHVl";
        log.attach( lines( "launching with --accessToken " + token + " --version 1.20.1" ), lines() );

        assertTrue( log.awaitClosed( 5_000 ) );
        assertFalse( log.text().contains( token ) );
        assertFalse( Files.readString( file ).contains( token ) );
    }

    @Test
    void listenersGetBatchesAndHearTheEnd() throws Exception
    {
        GameLog log = new GameLog( null );
        List< String > seen = new CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch ended = new java.util.concurrent.CountDownLatch( 1 );
        log.addListener( new GameLog.Listener()
        {
            @Override
            public void onLines( List< String > batch )
            {
                seen.addAll( batch );
            }

            @Override
            public void onClosed()
            {
                ended.countDown();
            }
        } );
        log.attach( lines( "a", "b", "c" ), lines() );

        assertTrue( ended.await( 5, java.util.concurrent.TimeUnit.SECONDS ) );
        assertEquals( List.of( "a", "b", "c" ), seen );
    }

    @Test
    void aLateSubscriberMissesNothingAndSeesNothingTwice() throws Exception
    {
        PipedOutputStream gameOut = new PipedOutputStream();
        PipedInputStream out = new PipedInputStream( gameOut, 1 << 16 );
        GameLog log = new GameLog( null );
        log.attach( out, lines() );

        for ( int i = 0; i < 200; i++ ) {
            gameOut.write( ( "early " + i + "\n" ).getBytes( StandardCharsets.UTF_8 ) );
        }
        gameOut.flush();
        Thread.sleep( 50 );  // let some lines be captured, some maybe still queued

        List< String > later = new CopyOnWriteArrayList<>();
        GameLog.Subscription sub = log.subscribe( batch -> later.addAll( batch ) );

        for ( int i = 0; i < 200; i++ ) {
            gameOut.write( ( "late " + i + "\n" ).getBytes( StandardCharsets.UTF_8 ) );
        }
        gameOut.close();
        assertTrue( log.awaitClosed( 5_000 ) );

        StringBuilder combined = new StringBuilder( sub.snapshot() );
        for ( String l : later ) {
            combined.append( l ).append( '\n' );
        }
        assertEquals( log.text(), combined.toString(), "snapshot + later lines = everything, once each" );
    }

    @Test
    void theInMemoryBufferIsBounded() throws Exception
    {
        GameLog log = new GameLog( null, 1_000, 500 );
        String[] many = new String[ 300 ];
        for ( int i = 0; i < many.length; i++ ) {
            many[ i ] = "line number " + i;
        }
        log.attach( lines( many ), lines() );
        assertTrue( log.awaitClosed( 5_000 ) );
        assertTrue( log.text().length() <= 1_000 );
        assertTrue( log.text().endsWith( "line number 299\n" ), "the newest lines are kept" );
    }

    @Test
    void logFileNamesAreSafe()
    {
        Path f = GameLog.fileFor( tempDir, "My Pack: Remastered/2", "2026-10-03--10-00-00" );
        assertEquals( "game-My_Pack__Remastered_2-2026-10-03--10-00-00.log", f.getFileName().toString() );
        assertEquals( tempDir, f.getParent() );
    }

    @Test
    void anUnwritableFileStillCapturesInMemory() throws IOException, InterruptedException
    {
        Path blocker = Files.writeString( tempDir.resolve( "not-a-dir" ), "x" );
        GameLog log = new GameLog( blocker.resolve( "game.log" ) );
        log.attach( lines( "still captured" ), lines() );
        assertTrue( log.awaitClosed( 5_000 ) );
        assertTrue( log.text().contains( "still captured" ) );
    }
}
