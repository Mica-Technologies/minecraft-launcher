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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StarterHandshakeTest
{
    @Test
    void writesTheStageWordTheStarterReads( @TempDir Path dir ) throws IOException
    {
        Path state = Files.writeString( dir.resolve( "x.state" ), "launching" );

        assertTrue( StarterHandshake.write( state.toString(), StarterHandshake.Stage.MAIN ) );
        assertEquals( "main", Files.readString( state ) );
        assertTrue( StarterHandshake.write( state.toString(), StarterHandshake.Stage.READY ) );
        assertEquals( "ready", Files.readString( state ) );
        assertTrue( StarterHandshake.write( state.toString(), StarterHandshake.Stage.HANDLED ) );
        assertEquals( "handled", Files.readString( state ) );
    }

    @Test
    void neverCreatesAFileTheStarterDidNot( @TempDir Path dir )
    {
        Path state = dir.resolve( "missing.state" );

        assertFalse( StarterHandshake.write( state.toString(), StarterHandshake.Stage.READY ) );
        assertFalse( Files.exists( state ) );
    }

    @Test
    void withoutTheVariableNothingIsWritten()
    {
        assertFalse( StarterHandshake.write( null, StarterHandshake.Stage.MAIN ) );
        assertFalse( StarterHandshake.write( " ", StarterHandshake.Stage.MAIN ) );
    }

    @Test
    void theStarterIsPreferredWhenItExists( @TempDir Path dir ) throws IOException
    {
        Path starter = Files.createFile( dir.resolve( "Mica Minecraft Launcher.exe" ) );
        String core = dir.resolve( "Mica Minecraft Launcher Core.exe" ).toString();

        assertEquals( starter.toString(), StarterHandshake.launcherExecutable( starter.toString(), core ) );
    }

    @Test
    void aMissingStarterFallsBackToTheJpackageLauncher( @TempDir Path dir )
    {
        String core = dir.resolve( "Mica Minecraft Launcher Core.exe" ).toString();

        assertEquals( core, StarterHandshake.launcherExecutable( dir.resolve( "gone.exe" ).toString(), core ) );
        assertEquals( core, StarterHandshake.launcherExecutable( null, core ) );
    }

    @Test
    void noPackagedLauncherMeansNoExecutable()
    {
        assertNull( StarterHandshake.launcherExecutable( null, null ) );
        assertNull( StarterHandshake.launcherExecutable( "", " " ) );
    }
}
