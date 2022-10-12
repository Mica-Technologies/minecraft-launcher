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

package com.micatechnologies.minecraft.launcher.game.modpack.manifests;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;
import com.micatechnologies.minecraft.launcher.consts.ManifestConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackProgressProvider;
import com.micatechnologies.minecraft.launcher.game.modpack.ManagedGameFile;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Class object representing a Minecraft game asset manifest. This class allows for the discovery and download of
 * applicable libraries for the Minecraft game.
 *
 * @author Mica Technologies
 * @version 1.1
 * @since 1.0
 */
public class GameAssetManifest extends ManagedGameFile
{
    /**
     * The mod pack to which this {@link GameAssetManifest} belongs to.
     *
     * @since 1.1
     */
    private final GameModPack parentModPack;

    /**
     * Constructor that creates a {@link GameAssetManifest} object with the supplied remote download URL, parent mod
     * pack reference and version number string.
     *
     * @param remote        asset manifest URL
     * @param parentModPack mod pack to which manifest belongs
     * @param version       asset manifest version
     *
     * @since 1.0
     */
    public GameAssetManifest( String remote, GameModPack parentModPack, String version ) {
        super( remote, SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                      LocalPathConstants.MINECRAFT_ASSET_RELATIVE_INDEXES_FOLDER,
                                                      version + ManifestConstants.JSON_FILE_EXTENSION ) );
        this.parentModPack = parentModPack;
    }

    /**
     * Gets a list of managed game file objects representing each asset in the manifest.
     *
     * @return list of asset files
     *
     * @throws ModpackException if unable to read manifest
     * @since 1.0
     */
    private ArrayList< ManagedGameFile > getAssets() throws ModpackException {
        // Create list for assets
        ArrayList< ManagedGameFile > assets = new ArrayList<>();

        // Get objects list from assets manifest
        JsonObject objects = readToJsonObject().get( ManifestConstants.MINECRAFT_ASSET_MANIFEST_OBJECTS_KEY )
                                               .getAsJsonObject();

        // Add each asset to list
        for ( String assetName : objects.keySet() ) {
            JsonObject asset = objects.getAsJsonObject( assetName );

            // Get hash of asset (file name)
            String assetHash = asset.get( ManifestConstants.MINECRAFT_ASSET_MANIFEST_OBJECT_HASH_KEY ).getAsString();

            // Get first two letters of hash (folder name)
            String assetFolder = assetHash.substring( 0, 2 );

            // Build full asset path
            String assetPath = SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                              LocalPathConstants.MINECRAFT_ASSET_RELATIVE_OBJECTS_FOLDER,
                                                              assetFolder, assetHash );

            // Build full asset URL
            String assetURL = ManifestConstants.MINECRAFT_ASSET_SERVER_URL_TEMPLATE.replaceAll(
                                                       ManifestConstants.MINECRAFT_ASSET_SERVER_URL_FOLDER_KEY, assetFolder )
                                                                                   .replaceAll(
                                                                                           ManifestConstants.MINECRAFT_ASSET_SERVER_URL_HASH_KEY,
                                                                                           assetHash );
            assets.add( new ManagedGameFile( assetURL, assetPath, assetHash, ManagedGameFileHashType.SHA1 ) );
        }

        // Return list
        return assets;
    }

    /**
     * Verifies and downloads the assets in the manifest to their respective locations.
     *
     * @param progressProvider progress manager
     *
     * @throws ModpackException if unable to read manifest or update asset
     * @since 1.1
     */
    public void downloadAssets( final GameModPackProgressProvider progressProvider )
    throws ModpackException, InterruptedException, ExecutionException
    {
        // Update asset manifest first
        updateLocalFile();

        // Update each asset
        List< ManagedGameFile > assets = getAssets();

        // Build list of asset download threads
        ExecutorService threadPool = Executors.newFixedThreadPool( assets.size() );
        List< Future< Boolean > > threadPoolFutures = new ArrayList<>();
        for ( ManagedGameFile asset : assets ) {
            Callable< Boolean > updateFileCallable = () -> {
                boolean ret = asset.updateLocalFile();

                // Update progress provider if present
                if ( progressProvider != null ) {
                    progressProvider.submitProgress(
                            LocalizationManager.VERIFIED_ASSET_PROGRESS_TEXT + " " + asset.getFileName(),
                            ( 50.0 / ( double ) assets.size() ) );
                }
                return ret;
            };
            Future< Boolean > future = threadPool.submit( updateFileCallable );
            threadPoolFutures.add( future );
        }
        threadPool.shutdown();
        threadPool.awaitTermination( Long.MAX_VALUE, TimeUnit.MILLISECONDS );

        // Parse list of futures
        for (Future< Boolean > threadPoolFuture : threadPoolFutures ) {
            threadPoolFuture.get();
        }
    }
}
