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
 * Class representing a Minecraft Forge Library/Asset
 *
 * @version 1.0
 */
class GameAsset extends ManagedGameFile
{

    /**
     * Flag for client required
     */
    private final boolean clientReq;

    /**
     * Flag for server required
     */
    private final boolean serverReq;

    /**
     * Create an MCForgeAsset object using the specified remote URL and local file path.
     *
     * @param remote    remote file url
     * @param local     local file path
     * @param clientReq flag indicating whether this asset is required on the client side
     * @param serverReq flag indicating whether this asset is required on the server side
     *
     * @since 1.0
     */
    GameAsset( String remote, String local, boolean clientReq, boolean serverReq ) {
        super( remote, local );
        this.clientReq = clientReq;
        this.serverReq = serverReq;
    }

    /**
     * Create an MCForgeAsset object using the specified remote URL, local file path, and
     * integrity hash to verify the downloaded copy against.
     *
     * @param remote    remote file url
     * @param local     local file path
     * @param hash      expected hash of the asset, used to verify the downloaded copy
     * @param hashType  algorithm of {@code hash} (e.g. SHA-1)
     * @param clientReq flag indicating whether this asset is required on the client side
     * @param serverReq flag indicating whether this asset is required on the server side
     *
     * @since 1.0
     */
    GameAsset( String remote,
               String local,
               String hash,
               ManagedGameFileHashType hashType,
               boolean clientReq,
               boolean serverReq )
    {
        super( remote, local, hash, hashType );
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
    void updateLocalFile( GameMode gameAppMode ) throws ModpackException {
        if ( isRequiredFor( gameAppMode ) ) {
            super.updateLocalFile();
        }
    }

    /**
     * Indicates whether this asset is required for the given side (client/server).
     * Assets that aren't required for the active side are never downloaded by
     * {@link #updateLocalFile(GameMode)} and so must also be excluded from the
     * classpath rather than contributing a path to a file that was never fetched.
     *
     * @param gameAppMode client/server
     *
     * @return true if this asset applies to the given side
     */
    boolean isRequiredFor( GameMode gameAppMode ) {
        return ( gameAppMode == GameMode.CLIENT && clientReq ) || ( gameAppMode == GameMode.SERVER && serverReq );
    }
}
