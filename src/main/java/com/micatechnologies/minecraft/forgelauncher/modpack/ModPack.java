package com.micatechnologies.minecraft.forgelauncher.modpack;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.micatechnologies.minecraft.forgelauncher.LauncherApp;
import com.micatechnologies.minecraft.forgelauncher.exceptions.FLModpackException;
import com.micatechnologies.minecraft.forgelauncher.game.GameMode;
import com.micatechnologies.minecraft.forgelauncher.utilities.Logger;
import com.micatechnologies.minecraft.forgelauncher.utilities.SystemUtils;
import org.apache.commons.io.FilenameUtils;

/**
 * Class representation of a Forge mod pack with functionality to update mods, game libraries and start the game.
 *
 * @author Mica Technologies/hawka97
 * @version 1.1
 */
public class ModPack {

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
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    private List<MCForgeMod> packMods;

    /**
     * List of mod pack Forge configs. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    private List<MCForgeAsset> packConfigs;

    /**
     * List of mod pack resource packs. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    private List<MCRemoteFile> packResourcePacks;

    /**
     * List of mod pack shader packs. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    private List<MCRemoteFile> packShaderPacks;

    /**
     * List of initial files for mod pack. Value read from manifest JSON.
     *
     * @since 1.0
     */
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    private List<MCForgeAsset> packInitialFiles;

    private transient MCForgeModpackProgressProvider progressProvider = null;



    /**
     * Get the installation folder of this mod pack.
     *
     * @return installation folder Path
     */
    @SuppressWarnings("WeakerAccess")
    public String getPackRootFolder() {
        final String sanitizedModPackName = getPackName().replaceAll("[^a-zA-Z0-9]", "");
        return LauncherApp.getModpacksInstallPath() + File.separator + sanitizedModPackName;
    }

    /**
     * Get a Forge application object referencing the Forge application jar in this modpack.
     *
     * @return mod pack MCForgeApp
     * @throws FLModpackException if unable to get Forge app object
     */
    private MCForgeApp getForgeApp() throws FLModpackException {
        return new MCForgeApp(packForgeURL, packForgeHash, getPackRootFolder().toString());
    }

    /**
     * Get the Minecraft version of this modpack.
     *
     * @return modpack Minecraft version
     * @throws FLModpackException if unable to get Minecraft version
     */
    @SuppressWarnings("WeakerAccess")
    public String getMinecraftVersion() throws FLModpackException {
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
        if (packBackgroundURL != null && !packBackgroundURL.equals("")) return packBackgroundURL;
        else return MCForgeModpackConsts.MODPACK_DEFAULT_BG_URL;
    }

    /**
     * Get the Forge version of this modpack.
     *
     * @return modpack Forge version
     * @throws FLModpackException if unable to get Forge version
     */
    @SuppressWarnings("WeakerAccess")
    public String getForgeVersion() throws FLModpackException {
        return getForgeApp().getForgeVersion();
    }

    public String getPackMinRAMGB() {
        return packMinRAMGB;
    }

    /**
     * Get the Minecraft library manifest for this modpack.
     *
     * @return Minecraft library manifest
     * @throws FLModpackException if unable to get manifest
     */
    public MCLibraryManifest getMinecraftLibraryManifest() throws FLModpackException {
        MCVersionManifest versionManifest = new MCVersionManifest(getPackRootFolder());
        return versionManifest.getMinecraftLibraryManifest(getMinecraftVersion());
    }

