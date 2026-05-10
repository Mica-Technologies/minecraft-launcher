/*
 * Copyright (c) 2021 Mica Technologies
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
import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.GUIConstants;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.utilities.*;
import com.micatechnologies.minecraft.launcher.system.DesktopShortcutManager;
import com.nativejavafx.taskbar.TaskbarProgressbar;
import com.nativejavafx.taskbar.TaskbarProgressbarFactory;
import io.github.palexdev.materialfx.controls.MFXButton;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MCLauncherMainGui extends MCLauncherAbstractGui
{
    /**
     * Installed mod pack select list.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    ListView< GameModPack > packSelectionList;

    /**
     * Play button. Starts the current selected mod pack.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton playBtn;

    /**
     * Exit button. Closes the application.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton exitBtn;

    /**
     * Settings button. Opens the settings window.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton settingsBtn;

    /**
     * Edit mod packs button. Opens the mod pack installation window.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton editButton;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton vanillaBtn;

    /**
     * Current user avatar image. Displays the avatar of the currently logged in user.
     *
     * @since 2.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    ImageView userImage;

    /**
     * Update available image. Displays a warning icon if there is a launcher update available.
     *
     * @since 2.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    ImageView updateImgView;

    /**
     * Player name label. Displays the user name of the currently logged in user.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    Label playerLabel;

    /**
     * Mod pack website button. Opens the mod pack website.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton websiteBtn;

    /**
     * Announcement banner.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    Label announcement;

    /**
     * Unstable mod pack warning banner.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    Label unstableWarning;

    /**
     * Announcement banner row constraints.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    RowConstraints announcementRow;

    /**
     * Center hero pane (replaces the legacy GridPane wrapper). Holds the modpack hero card.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    StackPane centerPane;

    // ----- Hero card fields (selected modpack detail) -----

    @SuppressWarnings( "unused" )
    @FXML
    ImageView heroPackLogo;

    @SuppressWarnings( "unused" )
    @FXML
    Label heroPackName;

    @SuppressWarnings( "unused" )
    @FXML
    HBox heroChipRow;

    @SuppressWarnings( "unused" )
    @FXML
    Label heroChipMc;

    @SuppressWarnings( "unused" )
    @FXML
    Label heroChipForge;

    @SuppressWarnings( "unused" )
    @FXML
    Label heroChipVersion;

    @SuppressWarnings( "unused" )
    @FXML
    Label heroChipBeta;

    @SuppressWarnings( "unused" )
    @FXML
    Region heroBackgroundLayer;

    @SuppressWarnings( "unused" )
    @FXML
    Label versionLabel;

    @SuppressWarnings( "unused" )
    @FXML
    Label offlineLabel;

    private TaskbarProgressbar taskbarProgressbar = null;

    /**
     * Constructor for abstract scene class that initializes {@link #scene} and sets <code>this</code> as the FXML
     * controller.
     *
     * @throws IOException if unable to load FXML file specified
     */
    public MCLauncherMainGui( Stage stage ) throws IOException {
        super( stage );
    }

    /**
     * Constructor for abstract scene class that initializes {@link #scene} and sets <code>this</code> as the FXML
     * controller.
     *
     * @throws IOException if unable to load FXML file specified
     */
    @SuppressWarnings( "unused" )
    public MCLauncherMainGui( Stage stage, double width, double height ) throws IOException {
        super( stage, width, height );
    }

    /**
     * Abstract method: This method must return the resource path for the JavaFX scene FXML file.
     *
     * @return JavaFX scene FXML resource path
     */
    @Override
    String getSceneFxmlPath() {
        return "gui/mainGUI.fxml";
    }

    /**
     * Abstract method: This method must return the name of the JavaFX scene.
     *
     * @return Java FX scene name
     */
    @Override
    String getSceneName() {
        return "Home";
    }

    /**
     * Abstract method: This method must perform initialization and setup of the scene and @FXML components.
     */
    @Override
    void setup() {
        // Configure window close
        stage.setOnCloseRequest( windowEvent -> {
            windowEvent.consume();
            exitBtn.fire();
        } );

        // Start Discord rich presence
        SystemUtilities.spawnNewTask( () -> DiscordRpcUtility.setMenuPresence( "Selecting a Mod Pack" ) );

        // Configure exit button
        exitBtn.setOnAction( event -> LauncherCore.closeApp() );

        // Set unstable mod pack warning hidden by default
        setUnstableWarning( false );

        // Check for launcher update and show image if there is one
        TaskbarProgressbar[] taskbarRef = new TaskbarProgressbar[ 1 ];
        UpdateCheckManager.checkAndConfigureUI( updateImgView, stage, taskbarRef );
        SystemUtilities.spawnNewTask( () -> {
            // Capture taskbar reference once the async check completes
            if ( taskbarRef[ 0 ] != null ) {
                taskbarProgressbar = taskbarRef[ 0 ];
            }
        } );

        // Display announcements if present
        if ( LauncherConstants.LAUNCHER_IS_DEV ) {
            setAnnouncementRow(
                    "[DEVELOPMENT MODE: Bugs may be present and not all features may function as intended]" );
        }
        else {
            setAnnouncementRow( null );
        }

        // Configure settings button
        settingsBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToSettingsGui();
                SystemUtilities.spawnNewTask( () -> DiscordRpcUtility.setMenuPresence( "Settings" ) );
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to load settings GUI due to an incomplete response from the GUI subsystem." );
                Logger.logThrowable( e );
            }
        } ) );

        // Configure modpacks edit button
        editButton.setDisable( AnnouncementManager.getDisableModpacksEdit() );
        editButton.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToEditModpacksGui();
                SystemUtilities.spawnNewTask( () -> DiscordRpcUtility.setMenuPresence( "Editing Mod Packs" ) );
            }
            catch ( IOException e ) {
                Logger.logError(
                        "Unable to load edit mod-packs GUI due to an incomplete response from the GUI subsystem." );
                Logger.logThrowable( e );
            }
        } ) );

        // Configure vanilla versions button
        vanillaBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToVanillaVersionsGui();
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to open vanilla versions GUI." );
                Logger.logThrowable( e );
            }
        } ) );

        // Configure play button
        playBtn.setDisable( AnnouncementManager.getDisableGameplay() );
        playBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            Platform.setImplicitExit( false );
            GameModPack installedModPackByFriendlyName = packSelectionList.getSelectionModel()
                                                                          .getSelectedItem();
            SystemUtilities.spawnNewTask( () -> DiscordRpcUtility.setGamePresence(
                    installedModPackByFriendlyName.getPackName(),
                    installedModPackByFriendlyName.getCustomDiscordRpc() ) );
            LauncherCore.play( installedModPackByFriendlyName, () -> GUIUtilities.JFXPlatformRun( () -> {
                try {
                    Objects.requireNonNull( MCLauncherGuiController.getTopStageOrNull() ).show();
                    MCLauncherGuiController.goToMainGui();
                    MCLauncherGuiController.requestFocus();
                }
                catch ( Exception e ) {
                    Logger.logError( "Unable to load main GUI due to an incomplete response from the GUI subsystem." );
                    Logger.logThrowable( e );
                    LauncherCore.closeApp();
                }
            } ) );
        } ) );

        // Configure website button
        websiteBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            GameModPack installedModPackByFriendlyName = packSelectionList.getSelectionModel()
                                                                          .getSelectedItem();
            try {
                Desktop.getDesktop().browse( URI.create( installedModPackByFriendlyName.getPackURL() ) );
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to open your browser. Please visit " +
                                         installedModPackByFriendlyName.getPackURL() +
                                         " to view the mod pack's website!" );
                Logger.logThrowable( e );
            }
        } ) );

        // Configure user label (compact — just username for nav bar)
        playerLabel.setText( MCLauncherAuthManager.getLoggedInUser().name() );

        // Configure version label in bottom bar
        versionLabel.setText( "Mica Launcher v" + LauncherConstants.LAUNCHER_APPLICATION_VERSION );

        // Show offline mode indicator if applicable
        if ( com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities.isOffline() ) {
            offlineLabel.setVisible( true );
            offlineLabel.setManaged( true );
        }

        // Configure user image
        userImage.setImage( new Image(
                GUIConstants.URL_MINECRAFT_USER_ICONS.replace( GUIConstants.URL_MINECRAFT_USER_ICONS_USER_REPLACE_KEY,
                                                               MCLauncherAuthManager.getLoggedInUser().uuid() ) ) );

        // Configure keyboard shortcuts
        scene.setOnKeyPressed( keyEvent -> {
            if ( keyEvent.getCode() == KeyCode.ENTER ) {
                keyEvent.consume();
                if ( !playBtn.isDisabled() ) {
                    playBtn.fire();
                }
            }
            else if ( keyEvent.getCode() == KeyCode.F5 ) {
                keyEvent.consume();
                SystemUtilities.spawnNewTask( () -> {
                    AnnouncementManager.checkAnnouncements();
                    GameModPackManager.fetchModPackInfo();
                    try {
                        MCLauncherGuiController.goToMainGui();
                    }
                    catch ( Exception e ) {
                        Logger.logError( "Oops! Unable to refresh." );
                        Logger.logThrowable( e );
                    }
                } );
            }
        } );

        // Configure mod pack list
        packSelectionList.setCellFactory( listView -> new ModPackCellFactory() );
        packSelectionList.setFixedCellSize( 52 );

        // Populate list of modpacks
        populateModpackDropdown();
    }

    @Override
    void afterShow() {
        // Get last mod pack selected from config
        String lastModPackSelected = ConfigManager.getLastModPackSelected();

        // Check if it exists/is installed
        GameModPack lastGameModPack = GameModPackManager.getInstalledModPackByName( lastModPackSelected );

        // Select mod pack
        if ( lastGameModPack != null ) {
            selectModpack( lastGameModPack.getFriendlyName() );
        }
        else {
            selectModpack( packSelectionList.getItems().get( 0 ) );
        }

        // Install tooltips
        TooltipManager.install( playBtn, "Launch the selected modpack." );
        TooltipManager.install( settingsBtn, "Open launcher settings (RAM, theme, JVM flags, proxy)." );
        TooltipManager.install( vanillaBtn, "Browse and play vanilla (unmodded) Minecraft versions." );
        TooltipManager.install( websiteBtn, "Open the selected modpack's website in your browser." );
        TooltipManager.install( packSelectionList, "Right-click a modpack for more options." );
    }

    @Override
    void cleanup() {
        if ( packSelectionList != null ) {
            packSelectionList.getSelectionModel().selectedItemProperty().removeListener( packSelectionChangeListener );
        }
        if ( taskbarProgressbar != null ) {
            GUIUtilities.JFXPlatformRun( () -> {
                taskbarProgressbar.stopProgress();
                taskbarProgressbar.closeOperations();
            } );
        }
    }

    @Override
    HelpTopic getHelpTopic() { return HelpTopic.MAIN_SCREEN; }

    /**
     * Custom change listener for handling a change in the selection of the mod pack list. Updates the mod pack logo and
     * information for the newly selected mod pack.
     *
     * @since 1.0
     */
    private final ChangeListener< GameModPack > packSelectionChangeListener
            = ( observable, oldValue, newValue ) -> {
        // Get selected mod pack
        GameModPack selectedGameModPack = newValue;
        if ( selectedGameModPack != null ) {
            ConfigManager.setLastModPackSelected( selectedGameModPack.getPackName() );
        }

        // Populate the hero detail card and unstable warning
        boolean unstableWarningVisible = selectedGameModPack != null && selectedGameModPack.getPackUnstable();
        setUnstableWarning( unstableWarningVisible );
        populateHeroFromModpack( selectedGameModPack );

        // Apply the modpack's image to the hero card's background layer (clipped, with veil overlay).
        applyHeroBackgroundImage( selectedGameModPack );
    };

    /**
     * Updates the hero card's background image layer to show the selected modpack's pack-background image. The image
     * sits behind a dark veil so the foreground hero text stays readable. Falls back to the launcher default
     * background if the modpack has no image or it can't be loaded.
     */
    private void applyHeroBackgroundImage( GameModPack pack )
    {
        if ( heroBackgroundLayer == null ) return;
        String url = null;
        try {
            if ( pack != null && pack.getPackBackgroundFilepath() != null ) {
                File f = new File( pack.getPackBackgroundFilepath() );
                if ( f.exists() ) {
                    url = f.toURI().toString();
                }
            }
        }
        catch ( Exception ignored ) { /* fall through to default */ }
        if ( url == null ) {
            url = ModPackConstants.MODPACK_DEFAULT_BG_URL;
        }
        // Reset and set inline background-image (size/position/repeat live in ui-base.css).
        heroBackgroundLayer.setStyle( "-fx-background-image: url('" + url + "');" );
    }

    /**
     * Updates the hero card fields (logo, name, stat chips) from the given modpack. A null modpack clears the card.
     */
    private void populateHeroFromModpack( GameModPack modPack ) {
        GUIUtilities.JFXPlatformRun( () -> {
            if ( modPack == null ) {
                heroPackName.setText( "" );
                showChip( heroChipMc, null );
                showChip( heroChipForge, null );
                showChip( heroChipVersion, null );
                showChip( heroChipBeta, null );
                heroPackLogo.setImage( null );
                return;
            }

            // Name
            String displayName = modPack.getFriendlyName();
            if ( displayName == null || displayName.isBlank() ) {
                displayName = modPack.getPackName();
            }
            heroPackName.setText( displayName != null ? displayName : "" );

            // Stat chips: MC version, Forge version, pack version, beta flag
            String mcVer = null;
            String forgeVer = null;
            try {
                mcVer = modPack.getMinecraftVersion();
            }
            catch ( Exception ignored ) { /* manifest not yet resolved — chip stays hidden */ }
            try {
                forgeVer = modPack.getForgeVersion();
            }
            catch ( Exception ignored ) { /* vanilla packs and unresolved packs return nothing */ }

            showChip( heroChipMc,      mcVer    != null && !mcVer.isBlank()    ? "Minecraft " + mcVer  : null );
            showChip( heroChipForge,   forgeVer != null && !forgeVer.isBlank() ? "Forge " + forgeVer    : null );
            showChip( heroChipVersion, modPack.getPackVersion() != null && !modPack.getPackVersion().isBlank()
                                          ? "v" + modPack.getPackVersion() : null );
            showChip( heroChipBeta,    modPack.getPackUnstable() ? "Beta" : null );

            // Pack logo (file URL for installed packs)
            try {
                String logoPath = modPack.getPackLogoFilepath();
                if ( logoPath != null && !logoPath.isBlank() ) {
                    File logoFile = new File( logoPath );
                    if ( logoFile.exists() ) {
                        heroPackLogo.setImage( new Image( logoFile.toURI().toString(), true ) );
                    }
                    else {
                        heroPackLogo.setImage( null );
                    }
                }
                else {
                    heroPackLogo.setImage( null );
                }
            }
            catch ( Exception e ) {
                heroPackLogo.setImage( null );
            }
        } );
    }

    /** Toggles a stat chip's visibility/managed state and sets its text in one call. Pass null/blank to hide. */
    private static void showChip( Label chip, String text ) {
        boolean show = text != null && !text.isBlank();
        if ( chip == null ) return;
        chip.setText( show ? text : "" );
        chip.setVisible( show );
        chip.setManaged( show );
    }

    public void setAnnouncementRow( String extra ) {
        String announcementText;
        if ( extra != null ) {
            announcementText = extra + "\n" + AnnouncementManager.getAnnouncementHome();
        }
        else {
            announcementText = AnnouncementManager.getAnnouncementHome();
        }
        if ( announcementText.length() > 0 ) {
            announcement.setText( announcementText );
            announcement.setMinHeight( 30 );
            announcementRow.setMinHeight( 30 );
        }
        else {
            announcement.setMaxHeight( 0 );
            announcementRow.setMaxHeight( 0 );
        }
    }

    public void setUnstableWarning( boolean unstable ) {
        // The unstable banner is now a flow item inside the hero VBox; just toggle managed/visible.
        unstableWarning.setVisible( unstable );
        unstableWarning.setManaged( unstable );
    }

    public void selectModpack( String modPack ) {
        // Select supplied mod pack
        for ( GameModPack mp : packSelectionList.getItems() ) {
            if ( mp.getFriendlyName().equals( modPack ) ) {
                packSelectionList.getSelectionModel().select( mp );
                return;
            }
        }
    }

    public void selectModpack( GameModPack modPack ) {
        GUIUtilities.JFXPlatformRun( () -> selectModpack( modPack.getFriendlyName() ) );
    }

    /**
     * Populates the contents of the mod pack dropdown list, or disables the pack selection list and displays a message
     * if no mod packs are installed.
     *
     * @since 1.0
     */
    private void populateModpackDropdown() {
        // Build combined list: Forge modpacks + vanilla versions
        List< GameModPack > allPacks = new ArrayList<>();
        allPacks.addAll( GameModPackManager.getInstalledModPacks() );

        // Add installed vanilla versions
        for ( String versionId : com.micatechnologies.minecraft.launcher.game.modpack.VanillaVersionManager.getInstalledVersionIds() ) {
            allPacks.add( GameModPack.createVanillaModPack( versionId ) );
        }

        // Reset mod pack selector
        packSelectionList.setDisable( false );
        packSelectionList.getSelectionModel().selectedItemProperty().removeListener( packSelectionChangeListener );
        packSelectionList.getItems().clear();

        // Populate mod packs dropdown
        if ( !allPacks.isEmpty() ) {
            packSelectionList.setItems( FXCollections.observableArrayList( allPacks ) );
            packSelectionList.getSelectionModel().selectedItemProperty().addListener( packSelectionChangeListener );
        }
        else {
            packSelectionList.setItems( FXCollections.singletonObservableList( GameModPack.NULL_MODPACK() ) );
            packSelectionList.setDisable( true );
            // Show the launcher's default background in the hero card when no packs are installed.
            applyHeroBackgroundImage( null );
        }
        // Let the GridPane layout handle list height naturally via vgrow.
        // Setting a fixed prefHeight conflicts with the virtual scroll and causes scroll jumping.
    }

    private static class ModPackCellFactory extends ListCell< GameModPack >
    {
        private final GridPane gridPane;
        private final ImageView packLogo;
        private final Label packNameLabel;
        private final Label packVersionLabel;
        private final Label updateBadge;

        public ModPackCellFactory()
        {
            setPrefHeight( 52 );
            getStyleClass().add( "pack-list-cell" );

            packLogo = new ImageView();
            packLogo.setFitHeight( 40 );
            packLogo.setPreserveRatio( true );
            packLogo.getStyleClass().add( "packLogo" );
            packNameLabel = new Label();
            packNameLabel.setAlignment( Pos.CENTER_LEFT );
            packNameLabel.getStyleClass().add( "packName" );
            packVersionLabel = new Label();
            packVersionLabel.setAlignment( Pos.CENTER_LEFT );
            packVersionLabel.getStyleClass().add( "packVersion" );
            updateBadge = new Label( "UPDATE" );
            updateBadge.getStyleClass().addAll( "stat-chip", "stat-chip-success", "updateBadge" );
            updateBadge.setVisible( false );
            updateBadge.setManaged( false );

            gridPane = new GridPane();
            gridPane.getStyleClass().add( "packPane" );
            gridPane.add( packLogo, 0, 0, 1, 2 );
            gridPane.add( packNameLabel, 1, 0 );
            javafx.scene.layout.HBox versionBox = new javafx.scene.layout.HBox( 6, packVersionLabel, updateBadge );
            versionBox.setAlignment( Pos.CENTER_LEFT );
            gridPane.add( versionBox, 1, 1 );
        }

        @Override
        protected void updateItem( GameModPack item, boolean empty ) {
            super.updateItem( item, empty );
            if ( empty || item == null ) {
                setText( null );
                setGraphic( null );
                setContextMenu( null );
            }
            else {
                String logoPath;
                if ( item.getPackLogoFilepath() != null && new File( item.getPackLogoFilepath() ).exists() ) {
                    logoPath = new File( item.getPackLogoFilepath() ).toURI().toString();
                }
                else {
                    logoPath = ModPackConstants.MODPACK_DEFAULT_LOGO_URL;
                }
                packLogo.setImage( new Image( logoPath ) );
                packNameLabel.setText( item.getPackName() );
                String versionText = "Version: " + item.getPackVersion();
                String lastPlayed = item.getLastPlayedFormatted();
                if ( !"Never played".equals( lastPlayed ) ) {
                    versionText += "  |  " + lastPlayed;
                }
                packVersionLabel.setText( versionText );

                // Show update badge if remote version is newer than installed
                boolean hasUpdate = item.isUpdateAvailable();
                updateBadge.setVisible( hasUpdate );
                updateBadge.setManaged( hasUpdate );

                setText( null );
                setGraphic( gridPane );
                setContextMenu( buildContextMenu( item ) );
            }
        }

        private ContextMenu buildContextMenu( GameModPack pack ) {
            ContextMenu menu = new ContextMenu();

            MenuItem openFolder = new MenuItem( "Open Install Folder" );
            openFolder.setOnAction( e -> openPackSubfolder( pack, "" ) );

            MenuItem openScreenshots = new MenuItem( "Open Screenshots" );
            openScreenshots.setOnAction( e -> openPackSubfolder( pack, "screenshots" ) );

            MenuItem openResourcePacks = new MenuItem( "Open Resource Packs" );
            openResourcePacks.setOnAction( e -> openPackSubfolder( pack, "resourcepacks" ) );

            MenuItem openShaderPacks = new MenuItem( "Open Shader Packs" );
            openShaderPacks.setOnAction( e -> openPackSubfolder( pack, "shaderpacks" ) );

            MenuItem openMods = new MenuItem( "Open Mods Folder" );
            openMods.setOnAction( e -> openPackSubfolder( pack, "mods" ) );

            MenuItem openConfig = new MenuItem( "Open Config Folder" );
            openConfig.setOnAction( e -> openPackSubfolder( pack, "config" ) );

            MenuItem createShortcut = new MenuItem( "Create Desktop Shortcut" );
            createShortcut.setOnAction( e -> createDesktopShortcut( pack ) );

            // Play stats header (non-interactive)
            MenuItem playStats = new MenuItem( "Played " + pack.getTotalPlayTimeFormatted() +
                                                       " (" + pack.getLaunchCount() + " launches)" );
            playStats.setDisable( true );

            menu.getItems().addAll( playStats, new SeparatorMenuItem(),
                                    openFolder, new SeparatorMenuItem(),
                                    openScreenshots, openResourcePacks, openShaderPacks,
                                    new SeparatorMenuItem(), openMods, openConfig,
                                    new SeparatorMenuItem(), createShortcut );
            return menu;
        }

        private void createDesktopShortcut( GameModPack pack ) {
            SystemUtilities.spawnNewTask( () -> {
                try {
                    DesktopShortcutManager.createShortcut( pack );
                    javafx.stage.Stage ownerStage = MCLauncherGuiController.getTopStageOrNull();
                    if ( ownerStage != null ) {
                        GUIUtilities.JFXPlatformRun( () -> {
                            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                                    javafx.scene.control.Alert.AlertType.INFORMATION );
                            alert.setTitle( "Shortcut Created" );
                            alert.setHeaderText( null );
                            alert.setContentText(
                                    "Desktop shortcut created for " + pack.getPackName() + "." );
                            alert.initOwner( ownerStage );
                            alert.initStyle( javafx.stage.StageStyle.UTILITY );
                            alert.showAndWait();
                        } );
                    }
                }
                catch ( Exception ex ) {
                    Logger.logError( "Failed to create desktop shortcut: " + ex.getMessage() );
                    Logger.logThrowable( ex );
                    javafx.stage.Stage ownerStage = MCLauncherGuiController.getTopStageOrNull();
                    if ( ownerStage != null ) {
                        GUIUtilities.showErrorMessage(
                                "Unable to create desktop shortcut: " + ex.getMessage(), ownerStage );
                    }
                }
            } );
        }

        private void openPackSubfolder( GameModPack pack, String subfolder ) {
            SystemUtilities.spawnNewTask( () -> {
                try {
                    String path = pack.getPackRootFolder();
                    if ( !subfolder.isEmpty() ) {
                        path += File.separator + subfolder;
                    }
                    File folder = new File( path );
                    if ( !folder.exists() ) {
                        folder.mkdirs();
                    }
                    Desktop.getDesktop().open( folder );
                }
                catch ( Exception ex ) {
                    Logger.logWarningSilent( "Unable to open folder: " + ex.getMessage() );
                }
            } );
        }
    }
}
