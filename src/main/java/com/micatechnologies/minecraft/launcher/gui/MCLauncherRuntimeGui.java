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

import com.micatechnologies.minecraft.launcher.LauncherCore;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.RuntimeManager;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import io.github.palexdev.materialfx.controls.MFXButton;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Controller for the runtime management GUI screen. Allows users to view, refresh, and delete installed Java runtimes.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2025.1
 */
public class MCLauncherRuntimeGui extends MCLauncherAbstractGui
{
    @SuppressWarnings( "unused" )
    @FXML
    Label announcement;

    @SuppressWarnings( "unused" )
    @FXML
    javafx.scene.layout.RowConstraints announcementRow;

    @SuppressWarnings( "unused" )
    @FXML
    Label helpBtn;

    @SuppressWarnings( "unused" )
    @FXML
    Label statusLabel;

    @SuppressWarnings( "unused" )
    @FXML
    Label infoLabel;

    @SuppressWarnings( "unused" )
    @FXML
    ListView< String > runtimeListView;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton refreshBtn;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton deleteBtn;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton deleteAllBtn;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton returnBtn;

    /**
     * The currently loaded list of runtime info maps.
     */
    private List< Map< String, String > > currentRuntimes;

    public MCLauncherRuntimeGui( Stage stage ) throws IOException {
        super( stage );
    }

    @Override
    String getSceneFxmlPath() {
        return "gui/runtimeManagementGUI.fxml";
    }

    @Override
    String getSceneName() {
        return "Runtime Management";
    }

    @Override
    void setup() {
        // Configure window close -- X button closes the app
        stage.setOnCloseRequest( windowEvent -> {
            windowEvent.consume();
            LauncherCore.closeApp();
        } );

        // Return button
        returnBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToSettingsGui();
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to return to settings GUI." );
                Logger.logThrowable( e );
            }
        } ) );

        // Wire the navbar help button.
        if ( helpBtn != null ) {
            helpBtn.setOnMouseClicked( e -> MCLauncherHelpWindow.show( getHelpTopic() ) );
            helpBtn.setCursor( javafx.scene.Cursor.HAND );
            TooltipManager.install( helpBtn, "Open the help window for this screen." );
        }

        // Refresh button
        refreshBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( this::refreshRuntimeList ) );

        // Delete selected runtime
        deleteBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            int selectedIndex = runtimeListView.getSelectionModel().getSelectedIndex();
            if ( selectedIndex < 0 || currentRuntimes == null || selectedIndex >= currentRuntimes.size() ) {
                GUIUtilities.JFXPlatformRun(
                        () -> statusLabel.setText( "No runtime selected." ) );
                return;
            }

            Map< String, String > selected = currentRuntimes.get( selectedIndex );
            String component = selected.get( "component" );

            int response = GUIUtilities.showQuestionMessage( "Confirm Delete",
                                                              "Delete " + component + " Runtime",
                                                              "This runtime will be re-downloaded when a modpack that needs it is launched. Continue?",
                                                              "Delete", "Cancel", stage );
            if ( response != 1 ) {
                return;
            }

            try {
                RuntimeManager.clearRuntime( component );
                GUIUtilities.JFXPlatformRun(
                        () -> statusLabel.setText( component + " runtime deleted." ) );
            }
            catch ( IOException e ) {
                Logger.logError( "Failed to delete " + component + " runtime." );
                Logger.logThrowable( e );
                GUIUtilities.JFXPlatformRun(
                        () -> statusLabel.setText( "Failed to delete runtime." ) );
            }

            refreshRuntimeList();
        } ) );

        // Delete all runtimes
        deleteAllBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            if ( currentRuntimes == null || currentRuntimes.isEmpty() ) {
                GUIUtilities.JFXPlatformRun(
                        () -> statusLabel.setText( "No runtimes installed." ) );
                return;
            }

            int response = GUIUtilities.showQuestionMessage( "Confirm Delete All",
                                                              "Delete All Runtimes",
                                                              "All runtimes will be re-downloaded when modpacks are launched. Continue?",
                                                              "Delete All", "Cancel", stage );
            if ( response != 1 ) {
                return;
            }

            for ( Map< String, String > rt : currentRuntimes ) {
                try {
                    RuntimeManager.clearRuntime( rt.get( "component" ) );
                }
                catch ( IOException e ) {
                    Logger.logError( "Failed to delete " + rt.get( "component" ) + " runtime." );
                }
            }

            GUIUtilities.JFXPlatformRun(
                    () -> statusLabel.setText( "All runtimes deleted." ) );
            refreshRuntimeList();
        } ) );

        // Initial load
        refreshRuntimeList();
    }

    @Override
    void afterShow() {
        // Nothing needed after show
    }

    @Override
    void cleanup() {
        // Nothing to clean up
    }

    @Override
    HelpTopic getHelpTopic() { return HelpTopic.RUNTIME_MANAGEMENT; }

    /**
     * Refreshes the runtime list view with current installed runtimes.
     */
    private void refreshRuntimeList() {
        currentRuntimes = RuntimeManager.getInstalledRuntimes();

        ObservableList< String > items = FXCollections.observableArrayList();
        if ( currentRuntimes.isEmpty() ) {
            items.add( "(No runtimes installed)" );
        }
        else {
            for ( Map< String, String > rt : currentRuntimes ) {
                String display = rt.get( "component" );
                String version = rt.get( "version" );
                if ( version != null && !version.equals( "Not verified" ) ) {
                    display += " (" + version + ")";
                }
                display += "  -  " + rt.get( "sizeMB" ) + " MB";
                items.add( display );
            }
        }

        GUIUtilities.JFXPlatformRun( () -> {
            runtimeListView.setItems( items );
            if ( !currentRuntimes.isEmpty() ) {
                statusLabel.setText( currentRuntimes.size() + " runtime(s) installed." );
            }
            else {
                statusLabel.setText( "No runtimes installed." );
            }
        } );
    }
}
