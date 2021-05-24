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

package com.micatechnologies.minecraft.launcher.game.auth;

import com.google.gson.annotations.SerializedName;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import org.apache.commons.lang3.SerializationUtils;

import java.io.*;

/**
 * Abstract class providing an interface for Minecraft authentication using either a Mojang or Microsoft account.
 *
 * @author Mica Technologies
 * @since 2021.2
 */
public abstract class MCLauncherAbstractAuthManager implements Serializable
{
    /**
     * The serialization unique identifier.
     */
    @Serial
    private static final long serialVersionUID = -5135279594434191565L;

    /**
     * The most recent access token received from the authentication system.
     */
    protected String lastAccessToken;

    /**
     * Method which returns the game access token, or a null value if the token can not be acquired.
     *
     * @return game access token or null
     */
    public abstract String getAccessTokenOrNull();

    /**
     * Reads the saved authentication manager information from the file system, or returns null if it is not present.
     *
     * @return saved authentication manager or null
     */
    public static MCLauncherAbstractAuthManager readAuthFromFileOrNull() {
        // Create saved account file object
        File userDiskFile = SynchronizedFileManager.getSynchronizedFile(
                LocalPathManager.getRememberedAccountFilePath() );

        MCLauncherAbstractAuthManager read = null;
        if ( !userDiskFile.isFile() ) {
            try {
                read = SerializationUtils.deserialize( new FileInputStream( userDiskFile ) );
            }
            catch ( FileNotFoundException e ) {
                Logger.logError( "An error occurred while reading saved authentication information!" );
                Logger.logThrowable( e );
                read = null;
            }
        }
        return read;
    }

    /**
     * Writes the specified authentication manager's information to the file system for persistence.
     *
     * @param authManager authentication manager to write to file
     *
     * @return true if saved, false is failed to save
     */
    public static boolean writeAuthToFile( MCLauncherAbstractAuthManager authManager ) {
        // Create saved account file object
        File userDiskFile = SynchronizedFileManager.getSynchronizedFile(
                LocalPathManager.getRememberedAccountFilePath() );

        boolean success = false;
        try {
            SerializationUtils.serialize( authManager, new FileOutputStream( userDiskFile ) );
            success = true;
        }
        catch ( FileNotFoundException e ) {
            Logger.logError( "An error occurred while writing saved authentication information!" );
            Logger.logThrowable( e );
        }

        return success;
    }
}
