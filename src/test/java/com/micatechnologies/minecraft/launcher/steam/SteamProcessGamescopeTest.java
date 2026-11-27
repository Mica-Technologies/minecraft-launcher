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
package com.micatechnologies.minecraft.launcher.steam;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SteamProcessGamescopeTest
{
    @Test
    void gameModeIsGamescope()
    {
        assertTrue( SteamProcess.underGamescope( Map.of( "XDG_CURRENT_DESKTOP", "gamescope" ) ) );
    }

    @Test
    void aGamescopeDisplayIsGamescopeEvenOutsideGameMode()
    {
        Map< String, String > env = Map.of( "XDG_CURRENT_DESKTOP", "KDE", "GAMESCOPE_WAYLAND_DISPLAY", "gamescope-0" );
        assertTrue( SteamProcess.underGamescope( env ) );
        assertFalse( SteamProcess.inGameMode( env ), "Steam can still be closed there" );
    }

    @Test
    void aDesktopIsNot()
    {
        assertFalse( SteamProcess.underGamescope( Map.of( "XDG_CURRENT_DESKTOP", "KDE" ) ) );
        assertFalse( SteamProcess.underGamescope( Map.of( "GAMESCOPE_WAYLAND_DISPLAY", " " ) ) );
        assertFalse( SteamProcess.underGamescope( Map.of() ) );
    }
}
