/*
 * Copyright (c) 2026 Mica Technologies
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

package com.micatechnologies.minecraft.launcher.mcp.tools;

import com.micatechnologies.minecraft.launcher.LauncherCore;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.game.modpack.ModPackDocument;
import com.micatechnologies.minecraft.launcher.game.modpack.ModPackFileEntry;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The production {@link McpLauncherActions}, driving real launcher operations.
 * <p>
 * Everything here has already passed two gates before it is reached: the tool's own
 * {@code validateBeforeApproval} content check, and the approval engine's consent decision. So
 * this class does not re-litigate whether an action <em>should</em> happen — it does it, and
 * reports what happened in terms a model can act on.
 * <p>
 * <b>Manifest edits are confined to packs the launcher owns.</b> Adding or removing a mod
 * rewrites the pack's manifest, which is only possible when that manifest is a local file this
 * launcher wrote. A pack installed from someone else's URL is not editable in place — the
 * upstream manifest is not ours to change, and silently diverging the local copy from the URL
 * it claims to come from would make the next update overwrite the edit without warning. Those
 * calls fail with an explanation pointing at {@code fork_modpack}.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class LiveMcpLauncherActions implements McpLauncherActions
{
    /** Directory under the launcher config folder holding manifests this launcher authored. */
    static final String AUTHORED_MANIFESTS_DIR = "mcp-manifests";

    @Override
    public Outcome installFromUrl( String url )
    {
        try {
            GameModPackManager.installModPackByURL( url );
            return Outcome.ok( "Installed the modpack from " + url + "." );
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.format( "log.mcpActions.installFailed", url ) );
            Logger.logThrowable( e );
            return Outcome.failed( "The install failed. The URL may not point at a valid modpack "
                                           + "manifest, or the download may have been interrupted." );
        }
    }

    @Override
    public Outcome uninstall( String friendlyName )
    {
        try {
            if ( findPack( friendlyName ) == null ) {
                return Outcome.failed( "No modpack named \"" + friendlyName + "\" is installed." );
            }
            GameModPackManager.uninstallModPackByFriendlyName( friendlyName );
            return Outcome.ok( "Uninstalled \"" + friendlyName + "\" and deleted its files." );
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.format( "log.mcpActions.uninstallFailed", friendlyName ) );
            Logger.logThrowable( e );
            return Outcome.failed( "The uninstall failed. Some files may still be on disk." );
        }
    }

    @Override
    public Outcome launch( String friendlyName, String account )
    {
        GameModPack pack = findPack( friendlyName );
        if ( pack == null ) {
            return Outcome.failed( "No modpack named \"" + friendlyName + "\" is installed." );
        }
        String accountUuid = null;
        if ( account != null && !account.isBlank() ) {
            accountUuid = accountUuidFor( account );
            if ( accountUuid == null ) {
                return Outcome.failed( "No signed-in account is named \"" + account + "\"." );
            }
        }
        final String forcedAccount = accountUuid;
        try {
            // play() prepares and spawns the game before returning; run it off the tool
            // executor, which is single-threaded and shared by every tool call.
            SystemUtilities.spawnNewTask( () -> {
                try {
                    LauncherCore.playAs( pack, forcedAccount );
                }
                catch ( Exception e ) {
                    Logger.logError( LocalizationManager.format( "log.mcpActions.launchFailed", friendlyName ) );
                    Logger.logThrowable( e );
                }
            } );
            // Deliberately reports that the launch *started*. Waiting for the game to finish
            // loading would mean holding the caller for minutes with no way to report progress.
            return Outcome.ok( "Started launching \"" + friendlyName + "\". Its progress shows in the "
                                       + "launcher's Running Games window; call list_running_games to follow it." );
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.format( "log.mcpActions.launchStartFailed", friendlyName ) );
            Logger.logThrowable( e );
            return Outcome.failed( "The launch could not be started." );
        }
    }

    @Override
    public String whyLaunchBlocked( String friendlyName, String account )
    {
        GameModPack pack = findPack( friendlyName );
        if ( pack == null ) {
            return null;  // the tool reports unknown packs itself
        }
        var registry = com.micatechnologies.minecraft.launcher.game.session.GameSessionRegistry.get();
        if ( registry.isPackActive( pack ) ) {
            return "\"" + friendlyName + "\" is already running. Stop it with stop_game first.";
        }
        String accountUuid;
        if ( account != null && !account.isBlank() ) {
            accountUuid = accountUuidFor( account );
            if ( accountUuid == null ) {
                return "No signed-in account is named \"" + account + "\". get_launcher_status names the "
                        + "default account.";
            }
        }
        else {
            var resolution = com.micatechnologies.minecraft.launcher.game.auth.LaunchAccountResolver.resolve(
                    com.micatechnologies.minecraft.launcher.config.ConfigManager.getAccountOverrideForPack( pack.getSettingsKey() ),
                    com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager.accounts().accounts() );
            if ( !resolution.ok() ) {
                return switch ( resolution.problem() ) {
                    case NO_ACCOUNT -> "No account is signed in. Sign in through the launcher first.";
                    case OVERRIDE_MISSING -> "This modpack is set to launch as an account that is no longer "
                            + "signed in. Change it in the launcher, or pass an account.";
                    case NEEDS_SIGN_IN -> "The account this modpack launches as has to sign in again in the launcher.";
                };
            }
            accountUuid = resolution.uuid();
        }
        for ( var session : registry.active() ) {
            if ( accountUuid != null && accountUuid.equals( session.accountUuid() ) ) {
                return "That account is already playing \"" + session.packName() + "\". An account can "
                        + "play one game at a time; pass a different account or stop that game.";
            }
        }
        return null;
    }

    /** The uuid of the signed-in account with this username (case-insensitive), or null. */
    private static String accountUuidFor( String username )
    {
        for ( var info : com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager.accounts().accounts() ) {
            if ( info.displayName().equalsIgnoreCase( username.trim() ) ) {
                return info.uuid();
            }
        }
        return null;
    }

    @Override
    public Outcome stopGame( String friendlyName )
    {
        try {
            for ( var session : com.micatechnologies.minecraft.launcher.game.session.GameSessionRegistry.get().active() ) {
                if ( session.packName() != null && session.packName().equalsIgnoreCase( friendlyName ) ) {
                    if ( session.phase() == com.micatechnologies.minecraft.launcher.game.session.GameSession.Phase.PREPARING ) {
                        session.cancel();
                        return Outcome.ok( "Cancelled the launch of \"" + session.packName() + "\"." );
                    }
                    session.stop( false );
                    return Outcome.ok( "Stopped \"" + session.packName() + "\"." );
                }
            }
            return Outcome.failed( "\"" + friendlyName + "\" is not running." );
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.get( "log.mcpActions.stopGameFailed" ) );
            Logger.logThrowable( e );
            return Outcome.failed( "The game could not be stopped." );
        }
    }

    @Override
    public Outcome createPack( String name, String modLoader, String modLoaderUrl )
    {
        try {
            ModPackDocument document = ModPackDocument.blank();
            document.putString( ModPackDocument.KEY_PACK_NAME, name );
            if ( modLoader != null && !modLoader.isBlank() ) {
                String normalized = modLoader.trim().toLowerCase( Locale.ROOT );
                if ( !isKnownLoader( normalized ) ) {
                    return Outcome.failed( "Unknown mod loader \"" + modLoader
                                                   + "\". Use forge, neoforge, or fabric." );
                }
                document.putString( "packModLoader", normalized );
            }
            if ( modLoaderUrl != null && !modLoaderUrl.isBlank() ) {
                document.putString( "packModLoaderURL", modLoaderUrl.trim() );
            }
            return installAuthored( document, "Created" );
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.format( "log.mcpActions.createFailed", name ) );
            Logger.logThrowable( e );
            return Outcome.failed( "The modpack could not be created." );
        }
    }

    @Override
    public Outcome forkPack( String sourceFriendlyName, String newName )
    {
        try {
            ModPackDocument source = readDocument( sourceFriendlyName );
            if ( source == null ) {
                return Outcome.failed( "Could not read the manifest for \"" + sourceFriendlyName
                                               + "\", so it cannot be forked." );
            }
            return installAuthored( source.forkOf( newName ), "Forked \"" + sourceFriendlyName
                    + "\" into" );
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.format( "log.mcpActions.forkFailed", sourceFriendlyName ) );
            Logger.logThrowable( e );
            return Outcome.failed( "The fork failed." );
        }
    }

    @Override
    public Outcome addMod( String friendlyName, String modName, String remoteUrl, String localPath )
    {
        return editManifest( friendlyName, document -> {
            ModPackFileEntry entry = new ModPackFileEntry( modName, remoteUrl, localPath, "",
                                                           "sha1", true, true );
            if ( !document.addMod( entry ) ) {
                return Outcome.failed( "\"" + friendlyName + "\" already installs something to "
                                               + localPath + "." );
            }
            return Outcome.ok( "Added \"" + modName + "\" to \"" + friendlyName
                                       + "\". It downloads on the next sync." );
        } );
    }

    @Override
    public Outcome removeMod( String friendlyName, String identifier )
    {
        return editManifest( friendlyName, document -> {
            if ( !document.removeMod( identifier ) ) {
                return Outcome.failed( "No mod matching \"" + identifier + "\" is in \""
                                               + friendlyName + "\"." );
            }
            return Outcome.ok( "Removed \"" + identifier + "\" from \"" + friendlyName
                                       + "\". The file goes on the next sync." );
        } );
    }

    /**
     * One edit applied to a pack's manifest document.
     *
     * @since 3.0
     */
    @FunctionalInterface
    private interface ManifestEdit
    {
        /**
         * Applies the edit.
         *
         * @param document the pack's manifest
         *
         * @return what happened; a failure leaves the manifest unwritten
         */
        Outcome apply( ModPackDocument document );
    }

    /**
     * Loads a pack's manifest, applies an edit, and writes it back.
     * <p>
     * Only packs whose manifest this launcher authored are editable — see the class javadoc for
     * why editing someone else's manifest in place is refused rather than attempted.
     *
     * @param friendlyName the pack to edit
     * @param edit         the edit to apply
     *
     * @return what happened
     */
    private Outcome editManifest( String friendlyName, ManifestEdit edit )
    {
        try {
            GameModPack pack = findPack( friendlyName );
            if ( pack == null ) {
                return Outcome.failed( "No modpack named \"" + friendlyName + "\" is installed." );
            }
            Path manifestPath = localManifestPathOf( pack );
            if ( manifestPath == null ) {
                return Outcome.failed( "\"" + friendlyName + "\" was installed from a remote "
                                               + "manifest, which this launcher does not own and "
                                               + "cannot edit. Use fork_modpack to make an "
                                               + "editable copy first." );
            }

            ModPackDocument document = ModPackDocument.fromJson(
                    Files.readString( manifestPath, StandardCharsets.UTF_8 ) );
            Outcome outcome = edit.apply( document );
            if ( !outcome.ok() ) {
                return outcome;
            }

            Files.writeString( manifestPath, document.toPrettyJson(), StandardCharsets.UTF_8 );
            refreshPackList();
            return outcome;
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.format( "log.mcpActions.editManifestFailed", friendlyName ) );
            Logger.logThrowable( e );
            return Outcome.failed( "The manifest could not be updated." );
        }
    }

    /**
     * Writes an authored manifest and installs it.
     * <p>
     * The write never replaces another installed pack's manifest. Pack names that differ only
     * in case, spacing or punctuation map to the same file, and the tools' own name check can
     * be raced, so the file is created with {@code CREATE_NEW}. A file no installed pack points
     * at — left behind when an authored pack was uninstalled — is stale and is cleared first,
     * so uninstalling a pack does not block re-creating it under the same name.
     *
     * @param document the manifest to publish
     * @param verb     how to describe what happened, e.g. {@code "Created"}
     *
     * @return what happened
     *
     * @throws IOException if the manifest cannot be written
     */
    private Outcome installAuthored( ModPackDocument document, String verb ) throws IOException
    {
        Path directory = Path.of( LocalPathManager.getLauncherConfigFolderPath(),
                                  AUTHORED_MANIFESTS_DIR );
        Path target = document.localManifestPathIn( directory );
        if ( Files.exists( target ) && !isManifestOfAnInstalledPack( target, directory ) ) {
            Files.delete( target );
        }
        String url;
        try {
            url = document.writeLocalManifest( directory, false );
        }
        catch ( FileAlreadyExistsException e ) {
            return Outcome.failed( "Another installed modpack already uses the manifest file "
                                           + "that \"" + document.getString( ModPackDocument.KEY_PACK_NAME )
                                           + "\" would be written to; its name differs only in "
                                           + "case, spacing or punctuation. Pick a more distinct name." );
        }
        GameModPackManager.installModPackByURL( url );
        refreshPackList();
        return Outcome.ok( verb + " \"" + document.getString( ModPackDocument.KEY_PACK_NAME )
                                   + "\" and installed it." );
    }

    /**
     * Reports whether an installed pack's manifest URL resolves to a given authored file.
     *
     * @param manifestPath the authored manifest file
     * @param authoredDir  the directory holding manifests this launcher wrote
     *
     * @return {@code true} when some installed pack is installed from that file
     */
    private static boolean isManifestOfAnInstalledPack( Path manifestPath, Path authoredDir )
    {
        Path wanted = manifestPath.toAbsolutePath().normalize();
        for ( GameModPack pack : installedPacks() ) {
            try {
                Path resolved = resolveAuthoredManifest( pack.getManifestUrl(), authoredDir );
                if ( wanted.equals( resolved ) ) {
                    return true;
                }
            }
            catch ( Exception e ) {
                // A pack whose metadata will not load might be the owner; assume it is, so the
                // file is kept and the create fails rather than clobbering it.
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the on-disk path of a pack's manifest when this launcher authored it.
     * <p>
     * A {@code file:} URL alone is not enough — an imported {@code .mmcjson} is also local, but
     * it is the user's file rather than one this feature owns. The path must additionally sit
     * inside {@link #AUTHORED_MANIFESTS_DIR}, checked after normalization so a crafted URL
     * cannot point somewhere else and claim to be ours.
     *
     * @param pack the pack to inspect
     *
     * @return the manifest path, or {@code null} when it is not an authored local manifest
     */
    private static Path localManifestPathOf( GameModPack pack )
    {
        Path authored = Path.of( LocalPathManager.getLauncherConfigFolderPath(),
                                 AUTHORED_MANIFESTS_DIR );
        Path resolved = resolveAuthoredManifest( pack.getManifestUrl(), authored );
        return resolved != null && Files.isRegularFile( resolved ) ? resolved : null;
    }

    /**
     * Resolves a manifest URL to a path inside the authored-manifests directory, or
     * {@code null} when it does not belong to us.
     *
     * <p>Split out from {@link #localManifestPathOf} with no filesystem access of its own, so
     * the containment rule can be tested directly — it is the check that decides whether an
     * edit is permitted, and "does this URL point inside our directory?" is exactly the kind of
     * question that is easy to answer subtly wrongly.</p>
     *
     * <p>A {@code file:} scheme alone is not sufficient. An imported {@code .mmcjson} is also a
     * local file, but it is the user's, not ours. Both paths are normalized before comparison,
     * so a URL threading {@code ..} back out cannot claim to be inside.</p>
     *
     * @param manifestUrl the pack's manifest URL
     * @param authoredDir the directory holding manifests this launcher wrote
     *
     * @return the contained path, or {@code null}
     *
     * @since 3.0
     */
    static Path resolveAuthoredManifest( String manifestUrl, Path authoredDir )
    {
        if ( manifestUrl == null || !manifestUrl.startsWith( "file:" ) || authoredDir == null ) {
            return null;
        }
        try {
            Path path = Path.of( URI.create( manifestUrl ) ).toAbsolutePath().normalize();
            Path authored = authoredDir.toAbsolutePath().normalize();
            // startsWith on Path compares whole name elements, so a sibling directory whose
            // name merely begins with the same characters does not match.
            return path.startsWith( authored ) && !path.equals( authored ) ? path : null;
        }
        catch ( Exception e ) {
            return null;
        }
    }

    /**
     * Reads a pack's manifest into a document, wherever it lives.
     *
     * @param friendlyName the pack to read
     *
     * @return the document, or {@code null} when it cannot be read
     */
    private static ModPackDocument readDocument( String friendlyName )
    {
        GameModPack pack = findPack( friendlyName );
        if ( pack == null ) {
            return null;
        }
        try {
            String manifest = com.micatechnologies.minecraft.launcher.game.modpack.ModpackExporter
                    .loadManifestText( pack );
            return manifest == null ? null : ModPackDocument.fromJson( manifest );
        }
        catch ( Exception e ) {
            return null;
        }
    }

    /**
     * Re-reads the installed-pack list so an edit is visible to later calls.
     */
    private static void refreshPackList()
    {
        try {
            GameModPackManager.fetchModPackInfo();
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( LocalizationManager.get( "log.mcpActions.refreshAfterEditFailed" ) );
        }
    }

    /**
     * Finds an installed pack by friendly name.
     *
     * @param friendlyName the name to look up
     *
     * @return the pack, or {@code null}
     */
    private static GameModPack findPack( String friendlyName )
    {
        if ( friendlyName == null || friendlyName.isBlank() ) {
            return null;
        }
        for ( GameModPack pack : installedPacks() ) {
            try {
                if ( friendlyName.equals( pack.getFriendlyName() ) ) {
                    return pack;
                }
            }
            catch ( Exception ignored ) {
                // A pack whose metadata will not load cannot be the one being asked for.
            }
        }
        return null;
    }

    /**
     * Returns the installed packs, substituting an empty list on failure.
     *
     * @return the installed packs
     */
    private static List< GameModPack > installedPacks()
    {
        try {
            List< GameModPack > packs = GameModPackManager.getInstalledModPacks();
            return packs == null ? List.of() : packs;
        }
        catch ( Exception e ) {
            return List.of();
        }
    }

    /**
     * Reports whether a mod loader identifier is one the launcher understands.
     *
     * @param normalized the lower-cased identifier
     *
     * @return {@code true} when the loader is known
     */
    private static boolean isKnownLoader( String normalized )
    {
        return ModPackConstants.MOD_LOADER_FORGE.equals( normalized )
                || ModPackConstants.MOD_LOADER_NEOFORGE.equals( normalized )
                || ModPackConstants.MOD_LOADER_FABRIC.equals( normalized );
    }
}
