/*
 * Copyright (c) 2026 Mica Technologies
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 */

package com.micatechnologies.minecraft.launcher.gui;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.VanillaVersionManager;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import io.github.palexdev.materialfx.controls.MFXButton;
import com.micatechnologies.minecraft.launcher.LauncherCore;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import io.github.palexdev.materialfx.controls.MFXComboBox;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.List;

/**
 * Controller for the vanilla Minecraft version browser and installer GUI.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public class MCLauncherVanillaVersionsGui extends MCLauncherAbstractGui
{
    @SuppressWarnings( "unused" )
    @FXML
    Label titleLabel;

    @SuppressWarnings( "unused" )
    @FXML
    Label countLabel;

    @SuppressWarnings( "unused" )
    @FXML
    Label statusLabel;

    @SuppressWarnings( "unused" )
    @FXML
    ListView< String > versionListView;

    @SuppressWarnings( "unused" )
    @FXML
    MFXComboBox< String > typeFilter;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton installBtn;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton uninstallBtn;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton returnBtn;

    private List< JsonObject > currentVersionList;
    private String currentFilter = "release";

    public MCLauncherVanillaVersionsGui( Stage stage ) throws IOException {
        super( stage );
    }

    @Override
    String getSceneFxmlPath() {
        return "gui/vanillaVersionsGUI.fxml";
    }

    @Override
    String getSceneName() {
        return "Vanilla Versions";
    }

    @Override
    void setup() {
        stage.setOnCloseRequest( windowEvent -> {
            windowEvent.consume();
            LauncherCore.closeApp();
        } );

        // Filter dropdown
        typeFilter.getItems().addAll( "Releases", "Snapshots", "Old Beta", "Old Alpha", "All" );

        typeFilter.setOnAction( event -> {
            String selected = typeFilter.getValue();
            if ( selected != null ) {
                currentFilter = switch ( selected ) {
                    case "Releases" -> "release";
                    case "Snapshots" -> "snapshot";
                    case "Old Beta" -> "old_beta";
                    case "Old Alpha" -> "old_alpha";
                    default -> "all";
                };
                refreshVersionList();
            }
        } );

        // Install button
        installBtn.setOnAction( event -> SystemUtilities.spawnNewTask( () -> {
            int selectedIndex = versionListView.getSelectionModel().getSelectedIndex();
            if ( selectedIndex < 0 || currentVersionList == null || selectedIndex >= currentVersionList.size() ) {
                GUIUtilities.JFXPlatformRun( () -> statusLabel.setText( "No version selected." ) );
                return;
            }

            String versionId = currentVersionList.get( selectedIndex ).get( "id" ).getAsString();
            if ( VanillaVersionManager.isInstalled( versionId ) ) {
                GUIUtilities.JFXPlatformRun( () -> statusLabel.setText( versionId + " is already installed." ) );
                return;
            }

            VanillaVersionManager.installVersion( versionId );
            GUIUtilities.JFXPlatformRun(
                    () -> statusLabel.setText( "Installed Minecraft " + versionId ) );
            refreshVersionList();
        } ) );

        // Uninstall button
        uninstallBtn.setOnAction( event -> SystemUtilities.spawnNewTask( () -> {
            int selectedIndex = versionListView.getSelectionModel().getSelectedIndex();
            if ( selectedIndex < 0 || currentVersionList == null || selectedIndex >= currentVersionList.size() ) {
                GUIUtilities.JFXPlatformRun( () -> statusLabel.setText( "No version selected." ) );
                return;
            }

            String versionId = currentVersionList.get( selectedIndex ).get( "id" ).getAsString();
            if ( !VanillaVersionManager.isInstalled( versionId ) ) {
                GUIUtilities.JFXPlatformRun( () -> statusLabel.setText( versionId + " is not installed." ) );
                return;
            }

            int response = GUIUtilities.showQuestionMessage( "Uninstall Version",
                                                              "Uninstall Minecraft " + versionId + "?",
                                                              "Would you also like to delete the installed game files?",
                                                              "Uninstall & Delete Files",
                                                              "Uninstall (Keep Files)", stage );
            if ( response == 1 ) {
                // Uninstall and delete files
                GameModPack vanillaPack = GameModPack.createVanillaModPack( versionId );
                try {
                    java.io.File installDir = new java.io.File( vanillaPack.getPackRootFolder() );
                    if ( installDir.exists() ) {
                        org.codehaus.plexus.util.FileUtils.deleteDirectory( installDir );
                    }
                }
                catch ( Exception e ) {
                    Logger.logWarningSilent( "Could not fully delete install folder: " + e.getMessage() );
                }
                VanillaVersionManager.uninstallVersion( versionId );
                GUIUtilities.JFXPlatformRun(
                        () -> statusLabel.setText( "Uninstalled Minecraft " + versionId + " (files deleted)" ) );
                refreshVersionList();
            }
            else if ( response == 2 ) {
                // Uninstall but keep files
                VanillaVersionManager.uninstallVersion( versionId );
                GUIUtilities.JFXPlatformRun(
                        () -> statusLabel.setText( "Uninstalled Minecraft " + versionId + " (files kept)" ) );
                refreshVersionList();
            }
            // Cancel (0) -- do nothing
        } ) );

        // Return button
        returnBtn.setOnAction( event -> SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToMainGui();
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to return to main GUI." );
                Logger.logThrowable( e );
            }
        } ) );
    }

    @Override
    void afterShow() {
        // Select "Releases" by default
        typeFilter.selectItem( "Releases" );
        typeFilter.getSelectionModel().selectItem( "Releases" );
        currentFilter = "release";

        // Load versions in background
        SystemUtilities.spawnNewTask( this::refreshVersionList );
    }

    @Override
    void cleanup() {
    }

    @Override
    HelpTopic getHelpTopic() { return HelpTopic.VANILLA_VERSIONS; }

    private void refreshVersionList() {
        currentVersionList = VanillaVersionManager.getVersionsByType( currentFilter );
        List< String > installedIds = VanillaVersionManager.getInstalledVersionIds();

        ObservableList< String > items = FXCollections.observableArrayList();
        for ( JsonObject version : currentVersionList ) {
            String id = version.get( "id" ).getAsString();
            String type = version.get( "type" ).getAsString();
            String releaseTime = version.has( "releaseTime" ) ?
                                 version.get( "releaseTime" ).getAsString().substring( 0, 10 ) : "";

            String display = id;
            if ( !"release".equals( type ) ) {
                display += "  [" + type + "]";
            }
            display += "  (" + releaseTime + ")";
            if ( installedIds.contains( id ) ) {
                display = "[INSTALLED]  " + display;
            }
            items.add( display );
        }

        GUIUtilities.JFXPlatformRun( () -> {
            versionListView.setItems( items );
            countLabel.setText( currentVersionList.size() + " versions" );
        } );
    }
}
