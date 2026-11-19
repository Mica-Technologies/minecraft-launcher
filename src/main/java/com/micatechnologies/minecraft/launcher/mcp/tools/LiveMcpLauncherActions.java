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
import com.micatechnologies.minecraft.launcher.utilities.HashUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
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
            String refused = applyLoader( document, modLoader, modLoaderUrl );
            if ( refused != null ) {
                return Outcome.failed( refused );
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

    /** The largest mod jar {@link #addMod} will download to hash. */
    static final long MAX_MOD_BYTES = 512L * 1024 * 1024;

    /**
     * A downloaded jar's hashes, lower-case hex.
     *
     * @param sha1   the SHA-1
     * @param sha256 the SHA-256
     *
     * @since 2026.11
     */
    record ModHashes( String sha1, String sha256 ) { }

    /**
     * Compares a downloaded jar's hashes with the ones the caller expected.
     *
     * @param actual the downloaded jar's hashes
     * @param sha1   the expected SHA-1, or {@code null}
     * @param sha256 the expected SHA-256, or {@code null}
     *
     * @return a failure message, or {@code null} when everything given matches
     */
    static String hashMismatch( ModHashes actual, String sha1, String sha256 )
    {
        if ( sha256 != null && !sha256.equalsIgnoreCase( actual.sha256() ) ) {
            return "The jar at that URL has SHA-256 " + actual.sha256() + ", not the expected " + sha256
                    + ". Nothing was added.";
        }
        if ( sha1 != null && !sha1.equalsIgnoreCase( actual.sha1() ) ) {
            return "The jar at that URL has SHA-1 " + actual.sha1() + ", not the expected " + sha1
                    + ". Nothing was added.";
        }
        return null;
    }

    /**
     * Builds the manifest entry for a mod, with the SHA-256 as its primary hash and the SHA-1
     * kept beside it, so the sync verifies the file strongest-first.
     *
     * @param modName   the display name
     * @param remoteUrl the download URL
     * @param localPath the jar's file name in the mods folder
     * @param hashes    the jar's hashes
     *
     * @return the entry, required on both sides
     */
    static ModPackFileEntry modEntry( String modName, String remoteUrl, String localPath, ModHashes hashes )
    {
        ModPackFileEntry entry = new ModPackFileEntry( modName, remoteUrl, localPath, hashes.sha256(), "sha256",
                                                       true, true );
        entry.putExtraHash( "sha1", hashes.sha1() );
        return entry;
    }

    /**
     * Adds a mod after downloading it once to record its hashes.
     * <p>
     * A hashless entry cannot be verified by the sync, and a hashless entry at a path whose
     * file is already on disk is never downloaded again, so updating a mod in place silently
     * kept the old jar (issue 101). Hashing at add time costs one download and fixes both.
     */
    @Override
    public Outcome addMod( String friendlyName, String modName, String remoteUrl, String localPath, String sha1,
                           String sha256 )
    {
        String fileName = ModPackDocument.normalizeModLocalPath( localPath );
        Outcome refusal = editRefusal( friendlyName );
        if ( refusal != null ) {
            return refusal;
        }

        ModHashes hashes;
        Path download = null;
        try {
            download = Files.createTempFile( "mmcl-mcp-mod-", ".jar" );
            NetworkUtilities.downloadFileFromURLBounded( URI.create( remoteUrl ).toURL(), download.toFile(),
                                                         MAX_MOD_BYTES );
            hashes = new ModHashes( HashUtilities.getFileSHA1( download.toFile() ),
                                    HashUtilities.getFileSHA256( download.toFile() ) );
        }
        catch ( Exception e ) {
            return Outcome.failed( "Could not download the mod from that URL to record its hash ("
                                           + e.getMessage() + "). Nothing was added." );
        }
        finally {
            if ( download != null ) {
                try {
                    Files.deleteIfExists( download );
                }
                catch ( IOException ignored ) {
                    // A temp file; the OS cleans it up.
                }
            }
        }
        if ( hashes.sha1() == null || hashes.sha256() == null ) {
            return Outcome.failed( "The downloaded mod could not be hashed. Nothing was added." );
        }
        String mismatch = hashMismatch( hashes, sha1, sha256 );
        if ( mismatch != null ) {
            return Outcome.failed( mismatch );
        }

        return editManifest( friendlyName, document -> {
            if ( !document.addMod( modEntry( modName, remoteUrl, fileName, hashes ) ) ) {
                return Outcome.failed( "\"" + friendlyName + "\" already installs a mod to mods/"
                                               + fileName + ". Remove it first to replace it." );
            }
            GameModPack pack = findPack( friendlyName );
            String where = pack != null && pack.getPackRootFolder() != null
                           ? Path.of( pack.getPackRootFolder(), "mods", fileName ).toString()
                           : "mods/" + fileName + " in the pack folder";
            return Outcome.ok( "Added \"" + modName + "\" to \"" + friendlyName + "\" (SHA-256 "
                                       + hashes.sha256() + "). It installs to " + where
                                       + " on the next sync." );
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
     * Switches an optional mod through the same rules the detail dialog uses. Writes the player's
     * config, not the manifest, so it does not go through {@link #editManifest}: a pack installed
     * from a remote manifest has optional mods the player may switch too.
     */
    @Override
    public Outcome setOptionalMod( String friendlyName, String localName, boolean enabled )
    {
        GameModPack pack = findPack( friendlyName );
        if ( pack == null ) {
            return Outcome.failed( "No modpack named \"" + friendlyName + "\" is installed." );
        }
        try {
            List< com.micatechnologies.minecraft.launcher.game.modpack.GameOptionalMod > mods =
                    com.micatechnologies.minecraft.launcher.game.modpack.OptionalMods.of( pack );
            String name = localName;
            for ( var mod : mods ) {
                if ( mod.key().equals( com.micatechnologies.minecraft.launcher.game.modpack.GameOptionalMod.keyOf( localName ) ) ) {
                    name = mod.getDisplayName();
                    break;
                }
            }
            var result = com.micatechnologies.minecraft.launcher.game.modpack.OptionalMods.toggle(
                    pack, mods, localName, enabled );
            return describeToggle( result, friendlyName, name, enabled );
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.format( "log.mcpActions.setOptionalModFailed", friendlyName ) );
            Logger.logThrowable( e );
            return Outcome.failed( "The optional mod could not be changed." );
        }
    }

    /**
     * Words a toggle's outcome for a model: the mod's new state, every other optional mod that
     * switched and why, and when it takes effect.
     *
     * @param result       the toggle's outcome
     * @param friendlyName the pack
     * @param name         the toggled mod's display name
     * @param enabled      the requested state
     *
     * @return the outcome
     */
    static Outcome describeToggle( com.micatechnologies.minecraft.launcher.game.modpack.OptionalModResolver.ToggleResult result,
                                   String friendlyName, String name, boolean enabled )
    {
        if ( !result.applied() ) {
            return Outcome.failed( switch ( result.refusal() ) {
                case UNKNOWN -> "\"" + friendlyName + "\" has no optional mod named \"" + name
                        + "\". Nothing was changed.";
                default -> "\"" + name + "\" cannot be turned on: the optional mods it requires conflict "
                        + "with each other. Nothing was changed.";
            } );
        }
        StringBuilder message = new StringBuilder();
        message.append( "Turned \"" ).append( name ).append( "\" " ).append( enabled ? "on" : "off" )
                .append( " in \"" ).append( friendlyName ).append( "\"." );
        for ( var change : result.changes() ) {
            message.append( ' ' );
            String cause = change.cause() == null || change.cause().isEmpty() ? null : change.cause();
            switch ( change.reason() ) {
                case REQUIRED_BY -> message.append( "Also turned on \"" ).append( change.name() ).append( '"' )
                        .append( cause != null ? ", which \"" + cause + "\" requires." : "." );
                case CONFLICTS_WITH -> message.append( "Turned off \"" ).append( change.name() )
                        .append( "\", which conflicts with \"" ).append( cause ).append( "\"." );
                case NO_LONGER_REQUIRED -> message.append( "Turned off \"" ).append( change.name() ).append( '"' )
                        .append( cause != null ? " too; it was only on because \"" + cause + "\" requires it."
                                               : " too." );
                case CONFLICT_CLEARED -> message.append( "Turned on \"" ).append( change.name() ).append( '"' )
                        .append( cause != null ? " again; it was only off because it conflicts with \"" + cause
                                + "\", which is now off." : " again." );
                default -> message.append( "Turned off \"" ).append( change.name() ).append( '"' )
                        .append( cause != null ? ", which requires \"" + cause + "\"." : "." );
            }
        }
        message.append( " Takes effect the next time the pack launches or syncs." );
        return Outcome.ok( message.toString() );
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
     * Says why a pack's manifest cannot be edited, if it cannot.
     *
     * @param friendlyName the pack
     *
     * @return the refusal, or {@code null} when the pack exists and its manifest is ours
     */
    private Outcome editRefusal( String friendlyName )
    {
        GameModPack pack = findPack( friendlyName );
        if ( pack == null ) {
            return Outcome.failed( "No modpack named \"" + friendlyName + "\" is installed." );
        }
        if ( localManifestPathOf( pack ) == null ) {
            return Outcome.failed( "\"" + friendlyName + "\" was installed from a remote "
                                           + "manifest, which this launcher does not own and "
                                           + "cannot edit. Use fork_modpack to make an "
                                           + "editable copy first." );
        }
        return null;
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
            Outcome refusal = editRefusal( friendlyName );
            if ( refusal != null ) {
                return refusal;
            }
            Path manifestPath = localManifestPathOf( findPack( friendlyName ) );

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
     * Writes a new pack's mod loader into its document: the lower-cased loader type, and the
     * installer URL in {@code packModLoaderURL} (a Cleanroom pack names its installer in
     * {@code packForgeURL} too, one jar for both sides).
     *
     * @param document     the new pack's document
     * @param modLoader    the requested loader, or {@code null} / blank for the default
     * @param modLoaderUrl the installer URL, or {@code null} / blank to leave it unset
     *
     * @return {@code null} when written, or the message refusing an unknown loader
     *
     * @since 2026.11
     */
    static String applyLoader( ModPackDocument document, String modLoader, String modLoaderUrl )
    {
        if ( modLoader != null && !modLoader.isBlank() ) {
            String normalized = modLoader.trim().toLowerCase( Locale.ROOT );
            if ( !isKnownLoader( normalized ) ) {
                return "Unknown mod loader \"" + modLoader + "\". Use forge, neoforge, fabric, or cleanroom.";
            }
            document.putString( "packModLoader", normalized );
        }
        if ( modLoaderUrl != null && !modLoaderUrl.isBlank() ) {
            document.putString( "packModLoaderURL", modLoaderUrl.trim() );
            if ( ModPackConstants.MOD_LOADER_CLEANROOM.equals( document.getString( "packModLoader" ) ) ) {
                document.putString( "packForgeURL", modLoaderUrl.trim() );
            }
        }
        return null;
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
                || ModPackConstants.MOD_LOADER_FABRIC.equals( normalized )
                || ModPackConstants.MOD_LOADER_CLEANROOM.equals( normalized );
    }
}
