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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SteamDeckTest
{
    @Test
    void bothDeckModelsAreRecognised()
    {
        assertTrue( SteamDeck.matches( "Valve\n", "Jupiter\n" ), "LCD; sysfs values end in a newline" );
        assertTrue( SteamDeck.matches( "Valve", "Galileo" ), "OLED" );
    }

    @Test
    void otherMachinesAreNot()
    {
        assertFalse( SteamDeck.matches( "Valve", "Some Future Device" ) );
        assertFalse( SteamDeck.matches( "Other Vendor", "Jupiter" ) );
        assertFalse( SteamDeck.matches( null, "Jupiter" ) );
        assertFalse( SteamDeck.matches( "Valve", null ) );
    }

    @Test
    void detectionNeverFailsOffLinux()
    {
        // This test machine is not a Deck; reading the missing sysfs files must just say so.
        assertFalse( SteamDeck.isSteamDeck() );
    }
}
