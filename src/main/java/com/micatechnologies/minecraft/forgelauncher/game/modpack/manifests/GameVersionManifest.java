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

package com.micatechnologies.minecraft.forgelauncher.game.modpack.manifests;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.forgelauncher.consts.ModPackConstants;
import com.micatechnologies.minecraft.forgelauncher.exceptions.ModpackException;
import com.micatechnologies.minecraft.forgelauncher.files.LocalPathManager;
import com.micatechnologies.minecraft.forgelauncher.files.Logger;
import com.micatechnologies.minecraft.forgelauncher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.forgelauncher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.forgelauncher.game.modpack.ManagedGameFile;
import com.micatechnologies.minecraft.forgelauncher.utilities.FileUtilities;
import com.micatechnologies.minecraft.forgelauncher.utilities.NetworkUtilities;

import java.io.File;
import java.io.IOException;

/**
 * Class representing the Mojang Minecraft version manifest and providing functionality to download a Minecraft
 * version's library manifest.
 *
 * @author Mica Technologies
 * @version 2.0
 * @creator hawka97
 * @editors hawka97
 */
public class GameVersionManifest
{
    /**
     * The JSON object containing the contents of the Minecraft version manifest after being downloaded in {@link
     * #download()}.
     *
     * @since 2.0
     */
    private static JsonObject versionManifest = null;

    /**
     * Downloads the contents of the Minecraft version manifest and stores in {@link #versionManifest}.
     *
     * @throws IOException if unable to download to file or read file
     * @since 2.0
     */
    private static void download() throws IOException {
        File versionManifestFile =
                SynchronizedFileManager.getSynchronizedFile( LocalPathManager.getMinecraftVersionManifestFilePath() );
        NetworkUtilities.downloadFileFromURL( ModPackConstants.MINECRAFT_VERSION_MANIFEST_URL, versionManifestFile );
        versionManifest = FileUtilities.readAsJson( versionManifestFile );
    }

    /**
     * Get the URL of the Minecraft library manifest for the specified Minecraft version.
     *
     * @param minecraftVersion minecraft version
     *
     * @return URL of Minecraft version's library manifest
     *
     * @throws ModpackException if unable to get URL
     * @since 1.0
     */
    private static String getMinecraftLibaryManifestURL( String minecraftVersion )
    throws ModpackException
    {
        // Download manifest if not downloaded
        if ( versionManifest == null ) {
            try {
                Logger.logDebug( "Minecraft library manifest has not been downloaded. Getting now..." );
                download();
                Logger.logDebug( "Minecraft library manifest has been downloaded!" );
            }
            catch ( IOException e ) {
                Logger.logError( "Failed to download and read Minecraft version manifest!" );
                Logger.logThrowable( e );
                return null;
            }
        }

        // Get versions from version manifest root object
        JsonArray minecraftVersions = versionManifest.getAsJsonArray( "versions" );

        // Loop through all versions in array
        for ( JsonElement version : minecraftVersions ) {
            // Check if version matches
            if ( version.getAsJsonObject().get( "id" ).getAsString().equals( minecraftVersion ) ) {
                return version.getAsJsonObject().get( "url" ).getAsString();
            }
        }

        // Throw exception if not found
        throw new ModpackException(
                "Unable to find specified Minecraft version library manifest." );
    }

    /**
     * Get the Minecraft library manifest for the specified Minecraft version.
     *
     * @param minecraftVersion Minecraft version
     * @param parent           parent mod pack
     *
     * @return Minecraft version's library manifest
     *
     * @throws ModpackException if unable to get library manifest
     * @since 1.0
     */
    public static GameLibraryManifest getMinecraftLibraryManifest( String minecraftVersion, GameModPack parent )
    throws ModpackException
    {
        return new GameLibraryManifest( getMinecraftLibaryManifestURL( minecraftVersion ),
                                        parent );
    }
}
