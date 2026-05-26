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

import com.micatechnologies.minecraft.launcher.config.GameModeManager;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.utilities.DownloadTracker;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Handles downloading and verification of modpack content files (mods, configs, resource packs, shader packs, and
 * initial files). Extracted from {@link GameModPack} to separate file synchronization concerns from game lifecycle
 * management.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
class GameModPackFileSync
{
    /**
     * Maximum number of concurrent download threads for mod files.
     */
    private static final int MAX_DOWNLOAD_THREADS = 8;

    private final GameModPackMetadata         metadata;
    private final GameModPackProgressProvider  progressProvider;
    private final DownloadTracker              downloadTracker;

    /**
     * Creates a new file sync handler for the specified modpack.
     *
     * @param metadata         the modpack metadata (provides paths, file lists, etc.)
     * @param progressProvider the progress callback, or null if progress reporting is not needed
     */
    GameModPackFileSync( GameModPackMetadata metadata, GameModPackProgressProvider progressProvider )
    {
        this.metadata = metadata;
        this.progressProvider = progressProvider;
        this.downloadTracker = ( progressProvider != null ) ? progressProvider.getDownloadTracker() : null;

        // Pre-count total files across all categories so the tracker shows accurate totals from the start
        if ( downloadTracker != null ) {
            long totalFiles = 0;
            if ( metadata.packMods != null ) totalFiles += metadata.packMods.size();
            if ( metadata.packConfigs != null ) totalFiles += metadata.packConfigs.size();
            if ( metadata.packResourcePacks != null && !GameModeManager.isServer() )
                totalFiles += metadata.packResourcePacks.size();
            if ( metadata.packShaderPacks != null && !GameModeManager.isServer() )
                totalFiles += metadata.packShaderPacks.size();
            if ( metadata.packInitialFiles != null ) totalFiles += metadata.packInitialFiles.size();
            downloadTracker.setTotalFileCount( totalFiles );
        }
    }

    /**
     * Verifies the integrity of local copies of this mod pack's mods and repair/download/update as necessary.
     *
     * @throws ModpackException if unable to fetch latest mods
     */
    void fetchLatestMods() throws ModpackException
    {
        // Imported packs (Prism / MultiMC instance imports) carry their
        // own user-curated mods/ folder that was never authored as a
        // Mica manifest. The packMods list is empty, so the regular
        // clearFloatingMods sweep would wipe everything the user just
        // imported. Skip both the cleanup and the download loop — there's
        // nothing to download. The user manages this pack's mods through
        // the detail-modal Mods section (toggle / Modrinth update check)
        // and any direct filesystem edits.
        if ( metadata.isImportedSkipSync() ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Imported pack — skipping mod sync", 100 );
            }
            return;
        }

        // Cleanup mods that don't belong
        clearFloatingMods();
        if ( progressProvider != null ) {
            progressProvider.submitProgress( "Removed floating mods", 30.0 );
        }

