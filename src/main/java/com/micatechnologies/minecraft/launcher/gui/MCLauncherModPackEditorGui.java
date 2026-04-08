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

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.LauncherCore;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.GUIUtilities;
import com.micatechnologies.minecraft.launcher.utilities.HashUtilities;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXTextField;
import io.github.palexdev.materialfx.controls.MFXToggleButton;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.RowConstraints;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GUI controller for the modpack JSON editor. Allows creating, editing, and exporting modpack definition files.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public class MCLauncherModPackEditorGui extends MCLauncherAbstractGui
{
    // region FXML fields

    @FXML MFXButton newBtn;
    @FXML MFXButton openFileBtn;
    @FXML MFXButton openUrlBtn;
    @FXML MFXButton saveBtn;
    @FXML MFXButton validateBtn;
    @FXML MFXButton returnBtn;
    @FXML TabPane editorTabPane;
    @FXML Label statusLabel;
    @FXML Label announcement;
    @FXML RowConstraints announcementRow;

    // Metadata fields
    @FXML MFXTextField packNameField;
    @FXML MFXTextField packVersionField;
    @FXML MFXTextField packURLField;
    @FXML MFXTextField packMinRAMField;
    @FXML MFXToggleButton packUnstableToggle;
    @FXML MFXToggleButton packCustomDiscordRpcToggle;
    @FXML MFXTextField packForgeURLField;
    @FXML MFXTextField packForgeHashField;
    @FXML MFXTextField packLogoURLField;
    @FXML MFXTextField packLogoSha1Field;
    @FXML MFXTextField packBgURLField;
    @FXML MFXTextField packBgSha1Field;
    @FXML ImageView logoPreview;
    @FXML ImageView bgPreview;
    @FXML TextArea scanExclusionsArea;

    // endregion

    /**
     * The working JSON document being edited.
     */
    private JsonObject workingDocument = null;

    /**
     * Maps JSON array key to the ObservableList backing each file list tab's TableView.
     */
    private final Map< String, ObservableList< ModPackEditorFileEntry > > fileListData = new HashMap<>();

    /**
     * Snapshot of the document at last save/load, for dirty checking.
     */
    private String savedSnapshot = "";

    /**
     * Path to the currently open file, or null if unsaved/new.
     */
    private File currentFile = null;

    public MCLauncherModPackEditorGui( Stage stage ) throws IOException
    {
        super( stage );
    }

    @Override
    String getSceneFxmlPath()
    {
        return "gui/modpackEditorGUI.fxml";
    }

    @Override
    String getSceneName()
    {
        return "Modpack Editor";
    }

    @Override
    void setup()
    {
        // Configure window close
        stage.setOnCloseRequest( windowEvent -> {
            windowEvent.consume();
            SystemUtilities.spawnNewTask( () -> {
                if ( isDirty() ) {
                    int response = GUIUtilities.showQuestionMessage( "Unsaved Changes",
                                                                     "Discard Changes?",
                                                                     "You have unsaved changes. Discard them?",
                                                                     "Discard", "Cancel", stage );
                    if ( response != 1 ) {
                        return;
                    }
                }
                LauncherCore.closeApp();
            } );
        } );

        // Toolbar buttons
        newBtn.setOnAction( e -> newDocument() );
        openFileBtn.setOnAction( e -> openFromFile() );
        openUrlBtn.setOnAction( e -> openFromUrl() );
        saveBtn.setOnAction( e -> saveToFile() );
        validateBtn.setOnAction( e -> validateDocument() );
        returnBtn.setOnAction( e -> SystemUtilities.spawnNewTask( () -> {
            if ( isDirty() ) {
                int response = GUIUtilities.showQuestionMessage( "Unsaved Changes",
                                                                 "Discard Changes?",
                                                                 "You have unsaved changes. Discard them?",
                                                                 "Discard", "Cancel", stage );
                if ( response != 1 ) {
                    return;
                }
            }
            try {
                MCLauncherGuiController.goToEditModpacksGui();
            }
            catch ( IOException ex ) {
                Logger.logError( "Unable to return to edit modpacks screen." );
                Logger.logThrowable( ex );
            }
        } ) );

        // Configure image preview refresh on URL field focus loss
        packLogoURLField.focusedProperty().addListener( ( obs, wasFocused, isFocused ) -> {
            if ( !isFocused ) {
                refreshImagePreview( packLogoURLField.getText(), logoPreview );
            }
        } );
        packBgURLField.focusedProperty().addListener( ( obs, wasFocused, isFocused ) -> {
            if ( !isFocused ) {
                refreshImagePreview( packBgURLField.getText(), bgPreview );
            }
        } );

        // Create file list tabs (all 5 types)
        editorTabPane.getTabs().add( createFileListTab( "Mods", "packMods", true, true ) );
        editorTabPane.getTabs().add( createFileListTab( "Configs", "packConfigs", false, true ) );
        editorTabPane.getTabs().add( createFileListTab( "Resources", "packResourcePacks", false, false ) );
        editorTabPane.getTabs().add( createFileListTab( "Shaders", "packShaderPacks", false, false ) );
        editorTabPane.getTabs().add( createFileListTab( "Initial Files", "packInitialFiles", false, true ) );

        // Start with a new empty document
        newDocument();
    }

    @Override
    void afterShow()
    {
        // Nothing needed
    }

    @Override
    void cleanup()
    {
        // Nothing to clean up -- no persistent listeners beyond FXML-bound ones
    }

    // region Document operations

    /**
     * Creates a new empty modpack document with default values.
     */
    private void newDocument()
    {
        workingDocument = new JsonObject();
        workingDocument.addProperty( "packName", "" );
        workingDocument.addProperty( "packVersion", "1.0.0" );
        workingDocument.addProperty( "packURL", "" );
        workingDocument.addProperty( "packUnstable", false );
        workingDocument.addProperty( "packCustomDiscordRpc", false );
        workingDocument.addProperty( "packMinRAMGB", "2" );
        workingDocument.addProperty( "packLogoURL", "" );
        workingDocument.addProperty( "packLogoSha1", "" );
        workingDocument.addProperty( "packBackgroundURL", "" );
        workingDocument.addProperty( "packBackgroundSha1", "" );
        workingDocument.addProperty( "packForgeURL", "" );
        workingDocument.addProperty( "packForgeHash", "" );
        workingDocument.add( "packScanExclusions", new JsonArray() );
        workingDocument.add( "packMods", new JsonArray() );
        workingDocument.add( "packConfigs", new JsonArray() );
        workingDocument.add( "packResourcePacks", new JsonArray() );
        workingDocument.add( "packShaderPacks", new JsonArray() );
        workingDocument.add( "packInitialFiles", new JsonArray() );

        currentFile = null;
        savedSnapshot = serializeDocument();
        populateFieldsFromDocument();
        updateStatus( "New modpack created" );
    }

    /**
     * Opens a modpack JSON from a local file.
     */
    private void openFromFile()
    {
        GUIUtilities.JFXPlatformRun( () -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle( "Open Modpack JSON" );
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter( "JSON Files", "*.json" ) );
            File file = fileChooser.showOpenDialog( stage );
            if ( file != null ) {
                SystemUtilities.spawnNewTask( () -> {
                    try {
                        String json = FileUtils.readFileToString( file, StandardCharsets.UTF_8 );
                        workingDocument = JSONUtilities.getGson().fromJson( json, JsonObject.class );
                        currentFile = file;
                        savedSnapshot = serializeDocument();
                        GUIUtilities.JFXPlatformRun( this::populateFieldsFromDocument );
                        updateStatus( "Loaded: " + file.getName() );
                    }
                    catch ( Exception ex ) {
                        Logger.logError( "Failed to open modpack JSON file." );
                        Logger.logThrowable( ex );
                        updateStatus( "Error loading file: " + ex.getMessage() );
                    }
                } );
            }
        } );
    }

    /**
     * Opens a modpack JSON from a URL.
     */
    private void openFromUrl()
    {
        SystemUtilities.spawnNewTask( () -> {
            int response = GUIUtilities.showQuestionMessage( "Open from URL",
                                                              "Enter Modpack URL",
                                                              "Paste the modpack manifest JSON URL:",
                                                              "OK", "Cancel", stage );
            // The question dialog doesn't support text input, so use a simpler approach:
            // Prompt via a JavaFX TextInputDialog
            GUIUtilities.JFXPlatformRun( () -> {
                javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog();
                dialog.setTitle( "Open from URL" );
                dialog.setHeaderText( "Enter Modpack Manifest URL" );
                dialog.setContentText( "URL:" );
                dialog.showAndWait().ifPresent( url -> SystemUtilities.spawnNewTask( () -> {
                    try {
                        updateStatus( "Downloading..." );
                        String json = NetworkUtilities.downloadFileFromURL( url );
                        workingDocument = JSONUtilities.getGson().fromJson( json, JsonObject.class );
                        currentFile = null;
                        savedSnapshot = serializeDocument();
                        GUIUtilities.JFXPlatformRun( this::populateFieldsFromDocument );
                        updateStatus( "Loaded from URL" );
                    }
                    catch ( Exception ex ) {
                        Logger.logError( "Failed to download modpack JSON from URL." );
                        Logger.logThrowable( ex );
                        updateStatus( "Error loading URL: " + ex.getMessage() );
                    }
                } ) );
            } );
        } );
    }

    /**
     * Saves the current document to a JSON file.
     */
    private void saveToFile()
    {
        GUIUtilities.JFXPlatformRun( () -> {
            collectFieldsToDocument();
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle( "Save Modpack JSON" );
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter( "JSON Files", "*.json" ) );
            if ( currentFile != null ) {
                fileChooser.setInitialDirectory( currentFile.getParentFile() );
                fileChooser.setInitialFileName( currentFile.getName() );
            }
            else if ( workingDocument.has( "packName" ) &&
                    !workingDocument.get( "packName" ).getAsString().isEmpty() ) {
                fileChooser.setInitialFileName(
                        workingDocument.get( "packName" ).getAsString().replaceAll( "[^a-zA-Z0-9]", "" ) + ".json" );
            }
            File file = fileChooser.showSaveDialog( stage );
            if ( file != null ) {
                SystemUtilities.spawnNewTask( () -> {
                    try {
                        String prettyJson = serializeDocument();
                        FileUtils.writeStringToFile( file, prettyJson, StandardCharsets.UTF_8 );
                        currentFile = file;
                        savedSnapshot = prettyJson;
                        updateStatus( "Saved: " + file.getName() );
                    }
                    catch ( Exception ex ) {
                        Logger.logError( "Failed to save modpack JSON." );
                        Logger.logThrowable( ex );
                        updateStatus( "Error saving: " + ex.getMessage() );
                    }
                } );
            }
        } );
    }

    /**
     * Validates the current document.
     */
    private void validateDocument()
    {
        GUIUtilities.JFXPlatformRun( () -> collectFieldsToDocument() );
        SystemUtilities.spawnNewTask( () -> {
            StringBuilder issues = new StringBuilder();

            // Check required fields
            checkRequired( issues, "packName", "Pack Name" );
            checkRequired( issues, "packVersion", "Pack Version" );

            // Check RAM is a valid number
            if ( workingDocument.has( "packMinRAMGB" ) ) {
                try {
                    Double.parseDouble( workingDocument.get( "packMinRAMGB" ).getAsString() );
                }
                catch ( NumberFormatException e ) {
                    issues.append( "- Minimum RAM is not a valid number\n" );
                }
            }

            // Validate file list entries
            validateFileEntries( issues, "packMods", "Mods", true );
            validateFileEntries( issues, "packConfigs", "Configs", false );
            validateFileEntries( issues, "packResourcePacks", "Resource Packs", false );
            validateFileEntries( issues, "packShaderPacks", "Shader Packs", false );
            validateFileEntries( issues, "packInitialFiles", "Initial Files", false );

            // Round-trip test
            try {
                String json = serializeDocument();
                JSONUtilities.getGson().fromJson( json,
                        com.micatechnologies.minecraft.launcher.game.modpack.GameModPack.class );
            }
            catch ( Exception e ) {
                issues.append( "- Round-trip deserialization failed: " ).append( e.getMessage() ).append( "\n" );
            }

            String result = issues.length() == 0 ? "Validation passed! No issues found." :
                            "Validation issues:\n" + issues;
            GUIUtilities.JFXPlatformRun( () -> {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                        issues.length() == 0 ? javafx.scene.control.Alert.AlertType.INFORMATION :
                        javafx.scene.control.Alert.AlertType.WARNING );
                alert.setTitle( "Validation Results" );
                alert.setHeaderText( issues.length() == 0 ? "Valid" : "Issues Found" );
                alert.setContentText( result );
                alert.showAndWait();
            } );
        } );
    }

    // endregion

    // region Field mapping

    /**
     * Populates all UI fields from the working document.
     */
    private void populateFieldsFromDocument()
    {
        if ( workingDocument == null ) {
            return;
        }

        packNameField.setText( getDocString( "packName" ) );
        packVersionField.setText( getDocString( "packVersion" ) );
        packURLField.setText( getDocString( "packURL" ) );
        packMinRAMField.setText( getDocString( "packMinRAMGB" ) );
        packUnstableToggle.setSelected( getDocBool( "packUnstable" ) );
        packCustomDiscordRpcToggle.setSelected( getDocBool( "packCustomDiscordRpc" ) );
        packForgeURLField.setText( getDocString( "packForgeURL" ) );
        packForgeHashField.setText( getDocString( "packForgeHash" ) );
        packLogoURLField.setText( getDocString( "packLogoURL" ) );
        packLogoSha1Field.setText( getDocString( "packLogoSha1" ) );
        packBgURLField.setText( getDocString( "packBackgroundURL" ) );
        packBgSha1Field.setText( getDocString( "packBackgroundSha1" ) );

        // Scan exclusions
        if ( workingDocument.has( "packScanExclusions" ) && workingDocument.get( "packScanExclusions" ).isJsonArray() ) {
            StringBuilder sb = new StringBuilder();
            for ( var el : workingDocument.getAsJsonArray( "packScanExclusions" ) ) {
                if ( sb.length() > 0 ) {
                    sb.append( "\n" );
                }
                sb.append( el.getAsString() );
            }
            scanExclusionsArea.setText( sb.toString() );
        }
        else {
            scanExclusionsArea.setText( "" );
        }

        // Image previews
        refreshImagePreview( getDocString( "packLogoURL" ), logoPreview );
        refreshImagePreview( getDocString( "packBackgroundURL" ), bgPreview );

        // File lists
        populateFileListsFromDocument();
    }

    /**
     * Collects all UI field values back into the working document.
     */
    private void collectFieldsToDocument()
    {
        if ( workingDocument == null ) {
            workingDocument = new JsonObject();
        }

        workingDocument.addProperty( "packName", packNameField.getText() );
        workingDocument.addProperty( "packVersion", packVersionField.getText() );
        workingDocument.addProperty( "packURL", packURLField.getText() );
        workingDocument.addProperty( "packMinRAMGB", packMinRAMField.getText() );
        workingDocument.addProperty( "packUnstable", packUnstableToggle.isSelected() );
        workingDocument.addProperty( "packCustomDiscordRpc", packCustomDiscordRpcToggle.isSelected() );
        workingDocument.addProperty( "packForgeURL", packForgeURLField.getText() );
        workingDocument.addProperty( "packForgeHash", packForgeHashField.getText() );
        workingDocument.addProperty( "packLogoURL", packLogoURLField.getText() );
        workingDocument.addProperty( "packLogoSha1", packLogoSha1Field.getText() );
        workingDocument.addProperty( "packBackgroundURL", packBgURLField.getText() );
        workingDocument.addProperty( "packBackgroundSha1", packBgSha1Field.getText() );

        // Scan exclusions
        JsonArray exclusions = new JsonArray();
        String exclusionText = scanExclusionsArea.getText();
        if ( exclusionText != null && !exclusionText.isBlank() ) {
            for ( String line : exclusionText.split( "\n" ) ) {
                String trimmed = line.trim();
                if ( !trimmed.isEmpty() ) {
                    exclusions.add( trimmed );
                }
            }
        }
        workingDocument.add( "packScanExclusions", exclusions );

        // File lists
        collectFileListsToDocument();
    }

    // endregion

    // region File list tabs

    /**
     * Creates a Tab containing a filterable, editable TableView for one file list type.
     *
     * @param tabName          display name for the tab
     * @param jsonArrayKey     the key in the working document (e.g., "packMods")
     * @param hasName          true if entries have a "name" field (mods only)
     * @param hasClientServerReq true if entries have clientReq/serverReq fields
     *
     * @return the configured Tab
     */
    @SuppressWarnings( "unchecked" )
    private Tab createFileListTab( String tabName, String jsonArrayKey, boolean hasName, boolean hasClientServerReq )
    {
        ObservableList< ModPackEditorFileEntry > data = FXCollections.observableArrayList();
        fileListData.put( jsonArrayKey, data );

        FilteredList< ModPackEditorFileEntry > filtered = new FilteredList<>( data, p -> true );
        SortedList< ModPackEditorFileEntry > sorted = new SortedList<>( filtered );

        TableView< ModPackEditorFileEntry > table = new TableView<>( sorted );
        sorted.comparatorProperty().bind( table.comparatorProperty() );
        table.setEditable( true );
        table.getSelectionModel().setSelectionMode( SelectionMode.MULTIPLE );
        table.setColumnResizePolicy( TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN );

        // Columns -- URL/path columns grow with the window; small columns stay fixed
        if ( hasName ) {
            TableColumn< ModPackEditorFileEntry, String > nameCol = new TableColumn<>( "Name" );
            nameCol.setCellValueFactory( c -> c.getValue().nameProperty() );
            nameCol.setCellFactory( TextFieldTableCell.forTableColumn() );
            nameCol.setOnEditCommit( e -> e.getRowValue().setName( e.getNewValue() ) );
            nameCol.setPrefWidth( 120 );
            nameCol.setMinWidth( 80 );
            nameCol.setMaxWidth( 200 );
            table.getColumns().add( nameCol );
        }

        TableColumn< ModPackEditorFileEntry, String > remoteCol = new TableColumn<>( "Remote URL" );
        remoteCol.setCellValueFactory( c -> c.getValue().remoteProperty() );
        remoteCol.setCellFactory( TextFieldTableCell.forTableColumn() );
        remoteCol.setOnEditCommit( e -> e.getRowValue().setRemote( e.getNewValue() ) );
        remoteCol.setPrefWidth( 300 );
        remoteCol.setMinWidth( 150 );
        // No maxWidth -- this column absorbs most of the extra space
        table.getColumns().add( remoteCol );

        TableColumn< ModPackEditorFileEntry, String > localCol = new TableColumn<>( "Local Path" );
        localCol.setCellValueFactory( c -> c.getValue().localProperty() );
        localCol.setCellFactory( TextFieldTableCell.forTableColumn() );
        localCol.setOnEditCommit( e -> e.getRowValue().setLocal( e.getNewValue() ) );
        localCol.setPrefWidth( 150 );
        localCol.setMinWidth( 100 );
        // No maxWidth -- grows with window, secondary to Remote URL
        table.getColumns().add( localCol );

        TableColumn< ModPackEditorFileEntry, String > hashCol = new TableColumn<>( "Hash" );
        hashCol.setCellValueFactory( c -> c.getValue().hashProperty() );
        hashCol.setCellFactory( TextFieldTableCell.forTableColumn() );
        hashCol.setOnEditCommit( e -> e.getRowValue().setHash( e.getNewValue() ) );
        hashCol.setPrefWidth( 100 );
        hashCol.setMinWidth( 60 );
        hashCol.setMaxWidth( 160 );
        table.getColumns().add( hashCol );

        TableColumn< ModPackEditorFileEntry, String > hashTypeCol = new TableColumn<>( "Type" );
        hashTypeCol.setCellValueFactory( c -> c.getValue().hashTypeProperty() );
        hashTypeCol.setCellFactory( TextFieldTableCell.forTableColumn() );
        hashTypeCol.setOnEditCommit( e -> e.getRowValue().setHashType( e.getNewValue() ) );
        hashTypeCol.setPrefWidth( 55 );
        hashTypeCol.setMinWidth( 45 );
        hashTypeCol.setMaxWidth( 65 );
        table.getColumns().add( hashTypeCol );

        if ( hasClientServerReq ) {
            TableColumn< ModPackEditorFileEntry, Boolean > clientCol = new TableColumn<>( "Client" );
            clientCol.setCellValueFactory( c -> c.getValue().clientReqProperty() );
            clientCol.setCellFactory( CheckBoxTableCell.forTableColumn( clientCol ) );
            clientCol.setPrefWidth( 55 );
            clientCol.setMinWidth( 50 );
            clientCol.setMaxWidth( 60 );
            table.getColumns().add( clientCol );

            TableColumn< ModPackEditorFileEntry, Boolean > serverCol = new TableColumn<>( "Server" );
            serverCol.setCellValueFactory( c -> c.getValue().serverReqProperty() );
            serverCol.setCellFactory( CheckBoxTableCell.forTableColumn( serverCol ) );
            serverCol.setPrefWidth( 55 );
            serverCol.setMinWidth( 50 );
            serverCol.setMaxWidth( 60 );
            table.getColumns().add( serverCol );
        }

        // Hash calc button column
        TableColumn< ModPackEditorFileEntry, Void > calcCol = new TableColumn<>( "" );
        calcCol.setPrefWidth( 50 );
        calcCol.setMinWidth( 45 );
        calcCol.setMaxWidth( 55 );
        calcCol.setSortable( false );
        calcCol.setCellFactory( col -> new TableCell< >()
        {
            private final Button btn = new Button( "Calc" );

            {
                btn.setOnAction( e -> {
                    ModPackEditorFileEntry entry = getTableView().getItems().get( getIndex() );
                    String remoteUrl = entry.getRemote();
                    if ( remoteUrl == null || remoteUrl.isBlank() ) {
                        return;
                    }
                    SystemUtilities.spawnNewTask( () -> {
                        try {
                            updateStatus( "Computing hash for " + entry.getLocal() + "..." );
                            File tempFile = File.createTempFile( "mclauncher_hash_", ".tmp" );
                            tempFile.deleteOnExit();
                            NetworkUtilities.downloadFileFromURL( new URL( remoteUrl ), tempFile );
                            String sha1 = HashUtilities.getFileSHA1( tempFile );
                            tempFile.delete();
                            if ( sha1 != null ) {
                                GUIUtilities.JFXPlatformRun( () -> {
                                    entry.setHash( sha1 );
                                    entry.setHashType( "sha1" );
                                    table.refresh();
                                } );
                                updateStatus( "Hash computed: " + sha1.substring( 0, 8 ) + "..." );
                            }
                            else {
                                updateStatus( "Hash computation returned null" );
                            }
                        }
                        catch ( Exception ex ) {
                            Logger.logWarningSilent( "Hash calc failed: " + ex.getMessage() );
                            updateStatus( "Hash calc failed: " + ex.getMessage() );
                        }
                    } );
                } );
                btn.setStyle( "-fx-font-size: 10;" );
            }

            @Override
            protected void updateItem( Void item, boolean empty )
            {
                super.updateItem( item, empty );
                setGraphic( empty ? null : btn );
            }
        } );
        table.getColumns().add( calcCol );

        // Filter field and buttons
        MFXTextField filterField = new MFXTextField();
        filterField.setFloatingText( "Filter..." );
        filterField.setPrefWidth( 200 );
        filterField.setMinHeight( 32 );
        filterField.textProperty().addListener( ( obs, oldVal, newVal ) -> {
            String lower = newVal == null ? "" : newVal.toLowerCase();
            filtered.setPredicate( entry -> {
                if ( lower.isEmpty() ) {
                    return true;
                }
                return entry.getName().toLowerCase().contains( lower ) ||
                       entry.getLocal().toLowerCase().contains( lower ) ||
                       entry.getRemote().toLowerCase().contains( lower );
            } );
        } );

        MFXButton addBtn = new MFXButton( "Add" );
        addBtn.setOnAction( e -> {
            ModPackEditorFileEntry newEntry = new ModPackEditorFileEntry();
            data.add( newEntry );
        } );

        MFXButton removeBtn = new MFXButton( "Remove" );
        removeBtn.getStyleClass().add( "dangerZone" );
        removeBtn.setOnAction( e -> {
            List< ModPackEditorFileEntry > selected = new ArrayList<>( table.getSelectionModel().getSelectedItems() );
            data.removeAll( selected );
        } );

        Label countLabel = new Label( "0 entries" );
        data.addListener( ( javafx.collections.ListChangeListener< ModPackEditorFileEntry > ) change ->
                countLabel.setText( data.size() + " entries" ) );

        HBox toolbar = new HBox( 8, filterField, addBtn, removeBtn, countLabel );
        toolbar.setAlignment( Pos.CENTER_LEFT );
        toolbar.setPadding( new Insets( 8 ) );

        BorderPane content = new BorderPane();
        content.setTop( toolbar );
        content.setCenter( table );

        Tab tab = new Tab( tabName, content );
        tab.setClosable( false );
        return tab;
    }

    /**
     * Populates all file list tabs from the working document's JSON arrays.
     */
    private void populateFileListsFromDocument()
    {
        populateFileList( "packMods", true, true );
        populateFileList( "packConfigs", false, true );
        populateFileList( "packResourcePacks", false, false );
        populateFileList( "packShaderPacks", false, false );
        populateFileList( "packInitialFiles", false, true );
    }

    private void populateFileList( String jsonArrayKey, boolean hasName, boolean hasClientServerReq )
    {
        ObservableList< ModPackEditorFileEntry > data = fileListData.get( jsonArrayKey );
        if ( data == null ) {
            return;
        }
        data.clear();

        if ( workingDocument == null || !workingDocument.has( jsonArrayKey ) ||
                !workingDocument.get( jsonArrayKey ).isJsonArray() ) {
            return;
        }

        for ( JsonElement el : workingDocument.getAsJsonArray( jsonArrayKey ) ) {
            if ( !el.isJsonObject() ) {
                continue;
            }
            JsonObject obj = el.getAsJsonObject();
            String name = hasName && obj.has( "name" ) ? obj.get( "name" ).getAsString() : "";
            String remote = obj.has( "remote" ) ? obj.get( "remote" ).getAsString() : "";
            String local = obj.has( "local" ) ? obj.get( "local" ).getAsString() : "";

            // Determine hash and hash type from whichever hash field is populated
            String hash = "";
            String hashType = "sha1";
            if ( obj.has( "sha1" ) && !obj.get( "sha1" ).getAsString().equals( "-1" ) ) {
                hash = obj.get( "sha1" ).getAsString();
                hashType = "sha1";
            }
            else if ( obj.has( "md5" ) && !obj.get( "md5" ).getAsString().equals( "-1" ) ) {
                hash = obj.get( "md5" ).getAsString();
                hashType = "md5";
            }
            else if ( obj.has( "sha256" ) && !obj.get( "sha256" ).getAsString().equals( "-1" ) ) {
                hash = obj.get( "sha256" ).getAsString();
                hashType = "sha256";
            }

            boolean clientReq = !hasClientServerReq || !obj.has( "clientReq" ) || obj.get( "clientReq" ).getAsBoolean();
            boolean serverReq = !hasClientServerReq || !obj.has( "serverReq" ) || obj.get( "serverReq" ).getAsBoolean();

            data.add( new ModPackEditorFileEntry( name, remote, local, hash, hashType, clientReq, serverReq ) );
        }
    }

    /**
     * Collects all file list tab entries back into the working document's JSON arrays.
     */
    private void collectFileListsToDocument()
    {
        collectFileList( "packMods", true, true );
        collectFileList( "packConfigs", false, true );
        collectFileList( "packResourcePacks", false, false );
        collectFileList( "packShaderPacks", false, false );
        collectFileList( "packInitialFiles", false, true );
    }

    private void collectFileList( String jsonArrayKey, boolean hasName, boolean hasClientServerReq )
    {
        ObservableList< ModPackEditorFileEntry > data = fileListData.get( jsonArrayKey );
        if ( data == null ) {
            return;
        }

        JsonArray array = new JsonArray();
        for ( ModPackEditorFileEntry entry : data ) {
            JsonObject obj = new JsonObject();
            if ( hasName ) {
                obj.addProperty( "name", entry.getName() );
            }
            obj.addProperty( "remote", entry.getRemote() );
            obj.addProperty( "local", entry.getLocal() );

            // Write hash in the correct field based on hashType
            String ht = entry.getHashType();
            String hv = entry.getHash();
            if ( "md5".equalsIgnoreCase( ht ) ) {
                obj.addProperty( "sha1", "-1" );
                obj.addProperty( "md5", hv.isEmpty() ? "-1" : hv );
                obj.addProperty( "sha256", "-1" );
            }
            else if ( "sha256".equalsIgnoreCase( ht ) ) {
                obj.addProperty( "sha1", "-1" );
                obj.addProperty( "md5", "-1" );
                obj.addProperty( "sha256", hv.isEmpty() ? "-1" : hv );
            }
            else {
                // Default to sha1
                obj.addProperty( "sha1", hv.isEmpty() ? "-1" : hv );
                obj.addProperty( "md5", "-1" );
                obj.addProperty( "sha256", "-1" );
            }

            if ( hasClientServerReq ) {
                obj.addProperty( "clientReq", entry.isClientReq() );
                obj.addProperty( "serverReq", entry.isServerReq() );
            }

            array.add( obj );
        }
        workingDocument.add( jsonArrayKey, array );
    }

    // endregion

    // region Helpers

    private String getDocString( String key )
    {
        if ( workingDocument.has( key ) && !workingDocument.get( key ).isJsonNull() ) {
            return workingDocument.get( key ).getAsString();
        }
        return "";
    }

    private boolean getDocBool( String key )
    {
        if ( workingDocument.has( key ) && !workingDocument.get( key ).isJsonNull() ) {
            return workingDocument.get( key ).getAsBoolean();
        }
        return false;
    }

    private String serializeDocument()
    {
        return new GsonBuilder().setPrettyPrinting().create().toJson( workingDocument );
    }

    /**
     * Collects UI fields into the document, then serializes. Use this when saving or validating (where the UI state
     * must be captured into the document first).
     */
    private String collectAndSerializeDocument()
    {
        collectFieldsToDocument();
        return serializeDocument();
    }

    private boolean isDirty()
    {
        if ( workingDocument == null ) {
            return false;
        }
        return !collectAndSerializeDocument().equals( savedSnapshot );
    }

    private void validateFileEntries( StringBuilder issues, String jsonArrayKey, String label, boolean hasName )
    {
        ObservableList< ModPackEditorFileEntry > data = fileListData.get( jsonArrayKey );
        if ( data == null || data.isEmpty() ) {
            return;
        }
        int idx = 0;
        for ( ModPackEditorFileEntry entry : data ) {
            idx++;
            String entryLabel = label + " #" + idx;
            if ( hasName && !entry.getName().isBlank() ) {
                entryLabel = label + " \"" + entry.getName() + "\"";
            }
            if ( entry.getRemote().isBlank() ) {
                issues.append( "- " ).append( entryLabel ).append( ": Remote URL is empty\n" );
            }
            if ( entry.getLocal().isBlank() ) {
                issues.append( "- " ).append( entryLabel ).append( ": Local path is empty\n" );
            }
        }
    }

    private void checkRequired( StringBuilder issues, String key, String label )
    {
        if ( !workingDocument.has( key ) || workingDocument.get( key ).getAsString().isBlank() ) {
            issues.append( "- " ).append( label ).append( " is required\n" );
        }
    }

    private void refreshImagePreview( String url, ImageView imageView )
    {
        if ( url == null || url.isBlank() ) {
            GUIUtilities.JFXPlatformRun( () -> imageView.setImage( null ) );
            return;
        }
        try {
            // JavaFX Image with backgroundLoading=true loads asynchronously
            Image image = new Image( url, true );
            GUIUtilities.JFXPlatformRun( () -> imageView.setImage( image ) );
        }
        catch ( Exception e ) {
            GUIUtilities.JFXPlatformRun( () -> imageView.setImage( null ) );
        }
    }

    private void updateStatus( String message )
    {
        GUIUtilities.JFXPlatformRun( () -> statusLabel.setText( message ) );
    }

    // endregion
}
