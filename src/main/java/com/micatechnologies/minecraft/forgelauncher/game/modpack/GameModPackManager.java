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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.forgelauncher.config.ConfigManager;
import com.micatechnologies.minecraft.forgelauncher.consts.ModPackConstants;
import com.micatechnologies.minecraft.forgelauncher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.forgelauncher.gui.GUIController;
import com.micatechnologies.minecraft.forgelauncher.gui.ProgressWindow;
import com.micatechnologies.minecraft.forgelauncher.files.Logger;
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
 * @author Mica Technologies
 * @version 1.0
 * @creator hawka97
 * @editors hawka97
 * @since 2.0
 */
public class GameModPackManager
{

    /**
     * List containing the mod packs that can be installed.
     *
     * @since 1.0
     */
    private static List< GameModPack > availableGameModPacks = null;

    /**
     * List containing the mod packs that are currently installed.
     *
     * @since 1.0
     */
    private static List< GameModPack > installedGameModPacks = null;

    /**
     * Method to handle populating the list of available mod packs from the available mod packs manifest referenced in
     * {@link ModPackConstants#AVAILABLE_PACKS_MANIFEST_URL}.
     *
     * @param progressWindow progress window to display progress information (can be null)
     *
     * @since 1.0
     */
    private synchronized static void fetchAvailableModPacks( ProgressWindow progressWindow ) {
        // Update progress window
        if ( progressWindow != null ) {
            progressWindow.setUpperLabelText( "Downloading available mod packs list" );
            progressWindow.setLowerLabelText( "Contacting server" );
        }
        else {
            Logger.logStd( "Downloading available mod packs list" + ": " +
                                   "Contacting server" );
        }

        // Fetch contents of available mod pack manifest
        String availableModPackManifestBody;
        try {
            availableModPackManifestBody = IOUtils.toString( new URL( ModPackConstants.AVAILABLE_PACKS_MANIFEST_URL ),
                                                             Charset.defaultCharset() );
        }
        catch ( IOException e ) {
            e.printStackTrace();
            Logger.logError( "Unable to fetch information about installable mod packs." );
            return;
        }

        // Clear existing list or create new
        if ( availableGameModPacks == null ) {
            availableGameModPacks = Collections.synchronizedList( new ArrayList<>() );
        }
        else {
            availableGameModPacks.clear();
        }

        // Store list of installed mod pack manifest URLs for filtering available
        List< String > installedModPackManifestUrls = getInstalledModPackURLs();

        // Parse available mod pack manifest contents
        JsonObject installableManifestUrls = new Gson().fromJson( availableModPackManifestBody, JsonObject.class );
        for ( JsonElement manifestUrl : installableManifestUrls
                .getAsJsonArray( ModPackConstants.AVAILABLE_PACKS_MANIFEST_LIST_KEY ) ) {
            final String manifestUrlVal = manifestUrl.getAsString();
            try {
                if ( !installedModPackManifestUrls.contains( manifestUrlVal ) ) {
                    GameModPack gameModPack = GameModPackFetcher.get( manifestUrlVal );
                    availableGameModPacks.add( gameModPack );

                    // Update progress window
                    if ( progressWindow != null ) {
                        progressWindow.setLowerLabelText(
                                "Added " + gameModPack.getPackName() + " v" + gameModPack.getPackVersion() +
                                        " to available mod packs" );
                    }
                    else {
                        Logger.logStd(
                                "Downloading available mod packs list" + ": " + "Added " + gameModPack.getPackName() +
                                        " v" + gameModPack.getPackVersion() +
                                        " to available mod packs" );
                    }
                }
                else {
                    Logger.logDebug(
                            "Not marking mod pack manifest as installable because it is installed already: " +
                                    manifestUrlVal );

                    // Update progress window
                    if ( progressWindow != null ) {
                        progressWindow.setLowerLabelText( "Already installed: " + manifestUrlVal );
                    }
                    else {
                        Logger.logStd( "Downloading available mod packs list" + ": " + "Already installed: " +
                                               manifestUrlVal );
                    }
                }

            }
            catch ( IOException e ) {
                e.printStackTrace();
                Logger.logError( "Unable to create an object for an available mod pack." );
            }
        }
        // Update progress window
        if ( progressWindow != null ) {
            progressWindow.setLowerLabelText( "Complete" );
        }
        else {
            Logger.logStd( "Downloading available mod packs list" + ": " + "Complete" );
        }
    }

