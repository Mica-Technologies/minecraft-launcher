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
            Logger.logError( "MCP install failed for " + url );
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
            Logger.logError( "MCP uninstall failed for " + friendlyName );
            Logger.logThrowable( e );
            return Outcome.failed( "The uninstall failed. Some files may still be on disk." );
        }
    }

    @Override
    public Outcome launch( String friendlyName )
    {
        GameModPack pack = findPack( friendlyName );
        if ( pack == null ) {
            return Outcome.failed( "No modpack named \"" + friendlyName + "\" is installed." );
        }
        try {
            // play() drives the whole launch and blocks until the game exits, so it cannot run
            // on the tool executor -- that thread is single-threaded and shared, and holding it
            // for a play session would stall every other tool call for hours.
            SystemUtilities.spawnNewTask( () -> {
                try {
                    LauncherCore.play( pack );
                }
                catch ( Exception e ) {
                    Logger.logError( "MCP-initiated launch of " + friendlyName + " failed" );
                    Logger.logThrowable( e );
                }
            } );
            // Deliberately reports that the launch *started*. Waiting for the game to finish
            // loading would mean holding the caller for minutes with no way to report progress.
            return Outcome.ok( "Started launching \"" + friendlyName + "\". The launcher window "
                                       + "shows progress; the game takes a while to appear." );
        }
        catch ( Exception e ) {
            Logger.logError( "MCP could not start a launch of " + friendlyName );
            Logger.logThrowable( e );
            return Outcome.failed( "The launch could not be started." );
        }
    }

    @Override
    public Outcome stopGame()
    {
        try {
            for ( GameModPack pack : installedPacks() ) {
                Process process = pack.getLastLaunchedProcess();
                if ( process != null && process.isAlive() ) {
                    process.destroy();
                    return Outcome.ok( "Stopped the running game (" + pack.getFriendlyName() + ")." );
                }
            }
            return Outcome.failed( "No game is currently running." );
        }
        catch ( Exception e ) {
            Logger.logError( "MCP could not stop the running game" );
            Logger.logThrowable( e );
            return Outcome.failed( "The game could not be stopped." );
        }
    }

    @Override
    public boolean isGameRunning()
    {
        try {
            for ( GameModPack pack : installedPacks() ) {
                Process process = pack.getLastLaunchedProcess();
                if ( process != null && process.isAlive() ) {
                    return true;
                }
            }
        }
        catch ( Exception e ) {
            // Reporting "running" on an error is the conservative answer: it blocks uninstall
            // and a second launch, which are the two things this guards.
            Logger.logWarningSilent( "MCP could not determine whether a game is running" );
            return true;
        }
        return false;
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
            Logger.logError( "MCP could not create modpack " + name );
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
            Logger.logError( "MCP could not fork " + sourceFriendlyName );
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
            Logger.logError( "MCP could not edit the manifest for " + friendlyName );
            Logger.logThrowable( e );
            return Outcome.failed( "The manifest could not be updated." );
        }
    }

    /**
     * Writes an authored manifest and installs it.
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
        String url = document.writeLocalManifest( directory );
        GameModPackManager.installModPackByURL( url );
        refreshPackList();
        return Outcome.ok( verb + " \"" + document.getString( ModPackDocument.KEY_PACK_NAME )
                                   + "\" and installed it." );
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
        String manifestUrl = pack.getManifestUrl();
        if ( manifestUrl == null || !manifestUrl.startsWith( "file:" ) ) {
            return null;
        }
        try {
            Path path = Path.of( URI.create( manifestUrl ) ).toAbsolutePath().normalize();
            Path authored = Path.of( LocalPathManager.getLauncherConfigFolderPath(),
                                     AUTHORED_MANIFESTS_DIR ).toAbsolutePath().normalize();
            if ( !path.startsWith( authored ) || !Files.isRegularFile( path ) ) {
                return null;
            }
            return path;
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
            Logger.logWarningSilent( "MCP could not refresh the modpack list after an edit" );
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
