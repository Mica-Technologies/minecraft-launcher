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

package com.micatechnologies.minecraft.launcher.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Logger#rotateLogFiles(File)} — the size-triggered log rotation that keeps a
 * long-running launch (or a server that's been up for weeks) from growing an unbounded log file.
 *
 * <p>Why this matters: this method runs against the launcher's own live log file, working
 * entirely through file-system renames with no higher-level safety net. A sequencing bug here
 * (shifting backups in the wrong order, or forgetting to make room before renaming) silently loses
 * log history — exactly the diagnostic trail a user or a support request needs to reconstruct
 * what a crashed launch actually did. It had no direct test despite manipulating real files on
 * disk; widened from private to package-private so it can be driven directly against a
 * {@code @TempDir}, without initializing the log system's global streams.</p>
 */
class LoggerRotationTest
{
    private static void write( File f, String content ) throws IOException
    {
        Files.writeString( f.toPath(), content );
    }

    private static String read( File f ) throws IOException
    {
        return Files.readString( f.toPath() );
    }

    @Test
    void rotatingWithNoExistingBackupsRenamesTheLogToDotOne( @TempDir File dir ) throws IOException
    {
        File log = new File( dir, "launcher.log" );
        write( log, "current session" );

        Logger.rotateLogFiles( log );

        assertFalse( log.exists(), "the live log file should have been renamed away" );
        File backup1 = new File( dir, "launcher.log.1" );
        assertTrue( backup1.exists() );
        assertEquals( "current session", read( backup1 ) );
    }

    @Test
    void rotatingShiftsAnExistingBackupUpByOne( @TempDir File dir ) throws IOException
    {
        File log = new File( dir, "launcher.log" );
        write( log, "newest" );
        File backup1 = new File( dir, "launcher.log.1" );
        write( backup1, "previously newest" );

        Logger.rotateLogFiles( log );

        assertEquals( "newest", read( new File( dir, "launcher.log.1" ) ) );
        assertEquals( "previously newest", read( new File( dir, "launcher.log.2" ) ) );
    }

    /**
     * With every backup slot already occupied, rotating must delete the oldest ({@code .3}) to
     * make room, then shift every remaining backup up by one, then rename the live log to
     * {@code .1}. A sequencing mistake here (e.g. deleting before reading, or shifting in the
     * wrong direction) would either lose a generation early or silently duplicate one.
     */
    @Test
    void rotatingAtCapacityDeletesTheOldestBackupAndShiftsTheRest( @TempDir File dir ) throws IOException
    {
        File log = new File( dir, "launcher.log" );
        write( log, "gen0" );
        // Fill every backup slot (.1 .. .MAX_LOG_BACKUPS) so the oldest must be evicted.
        for ( int i = 1; i <= Logger.MAX_LOG_BACKUPS; i++ ) {
            write( new File( dir, "launcher.log." + i ), "gen" + i );
        }

        Logger.rotateLogFiles( log );

        assertEquals( "gen0", read( new File( dir, "launcher.log.1" ) ) );
        for ( int i = 1; i < Logger.MAX_LOG_BACKUPS; i++ ) {
            assertEquals( "gen" + i, read( new File( dir, "launcher.log." + ( i + 1 ) ) ) );
        }
        // The former oldest generation must be gone -- there is no slot beyond MAX_LOG_BACKUPS.
        assertFalse( new File( dir, "launcher.log." + ( Logger.MAX_LOG_BACKUPS + 1 ) ).exists() );
    }

    @Test
    void rotatingWithAGapInTheBackupSequenceOnlyShiftsExistingFiles( @TempDir File dir ) throws IOException
    {
        // .1 present, .2 missing (e.g. manually deleted) -- must not throw or fabricate .2's
        // shift target from nothing.
        File log = new File( dir, "launcher.log" );
        write( log, "current" );
        write( new File( dir, "launcher.log.1" ), "old-one" );

        Logger.rotateLogFiles( log );

        assertEquals( "current", read( new File( dir, "launcher.log.1" ) ) );
        assertEquals( "old-one", read( new File( dir, "launcher.log.2" ) ) );
        assertFalse( new File( dir, "launcher.log.3" ).exists() );
    }

    @Test
    void rotatingIsIdempotentlySafeWhenCalledOnAFreshLogWithNoBackupsTwice( @TempDir File dir ) throws IOException
    {
        File log = new File( dir, "launcher.log" );
        write( log, "first" );
        Logger.rotateLogFiles( log );

        // Simulate the log system creating a brand-new live log file post-rotation, then
        // rotating again -- must not throw even though the previous rotation already
        // populated .1.
        write( log, "second" );
        Logger.rotateLogFiles( log );

        assertEquals( "second", read( new File( dir, "launcher.log.1" ) ) );
        assertEquals( "first", read( new File( dir, "launcher.log.2" ) ) );
    }
}