    /**
     * Method to handle populating the list of installed mod packs from the launcher configuration.
     *
     * @param progressWindow progress window to display progress information (can be null)
     *
     * @since 1.0
     */
    private synchronized static void fetchInstalledModPacks( ProgressWindow progressWindow ) {
        // Update progress window to show start of fetch installed
        if ( progressWindow != null ) {
            progressWindow.setUpperLabelText( "Downloading installed mod pack updates" );
            progressWindow.setLowerLabelText( "Updating list of applicable mod packs" );
        }
        else {
            Logger.logStd( "Downloading installed mod pack updates" + ": " + "Updating list of applicable mod packs" );
        }


        // Get list of installed mod pack manifest URLs
        List< String > installedModPackManifestUrls = ConfigManager.getInstalledModPacks();

        // Clear existing list or create new
        if ( installedGameModPacks == null ) {
            installedGameModPacks = Collections.synchronizedList( new ArrayList<>() );
        }
        else {
            installedGameModPacks.clear();
        }

        // For each mod pack, get object from latest manifest
        for ( String manifestUrl : installedModPackManifestUrls ) {
            try {
                GameModPack gameModPack = GameModPackFetcher.get( manifestUrl );
                installedGameModPacks.add( gameModPack );

                // Update progress window
                if ( progressWindow != null ) {
                    progressWindow.setLowerLabelText(
                            "Got latest version of " + gameModPack.getPackName() + " (v" + gameModPack.getPackVersion() + ")" );
                }
                else {
                    Logger.logStd( "Downloading installed mod pack updates" + ": " + "Got latest version of " +
                                           gameModPack.getPackName() + " (v" + gameModPack.getPackVersion() + ")" );
                }
            }
            catch ( Exception e ) {
                e.printStackTrace();
                Logger.logError( "Unable to create an object for the installed mod pack from" + manifestUrl );
            }
        }

        // Update progress window
        if ( progressWindow != null ) {
            progressWindow.setLowerLabelText( "Complete" );
        }
        else {
            Logger.logStd( "Downloading installed mod pack updates" + ": " + "Complete" );
        }
    }

    /**
     * Method to handle populating the list of installed mod packs, then the list of mod packs that are available for
     * install. If the application is in a state that support a graphical user interface, one will be displayed to show
     * the progress of this task.
     *
     * @since 1.0
     */
    public synchronized static void fetchModPackInfo() {
        // Create progress window if applicable
        ProgressWindow progressWindow = null;
        if ( GUIController.shouldCreateGui() ) {
            progressWindow = new ProgressWindow();
            progressWindow.show( LocalizationManager.MODPACK_INSTALL_FETCH_UPPER_LABEL,
                                 LocalizationManager.MODPACK_INSTALL_FETCH_LOWER_LABEL );
        }

        // Update installed mod packs and available mod packs
        fetchInstalledModPacks( progressWindow );
        fetchAvailableModPacks( progressWindow );

        // Close progress window if applicable
        if ( progressWindow != null ) {
            progressWindow.close();
            try {
                progressWindow.closedLatch.await();
            }
            catch ( InterruptedException e ) {
                Logger.logError(
                        "Unable to wait for progress window to complete before returning from parent task." );
                e.printStackTrace();
            }
        }
    }

