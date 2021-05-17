/*
 * Copyright (c) 2021 Mica Technologies
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

import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;

/**
 * A class representation of a Minecraft Forge mod that can be downloaded locally and verified using the specified
 * hash.
 *
 * @version 1.0
 */
public class GameMod extends ManagedGameFile
{

    /**
     * Name of this mod
     */
    final String name;

    /**
     * Boolean if mod required on client
     */
    final boolean clientReq;

    /**
     * Boolean if mod required on server
     */
    final boolean serverReq;

    /**
     * Create a Forge Mod object with using the specified remote URL, local file path and SHA-1 * hash.
     *
     * @param modName      name of mod
     * @param modURL       URL of mod
     * @param modSHA1      SHA-1 hash of mod
     * @param modLocalFile file location of mod on disk
     * @param clientReq    flag if required on client
     * @param serverReq    flag if required on server
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    public GameMod( String modName,
                    String modURL,
                    String modSHA1,
                    String modLocalFile,
                    boolean clientReq,
                    boolean serverReq )
    {
        super( modURL, modLocalFile, modSHA1 );

        // Store mod information
        this.name = modName;
        this.clientReq = clientReq;
        this.serverReq = serverReq;
    }

    /**
     * Update the local copy of this MCForgeAsset using the specified game mode (Client/Server).
     *
     * @param gameAppMode client/server
     *
     * @throws ModpackException if update fails
     */
    void updateLocalFile( GameMode gameAppMode ) throws ModpackException
    {
        if ( ( gameAppMode == GameMode.CLIENT && clientReq ) || ( gameAppMode == GameMode.SERVER && serverReq ) ) {
            super.updateLocalFile();
        }
    }
}
