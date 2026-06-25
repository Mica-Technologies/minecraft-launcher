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
     * Display name of this mod, used in logs and UI rather than for download/verification.
     *
     * @since 1.0
     */
    final String name;

    /**
     * Flag indicating whether this mod is required when launching in client mode. When
     * {@code false}, {@link #updateLocalFile(GameMode)} skips downloading it for the client.
     *
     * @since 1.0
     */
    final boolean clientReq;

    /**
     * Flag indicating whether this mod is required when launching in server mode. When
     * {@code false}, {@link #updateLocalFile(GameMode)} skips downloading it for the server.
     *
     * @since 1.0
     */
    final boolean serverReq;

    /**
     * Create a Forge Mod object with using the specified remote URL, local file path and hash.
     *
     * @param modName      name of mod
     * @param modURL       URL of mod
     * @param modHash      hash of mod
     * @param modHashType  hash type of mod
     * @param modLocalFile file location of mod on disk
     * @param clientReq    flag if required on client
     * @param serverReq    flag if required on server
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    public GameMod( String modName,
                    String modURL,
                    String modHash,
                    ManagedGameFileHashType modHashType,
                    String modLocalFile,
                    boolean clientReq,
                    boolean serverReq )
    {
        super( modURL, modLocalFile, modHash, modHashType );

        // Store mod information
        this.name = modName;
        this.clientReq = clientReq;
        this.serverReq = serverReq;
    }

    /**
     * Update the local copy of this mod using the specified game mode (Client/Server). The
     * download only occurs when this mod is required for the given side; otherwise the call
     * is a no-op that reports success.
     *
     * @param gameAppMode client/server
     *
     * @return {@code true} if the local copy is up to date after the call (including the
     *         no-op case where the mod is not required for {@code gameAppMode}); otherwise the
     *         result of the underlying {@link ManagedGameFile#updateLocalFile()} call
     *
     * @throws ModpackException if update fails
     *
     * @since 1.0
     */
    boolean updateLocalFile( GameMode gameAppMode ) throws ModpackException
    {
        if ( ( gameAppMode == GameMode.CLIENT && clientReq ) || ( gameAppMode == GameMode.SERVER && serverReq ) ) {
            return super.updateLocalFile();
        }
        return true;
    }
}
