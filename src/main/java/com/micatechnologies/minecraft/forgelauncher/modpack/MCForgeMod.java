package com.micatechnologies.minecraft.forgelauncher.modpack;

import com.micatechnologies.minecraft.forgelauncher.exceptions.FLModpackException;
import com.micatechnologies.minecraft.forgelauncher.game.GameMode;

/**
 * A class representation of a Minecraft Forge mod that can be downloaded locally and verified using
 * the specified hash.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class MCForgeMod extends MCRemoteFile {

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
     * Create a Forge Mod object with using the specified remote URL, local file path and SHA-1 *
     * hash.
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
    public MCForgeMod( String modName, String modURL, String modSHA1, String modLocalFile,
                       boolean clientReq, boolean serverReq ) {
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
     * @throws FLModpackException if update fails
     */
    void updateLocalFile( GameMode gameAppMode ) throws FLModpackException {
        if ( ( gameAppMode == GameMode.CLIENT && clientReq ) || (
                gameAppMode == GameMode.SERVER && serverReq ) ) {
            super.updateLocalFile();
        }
    }
}