        // Check if mods supplied
        if ( metadata.packMods == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "No mods to handle", 100 );
            }
            return;
        }

        // Get full path to mods folder
        String modLocalPathPrefix = metadata.getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_MODS_LOCAL_FOLDER;

        // Build list of mod download threads
        if ( metadata.packMods.size() > 1 ) {
            ExecutorService threadPool = Executors.newFixedThreadPool(
                    Math.min( metadata.packMods.size(), MAX_DOWNLOAD_THREADS ) );
            List< Future< Boolean > > threadPoolFutures = new ArrayList<>();
            for ( GameMod mod : metadata.packMods ) {
                Callable< Boolean > updateFileCallable = () -> {
                    mod.setLocalPathPrefix( modLocalPathPrefix );
                    if ( downloadTracker != null ) {
                        mod.setDownloadTracker( downloadTracker );
                    }

                    if ( progressProvider != null ) {
                        progressProvider.setCurrText( "Downloading " + mod.name + "..." );
                    }

                    boolean ret = mod.updateLocalFile( GameModeManager.getCurrentGameMode() );

                    if ( progressProvider != null ) {
                        progressProvider.submitProgress( "Verified " + mod.name,
                                                         ( 70.0 / ( double ) metadata.packMods.size() ) );
                    }
                    if ( downloadTracker != null ) {
                        downloadTracker.completeFile();
                    }
                    return ret;
                };
                Future< Boolean > future = threadPool.submit( updateFileCallable );
                threadPoolFutures.add( future );
            }
            threadPool.shutdown();
            try {
                if ( !threadPool.awaitTermination( 30, TimeUnit.MINUTES ) ) {
                    threadPool.shutdownNow();
                    throw new ModpackException(
                            "Mod downloads did not complete within 30 minutes. Check your network connection." );
                }
            }
            catch ( InterruptedException e ) {
                threadPool.shutdownNow();
                throw new ModpackException( "The download of Minecraft mods was interrupted before completion!", e );
            }

            // Parse list of futures
            for ( Future< Boolean > threadPoolFuture : threadPoolFutures ) {
                try {
                    threadPoolFuture.get();
                }
                catch ( InterruptedException e ) {
                    throw new ModpackException( "The download of Minecraft mods was interrupted before completion!",
                                                e );
                }
                catch ( ExecutionException e ) {
                    throw new ModpackException( "Unable to execute runner to retrieve Minecraft mods!", e );
                }
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this mod pack's configs and repair/download/update as necessary.
     *
     * @throws ModpackException if unable to fetch latest configs
     */
    void fetchLatestConfigs() throws ModpackException
    {
        // Get full path to configs folder
        String configLocalPathPrefix = metadata.getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_CONFIGS_LOCAL_FOLDER;

        // Check if configs supplied
        if ( metadata.packConfigs == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "No configs to handle", 100 );
            }
            return;
        }

        // Update each config if necessary
        for ( GameAsset config : metadata.packConfigs ) {
            config.setLocalPathPrefix( configLocalPathPrefix );
            if ( downloadTracker != null ) {
                config.setDownloadTracker( downloadTracker );
            }
            if ( progressProvider != null ) {
                progressProvider.setCurrText( "Downloading " + FilenameUtils.getName( config.getFullLocalFilePath() ) + "..." );
            }
            config.updateLocalFile( GameModeManager.getCurrentGameMode() );

            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Verified " + FilenameUtils.getName( config.getFullLocalFilePath() ),
                                                 ( 100.0 / ( double ) metadata.packConfigs.size() ) );
            }
            if ( downloadTracker != null ) {
                downloadTracker.completeFile();
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this mod pack's resource packs and repair/download/update as
     * necessary.
     *
     * @throws ModpackException if unable to fetch latest resource packs
     */
    void fetchLatestResourcePacks() throws ModpackException
    {
        // Only download resource packs on client (not server)
        if ( GameModeManager.isServer() ) {
            return;
        }

        // Check if resource packs supplied
        if ( metadata.packResourcePacks == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "No resource packs to handle", 100 );
            }
            return;
        }

        // Get full path to resource pack folder
        String resPackLocalPathPrefix = metadata.getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_RESOURCEPACKS_LOCAL_FOLDER;

        // Update each resource pack if necessary
        for ( ManagedGameFile resourcePack : metadata.packResourcePacks ) {
            resourcePack.setLocalPathPrefix( resPackLocalPathPrefix );
            if ( downloadTracker != null ) {
                resourcePack.setDownloadTracker( downloadTracker );
            }
            if ( progressProvider != null ) {
                progressProvider.setCurrText( "Downloading " + FilenameUtils.getName( resourcePack.getFullLocalFilePath() ) + "..." );
            }
            resourcePack.updateLocalFile();
            if ( progressProvider != null ) {
                progressProvider.submitProgress(
                        "Verified " + FilenameUtils.getName( resourcePack.getFullLocalFilePath() ),
                        ( 100.0 / ( double ) metadata.packResourcePacks.size() ) );
            }
            if ( downloadTracker != null ) {
                downloadTracker.completeFile();
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this mod pack's shader packs and repair/download/update as necessary.
     *
     * @throws ModpackException if unable to fetch latest shader packs
     */
    void fetchLatestShaderPacks() throws ModpackException
    {
        // Only download shader packs on client (not server)
        if ( GameModeManager.isServer() ) {
            return;
        }

        // Check if shader packs supplied
        if ( metadata.packShaderPacks == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "No shader packs to handle", 100 );
            }
            return;
        }

        // Get full path to shader pack folder
        String shaderPackLocalPathPrefix = metadata.getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_SHADERPACKS_LOCAL_FOLDER;

        // Update each shader pack if necessary
        for ( ManagedGameFile shaderPack : metadata.packShaderPacks ) {
            shaderPack.setLocalPathPrefix( shaderPackLocalPathPrefix );
            if ( downloadTracker != null ) {
                shaderPack.setDownloadTracker( downloadTracker );
            }
            if ( progressProvider != null ) {
                progressProvider.setCurrText( "Downloading " + FilenameUtils.getName( shaderPack.getFullLocalFilePath() ) + "..." );
            }
            shaderPack.updateLocalFile();
            if ( progressProvider != null ) {
                progressProvider.submitProgress(
                        "Verified " + FilenameUtils.getName( shaderPack.getFullLocalFilePath() ),
                        ( 100.0 / ( double ) metadata.packShaderPacks.size() ) );
            }
            if ( downloadTracker != null ) {
                downloadTracker.completeFile();
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this mod pack's initial files and repair/download/update as necessary.
     *
     * @throws ModpackException if unable to fetch latest initial files
     */
    void fetchLatestInitialFiles() throws ModpackException
    {
        // Get full path to configs folder
        String initFilesLocalPathPrefix = metadata.getPackRootFolder();

        // Note: log4j security configs are now downloaded and applied in applyLog4jSecurityConfig()
        // during startGame(), using the correct version-specific config for the Minecraft version.

        // Check if initial files supplied
        if ( metadata.packInitialFiles == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "No initial files to handle", 100 );
            }
            return;
        }

        // Update each initial file if necessary
        for ( GameAsset initFile : metadata.packInitialFiles ) {
            initFile.setLocalPathPrefix( initFilesLocalPathPrefix );
            if ( downloadTracker != null ) {
                initFile.setDownloadTracker( downloadTracker );
            }
            if ( progressProvider != null ) {
                progressProvider.setCurrText( "Downloading " + FilenameUtils.getName( initFile.getFullLocalFilePath() ) + "..." );
            }
            initFile.updateLocalFile( GameModeManager.getCurrentGameMode() );

            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Verified " + FilenameUtils.getName( initFile.getFullLocalFilePath() ),
                                                 ( 100.0 / ( double ) metadata.packInitialFiles.size() ) );
            }
            if ( downloadTracker != null ) {
                downloadTracker.completeFile();
            }
        }
    }

    /**
     * Remove all mods from this modpack mods folder that are not in the modpack manifest.
     */
    private void clearFloatingMods()
    {
        // Get full path to modpack mods folder
        String modLocalPathPrefix = metadata.getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_MODS_LOCAL_FOLDER;

        // Build list of valid mods
        ArrayList< String > validModPaths = new ArrayList<>();
        if ( metadata.packMods != null ) {
            for ( GameMod mod : metadata.packMods ) {
                if ( ( GameModeManager.isClient() && mod.clientReq ) ||
                        ( GameModeManager.isServer() && mod.serverReq ) ) {
                    mod.setLocalPathPrefix( modLocalPathPrefix );
                    validModPaths.add( mod.getFullLocalFilePath() );
                }
            }
        }

        // Loop through files in mods folder and remove unwanted
        File modpackModsFolderFile = SynchronizedFileManager.getSynchronizedFile( modLocalPathPrefix );
        File[] modsFolderFiles = modpackModsFolderFile.listFiles();
        if ( modsFolderFiles != null ) {
            for ( File modFile : modsFolderFiles ) {
                if ( !validModPaths.contains( modFile.getPath() ) && modFile.isFile() ) {
                    boolean delete = modFile.delete();
                    if ( !delete ) {
                        Logger.logError( "Unable to delete file during mod folder sanitization." );
                    }
                }
            }
        }
    }
}
