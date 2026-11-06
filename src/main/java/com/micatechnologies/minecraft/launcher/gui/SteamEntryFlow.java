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

package com.micatechnologies.minecraft.launcher.gui;

import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.steam.SteamArtwork;
import com.micatechnologies.minecraft.launcher.steam.SteamLibrary;
import com.micatechnologies.minecraft.launcher.steam.SteamProcess;
import com.micatechnologies.minecraft.launcher.steam.SteamShortcuts;
import com.micatechnologies.minecraft.launcher.utilities.NotificationManager;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import javafx.scene.control.ChoiceDialog;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.apache.commons.lang3.SystemUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * "Add to Steam" for the launcher and for a modpack (plan phase 7): picks the Steam account,
 * closes Steam when it is running (Steam overwrites its shortcut list on exit; decision D13),
 * writes the entry and its artwork, and starts Steam again if it was running.
 *
 * <p>Runs on a background thread; dialogs go to the JavaFX thread and are waited for.
 *
 * @since 2026.11
 */
public final class SteamEntryFlow
{
    /** How long Steam gets to close itself. */
    private static final long STEAM_SHUTDOWN_TIMEOUT_MS = 30_000L;

    private SteamEntryFlow() { /* static-only */ }

    /** One change to a Steam library, made while Steam is closed. */
    @FunctionalInterface
    private interface Change
    {
        /**
         * @param target  the account
         * @param program the launcher program entries run
         *
         * @throws IOException when the shortcut list can't be read or written
         */
        void apply( SteamLibrary.Target target, Path program ) throws IOException;
    }

    /** The messages a change uses, by localization key. */
    private record Texts( String closeBody, String closeButton, String doneTitle, String doneBody, String failed )
    {
    }

    private static final Texts ADD = new Texts( "steam.add.closeSteam.body", "steam.add.closeSteam.button",
                                                "steam.add.done.title", "steam.add.done.body",
                                                "steam.add.error.failed" );
    private static final Texts REMOVE = new Texts( "steam.remove.closeSteam.body", "steam.remove.closeSteam.button",
                                                   "steam.remove.done.title", "steam.remove.done.body",
                                                   "steam.remove.error.failed" );

    /**
     * Adds a modpack to the Steam library; starting it there goes straight into the pack.
     *
     * @param pack  the modpack
     * @param owner the window dialogs belong to, or {@code null}
     *
     * @since 2026.11
     */
    public static void addPack( GameModPack pack, Stage owner )
    {
        String name = pack.getPackName();
        SystemUtilities.spawnNewTask( () -> run( name, owner, ADD, SteamLibrary.targets(), ( target, program ) ->
                SteamLibrary.add( target, program, List.of( new SteamLibrary.Request(
                        name, SteamShortcuts.packOptions( name, SystemUtils.IS_OS_WINDOWS ),
                        SteamArtwork.read( pack.getPackLogoFilepath() ),
                        SteamArtwork.read( pack.getPackBackgroundFilepath() ) ) ) ) ) );
    }

    /**
     * Adds the launcher itself to the Steam library.
     *
     * @param owner the window dialogs belong to, or {@code null}
     *
     * @since 2026.11
     */
    public static void addLauncher( Stage owner )
    {
        SystemUtilities.spawnNewTask( () -> run( LauncherConstants.LAUNCHER_APPLICATION_NAME, owner, ADD,
                                                 SteamLibrary.targets(), ( target, program ) -> SteamLibrary.add(
                        target, program, List.of( SteamLibrary.launcherRequest() ) ) ) );
    }

    /**
     * Removes a modpack's entry (and its artwork) from the Steam library (plan decision D25).
     *
     * @param pack  the modpack
     * @param owner the window dialogs belong to, or {@code null}
     *
     * @since 2026.11
     */
    public static void removePack( GameModPack pack, Stage owner )
    {
        SystemUtilities.spawnNewTask( () -> {
            String name = pack.getPackName();
            List< SteamLibrary.Target > holding = targetsWith( name );
            if ( holding.isEmpty() ) {
                NotificationManager.info( LocalizationManager.get( "steam.remove.done.title" ),
                                          LocalizationManager.format( "steam.remove.notFound", name ) );
                return;
            }
            run( name, owner, REMOVE, holding, ( target, program ) -> SteamLibrary.remove(
                    target, SteamShortcuts.packOptions( name, SystemUtils.IS_OS_WINDOWS ) ) );
        } );
    }

    /**
     * After a modpack is uninstalled: if a Steam library still has its entry, offers to remove it
     * there too (plan decision D25). Does nothing when no library has it.
     *
     * @param packName the uninstalled modpack's name
     * @param owner    the window dialogs belong to, or {@code null}
     *
     * @since 2026.11
     */
    public static void offerRemovalAfterUninstall( String packName, Stage owner )
    {
        SystemUtilities.spawnNewTask( () -> {
            List< SteamLibrary.Target > holding = targetsWith( packName );
            if ( holding.isEmpty() ) {
                return;
            }
            int answer = GUIUtilities.showQuestionMessage(
                    LocalizationManager.get( "steam.remove.title" ),
                    LocalizationManager.format( "steam.remove.afterUninstall.header", packName ),
                    LocalizationManager.get( "steam.remove.afterUninstall.body" ),
                    LocalizationManager.get( "steam.remove.afterUninstall.remove" ),
                    LocalizationManager.get( "steam.remove.afterUninstall.keep" ), owner );
            if ( answer == 1 ) {
                run( packName, owner, REMOVE, holding, ( target, program ) -> SteamLibrary.remove(
                        target, SteamShortcuts.packOptions( packName, SystemUtils.IS_OS_WINDOWS ) ) );
            }
        } );
    }

