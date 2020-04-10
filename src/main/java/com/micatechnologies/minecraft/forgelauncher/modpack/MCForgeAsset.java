package com.micatechnologies.minecraft.forgelauncher.modpack;

import com.micatechnologies.minecraft.forgelauncher.exceptions.FLModpackException;

/**
 * Class representing a Minecraft Forge Library/Asset
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
class MCForgeAsset extends MCRemoteFile {

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
     * @param remote remote file url
     * @param local  local file path
     *
     * @since 1.0
     */
    MCForgeAsset( String remote, String local, boolean clientReq, boolean serverReq ) {
        super( remote, local );
        this.clientReq = clientReq;
        this.serverReq = serverReq;
    }

    /**
     * Update the local copy of this MCForgeAsset using the specified game mode (Client/Server).
     *
     * @param gameAppMode client/server
     *
     * @throws FLModpackException if update fails
     */
    void updateLocalFile( int gameAppMode ) throws FLModpackException {
        if ( ( gameAppMode == MCForgeModpackConsts.MINECRAFT_CLIENT_MODE && clientReq ) || (
                gameAppMode == MCForgeModpackConsts.MINECRAFT_SERVER_MODE && serverReq ) ) {
            super.updateLocalFile();
        }
    }
}
