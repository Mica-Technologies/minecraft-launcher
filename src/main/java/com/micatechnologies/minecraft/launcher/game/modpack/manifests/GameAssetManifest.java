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
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackProgressProvider;
import com.micatechnologies.minecraft.launcher.game.modpack.ManagedGameFile;
import com.micatechnologies.minecraft.launcher.utilities.JsonHelper;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
     * The asset-index id (e.g. {@code pre-1.6}, {@code legacy}, {@code 1.7.10}). Used to
     * scope the virtual-asset tree under {@code assets/virtual/&lt;id&gt;/} when the index
     * is marked virtual.
     *
     * @since 3.2
     */
    private final String version;

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
        // Index lives under the launcher-wide shared/assets/indexes/ folder so multiple
        // modpacks targeting the same MC version share one copy.
        super( remote, SystemUtilities.buildFilePath( LocalPathManager.getLauncherSharedAssetsFolderPath(),
                                                      "indexes",
                                                      version + ManifestConstants.JSON_FILE_EXTENSION ) );
        this.parentModPack = parentModPack;
        this.version = version;
    }

    /** Absolute path to the shared {@code assets/} root used by every modpack. Modern MC
     *  reads via the asset index from here; legacy MC reads from the per-pack flat tree
     *  materialized by {@link #materializeVirtualTree()}.
     *
     *  @since 3.2 */
    public static String getSharedAssetsRoot() {
        return LocalPathManager.getLauncherSharedAssetsFolderPath();
    }

    /** Deletes the now-redundant per-pack {@code assets/objects/} and {@code assets/indexes/}
     *  folders if they exist. Hashed objects and indexes are stored in the shared launcher
     *  tree now; this is a one-shot migration hook that runs each download but is a cheap
     *  no-op once the folders are gone. Best-effort — failures are logged, not propagated. */
    private void cleanupLegacyPerPackAssetsTree()
    {
        deleteRecursive( new File( SystemUtilities.buildFilePath(
                parentModPack.getPackRootFolder(),
                LocalPathConstants.MINECRAFT_ASSET_RELATIVE_OBJECTS_FOLDER ) ) );
        deleteRecursive( new File( SystemUtilities.buildFilePath(
                parentModPack.getPackRootFolder(),
                LocalPathConstants.MINECRAFT_ASSET_RELATIVE_INDEXES_FOLDER ) ) );
    }

    private void deleteRecursive( File f )
    {
        if ( f == null || !f.exists() ) {
            return;
        }
        try {
            if ( f.isDirectory() ) {
                File[] children = f.listFiles();
                if ( children != null ) {
                    for ( File child : children ) {
                        deleteRecursive( child );
                    }
                }
            }
            if ( !f.delete() ) {
                Logger.logWarningSilent( "Unable to delete legacy asset path: " + f.getAbsolutePath() );
            }
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Error cleaning legacy asset path " + f.getAbsolutePath() +
                                             ": " + e.getMessage() );
        }
    }

    /**
     * Returns true if the asset index is marked {@code virtual: true} — meaning the game
     * expects to read assets by virtual name from a flat directory, not by hash. The pre-1.6
     * Mojang index uses this; modern indexes do not.
     *
     * @return true if the index requires a virtual asset tree
     *
     * @throws ModpackException if the manifest can't be read
     * @since 3.2
     */
    public boolean isVirtual() throws ModpackException {
        return JsonHelper.getBoolean( readToJsonObject(), "virtual", false );
    }

    /**
     * Returns true if the asset index is marked {@code map_to_resources: true} — 1.6.x
     * convention where assets are dropped into {@code &lt;gameDir&gt;/resources/}.
     *
     * @return true if the index requires resource-mapping
     *
     * @throws ModpackException if the manifest can't be read
     * @since 3.2
     */
    public boolean mapsToResources() throws ModpackException {
        return JsonHelper.getBoolean( readToJsonObject(), "map_to_resources", false );
    }

    /**
     * Returns the on-disk path where the virtual flat asset tree lives for this index,
     * scoped by the asset-index id so multiple legacy versions can coexist in one modpack
     * install root. Callers point {@code --assetsDir} (or {@code ${game_assets}}) here.
     *
     * @return absolute path string to {@code assets/virtual/&lt;id&gt;/}
     *
     * @since 3.2
     */
    public String getVirtualAssetsPath() {
        return SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                              LocalPathConstants.MINECRAFT_ASSET_RELATIVE_VIRTUAL_FOLDER,
                                              version );
    }

    /**
     * Returns the on-disk path used when the index maps to resources — the
     * {@code &lt;gameDir&gt;/resources/} folder inside the modpack install.
     *
     * @return absolute path string to {@code &lt;gameDir&gt;/resources/}
     *
     * @since 3.2
     */
    public String getResourcesPath() {
        return SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                              LocalPathConstants.MINECRAFT_RESOURCES_FOLDER );
    }

    /**
     * Materializes the flat virtual-asset tree by copying each hashed object to its virtual
     * path under {@link #getVirtualAssetsPath()} (or {@link #getResourcesPath()} when the
     * index uses {@code map_to_resources}). Skips files that are already present and have a
     * matching length — a cheap freshness check that avoids re-copying tens of thousands of
     * sound clips on every launch. Idempotent.
     *
     * <p>No-op when neither {@code virtual} nor {@code map_to_resources} is set. Safe to
     * call even on modern indexes — the early-return keeps the cost to a single JSON read.</p>
     *
     * @throws ModpackException if the manifest can't be read or copies fail
     * @since 3.2
     */
    public void materializeVirtualTree() throws ModpackException {
        boolean virtual = isVirtual();
        boolean mapToResources = mapsToResources();
        if ( !virtual && !mapToResources ) {
            return;
        }

        String destRoot = mapToResources ? getResourcesPath() : getVirtualAssetsPath();
        JsonObject objects = readToJsonObject().get( ManifestConstants.MINECRAFT_ASSET_MANIFEST_OBJECTS_KEY )
                                               .getAsJsonObject();

        int copied = 0;
        int skipped = 0;
        for ( String assetName : objects.keySet() ) {
            JsonObject asset = objects.getAsJsonObject( assetName );
            String assetHash = asset.get( ManifestConstants.MINECRAFT_ASSET_MANIFEST_OBJECT_HASH_KEY ).getAsString();
            String assetFolder = assetHash.substring( 0, 2 );

            File source = new File( SystemUtilities.buildFilePath(
                    LocalPathManager.getLauncherSharedAssetsFolderPath(),
                    "objects",
                    assetFolder, assetHash ) );
            File dest = new File( SystemUtilities.buildFilePath( destRoot, assetName ) );

            if ( !source.isFile() ) {
                // The hashed object should have been downloaded by downloadAssets() already;
                // if it's missing we skip rather than fail so the launch isn't blocked by
                // one stale entry. Surfaces as the game failing to load that specific
                // resource rather than as a hard launch error.
                continue;
            }

            if ( dest.isFile() && dest.length() == source.length() ) {
                skipped++;
                continue;
            }

            try {
                File parent = dest.getParentFile();
                if ( parent != null && !parent.isDirectory() && !parent.mkdirs() ) {
                    throw new IOException( "Unable to create directory: " + parent );
                }
                Files.copy( source.toPath(), dest.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES );
                copied++;
            }
            catch ( IOException e ) {
                throw new ModpackException( "Failed to materialize legacy asset " + assetName, e );
            }
        }

        Logger.logDebug( "Virtual asset tree ready at " + destRoot +
                                 " (" + copied + " copied, " + skipped + " up-to-date)" );
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

            // Build full asset path (shared across all modpacks targeting this MC version)
            String assetPath = SystemUtilities.buildFilePath(
                    LocalPathManager.getLauncherSharedAssetsFolderPath(),
                    "objects",
                    assetFolder, assetHash );

            // Build full asset URL
            String assetURL = ManifestConstants.MINECRAFT_ASSET_SERVER_URL_TEMPLATE.replace(
                    ManifestConstants.MINECRAFT_ASSET_SERVER_URL_FOLDER_KEY, assetFolder )
                                                                                   .replace(
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
        // One-shot migration: an older launcher build kept hashed objects + asset indexes
        // under <packRoot>/assets/{objects,indexes}/ — now both live in the shared launcher
        // folder. Delete the orphans so they don't waste disk. The per-pack assets/virtual/
        // subtree (legacy flat-asset materialization) stays.
        cleanupLegacyPerPackAssetsTree();

        // Update asset manifest first
        updateLocalFile();

        // Update each asset
        List< ManagedGameFile > assets = getAssets();
        if ( assets.isEmpty() ) {
            return;
        }

        // Build list of asset download threads
        int maxThreads = Math.max( 1, Runtime.getRuntime().availableProcessors() );
        int threadCount = Math.min( assets.size(), maxThreads );
        ExecutorService threadPool = Executors.newFixedThreadPool( threadCount );
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
        if ( !threadPool.awaitTermination( 30, TimeUnit.MINUTES ) ) {
            threadPool.shutdownNow();
            throw new ModpackException( "Asset downloads did not complete within 30 minutes." );
        }

        // Parse list of futures
        for (Future< Boolean > threadPoolFuture : threadPoolFutures ) {
            threadPoolFuture.get();
        }
    }
}
