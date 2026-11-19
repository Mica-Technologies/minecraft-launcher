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

import com.micatechnologies.minecraft.launcher.config.GameModeManager;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.utilities.DownloadExecutor;
import com.micatechnologies.minecraft.launcher.utilities.DownloadTracker;
import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Handles downloading and verification of modpack content files (mods, configs, resource packs, shader packs, and
 * initial files). Extracted from {@link GameModPack} to separate file synchronization concerns from game lifecycle
 * management.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
class GameModPackFileSync
{
    /**
     * The modpack metadata supplying the per-category file lists, the pack root
     * folder, and the imported-pack flag that drives the sync behaviour.
     */
    private final GameModPackMetadata         metadata;

    /**
     * The progress callback used to surface per-file status and percent-complete
     * updates to the UI, or {@code null} when progress reporting is not required.
     */
    private final GameModPackProgressProvider  progressProvider;

    /**
     * The aggregate download tracker (total / completed file counts) for the
     * current sync, derived from {@link #progressProvider}; {@code null} when no
     * progress provider was supplied.
     */
    private final DownloadTracker              downloadTracker;

    /**
     * Sidecar in the pack root listing the identity keys of every optional mod the manifest
     * declared at the last successful mod sync, one per line. {@link PendingUpdateDiff} reads it
     * to tell an optional mod that is new in an update from one the player simply left off.
     *
     * @since 2026.11
     */
    static final String KNOWN_OPTIONAL_MODS_FILE = ".known_optional_mods";

    /**
     * The mods this sync installs and keeps on the current side: every {@code packMods} entry plus
     * the optional mods that are on (see {@link OptionalModResolver#selectForSide}). {@code null}
     * exactly when {@code packMods} is null, i.e. when there is no authoritative mod list.
     *
     * @since 2026.11
     */
    private final List< GameMod >              syncedMods;

    /**
     * Creates a new file sync handler for the specified modpack.
     *
     * @param metadata         the modpack metadata (provides paths, file lists, etc.)
     * @param progressProvider the progress callback, or null if progress reporting is not needed
     */
    GameModPackFileSync( GameModPackMetadata metadata, GameModPackProgressProvider progressProvider )
    {
        this.metadata = metadata;
        this.progressProvider = progressProvider;
        this.downloadTracker = ( progressProvider != null ) ? progressProvider.getDownloadTracker() : null;

        // The player's optional-mod choices only matter on a client: a dedicated server has no one
        // to choose, and installs only the optional mods that are server-side and on by default.
        GameMode side = GameModeManager.getCurrentGameMode();
        Map< String, Boolean > choices = side == GameMode.CLIENT && metadata instanceof GameModPack pack
                                         ? OptionalMods.choicesFor( pack )
                                         : Map.of();
        this.syncedMods = modsForSide( metadata, side, choices );

        // Pre-count total files across all categories so the tracker shows accurate totals from the start
        if ( downloadTracker != null ) {
            long totalFiles = 0;
            if ( syncedMods != null ) totalFiles += syncedMods.size();
            if ( metadata.packConfigs != null ) totalFiles += metadata.packConfigs.size();
            if ( metadata.packResourcePacks != null && !GameModeManager.isServer() )
                totalFiles += metadata.packResourcePacks.size();
            if ( metadata.packShaderPacks != null && !GameModeManager.isServer() )
                totalFiles += metadata.packShaderPacks.size();
            if ( metadata.packInitialFiles != null ) totalFiles += metadata.packInitialFiles.size();
            downloadTracker.setTotalFileCount( totalFiles );
        }
    }

    /**
     * Verifies the integrity of local copies of this mod pack's mods and repair/download/update as necessary.
     *
     * @throws ModpackException if unable to fetch latest mods
     */
    void fetchLatestMods() throws ModpackException
    {
        // Imported packs (Prism / MultiMC instance imports) carry their
        // own user-curated mods/ folder that was never authored as a
        // Mica manifest. The packMods list is empty, so the regular
        // clearFloatingMods sweep would wipe everything the user just
        // imported. Skip both the cleanup and the download loop — there's
        // nothing to download. The user manages this pack's mods through
        // the detail-modal Mods section (toggle / Modrinth update check)
        // and any direct filesystem edits.
        if ( metadata.isImportedSkipSync() ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( LocalizationManager.get( "fileSync.progress.importedSkip" ), 100 );
            }
            return;
        }