    /**
     * Gets and returns a list of the mod packs that are available for install.
     *
     * @return list of available mod packs
     *
     * @since 1.0
     */
    public synchronized static List< GameModPack > getAvailableModPacks() {
        // Populate lists if not already done
        if ( availableGameModPacks == null || installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        return availableGameModPacks;
    }

    /**
     * Gets and returns a list of the mod packs that are currently installed.
     *
     * @return list of installed mod packs
     *
     * @since 1.0
     */
    public synchronized static List< GameModPack > getInstalledModPacks() {
        // Populate lists if not already done
        if ( availableGameModPacks == null || installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        return installedGameModPacks;
    }

    /**
     * Gets and returns a list of the manifest URLs of mod packs that are available for install.
     *
     * @return list of installable mod pack manifest URLs
     *
     * @since 1.0
     */
    public synchronized static List< String > getAvailableModPackURLs() {
        // Populate lists if not already done
        if ( availableGameModPacks == null || installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        // Populate list of available mod pack manifest URLs and return
        List< String > availableModPackURLs = new ArrayList<>();
        for ( GameModPack gameModPack : getAvailableModPacks() ) {
            availableModPackURLs.add( gameModPack.getManifestUrl() );
        }
        return availableModPackURLs;
    }

    /**
     * Gets and returns a list of the manifest URLs of mod packs that are currently installed.
     *
     * @return list of installed mod pack manifest URLs
     *
     * @since 1.0
     */
    public synchronized static List< String > getInstalledModPackURLs() {
        // Populate lists if not already done
        if ( availableGameModPacks == null || installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        // Populate list of installed mod pack manifest URLs and return
        List< String > installedModPackUrls = new ArrayList<>();
        for ( GameModPack gameModPack : getInstalledModPacks() ) {
            installedModPackUrls.add( gameModPack.getManifestUrl() );
        }
        return installedModPackUrls;
    }

    /**
     * Gets and returns a list of the friendly names of mod packs that are available for install.
     *
     * @return list of installable mod pack friendly names
     *
     * @since 1.0
     */
    public synchronized static List< String > getAvailableModPackFriendlyNames() {
        // Populate lists if not already done
        if ( availableGameModPacks == null || installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        // Populate list of available mod pack manifest URLs and return
        List< String > availableModPackFriendlyNames = new ArrayList<>();
        for ( GameModPack gameModPack : getAvailableModPacks() ) {
            availableModPackFriendlyNames.add( gameModPack.getFriendlyName() );
        }
        return availableModPackFriendlyNames;
    }

    /**
     * Gets and returns a list of the friendly names of mod packs that are currently installed.
     *
     * @return list of installed mod pack friendly names
     *
     * @since 1.0
     */
    public synchronized static List< String > getInstalledModPackFriendlyNames() {
        // Populate lists if not already done
        if ( availableGameModPacks == null || installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        // Populate list of installed mod pack manifest URLs and return
        List< String > installedModPackFriendlyNames = new ArrayList<>();
        for ( GameModPack gameModPack : getInstalledModPacks() ) {
            installedModPackFriendlyNames.add( gameModPack.getFriendlyName() );
        }
        return installedModPackFriendlyNames;
    }

    /**
     * Gets and returns the mod pack object of the installed mod pack with the specified name.
     *
     * @param packName mod pack name
     *
     * @return mod pack with specified name
     *
     * @since 1.0
     */
    public synchronized static GameModPack getInstalledModPackByName( String packName ) {
        // Populate lists if not already done
        if ( availableGameModPacks == null || installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        // Find matching mod pack and return
        GameModPack foundGameModPack = null;
        for ( GameModPack gameModPack : getInstalledModPacks() ) {
            if ( gameModPack.getPackName().equalsIgnoreCase( packName ) ) {
                foundGameModPack = gameModPack;
            }
        }
        return foundGameModPack;
    }

    /**
     * Gets and returns the mod pack object of the installed mod pack with the specified friendly name.
     *
     * @param friendlyName mod pack friendly name
     *
     * @return mod pack with specified friendly name
     *
     * @since 1.0
     */
    public synchronized static GameModPack getInstalledModPackByFriendlyName( String friendlyName ) {
        // Populate lists if not already done
        if ( availableGameModPacks == null || installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        // Find matching mod pack and return
        GameModPack foundGameModPack = null;
        for ( GameModPack gameModPack : getInstalledModPacks() ) {
            if ( gameModPack.getFriendlyName().equalsIgnoreCase( friendlyName ) ) {
                foundGameModPack = gameModPack;
            }
        }
        return foundGameModPack;
    }

    /**
     * Uninstalls the specified mod pack from the launcher.
     *
     * @param gameModPack mod pack to uninstall
     *
     * @since 1.0
     */
    public synchronized static void uninstallModPack( GameModPack gameModPack ) {
        // Populate lists if not already done
        if ( availableGameModPacks == null || installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        // Remove specified pack from installed list
        if ( installedGameModPacks != null ) {
            installedGameModPacks.remove( gameModPack );
            saveToConfig();
            fetchModPackInfo();
        }
        else {
            Logger.logError( "Unable to uninstall mod pack " + gameModPack.getPackName() + "!" );
        }
    }

    /**
     * Uninstalls the mod pack with specified manifest URL from the launcher.
     *
     * @param url manifest URL of mod pack to uninstall
     *
     * @since 1.0
     */
    public synchronized static void uninstallModPackByURL( String url ) {
        // Populate lists if not already done
        if ( availableGameModPacks == null || installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        // Find matching mod pack and remove
        for ( GameModPack gameModPack : getInstalledModPacks() ) {
            if ( gameModPack.getManifestUrl().equals( url ) ) {
                uninstallModPack( gameModPack );
            }
        }
    }

    /**
     * Uninstalls the mod pack with specified friendly name from the launcher.
     *
     * @param friendlyName friendly name of mod pack to uninstall
     *
     * @since 1.0
     */
    public synchronized static void uninstallModPackByFriendlyName( String friendlyName ) {
        // Populate lists if not already done
        if ( availableGameModPacks == null || installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        // Find matching mod pack and remove
        for ( GameModPack gameModPack : getInstalledModPacks() ) {
            final String modPackFriendlyName = gameModPack.getFriendlyName();
            if ( modPackFriendlyName.equals( friendlyName ) ) {
                uninstallModPack( gameModPack );
            }
        }
    }

    /**
     * Installs the specified mod pack in the launcher.
     *
     * @param gameModPack mod pack to install
     *
     * @since 1.0
     */
    public synchronized static void installModPack( GameModPack gameModPack ) {
        // Populate lists if not already done
        if ( availableGameModPacks == null || installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        // Add specified pack to installed list
        if ( installedGameModPacks != null ) {
            installedGameModPacks.add( gameModPack );
            saveToConfig();
            fetchModPackInfo();

        }
        else {
            Logger.logError( "Unable to install mod pack " + gameModPack.getPackName() + "!" );
        }
    }

    /**
     * Installs the specified mod pack by friendly name in the launcher.
     *
     * @param friendlyName friendly name of mod pack to install
     *
     * @since 1.0
     */
    public synchronized static void installModPackByFriendlyName( String friendlyName ) {
        // Populate lists if not already done
        if ( availableGameModPacks == null || installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        try {
            // Find mod pack by friendly name
            GameModPack locatedGameModPack = null;
            for ( GameModPack gameModPack : getAvailableModPacks() ) {
                final String modPackFriendlyName = gameModPack.getFriendlyName();
                if ( modPackFriendlyName.equals( friendlyName ) ) {
                    locatedGameModPack = gameModPack;
                    break;
                }
            }

            // Install mod pack
            if ( locatedGameModPack == null ) {
                Logger.logError( "Unable to install " + friendlyName + " because it is not an available mod pack." );
            }
            else {
                installModPack( locatedGameModPack );
            }
        }
        catch ( Exception e ) {
            e.printStackTrace();
            Logger.logError( "Unable to install " + friendlyName );
        }
    }

    /**
     * Installs the specified mod pack by friendly name in the launcher.
     *
     * @param url manifest url of mod pack to install
     *
     * @since 1.0
     */
    public synchronized static void installModPackByURL( String url ) {
        // Populate lists if not already done
        if ( availableGameModPacks == null || installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        // Add mod pack
        try {
            installModPack( GameModPackFetcher.get( url ) );
        }
        catch ( Exception e ) {
            e.printStackTrace();
            Logger.logError( "Unable to install mod pack from " + url );
        }
    }

    /**
     * Saves the installed mod pack list to the launcher configuration
     *
     * @since 1.0
     */
    public synchronized static void saveToConfig() {
        // Save installed mod packs to configuration
        ConfigManager.setInstalledModPacks( getInstalledModPackURLs() );
    }
}