    /**
     * Verify all local modpack files and downloads files if necessary, then compiles the classpath String for running
     * this modpack.
     *
     * @return modpack classpath String
     * @throws FLModpackException if unable to update modpack or build classpath
     * @since 1.0
     */
    public String buildModpackClasspath() throws FLModpackException {
        if (progressProvider != null) {
            progressProvider.setCurrText("Building modpack classpath enviornment");
        }

        // Verify local Forge mods
        if (progressProvider != null) {
            progressProvider.startProgressSection("Fetching mods", 20.0);
        }
        fetchLatestMods();
        if (progressProvider != null) {
            progressProvider.endProgressSection("Done fetching mods");
        }

        // Verify local Forge configs
        if (progressProvider != null) {
            progressProvider.startProgressSection("Fetching configs", 5.0);
        }
        fetchLatestConfigs();
        if (progressProvider != null) {
            progressProvider.endProgressSection("Done fetching configs");
        }

        // Verify local resource packs
        if (progressProvider != null) {
            progressProvider.startProgressSection("Fetching resource packs", 7.0);
        }
        fetchLatestResourcePacks();
        if (progressProvider != null) {
            progressProvider.endProgressSection("Done fetching resource packs");
        }

        // Verify local shader packs
        if (progressProvider != null) {
            progressProvider.startProgressSection("Fetching shader packs", 5.0);
        }
        fetchLatestShaderPacks();
        if (progressProvider != null) {
            progressProvider.endProgressSection("Done fetching shader packs");
        }

        // Verify local initial files
        if (progressProvider != null) {
            progressProvider.startProgressSection("Fetching initial files", 2.0);
        }
        fetchLatestInitialFiles();
        if (progressProvider != null) {
            progressProvider.endProgressSection("Done fetching initial files");
        }

        // Verify modpack logo
        if (progressProvider != null) {
            progressProvider.startProgressSection("Fetching modpack logo", 1.0);
        }
        fetchLatestModpackLogo();
        if (progressProvider != null) {
            progressProvider.endProgressSection("Done fetching modpack logo");
        }

        // Verify local Forge assets and get classpath
        if (progressProvider != null) {
            progressProvider.startProgressSection("Fetching Forge assets and classpath", 20.0);
        }
        String forgeAssetClasspath = getForgeApp().buildForgeClasspath(LauncherApp.getMode(),
                progressProvider);
        if (progressProvider != null) {
            progressProvider.endProgressSection("Done fetching Forge assets and classpath");
        }

        // Verify local Minecraft assets and get classpath
        if (progressProvider != null) {
            progressProvider.startProgressSection(
                    "Fetching Minecraft assets, libraries and classpath", 40.0);
        }
        MCVersionManifest versionManifest = new MCVersionManifest(getPackRootFolder());
        MCLibraryManifest libraryManifest = versionManifest.getMinecraftLibraryManifest(
                getMinecraftVersion());
        String minecraftAssetClasspath = libraryManifest.buildMinecraftClasspath(LauncherApp.getMode(),
                progressProvider);
        if (progressProvider != null) {
            progressProvider.endProgressSection(
                    "Done fetching Minecraft assets, libraries and classpath");
        }

        // Add classpath separator between Forge and Minecraft only if Forge classpath not empty (it shouldn't be)
        if (!forgeAssetClasspath.isEmpty() && !forgeAssetClasspath.endsWith(
                SystemUtils.getClasspathSeparator())) {
            forgeAssetClasspath += SystemUtils.getClasspathSeparator();
        }

        return forgeAssetClasspath + minecraftAssetClasspath;
    }

