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

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Function;

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.config.GameModeManager;
import com.micatechnologies.minecraft.launcher.consts.GUIConstants;
import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackScanDetectionException;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.RuntimeManager;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.GameLibraryManifest;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.GameVersionManifest;
import com.micatechnologies.minecraft.launcher.utilities.HashUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import me.cortex.jarscanner.Constants;
import me.cortex.jarscanner.Main;
import me.cortex.jarscanner.Results;
import org.apache.commons.io.FilenameUtils;

/**
 * Class representation of a Forge mod pack with functionality to update mods, game libraries and start the game.
 *
 * @version 1.3
 */
public class GameModPack
{

    /**
     * Mod pack name. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packName;

    /**
     * Mod pack version. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packVersion;

    /**
     * Mod pack website URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packURL;

    /**
     * Mod pack unstable flag. Value read from manifest JSON.
     *
     * @since 1.2
     */
    @SuppressWarnings( "unused" )
    private boolean packUnstable;

    /**
     * Mod pack custom Discord RPC flag. Value read from manifest JSON.
     *
     * @since 1.2
     */
    @SuppressWarnings( "unused" )
    private boolean packCustomDiscordRpc;

    /**
     * Mod pack logo URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packLogoURL;

    /**
     * Mod pack logo SHA-1. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packLogoSha1;

    /**
     * Mod pack background URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packBackgroundURL;

    /**
     * Mod pack background SHA-1. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packBackgroundSha1;

    /**
     * Mod pack minimum RAM (GB). Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packMinRAMGB;

    /**
     * Mod pack Forge download URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packForgeURL;

    /**
     * Mod pack Forge download SHA-1 hash. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    private String packForgeHash;

    /**
     * Mod pack scan exclusions (file or folder names, relative to mod pack root). Value read from manifest JSON.
     *
     * @since 1.3
     */
    @SuppressWarnings( "unused" )
    private List< String > packScanExclusions;

    /**
     * List of mod pack Forge mods. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    private List< GameMod > packMods;

    /**
     * List of mod pack Forge configs. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    private List< GameAsset > packConfigs;

    /**
     * List of mod pack resource packs. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    private List< ManagedGameFile > packResourcePacks;

    /**
     * List of mod pack shader packs. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    private List< ManagedGameFile > packShaderPacks;

    /**
     * List of initial files for mod pack. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( { "MismatchedQueryAndUpdateOfCollection", "unused" } )
    private List< GameAsset > packInitialFiles;

    private transient GameModPackProgressProvider progressProvider = null;
    private           boolean                     didCacheImages   = false;

    /**
     * Get the installation folder of this mod pack.
     *
     * @return installation folder Path
     */
    @SuppressWarnings( "WeakerAccess" )
    public String getPackRootFolder() {
        return LocalPathManager.getLauncherModpackFolderPath() + File.separator + getPackSanitizedName();
    }

    public String getPackSanitizedName() {
        return getPackName().replaceAll( "[^a-zA-Z0-9]", "" );
    }

    public String getPackBinFolder() {
        return SystemUtilities.buildFilePath( getPackRootFolder(), LocalPathConstants.MOD_PACK_BIN_FOLDER_NAME );
    }

    /**
     * Get a Forge application object referencing the Forge application jar in this modpack.
     *
     * @return mod pack MCForgeApp
     *
     * @throws ModpackException if unable to get Forge app object
     */
    private GameModLoaderForge getForgeApp() throws ModpackException {
        return new GameModLoaderForge( packForgeURL, packForgeHash, this );
    }

    /**
     * Get the Minecraft version of this modpack.
     *
     * @return modpack Minecraft version
     *
     * @throws ModpackException if unable to get Minecraft version
     */
    @SuppressWarnings( "WeakerAccess" )
    public String getMinecraftVersion() throws ModpackException {
        return getForgeApp().getMinecraftVersion();
    }

    /**
     * Get the Forge version of this modpack.
     *
     * @return modpack Forge version
     *
     * @throws ModpackException if unable to get Forge version
     */
    @SuppressWarnings( "WeakerAccess" )
    public String getForgeVersion() throws ModpackException {
        return getForgeApp().getForgeVersion();
    }

