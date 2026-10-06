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

package com.micatechnologies.minecraft.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link LauncherCore#moveAsideForReset}, the single rename Reset Launcher relies on to
 * either take the whole launcher folder out of the way or leave it untouched.
 */
class LauncherResetMoveAsideTest
{
    @Test
    void movesTheWholeFolderToAStampedSibling( @TempDir Path dir ) throws IOException
    {
        Path root = dir.resolve( ".mica-launcher" );
        Files.createDirectories( root.resolve( "config" ) );
        Files.writeString( root.resolve( "config" ).resolve( "configuration.json" ), "{}" );

        Path aside = LauncherCore.moveAsideForReset( root, 42L );

        assertEquals( dir.resolve( ".mica-launcher.reset-42" ), aside );
        assertFalse( Files.exists( root ) );
        assertTrue( Files.exists( aside.resolve( "config" ).resolve( "configuration.json" ) ) );
    }

    @Test
    void aMissingFolderHasNothingToMove( @TempDir Path dir ) throws IOException
    {
        assertNull( LauncherCore.moveAsideForReset( dir.resolve( "absent" ), 1L ) );
    }

    @Test
    void aFailedRenameLeavesTheFolderInPlace( @TempDir Path dir ) throws IOException
    {
        Path root = dir.resolve( "launcher" );
        Files.createDirectories( root );
        Files.writeString( root.resolve( "keep.txt" ), "x" );
        // The sibling name is already taken by a non-empty folder, so the rename must fail.
        Files.createDirectories( dir.resolve( "launcher.reset-7" ).resolve( "occupied" ) );

        assertThrows( IOException.class, () -> LauncherCore.moveAsideForReset( root, 7L ) );
        assertTrue( Files.exists( root.resolve( "keep.txt" ) ) );
    }
}
