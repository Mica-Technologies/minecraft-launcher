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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How optional mods show in the pending-update summary: changes to ones that are on read like any
 * mod change with an optional tag, a new one that is off reads as "available", changes to ones
 * that are off are hidden, and an optional jar never reads as removed.
 */
class PendingUpdateDiffOptionalTest
{
    /** A hash no real file has, so an installed copy always reads as changed. */
    private static final String WRONG_SHA256 = "0".repeat( 64 );

    @TempDir
    Path modsDir;

    private File[] install( String... names ) throws Exception
    {
        for ( String name : names ) {
            Files.writeString( modsDir.resolve( name ), "jar bytes of " + name );
        }
        return modsDir.toFile().listFiles();
    }

    private static GameMod required( String local )
    {
        return new GameMod( local, "https://example.invalid/" + local, null,
                            ManagedGameFile.ManagedGameFileHashType.SHA1, local, true, true );
    }

    private static GameOptionalMod optional( String name, String local, String sha256 )
    {
        return new GameOptionalMod( name, "https://example.invalid/" + local, local, sha256, true, false, "", false,
                                    null, null );
    }

    @Test
    void changesToEnabledOptionalModsAreListedAndTagged() throws Exception
    {
        File[] onDisk = install( "jei.jar", "ausm.jar" );
        List< GameOptionalMod > opt = List.of( optional( "AUSM", "ausm.jar", WRONG_SHA256 ),
                                               optional( "Lib", "lib.jar", null ) );
        PendingUpdateDiff.Result result = PendingUpdateDiff.compute( List.of( required( "jei.jar" ) ), opt,
                                                                     Set.of( "ausm.jar", "lib.jar" ),
                                                                     Set.of( "ausm.jar", "lib.jar" ), onDisk );
        assertEquals( List.of( "ausm" ), result.updatedNames() );
        assertEquals( List.of( "lib" ), result.addedNames() );
        assertEquals( Set.of( "ausm", "lib" ), result.optionalNames() );
        assertTrue( result.removedNames().isEmpty() );
        assertTrue( result.newOptionalNames().isEmpty() );
    }

    @Test
    void aNewOptionalModThatIsOffIsAvailableNotAdded() throws Exception
    {
        File[] onDisk = install( "jei.jar" );
        List< GameOptionalMod > opt = List.of( optional( "AUSM", "ausm.jar", WRONG_SHA256 ),
                                               optional( "Old", "old.jar", WRONG_SHA256 ) );
        PendingUpdateDiff.Result result = PendingUpdateDiff.compute( List.of( required( "jei.jar" ) ), opt, Set.of(),
                                                                     Set.of( "old.jar" ), onDisk );
        assertEquals( List.of( "AUSM" ), result.newOptionalNames(),
                      "only the one the last sync did not know about is new" );
        assertTrue( result.addedNames().isEmpty() );
        assertTrue( result.updatedNames().isEmpty(), "changes to optional mods that are off are hidden" );
        assertFalse( result.isEmpty() );
    }

    @Test
    void anOptionalJarOnDiskIsNeverReportedRemoved() throws Exception
    {
        // Switched off since the last sync: the jar is still there and the sweep will remove it,
        // but that was the player's choice, not the update's.
        File[] onDisk = install( "jei.jar", "ausm.jar", "handmade.jar" );
        List< GameOptionalMod > opt = List.of( optional( "AUSM", "ausm.jar", null ) );
        PendingUpdateDiff.Result result = PendingUpdateDiff.compute( List.of( required( "jei.jar" ) ), opt, Set.of(),
                                                                     Set.of( "ausm.jar" ), onDisk );
        assertEquals( List.of( "handmade" ), result.removedNames() );
        assertTrue( result.newOptionalNames().isEmpty() );
    }

    @Test
    void withoutARecordEveryOptionalModThatIsOffReadsAsNew() throws Exception
    {
        File[] onDisk = install( "jei.jar" );
        List< GameOptionalMod > opt = List.of( optional( "AUSM", "ausm.jar", null ) );
        PendingUpdateDiff.Result result = PendingUpdateDiff.compute( List.of( required( "jei.jar" ) ), opt, Set.of(),
                                                                     Set.of(), onDisk );
        assertEquals( List.of( "AUSM" ), result.newOptionalNames() );
    }

    @Test
    void aPackWithoutOptionalModsDiffsAsBefore() throws Exception
    {
        File[] onDisk = install( "jei.jar", "gone.jar" );
        PendingUpdateDiff.Result result = PendingUpdateDiff.compute( List.of( required( "jei.jar" ),
                                                                              required( "new.jar" ) ),
                                                                     List.of(), Set.of(), Set.of(), onDisk );
        assertEquals( List.of( "new" ), result.addedNames() );
        assertEquals( List.of( "gone" ), result.removedNames() );
        assertTrue( result.optionalNames().isEmpty() );
    }
}
