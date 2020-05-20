package com.micatechnologies.minecraft.forgelauncher.modpack;

import com.micatechnologies.minecraft.forgelauncher.MCFLApp;

import java.util.ArrayList;
import java.util.List;

/**
 * Mod pack list manager class. This class handles the loading and modification of mod packs installed in the Mica Forge
 * Launcher.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class InstalledModPackManager {
    /**
     * Internal list. This list stores the mod packs currently configured in the launcher.
     *
     * @since 1.0
     */
    private static List< ModPack > modpacksList = null;

    /**
     * Loads the internal mod pack list. Called when the list has not been populated.
     *
     * @since 1.0
     */
    private synchronized static void loadModpacks() {

        modpacksList = MCFLApp.getModpacks();
        // TODO: Read directly from config. Remove middle man -MCFLApp
    }

    /**
     * Gets a list of the URLs of the configured mod packs.
     *
     * @return list of mod pack URLs
     *
     * @since 1.0
     */
    public synchronized static List< String > getModpacksListByUrl() {
        // Load mod pack list if not loaded
        if ( modpacksList == null ) loadModpacks();

        // Create list for modpack URLs
        List< String > modpackUrlList = new ArrayList<>();

        // Populate list of modpack URLs and return
        for ( ModPack pack : modpacksList ) {
            modpackUrlList.add( pack.getManifestUrl() );
        }
        return modpackUrlList;
    }

    /**
     * Gets a list of the friendly names of the configured mod packs. Note :Friendly names are formatted as {mod pack
     * name}: {mod pack version}.
     *
     * @return list of mod pack friendly names
     *
     * @since 1.0
     */
    public synchronized static List< String > getModpacksListByFriendly() {
        // Load mod pack list if not loaded
        if ( modpacksList == null ) loadModpacks();

        // Create list for mod pack URLs
        List< String > modpackUrlList = new ArrayList<>();

        // Populate list of mod pack URLs and return
        for ( ModPack pack : modpacksList ) {
            modpackUrlList.add( pack.getPackName() + ": " + pack.getPackVersion() );
        }
        return modpackUrlList;
    }

    /**
     * Removes the mod pack with specified URL from the configuration.
     *
     * @param url URL of mod pack to remove
     *
     * @since 1.0
     */
    public synchronized static void removeModpackByURL( String url ) {
        // Load mod pack list if not loaded
        if ( modpacksList == null ) loadModpacks();

        // Compare mod packs in list. Remove if a match
        for ( ModPack pack : modpacksList ) {
            final String currModpackUrl = pack.getManifestUrl();
            if ( currModpackUrl.equals( url ) ) {
                modpacksList.remove( pack );
                break;
            }
        }
    }

    /**
     * Removes the mod pack with specified friendly name from the configuration.
     *
     * @param friendly friendly name of mod pack to remove
     *
     * @since 1.0
     */
    public synchronized static void removeModpackByFriendly( String friendly ) {
        // Load mod pack list if not loaded
        if ( modpacksList == null ) loadModpacks();

        // Compare mod packs in list. Remove if a match
        for ( ModPack pack : modpacksList ) {
            final String currModpackFriendly = pack.getPackName() + ": " + pack.getPackVersion();
            if ( currModpackFriendly.equals( friendly ) ) {
                modpacksList.remove( pack );
                break;
            }
        }
    }

    /**
     * Adds the mod pack hosted by the manifest at the specified URL.
     *
     * @param modPackUrl mod pack manifest URL
     */
    public synchronized static void installModPack( String modPackUrl ) {
        // TODO: Install modpack
    }
}
