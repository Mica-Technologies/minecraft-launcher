package com.micatechnologies.minecraft.forgelauncher.modpack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.forgelauncher.LauncherApp;
import com.micatechnologies.minecraft.forgelauncher.utilities.Logger;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mod pack list manager class. This class handles the loading and modification of mod packs installed in the launcher.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class ModPackInstallManager {

    /**
     * List containing the mod packs that can be installed.
     *
     * @since 1.0
     */
    private static List<ModPack> availableModPacks = null;

    /**
     * List containing the mod packs that are currently installed.
     *
     * @since 1.0
     */
    private static List<ModPack> installedModPacks = null;

    /**
     * Method to handle populating the list of available mod packs from the available mod packs manifest referenced in {@link ModPackConstants#AVAILABLE_PACKS_MANIFEST_URL}.
     *
     * @since 1.0
     */
    private synchronized static void fetchAvailableModPacks() {
        // Fetch contents of available mod pack manifest
        String availableModPackManifestBody;
        try {
            availableModPackManifestBody = IOUtils.toString(new URL(ModPackConstants.AVAILABLE_PACKS_MANIFEST_URL), Charset.defaultCharset());
        } catch (IOException e) {
            e.printStackTrace();
            Logger.logError("Unable to fetch information about installable mod packs.");
            return;
        }

        // Clear existing list or create new
        if (availableModPacks == null) availableModPacks = Collections.synchronizedList(new ArrayList<>());
        else availableModPacks.clear();

        // Store list of installed mod pack manifest URLs for filtering available
        List<String> installedModPackManifestUrls = getInstalledModPackURLs();

        // Parse available mod pack manifest contents
        JsonObject installableManifestUrls = new Gson().fromJson(availableModPackManifestBody, JsonObject.class);
        for (JsonElement manifestUrl : installableManifestUrls.getAsJsonArray(ModPackConstants.AVAILABLE_PACKS_MANIFEST_LIST_KEY)) {
            final String manifestUrlVal = manifestUrl.getAsString();
            try {
                if (!installedModPackManifestUrls.contains(manifestUrlVal)) {
                    availableModPacks.add(ModPackFetcher.get(manifestUrlVal));
                } else {
                    Logger.logDebug("Not marking mod pack manifest as installable because it is installed already: " + manifestUrlVal);
                }

            } catch (IOException e) {
                e.printStackTrace();
                Logger.logError("Unable to create an object for an available mod pack.");
            }
        }
    }

    /**
     * Method to handle populating the list of installed mod packs from the launcher configuration.
     *
     * @since 1.0
     */
    private synchronized static void fetchInstalledModPacks() {
        // Get list of installed mod pack manifest URLs
        List<String> installedModPackManifestUrls = LauncherApp.getLauncherConfig().getModpacks();

        // Clear existing list or create new
        if (installedModPacks == null) installedModPacks = Collections.synchronizedList(new ArrayList<>());
        else installedModPacks.clear();

        // For each mod pack, get object from latest manifest
        for (String manifestUrl : installedModPackManifestUrls) {
            try {
                installedModPacks.add(ModPackFetcher.get(manifestUrl));
            } catch (Exception e) {
                e.printStackTrace();
                Logger.logError("Unable to create an object for the installed mod pack from" + manifestUrl);
            }
        }
    }

    /**
     * Method to handle populating the list of installed mod packs, then the list of mod packs that are available for install.
     *
     * @since 1.0
     */
    public synchronized static void fetchModPackInfo() {
        fetchInstalledModPacks();
        fetchAvailableModPacks();
    }

    /**
     * Gets and returns a list of the mod packs that are available for install.
     *
     * @return list of available mod packs
     * @since 1.0
     */
    public synchronized static List<ModPack> getAvailableModPacks() {
        // Populate lists if not already done
        if (availableModPacks == null || installedModPacks == null) fetchModPackInfo();

        return availableModPacks;
    }

    /**
     * Gets and returns a list of the mod packs that are currently installed.
     *
     * @return list of installed mod packs
     * @since 1.0
     */
    public synchronized static List<ModPack> getInstalledModPacks() {
        // Populate lists if not already done
        if (availableModPacks == null || installedModPacks == null) fetchModPackInfo();

        return installedModPacks;
    }

    /**
     * Gets and returns a list of the manifest URLs of mod packs that are available for install.
     *
     * @return list of installable mod pack manifest URLs
     * @since 1.0
     */
    public synchronized static List<String> getAvailableModPackURLs() {
        // Populate lists if not already done
        if (availableModPacks == null || installedModPacks == null) fetchModPackInfo();

        // Populate list of available mod pack manifest URLs and return
        List<String> availableModPackURLs = new ArrayList<>();
        for (ModPack modPack : getAvailableModPacks()) {
            availableModPackURLs.add(modPack.getManifestUrl());
        }
        return availableModPackURLs;
    }

    /**
     * Gets and returns a list of the manifest URLs of mod packs that are currently installed.
     *
     * @return list of installed mod pack manifest URLs
     * @since 1.0
     */
    public synchronized static List<String> getInstalledModPackURLs() {
        // Populate lists if not already done
        if (availableModPacks == null || installedModPacks == null) fetchModPackInfo();

        // Populate list of installed mod pack manifest URLs and return
        List<String> installedModPackUrls = new ArrayList<>();
        for (ModPack modPack : getInstalledModPacks()) {
            installedModPackUrls.add(modPack.getManifestUrl());
        }
        return installedModPackUrls;
    }

    /**
     * Gets and returns a list of the friendly names of mod packs that are available for install.
     *
     * @return list of installable mod pack friendly names
     * @since 1.0
     */
    public synchronized static List<String> getAvailableModPackFriendlyNames() {
        // Populate lists if not already done
        if (availableModPacks == null || installedModPacks == null) fetchModPackInfo();

        // Populate list of available mod pack manifest URLs and return
        List<String> availableModPackFriendlyNames = new ArrayList<>();
        for (ModPack modPack : getAvailableModPacks()) {
            availableModPackFriendlyNames.add(modPack.getFriendlyName());
        }
        return availableModPackFriendlyNames;
    }

    /**
     * Gets and returns a list of the friendly names of mod packs that are currently installed.
     *
     * @return list of installed mod pack friendly names
     * @since 1.0
     */
    public synchronized static List<String> getInstalledModPackFriendlyNames() {
        // Populate lists if not already done
        if (availableModPacks == null || installedModPacks == null) fetchModPackInfo();

        // Populate list of installed mod pack manifest URLs and return
        List<String> installedModPackFriendlyNames = new ArrayList<>();
        for (ModPack modPack : getInstalledModPacks()) {
            installedModPackFriendlyNames.add(modPack.getFriendlyName());
        }
        return installedModPackFriendlyNames;
    }

    /**
     * Gets and returns the mod pack object of the installed mod pack with the specified name.
     *
     * @param packName mod pack name
     * @return mod pack with specified name
     * @since 1.0
     */
    public synchronized static ModPack getInstalledModPackByName(String packName) {
        // Populate lists if not already done
        if (availableModPacks == null || installedModPacks == null) fetchModPackInfo();

        // Find matching mod pack and return
        ModPack foundModPack = null;
        for (ModPack modPack : getInstalledModPacks()) {
            if (modPack.getPackName().equalsIgnoreCase(packName)) foundModPack = modPack;
        }
        return foundModPack;
    }

    /**
     * Gets and returns the mod pack object of the installed mod pack with the specified friendly name.
     *
     * @param friendlyName mod pack friendly name
     * @return mod pack with specified friendly name
     * @since 1.0
     */
    public synchronized static ModPack getInstalledModPackByFriendlyName(String friendlyName) {
        // Populate lists if not already done
        if (availableModPacks == null || installedModPacks == null) fetchModPackInfo();

        // Find matching mod pack and return
        ModPack foundModPack = null;
        for (ModPack modPack : getInstalledModPacks()) {
            if (modPack.getFriendlyName().equalsIgnoreCase(friendlyName)) foundModPack = modPack;
        }
        return foundModPack;
    }

    /**
     * Uninstalls the specified mod pack from the launcher.
     *
     * @param modPack mod pack to uninstall
     * @since 1.0
     */
    public synchronized static void uninstallModPack(ModPack modPack) {
        // Populate lists if not already done
        if (availableModPacks == null || installedModPacks == null) fetchModPackInfo();

        // Remove specified pack from installed list
        if (installedModPacks != null) {
            installedModPacks.remove(modPack);
            saveToConfig();
            fetchInstalledModPacks();
            fetchAvailableModPacks();
        } else Logger.logError("Unable to uninstall mod pack " + modPack.getPackName() + "!");
    }

    /**
     * Uninstalls the mod pack with specified manifest URL from the launcher.
     *
     * @param url manifest URL of mod pack to uninstall
     * @since 1.0
     */
    public synchronized static void uninstallModPackByURL(String url) {
        // Populate lists if not already done
        if (availableModPacks == null || installedModPacks == null) fetchModPackInfo();

        // Find matching mod pack and remove
        for (ModPack modPack : getInstalledModPacks()) {
            if (modPack.getManifestUrl().equals(url)) uninstallModPack(modPack);
        }
    }

    /**
     * Uninstalls the mod pack with specified friendly name from the launcher.
     *
     * @param friendlyName friendly name of mod pack to uninstall
     * @since 1.0
     */
    public synchronized static void uninstallModPackByFriendlyName(String friendlyName) {
        // Populate lists if not already done
        if (availableModPacks == null || installedModPacks == null) fetchModPackInfo();

        // Find matching mod pack and remove
        for (ModPack modPack : getInstalledModPacks()) {
            final String modPackFriendlyName = modPack.getFriendlyName();
            if (modPackFriendlyName.equals(friendlyName)) uninstallModPack(modPack);
        }
    }

    /**
     * Installs the specified mod pack in the launcher.
     *
     * @param modPack mod pack to install
     * @since 1.0
     */
    public synchronized static void installModPack(ModPack modPack) {
        // Populate lists if not already done
        if (availableModPacks == null || installedModPacks == null) fetchModPackInfo();

        // Add specified pack to installed list
        if (installedModPacks != null) {
            installedModPacks.add(modPack);
            saveToConfig();
            fetchInstalledModPacks();
            fetchAvailableModPacks();

        } else Logger.logError("Unable to install mod pack " + modPack.getPackName() + "!");
    }

    /**
     * Installs the specified mod pack by friendly name in the launcher.
     *
     * @param friendlyName friendly name of mod pack to install
     * @since 1.0
     */
    public synchronized static void installModPackByFriendlyName(String friendlyName) {
        // Populate lists if not already done
        if (availableModPacks == null || installedModPacks == null) fetchModPackInfo();

        try {
            // Find mod pack by friendly name
            ModPack locatedModPack = null;
            for (ModPack modPack : getAvailableModPacks()) {
                final String modPackFriendlyName = modPack.getFriendlyName();
                if (modPackFriendlyName.equals(friendlyName)) {
                    locatedModPack = modPack;
                    break;
                }
            }

            // Install mod pack
            if (locatedModPack == null) {
                Logger.logError("Unable to install " + friendlyName + " because it is not an available mod pack.");
            } else {
                installModPack(locatedModPack);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Logger.logError("Unable to install " + friendlyName);
        }
    }

    /**
     * Installs the specified mod pack by friendly name in the launcher.
     *
     * @param url manifest url of mod pack to install
     * @since 1.0
     */
    public synchronized static void installModPackByURL(String url) {
        // Populate lists if not already done
        if (availableModPacks == null || installedModPacks == null) fetchModPackInfo();

        // Add mod pack
        try {
            installModPack(ModPackFetcher.get(url));
        } catch (Exception e) {
            e.printStackTrace();
            Logger.logError("Unable to install mod pack from " + url);
        }
    }

    /**
     * Saves the installed mod pack list to the launcher configuration
     *
     * @since 1.0
     */
    public synchronized static void saveToConfig() {
        // Save installed mod packs to configuration
        LauncherApp.getLauncherConfig().getModpacks().clear();
        LauncherApp.getLauncherConfig().getModpacks().addAll(getInstalledModPackURLs());
        LauncherApp.saveConfig();
    }
}
