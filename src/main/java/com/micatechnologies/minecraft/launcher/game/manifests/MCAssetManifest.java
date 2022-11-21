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

package com.micatechnologies.minecraft.launcher.game.manifests;

import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.game.manifests.loaders.MCAssetLoader;
import com.micatechnologies.minecraft.launcher.utilities.FileUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * The information manifest for a specific Minecraft version containing the asset download information for the game.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2022.1
 */
public class MCAssetManifest
{
    /**
     * The map of asset objects from their file name to their corresponding hash and file size.
     *
     * @since 1.0
     */
    private Map< String, Asset > objects;

    /**
     * Gets the map of asset objects from their file name to their corresponding hash and file size.
     *
     * @return map of asset objects from their file name to their corresponding hash and file size.
     *
     * @since 1.0
     */
    public Map< String, Asset > getObjects() {
        return objects;
    }

    /**
     * Object containing an asset's hash information and file size.
     *
     * @author Mica Technologies
     * @version 1.0
     * @since 1.0
     */
    public static class Asset
    {
        /**
         * The hash of the asset.
         *
         * @since 1.0
         */
        private String hash;

        /**
         * The file size of the asset.
         *
         * @since 1.0
         */
        private int size;

        /**
         * Gets the hash of the asset.
         *
         * @return hash of the asset
         *
         * @since 1.0
         */
        public String getHash() {
            return hash;
        }

        /**
         * Gets the file size of the asset.
         *
         * @return file size of the asset
         *
         * @since 1.0
         */
        public int getSize() {
            return size;
        }
    }

    /**
     * The URL prefix for download URLs of asset indexes without a specified URL in the asset index information.
     *
     * @since 1.0
     */
    private static final String ASSET_INDEX_URL_PREFIX = "https://launchermeta.mojang.com/v1/packages/";

    /**
     * Downloads the {@link MCAssetManifest} for the specified {@link MCVersionManifest} using either the specified
     * asset index URL, or alternatively a launcher meta URL based on the asset index ID and SHA-1 hash if the URL is
     * not present.
     *
     * @param MCVersionManifest the {@link MCVersionManifest} to download the {@link MCAssetManifest} for.
     * @param installFolder     the folder where the instance of Minecraft specified in the {@link MCAssetManifest} is
     *                          to be extracted.
     *
     * @return {@link MCAssetManifest} for the specified {@link MCVersionManifest}
     *
     * @throws IOException if unable to download the {@link MCAssetManifest} for the specified {@link
     *                     MCVersionManifest}
     * @since 1.0
     */
    public static MCAssetManifest download( MCVersionManifest MCVersionManifest, Path installFolder ) throws IOException
    {
        // Get asset index information
        MCVersionManifest.AssetIndex assetIndex = MCVersionManifest.getAssetIndex();

        // Get URL of asset index
        String assetIndexUrl = assetIndex.getUrl();
        String assetIndexUrlSecondary = ASSET_INDEX_URL_PREFIX +
                assetIndex.getSha1() +
                "/" +
                assetIndex.getId() +
                ".json";
        if ( assetIndexUrl == null ) {
            assetIndexUrl = assetIndexUrlSecondary;
        }

        // Download asset manifest file
        Path assetManifestFilePath = installFolder.resolve( MCAssetLoader.MINECRAFT_ASSET_INDEXES_DOWNLOAD_FOLDER )
                                                  .resolve( assetIndex.getId() + ".json" );
        File assetManifestFile = SynchronizedFileManager.getSynchronizedFile( assetManifestFilePath );
        NetworkUtilities.downloadFileFromURL( assetIndexUrl, assetManifestFile );
        return FileUtilities.readAsJsonObject( assetManifestFile, MCAssetManifest.class );
    }
}
