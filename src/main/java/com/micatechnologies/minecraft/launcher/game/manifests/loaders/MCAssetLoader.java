/*
 * Copyright (c) 2022 Mica Technologies
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

package com.micatechnologies.minecraft.launcher.game.manifests.loaders;

import com.micatechnologies.minecraft.launcher.files.ManagedRemoteFile;
import com.micatechnologies.minecraft.launcher.files.hash.FileChecksum;
import com.micatechnologies.minecraft.launcher.files.hash.FileChecksumSHA1;
import com.micatechnologies.minecraft.launcher.game.manifests.MCAssetManifest;

import java.io.File;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Utility for loading a list of valid assets for the specified {@link MCAssetManifest} in the desired installation
 * folder location.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2022.1
 */
public class MCAssetLoader
{
    /**
     * The folder within a Minecraft installation folder where downloaded or cached asset objects are stored.
     *
     * @since 1.0
     */
    public static final Path MINECRAFT_ASSET_OBJECTS_DOWNLOAD_FOLDER = Path.of( "assets", "objects" );

    /**
     * The folder within a Minecraft installation folder where downloaded or cached asset indexes are stored.
     *
     * @since 1.0
     */
    public static final Path MINECRAFT_ASSET_INDEXES_DOWNLOAD_FOLDER = Path.of( "assets", "indexes" );

    /**
     * The remote URL for downloading Minecraft assets by their hash.
     *
     * @since 1.0
     */
    private static final String MINECRAFT_ASSET_DOWNLOAD_URL_PREFIX = "https://resources.download.minecraft.net/";

    /**
     * Builds a list of valid (applicable) assets for the specified {@link MCAssetManifest} in the desired installation
     * folder location.
     *
     * @param installFolder installation folder location
     * @param assetManifest {@link MCAssetManifest} to get valid assets for
     *
     * @return list of valid (applicable) assets for the specified {@link MCAssetManifest} in the desired * installation
     *         folder location
     *
     * @throws MalformedURLException if an asset in the asset manifest does not have a properly formed URL and/or its
     *                               URL is missing and could not be interpreted
     * @since 1.0
     */
    public List< ManagedRemoteFile > getValidAssetList( String installFolder, MCAssetManifest assetManifest )
    throws MalformedURLException
    {
        // Get assets from asset manifest
        Map< String, MCAssetManifest.Asset > assetManifestObjects = assetManifest.getObjects();

        // Loop through each asset and add managed file object to list
        List< ManagedRemoteFile > validAssetList = new ArrayList<>();
        for ( Map.Entry< String, MCAssetManifest.Asset > assetEntry : assetManifestObjects.entrySet() ) {
            MCAssetManifest.Asset assetObject = assetEntry.getValue();

            // Build new managed file object
            String validAssetRemoteUrl = MINECRAFT_ASSET_DOWNLOAD_URL_PREFIX +
                    assetObject.getHash().substring( 0, 2 ) +
                    "/" +
                    assetObject.getHash();
            String validAssetFilePath = installFolder +
                    File.separator +
                    MINECRAFT_ASSET_OBJECTS_DOWNLOAD_FOLDER +
                    File.separator +
                    assetObject.getHash().substring( 0, 2 ) +
                    File.separator +
                    assetObject.getHash();
            FileChecksum validAssetFileHash = new FileChecksumSHA1( assetObject.getHash() );
            ManagedRemoteFile validAsset = new ManagedRemoteFile( validAssetFilePath, validAssetRemoteUrl,
                                                                  validAssetFileHash );

            // Add built object to list
            validAssetList.add( validAsset );
        }

        return validAssetList;
    }
}