    /** The libraries that hold this pack's entry. */
    private static List< SteamLibrary.Target > targetsWith( String packName )
    {
        String options = SteamShortcuts.packOptions( packName, SystemUtils.IS_OS_WINDOWS );
        return SteamLibrary.targets().stream().filter( t -> SteamLibrary.has( t, options ) ).toList();
    }

    /**
     * Picks the account, closes Steam if it runs (D13), applies the change, reports, and starts
     * Steam again. Runs on a background thread.
     */
    private static void run( String name, Stage owner, Texts texts, List< SteamLibrary.Target > targets,
                             Change change )
    {
        Path program = SteamLibrary.launcherProgram();
        if ( program == null ) {
            GUIUtilities.showErrorMessage( LocalizationManager.get( "steam.add.error.notInstalled" ), owner );
            return;
        }
        if ( targets.isEmpty() ) {
            GUIUtilities.showErrorMessage( LocalizationManager.get( "steam.add.error.noSteam" ), owner );
            return;
        }
        Optional< SteamLibrary.Target > target = targets.size() == 1 ? Optional.of( targets.get( 0 ) )
                                                                       : chooseAccount( targets, owner );
        if ( target.isEmpty() ) {
            return;
        }

        boolean closedSteam = false;
        try {
            if ( SteamProcess.isRunning() ) {
                if ( SteamProcess.inGameMode( System.getenv() ) ) {
                    GUIUtilities.showErrorMessage( LocalizationManager.get( "steam.add.error.gameMode" ), owner );
                    return;
                }
                int answer = GUIUtilities.showQuestionMessage(
                        LocalizationManager.get( "steam.add.title" ),
                        LocalizationManager.get( "steam.add.closeSteam.header" ),
                        LocalizationManager.format( texts.closeBody(), name ),
                        LocalizationManager.get( texts.closeButton() ),
                        LocalizationManager.get( "dialog.button.cancel" ), owner );
                if ( answer != 1 ) {
                    return;
                }
                if ( !SteamProcess.shutdown( target.get().install(), STEAM_SHUTDOWN_TIMEOUT_MS ) ) {
                    GUIUtilities.showErrorMessage( LocalizationManager.get( "steam.add.error.closeFailed" ), owner );
                    return;
                }
                closedSteam = true;
            }

            change.apply( target.get(), program );
            NotificationManager.success( LocalizationManager.get( texts.doneTitle() ),
                                         LocalizationManager.format( texts.doneBody(), name ) );
        }
        catch ( InterruptedException e ) {
            Thread.currentThread().interrupt();
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.steam.addFailed", String.valueOf( e ) ) );
            GUIUtilities.showErrorMessage(
                    LocalizationManager.format( texts.failed(), String.valueOf( e.getMessage() ) ), owner );
        }
        finally {
            if ( closedSteam ) {
                try {
                    SteamProcess.start( target.get().install() );
                }
                catch ( Exception e ) {
                    Logger.logWarningSilent( LocalizationManager.format( "log.steam.restartFailed",
                                                                         String.valueOf( e ) ) );
                }
            }
        }
    }

    /** Plan decision D18: several accounts → ask which, the most recent preselected. */
    private static Optional< SteamLibrary.Target > chooseAccount( List< SteamLibrary.Target > targets, Stage owner )
    {
        List< String > labels = new ArrayList<>();
        for ( SteamLibrary.Target t : targets ) {
            String persona = t.account().personaName();
            labels.add( persona.isBlank()
                        ? LocalizationManager.format( "steam.add.account.unnamed", t.account().accountId() )
                        : persona );
        }
        CompletableFuture< Optional< String > > choice = new CompletableFuture<>();
        GUIUtilities.JFXPlatformRun( () -> {
            ChoiceDialog< String > dialog = new ChoiceDialog<>( labels.get( 0 ), labels );
            dialog.setTitle( LocalizationManager.get( "steam.add.title" ) );
            dialog.setHeaderText( LocalizationManager.get( "steam.add.account.header" ) );
            dialog.setContentText( LocalizationManager.get( "steam.add.account.label" ) );
            dialog.initStyle( StageStyle.UTILITY );
            if ( owner != null ) {
                dialog.initOwner( owner );
            }
            GUIUtilities.themeAlertChrome( dialog );
            choice.complete( dialog.showAndWait() );
        } );
        Optional< String > picked = choice.join();
        return picked.map( label -> targets.get( labels.indexOf( label ) ) );
    }
}