    public double getPackMinRAMGB() {
        return Double.parseDouble( packMinRAMGB );
    }

    /**
     * Get the Minecraft library manifest for this modpack.
     *
     * @return Minecraft library manifest
     *
     * @throws ModpackException if unable to get manifest
     */
    public GameLibraryManifest getMinecraftLibraryManifest() throws ModpackException {
        return GameVersionManifest.getMinecraftLibraryManifest( getMinecraftVersion(), this );
    }

    /**
     * Get the mod pack scan exclusions (file or folder names, relative to mod pack root) for this modpack.
     *
     * @return list of scan exclusions
     *
     * @since 1.3
     */
    public List< String > getPackScanExclusions() {
        if ( packScanExclusions == null ) {
            packScanExclusions = new ArrayList<>();
        }
        return packScanExclusions;
    }

    /**
     * Verify all local modpack files and downloads files if necessary, then compiles the classpath String for running
     * this modpack.
     *
     * @return modpack classpath String
     *
     * @throws ModpackException if unable to update modpack or build classpath
     * @since 1.0
     */
    public String buildModpackClasspath() throws ModpackException {
        if ( progressProvider != null ) {
            progressProvider.setCurrText( "Building modpack classpath environment" );
        }

        // Verify local Forge mods
        if ( progressProvider != null ) {
            progressProvider.startProgressSection( "Fetching mods", 20.0 );
        }
        fetchLatestMods();
        if ( progressProvider != null ) {
            progressProvider.endProgressSection( "Done fetching mods" );
        }

        // Verify local Forge configs
        if ( progressProvider != null ) {
            progressProvider.startProgressSection( "Fetching configs", 5.0 );
        }
        fetchLatestConfigs();
        if ( progressProvider != null ) {
            progressProvider.endProgressSection( "Done fetching configs" );
        }

        // Verify local resource packs
        if ( progressProvider != null ) {
            progressProvider.startProgressSection( "Fetching resource packs", 7.0 );
        }
        fetchLatestResourcePacks();
        if ( progressProvider != null ) {
            progressProvider.endProgressSection( "Done fetching resource packs" );
        }

        // Verify local shader packs
        if ( progressProvider != null ) {
            progressProvider.startProgressSection( "Fetching shader packs", 5.0 );
        }
        fetchLatestShaderPacks();
        if ( progressProvider != null ) {
            progressProvider.endProgressSection( "Done fetching shader packs" );
        }

        // Verify local initial files
        if ( progressProvider != null ) {
            progressProvider.startProgressSection( "Fetching initial files", 2.0 );
        }
        fetchLatestInitialFiles();
        if ( progressProvider != null ) {
            progressProvider.endProgressSection( "Done fetching initial files" );
        }

        // Verify modpack logo
        if ( progressProvider != null ) {
            progressProvider.startProgressSection( "Fetching modpack images", 1.0 );
        }
        cacheImages();
        if ( progressProvider != null ) {
            progressProvider.endProgressSection( "Done fetching modpack images" );
        }

        // Verify local Forge assets and get classpath
        if ( progressProvider != null ) {
            progressProvider.startProgressSection( "Fetching Forge assets and classpath", 20.0 );
        }
        String forgeAssetClasspath = getForgeApp().buildForgeClasspath( GameModeManager.getCurrentGameMode(),
                                                                        progressProvider );
        if ( progressProvider != null ) {
            progressProvider.endProgressSection( "Done fetching Forge assets and classpath" );
        }

        // Verify local Minecraft assets and get classpath
        if ( progressProvider != null ) {
            progressProvider.startProgressSection( "Fetching Minecraft assets, libraries and classpath", 25.0 );
        }
        GameLibraryManifest libraryManifest = GameVersionManifest.getMinecraftLibraryManifest( getMinecraftVersion(),
                                                                                               this );
        String minecraftAssetClasspath = libraryManifest.buildMinecraftClasspath( GameModeManager.getCurrentGameMode(),
                                                                                  progressProvider );
        if ( progressProvider != null ) {
            progressProvider.endProgressSection( "Done fetching Minecraft assets, libraries and classpath" );
        }

        // Run scan on downloaded files
        if ( progressProvider != null ) {
            progressProvider.startProgressSection( "Scanning downloaded files", 15.0 );
        }
        try {
            scanModPackRootFolder();
        }
        catch ( IOException e ) {
            throw new ModpackException( "Unable to scan downloaded files due to an exception!", e );
        }
        catch ( InterruptedException e ) {
            throw new ModpackException( "Unable to scan downloaded files due to an interruption!", e );
        }
        if ( progressProvider != null ) {
            progressProvider.endProgressSection( "Done scanning downloaded files!" );
        }

        // Add classpath separator between Forge and Minecraft only if Forge classpath not empty (it shouldn't be)
        if ( !forgeAssetClasspath.isEmpty() && !forgeAssetClasspath.endsWith( File.pathSeparator ) ) {
            forgeAssetClasspath += File.pathSeparator;
        }

        return forgeAssetClasspath + minecraftAssetClasspath;
    }

