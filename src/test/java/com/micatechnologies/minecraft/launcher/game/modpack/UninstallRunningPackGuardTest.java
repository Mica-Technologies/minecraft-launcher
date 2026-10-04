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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order of {@link GameModPackManager#uninstallGuarded}: a launching or running pack is
 * refused before its folder is touched. The folder used to be deleted by the caller first, so
 * uninstalling a running pack wiped its saves and was then refused.
 */
class UninstallRunningPackGuardTest
{
    private final List< String > steps = new ArrayList<>();

    private boolean uninstall( boolean active, boolean deleteFiles )
    {
        GameModPack pack = new GameModPack();
        pack.packName = "Alto";
        return GameModPackManager.uninstallGuarded( pack, deleteFiles, p -> active,
                                                    p -> steps.add( "delete" ),
                                                    p -> steps.add( "unregister" ) );
    }

    @Test
    void runningPackIsRefusedBeforeAnythingIsDeleted()
    {
        assertFalse( uninstall( true, true ) );
        assertTrue( steps.isEmpty(), "a refused uninstall must not delete or unregister anything" );
    }

    @Test
    void idlePackWithDeleteFilesDeletesThenUnregisters()
    {
        assertTrue( uninstall( false, true ) );
        assertEquals( List.of( "delete", "unregister" ), steps );
    }

    @Test
    void idlePackKeepingFilesOnlyUnregisters()
    {
        assertTrue( uninstall( false, false ) );
        assertEquals( List.of( "unregister" ), steps );
    }
}
