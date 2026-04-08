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
import com.micatechnologies.minecraft.launcher.utilities.CacheManager;
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
    @FXML MFXButton diffBtn;
    @FXML MFXButton returnBtn;
    @FXML TabPane editorTabPane;
    @FXML Label statusLabel;
    @FXML Label announcement;
    @FXML RowConstraints announcementRow;

    // Version bump buttons
    @FXML MFXButton bumpMajorBtn;
    @FXML MFXButton bumpMinorBtn;
    @FXML MFXButton bumpPatchBtn;

    // Forge picker
    @FXML MFXButton forgePickerBtn;

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

        // Forge picker
        forgePickerBtn.setOnAction( e -> pickForgeVersion() );

        // Version bump buttons
        bumpMajorBtn.setOnAction( e -> bumpVersion( 0 ) );
        bumpMinorBtn.setOnAction( e -> bumpVersion( 1 ) );
        bumpPatchBtn.setOnAction( e -> bumpVersion( 2 ) );

        // Toolbar buttons
        newBtn.setOnAction( e -> newDocument() );
        openFileBtn.setOnAction( e -> openFromFile() );
        openUrlBtn.setOnAction( e -> openFromUrl() );
        saveBtn.setOnAction( e -> saveToFile() );
        validateBtn.setOnAction( e -> validateDocument() );
        diffBtn.setOnAction( e -> showDiff() );
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
        Tab modsTab = createFileListTab( "Mods", "packMods", true, true );
        editorTabPane.getTabs().add( modsTab );
        editorTabPane.getTabs().add( createFileListTab( "Configs", "packConfigs", false, true ) );
        editorTabPane.getTabs().add( createFileListTab( "Resources", "packResourcePacks", false, false ) );
        editorTabPane.getTabs().add( createFileListTab( "Shaders", "packShaderPacks", false, false ) );
        editorTabPane.getTabs().add( createFileListTab( "Initial Files", "packInitialFiles", false, true ) );

        // Add Modrinth search button to the Mods tab toolbar
        BorderPane modsContent = ( BorderPane ) modsTab.getContent();
        HBox modsToolbar = ( HBox ) modsContent.getTop();
        MFXButton searchModrinthBtn = new MFXButton( "Search Modrinth" );
        searchModrinthBtn.setOnAction( e -> searchModrinth() );
        modsToolbar.getChildren().add( modsToolbar.getChildren().size() - 1, searchModrinthBtn );

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

    @Override
    HelpTopic getHelpTopic() { return HelpTopic.MODPACK_EDITOR; }

    // region Document operations

    /**
     * Creates a new empty modpack document with default values.
     */
    private void newDocument()
    {
        workingDocument = new JsonObject();
        workingDocument.addProperty( "manifestFormat", 2 );
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
                        GUIUtilities.JFXPlatformRun( () -> {
                            populateFieldsFromDocument();
                            // Snapshot after round-trip so the normalized form matches future diffs
                            collectFieldsToDocument();
                            savedSnapshot = serializeDocument();
                        } );
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
                        GUIUtilities.JFXPlatformRun( () -> {
                            populateFieldsFromDocument();
                            collectFieldsToDocument();
                            savedSnapshot = serializeDocument();
                        } );
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
     * Opens a Forge version picker dialog. Fetches available versions from the Forge Maven promotions API, displays
     * them grouped by Minecraft version, and auto-populates the Forge URL and hash fields.
     */
    private void pickForgeVersion()
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                updateStatus( "Fetching Forge versions..." );
                String json = NetworkUtilities.downloadFileFromURL(
                        "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json" );
                JsonObject promos = JSONUtilities.getGson().fromJson( json, JsonObject.class )
                                                  .getAsJsonObject( "promos" );

                // Build list of entries: "MC x.y.z - Forge a.b.c (recommended)" etc.
                List< String > entries = new ArrayList<>();
                Map< String, String > entryToForgeUrl = new HashMap<>();

                // Collect unique MC versions and their forge versions
                Map< String, String > recommended = new HashMap<>();
                Map< String, String > latest = new HashMap<>();
                for ( Map.Entry< String, JsonElement > entry : promos.entrySet() ) {
                    String key = entry.getKey();
                    String forgeVer = entry.getValue().getAsString();
                    if ( key.endsWith( "-recommended" ) ) {
                        String mcVer = key.replace( "-recommended", "" );
                        recommended.put( mcVer, forgeVer );
                    }
                    else if ( key.endsWith( "-latest" ) ) {
                        String mcVer = key.replace( "-latest", "" );
                        latest.put( mcVer, forgeVer );
                    }
                }

                // Build display entries, recommended first
                List< String > mcVersions = new ArrayList<>( latest.keySet() );
                mcVersions.sort( ( a, b ) -> com.micatechnologies.minecraft.launcher.utilities.VersionUtilities
                        .compareVersionNumbers( b, a ) );

                for ( String mcVer : mcVersions ) {
                    if ( recommended.containsKey( mcVer ) ) {
                        String forgeVer = recommended.get( mcVer );
                        String label = "MC " + mcVer + " - Forge " + forgeVer + " (recommended)";
                        String url = "https://maven.minecraftforge.net/net/minecraftforge/forge/" +
                                mcVer + "-" + forgeVer + "/forge-" + mcVer + "-" + forgeVer + "-installer.jar";
                        entries.add( label );
                        entryToForgeUrl.put( label, url );
                    }
                    if ( latest.containsKey( mcVer ) ) {
                        String forgeVer = latest.get( mcVer );
                        String recVer = recommended.get( mcVer );
                        // Skip if latest == recommended (already listed)
                        if ( recVer != null && recVer.equals( forgeVer ) ) {
                            continue;
                        }
                        String label = "MC " + mcVer + " - Forge " + forgeVer + " (latest)";
                        String url = "https://maven.minecraftforge.net/net/minecraftforge/forge/" +
                                mcVer + "-" + forgeVer + "/forge-" + mcVer + "-" + forgeVer + "-installer.jar";
                        entries.add( label );
                        entryToForgeUrl.put( label, url );
                    }
                }

                GUIUtilities.JFXPlatformRun( () -> {
                    ChoiceDialog< String > dialog = new ChoiceDialog<>( entries.isEmpty() ? null : entries.get( 0 ),
                                                                         entries );
                    dialog.setTitle( "Pick Forge Version" );
                    dialog.setHeaderText( "Select a Forge version" );
                    dialog.setContentText( "Forge:" );
                    applyThemeToDialog( dialog );
                    dialog.showAndWait().ifPresent( selected -> {
                        String url = entryToForgeUrl.get( selected );
                        if ( url != null ) {
                            packForgeURLField.setText( url );
                            packForgeHashField.setText( "" );
                            updateStatus( "Forge URL set. Computing hash..." );
                            // Download and compute hash in background
                            SystemUtilities.spawnNewTask( () -> {
                                try {
                                    File tempFile = File.createTempFile( "forge_installer_", ".jar" );
                                    tempFile.deleteOnExit();
                                    NetworkUtilities.downloadFileFromURL( new URL( url ), tempFile );
                                    String sha1 = HashUtilities.getFileSHA1( tempFile );
                                    tempFile.delete();
                                    if ( sha1 != null ) {
                                        GUIUtilities.JFXPlatformRun(
                                                () -> packForgeHashField.setText( sha1 ) );
                                        updateStatus( "Forge hash computed: " + sha1.substring( 0, 8 ) + "..." );
                                    }
                                }
                                catch ( Exception ex ) {
                                    updateStatus( "Failed to compute Forge hash: " + ex.getMessage() );
                                }
                            } );
                        }
                    } );
                } );
            }
            catch ( Exception ex ) {
                Logger.logError( "Failed to fetch Forge promotions." );
                Logger.logThrowable( ex );
                updateStatus( "Failed to fetch Forge versions: " + ex.getMessage() );
            }
        } );
    }

    /**
     * Opens a Modrinth search dialog. The user types a mod name, results are displayed in a list, and selected mods
     * can be added to the Mods table with their download URL and hash pre-populated.
     */
    private void searchModrinth()
    {
        GUIUtilities.JFXPlatformRun( () -> {
            // Build a custom dialog with search field and results list
            Dialog< List< ModPackEditorFileEntry > > dialog = new Dialog<>();
            dialog.setTitle( "Search Modrinth" );
            dialog.setHeaderText( "Search for mods on Modrinth" );

            ButtonType addButtonType = new ButtonType( "Add Selected", ButtonBar.ButtonData.OTHER );
            dialog.getDialogPane().getButtonTypes().addAll( addButtonType, ButtonType.CANCEL );

            // Search field
            MFXTextField searchField = new MFXTextField();
            searchField.setFloatingText( "Mod name..." );
            searchField.setPrefWidth( 400 );
            searchField.setMinHeight( 36 );

            MFXButton searchBtn = new MFXButton( "Search" );
            HBox searchBar = new HBox( 8, searchField, searchBtn );
            searchBar.setAlignment( Pos.CENTER_LEFT );

            // Game version filter
            javafx.scene.control.CheckBox versionFilterCheck = new javafx.scene.control.CheckBox(
                    "Filter by game version:" );
            MFXTextField versionFilterField = new MFXTextField();
            versionFilterField.setPrefWidth( 100 );
            versionFilterField.setMinHeight( 32 );
            // Try to auto-detect MC version from the Forge URL (e.g., "forge/1.20.1-47.3.0/...")
            String forgeUrl = packForgeURLField.getText();
            if ( forgeUrl != null && forgeUrl.contains( "/forge/" ) ) {
                String afterForge = forgeUrl.substring( forgeUrl.indexOf( "/forge/" ) + 7 );
                int dashIdx = afterForge.indexOf( "-" );
                if ( dashIdx > 0 ) {
                    versionFilterField.setText( afterForge.substring( 0, dashIdx ) );
                    versionFilterCheck.setSelected( true );
                }
            }
            versionFilterField.addEventFilter( javafx.scene.input.KeyEvent.KEY_PRESSED, ev -> {
                if ( ev.getCode() == javafx.scene.input.KeyCode.ENTER ) {
                    ev.consume();
                    searchBtn.fire();
                }
            } );
            versionFilterField.disableProperty().bind( versionFilterCheck.selectedProperty().not() );
            HBox versionBar = new HBox( 8, versionFilterCheck, versionFilterField );
            versionBar.setAlignment( Pos.CENTER_LEFT );

            // Capture theme colors for use inside the cell factory
            String[] cellColors = getThemeColors();
            String cellBg = cellColors[ 0 ];
            String cellFg = cellColors[ 1 ];
            String cellSurface = cellColors[ 4 ];

            // Results list with rich cells (icon + title + description)
            ListView< JsonObject > resultsList = new ListView<>();
            resultsList.setPrefHeight( 350 );
            resultsList.getSelectionModel().setSelectionMode( SelectionMode.MULTIPLE );
            resultsList.setCellFactory( lv -> new ListCell< >()
            {
                private final ImageView icon = new ImageView();
                private final Label titleLabel = new Label();
                private final Label descLabel = new Label();
                private final javafx.scene.layout.VBox textBox = new javafx.scene.layout.VBox( 2, titleLabel,
                                                                                                descLabel );
                private final HBox cellBox = new HBox( 10, icon, textBox );

                {
                    icon.setFitWidth( 40 );
                    icon.setFitHeight( 40 );
                    icon.setPreserveRatio( true );
                    titleLabel.setStyle( "-fx-font-weight: bold; -fx-font-size: 13; -fx-text-fill: " + cellFg + ";" );
                    descLabel.setStyle( "-fx-font-size: 11; -fx-opacity: 0.8; -fx-text-fill: " + cellFg + ";" );
                    descLabel.setWrapText( true );
                    descLabel.setMaxHeight( 32 );
                    cellBox.setAlignment( Pos.CENTER_LEFT );
                    cellBox.setPadding( new Insets( 4 ) );
                    textBox.setMaxWidth( Double.MAX_VALUE );
                    javafx.scene.layout.HBox.setHgrow( textBox, javafx.scene.layout.Priority.ALWAYS );
                }

                @Override
                protected void updateItem( JsonObject project, boolean empty )
                {
                    super.updateItem( project, empty );
                    if ( empty || project == null ) {
                        setGraphic( null );
                        setStyle( "-fx-background-color: transparent;" );
                        return;
                    }
                    setStyle( "-fx-background-color: " + cellSurface + "; -fx-background-radius: 8;" +
                              "-fx-background-insets: 2;" );
                    String title = project.has( "title" ) ? project.get( "title" ).getAsString() : "?";
                    String author = project.has( "author" ) ? project.get( "author" ).getAsString() : "";
                    String desc = project.has( "description" ) ? project.get( "description" ).getAsString() : "";
                    String iconUrl = project.has( "icon_url" ) ? project.get( "icon_url" ).getAsString() : "";

                    titleLabel.setText( title + ( author.isEmpty() ? "" : "  by " + author ) );

                    // Truncate description to ~120 chars
                    if ( desc.length() > 120 ) {
                        desc = desc.substring( 0, 117 ) + "...";
                    }
                    descLabel.setText( desc );

                    // Load icon -- if search result has no icon_url, try project detail API
                    String projectSlugForIcon = project.has( "slug" ) ?
                            project.get( "slug" ).getAsString() : null;
                    icon.setImage( null );
                    if ( !iconUrl.isEmpty() || ( projectSlugForIcon != null && !projectSlugForIcon.isEmpty() ) ) {
                        final String finalIconUrl = iconUrl;
                        final String finalSlug = projectSlugForIcon;
                        SystemUtilities.spawnNewTask( () -> {
                            try {
                                String resolvedUrl = finalIconUrl;
                                // If search result didn't include icon_url, fetch from project detail
                                if ( resolvedUrl.isEmpty() && finalSlug != null ) {
                                    String projectJson = NetworkUtilities.downloadFileFromURL(
                                            "https://api.modrinth.com/v2/project/" + finalSlug );
                                    JsonObject projDetail = JSONUtilities.getGson().fromJson( projectJson,
                                                                                              JsonObject.class );
                                    if ( projDetail.has( "icon_url" ) &&
                                            !projDetail.get( "icon_url" ).isJsonNull() ) {
                                        resolvedUrl = projDetail.get( "icon_url" ).getAsString();
                                    }
                                }
                                if ( !resolvedUrl.isEmpty() ) {
                                    File cachedIcon = CacheManager.downloadAndCache( resolvedUrl );
                                    Image img = new Image( cachedIcon.toURI().toString(), 40, 40, true, true );
                                    if ( !img.isError() ) {
                                        GUIUtilities.JFXPlatformRun( () -> icon.setImage( img ) );
                                    }
                                }
                            }
                            catch ( Exception ignored ) {
                                // Icon load failure is non-critical
                            }
                        } );
                    }
                    // Context menu for opening Modrinth page
                    String projectSlug = project.has( "slug" ) ? project.get( "slug" ).getAsString() : null;
                    if ( projectSlug != null && !projectSlug.isEmpty() ) {
                        ContextMenu contextMenu = new ContextMenu();
                        MenuItem openPageItem = new MenuItem( "Open Modrinth Page" );
                        openPageItem.setOnAction( ev -> {
                            try {
                                java.awt.Desktop.getDesktop().browse(
                                        java.net.URI.create( "https://modrinth.com/mod/" + projectSlug ) );
                            }
                            catch ( Exception ignored ) {
                            }
                        } );
                        contextMenu.getItems().add( openPageItem );
                        setContextMenu( contextMenu );
                    }

                    setGraphic( cellBox );
                }
            } );

            Label infoLabel = new Label( "Enter a search term and click Search" );

            javafx.scene.layout.VBox content = new javafx.scene.layout.VBox( 8, searchBar, versionBar, infoLabel,
                                                                              resultsList );
            content.setPrefWidth( 600 );

            // Apply inline theme colors to every dialog element
            String[] colors = getThemeColors();
            String bg = colors[ 0 ];
            String fg = colors[ 1 ];
            String surfaceDark = colors[ 2 ];
            String accent = colors[ 3 ];
            String surfaceContainer = colors[ 4 ];

            content.setStyle( "-fx-background-color: " + bg + ";" );
            infoLabel.setStyle( "-fx-text-fill: " + fg + ";" );
            resultsList.setStyle( "-fx-background-color: " + surfaceDark + ";" +
                    "-fx-border-color: " + surfaceContainer + "; -fx-border-width: 1; -fx-border-radius: 8;" );
            versionFilterCheck.setStyle( "-fx-text-fill: " + fg + "; -fx-mark-color: " + accent + ";" );
            searchField.setStyle( "-fx-text-fill: " + fg + "; -fx-background-color: " + surfaceContainer + ";" +
                    "-fx-border-color: " + surfaceContainer + "; -fx-border-radius: 8;" );
            versionFilterField.setStyle( "-fx-text-fill: " + fg + "; -fx-background-color: " + surfaceContainer +
                    "; -fx-border-color: " + surfaceContainer + "; -fx-border-radius: 8;" );
            searchBtn.setStyle( "-fx-background-color: " + accent + "; -fx-text-fill: white;" +
                    "-fx-background-radius: 20; -fx-padding: 6 16 6 16;" );

            dialog.getDialogPane().setContent( content );

            // Apply theme to the dialog
            applyThemeToDialog( dialog );

            // Comprehensively style every dialog element after layout
            dialog.setOnShown( e -> {
                javafx.scene.Scene dialogScene = dialog.getDialogPane().getScene();
                if ( dialogScene != null ) {
                    dialogScene.getRoot().setStyle( "-fx-background-color: " + bg + ";" );
                    if ( scene != null ) {
                        dialogScene.getStylesheets().addAll( scene.getStylesheets() );
                    }
                }
                // Header panel background
                dialog.getDialogPane().lookupAll( ".header-panel" ).forEach( node ->
                        node.setStyle( "-fx-background-color: " + surfaceContainer + ";" ) );
                // Header text
                dialog.getDialogPane().lookupAll( ".header-panel .label" ).forEach( node ->
                        node.setStyle( "-fx-text-fill: " + fg + ";" ) );
                // Button bar background
                dialog.getDialogPane().lookupAll( ".button-bar" ).forEach( node ->
                        node.setStyle( "-fx-background-color: " + bg + "; -fx-padding: 12;" ) );
                // All buttons in the dialog
                dialog.getDialogPane().lookupAll( ".button-bar .button" ).forEach( node ->
                        node.setStyle( "-fx-background-color: " + accent + "; -fx-text-fill: white;" +
                                "-fx-background-radius: 12; -fx-padding: 6 16 6 16; -fx-cursor: hand;" ) );
                // Scrollbars
                dialog.getDialogPane().lookupAll( ".scroll-bar" ).forEach( node ->
                        node.setStyle( "-fx-background-color: transparent;" ) );
                dialog.getDialogPane().lookupAll( ".scroll-bar .thumb" ).forEach( node ->
                        node.setStyle( "-fx-background-color: rgba(200,200,200,0.35); -fx-background-radius: 5;" ) );
                dialog.getDialogPane().lookupAll( ".scroll-bar .track" ).forEach( node ->
                        node.setStyle( "-fx-background-color: transparent;" ) );
                dialog.getDialogPane().lookupAll( ".scroll-bar .increment-button" ).forEach( node ->
                        node.setStyle( "-fx-background-color: transparent; -fx-padding: 0;" ) );
                dialog.getDialogPane().lookupAll( ".scroll-bar .decrement-button" ).forEach( node ->
                        node.setStyle( "-fx-background-color: transparent; -fx-padding: 0;" ) );
                dialog.getDialogPane().lookupAll( ".scroll-bar .increment-arrow" ).forEach( node ->
                        node.setStyle( "-fx-shape: \"\"; -fx-padding: 0;" ) );
                dialog.getDialogPane().lookupAll( ".scroll-bar .decrement-arrow" ).forEach( node ->
                        node.setStyle( "-fx-shape: \"\"; -fx-padding: 0;" ) );
            } );

            searchBtn.setOnAction( ev -> {
                String query = searchField.getText();
                if ( query == null || query.isBlank() ) {
                    return;
                }
                infoLabel.setText( "Searching..." );
                resultsList.getItems().clear();

                // Build facets: always filter to mods, optionally filter by game version
                String gameVersion = versionFilterCheck.isSelected() ? versionFilterField.getText().trim() : "";
                SystemUtilities.spawnNewTask( () -> {
                    try {
                        String encodedQuery = java.net.URLEncoder.encode( query, "UTF-8" );
                        String facetsJson;
                        if ( !gameVersion.isEmpty() ) {
                            facetsJson = "[[\"project_type:mod\"],[\"versions:" + gameVersion + "\"]]";
                        }
                        else {
                            facetsJson = "[[\"project_type:mod\"]]";
                        }
                        String encodedFacets = java.net.URLEncoder.encode( facetsJson, "UTF-8" );
                        String apiUrl = "https://api.modrinth.com/v2/search?query=" + encodedQuery +
                                "&facets=" + encodedFacets + "&limit=25";
                        String response = NetworkUtilities.downloadFileFromURL( apiUrl );
                        JsonObject json = JSONUtilities.getGson().fromJson( response, JsonObject.class );
                        JsonArray hits = json.getAsJsonArray( "hits" );

                        GUIUtilities.JFXPlatformRun( () -> {
                            for ( JsonElement hit : hits ) {
                                resultsList.getItems().add( hit.getAsJsonObject() );
                            }
                            infoLabel.setText( hits.size() + " results found. Select mods to add." );
                        } );
                    }
                    catch ( Exception ex ) {
                        GUIUtilities.JFXPlatformRun(
                                () -> infoLabel.setText( "Search failed: " + ex.getMessage() ) );
                    }
                } );
            } );

            // Handle Enter key in search field -- use key filter to prevent dialog close
            searchField.addEventFilter( javafx.scene.input.KeyEvent.KEY_PRESSED, ev -> {
                if ( ev.getCode() == javafx.scene.input.KeyCode.ENTER ) {
                    ev.consume();
                    searchBtn.fire();
                }
            } );

            dialog.setResultConverter( button -> {
                if ( button == addButtonType ) {
                    List< ModPackEditorFileEntry > entries = new ArrayList<>();
                    for ( JsonObject project : resultsList.getSelectionModel().getSelectedItems() ) {
                        if ( project != null ) {
                            String title = project.has( "title" ) ? project.get( "title" ).getAsString() : "";
                            String slug = project.has( "slug" ) ? project.get( "slug" ).getAsString() : "";
                            // We'll need to fetch version info to get the actual download URL
                            // For now, populate with Modrinth project page and mark for version fetch
                            ModPackEditorFileEntry entry = new ModPackEditorFileEntry();
                            entry.setName( title );
                            entry.setRemote( "modrinth:" + slug );
                            entry.setLocal( slug + ".jar" );
                            entry.setHashType( "sha1" );
                            entry.setModrinthSlug( slug );
                            entries.add( entry );
                        }
                    }
                    return entries;
                }
                return null;
            } );

            // Capture game version for version-filtered fetching
            final String selectedGameVersion = versionFilterCheck.isSelected() ?
                    versionFilterField.getText().trim() : "";

            dialog.showAndWait().ifPresent( entries -> {
                ObservableList< ModPackEditorFileEntry > modsData = fileListData.get( "packMods" );
                if ( modsData != null && !entries.isEmpty() ) {
                    // For each entry, fetch a compatible version from Modrinth to get the download URL
                    SystemUtilities.spawnNewTask( () -> {
                        for ( ModPackEditorFileEntry entry : entries ) {
                            String slug = entry.getRemote().replace( "modrinth:", "" );
                            try {
                                String versionFilter = selectedGameVersion.isEmpty() ? "" :
                                        "&game_versions=[\"" + selectedGameVersion + "\"]";
                                String versionsUrl =
                                        "https://api.modrinth.com/v2/project/" + slug + "/version?limit=1" +
                                        versionFilter;
                                String versionsJson = NetworkUtilities.downloadFileFromURL( versionsUrl );
                                JsonArray versions = JSONUtilities.getGson().fromJson( versionsJson, JsonArray.class );
                                if ( !versions.isEmpty() ) {
                                    JsonObject version = versions.get( 0 ).getAsJsonObject();
                                    JsonArray files = version.getAsJsonArray( "files" );
                                    if ( files != null && !files.isEmpty() ) {
                                        JsonObject file = files.get( 0 ).getAsJsonObject();
                                        String url = file.has( "url" ) ? file.get( "url" ).getAsString() : "";
                                        String filename = file.has( "filename" ) ?
                                                           file.get( "filename" ).getAsString() : slug + ".jar";
                                        String sha1 = "";
                                        if ( file.has( "hashes" ) ) {
                                            JsonObject hashes = file.getAsJsonObject( "hashes" );
                                            if ( hashes.has( "sha1" ) ) {
                                                sha1 = hashes.get( "sha1" ).getAsString();
                                            }
                                        }
                                        entry.setRemote( url );
                                        entry.setLocal( filename );
                                        entry.setHash( sha1 );
                                    }
                                }
                            }
                            catch ( Exception ex ) {
                                Logger.logWarningSilent( "Failed to fetch Modrinth version for " + slug );
                            }
                        }
                        GUIUtilities.JFXPlatformRun( () -> {
                            modsData.addAll( entries );
                            updateStatus( "Added " + entries.size() + " mod(s) from Modrinth" );
                        } );
                    } );
                }
            } );
        } );
    }

    /**
     * Shows a diff between the saved/loaded version and the current editor state.
     */
    private void showDiff()
    {
        collectFieldsToDocument();
        String current = serializeDocument();
        String original = savedSnapshot;

        if ( current.equals( original ) ) {
            GUIUtilities.JFXPlatformRun( () -> {
                Alert alert = new Alert( Alert.AlertType.INFORMATION );
                alert.setTitle( "Diff" );
                alert.setHeaderText( "No Changes" );
                alert.setContentText( "The document has not been modified since it was last saved or loaded." );
                applyThemeToDialog( alert );
                alert.showAndWait();
            } );
            return;
        }

        // Simple line-by-line diff
        String[] origLines = original.split( "\n" );
        String[] currLines = current.split( "\n" );
        StringBuilder diff = new StringBuilder();

        int maxLen = Math.max( origLines.length, currLines.length );
        for ( int i = 0; i < maxLen; i++ ) {
            String origLine = i < origLines.length ? origLines[ i ] : "";
            String currLine = i < currLines.length ? currLines[ i ] : "";
            if ( !origLine.equals( currLine ) ) {
                if ( !origLine.isEmpty() ) {
                    diff.append( "- " ).append( origLine.trim() ).append( "\n" );
                }
                if ( !currLine.isEmpty() ) {
                    diff.append( "+ " ).append( currLine.trim() ).append( "\n" );
                }
            }
        }

        String diffText = diff.toString();
        GUIUtilities.JFXPlatformRun( () -> {
            Alert alert = new Alert( Alert.AlertType.INFORMATION );
            alert.setTitle( "Diff" );
            alert.setHeaderText( "Changes since last save/load" );

            TextArea textArea = new TextArea( diffText );
            textArea.setEditable( false );
            textArea.setWrapText( false );
            textArea.setStyle( "-fx-font-family: monospace;" );
            textArea.setPrefHeight( 400 );
            textArea.setPrefWidth( 600 );

            alert.getDialogPane().setExpandableContent( textArea );
            alert.getDialogPane().setExpanded( true );
            applyThemeToDialog( alert );
            alert.showAndWait();
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
                applyThemeToDialog( alert );
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
        hashCol.setPrefWidth( 140 );
        hashCol.setMinWidth( 80 );
        // No maxWidth -- hash column can grow to show full SHA-1 (40 chars)
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

        MFXButton checkUrlsBtn = new MFXButton( "Check URLs" );
        checkUrlsBtn.setOnAction( e -> SystemUtilities.spawnNewTask( () -> {
            updateStatus( "Checking URLs for " + tabName + "..." );
            int broken = 0;
            int checked = 0;
            for ( ModPackEditorFileEntry entry : data ) {
                String remoteUrl = entry.getRemote();
                if ( remoteUrl == null || remoteUrl.isBlank() ) {
                    continue;
                }
                checked++;
                try {
                    java.net.HttpURLConnection conn =
                            ( java.net.HttpURLConnection ) new URL( remoteUrl ).openConnection();
                    conn.setRequestMethod( "HEAD" );
                    conn.setConnectTimeout( 5000 );
                    conn.setReadTimeout( 5000 );
                    conn.setRequestProperty( "User-Agent", "MicaMinecraftLauncher" );
                    int code = conn.getResponseCode();
                    conn.disconnect();
                    if ( code < 200 || code >= 400 ) {
                        entry.setHash( "[URL " + code + "] " + entry.getHash() );
                        broken++;
                    }
                }
                catch ( Exception ex ) {
                    entry.setHash( "[URL ERR] " + entry.getHash() );
                    broken++;
                }
            }
            final int finalBroken = broken;
            final int finalChecked = checked;
            GUIUtilities.JFXPlatformRun( () -> table.refresh() );
            updateStatus( "URL check: " + finalChecked + " checked, " + finalBroken + " broken" );
        } ) );

        Label countLabel = new Label( "0 entries" );
        data.addListener( ( javafx.collections.ListChangeListener< ModPackEditorFileEntry > ) change ->
                countLabel.setText( data.size() + " entries" ) );

        HBox toolbar = new HBox( 8, filterField, addBtn, removeBtn, checkUrlsBtn, countLabel );
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

            ModPackEditorFileEntry entry = new ModPackEditorFileEntry( name, remote, local, hash, hashType,
                                                                       clientReq, serverReq );
            // Read optional Modrinth slug (manifestFormat 2+)
            if ( obj.has( "modrinthSlug" ) ) {
                entry.setModrinthSlug( obj.get( "modrinthSlug" ).getAsString() );
            }
            data.add( entry );
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

            // Include Modrinth slug if present (manifestFormat 2+)
            if ( !entry.getModrinthSlug().isEmpty() ) {
                obj.addProperty( "modrinthSlug", entry.getModrinthSlug() );
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

    /**
     * Bumps the version number at the specified position (0=major, 1=minor, 2=patch). Parses the current version
     * string, increments the target segment, and resets all segments to the right to zero.
     *
     * @param position 0 for major, 1 for minor, 2 for patch
     */
    private void bumpVersion( int position )
    {
        String current = packVersionField.getText();
        if ( current == null || current.isBlank() ) {
            current = "0.0.0";
        }

        String[] parts = current.split( "\\." );
        int[] segments = new int[ Math.max( 3, parts.length ) ];
        for ( int i = 0; i < parts.length; i++ ) {
            try {
                segments[ i ] = Integer.parseInt( parts[ i ] );
            }
            catch ( NumberFormatException ignored ) {
                segments[ i ] = 0;
            }
        }

        // Increment target and reset segments to the right
        if ( position < segments.length ) {
            segments[ position ]++;
            for ( int i = position + 1; i < segments.length; i++ ) {
                segments[ i ] = 0;
            }
        }

        String newVersion = segments[ 0 ] + "." + segments[ 1 ] + "." + segments[ 2 ];
        packVersionField.setText( newVersion );
        updateStatus( "Version bumped to " + newVersion );
    }

    /**
     * Applies the current scene's theme stylesheet to a dialog so it matches the app's look. Stylesheets are applied
     * both to the DialogPane and to the Dialog's own Scene (which is only available once the dialog is showing).
     */
    /**
     * Determines the current theme's background and text colors based on ConfigManager.
     */
    private String[] getThemeColors()
    {
        String theme = com.micatechnologies.minecraft.launcher.config.ConfigManager.getTheme();
        return switch ( theme.toLowerCase() )
        {
            case "light" -> new String[]{ "#FFFBFE", "#1C1B1F", "#F4EFF4", "#2E7D32", "rgba(231,224,236,0.55)" };
            case "blue+gray" -> new String[]{ "#111318", "#E2E2E9", "#0C0E13", "#4D82F0", "rgba(68,71,79,0.55)" };
            case "orange+purple" -> new String[]{ "#1F1019", "#F0DEE6", "#140B11", "#9C3587", "rgba(79,55,70,0.55)" };
            default -> new String[]{ "#1C1B1F", "#E6E1E5", "#131316", "#4CAF50", "rgba(73,69,79,0.55)" };
        };
    }

    /**
     * Applies the current theme to a dialog using inline styles. JavaFX Dialog's CSS stylesheet inheritance is
     * unreliable, so we apply colors directly to the dialog pane and its key children.
     */
    private void applyThemeToDialog( Dialog< ? > dialog )
    {
        String[] colors = getThemeColors();
        String bg = colors[ 0 ];
        String fg = colors[ 1 ];
        String surfaceDark = colors[ 2 ];
        String accent = colors[ 3 ];
        String surfaceContainer = colors[ 4 ];

        dialog.getDialogPane().setStyle(
                "-fx-background-color: " + bg + ";" );

        // Style the header panel
        dialog.getDialogPane().lookupAll( ".header-panel" ).forEach( node ->
                node.setStyle( "-fx-background-color: " + surfaceContainer + ";" ) );

        // Apply stylesheets for any CSS-styled children (buttons, list cells, etc.)
        if ( scene != null && !scene.getStylesheets().isEmpty() ) {
            dialog.getDialogPane().getStylesheets().addAll( scene.getStylesheets() );
        }
        dialog.getDialogPane().getStyleClass().add( "rootPane" );

        // Apply to the Dialog's Scene once available
        dialog.setOnShown( e -> {
            javafx.scene.Scene dialogScene = dialog.getDialogPane().getScene();
            if ( dialogScene != null ) {
                dialogScene.getRoot().setStyle( "-fx-background-color: " + bg + ";" );
                if ( scene != null ) {
                    dialogScene.getStylesheets().addAll( scene.getStylesheets() );
                }
            }
        } );
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
