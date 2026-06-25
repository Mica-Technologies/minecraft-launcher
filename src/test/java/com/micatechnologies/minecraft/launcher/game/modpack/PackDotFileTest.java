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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Behavioral tests for the {@link PackDotFile} per-pack sidecar helper — locks
 * the read / readLines / write / modify contract that the launch-history and
 * seen-news sidecars depend on.
 *
 * @since 2026.6
 */
class PackDotFileTest
{
    private static final String NAME = ".test_sidecar";

    @Test
    void read_returnsNull_whenAbsent( @TempDir Path dir )
    {
        assertNull( PackDotFile.read( dir.toString(), NAME ) );
    }

    @Test
    void readLines_returnsEmpty_whenAbsent( @TempDir Path dir )
    {
        assertTrue( PackDotFile.readLines( dir.toString(), NAME ).isEmpty() );
    }

    @Test
    void write_then_read_roundTrips( @TempDir Path dir ) throws IOException
    {
        PackDotFile.write( dir.toString(), NAME, "hello\nworld\n" );
        assertEquals( "hello\nworld\n", PackDotFile.read( dir.toString(), NAME ) );
        assertEquals( List.of( "hello", "world" ), PackDotFile.readLines( dir.toString(), NAME ) );
    }

    @Test
    void write_createsMissingPackRoot( @TempDir Path dir ) throws IOException
    {
        // Pack root doesn't exist yet — write must mkdirs it (the bug that
        // recordSessionEnd previously had).
        Path nested = dir.resolve( "freshpack" );
        PackDotFile.write( nested.toString(), NAME, "x" );
        assertEquals( "x", PackDotFile.read( nested.toString(), NAME ) );
    }

    @Test
    void modify_appliesTransformToExistingContent( @TempDir Path dir ) throws IOException
    {
        PackDotFile.write( dir.toString(), NAME, "1\n2\n3\n" );
        PackDotFile.modify( dir.toString(), NAME, existing -> existing.trim() + "\n4\n" );
        assertEquals( "1\n2\n3\n4\n", PackDotFile.read( dir.toString(), NAME ) );
    }

    @Test
    void modify_receivesNull_whenAbsent( @TempDir Path dir ) throws IOException
    {
        PackDotFile.modify( dir.toString(), NAME,
                existing -> existing == null ? "created\n" : "should-not-happen" );
        assertEquals( "created\n", PackDotFile.read( dir.toString(), NAME ) );
    }
}