    /**
     * Scans the modpack root folder for any infections identified by the {@link me.cortex.jarscanner.Detector} class.
     *
     * @throws ModpackException     if any infections are found
     * @throws IOException          if any I/O errors occur while scanning
     * @throws InterruptedException if the current thread is interrupted while scanning
     */
    public void scanModPackRootFolder() throws ModpackException, IOException, InterruptedException {
        int halfCoreCount = Runtime.getRuntime().availableProcessors() / 2;
        int scanCoreCount = Math.max( 1, halfCoreCount );
        boolean emitWalkErrors = true;
        Function< String, String > logOutput = ( out ) -> {
            if ( progressProvider != null ) {
                String processedOut = out.replace( Constants.ANSI_RED, "" )
                                         .replace( Constants.ANSI_GREEN, "" )
                                         .replace( Constants.ANSI_WHITE, "" )
                                         .replace( Constants.ANSI_RESET, "" );
                progressProvider.setCurrText( processedOut );
            }
            return out;
        };

        Results scanResults = Main.run( scanCoreCount, Path.of( getPackRootFolder() ), emitWalkErrors,
                                        getPackScanExclusions(), logOutput, null );
        if ( scanResults.getStage1Detections() != null && !scanResults.getStage1Detections().isEmpty() ) {
            logOutput.apply( "Stage 1 infections found: " + scanResults.getStage1Detections().size() );
            throw new ModpackScanDetectionException( scanResults );
        }
        else if ( scanResults.getStage2Detections() != null && !scanResults.getStage2Detections().isEmpty() ) {
            logOutput.apply( "Stage 2 infections found: " + scanResults.getStage2Detections().size() );
            throw new ModpackScanDetectionException( scanResults );
        }
    }

    public void startGame() throws ModpackException
    {
        // Get classpath, main class and Minecraft args
        String cp = buildModpackClasspath();
        String minecraftArgs = GameModeManager.isClient() ? getForgeApp().getMinecraftArguments() : "";
        String minecraftMainClass = GameModeManager.isClient() ?
                                    getForgeApp().getMinecraftMainClass() :
                                    "net.minecraftforge.fml.relauncher.ServerLaunchWrapper";

        // Add main class to arguments
        minecraftArgs = minecraftMainClass + " " + minecraftArgs;

        // Add log4j patch
        minecraftArgs = "-Dlog4j2.formatMsgNoLookups=true -Dlog4j.configurationFile=log4j2_112-116.xml" +
                " " +
                minecraftArgs;

        // Add min and max RAM to arguments
        long SminRAMMB = ConfigManager.getMinRam();
        long SmaxRAMMB = ConfigManager.getMaxRam();
        if ( GameModeManager.isServer() ) {
            // Get min and max RAM from existing JVM args
            RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
            List< String > aList = bean.getInputArguments();

            for ( String s : aList ) {
                System.out.println( s );
                if ( s.contains( "Xms" ) ) {
                    SminRAMMB = Integer.parseInt( s.replaceAll( "\\D+", "" ) );
                    System.out.println( "Configuring min RAM from provided " + s );
                }
                if ( s.contains( "Xmx" ) ) {
                    SmaxRAMMB = Integer.parseInt( s.replaceAll( "\\D+", "" ) );
                    System.out.println( "Configuring max RAM from provided " + s );
                }
            }

        }
        minecraftArgs = "-Xms" + SminRAMMB + "m " + minecraftArgs;
        minecraftArgs = "-Xmx" + SmaxRAMMB + "m " + minecraftArgs;

        // Add garbage collection config to arguments
        minecraftArgs = ConfigManager.getCustomJvmArgs() + " " + minecraftArgs;

        // Add classpath to arguments
        if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
            minecraftArgs = "-cp \"" + cp + "\" " + minecraftArgs;
        }
        else {
            minecraftArgs = "-cp " + cp + " " + minecraftArgs;
        }

