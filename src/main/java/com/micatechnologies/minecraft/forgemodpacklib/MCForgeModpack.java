package com.micatechnologies.minecraft.forgemodpacklib;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.io.FileUtils;

/**
 * Class representation of a Forge modpack with functionality to update mods, game libraries and
 * start the game.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class MCForgeModpack {

    /**
     * Modpack name
     * <p>
     * Read via Modpack Manifest JSON
     */
    private                 String                         packName;

    /**
     * Modpack version
     * <p>
     * Read via Modpack Manifest JSON
     */
    private                 String                         packVersion;

    /**
     * Modpack website URL
     * <p>
     * Read via Modpack Manifest JSON
     */
    private                 String                         packURL;

    /**
     * Modpack logo URL
     * <p>
     * Read via Modpack Manifest JSON
     */
    private                 String                         packLogoURL;

    /**
     * Modpack minimum RAM (GB)
     * <p>
     * Read via Modpack Manifest JSON
     */
    private                 String                         packMinRAMGB;

    /**
     * Modpack Forge download URL
     * <p>
     * Read via Modpack Manifest JSON
     */
    private                 String                         packForgeURL;

    /**
     * Modpack Forge download SHA-1 hash
     * <p>
     * Read via Modpack Manifest JSON
     */
    private                 String                         packForgeHash;

    /**
     * List of modpack Forge mods
     * <p>
     * Read via Modpack Manifest JSON
     */
    private                 List< MCForgeMod >             packMods;

    /**
     * List of modpack Forge configs
     * <p>
     * Read via Modpack Manifest JSON
     */
    private                 List< MCForgeAsset >           packConfigs;

    /**
     * List of modpack resource packs
     * <p>
     * Read via Modpack Manifest JSON
     */
    private                 List< MCRemoteFile >           packResourcePacks;

    /**
     * List of modpack shaders packs
     * <p>
     * Read via Modpack Manifest JSON
     */
    private                 List< MCRemoteFile >           packShaderPacks;

    /**
     * Modpack installation folder
     */
    private transient       Path                           packRootFolder;

    /**
     * Modpack game mode (Client/Server)
     */
    private transient       int                            appGameMode;

    private transient       MCForgeModpackProgressProvider progressProvider               = null;

    private final transient String                         APP_GARBAGE_COLLECTOR_SETTINGS = "-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:MaxGCPauseMillis=100 -XX:+DisableExplicitGC -XX:TargetSurvivorRatio=90 -XX:G1NewSizePercent=50 -XX:G1MaxNewSizePercent=80 -XX:G1MixedGCLiveThresholdPercent=35 -XX:+AlwaysPreTouch -XX:+ParallelRefProcEnabled ";

    /**
     * Get the installation folder of this modpack.
     *
     * @return installation folder Path
     */
    @SuppressWarnings( "WeakerAccess" )
    public Path getPackRootFolder() {
        return packRootFolder;
    }

    /**
     * Get a Forge application object referencing the Forge application jar in this modpack.
     *
     * @return modpack MCForgeApp
     *
     * @throws MCForgeModpackException if unable to get Forge app object
     */
    private MCForgeApp getForgeApp() throws MCForgeModpackException {
        return new MCForgeApp( packForgeURL, packForgeHash, getPackRootFolder().toString() );
    }

    /**
     * Get the Minecraft version of this modpack.
     *
     * @return modpack Minecraft version
     *
     * @throws MCForgeModpackException if unable to get Minecraft version
     */
    @SuppressWarnings( "WeakerAccess" )
    public String getMinecraftVersion() throws MCForgeModpackException {
        return getForgeApp().getMinecraftVersion();
    }

    /**
     * Get the Forge version of this modpack.
     *
     * @return modpack Forge version
     *
     * @throws MCForgeModpackException if unable to get Forge version
     */
    @SuppressWarnings( "WeakerAccess" )
    public String getForgeVersion() throws MCForgeModpackException {
        return getForgeApp().getForgeVersion();
    }

    /**
     * Get the Minecraft library manifest for this modpack.
     *
     * @return Minecraft library manifest
     *
     * @throws MCForgeModpackException if unable to get manifest
     */
    public MCLibraryManifest getMinecraftLibraryManifest() throws MCForgeModpackException {
        MCVersionManifest versionManifest = new MCVersionManifest( getPackRootFolder().toString() );
        return versionManifest.getMinecraftLibraryManifest( getMinecraftVersion() );
    }

    /**
     * Verify all local modpack files and downloads files if necessary, then compiles the classpath
     * String for running this modpack.
     *
     * @return modpack classpath String
     *
     * @throws MCForgeModpackException if unable to update modpack or build classpath
     * @since 1.0
     */
    public String buildModpackClasspath() throws MCForgeModpackException {
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
            progressProvider.startProgressSection( "Fetching shader packs", 7.0 );
        }
        fetchLatestShaderPacks();
        if ( progressProvider != null ) {
            progressProvider.endProgressSection( "Done fetching shader packs" );
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
        String forgeAssetClasspath = getForgeApp().buildForgeClasspath( appGameMode,
                                                                        progressProvider );
        if ( progressProvider != null ) {
            progressProvider.endProgressSection( "Done fetching Forge assets and classpath" );
        }

        // Verify local Minecraft assets and get classpath
        if ( progressProvider != null ) {
            progressProvider.startProgressSection(
                "Fetching Minecraft assets, libraries and classpath", 40.0 );
        }
        MCVersionManifest versionManifest = new MCVersionManifest( getPackRootFolder().toString() );
        MCLibraryManifest libraryManifest = versionManifest.getMinecraftLibraryManifest(
            getMinecraftVersion() );
        String minecraftAssetClasspath = libraryManifest.buildMinecraftClasspath( appGameMode,
                                                                                  progressProvider );
        if ( progressProvider != null ) {
            progressProvider.endProgressSection(
                "Done fetching Minecraft assets, libraries and classpath" );
        }

        // Add classpath separator between Forge and Minecraft only if Forge classpath not empty (it shouldn't be)
        if ( !forgeAssetClasspath.isEmpty() && !forgeAssetClasspath.endsWith(
            MCModpackOSUtils.getClasspathSeparator() ) ) {
            forgeAssetClasspath += MCModpackOSUtils.getClasspathSeparator();
        }

        return forgeAssetClasspath + minecraftAssetClasspath;
    }

    public void startGame( String javaPath, String accountFriendlyName, String accountIdentifier,
                           String accountAccessToken, int minRAMMB, int maxRAMMB )
        throws MCForgeModpackException {
        // Get classpath, main class and Minecraft args
        String cp = buildModpackClasspath();
        String minecraftArgs =
            appGameMode == MCForgeModpackConsts.MINECRAFT_CLIENT_MODE ? getForgeApp()
                .getMinecraftArguments() : "";
        String minecraftMainClass =
            appGameMode == MCForgeModpackConsts.MINECRAFT_CLIENT_MODE ? getForgeApp()
                .getMinecraftMainClass() : "net.minecraftforge.fml.relauncher.ServerLaunchWrapper";

        // Add main class to arguments
        minecraftArgs = minecraftMainClass + " " + minecraftArgs;

        // Add min and max RAM to arguments
        minecraftArgs = "-Xms" + minRAMMB + "m " + minecraftArgs;
        minecraftArgs = "-Xmx" + maxRAMMB + "m " + minecraftArgs;

        // Add garbage collection config to arguments
        minecraftArgs = APP_GARBAGE_COLLECTOR_SETTINGS + minecraftArgs;

        // Add classpath to arguments
        minecraftArgs = "-cp " + cp + " " + minecraftArgs;

        // Add natives path to arguments
        minecraftArgs =
            "-Djava.library.path=" + getPackRootFolder() + MCModpackOSUtils.getFileSeparator()
                + MCForgeModpackConsts.MODPACK_MINECRAFT_NATIVES_LOCAL_FOLDER + " " + minecraftArgs;

        // Replace fillers with data
        if ( appGameMode == MCForgeModpackConsts.MINECRAFT_CLIENT_MODE ) {
            minecraftArgs = minecraftArgs.replace( "${auth_player_name}", accountFriendlyName );
            minecraftArgs = minecraftArgs.replace( "${version_name}", getForgeVersion() );
            minecraftArgs = minecraftArgs.replace( "${game_directory}",
                                                   getPackRootFolder().toString() );
            minecraftArgs = minecraftArgs.replace( "${assets_root}",
                                                   getPackRootFolder().toString() + MCModpackOSUtils
                                                       .getFileSeparator()
                                                       + MCForgeModpackConsts.MODPACK_MINECRAFT_ASSETS_LOCAL_FOLDER );
            minecraftArgs = minecraftArgs.replace( "${assets_index_name}",
                                                   getMinecraftLibraryManifest()
                                                       .getAssetIndexVersion() );
            minecraftArgs = minecraftArgs.replace( "${auth_uuid}", accountIdentifier );
            minecraftArgs = minecraftArgs.replace( "${auth_access_token}", accountAccessToken );
            minecraftArgs = minecraftArgs.replace( "${user_type}", "mojang" );

            // Add title and icon to arguments
            minecraftArgs += " --title " + packName;
            minecraftArgs += " --icon " + getPackLogoFilepath();
        }

        // Add java call to front of args
        minecraftArgs = javaPath + " " + minecraftArgs;

        // Start game
        try {
            MCModpackOSUtils.executeStringCommand( minecraftArgs, getPackRootFolder().toString() );
        }
        catch ( IOException | InterruptedException e ) {
            throw new MCForgeModpackException( "Unable to execute modpack game.", e );
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
            getPackRootFolder().toString() + MCModpackOSUtils.getFileSeparator()
                + MCForgeModpackConsts.MODPACK_FORGE_MODS_LOCAL_FOLDER;

        // Build list of valid mods
        ArrayList< String > validModPaths = new ArrayList<>();
        for ( MCForgeMod mod : packMods ) {
            mod.setLocalPathPrefix( modLocalPathPrefix );
            validModPaths.add( mod.getFullLocalFilePath() );
        }

        // Loop through files in mods folder and remove unwanted
        File modpackModsFolderFile = new File( modLocalPathPrefix );
        File[] modsFolderFiles = modpackModsFolderFile.listFiles();
        if ( modsFolderFiles != null ) {
            for ( File modFile : modsFolderFiles ) {
                if ( !validModPaths.contains( modFile.getPath() ) ) {
                    modFile.delete();
                }
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this modpack's mods and repair/download/update as
     * necessary.
     *
     * @throws MCForgeModpackException if unable to fetch latest mods
     */
    private void fetchLatestMods() throws MCForgeModpackException {
        // Cleanup mods that don't belong
        clearFloatingMods();
        if ( progressProvider != null ) {
            progressProvider.submitProgress( "Removed floating mods", 30.0 );
        }

        // Get full path to mods folder
        String modLocalPathPrefix =
            getPackRootFolder().toString() + MCModpackOSUtils.getFileSeparator()
                + MCForgeModpackConsts.MODPACK_FORGE_MODS_LOCAL_FOLDER;

        // Update each mod if not already fully downloaded
        for ( MCForgeMod mod : packMods ) {
            mod.setLocalPathPrefix( modLocalPathPrefix );
            mod.updateLocalFile( appGameMode );

            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Verified " + mod.name,
                                                 ( 70.0 / ( double ) packMods.size() ) );
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this modpack's configs and repair/download/update
     * as necessary.
     *
     * @throws MCForgeModpackException if unable to fetch latest configs
     */
    private void fetchLatestConfigs() throws MCForgeModpackException {
        // Get full path to configs folder
        String configLocalPathPrefix =
            getPackRootFolder().toString() + MCModpackOSUtils.getFileSeparator()
                + MCForgeModpackConsts.MODPACK_FORGE_CONFIGS_LOCAL_FOLDER;

        // Update each config if necessary
        for ( MCForgeAsset config : packConfigs ) {
            config.setLocalPathPrefix( configLocalPathPrefix );
            config.updateLocalFile( appGameMode );

            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Verified " + config.getLocalFilePath(),
                                                 ( 100.0 / ( double ) packConfigs.size() ) );
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this modpack's resource packs and
     * repair/download/update as necessary.
     *
     * @throws MCForgeModpackException if unable to fetch latest resource packs
     */
    private void fetchLatestResourcePacks() throws MCForgeModpackException {
        // Only download resource packs on client (not server)
        if ( appGameMode == MCForgeModpackConsts.MINECRAFT_SERVER_MODE ) {
            return;
        }

        // Get full path to resource pack folder
        String resPackLocalPathPrefix =
            getPackRootFolder().toString() + MCModpackOSUtils.getFileSeparator()
                + MCForgeModpackConsts.MODPACK_FORGE_RESOURCEPACKS_LOCAL_FOLDER;

        // Update each resource pack if necessary
        for ( MCRemoteFile resourcePack : packResourcePacks ) {
            resourcePack.setLocalPathPrefix( resPackLocalPathPrefix );
            resourcePack.updateLocalFile();
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Verified " + resourcePack.getLocalFilePath(),
                                                 ( 100.0 / ( double ) packResourcePacks.size() ) );
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this modpack's shader packs and
     * repair/download/update as necessary.
     *
     * @throws MCForgeModpackException if unable to fetch latest shader packs
     */
    private void fetchLatestShaderPacks() throws MCForgeModpackException {
        // Only download shader packs on client (not server)
        if ( appGameMode == MCForgeModpackConsts.MINECRAFT_SERVER_MODE ) {
            return;
        }

        // Get full path to shader pack folder
        String shaderPackLocalPathPrefix =
            getPackRootFolder().toString() + MCModpackOSUtils.getFileSeparator()
                + MCForgeModpackConsts.MODPACK_FORGE_SHADERPACKS_LOCAL_FOLDER;

        // Update each shader pack if necessary
        for ( MCRemoteFile shaderPack : packResourcePacks ) {
            shaderPack.setLocalPathPrefix( shaderPackLocalPathPrefix );
            shaderPack.updateLocalFile();
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Verified " + shaderPack.getLocalFilePath(),
                                                 ( 100.0 / ( double ) packShaderPacks.size() ) );
            }
        }
    }

    String getPackLogoFilepath() {
        return getPackRootFolder().toString() + MCModpackOSUtils.getFileSeparator()
            + MCForgeModpackConsts.MODPACK_LOGO_LOCAL_FILE;
    }

    public void setProgressProvider( MCForgeModpackProgressProvider progressProvider ) {
        this.progressProvider = progressProvider;
    }

    /**
     * Download the modpack's logo from the URL specified in the manifest
     *
     * @throws MCForgeModpackException if unable to download/fetch logo
     */
    private void fetchLatestModpackLogo() throws MCForgeModpackException {
        // Only download logo on client (not server)
        if ( appGameMode == MCForgeModpackConsts.MINECRAFT_SERVER_MODE ) {
            return;
        }

        // Download latest logo
        try {
            FileUtils.copyURLToFile( new URL( packLogoURL ), new File( getPackLogoFilepath() ) );
            if ( progressProvider != null ) {
                progressProvider.submitProgress( "Verified modpack logo", 100.0 );
            }
        }
        catch ( IOException e ) {
            throw new MCForgeModpackException( "Unable to download/fetch modpack logo.", e );
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

    /**
     * Download the modpack manifest from the specified URL to the specified modpack folder for the
     * selected game mode (server/client).
     *
     * @param downloadURL       manifest URL
     * @param modpackRootFolder modpack root folder
     * @param appGameMode       client/server
     *
     * @return downloaded modpack manifest
     *
     * @throws MCForgeModpackException if unable to download
     */
    public static MCForgeModpack downloadFromURL( URL downloadURL, Path modpackRootFolder,
                                                  int appGameMode ) throws MCForgeModpackException {
        // Create file for downloading manifest
        File modpackManifestFile = new File(
            modpackRootFolder.toString() + MCModpackOSUtils.getFileSeparator()
                + MCForgeModpackConsts.MODPACK_MANIFEST_LOCAL_PATH );

        // Ensure local paths exist
        File binPath = new File( modpackRootFolder + MCModpackOSUtils.getFileSeparator() + "bin" );
        File modsPath = new File(
            modpackRootFolder + MCModpackOSUtils.getFileSeparator() + "mods" );
        File configPath = new File(
            modpackRootFolder + MCModpackOSUtils.getFileSeparator() + "config" );
        File nativePath = new File(
            modpackRootFolder + MCModpackOSUtils.getFileSeparator() + "bin" + MCModpackOSUtils
                .getFileSeparator() + "natives" );
        File resPackPath = new File(
            modpackRootFolder + MCModpackOSUtils.getFileSeparator() + "resourcepacks" );
        File shaderPackPath = new File(
            modpackRootFolder + MCModpackOSUtils.getFileSeparator() + "shaderpacks" );

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
        if ( !resPackPath.exists() && appGameMode == MCForgeModpackConsts.MINECRAFT_CLIENT_MODE ) {
            resPackPath.mkdir();
        }
        if ( !shaderPackPath.exists()
            && appGameMode == MCForgeModpackConsts.MINECRAFT_CLIENT_MODE ) {
            shaderPackPath.mkdir();
        }

        // Download manifest from supplied URL
        try {
            FileUtils.copyURLToFile( downloadURL, modpackManifestFile );
        }
        catch ( IOException e ) {
            throw new MCForgeModpackException( "Unable to download manifest from URL.", e );
        }

        // Read downloaded manifest into object and return
        BufferedReader bufferedReader;
        try {
            bufferedReader = new BufferedReader( new FileReader( modpackManifestFile ) );
            MCForgeModpack createdModpackManifest = new Gson().fromJson( bufferedReader,
                                                                         MCForgeModpack.class );
            createdModpackManifest.packRootFolder = modpackRootFolder;
            createdModpackManifest.appGameMode = appGameMode;
            return createdModpackManifest;
        }
        catch ( FileNotFoundException e ) {
            throw new MCForgeModpackException( "Unable to parse downloaded manifest.", e );
        }
    }
}
