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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tile positions decide which corners of a Settings sidebar tile are rounded.
 *
 * @since 2026.10
 */
class SettingsNavTileTest
{
    @Test
    void aLoneTileIsRoundedAllRound()
    {
        assertEquals( "navTileOnly", SettingsNavTile.positionClass( 0, 1 ) );
    }

    @Test
    void groupTilesAreFirstMiddleLast()
    {
        assertEquals( "navTileFirst", SettingsNavTile.positionClass( 0, 3 ) );
        assertEquals( "navTileMiddle", SettingsNavTile.positionClass( 1, 3 ) );
        assertEquals( "navTileLast", SettingsNavTile.positionClass( 2, 3 ) );
        assertEquals( "navTileLast", SettingsNavTile.positionClass( 1, 2 ) );
    }
}
