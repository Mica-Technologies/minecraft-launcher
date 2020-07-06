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

package com.micatechnologies.minecraft.forgelauncher.game.modpack;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import com.micatechnologies.minecraft.forgelauncher.config.ConfigManager;
import com.micatechnologies.minecraft.forgelauncher.config.GameModeManager;
import com.micatechnologies.minecraft.forgelauncher.consts.ModPackConstants;
import com.micatechnologies.minecraft.forgelauncher.exceptions.ModpackException;
import com.micatechnologies.minecraft.forgelauncher.files.LocalPathManager;
import com.micatechnologies.minecraft.forgelauncher.files.Logger;
import com.micatechnologies.minecraft.forgelauncher.files.RuntimeManager;
import com.micatechnologies.minecraft.forgelauncher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.forgelauncher.game.auth.AuthManager;
import com.micatechnologies.minecraft.forgelauncher.game.modpack.manifests.GameLibraryManifest;
import com.micatechnologies.minecraft.forgelauncher.game.modpack.manifests.GameVersionManifest;
import com.micatechnologies.minecraft.forgelauncher.utilities.NetworkUtilities;
import com.micatechnologies.minecraft.forgelauncher.utilities.SystemUtilities;
import org.apache.commons.io.FilenameUtils;

/**
 * Class representation of a Forge mod pack with functionality to update mods, game libraries and start the game.
 *
 * @author Mica Technologies/hawka97
 * @version 1.1
 */
public class GameModPack
{

    /**
     * Mod pack name. Value read from manifest JSON.
     *
     * @since 1.0
     */
    private String packName;

    /**
     * Mod pack version. Value read from manifest JSON.
     *
     * @since 1.0
     */
    private String packVersion;

    /**
     * Mod pack website URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    private String packURL;

    /**
     * Mod pack logo URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    private String packLogoURL;

    /**
     * Mod pack background URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    private String packBackgroundURL;

    /**
     * Mod pack minimum RAM (GB). Value read from manifest JSON.
     *
     * @since 1.0
     */
    private String packMinRAMGB;

    /**
     * Mod pack Forge download URL. Value read from manifest JSON.
     *
     * @since 1.0
     */
    private String packForgeURL;

    /**
     * Mod pack Forge download SHA-1 hash. Value read from manifest JSON.
     *
     * @since 1.0
     */
    private String packForgeHash;

    /**
     * List of mod pack Forge mods. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "MismatchedQueryAndUpdateOfCollection" )
    private List< GameMod > packMods;

    /**
     * List of mod pack Forge configs. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "MismatchedQueryAndUpdateOfCollection" )
    private List< GameAsset > packConfigs;

    /**
     * List of mod pack resource packs. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "MismatchedQueryAndUpdateOfCollection" )
    private List< ManagedGameFile > packResourcePacks;

    /**
     * List of mod pack shader packs. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "MismatchedQueryAndUpdateOfCollection" )
    private List< ManagedGameFile > packShaderPacks;

    /**
     * List of initial files for mod pack. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings( "MismatchedQueryAndUpdateOfCollection" )
    private List< GameAsset > packInitialFiles;

    private transient GameModPackProgressProvider progressProvider = null;


    /**
     * Get the installation folder of this mod pack.
     *
     * @return installation folder Path
     */
    @SuppressWarnings( "WeakerAccess" )
    public String getPackRootFolder() {
        final String sanitizedModPackName = getPackName().replaceAll( "[^a-zA-Z0-9]", "" );
        return LocalPathManager.getLauncherModpackFolderPath() + File.separator + sanitizedModPackName;
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
     * Get the URL to the modpack logo
     *
     * @return modpack logo URL
     */
    public String getPackLogoURL() {
        return packLogoURL;
    }

    /**
     * Get the URL to the modpack background
     *
     * @return modpack background URL
     */
    public String getPackBackgroundURL() {
        if ( packBackgroundURL != null && !packBackgroundURL.equals( "" ) ) {
            return packBackgroundURL;
        }
        else {
            return ModPackConstants.MODPACK_DEFAULT_BG_URL;
        }
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
            progressProvider.setCurrText( "Building modpack classpath enviornment" );
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
            progressProvider.startProgressSection( "Fetching modpack logo", 1.0 );
        }
        fetchLatestModpackLogo();
        if ( progressProvider != null ) {
            progressProvider.endProgressSection( "Done fetching modpack logo" );
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
            progressProvider.startProgressSection(
                    "Fetching Minecraft assets, libraries and classpath", 40.0 );
        }
        GameLibraryManifest libraryManifest = GameVersionManifest.getMinecraftLibraryManifest(
                getMinecraftVersion(), this );
        String minecraftAssetClasspath = libraryManifest.buildMinecraftClasspath( GameModeManager.getCurrentGameMode(),
                                                                                  progressProvider );
        if ( progressProvider != null ) {
            progressProvider.endProgressSection(
                    "Done fetching Minecraft assets, libraries and classpath" );
        }

        // Add classpath separator between Forge and Minecraft only if Forge classpath not empty (it shouldn't be)
        if ( !forgeAssetClasspath.isEmpty() && !forgeAssetClasspath.endsWith(
                File.pathSeparator ) ) {
            forgeAssetClasspath += File.pathSeparator;
        }

        return forgeAssetClasspath + minecraftAssetClasspath;
    }

