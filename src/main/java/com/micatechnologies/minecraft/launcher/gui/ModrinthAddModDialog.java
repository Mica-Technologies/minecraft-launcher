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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.import_.ModrinthClient;
import com.micatechnologies.minecraft.launcher.utilities.FxAsyncTask;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NotificationManager;
import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXTextField;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.net.URL;
import java.util.List;

/**
 * "Add a mod from Modrinth" dialog for the modpack detail modal. Searches Modrinth for <b>mods</b>
 * compatible with the pack's Minecraft version + loader, and one-click adds a chosen mod's latest
 * compatible jar into the pack's {@code mods/} folder. Reuses {@link ModrinthClient} for search and
 * version resolution; the download is a plain bounded fetch into the mods directory.
 *
 * <p>Self-contained modal {@link Stage}; inherits the owner window's theme stylesheets so it reads
 * as part of the launcher. All network work runs off the FX thread via {@link FxAsyncTask}.</p>
 *
 * @since 2026.6
 */
public final class ModrinthAddModDialog
{
    private ModrinthAddModDialog() { /* static-only */ }

    /**
     * Opens the dialog for the given pack.
     *
     * @param pack    the (modded) pack to add a mod to — vanilla packs have no loader and are rejected
     * @param modsDir the pack's {@code mods/} directory (download target)
     * @param owner   the owner stage (for modality + theme inheritance)
     */
    public static void show( GameModPack pack, File modsDir, Stage owner )
    {
        if ( pack == null || modsDir == null ) {
            return;
        }
        // Resolve the pack's loader up-front; vanilla packs can't take loose mods.
        final String loader = pack.getModLoaderType();
        if ( loader == null ) {
            NotificationManager.warn(
                    LocalizationManager.get( "addMod.vanilla.title" ),
                    LocalizationManager.get( "addMod.vanilla.body" ) );
            return;
        }

        Stage stage = new Stage();
        stage.initModality( Modality.NONE );
        if ( owner != null ) {
            stage.initOwner( owner );
        }
        stage.setTitle( LocalizationManager.get( "addMod.title" ) );

        // --- Search bar ---
        MFXTextField queryField = new MFXTextField();
        queryField.setFloatingText( LocalizationManager.get( "addMod.search.prompt" ) );
        queryField.setPrefWidth( 320 );
        HBox.setHgrow( queryField, Priority.ALWAYS );
        MFXButton searchBtn = new MFXButton( LocalizationManager.get( "addMod.search.button" ) );
        searchBtn.getStyleClass().add( "heroCardPrimaryBtn" );
        searchBtn.setPrefHeight( 32 );
        HBox searchBar = new HBox( 8, queryField, searchBtn );
        searchBar.setAlignment( Pos.CENTER_LEFT );

        Label status = new Label();
        status.getStyleClass().add( "muted" );

        VBox results = new VBox( 6 );
        ScrollPane scroll = new ScrollPane( results );
        scroll.setFitToWidth( true );
        scroll.setPrefViewportHeight( 360 );
        VBox.setVgrow( scroll, Priority.ALWAYS );

        // The MC version resolution can hit disk / parse the installer, so do it off-thread once and
        // remember it for the search facets. Best-effort: null means "search any MC version".
        final String[] mcVersion = { null };
        FxAsyncTask.run( () -> {
            try {
                mcVersion[ 0 ] = pack.getMinecraftVersion();
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( "AddMod: could not resolve MC version: " + t.getMessage() );
            }
        } );

        Runnable doSearch = () -> {
            String q = queryField.getText() == null ? "" : queryField.getText().trim();
            if ( q.isEmpty() ) {
                return;
            }
            searchBtn.setDisable( true );
            results.getChildren().clear();
            status.setText( LocalizationManager.get( "addMod.searching" ) );
            FxAsyncTask.run(
                    () -> {
                        List< ModrinthClient.SearchHit > hits =
                                ModrinthClient.search( q, mcVersion[ 0 ], loader, 25 );
                        Platform.runLater( () -> {
                            searchBtn.setDisable( false );
                            if ( hits.isEmpty() ) {
                                status.setText( LocalizationManager.get( "addMod.noResults" ) );
                                return;
                            }
                            status.setText( LocalizationManager.format( "addMod.resultCount", hits.size() ) );
                            for ( ModrinthClient.SearchHit hit : hits ) {
                                results.getChildren().add( buildResultRow( hit, pack, modsDir, loader, mcVersion ) );
                            }
                        } );
                    } );
        };
        searchBtn.setOnAction( e -> doSearch.run() );
        queryField.setOnAction( e -> doSearch.run() );

        Label compat = new Label( LocalizationManager.format( "addMod.compatNote", loader ) );
        compat.getStyleClass().add( "muted" );
        compat.setWrapText( true );

        VBox rootBox = new VBox( 10, searchBar, compat, status, scroll );
        rootBox.getStyleClass().add( "rootPane" );
        rootBox.setPadding( new Insets( 16 ) );

        Scene scene = new Scene( rootBox, 560, 520 );
        // Inherit the launcher's active theme so the dialog matches the rest of the app.
        if ( owner != null && owner.getScene() != null && owner.getScene().getRoot() != null ) {
            scene.getStylesheets().addAll( owner.getScene().getStylesheets() );
            rootBox.getStylesheets().addAll( owner.getScene().getRoot().getStylesheets() );
        }
        stage.setScene( scene );
        stage.show();
        queryField.requestFocus();
    }

