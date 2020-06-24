/*
 * Copyright (c) 2020 Mica Technologies
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

package com.micatechnologies.minecraft.forgelauncher.utilities.objects;

import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.ClientAndServer;

/**
 * Enum used to identify the supported game modes of the launcher, client and server.
 *
 * @author Mica Technologies
 * @editors hawka97
 * @creator hawka97
 * @version 1.0
 * @since 2.0
 */
@ClientAndServer
public enum GameMode
{
    /**
     * Client game mode enum
     *
     * @since 1.0
     */
    CLIENT( "CLIENT" ),

    /**
     * Server game mode enum
     *
     * @since 1.0
     */
    SERVER( "SERVER" );

    /**
     * Stored string name value of the game mode. Value is returned by {@link #getStringName()}.
     *
     * @since 1.0
     */
    private final String gameModeString;

    /**
     * Constructor for a game mode Enum object with specified game mode string value
     *
     * @param gameModeString game mode string value
     *
     * @since 1.0
     */
    @ClientAndServer
    GameMode( String gameModeString ) {
        this.gameModeString = gameModeString;
    }

    /**
     * Gets the game mode string name value
     *
     * @return game mode string name
     *
     * @since 1.0
     */
    @ClientAndServer
    public String getStringName() {
        return gameModeString;
    }
}
