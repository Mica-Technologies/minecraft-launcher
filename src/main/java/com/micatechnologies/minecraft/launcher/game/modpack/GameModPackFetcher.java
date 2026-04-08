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

import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Class for fetching mod pack objects from their manifest URL. Supports offline mode by caching manifests locally and
 * falling back to the cache when the network is unavailable.
 *
 * @author Mica Technologies
 * @version 2.0
 * @since 2.0
 */
public class GameModPackFetcher
{
    /**
     * Subdirectory under the launcher folder where cached manifests are stored.
     */
    private static final String MANIFEST_CACHE_DIR = "manifest_cache";

    /**
     * Fetches the mod pack object from the specified manifest URL. If the network is available, downloads the latest
     * manifest and caches it locally. If offline, falls back to the cached version.
     *
     * @param manifestUrl       mod pack manifest URL
     * @param createEnvironment whether to create the mod pack environment
     *
     * @return mod pack object
     *
     * @since 1.0
     */
    public static GameModPack get( String manifestUrl, boolean createEnvironment ) {
        GameModPack gameModPack;
        try {
            String manifestBody;
            if ( NetworkUtilities.isOffline() ) {
                // Offline: load from cache
                manifestBody = loadCachedManifest( manifestUrl );
                if ( manifestBody == null ) {
                    throw new IOException( "No cached manifest available for offline mode" );
                }
                Logger.logStd( "Loaded cached manifest for offline mode: " + manifestUrl );
            }
            else {
                // Online: download and cache
                manifestBody = NetworkUtilities.downloadFileFromURL( manifestUrl );
                cacheManifest( manifestUrl, manifestBody );
            }
            gameModPack = JSONUtilities.getGson().fromJson( manifestBody, GameModPack.class );
            if ( createEnvironment ) {
                gameModPack.prepareEnvironment();
                if ( !NetworkUtilities.isOffline() ) {
                    gameModPack.cacheImages();
                }
            }
        }
        catch ( Exception e ) {
            // Use silent logging to avoid blocking error dialogs for each failed pack
            Logger.logErrorSilent( "The following installed mod pack could not be loaded: " + manifestUrl );
            Logger.logThrowable( e );
            gameModPack = GameModPack.createFailedModPack( manifestUrl, e.getMessage() );
        }

        gameModPack.manifestUrl = manifestUrl;
        return gameModPack;
    }

    /**
     * Returns the cache file path for the given manifest URL. Uses a hash of the URL as filename.
     */
    private static Path getCacheFilePath( String manifestUrl )
    {
        String hash = Integer.toHexString( manifestUrl.hashCode() );
        Path cacheDir = Path.of( LocalPathManager.getLauncherModpackFolderPath(), MANIFEST_CACHE_DIR );
        return cacheDir.resolve( hash + ".json" );
    }

    /**
     * Caches the manifest body to disk for offline use.
     */
    private static void cacheManifest( String manifestUrl, String manifestBody )
    {
        try {
            Path cacheFile = getCacheFilePath( manifestUrl );
            //noinspection ResultOfMethodCallIgnored
            cacheFile.getParent().toFile().mkdirs();
            Files.writeString( cacheFile, manifestBody, StandardCharsets.UTF_8 );
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "Unable to cache manifest for " + manifestUrl );
        }
    }

    /**
     * Loads a cached manifest from disk, or returns null if no cache exists.
     */
    private static String loadCachedManifest( String manifestUrl )
    {
        try {
            Path cacheFile = getCacheFilePath( manifestUrl );
            if ( Files.exists( cacheFile ) ) {
                return Files.readString( cacheFile, StandardCharsets.UTF_8 );
            }
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "Unable to load cached manifest for " + manifestUrl );
        }
        return null;
    }
}