    public void startGame(String javaPath, String accountFriendlyName, String accountIdentifier,
                          String accountAccessToken, int minRAMMB, int maxRAMMB)
            throws FLModpackException {
        // Get classpath, main class and Minecraft args
        String cp = buildModpackClasspath();
        String minecraftArgs =
                LauncherApp.getMode() == GameMode.CLIENT ? getForgeApp()
                        .getMinecraftArguments() : "";
        String minecraftMainClass =
                LauncherApp.getMode() == GameMode.CLIENT ? getForgeApp()
                        .getMinecraftMainClass() : "net.minecraftforge.fml.relauncher.ServerLaunchWrapper";

        // Add main class to arguments
        minecraftArgs = minecraftMainClass + " " + minecraftArgs;

        // Add min and max RAM to arguments
        int SminRAMMB = minRAMMB;
        int SmaxRAMMB = maxRAMMB;
        if (LauncherApp.getMode() == GameMode.SERVER) {
            // Get min and max RAM from existing JVM args
            RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
            List<String> aList = bean.getInputArguments();

            for (String s : aList) {
                System.out.println(s);
                if (s.contains("Xms")) {
                    SminRAMMB = Integer.parseInt(s.replaceAll("\\D+", ""));
                    System.out.println("Configuring min RAM from provided " + s);
                }
                if (s.contains("Xmx")) {
                    SmaxRAMMB = Integer.parseInt(s.replaceAll("\\D+", ""));
                    System.out.println("Configuring max RAM from provided " + s);
                }
            }

        }
        minecraftArgs = "-Xms" + SminRAMMB + "m " + minecraftArgs;
        minecraftArgs = "-Xmx" + SmaxRAMMB + "m " + minecraftArgs;

        // Add garbage collection config to arguments for client
        if (LauncherApp.getMode() == GameMode.CLIENT)
            minecraftArgs = ModPackConstants.APP_GARBAGE_COLLECTOR_SETTINGS + minecraftArgs;

        // Add classpath to arguments
        if (org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS) minecraftArgs = "-cp \"" + cp + "\" " + minecraftArgs;
        else minecraftArgs = "-cp " + cp + " " + minecraftArgs;

        // Add natives path to arguments
        if (org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS) minecraftArgs =
                "-Djava.library.path=\"" + getPackRootFolder() + File.separator
                        + MCForgeModpackConsts.MODPACK_MINECRAFT_NATIVES_LOCAL_FOLDER + "\" " + minecraftArgs;
        else minecraftArgs =
                "-Djava.library.path=" + getPackRootFolder() + File.separator
                        + MCForgeModpackConsts.MODPACK_MINECRAFT_NATIVES_LOCAL_FOLDER + " " + minecraftArgs;

        // Replace fillers with data
        if (LauncherApp.getMode() == GameMode.CLIENT) {
            minecraftArgs = minecraftArgs.replace("${auth_player_name}", accountFriendlyName);
            minecraftArgs = minecraftArgs.replace("${version_name}", getForgeVersion());
            if (org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS) {
                minecraftArgs = minecraftArgs.replace("${game_directory}",
                        "\"" + getPackRootFolder().toString() + "\"");
                minecraftArgs = minecraftArgs.replace("${assets_root}",
                        "\"" + getPackRootFolder().toString() + File.separator
                                + MCForgeModpackConsts.MODPACK_MINECRAFT_ASSETS_LOCAL_FOLDER + "\"");
            } else {
                minecraftArgs = minecraftArgs.replace("${game_directory}",
                        getPackRootFolder().toString());
                minecraftArgs = minecraftArgs.replace("${assets_root}",
                        getPackRootFolder().toString() + File.separator
                                + MCForgeModpackConsts.MODPACK_MINECRAFT_ASSETS_LOCAL_FOLDER);
            }

            minecraftArgs = minecraftArgs.replace("${assets_index_name}",
                    getMinecraftLibraryManifest()
                            .getAssetIndexVersion());
            minecraftArgs = minecraftArgs.replace("${auth_uuid}", accountIdentifier);
            minecraftArgs = minecraftArgs.replace("${auth_access_token}", accountAccessToken);
            minecraftArgs = minecraftArgs.replace("${user_type}", "mojang");

            // Add title and icon to arguments
            minecraftArgs += " --title " + packName;
            minecraftArgs += " --icon " + getPackLogoFilepath();

            // Set dock name and icon for macOS
            if (org.apache.commons.lang3.SystemUtils.IS_OS_MAC) {
                minecraftArgs += " -Xdock:icon=\"" + getPackLogoFilepath() + "\"";
                minecraftArgs += " -Xdock:name=\"" + getPackName() + "\" ";
                minecraftArgs += "-Dapple.laf.useScreenMenuBar=true ";
            }
        }

        // Add java call to front of args
        minecraftArgs = javaPath + " " + minecraftArgs;

        // Start game
        try {
            SystemUtils.executeStringCommand(minecraftArgs, getPackRootFolder().toString());
        } catch (IOException | InterruptedException e) {
            throw new FLModpackException("Unable to execute modpack game.", e);
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
                        + MCForgeModpackConsts.MODPACK_FORGE_MODS_LOCAL_FOLDER;

        // Build list of valid mods
        ArrayList<String> validModPaths = new ArrayList<>();
        for (MCForgeMod mod : packMods) {
            mod.setLocalPathPrefix(modLocalPathPrefix);
            validModPaths.add(mod.getFullLocalFilePath());
        }

        // Loop through files in mods folder and remove unwanted
        File modpackModsFolderFile = new File(modLocalPathPrefix);
        File[] modsFolderFiles = modpackModsFolderFile.listFiles();
        if (modsFolderFiles != null) {
            for (File modFile : modsFolderFiles) {
                if (!validModPaths.contains(modFile.getPath())) {
                    boolean delete = modFile.delete();
                    if (!delete) Logger.logError("Unable to delete file during mod folder sanitization.");
                }
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this modpack's mods and repair/download/update as necessary.
     *
     * @throws FLModpackException if unable to fetch latest mods
     */
    private void fetchLatestMods() throws FLModpackException {
        // Cleanup mods that don't belong
        clearFloatingMods();
        if (progressProvider != null) {
            progressProvider.submitProgress("Removed floating mods", 30.0);
        }

        // Check if mods supplied
        if (packMods == null) {
            if (progressProvider != null) {
                progressProvider.submitProgress("No mods to handle", 100);
            }
            return;
        }

        // Get full path to mods folder
        String modLocalPathPrefix =
                getPackRootFolder().toString() + File.separator
                        + MCForgeModpackConsts.MODPACK_FORGE_MODS_LOCAL_FOLDER;

        // Update each mod if not already fully downloaded
        for (MCForgeMod mod : packMods) {
            mod.setLocalPathPrefix(modLocalPathPrefix);
            mod.updateLocalFile(LauncherApp.getMode());

            if (progressProvider != null) {
                progressProvider.submitProgress("Verified " + mod.name,
                        (70.0 / (double) packMods.size()));
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this modpack's configs and repair/download/update as necessary.
     *
     * @throws FLModpackException if unable to fetch latest configs
     */
    private void fetchLatestConfigs() throws FLModpackException {
        // Get full path to configs folder
        String configLocalPathPrefix =
                getPackRootFolder().toString() + File.separator
                        + MCForgeModpackConsts.MODPACK_FORGE_CONFIGS_LOCAL_FOLDER;

        // Check if configs supplied
        if (packConfigs == null) {
            if (progressProvider != null) {
                progressProvider.submitProgress("No configs to handle", 100);
            }
            return;
        }

        // Update each config if necessary
        for (MCForgeAsset config : packConfigs) {
            config.setLocalPathPrefix(configLocalPathPrefix);
            config.updateLocalFile(LauncherApp.getMode());

            if (progressProvider != null) {
                progressProvider.submitProgress("Verified " + FilenameUtils.getName(config.getFullLocalFilePath()),
                        (100.0 / (double) packConfigs.size()));
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this modpack's resource packs and repair/download/update as necessary.
     *
     * @throws FLModpackException if unable to fetch latest resource packs
     */
    private void fetchLatestResourcePacks() throws FLModpackException {
        // Only download resource packs on client (not server)
        if (LauncherApp.getMode() == GameMode.SERVER) {
            return;
        }

        // Check if resource packs supplied
        if (packResourcePacks == null) {
            if (progressProvider != null) {
                progressProvider.submitProgress("No resource packs to handle", 100);
            }
            return;
        }

        // Get full path to resource pack folder
        String resPackLocalPathPrefix =
                getPackRootFolder().toString() + File.separator
                        + MCForgeModpackConsts.MODPACK_FORGE_RESOURCEPACKS_LOCAL_FOLDER;

        // Update each resource pack if necessary
        for (MCRemoteFile resourcePack : packResourcePacks) {
            resourcePack.setLocalPathPrefix(resPackLocalPathPrefix);
            resourcePack.updateLocalFile();
            if (progressProvider != null) {
                progressProvider.submitProgress("Verified " + FilenameUtils.getName(resourcePack.getFullLocalFilePath()),
                        (100.0 / (double) packResourcePacks.size()));
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this modpack's shader packs and repair/download/update as necessary.
     *
     * @throws FLModpackException if unable to fetch latest shader packs
     */
    private void fetchLatestShaderPacks() throws FLModpackException {
        // Only download shader packs on client (not server)
        if (LauncherApp.getMode() == GameMode.SERVER) {
            return;
        }

        // Check if shader packs supplied
        if (packShaderPacks == null) {
            if (progressProvider != null) {
                progressProvider.submitProgress("No shader packs to handle", 100);
            }
            return;
        }

        // Get full path to shader pack folder
        String shaderPackLocalPathPrefix =
                getPackRootFolder().toString() + File.separator
                        + MCForgeModpackConsts.MODPACK_FORGE_SHADERPACKS_LOCAL_FOLDER;

        // Update each shader pack if necessary
        for (MCRemoteFile shaderPack : packShaderPacks) {
            shaderPack.setLocalPathPrefix(shaderPackLocalPathPrefix);
            shaderPack.updateLocalFile();
            if (progressProvider != null) {
                progressProvider.submitProgress("Verified " + FilenameUtils.getName(shaderPack.getFullLocalFilePath()),
                        (100.0 / (double) packShaderPacks.size()));
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this modpack's initial files and repair/download/updated as necessary.
     *
     * @throws FLModpackException if unable to fetch latest initial files
     */
    private void fetchLatestInitialFiles() throws FLModpackException {
        // Get full path to configs folder
        String initFilesLocalPathPrefix =
                getPackRootFolder().toString();

        // Check if initial files supplied
        if (packInitialFiles == null) {
            if (progressProvider != null) {
                progressProvider.submitProgress("No configs to handle", 100);
            }
            return;
        }

        // Update each initial file if necessary
        for (MCForgeAsset initFile : packInitialFiles) {
            initFile.setLocalPathPrefix(initFilesLocalPathPrefix);
            initFile.updateLocalFile(LauncherApp.getMode());

            if (progressProvider != null) {
                progressProvider.submitProgress("Verified " + FilenameUtils.getName(initFile.getFullLocalFilePath()),
                        (100.0 / (double) packInitialFiles.size()));
            }
        }
    }

    String getPackLogoFilepath() {
        return getPackRootFolder().toString() + File.separator
                + MCForgeModpackConsts.MODPACK_LOGO_LOCAL_FILE;
    }

    public void setProgressProvider(MCForgeModpackProgressProvider progressProvider) {
        this.progressProvider = progressProvider;
    }

    /**
     * Download the modpack's logo from the URL specified in the manifest
     *
     * @throws FLModpackException if unable to download/fetch logo
     */
    private void fetchLatestModpackLogo() throws FLModpackException {
        // Only download logo on client (not server)
        if (LauncherApp.getMode() == GameMode.SERVER) {
            return;
        }

        // Download latest logo
        try {
            SystemUtils.downloadFileFromURL(new URL(packLogoURL), new File(getPackLogoFilepath()));
            if (progressProvider != null) {
                progressProvider.submitProgress("Verified modpack logo", 100.0);
            }
        } catch (IOException e) {
            throw new FLModpackException("Unable to download/fetch modpack logo.", e);
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
        File binPath = new File(getPackRootFolder() + File.separator + "bin");
        File modsPath = new File(
                getPackRootFolder() + File.separator + "mods");
        File configPath = new File(
                getPackRootFolder() + File.separator + "config");
        File nativePath = new File(
                getPackRootFolder() + File.separator + "bin" + File.separator + "natives");
        File resPackPath = new File(
                getPackRootFolder() + File.separator + "resourcepacks");
        File shaderPackPath = new File(
                getPackRootFolder() + File.separator + "shaderpacks");

        binPath.getParentFile().mkdirs();

        if (!binPath.exists()) {
            binPath.mkdir();
        }
        if (!modsPath.exists()) {
            modsPath.mkdir();
        }
        if (!configPath.exists()) {
            configPath.mkdir();
        }
        if (!nativePath.exists()) {
            nativePath.mkdir();
        }
        if (!resPackPath.exists() && LauncherApp.getMode() == GameMode.CLIENT) {
            resPackPath.mkdir();
        }
        if (!shaderPackPath.exists()
                && LauncherApp.getMode() == GameMode.CLIENT) {
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
        return String.format(ModPackConstants.MODPACK_FRIENDLY_NAME_TEMPLATE, getPackName(), getPackVersion());
    }
}
