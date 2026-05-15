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
import com.micatechnologies.minecraft.launcher.gui.MCLauncherProgressGui;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
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
     * Listener for non-fatal errors from background tasks (cache warming,
     * available-pack fetch, install-index revalidate) that don't have a
     * {@link GameModPackProgressProvider} attached. Lets the launcher's
     * UI subscribe once and surface a notification toast instead of every
     * background-task failure being silently logged. {@code null} when no
     * listener is registered (e.g. before the GUI is up, or in headless
     * server mode).
     *
     * <p>{@code volatile} so the GUI thread's set-once at startup is
     * visible to the background-task threads that fire it without a
     * synchronized block on the hot path.</p>
     *
     * @since 2026.5
     */
    private static volatile java.util.function.BiConsumer< String, Throwable > backgroundErrorListener = null;

    /** Sets (or clears with {@code null}) the listener invoked when a
     *  background task hits a non-fatal error. Idempotent — last writer
     *  wins. Wire this once from the launcher session right after the
     *  GUI is up; clear it during shutdown. */
    public static void setBackgroundErrorListener( java.util.function.BiConsumer< String, Throwable > listener ) {
        backgroundErrorListener = listener;
    }

    /** Invokes {@link #backgroundErrorListener} with {@code message} +
     *  {@code cause}, swallowing any exception the listener throws so a
     *  bad UI handler can't crash the background task that called it.
     *  No-op when no listener is registered. */
    static void fireBackgroundError( String message, Throwable cause ) {
        java.util.function.BiConsumer< String, Throwable > listener = backgroundErrorListener;
        if ( listener == null ) return;
        try {
            listener.accept( message, cause );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "background-error listener threw: " + t.getClass().getSimpleName() );
        }
    }

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

        // Phase 1a — index-first ultra-fast path. The unified install index
        // (manifests/install_index.json) holds the card-rendering subset of
        // every previously-fetched pack in a single file, so this loop opens
        // ONE file regardless of how many packs the user has installed —
        // critical once CurseForge / Modrinth imports push installed counts
        // into the hundreds. The stubs returned here carry name/version/image
        // URLs/RAM/flags only; full manifests (mods, forge URL, scan rules)
        // are loaded by the per-manifest cache pass below and by Phase 2's
        // network revalidate.
        InstallIndex index = InstallIndex.load();
        final List< String > needsFullLoad = new java.util.ArrayList<>();
        for ( String manifestUrl : installedModPackManifestUrls ) {
            InstallIndex.Entry entry = index.get( manifestUrl );
            if ( entry != null ) {
                installedGameModPacks.add( GameModPack.createStubFromIndex( manifestUrl, entry ) );
            }
            // Even when the stub is in hand we still need to load the full
            // manifest into a real pack object before Play can fire — that's
            // the per-manifest cache pass.
            needsFullLoad.add( manifestUrl );
        }

        // Phase 1b — per-manifest cache load for full data. Replaces each
        // stub in-place once the full pack is parsed so the cards keep their
        // identity in the FlowPane but pick up real mod lists / forge config.
        // Any URL whose per-manifest cache is missing falls through to the
        // synchronous network path below.
        final List< String > needsNetwork = new java.util.ArrayList<>();
        for ( String manifestUrl : needsFullLoad ) {
            GameModPack cached = GameModPackFetcher.getFromCache( manifestUrl, true );
            if ( cached != null ) {
                replaceOrAppendByUrl( installedGameModPacks, manifestUrl, cached );
            }
            else if ( !installedGameModPacks.stream().anyMatch( p ->
                    manifestUrl.equals( p.getManifestUrl() ) ) ) {
                // No stub AND no cached body — must hit network.
                needsNetwork.add( manifestUrl );
            }
            else {
                // We have a stub but no per-manifest cache (unusual — cache file
                // was deleted out-of-band). Schedule a network fetch so the stub
                // gets promoted to a real pack rather than staying half-loaded.
                needsNetwork.add( manifestUrl );
            }
        }

        // Phase 1b — synchronous network for any pack we couldn't load from cache.
        // Same parallelStream as before; the loop only fires when the cache misses,
        // which on a warm launcher is empty.
        final MCLauncherProgressGui finalProgressWindow = progressWindow;
        if ( !needsNetwork.isEmpty() ) {
            needsNetwork.parallelStream().forEach( manifestUrl -> {
                try {
                    GameModPack gameModPack = GameModPackFetcher.get( manifestUrl, true );
                    installedGameModPacks.add( gameModPack );
                    try {
                        ModPackUpdateLog.recordRemoteVersionSeen( gameModPack );
                    }
                    catch ( Throwable t ) {
                        Logger.logWarningSilent( "Update-log record failed for "
                                                         + gameModPack.getPackName() + ": " + t.getMessage() );
                    }
                    if ( finalProgressWindow != null ) {
                        finalProgressWindow.setDetailText( LocalizationManager.GOT_LATEST_VERSION_OF_TEXT
                                                                   + " " + gameModPack.getPackName()
                                                                   + " (v" + gameModPack.getPackVersion() + ")" );
                    }
                }
                catch ( Exception e ) {
                    Logger.logError( LocalizationManager.UNABLE_CREATE_OBJ_FOR_INSTALLED_MOD_PACK_FROM_TEXT
                                             + " " + manifestUrl );
                    Logger.logThrowable( e );
                }
            } );
        }

        // Phase 2 — background revalidate. Kick off network fetches for every pack
        // (including the ones we already loaded from cache) so the cache stays current
        // and any manifest changes show up in the UI within a few seconds of cold
        // start. The future is exposed via getInstalledRevalidateFuture so the main
        // menu can attach a re-render hook and a small "Refreshing modpacks…"
        // indicator.
        if ( !NetworkUtilities.isOffline() && !installedModPackManifestUrls.isEmpty() ) {
            startInstalledRevalidateAsync( installedModPackManifestUrls );
        }

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
     * Background future for the Phase 2 revalidation pass of {@link #fetchInstalledModPacks}.
     * Lets the main menu show a "Refreshing modpacks…" indicator and trigger a
     * card-grid re-render once the future settles.
     *
     * @since 3.5
     */
    private static volatile CompletableFuture< Void > installedRevalidateFuture = null;

    /**
     * Returns the in-flight installed-modpack revalidation future, or {@code null} if
     * no revalidation has been started (offline run, no installed packs, etc.).
     *
     * @since 3.5
     */
    public static CompletableFuture< Void > getInstalledRevalidateFuture() {
        return installedRevalidateFuture;
    }

    /**
     * Reports whether the background installed-modpack revalidation is still running.
     *
     * @since 3.5
     */
    public static boolean isInstalledRevalidatePending() {
        CompletableFuture< Void > f = installedRevalidateFuture;
        return f != null && !f.isDone();
    }

    /**
     * Replaces the {@link GameModPack} in {@code list} whose manifest URL matches
     * {@code manifestUrl} with {@code fresh}, preserving the entry's index in the
     * list so external observers (the FlowPane, the recent-played sort) don't see
     * the slot move. Appends {@code fresh} if no entry matches. Used to upgrade
     * an index-stub into a fully-loaded pack without rebuilding the list.
     *
     * @since 2026.3
     */
    private static void replaceOrAppendByUrl( List< GameModPack > list, String manifestUrl, GameModPack fresh )
    {
        if ( list == null || manifestUrl == null || fresh == null ) return;
        for ( int i = 0; i < list.size(); i++ ) {
            GameModPack current = list.get( i );
            if ( current != null && manifestUrl.equals( current.getManifestUrl() ) ) {
                list.set( i, fresh );
                return;
            }
        }
        list.add( fresh );
    }

    /**
     * Kicks off the background revalidate pass. Each pack's manifest is re-fetched
     * in parallel; entries whose freshly-fetched version differs from the cache-loaded
     * one are swapped into {@link #installedGameModPacks} in place.
     */
    private static void startInstalledRevalidateAsync( List< String > manifestUrls ) {
        installedRevalidateFuture = CompletableFuture.runAsync( () -> {
            try {
                manifestUrls.parallelStream().forEach( manifestUrl -> {
                    try {
                        GameModPack fresh = GameModPackFetcher.get( manifestUrl, true );
                        if ( fresh == null ) return;
                        // Walk the live list and replace the matching entry. CopyOnWriteArrayList
                        // semantics: set() is atomic per index. Match by manifest URL since
                        // pack names can change across versions.
                        for ( int i = 0; i < installedGameModPacks.size(); i++ ) {
                            GameModPack current = installedGameModPacks.get( i );
                            if ( current != null && manifestUrl.equals( current.getManifestUrl() ) ) {
                                installedGameModPacks.set( i, fresh );
                                break;
                            }
                        }
                        try {
                            ModPackUpdateLog.recordRemoteVersionSeen( fresh );
                        }
                        catch ( Throwable t ) {
                            Logger.logWarningSilent( "Update-log record failed for "
                                                             + fresh.getPackName() + ": " + t.getMessage() );
                        }
                    }
                    catch ( Throwable t ) {
                        // Per-pack failure is non-fatal — the cached version stays in the list.
                        Logger.logWarningSilent( "Background revalidate failed for "
                                                         + manifestUrl + ": " + t.getMessage() );
                    }
                } );
            }
            catch ( Throwable t ) {
                Logger.logErrorSilent( "Installed-modpack background revalidate failed." );
                Logger.logThrowable( t );
                fireBackgroundError(
                        "Couldn't refresh installed-modpack info — using cached data.", t );
            }
        } );
    }

    /**
     * Method to handle populating the list of installed mod packs, then the list of mod packs that are available for
     * install. If the application is in a state that support a graphical user interface, one will be displayed to show
     * the progress of this task.
     *
     * @since 1.0
     */
    public synchronized static void fetchModPackInfo() {
        // No more full-screen progress GUI takeover here. Historically every
        // install / uninstall / refresh routed through this method and saw the
        // launcher swap to a dedicated loading scene, then back to the original
        // view — jarring, especially for a sub-second refresh on a warm cache,
        // and visually inconsistent with the rest of the launcher (the main
        // menu and Library already use a subtle bottom-bar "Refreshing
        // modpacks…" indicator for the same kind of background work). Callers
        // that want to surface progress for a long-running op now flip their
        // own bottom-bar label / show a placeholder card and let this method
        // do its thing silently.
        // checkNetworkAvailability primes NetworkUtilities.offlineMode so
        // the isOffline() guard below sees a fresh signal; return value is
        // intentionally discarded since we only need the side-effect.
        NetworkUtilities.checkNetworkAvailability();
        fetchInstalledModPacks( null );
        if ( !NetworkUtilities.isOffline() ) {
            fetchAvailableModPacks( null );
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
                    fireBackgroundError(
                            "Couldn't load the available-modpacks list — Browse will show only installed packs.", t );
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
     * Non-blocking variant of {@link #getAvailableModPacks()}. Returns whatever's currently in
     * the available-packs cache without waiting for the background fetch — empty list if the
     * fetch hasn't yet populated the cache.
     *
     * <p>Use this when the caller is rendering UI on the FX thread and wants to paint
     * <em>something</em> immediately rather than stalling for a cold network. Callers
     * should pair this with {@link #getAvailableFetchFuture()}.whenComplete(...) so the
     * UI re-renders once the real data arrives.</p>
     *
     * @return the current available-packs list (possibly empty); never {@code null}
     *
     * @since 3.5
     */
    public synchronized static List< GameModPack > getAvailableModPacksIfReady() {
        if ( availableGameModPacks == null ) {
            return Collections.emptyList();
        }
        return availableGameModPacks;
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
            // Drop the matching install-index entry so the next cold start
            // doesn't paint a ghost card for an uninstalled pack.
            try {
                String url = gameModPack.getManifestUrl();
                if ( url != null && !url.isBlank() ) {
                    InstallIndex idx = InstallIndex.load();
                    idx.remove( url );
                    idx.save();
                }
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( "Install-index cleanup failed for uninstall: "
                                                 + t.getClass().getSimpleName() );
            }
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