    public void startGame()
    throws ModpackException
    {
        // Get classpath, main class and Minecraft args
        String cp = buildModpackClasspath();
        String minecraftArgs =
                GameModeManager.isClient() ? getForgeApp()
                        .getMinecraftArguments() : "";
        String minecraftMainClass =
                GameModeManager.isClient() ? getForgeApp()
                        .getMinecraftMainClass() : "net.minecraftforge.fml.relauncher.ServerLaunchWrapper";

        // Add main class to arguments
        minecraftArgs = minecraftMainClass + " " + minecraftArgs;

        // Add min and max RAM to arguments
        long SminRAMMB = ConfigManager.getMinRam();
        long SmaxRAMMB = ConfigManager.getMinRam();
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

        // Add garbage collection config to arguments for client
        if ( GameModeManager.isClient() ) {
            minecraftArgs = ModPackConstants.APP_GARBAGE_COLLECTOR_SETTINGS + minecraftArgs;
        }

        // Add classpath to arguments
        if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
            minecraftArgs = "-cp \"" + cp + "\" " + minecraftArgs;
        }
        else {
            minecraftArgs = "-cp " + cp + " " + minecraftArgs;
        }

        // Add natives path to arguments
        if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
            minecraftArgs =
                    "-Djava.library.path=\"" + getPackRootFolder() + File.separator
                            + ModPackConstants.MODPACK_MINECRAFT_NATIVES_LOCAL_FOLDER + "\" " + minecraftArgs;
        }
        else {
            minecraftArgs =
                    "-Djava.library.path=" + getPackRootFolder() + File.separator
                            + ModPackConstants.MODPACK_MINECRAFT_NATIVES_LOCAL_FOLDER + " " + minecraftArgs;
        }

        // Replace fillers with data
        if ( GameModeManager.isClient() ) {
            minecraftArgs =
                    minecraftArgs.replace( "${auth_player_name}", AuthManager.getLoggedInAccount().getFriendlyName() );
            minecraftArgs = minecraftArgs.replace( "${version_name}", getForgeVersion() );
            if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
                minecraftArgs = minecraftArgs.replace( "${game_directory}",
                                                       "\"" + getPackRootFolder() + "\"" );
                minecraftArgs = minecraftArgs.replace( "${assets_root}",
                                                       "\"" + getPackRootFolder() + File.separator
                                                               +
                                                               ModPackConstants.MODPACK_MINECRAFT_ASSETS_LOCAL_FOLDER +
                                                               "\"" );
            }
            else {
                minecraftArgs = minecraftArgs.replace( "${game_directory}",
                                                       getPackRootFolder() );
                minecraftArgs = minecraftArgs.replace( "${assets_root}",
                                                       getPackRootFolder() + File.separator
                                                               +
                                                               ModPackConstants.MODPACK_MINECRAFT_ASSETS_LOCAL_FOLDER );
            }

            minecraftArgs = minecraftArgs.replace( "${assets_index_name}",
                                                   getMinecraftLibraryManifest()
                                                           .getAssetIndexVersion() );
            minecraftArgs =
                    minecraftArgs.replace( "${auth_uuid}", AuthManager.getLoggedInAccount().getUserIdentifier() );
            minecraftArgs = minecraftArgs
                    .replace( "${auth_access_token}", AuthManager.getLoggedInAccount().getLastAccessToken() );
            minecraftArgs = minecraftArgs.replace( "${user_type}", "mojang" );

            // Add title and icon to arguments
            minecraftArgs += " --title " + packName;
            minecraftArgs += " --icon " + getPackLogoFilepath();

            // Set dock name and icon for macOS
            if ( org.apache.commons.lang3.SystemUtils.IS_OS_MAC ) {
                minecraftArgs += " -Xdock:icon=\"" + getPackLogoFilepath() + "\"";
                minecraftArgs += " -Xdock:name=\"" + getPackName() + "\" ";
                minecraftArgs += "-Dapple.laf.useScreenMenuBar=true ";
            }
        }

        // Add java call to front of args
        minecraftArgs = RuntimeManager.getJre8Path() + " " + minecraftArgs;

        // Start game
        try {
            SystemUtilities.executeStringCommand( minecraftArgs, getPackRootFolder() );
        }
        catch ( IOException | InterruptedException e ) {
            throw new ModpackException( "Unable to execute modpack game.", e );
        }
    }

    /**
     * Remove all mods from this modpack mods folder that are not in the modpack.
     *
     * @since 1.0
     */
    private void clearFloatingMods() {
        // Get full path to modpack mods folder
        String modLocalPathPrefix =
                getPackRootFolder().toString() + File.separator
                        + ModPackConstants.MODPACK_FORGE_MODS_LOCAL_FOLDER;

        // Build list of valid mods
        ArrayList< String > validModPaths = new ArrayList<>();
        for ( GameMod mod : packMods ) {
            mod.setLocalPathPrefix( modLocalPathPrefix );
            validModPaths.add( mod.getFullLocalFilePath() );
        }

        // Loop through files in mods folder and remove unwanted
        File modpackModsFolderFile = SynchronizedFileManager.getSynchronizedFile( modLocalPathPrefix );
        File[] modsFolderFiles = modpackModsFolderFile.listFiles();
        if ( modsFolderFiles != null ) {
            for ( File modFile : modsFolderFiles ) {
                if ( !validModPaths.contains( modFile.getPath() ) ) {
                    boolean delete = modFile.delete();
                    if ( !delete ) {
                        Logger.logError( "Unable to delete file during mod folder sanitization." );
                    }
                }
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this modpack's mods and repair/download/update as necessary.
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
        String modLocalPathPrefix =
                getPackRootFolder().toString() + File.separator
                        + ModPackConstants.MODPACK_FORGE_MODS_LOCAL_FOLDER;

        // Update each mod if not already fully downloaded
        for ( GameMod mod : packMods ) {
            mod.setLocalPathPrefix( modLocalPathPrefix );
            mod.updateLocalFile( GameModeManager.getCurrentGameMode() );

            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Verified " + mod.name,
                                                 ( 70.0 / ( double ) packMods.size() ) );
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this modpack's configs and repair/download/update as necessary.
     *
     * @throws ModpackException if unable to fetch latest configs
     */
    private void fetchLatestConfigs() throws ModpackException {
        // Get full path to configs folder
        String configLocalPathPrefix =
                getPackRootFolder().toString() + File.separator
                        + ModPackConstants.MODPACK_FORGE_CONFIGS_LOCAL_FOLDER;

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
     * Verifies the integrity of local copies of this modpack's resource packs and repair/download/update as necessary.
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
        String resPackLocalPathPrefix =
                getPackRootFolder().toString() + File.separator
                        + ModPackConstants.MODPACK_FORGE_RESOURCEPACKS_LOCAL_FOLDER;

        // Update each resource pack if necessary
        for ( ManagedGameFile resourcePack : packResourcePacks ) {
            resourcePack.setLocalPathPrefix( resPackLocalPathPrefix );
            resourcePack.updateLocalFile();
            if ( progressProvider != null ) {
                progressProvider
                        .submitProgress( "Verified " + FilenameUtils.getName( resourcePack.getFullLocalFilePath() ),
                                         ( 100.0 / ( double ) packResourcePacks.size() ) );
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this modpack's shader packs and repair/download/update as necessary.
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
        String shaderPackLocalPathPrefix =
                getPackRootFolder().toString() + File.separator
                        + ModPackConstants.MODPACK_FORGE_SHADERPACKS_LOCAL_FOLDER;

        // Update each shader pack if necessary
        for ( ManagedGameFile shaderPack : packShaderPacks ) {
            shaderPack.setLocalPathPrefix( shaderPackLocalPathPrefix );
            shaderPack.updateLocalFile();
            if ( progressProvider != null ) {
                progressProvider
                        .submitProgress( "Verified " + FilenameUtils.getName( shaderPack.getFullLocalFilePath() ),
                                         ( 100.0 / ( double ) packShaderPacks.size() ) );
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this modpack's initial files and repair/download/updated as necessary.
     *
     * @throws ModpackException if unable to fetch latest initial files
     */
    private void fetchLatestInitialFiles() throws ModpackException {
        // Get full path to configs folder
        String initFilesLocalPathPrefix =
                getPackRootFolder().toString();

        // Check if initial files supplied
        if ( packInitialFiles == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "No configs to handle", 100 );
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

    String getPackLogoFilepath() {
        return getPackRootFolder().toString() + File.separator
                + ModPackConstants.MODPACK_LOGO_LOCAL_FILE;
    }

    public void setProgressProvider( GameModPackProgressProvider progressProvider ) {
        this.progressProvider = progressProvider;
    }

    /**
     * Download the modpack's logo from the URL specified in the manifest
     *
     * @throws ModpackException if unable to download/fetch logo
     */
    private void fetchLatestModpackLogo() throws ModpackException {
        // Only download logo on client (not server)
        if ( GameModeManager.isServer() ) {
            return;
        }

        // Download latest logo
        try {
            NetworkUtilities.downloadFileFromURL( new URL( packLogoURL ),
                                                  SynchronizedFileManager
                                                          .getSynchronizedFile( getPackLogoFilepath() ) );
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Verified modpack logo", 100.0 );
            }
        }
        catch ( IOException e ) {
            throw new ModpackException( "Unable to download/fetch modpack logo.", e );
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
        File modsPath = SynchronizedFileManager.getSynchronizedFile(
                getPackRootFolder() + File.separator + "mods" );
        File configPath = SynchronizedFileManager.getSynchronizedFile(
                getPackRootFolder() + File.separator + "config" );
        File nativePath = SynchronizedFileManager.getSynchronizedFile(
                getPackRootFolder() + File.separator + "bin" + File.separator + "natives" );
        File resPackPath = SynchronizedFileManager.getSynchronizedFile(
                getPackRootFolder() + File.separator + "resourcepacks" );
        File shaderPackPath = SynchronizedFileManager.getSynchronizedFile(
                getPackRootFolder() + File.separator + "shaderpacks" );

        binPath.getParentFile().mkdirs();

        if ( !binPath.exists() ) {
            binPath.mkdir();
        }
        if ( !modsPath.exists() ) {
            modsPath.mkdir();
        }
        if ( !configPath.exists() ) {
            configPath.mkdir();
        }
        if ( !nativePath.exists() ) {
            nativePath.mkdir();
        }
        if ( !resPackPath.exists() && GameModeManager.isClient() ) {
            resPackPath.mkdir();
        }
        if ( !shaderPackPath.exists() && GameModeManager.isClient() ) {
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

    public String getPackURL() {
        return packURL;
    }

    public String getFriendlyName() {
        return String.format( ModPackConstants.MODPACK_FRIENDLY_NAME_TEMPLATE, getPackName(), getPackVersion() );
    }
}
