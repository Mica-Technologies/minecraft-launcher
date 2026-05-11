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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiController;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherProgressGui;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mod pack list manager class. This class handles the loading and modification of mod packs installed in the launcher.
 *
 * @author Mica Technologies
 * @version 1.0
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
     * In-flight (or completed) future for the background available-modpacks fetch kicked off
     * at launcher startup. Read by the main GUI to show a "loading available packs" indicator
     * and by {@link #getAvailableModPacks()} to block on completion when a caller actually
     * needs the data (e.g. the Game Library screen).
     *
     * <p>Volatile so the unsynchronized read in {@link #startAvailableModPacksFetchAsync()}'s
     * double-checked initialization is safe.</p>
     *
     * @since 3.4
     */
    private static volatile CompletableFuture< Void > availableFetchFuture = null;

    /**
     * Method to handle populating the list of available mod packs from the available mod packs manifest referenced in
     * {@link ModPackConstants#AVAILABLE_PACKS_MANIFEST_URL}.
     *
     * @param progressWindow progress window to display progress information (can be null)
     *
     * @since 1.0
     */
    public synchronized static void fetchAvailableModPacks( MCLauncherProgressGui progressWindow ) {
        // Update progress window
        if ( progressWindow != null ) {
            progressWindow.setSectionText( LocalizationManager.DOWNLOADING_AVAILABLE_MOD_PACKS_LIST_TEXT );
            progressWindow.setDetailText( LocalizationManager.CONTACTING_SERVER_TEXT );
        }
        else {
            Logger.logStd( LocalizationManager.DOWNLOADING_AVAILABLE_MOD_PACKS_LIST_TEXT +
                                   ": " +
                                   LocalizationManager.CONTACTING_SERVER_TEXT );
        }

        // Fetch contents of available mod pack manifest
        String availableModPackManifestBody;
        try {
            availableModPackManifestBody = NetworkUtilities.downloadFileFromURL(
                    ModPackConstants.AVAILABLE_PACKS_MANIFEST_URL );
        }
        catch ( IOException e ) {
            Logger.logThrowable( e );
            Logger.logError( LocalizationManager.UNABLE_FETCH_INFO_INSTALLABLE_MOD_PACKS_TEXT );
            return;
        }

        // Clear existing list or create new
        if ( availableGameModPacks == null ) {
            availableGameModPacks = new CopyOnWriteArrayList<>();
        }
        else {
            availableGameModPacks.clear();
        }

        // Store list of installed mod pack manifest URLs for filtering available
        List< String > installedModPackManifestUrls = getInstalledModPackURLs();

        // Parse available mod pack manifest contents. Per-pack manifest fetches are
        // network-bound and independent — parallelStream cuts wall time roughly N-fold
        // for N available packs, the single biggest hit to launcher startup latency
        // when there's a meaningful number of available packs in the manifest.
        JsonObject installableManifestUrls = JSONUtilities.getGson().fromJson( availableModPackManifestBody, JsonObject.class );
        final MCLauncherProgressGui finalProgressWindow = progressWindow;
        installableManifestUrls.getAsJsonArray( ModPackConstants.AVAILABLE_PACKS_MANIFEST_LIST_KEY )
                .asList().parallelStream().forEach( manifestUrl -> {
            final String manifestUrlVal = manifestUrl.getAsString();
            if ( !installedModPackManifestUrls.contains( manifestUrlVal ) ) {
                GameModPack gameModPack = GameModPackFetcher.get( manifestUrlVal, false );
                if ( gameModPack.getFriendlyName() != null ) {
                    availableGameModPacks.add( gameModPack );

                    // Update progress window
                    if ( finalProgressWindow != null ) {
                        finalProgressWindow.setDetailText( LocalizationManager.ADDED_TEXT +
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
            }
            else {
                Logger.logDebug(
                        LocalizationManager.NOT_MARKING_INSTALLABLE_ALREADY_INSTALLED_TEXT + ": " + manifestUrlVal );

                // Update progress window
                if ( finalProgressWindow != null ) {
                    finalProgressWindow.setDetailText(
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
        } );
        // Update progress window
        if ( progressWindow != null ) {
            progressWindow.setDetailText( LocalizationManager.COMPLETED_TEXT );
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
    public synchronized static void fetchInstalledModPacks( MCLauncherProgressGui progressWindow ) {
        // Update progress window to show start of fetch installed
        if ( progressWindow != null ) {
            progressWindow.setSectionText( LocalizationManager.DOWNLOADING_INSTALLED_MOD_PACK_UPDATES_TEXT );
            progressWindow.setDetailText( LocalizationManager.UPDATING_LIST_APPLICABLE_MOD_PACKS_TEXT );
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
            installedGameModPacks = new CopyOnWriteArrayList<>();
        }
        else {
            installedGameModPacks.clear();
        }

        // For each mod pack, get object from latest manifest. Per-pack manifest fetches
        // are network-bound and independent — running them in parallel via parallelStream
        // shrinks wall time roughly N-fold for N installed packs, which is the dominant
        // cost in launcher startup for users with several packs installed.
        final MCLauncherProgressGui finalProgressWindow = progressWindow;
        installedModPackManifestUrls.parallelStream().forEach( manifestUrl -> {
            try {
                GameModPack gameModPack = GameModPackFetcher.get( manifestUrl, true );
                installedGameModPacks.add( gameModPack );

                // Update progress window
                if ( finalProgressWindow != null ) {
                    finalProgressWindow.setDetailText( LocalizationManager.GOT_LATEST_VERSION_OF_TEXT +
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
        } );

        // Update progress window
        if ( progressWindow != null ) {
            progressWindow.setDetailText( LocalizationManager.COMPLETED_TEXT );
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
            progressWindow.setUpperLabelText( LocalizationManager.MODPACK_INSTALL_FETCH_UPPER_LABEL );
            progressWindow.setSectionText( LocalizationManager.MODPACK_INSTALL_FETCH_LOWER_LABEL );
            progressWindow.setDetailText( "" );
        }

        // Check network connectivity before fetching
        if ( progressWindow != null ) {
            progressWindow.setDetailText( "Checking network connectivity..." );
        }
        boolean online = NetworkUtilities.checkNetworkAvailability();
        if ( !online && progressWindow != null ) {
            progressWindow.setDetailText( "Offline mode: using cached data" );
        }

        // Update installed mod packs and available mod packs
        fetchInstalledModPacks( progressWindow );
        if ( !NetworkUtilities.isOffline() ) {
            fetchAvailableModPacks( progressWindow );
        }
    }

    /**
     * Kicks off an async fetch of the available-modpacks list and returns the in-flight future.
     * Idempotent: if a fetch has already been started (or completed), returns the existing
     * future without starting a second one.
     *
     * <p>Used by the launcher startup path to keep the available-packs fetch off the critical
     * path — the main menu only needs installed packs to render, so available packs can load
     * in the background while the user is already looking at the modpack list. Callers that
     * actually need the data (e.g. the Game Library screen) hit
     * {@link #getAvailableModPacks()}, which blocks on this future when it's still running.</p>
     *
     * <p>No-op when offline — there's nothing to fetch, the returned future completes
     * immediately so {@link #isAvailableModPacksFetchPending()} reports false.</p>
     *
     * @return the future representing the background fetch (never null)
     *
     * @since 3.4
     */
    public static CompletableFuture< Void > startAvailableModPacksFetchAsync() {
        CompletableFuture< Void > existing = availableFetchFuture;
        if ( existing != null ) {
            return existing;
        }
        synchronized ( GameModPackManager.class ) {
            if ( availableFetchFuture != null ) {
                return availableFetchFuture;
            }
            if ( NetworkUtilities.isOffline() ) {
                availableFetchFuture = CompletableFuture.completedFuture( null );
                return availableFetchFuture;
            }
            availableFetchFuture = CompletableFuture.runAsync( () -> {
                try {
                    fetchAvailableModPacks( null );
                }
                catch ( Throwable t ) {
                    Logger.logErrorSilent( "Background available-modpacks fetch failed." );
                    Logger.logThrowable( t );
                }
            } );
            return availableFetchFuture;
        }
    }

    /**
     * Reports whether the background available-modpacks fetch is still running. Used by the
     * main menu to surface a tiny "loading available packs" indicator that disappears once the
     * background fetch completes.
     *
     * @return {@code true} if a fetch is in flight, {@code false} if not started, completed, or failed
     *
     * @since 3.4
     */
    public static boolean isAvailableModPacksFetchPending() {
        CompletableFuture< Void > f = availableFetchFuture;
        return f != null && !f.isDone();
    }

    /**
     * Returns the in-flight available-modpacks fetch future, or {@code null} if no fetch has
     * been started. Callers can attach a completion handler (e.g. to hide a "loading"
     * indicator in the UI) via {@link CompletableFuture#whenComplete(java.util.function.BiConsumer)}.
     *
     * @return the in-flight future, or {@code null} if not started
     *
     * @since 3.4
     */
    public static CompletableFuture< Void > getAvailableFetchFuture() {
        return availableFetchFuture;
    }

    /**
     * Gets and returns a list of the mod packs that are available for install.
     *
     * @return list of available mod packs
     *
     * @since 1.0
     */
    public static List< GameModPack > getAvailableModPacks() {
        // Wait for the background available-modpacks fetch BEFORE entering any synchronized
        // block — the background task acquires the class monitor when it runs
        // fetchAvailableModPacks, so if we held it here while waiting we'd deadlock against it.
        // The Library screen wants a complete view rather than a partial in-flight one.
        waitForAvailableFetch();

        return getAvailableModPacksLocked();
    }

    /**
     * Synchronized inner accessor — kept separate from {@link #getAvailableModPacks()} so the
     * caller can safely wait on {@link #availableFetchFuture} before entering the lock.
     */
    private synchronized static List< GameModPack > getAvailableModPacksLocked() {
        // Populate lists if not already done (legacy fallback for callers that bypass the
        // async startup path — e.g. tests or future entry points that never call
        // startAvailableModPacksFetchAsync).
        if ( installedGameModPacks == null ) {
            fetchModPackInfo();
        }
        return availableGameModPacks;
    }

    /**
     * Blocks the calling thread until the background available-modpacks fetch finishes. No-op
     * if no fetch was ever started or the existing fetch has already completed. Must be called
     * outside any GameModPackManager.class synchronized block (the background fetch acquires
     * the same monitor — see {@link #getAvailableModPacks()} for the deadlock note).
     */
    private static void waitForAvailableFetch() {
        CompletableFuture< Void > f = availableFetchFuture;
        if ( f != null && !f.isDone() ) {
            try {
                f.get();
            }
            catch ( Exception e ) {
                Logger.logErrorSilent( "Wait for available-modpacks fetch was interrupted." );
            }
        }
    }

    /**
     * Gets and returns a list of the manifest URLs of mod packs that are currently installed.
     *
     * @return list of installed mod pack manifest URLs
     *
     * @since 1.0
     */
    public synchronized static List< String > getInstalledModPackURLs() {
        // Populate lists if not already done. Only installed packs are needed here, so
        // we deliberately skip the availableGameModPacks null check — that list lazy-loads
        // in the background after startup and would otherwise spuriously trigger a full
        // fetchModPackInfo() re-fetch on every call until the background task settles.
        if ( installedGameModPacks == null ) {
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
    public static List< String > getAvailableModPackFriendlyNames() {
        // Wait for the background fetch outside the class lock — same deadlock note as
        // getAvailableModPacks().
        waitForAvailableFetch();
        return getAvailableModPackFriendlyNamesLocked();
    }

    private synchronized static List< String > getAvailableModPackFriendlyNamesLocked() {
        // Populate lists if not already done (legacy fallback for callers that bypass the
        // async startup path).
        if ( availableGameModPacks == null ) {
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
        if ( installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        // Populate list of installed mod pack manifest URLs and return
        List< String > installedModPackFriendlyNames = new ArrayList<>();
        for ( GameModPack gameModPack : installedGameModPacks ) {
            if ( gameModPack.getFriendlyName() != null ) {
                installedModPackFriendlyNames.add( gameModPack.getFriendlyName() );
            }
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
        if ( installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        // Find matching mod pack and return
        GameModPack foundGameModPack = null;
        for ( GameModPack gameModPack : installedGameModPacks ) {
            if ( gameModPack.getPackName() != null && gameModPack.getPackName().equalsIgnoreCase( packName ) ) {
                foundGameModPack = gameModPack;
            }
        }
        return foundGameModPack;
    }

    /**
     * Gets and returns the mod pack object of the installed mod pack with the specified URL.
     *
     * @param packUrl mod pack URL
     *
     * @return mod pack with specified URL
     *
     * @since 1.0
     */
    public synchronized static GameModPack getInstalledModPackByURL( String packUrl ) {
        // Populate lists if not already done
        if ( installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        // Find matching mod pack and return
        GameModPack foundGameModPack = null;
        for ( GameModPack gameModPack : installedGameModPacks ) {
            if ( gameModPack.getManifestUrl() != null && gameModPack.getManifestUrl().equalsIgnoreCase( packUrl ) ) {
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
        if ( installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        // Find matching mod pack and return
        GameModPack foundGameModPack = null;
        for ( GameModPack gameModPack : installedGameModPacks ) {
            if ( gameModPack.getFriendlyName() != null &&
                    gameModPack.getFriendlyName().equalsIgnoreCase( friendlyName ) ) {
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
        if ( installedGameModPacks == null ) {
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
        if ( installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        // Find matching mod pack and remove
        for ( GameModPack gameModPack : installedGameModPacks ) {
            if ( gameModPack.getManifestUrl() != null && gameModPack.getManifestUrl().equals( url ) ) {
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
        if ( installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        // Find matching mod pack and remove
        for ( GameModPack gameModPack : installedGameModPacks ) {
            final String modPackFriendlyName = gameModPack.getFriendlyName();
            if ( modPackFriendlyName != null && modPackFriendlyName.equals( friendlyName ) ) {
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
        if ( installedGameModPacks == null ) {
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
        if ( installedGameModPacks == null ) {
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
        if ( installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        // Add mod pack
        GameModPack modPack = GameModPackFetcher.get( url,true );
        if ( modPack.getFriendlyName() != null ) {
            installModPack( modPack );
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

    /**
     * Gets and returns a list of the mod packs that are installed.
     *
     * @return list of installed mod packs
     *
     * @since 1.1
     */
    public synchronized static List< GameModPack > getInstalledModPacks() {
        // Populate lists if not already done
        if ( installedGameModPacks == null ) {
            fetchModPackInfo();
        }

        return installedGameModPacks;
    }
}
