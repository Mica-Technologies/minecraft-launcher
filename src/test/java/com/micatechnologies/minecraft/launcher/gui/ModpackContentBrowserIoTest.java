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


package com.micatechnologies.minecraft.launcher.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the two file helpers behind the modpack content browser: the crash-report reader
 * and the directory-size walk. Both used to fail in ways the UI couldn't recover from: a
 * cp1252 crash report threw instead of showing, and a symlink loop overflowed the stack.
 */
class ModpackContentBrowserIoTest
{
    @TempDir
    Path tempDir;

    @Test
    void readsAUtf8CrashReport() throws Exception
    {
        Path report = tempDir.resolve( "crash.txt" );
        Files.writeString( report, "Exception in Ölmühle — ✓", StandardCharsets.UTF_8 );
        assertEquals( "Exception in Ölmühle — ✓", ModpackContentBrowser.readReportText( report ) );
    }

    @Test
    void readsACp1252CrashReportInsteadOfFailing() throws Exception
    {
        Path report = tempDir.resolve( "crash.txt" );
        // "Ölmühle" in windows-1252: Ö = 0xD6, ü = 0xFC. Not valid UTF-8.
        Files.write( report, new byte[]{ (byte) 0xD6, 'l', 'm', (byte) 0xFC, 'h', 'l', 'e' } );
        assertEquals( "Ölmühle", ModpackContentBrowser.readReportText( report ) );
    }

    @Test
    void sumsRegularFilesThroughSubdirectories() throws Exception
    {
        Files.write( tempDir.resolve( "a.bin" ), new byte[ 100 ] );
        Path sub = Files.createDirectories( tempDir.resolve( "sub/deeper" ) );
        Files.write( sub.resolve( "b.bin" ), new byte[ 23 ] );
        assertEquals( 123L, ModpackContentBrowser.directorySize( tempDir.toFile() ) );
    }

    @Test
    void aSymlinkLoopDoesNotRecurseForever() throws Exception
    {
        Files.write( tempDir.resolve( "a.bin" ), new byte[ 10 ] );
        Path loop = tempDir.resolve( "loop" );
        try {
            Files.createSymbolicLink( loop, tempDir );
        }
        catch ( UnsupportedOperationException | java.io.IOException noSymlinks ) {
            // Windows without developer mode can't create links; nothing to test there.
            return;
        }
        assertTrue( Files.isSymbolicLink( loop ) );
        assertEquals( 10L, ModpackContentBrowser.directorySize( tempDir.toFile() ) );
    }

    @Test
    void aMissingDirectoryIsZero()
    {
        assertEquals( 0L, ModpackContentBrowser.directorySize( tempDir.resolve( "nope" ).toFile() ) );
        assertEquals( 0L, ModpackContentBrowser.directorySize( null ) );
    }
}