    /** One search-result row: title + meta + description, with a one-click Add button. */
    private static HBox buildResultRow( ModrinthClient.SearchHit hit, GameModPack pack, File modsDir,
                                        String loader, String[] mcVersion )
    {
        VBox text = new VBox( 2 );
        HBox.setHgrow( text, Priority.ALWAYS );

        Label title = new Label( hit.title() == null ? hit.slug() : hit.title() );
        title.getStyleClass().add( "modpackDetailContentName" );

        String metaStr = LocalizationManager.format( "addMod.result.meta",
                                                     hit.author() == null ? "?" : hit.author(),
                                                     formatDownloads( hit.downloads() ) );
        Label meta = new Label( metaStr );
        meta.getStyleClass().add( "muted" );

        Label desc = new Label( hit.description() == null ? "" : hit.description() );
        desc.getStyleClass().add( "muted" );
        desc.setWrapText( true );
        desc.setMaxWidth( 380 );

        text.getChildren().addAll( title, meta, desc );

        MFXButton addBtn = new MFXButton( LocalizationManager.get( "addMod.add" ) );
        addBtn.getStyleClass().add( "heroCardSecondaryBtn" );
        addBtn.setPrefHeight( 28 );
        addBtn.setOnAction( e -> {
            addBtn.setDisable( true );
            addBtn.setText( LocalizationManager.get( "addMod.adding" ) );
            FxAsyncTask.run( () -> addMod( hit, modsDir, loader, mcVersion[ 0 ], addBtn ) );
        } );

        Region spacer = new Region();
        HBox.setHgrow( spacer, Priority.ALWAYS );

        HBox row = new HBox( 10, text, spacer, addBtn );
        row.setAlignment( Pos.CENTER_LEFT );
        row.getStyleClass().add( "modpackDetailContentRow" );
        row.setPadding( new Insets( 6, 4, 6, 4 ) );
        return row;
    }

    /** Resolves the latest compatible file for a hit and downloads it into the mods folder. */
    private static void addMod( ModrinthClient.SearchHit hit, File modsDir, String loader,
                                String mcVersion, MFXButton addBtn )
    {
        try {
            ModrinthClient.FileRef file =
                    ModrinthClient.resolveLatestCompatibleFile( hit.projectId(), mcVersion, loader );
            if ( file == null || file.url() == null ) {
                Platform.runLater( () -> {
                    addBtn.setDisable( false );
                    addBtn.setText( LocalizationManager.get( "addMod.add" ) );
                } );
                NotificationManager.warn(
                        LocalizationManager.get( "addMod.noCompatible.title" ),
                        LocalizationManager.format( "addMod.noCompatible.body",
                                                    hit.title() == null ? hit.slug() : hit.title() ) );
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            modsDir.mkdirs();
            String filename = ( file.filename() != null && !file.filename().isBlank() )
                    ? file.filename()
                    : file.url().substring( file.url().lastIndexOf( '/' ) + 1 );
            File dest = new File( modsDir, filename );
            NetworkUtilities.downloadFileFromURL( new URL( file.url() ), dest );

            Platform.runLater( () -> addBtn.setText( LocalizationManager.get( "addMod.added" ) ) );
            NotificationManager.success(
                    LocalizationManager.get( "addMod.success.title" ),
                    LocalizationManager.format( "addMod.success.body",
                                                hit.title() == null ? hit.slug() : hit.title() ) );
        }
        catch ( Exception ex ) {
            Logger.logWarningSilent( "AddMod: download failed for " + hit.slug() + ": " + ex.getMessage() );
            Platform.runLater( () -> {
                addBtn.setDisable( false );
                addBtn.setText( LocalizationManager.get( "addMod.add" ) );
            } );
            NotificationManager.error(
                    LocalizationManager.get( "addMod.fail.title" ),
                    LocalizationManager.format( "addMod.fail.body",
                                                hit.title() == null ? hit.slug() : hit.title() ) );
        }
    }

    /** Compact human-readable download count (e.g. {@code 1.2M}, {@code 45.0K}). */
    private static String formatDownloads( long downloads )
    {
        if ( downloads >= 1_000_000 ) {
            return String.format( "%.1fM", downloads / 1_000_000.0 );
        }
        if ( downloads >= 1_000 ) {
            return String.format( "%.1fK", downloads / 1_000.0 );
        }
        return Long.toString( downloads );
    }
}
