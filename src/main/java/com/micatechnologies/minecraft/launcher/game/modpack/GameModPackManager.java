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

package com.micatechnologies.minecraft.launcher.game.modpack;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiController;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherProgressGui;
import com.micatechnologies.minecraft.launcher.files.Logger;
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
    private synchronized static void fetchAvailableModPacks( MCLauncherProgressGui progressWindow ) {
        // Update progress window
        if ( progressWindow != null ) {
            progressWindow.setUpperLabelText( LocalizationManager.DOWNLOADING_AVAILABLE_MOD_PACKS_LIST_TEXT );
            progressWindow.setLowerLabelText( LocalizationManager.CONTACTING_SERVER_TEXT );
        }
        else {
            Logger.logStd( LocalizationManager.DOWNLOADING_AVAILABLE_MOD_PACKS_LIST_TEXT +
                                   ": " +
                                   LocalizationManager.CONTACTING_SERVER_TEXT );
        }

        // Fetch contents of available mod pack manifest
        String availableModPackManifestBody;
        try {
            availableModPackManifestBody = IOUtils.toString( new URL( ModPackConstants.AVAILABLE_PACKS_MANIFEST_URL ),
                                                             Charset.defaultCharset() );
        }
        catch ( IOException e ) {
            e.printStackTrace();
            Logger.logError( LocalizationManager.UNABLE_FETCH_INFO_INSTALLABLE_MOD_PACKS_TEXT );
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
        for ( JsonElement manifestUrl : installableManifestUrls.getAsJsonArray(
                ModPackConstants.AVAILABLE_PACKS_MANIFEST_LIST_KEY ) ) {
            final String manifestUrlVal = manifestUrl.getAsString();
            try {
                if ( !installedModPackManifestUrls.contains( manifestUrlVal ) ) {
                    GameModPack gameModPack = GameModPackFetcher.get( manifestUrlVal );
                    availableGameModPacks.add( gameModPack );

                    // Update progress window
                    if ( progressWindow != null ) {
                        progressWindow.setLowerLabelText( LocalizationManager.ADDED_TEXT +
                                                                  " " +
                                                                  gameModPack.getPackName() +
                                                                  " v" +
                                                                  gameModPack.getPackVersion() +
                                                                  " " +
                                                                  LocalizationManager.TO_AVAILABLE_MOD_PACKS_TEXT );
                    }
                    else {
                        Logger.logStd( LocalizationManager.DOWNLOADING_AVAILABLE_MOD_PACKS_LIST_TEXT +
                                               ": " +
                                               LocalizationManager.ADDED_TEXT +
                                               " " +
                                               gameModPack.getPackName() +
                                               " v" +
                                               gameModPack.getPackVersion() +
                                               " " +
                                               LocalizationManager.TO_AVAILABLE_MOD_PACKS_TEXT );
                    }
                }
                else {
                    Logger.logDebug( LocalizationManager.NOT_MARKING_INSTALLABLE_ALREADY_INSTALLED_TEXT +
                                             ": " +
                                             manifestUrlVal );

                    // Update progress window
                    if ( progressWindow != null ) {
                        progressWindow.setLowerLabelText(
                                LocalizationManager.ALREADY_INSTALLED_TEXT + ": " + manifestUrlVal );
                    }
                    else {
                        Logger.logStd( LocalizationManager.DOWNLOADING_AVAILABLE_MOD_PACKS_LIST_TEXT +
                                               ": " +
                                               LocalizationManager.ALREADY_INSTALLED_TEXT +
                                               ": " +
                                               manifestUrlVal );
                    }
                }

            }
            catch ( IOException e ) {
                Logger.logError( LocalizationManager.UNABLE_CREATE_OBJ_FOR_AVAILABLE_MOD_PACK_TEXT );
                Logger.logThrowable( e );
            }
        }
        // Update progress window
        if ( progressWindow != null ) {
            progressWindow.setLowerLabelText( LocalizationManager.COMPLETED_TEXT );
        }
        else {
            Logger.logStd( LocalizationManager.DOWNLOADING_AVAILABLE_MOD_PACKS_LIST_TEXT +
                                   ": " +
                                   LocalizationManager.COMPLETED_TEXT );
        }
    }

    /**
     * Method to handle populating the list of installed mod packs from the launcher configuration.
     *
     * @param progressWindow progress window to display progress information (can be null)
     *
     * @since 1.0
     */
    private synchronized static void fetchInstalledModPacks( MCLauncherProgressGui progressWindow ) {
        // Update progress window to show start of fetch installed
        if ( progressWindow != null ) {
            progressWindow.setUpperLabelText( LocalizationManager.DOWNLOADING_INSTALLED_MOD_PACK_UPDATES_TEXT );
            progressWindow.setLowerLabelText( LocalizationManager.UPDATING_LIST_APPLICABLE_MOD_PACKS_TEXT );
        }
        else {
            Logger.logStd( LocalizationManager.DOWNLOADING_INSTALLED_MOD_PACK_UPDATES_TEXT +
                                   ": " +
                                   LocalizationManager.UPDATING_LIST_APPLICABLE_MOD_PACKS_TEXT );
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
                    progressWindow.setLowerLabelText( LocalizationManager.GOT_LATEST_VERSION_OF_TEXT +
                                                              " " +
                                                              gameModPack.getPackName() +
                                                              " (v" +
                                                              gameModPack.getPackVersion() +
                                                              ")" );
                }
                else {
                    Logger.logStd( LocalizationManager.DOWNLOADING_INSTALLED_MOD_PACK_UPDATES_TEXT +
                                           ": " +
                                           LocalizationManager.GOT_LATEST_VERSION_OF_TEXT +
                                           " " +
                                           gameModPack.getPackName() +
                                           " (v" +
                                           gameModPack.getPackVersion() +
                                           ")" );
                }
            }
            catch ( Exception e ) {
                Logger.logError(
                        LocalizationManager.UNABLE_CREATE_OBJ_FOR_INSTALLED_MOD_PACK_FROM_TEXT + " " + manifestUrl );
                Logger.logThrowable( e );
            }
        }

        // Update progress window
        if ( progressWindow != null ) {
            progressWindow.setLowerLabelText( LocalizationManager.COMPLETED_TEXT );
        }
        else {
            Logger.logStd( LocalizationManager.DOWNLOADING_INSTALLED_MOD_PACK_UPDATES_TEXT +
                                   ": " +
                                   LocalizationManager.COMPLETED_TEXT );
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
        MCLauncherProgressGui progressWindow = null;
        try {
            if ( MCLauncherGuiController.shouldCreateGui() ) {
                progressWindow = MCLauncherGuiController.goToProgressGui();
            }
        }
        catch ( IOException e ) {
            Logger.logError( "Unable to load progress GUI due to an incomplete response from the GUI subsystem." );
            Logger.logThrowable( e );
        }

        if ( progressWindow != null ) {
            progressWindow.setLabelTexts( LocalizationManager.MODPACK_INSTALL_FETCH_UPPER_LABEL,
                                          LocalizationManager.MODPACK_INSTALL_FETCH_LOWER_LABEL );
        }

        // Update installed mod packs and available mod packs
        fetchInstalledModPacks( progressWindow );
        fetchAvailableModPacks( progressWindow );
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
        for ( GameModPack gameModPack : availableGameModPacks ) {
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
        for ( GameModPack gameModPack : installedGameModPacks ) {
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
        for ( GameModPack gameModPack : availableGameModPacks ) {
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
        for ( GameModPack gameModPack : installedGameModPacks ) {
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
        for ( GameModPack gameModPack : installedGameModPacks ) {
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
        for ( GameModPack gameModPack : installedGameModPacks ) {
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
            Logger.logError( LocalizationManager.UNABLE_TO_UNINSTALL_MOD_PACK_TEXT + " " + gameModPack.getPackName() );
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
        for ( GameModPack gameModPack : installedGameModPacks ) {
            if ( gameModPack.getManifestUrl().equals( url ) ) {
                uninstallModPack( gameModPack );
                break;
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
        for ( GameModPack gameModPack : installedGameModPacks ) {
            final String modPackFriendlyName = gameModPack.getFriendlyName();
            if ( modPackFriendlyName.equals( friendlyName ) ) {
                uninstallModPack( gameModPack );
                break;
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
                Logger.logError( LocalizationManager.UNABLE_TO_INSTALL_TEXT +
                                         " " +
                                         friendlyName +
                                         " " +
                                         LocalizationManager.BECAUSE_NOT_AVAILABLE_MOD_PACK_TEXT );
            }
            else {
                installModPack( locatedGameModPack );
            }
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.UNABLE_TO_INSTALL_TEXT + " " + friendlyName );
            Logger.logThrowable( e );
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
            Logger.logError( LocalizationManager.UNABLE_TO_INSTALL_MOD_PACK_FROM_TEXT + " " + url );
            Logger.logThrowable( e );
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