        // A null/absent mod list means one of two very different things, and conflating them is
        // exactly what let a headless server boot with an empty mods folder: either the manifest
        // genuinely declares no mods, or we never loaded a manifest at all (an install-index stub
        // whose full body was never fetched, or a failed-fetch sentinel). Only the former is safe
        // to treat as "nothing to do" — the latter must abort the launch, loudly, before any
        // sweeping or launching happens. Checked before clearFloatingMods so a half-loaded pack
        // can't touch the installed files at all.
        // (The stub/failed-load flags live on GameModPack, the only concrete subclass; the sync
        // holds the abstract metadata type, so match on it rather than widening the base class.)
        if ( metadata instanceof GameModPack pack && ( pack.isStub() || pack.isFailedLoad() ) ) {
            throw new ModpackException( pack.isFailedLoad()
                    ? LocalizationManager.format( "fileSync.error.manifestFetchFailed", pack.getPackName() )
                    : LocalizationManager.format( "fileSync.error.manifestStub", pack.getPackName() ) );
        }

        // Cleanup mods that don't belong
        clearFloatingMods();
        if ( progressProvider != null ) {
            progressProvider.submitProgress( LocalizationManager.get( "fileSync.progress.floatingRemoved" ), 30.0 );
        }

        // Check if mods supplied. Reaching here with a null list means a fully-loaded manifest that
        // genuinely declares no mods (e.g. a vanilla-version pack), which is a legitimate no-op.
        if ( metadata.packMods == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( LocalizationManager.get( "fileSync.progress.noMods" ), 100 );
            }
            return;
        }

        // Get full path to mods folder
        String modLocalPathPrefix = metadata.getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_MODS_LOCAL_FOLDER;

        // Build list of mod download tasks on the shared bounded download pool.
        // Guarded on non-empty rather than size() > 1: the old `> 1` test silently skipped the
        // entire download block for a single-mod pack, which then launched with that mod missing.
        if ( !syncedMods.isEmpty() ) {
            final int modCount = syncedMods.size();
            // Mods download concurrently on the shared pool, so a per-mod "Downloading X"
            // detail line is last-writer-wins across ~16 threads — the name shown rarely
            // matches the file actually transferring (the "wrong mod name" bug, and the
            // frozen name that makes a slow sibling look stuck). Report an aggregate
            // completed-count instead, which is correct no matter which pool thread writes
            // it; the live speed/ETA still comes from the shared DownloadTracker.
            final java.util.concurrent.atomic.AtomicInteger modsProcessed =
                    new java.util.concurrent.atomic.AtomicInteger( 0 );
            if ( progressProvider != null ) {
                progressProvider.setCurrText( LocalizationManager.format( "fileSync.progress.downloadingMods", 0, modCount ) );
            }
            List< Future< Boolean > > threadPoolFutures = new ArrayList<>();
            for ( GameMod mod : syncedMods ) {
                Callable< Boolean > updateFileCallable = () -> {
                    mod.setLocalPathPrefix( modLocalPathPrefix );
                    if ( downloadTracker != null ) {
                        mod.setDownloadTracker( downloadTracker );
                    }

                    boolean ret = mod.updateLocalFile( GameModeManager.getCurrentGameMode(), metadata.getPrepareContext() );

                    int done = modsProcessed.incrementAndGet();
                    if ( progressProvider != null ) {
                        progressProvider.submitProgress( LocalizationManager.format( "fileSync.progress.downloadingMods", done, modCount ),
                                                         ( 70.0 / ( double ) modCount ) );
                    }
                    if ( downloadTracker != null ) {
                        downloadTracker.completeFile();
                    }
                    return ret;
                };
                Future< Boolean > future = DownloadExecutor.submit( updateFileCallable );
                threadPoolFutures.add( future );
            }
            // Drain the futures on the shared pool, bounded at 30 minutes. awaitAll
            // cancels still-pending siblings on interrupt/timeout/failure.
            try {
                DownloadExecutor.awaitAll( threadPoolFutures, 30 * 60 * 1000L );
            }
            catch ( TimeoutException e ) {
                throw new ModpackException( LocalizationManager.get( "fileSync.error.modsTimedOut" ) );
            }
            catch ( InterruptedException e ) {
                Thread.currentThread().interrupt();
                throw new ModpackException( LocalizationManager.get( "fileSync.error.modsInterrupted" ), e );
            }
            catch ( ExecutionException e ) {
                throw new ModpackException( LocalizationManager.format( "fileSync.error.modsFailed",
                        e.getCause() != null ? e.getCause().getMessage() : e.getMessage() ), e );
            }
        }

        // Post-sync existence assertion. Everything above reports success per-file, but nothing
        // downstream ever confirmed the bytes actually landed on disk — so a sync that wrote
        // nothing at all was indistinguishable from a real one, and the game launched anyway.
        // This is the cheap, unconditional backstop: every mod the manifest declares must exist
        // as a non-empty file before we hand the classpath to the JVM. Hash correctness is the
        // verify layer's job; this only catches "the file isn't even there." Mods not required on
        // this side (e.g. server-only mods on a client) were skipped by updateLocalFile on purpose,
        // so they're excluded here too — otherwise every client launch of a pack with server-only
        // mods would abort.
        final GameMode currentMode = GameModeManager.getCurrentGameMode();
        final List< String > missingMods = new ArrayList<>();
        int requiredModCount = 0;
        for ( GameMod mod : syncedMods ) {
            if ( !mod.isRequiredFor( currentMode ) ) {
                continue;
            }
            requiredModCount++;
            mod.setLocalPathPrefix( modLocalPathPrefix );
            File modFile = SynchronizedFileManager.getSynchronizedFile( mod.getFullLocalFilePath() );
            if ( modFile == null || !modFile.isFile() || modFile.length() == 0L ) {
                missingMods.add( FilenameUtils.getName( mod.getFullLocalFilePath() ) );
            }
        }
        if ( !missingMods.isEmpty() ) {
            throw new ModpackException( LocalizationManager.format( "fileSync.error.modsMissing",
                    metadata.getPackName(), missingMods.size(), requiredModCount,
                    String.join( ", ", missingMods ) ) );
        }
        Logger.logDebug( LocalizationManager.format( "log.gameModPackFileSync.modCheckPassed", metadata.getPackName(), requiredModCount ) );
        rememberOptionalMods();
    }

    /**
     * Records which optional mods the manifest declared at this successful sync, so the next
     * pending-update diff can tell a newly added optional mod from one the player left off. Best
     * effort: a failed write only makes the next diff list existing optional mods as new.
     */
    private void rememberOptionalMods()
    {
        List< String > keys = new ArrayList<>();
        for ( GameOptionalMod mod : metadata.getPackOptionalMods() ) {
            if ( !mod.key().isEmpty() ) {
                keys.add( mod.key() );
            }
        }
        try {
            PackDotFile.write( metadata.getPackRootFolder(), KNOWN_OPTIONAL_MODS_FILE, String.join( "\n", keys ) );
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.gameModPackFileSync.knownOptionalWriteFailed",
                                                                 metadata.getPackName() ) );
        }
    }

    /**
     * Lists the mods a sync installs and keeps on one side: every {@code packMods} entry, then the
     * optional mods {@link OptionalModResolver#selectForSide} picks. An optional mod whose file is
     * also a {@code packMods} entry is skipped (the manifest is invalid; the required entry wins).
     * Side filtering of the {@code packMods} entries themselves stays with
     * {@link GameMod#isRequiredFor}, as before.
     *
     * @param metadata the pack
     * @param side     the side being synced
     * @param choices  the player's optional-mod choices; ignored on a server
     *
     * @return the mods, or {@code null} when the pack has no authoritative mod list
     *         ({@code packMods} is null)
     *
     * @since 2026.11
     */
    static List< GameMod > modsForSide( GameModPackMetadata metadata, GameMode side, Map< String, Boolean > choices )
    {
        if ( metadata.packMods == null ) {
            return null;
        }
        List< GameMod > mods = new ArrayList<>( metadata.packMods );
        List< GameOptionalMod > optional = metadata.getPackOptionalMods();
        if ( optional.isEmpty() ) {
            return mods;
        }
        Set< String > requiredNames = new HashSet<>();
        for ( GameMod mod : metadata.packMods ) {
            requiredNames.add( GameOptionalMod.keyOf( mod.getLocalFilePath() == null ? null
                                                          : mod.getLocalFilePath().replace( '\\', '/' ) ) );
        }
        List< GameOptionalMod > selected = OptionalModResolver.selectForSide( optional, side, choices );
        for ( GameOptionalMod mod : selected ) {
            if ( !requiredNames.contains( mod.key() ) ) {
                mods.add( mod );
            }
        }
        Logger.logDebug( LocalizationManager.format( "log.gameModPackFileSync.optionalSelected", metadata.getPackName(),
                                                     selected.size(), optional.size() ) );
        return Collections.unmodifiableList( mods );
    }

    /**
     * Verifies the integrity of local copies of this mod pack's configs and repair/download/update as necessary.
     *
     * @throws ModpackException if unable to fetch latest configs
     */
    void fetchLatestConfigs() throws ModpackException
    {
        // Get full path to configs folder
        String configLocalPathPrefix = metadata.getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_CONFIGS_LOCAL_FOLDER;

        // Check if configs supplied
        if ( metadata.packConfigs == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( LocalizationManager.get( "fileSync.progress.noConfigs" ), 100 );
            }
            return;
        }

        // Update each config if necessary
        for ( GameAsset config : metadata.packConfigs ) {
            config.setLocalPathPrefix( configLocalPathPrefix );
            if ( downloadTracker != null ) {
                config.setDownloadTracker( downloadTracker );
            }
            if ( progressProvider != null ) {
                progressProvider.setCurrText( LocalizationManager.format( "fileSync.progress.downloading", FilenameUtils.getName( config.getFullLocalFilePath() ) ) );
            }
            config.updateLocalFile( GameModeManager.getCurrentGameMode(), metadata.getPrepareContext() );

            if ( progressProvider != null ) {
                progressProvider.submitProgress( LocalizationManager.format( "fileSync.progress.verified", FilenameUtils.getName( config.getFullLocalFilePath() ) ),
                                                 ( 100.0 / ( double ) metadata.packConfigs.size() ) );
            }
            if ( downloadTracker != null ) {
                downloadTracker.completeFile();
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this mod pack's resource packs and repair/download/update as
     * necessary.
     *
     * @throws ModpackException if unable to fetch latest resource packs
     */
    void fetchLatestResourcePacks() throws ModpackException
    {
        // Only download resource packs on client (not server)
        if ( GameModeManager.isServer() ) {
            return;
        }

        // Check if resource packs supplied
        if ( metadata.packResourcePacks == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( LocalizationManager.get( "fileSync.progress.noResourcePacks" ), 100 );
            }
            return;
        }

        // Get full path to resource pack folder
        String resPackLocalPathPrefix = metadata.getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_RESOURCEPACKS_LOCAL_FOLDER;

        // Update each resource pack if necessary
        for ( ManagedGameFile resourcePack : metadata.packResourcePacks ) {
            resourcePack.setLocalPathPrefix( resPackLocalPathPrefix );
            if ( downloadTracker != null ) {
                resourcePack.setDownloadTracker( downloadTracker );
            }
            if ( progressProvider != null ) {
                progressProvider.setCurrText( LocalizationManager.format( "fileSync.progress.downloading", FilenameUtils.getName( resourcePack.getFullLocalFilePath() ) ) );
            }
            resourcePack.updateLocalFile( metadata.getPrepareContext() );
            if ( progressProvider != null ) {
                progressProvider.submitProgress(
                        LocalizationManager.format( "fileSync.progress.verified", FilenameUtils.getName( resourcePack.getFullLocalFilePath() ) ),
                        ( 100.0 / ( double ) metadata.packResourcePacks.size() ) );
            }
            if ( downloadTracker != null ) {
                downloadTracker.completeFile();
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this mod pack's shader packs and repair/download/update as necessary.
     *
     * @throws ModpackException if unable to fetch latest shader packs
     */
    void fetchLatestShaderPacks() throws ModpackException
    {
        // Only download shader packs on client (not server)
        if ( GameModeManager.isServer() ) {
            return;
        }

        // Check if shader packs supplied
        if ( metadata.packShaderPacks == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( LocalizationManager.get( "fileSync.progress.noShaderPacks" ), 100 );
            }
            return;
        }

        // Get full path to shader pack folder
        String shaderPackLocalPathPrefix = metadata.getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_SHADERPACKS_LOCAL_FOLDER;

        // Update each shader pack if necessary
        for ( ManagedGameFile shaderPack : metadata.packShaderPacks ) {
            shaderPack.setLocalPathPrefix( shaderPackLocalPathPrefix );
            if ( downloadTracker != null ) {
                shaderPack.setDownloadTracker( downloadTracker );
            }
            if ( progressProvider != null ) {
                progressProvider.setCurrText( LocalizationManager.format( "fileSync.progress.downloading", FilenameUtils.getName( shaderPack.getFullLocalFilePath() ) ) );
            }
            shaderPack.updateLocalFile( metadata.getPrepareContext() );
            if ( progressProvider != null ) {
                progressProvider.submitProgress(
                        LocalizationManager.format( "fileSync.progress.verified", FilenameUtils.getName( shaderPack.getFullLocalFilePath() ) ),
                        ( 100.0 / ( double ) metadata.packShaderPacks.size() ) );
            }
            if ( downloadTracker != null ) {
                downloadTracker.completeFile();
            }
        }
    }

    /**
     * Verifies the integrity of local copies of this mod pack's initial files and repair/download/update as necessary.
     *
     * @throws ModpackException if unable to fetch latest initial files
     */
    void fetchLatestInitialFiles() throws ModpackException
    {
        // Get full path to configs folder
        String initFilesLocalPathPrefix = metadata.getPackRootFolder();

        // Note: log4j security configs are now downloaded and applied in applyLog4jSecurityConfig()
        // during startGame(), using the correct version-specific config for the Minecraft version.

        // Check if initial files supplied
        if ( metadata.packInitialFiles == null ) {
            if ( progressProvider != null ) {
                progressProvider.submitProgress( LocalizationManager.get( "fileSync.progress.noInitialFiles" ), 100 );
            }
            return;
        }

        // Update each initial file if necessary
        for ( GameAsset initFile : metadata.packInitialFiles ) {
            initFile.setLocalPathPrefix( initFilesLocalPathPrefix );
            if ( downloadTracker != null ) {
                initFile.setDownloadTracker( downloadTracker );
            }
            if ( progressProvider != null ) {
                progressProvider.setCurrText( LocalizationManager.format( "fileSync.progress.downloading", FilenameUtils.getName( initFile.getFullLocalFilePath() ) ) );
            }
            initFile.updateLocalFile( GameModeManager.getCurrentGameMode(), metadata.getPrepareContext() );

            if ( progressProvider != null ) {
                progressProvider.submitProgress( LocalizationManager.format( "fileSync.progress.verified", FilenameUtils.getName( initFile.getFullLocalFilePath() ) ),
                                                 ( 100.0 / ( double ) metadata.packInitialFiles.size() ) );
            }
            if ( downloadTracker != null ) {
                downloadTracker.completeFile();
            }
        }
    }

    /**
     * Remove all mods from this modpack mods folder that are not in the modpack manifest.
     */
    private void clearFloatingMods()
    {
        // CRITICAL data-loss guard: a null packMods means we have NO authoritative mod list —
        // the manifest failed to load (createFailedModPack leaves packMods null) or the pack is
        // an unpopulated stub. In that state EVERY installed mod counts as "floating" and the
        // sweep below would delete the entire mods folder, leaving the game to launch with no
        // mods (the reported empty-mods-folder wipe). Only sweep when we actually have a manifest
        // mod list to compare against. A genuinely empty (non-null) list is still a valid
        // "remove all floating mods" instruction and is allowed through.
        if ( metadata.packMods == null ) {
            Logger.logWarningSilent( LocalizationManager.get( "log.gameModPackFileSync.floatingCleanupSkipped" ) );
            return;
        }

        // Get full path to modpack mods folder
        String modLocalPathPrefix = metadata.getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_FORGE_MODS_LOCAL_FOLDER;

        // Build list of valid mods: the required mods for this side plus the optional mods that
        // are on. An optional mod that is off is not in the list, so a jar left from when it was
        // on is removed here like any other file that does not belong.
        ArrayList< String > validModPaths = new ArrayList<>();
        for ( GameMod mod : syncedMods ) {
            if ( ( GameModeManager.isClient() && mod.clientReq ) ||
                    ( GameModeManager.isServer() && mod.serverReq ) ) {
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
                        Logger.logError( LocalizationManager.get( "log.gameModPackFileSync.sanitizeDeleteFailed" ) );
                    }
                }
            }
        }
    }
}
