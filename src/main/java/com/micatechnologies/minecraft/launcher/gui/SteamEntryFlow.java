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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

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
        run( name, owner, () -> List.of( new SteamLibrary.Request(
                name, SteamShortcuts.packOptions( name, SystemUtils.IS_OS_WINDOWS ),
                SteamArtwork.read( pack.getPackLogoFilepath() ),
                SteamArtwork.read( pack.getPackBackgroundFilepath() ) ) ) );
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
        run( LauncherConstants.LAUNCHER_APPLICATION_NAME, owner, () -> List.of( SteamLibrary.launcherRequest() ) );
    }

    private static void run( String name, Stage owner, Supplier< List< SteamLibrary.Request > > requests )
    {
        SystemUtilities.spawnNewTask( () -> {
            Path program = SteamLibrary.launcherProgram();
            if ( program == null ) {
                GUIUtilities.showErrorMessage( LocalizationManager.get( "steam.add.error.notInstalled" ), owner );
                return;
            }
            List< SteamLibrary.Target > targets = SteamLibrary.targets();
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
                            LocalizationManager.format( "steam.add.closeSteam.body", name ),
                            LocalizationManager.get( "steam.add.closeSteam.button" ),
                            LocalizationManager.get( "dialog.button.cancel" ), owner );
                    if ( answer != 1 ) {
                        return;
                    }
                    if ( !SteamProcess.shutdown( target.get().install(), STEAM_SHUTDOWN_TIMEOUT_MS ) ) {
                        GUIUtilities.showErrorMessage( LocalizationManager.get( "steam.add.error.closeFailed" ),
                                                       owner );
                        return;
                    }
                    closedSteam = true;
                }

                SteamLibrary.add( target.get(), program, requests.get() );
                NotificationManager.success( LocalizationManager.get( "steam.add.done.title" ),
                                             LocalizationManager.format( "steam.add.done.body", name ) );
            }
            catch ( InterruptedException e ) {
                Thread.currentThread().interrupt();
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.steam.addFailed", String.valueOf( e ) ) );
                GUIUtilities.showErrorMessage(
                        LocalizationManager.format( "steam.add.error.failed", String.valueOf( e.getMessage() ) ),
                        owner );
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
        } );
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
