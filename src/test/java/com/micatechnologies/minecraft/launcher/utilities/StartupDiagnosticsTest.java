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

import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the startup log that records launches ending before the main log exists.
 *
 * @since 2026.10
 */
class StartupDiagnosticsTest
{
    @TempDir
    Path dir;

    @Test
    void appendsTimestampedLinesWithTheProcessId() throws Exception
    {
        Path log = dir.resolve( "logs" ).resolve( "startup.log" );
        StartupDiagnostics.append( log, "first" );
        StartupDiagnostics.append( log, "second" );

        var lines = Files.readAllLines( log, StandardCharsets.UTF_8 );
        assertEquals( 2, lines.size() );
        assertTrue( lines.get( 0 ).endsWith( "[pid " + ProcessHandle.current().pid() + "] first" ) );
        assertTrue( lines.get( 1 ).endsWith( "] second" ) );
    }

    @Test
    void rollsOverOnceTheLogPassesItsLimit() throws Exception
    {
        Path log = dir.resolve( "startup.log" );
        Files.writeString( log, "x".repeat( (int) StartupDiagnostics.MAX_BYTES + 1 ) );

        StartupDiagnostics.append( log, "after rollover" );

        assertTrue( Files.size( dir.resolve( "startup.log.1" ) ) > StartupDiagnostics.MAX_BYTES );
        var lines = Files.readAllLines( log, StandardCharsets.UTF_8 );
        assertEquals( 1, lines.size() );
        assertTrue( lines.get( 0 ).endsWith( "after rollover" ) );
    }

    @Test
    void neverThrowsWhenTheLogCannotBeWritten() throws Exception
    {
        // A regular file where the logs folder should be makes the directory impossible to create.
        Path blocker = dir.resolve( "logs" );
        Files.writeString( blocker, "not a folder" );

        assertDoesNotThrow( () -> StartupDiagnostics.append( blocker.resolve( "startup.log" ), "lost" ) );
        assertFalse( Files.isDirectory( blocker ) );
    }

    @Test
    void theLogSitsInTheClientFolderWhateverTheGameMode()
    {
        // Resolved before the game mode is known, so it must not depend on it (a mode-dependent
        // path would land in the working directory).
        assertEquals( Path.of( LocalPathConstants.CLIENT_MODE_LAUNCHER_FOLDER_PATH, "logs", "startup.log" ),
                      StartupDiagnostics.logPath() );
    }
}
