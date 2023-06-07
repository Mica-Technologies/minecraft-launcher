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
import com.nativejavafx.taskbar.TaskbarProgressbar;
import com.nativejavafx.taskbar.TaskbarProgressbarFactory;
import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXListView;
import io.github.palexdev.materialfx.controls.cell.MFXListCell;
import io.github.palexdev.materialfx.utils.others.FunctionalStringConverter;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.RowConstraints;
import javafx.stage.Stage;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.time.OffsetDateTime;
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
    MFXListView< GameModPack > packSelectionList;

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
     * Logout button. Logs out the currently logged in user.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton logoutBtn;

    /**
     * Edit mod packs button. Opens the mod pack installation window.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton editButton;

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
     * Unstable mod pack warning banner row constraints.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    RowConstraints unstableWarningRow;

    /**
     * Center content/button pane.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    GridPane centerPane;

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
        SystemUtilities.spawnNewTask(
                () -> DiscordRpcUtility.setRichPresence( "In Menus", "Selecting a Mod Pack", OffsetDateTime.now(),
                                                         "mica_minecraft_launcher", "Mica Minecraft Launcher",
                                                         "clipboard", "Selecting a Mod Pack" ) );

        // Configure exit button
        exitBtn.setOnAction( event -> LauncherCore.closeApp() );

        // Set unstable mod pack warning hidden by default
        setUnstableWarning( false );

        // Check for launcher update and show image if there is one
        updateImgView.setVisible( false );
        SystemUtilities.spawnNewTask( () -> {
            try {
                // Get current version
                String version = LauncherConstants.LAUNCHER_APPLICATION_VERSION;

                // Get latest version
                String latestVersionURL = UpdateCheckUtilities.getLatestReleaseURL();
                String latestVersion = UpdateCheckUtilities.getLatestReleaseVersion();

                // Check if current version is less than latest
                if ( SystemUtilities.compareVersionNumbers( version, latestVersion ) == -1 ) {
                    // Setup taskbar progress bar
                    if ( TaskbarProgressbar.isSupported() ) {
                        taskbarProgressbar = TaskbarProgressbarFactory.getTaskbarProgressbar( stage );
                    }

                    GUIUtilities.JFXPlatformRun( () -> {
                        updateImgView.setVisible( true );
                        updateImgView.setOnMouseClicked( mouseEvent -> SystemUtilities.spawnNewTask( () -> {
                            int response = GUIUtilities.showQuestionMessage( "Update Available",
                                                                             "Update Ready to " + "Download",
                                                                             "An update has been found and is ready to be downloaded and installed.",
                                                                             "Update Now", "Update Later", stage );
                            if ( response == 1 ) {
                                try {
                                    Desktop.getDesktop().browse( URI.create( latestVersionURL ) );
                                }
                                catch ( IOException e ) {
                                    Logger.logError( "Unable to open your browser. Please visit " +
                                                             latestVersionURL +
                                                             " to download the latest launcher updates!" );
                                    Logger.logThrowable( e );
                                }
                            }
                        } ) );

                        if ( taskbarProgressbar != null ) {
                            taskbarProgressbar.showFullErrorProgress();
                        }
                    } );
                }
            }
            catch ( Exception e ) {
                Logger.logError( "An error occurred while checking for an updated launcher version!" );
                Logger.logThrowable( e );
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
                SystemUtilities.spawnNewTask(
                        () -> DiscordRpcUtility.setRichPresence( "In Menus", "Settings", OffsetDateTime.now(),
                                                                 "mica_minecraft_launcher", "Mica Minecraft Launcher",
                                                                 "settings", "Settings" ) );
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
                SystemUtilities.spawnNewTask(
                        () -> DiscordRpcUtility.setRichPresence( "In Menus", "Editing Mod Packs", OffsetDateTime.now(),
                                                                 "mica_minecraft_launcher", "Mica Minecraft Launcher",
                                                                 "download", "Editing Mod Packs" ) );
            }
            catch ( IOException e ) {
                Logger.logError(
                        "Unable to load edit mod-packs GUI due to an incomplete response from the GUI subsystem." );
                Logger.logThrowable( e );
            }
        } ) );

        // Configure logout button
        logoutBtn.setOnAction( actionEvent -> {
            MCLauncherAuthManager.logout();
            LauncherCore.restartApp();
        } );

        // Configure play button
        playBtn.setDisable( AnnouncementManager.getDisableGameplay() );
        playBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            Platform.setImplicitExit( false );
            GameModPack installedModPackByFriendlyName = packSelectionList.getSelectionModel()
                                                                          .getSelectedValues()
                                                                          .get( 0 );
            if ( installedModPackByFriendlyName.getCustomDiscordRpc() ) {
                SystemUtilities.spawnNewTask( DiscordRpcUtility::exit );
            }
            else {
                SystemUtilities.spawnNewTask( () -> DiscordRpcUtility.setRichPresence( "In Game (Minecraft)",
                                                                                       "Mod Pack: " +
                                                                                               installedModPackByFriendlyName.getPackName(),
                                                                                       OffsetDateTime.now(),
                                                                                       "mica_minecraft_launcher",
                                                                                       "Mica Minecraft Launcher",
                                                                                       "game", "In Game" ) );
            }
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
                                                                          .getSelectedValues()
                                                                          .get( 0 );
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

        // Configure user label
        playerLabel.setText(
                TimeUtilities.getFriendlyTimeBasedGreeting() + ",\n" + MCLauncherAuthManager.getLoggedInUser().name() );

        // Configure user image
        userImage.setImage( new Image(
                GUIConstants.URL_MINECRAFT_USER_ICONS.replace( GUIConstants.URL_MINECRAFT_USER_ICONS_USER_REPLACE_KEY,
                                                               MCLauncherAuthManager.getLoggedInUser().uuid() ) ) );

        // Configure ENTER key to press play button
        scene.setOnKeyPressed( keyEvent -> {
            if ( keyEvent.getCode() == KeyCode.ENTER ) {
                keyEvent.consume();
                if ( !playBtn.isDisabled() ) {
                    playBtn.fire();
                }
            }
        } );

        // Configure F5 key to refresh
        scene.setOnKeyPressed( keyEvent -> {
            if ( keyEvent.getCode() == KeyCode.F5 ) {
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
        packSelectionList.setCellFactory( modpack -> new ModPackCellFactory( packSelectionList, modpack ) );
        packSelectionList.setConverter( FunctionalStringConverter.to( gameModPack -> "" ) );

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
    }

    @Override
    void cleanup() {
        if ( taskbarProgressbar != null ) {
            GUIUtilities.JFXPlatformRun( () -> {
                taskbarProgressbar.stopProgress();
                taskbarProgressbar.closeOperations();
            } );
        }
    }

    /**
     * Custom change listener for handling a change in the selection of the mod pack list. Updates the mod pack logo and
     * information for the newly selected mod pack.
     *
     * @since 1.0
     */
    private final ChangeListener< ObservableMap< Integer, GameModPack > > packSelectionChangeListener
            = ( ObservableValue< ? extends ObservableMap< Integer, GameModPack > > observable, ObservableMap< Integer, GameModPack > oldValue, ObservableMap< Integer, GameModPack > newValue ) -> {
        // Get selected mod pack
        GameModPack selectedGameModPack = packSelectionList.getSelectionModel().getSelectedValues().get( 0 );
        if ( selectedGameModPack != null ) {
            ConfigManager.setLastModPackSelected( selectedGameModPack.getPackName() );
        }

        // Set unstable warning
        boolean unstableWarningVisible = false;
        if ( selectedGameModPack != null ) {
            unstableWarningVisible = selectedGameModPack.getPackUnstable();
        }
        setUnstableWarning( unstableWarningVisible );

        // Set modpack background image on root pane
        if ( selectedGameModPack != null ) {
            // noinspection All
            rootPane.setStyle( rootPane.getStyle() +
                                       "-fx-background-image: url('" +
                                       new File( selectedGameModPack.getPackBackgroundFilepath() ).toURI() +
                                       "');" );
        }
        else {
            rootPane.setStyle( rootPane.getStyle() +
                                       "-fx-background-image: url('" +
                                       ModPackConstants.MODPACK_DEFAULT_BG_URL +
                                       "');" );
        }
        rootPane.setStyle( rootPane.getStyle() + "-fx-background-size: cover; -fx-background-repeat: no-repeat;" );
    };

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
        unstableWarning.setVisible( unstable );
        unstableWarningRow.setMaxHeight( unstable ? 37 : 0 );
        unstableWarningRow.setMaxHeight( unstable ? 37 : 0 );
        unstableWarningRow.setMinHeight( unstable ? 37 : 0 );
        centerPane.setPrefHeight( unstable ? 100 : 60 );
        centerPane.setMaxHeight( unstable ? 100 : 60 );
        centerPane.setMinHeight( unstable ? 100 : 60 );
    }

    public void selectModpack( String modPack ) {
        // Select supplied mod pack
        packSelectionList.getSelectionModel()
                         .selectItem( packSelectionList.getItems()
                                                       .filtered( mp -> mp.getFriendlyName().equals( modPack ) )
                                                       .get( 0 ) );
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
        // Get list of mod pack names
        List< String > modpackListNames = GameModPackManager.getInstalledModPackFriendlyNames();

        // Reset mod pack selector
        packSelectionList.setDisable( false );
        packSelectionList.getSelectionModel().setAllowsMultipleSelection( false );
        packSelectionList.getSelectionModel().selectionProperty().removeListener( packSelectionChangeListener );
        packSelectionList.getItems().clear();

        // Populate mod packs dropdown
        if ( modpackListNames.size() > 0 ) {
            packSelectionList.setItems( FXCollections.observableList( GameModPackManager.getInstalledModPacks() ) );
            packSelectionList.getSelectionModel().selectionProperty().addListener( packSelectionChangeListener );
        }
        else {
            packSelectionList.setItems( FXCollections.singletonObservableList( GameModPack.NULL_MODPACK() ) );
            packSelectionList.setDisable( true );
            rootPane.setStyle( rootPane.getStyle() +
                                       "-fx-background-image: url('" +
                                       ModPackConstants.MODPACK_DEFAULT_BG_URL +
                                       "');" );
            rootPane.setStyle( rootPane.getStyle() + "-fx-background-size: cover; -fx-background-repeat: no-repeat;" );
        }
        packSelectionList.setPrefHeight( packSelectionList.getItems().size() * 52 );
    }

    private static class ModPackCellFactory extends MFXListCell< GameModPack >
    {
        private final GridPane gridPane;

        public ModPackCellFactory( MFXListView< GameModPack > listView, GameModPack cell )
        {
            super( listView, cell );
            setPrefHeight( 52 );

            String packLogoFilepath = cell.getPackLogoFilepath() != null ?
                                      new File( cell.getPackLogoFilepath() ).toURI().toString() :
                                      ModPackConstants.MODPACK_DEFAULT_LOGO_URL;
            ImageView packLogo = new ImageView( packLogoFilepath );
            packLogo.setFitHeight( 40 );
            packLogo.setPreserveRatio( true );
            packLogo.getStyleClass().add( "packLogo" );
            Label packName = new Label( cell.getPackName() );
            packName.setAlignment( Pos.CENTER_LEFT );
            packName.getStyleClass().add( "packName" );
            Label packVersion = new Label( "Version: " + cell.getPackVersion() );
            packVersion.setAlignment( Pos.CENTER_LEFT );
            packVersion.getStyleClass().add( "packVersion" );

            // Add components to grid pane 2x2 (logo takes entire left column)
            gridPane = new GridPane();
            gridPane.getStyleClass().add( "packPane" );
            //gridPane.setAlignment( Pos.CENTER );
            gridPane.add( packLogo, 0, 0, 1, 2 );
            gridPane.add( packName, 1, 0 );
            gridPane.add( packVersion, 1, 1 );

            render( cell );
        }

        @Override
        protected void render( GameModPack cell ) {
            super.render( cell );
            if ( gridPane != null ) {
                getChildren().add( 0, gridPane );
            }
        }
    }
}