        // Add natives path to arguments
        String nativesFolder = getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_MINECRAFT_NATIVES_LOCAL_FOLDER;
        File nativesFolderFile = SynchronizedFileManager.getSynchronizedFile( nativesFolder );
        if ( nativesFolderFile.exists() ) {
            nativesFolderFile.setExecutable( true );
            nativesFolderFile.setReadable( true );
            nativesFolderFile.setWritable( true );
            for ( File f : nativesFolderFile.listFiles() ) {
                f.setExecutable( true );
                f.setReadable( true );
                f.setWritable( true );
            }
        }
        if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
            minecraftArgs = "-Djava.library.path=\"" + nativesFolder + "\" " + minecraftArgs;
        }
        else {
            minecraftArgs = "-Djava.library.path=" + nativesFolder + " " + minecraftArgs;
        }

        // Replace fillers with data
        if ( GameModeManager.isClient() ) {
            minecraftArgs = minecraftArgs.replace( "${auth_player_name}",
                                                   MCLauncherAuthManager.getLoggedInUser().name() );
            minecraftArgs = minecraftArgs.replace( "${version_name}", getForgeVersion() );
            if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
                minecraftArgs = minecraftArgs.replace( "${game_directory}", "\"" + getPackRootFolder() + "\"" );
                minecraftArgs = minecraftArgs.replace( "${assets_root}", "\"" +
                        getPackRootFolder() +
                        File.separator +
                        ModPackConstants.MODPACK_MINECRAFT_ASSETS_LOCAL_FOLDER +
                        "\"" );
            }
            else {
                minecraftArgs = minecraftArgs.replace( "${game_directory}", getPackRootFolder() );
                minecraftArgs = minecraftArgs.replace( "${assets_root}", getPackRootFolder() +
                        File.separator +
                        ModPackConstants.MODPACK_MINECRAFT_ASSETS_LOCAL_FOLDER );
            }

            minecraftArgs = minecraftArgs.replace( "${assets_index_name}",
                                                   getMinecraftLibraryManifest().getAssetIndexVersion() );
            minecraftArgs = minecraftArgs.replace( "${auth_uuid}", MCLauncherAuthManager.getLoggedInUser().uuid() );
            minecraftArgs = minecraftArgs.replace( "${auth_access_token}",
                                                   MCLauncherAuthManager.getLoggedInUser().accessToken() );
            minecraftArgs = minecraftArgs.replace( "${user_type}", "mojang" );

            // Add title and icon to arguments
            minecraftArgs += " --title " + packName;
            minecraftArgs += " --icon " + getPackLogoFilepath();

            // Set dock name and icon for macOS
            if ( org.apache.commons.lang3.SystemUtils.IS_OS_MAC ) {
                minecraftArgs += " -Xdock:icon=\"" + getPackLogoFilepath() + "\"";
                minecraftArgs += " -Xdock:name=\"" + getPackName() + "\" ";
                minecraftArgs += "-Dapple.laf.useScreenMenuBar=true ";
                minecraftArgs += "-Djdk.lang.Process.launchMechanism=vfork ";
            }
        }

        // Add java call to front of args
        minecraftArgs = RuntimeManager.getJre8Path() + " " + minecraftArgs;

        // Start game
        try {
            SystemUtilities.executeStringCommand( minecraftArgs, getPackRootFolder() );
        }
        catch ( IOException | InterruptedException e ) {
            throw new ModpackException( "Unable to execute mod pack game.", e );
        }
    }

    /**
     * Remove all mods from this modpack mods folder that are not in the modpack.
     *
     * @since 1.0
     */
    private void clearFloatingMods() {
        // Get full path to modpack mods folder
        String modLocalPathPrefix = getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_MODS_LOCAL_FOLDER;

        // Build list of valid mods
        ArrayList< String > validModPaths = new ArrayList<>();
        for ( GameMod mod : packMods ) {
            if ( ( GameModeManager.isClient() && mod.clientReq ) || ( GameModeManager.isServer() && mod.serverReq ) ) {
                mod.setLocalPathPrefix( modLocalPathPrefix );
                validModPaths.add( mod.getFullLocalFilePath() );
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

    /**
     * Verifies the integrity of local copies of this mod pack's mods and repair/download/update as necessary.
     *
     * @throws ModpackException if unable to fetch latest mods
     */
    private void fetchLatestMods() throws ModpackException {
        // Cleanup mods that don't belong
        clearFloatingMods();
        if ( progressProvider != null ) {
            progressProvider.submitProgress( "Removed floating mods", 30.0 );
        }

        // Check if mods supplied
        if ( packMods == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "No mods to handle", 100 );
            }
            return;
        }

        // Get full path to mods folder
        String modLocalPathPrefix = getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_MODS_LOCAL_FOLDER;

        // Build list of mod download threads
        if ( packMods.size() > 1 ) {
            ExecutorService threadPool = Executors.newFixedThreadPool( packMods.size() );
            List< Future< Boolean > > threadPoolFutures = new ArrayList<>();
            for ( GameMod mod : packMods ) {
                Callable< Boolean > updateFileCallable = () -> {
                    mod.setLocalPathPrefix( modLocalPathPrefix );

                    if ( progressProvider != null ) {
                        progressProvider.submitProgress( "Verifying " + mod.name,
                                                         ( 70.0 / ( double ) packMods.size() ) );
                    }

                    boolean ret = mod.updateLocalFile( GameModeManager.getCurrentGameMode() );

                    if ( progressProvider != null ) {
                        progressProvider.setCurrText( "Verified " + mod.name );
                    }
                    return ret;
                };
                Future< Boolean > future = threadPool.submit( updateFileCallable );
                threadPoolFutures.add( future );
            }
            threadPool.shutdown();
            try {
                threadPool.awaitTermination( Long.MAX_VALUE, TimeUnit.MILLISECONDS );
            }
            catch ( InterruptedException e ) {
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
    private void fetchLatestConfigs() throws ModpackException {
        // Get full path to configs folder
        String configLocalPathPrefix = getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_CONFIGS_LOCAL_FOLDER;

        // Check if configs supplied
        if ( packConfigs == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "No configs to handle", 100 );
            }
            return;
        }

        // Update each config if necessary
        for ( GameAsset config : packConfigs ) {
            config.setLocalPathPrefix( configLocalPathPrefix );
            config.updateLocalFile( GameModeManager.getCurrentGameMode() );

            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Verified " + FilenameUtils.getName( config.getFullLocalFilePath() ),
                                                 ( 100.0 / ( double ) packConfigs.size() ) );
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this mod pack's resource packs and repair/download/update as
     * necessary.
     *
     * @throws ModpackException if unable to fetch latest resource packs
     */
    private void fetchLatestResourcePacks() throws ModpackException {
        // Only download resource packs on client (not server)
        if ( GameModeManager.isServer() ) {
            return;
        }

        // Check if resource packs supplied
        if ( packResourcePacks == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "No resource packs to handle", 100 );
            }
            return;
        }

        // Get full path to resource pack folder
        String resPackLocalPathPrefix = getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_RESOURCEPACKS_LOCAL_FOLDER;

        // Update each resource pack if necessary
        for ( ManagedGameFile resourcePack : packResourcePacks ) {
            resourcePack.setLocalPathPrefix( resPackLocalPathPrefix );
            resourcePack.updateLocalFile();
            if ( progressProvider != null ) {
                progressProvider.submitProgress(
                        "Verified " + FilenameUtils.getName( resourcePack.getFullLocalFilePath() ),
                        ( 100.0 / ( double ) packResourcePacks.size() ) );
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this mod pack's shader packs and repair/download/update as necessary.
     *
     * @throws ModpackException if unable to fetch latest shader packs
     */
    private void fetchLatestShaderPacks() throws ModpackException {
        // Only download shader packs on client (not server)
        if ( GameModeManager.isServer() ) {
            return;
        }

        // Check if shader packs supplied
        if ( packShaderPacks == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "No shader packs to handle", 100 );
            }
            return;
        }

        // Get full path to shader pack folder
        String shaderPackLocalPathPrefix = getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_SHADERPACKS_LOCAL_FOLDER;

        // Update each shader pack if necessary
        for ( ManagedGameFile shaderPack : packShaderPacks ) {
            shaderPack.setLocalPathPrefix( shaderPackLocalPathPrefix );
            shaderPack.updateLocalFile();
            if ( progressProvider != null ) {
                progressProvider.submitProgress(
                        "Verified " + FilenameUtils.getName( shaderPack.getFullLocalFilePath() ),
                        ( 100.0 / ( double ) packShaderPacks.size() ) );
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this mod pack's initial files and repair/download/updated as
     * necessary.
     *
     * @throws ModpackException if unable to fetch latest initial files
     */
    private void fetchLatestInitialFiles() throws ModpackException {
        // Get full path to configs folder
        String initFilesLocalPathPrefix = getPackRootFolder();

        // Check for log4j XML patch
        GameAsset log4jPatch = new GameAsset(
                "https://launcher.mojang.com/v1/objects/02937d122c86ce73319ef9975b58896fc1b491d1/log4j2_112-116.xml",
                "log4j2_112-116.xml", "02937d122c86ce73319ef9975b58896fc1b491d1",
                ManagedGameFile.ManagedGameFileHashType.SHA1, true, true );
        log4jPatch.setLocalPathPrefix( initFilesLocalPathPrefix );
        log4jPatch.updateLocalFile( GameModeManager.getCurrentGameMode() );

        // Check if initial files supplied
        if ( packInitialFiles == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "No initial files to handle", 100 );
            }
            return;
        }

        // Update each initial file if necessary
        for ( GameAsset initFile : packInitialFiles ) {
            initFile.setLocalPathPrefix( initFilesLocalPathPrefix );
            initFile.updateLocalFile( GameModeManager.getCurrentGameMode() );

            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Verified " + FilenameUtils.getName( initFile.getFullLocalFilePath() ),
                                                 ( 100.0 / ( double ) packInitialFiles.size() ) );
            }
        }
    }

    public synchronized String getPackLogoFilepath() {
        if ( !didCacheImages ) {
            cacheImages();
        }
        return getRawLogoFilePath();
    }

    public synchronized String getPackBackgroundFilepath() {
        if ( !didCacheImages ) {
            cacheImages();
        }
        return getRawBackgroundFilePath();
    }

    public void setProgressProvider( GameModPackProgressProvider progressProvider ) {
        this.progressProvider = progressProvider;
    }

    private String getRawLogoFilePath() {
        String filename;
        if ( packLogoSha1 != null ) {
            filename = packLogoSha1 + ".png";
        }
        else {
            filename = "logo_" + getPackSanitizedName() + ".png";
        }
        return LocalPathManager.getLauncherMetadataFolderPath() + File.separator + filename;
    }

    private String getRawBackgroundFilePath() {
        String filename;
        if ( packBackgroundSha1 != null ) {
            filename = packBackgroundSha1 + ".png";
        }
        else {
            filename = "background_" + getPackSanitizedName() + ".png";
        }
        return LocalPathManager.getLauncherMetadataFolderPath() + File.separator + filename;
    }

    /**
     * Download the mod pack's logo from the URL specified in the manifest
     *
     * @throws ModpackException if unable to download/fetch logo
     */
    private synchronized void fetchLatestModpackLogo() throws ModpackException {
        // Download latest logo
        try {
            File syncFile = SynchronizedFileManager.getSynchronizedFile( getRawLogoFilePath() );
            boolean redownload = false;
            if ( packLogoSha1 == null ) {
                redownload = true;
            }
            else if ( !HashUtilities.verifySHA1( syncFile, packLogoSha1 ) ) {
                redownload = true;
            }
            if ( redownload ) {
                NetworkUtilities.downloadFileFromURL(
                        new URL( Objects.requireNonNullElse( packLogoURL, ModPackConstants.MODPACK_DEFAULT_LOGO_URL ) ),
                        syncFile );
            }
        }
        catch ( IOException e ) {
            throw new ModpackException( "Unable to download/fetch mod pack logo.", e );
        }
    }

    /**
     * Download the mod pack's logo from the URL specified in the manifest
     *
     * @throws ModpackException if unable to download/fetch logo
     */
    private synchronized void fetchLatestModpackBackground() throws ModpackException {
        // Download latest background
        try {
            File syncFile = SynchronizedFileManager.getSynchronizedFile( getRawBackgroundFilePath() );
            boolean redownload = false;
            if ( packBackgroundSha1 == null ) {
                redownload = true;
            }
            else if ( !HashUtilities.verifySHA1( syncFile, packBackgroundSha1 ) ) {
                redownload = true;
            }
            if ( redownload ) {
                NetworkUtilities.downloadFileFromURL( new URL(
                                                              Objects.requireNonNullElse( packBackgroundURL, ModPackConstants.MODPACK_DEFAULT_BG_URL ) ),
                                                      syncFile );
            }
        }
        catch ( IOException e ) {
            throw new ModpackException( "Unable to download/fetch mod pack background.", e );
        }
    }

    public synchronized void cacheImages() {
        try {
            fetchLatestModpackLogo();
            fetchLatestModpackBackground();
            didCacheImages = true;
        }
        catch ( Exception e ) {
            didCacheImages = false;
            Logger.logError( "Unable to download image assets for mod pack: " + getFriendlyName() );
            Logger.logThrowable( e );
        }
    }

    /**
     * Get and return the name of this modpack
     *
     * @return modpack name
     */
    public String getPackName() {
        return packName;
    }

    public void prepareEnvironment() {
        // Ensure local paths exist
        File binPath = SynchronizedFileManager.getSynchronizedFile( getPackRootFolder() + File.separator + "bin" );
        File modsPath = SynchronizedFileManager.getSynchronizedFile( getPackRootFolder() + File.separator + "mods" );
        File configPath = SynchronizedFileManager.getSynchronizedFile(
                getPackRootFolder() + File.separator + "config" );
        File nativePath = SynchronizedFileManager.getSynchronizedFile(
                getPackRootFolder() + File.separator + "bin" + File.separator + "natives" );
        File resPackPath = SynchronizedFileManager.getSynchronizedFile(
                getPackRootFolder() + File.separator + "resourcepacks" );
        File shaderPackPath = SynchronizedFileManager.getSynchronizedFile(
                getPackRootFolder() + File.separator + "shaderpacks" );

        //noinspection ResultOfMethodCallIgnored
        binPath.getParentFile().mkdirs();

        if ( !binPath.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            binPath.mkdir();
        }
        if ( !modsPath.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            modsPath.mkdir();
        }
        if ( !configPath.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            configPath.mkdir();
        }
        if ( !nativePath.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            nativePath.mkdir();
        }
        if ( !resPackPath.exists() && GameModeManager.isClient() ) {
            //noinspection ResultOfMethodCallIgnored
            resPackPath.mkdir();
        }
        if ( !shaderPackPath.exists() && GameModeManager.isClient() ) {
            //noinspection ResultOfMethodCallIgnored
            shaderPackPath.mkdir();
        }
    }

    String manifestUrl;

    public String getManifestUrl() {
        return manifestUrl;
    }

    public String getPackVersion() {
        return packVersion;
    }

    public boolean getPackUnstable() {
        return packUnstable;
    }

    public boolean getCustomDiscordRpc() {
        return packCustomDiscordRpc;
    }

    public String getPackURL() {
        return packURL;
    }

    public String getFriendlyName() {
        return getPackName() != null ?
               String.format( ModPackConstants.MODPACK_FRIENDLY_NAME_TEMPLATE, getPackName(), getPackVersion() ) :
               null;
    }

    public static GameModPack NULL_MODPACK() {
        GameModPack nullModPack = new GameModPack();
        nullModPack.packName = "No mod packs installed!";
        nullModPack.packVersion = "N/A";
        nullModPack.packLogoURL = GUIConstants.URL_MINECRAFT_NO_MOD_PACK_IMAGE;
        return nullModPack;
    }
}
