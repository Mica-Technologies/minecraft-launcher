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

import com.jagrosh.discordipc.IPCClient;
import com.jagrosh.discordipc.IPCListener;
import com.jagrosh.discordipc.entities.RichPresence;
import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXComboBox;
import com.micatechnologies.minecraft.launcher.LauncherCore;
import com.micatechnologies.minecraft.launcher.consts.GUIConstants;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.auth.AuthManager;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.utilities.GUIUtilities;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import com.micatechnologies.minecraft.launcher.utilities.UpdateCheckUtilities;
import com.micatechnologies.minecraft.launcher.utilities.annotations.OnScreen;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;

public class MCLauncherMainGui extends MCLauncherAbstractGui
{
    /**
     * Mod pack logo image.
     *
     * @since 2.0
     */
    @FXML
    @OnScreen
    ImageView packLogo;

    /**
     * Installed mod pack selection list.
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    JFXComboBox< String > packSelection;

    /**
     * Play button. Starts the current selected mod pack.
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    JFXButton playBtn;

    /**
     * Exit button. Closes the application.
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    JFXButton exitBtn;

    /**
     * Settings button. Opens the settings window.
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    JFXButton settingsBtn;

    /**
     * Logout button. Logs out the currently logged in user.
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    JFXButton logoutBtn;

    /**
     * Edit mod packs button. Opens the mod pack installation window.
     *
     * @since 3.0
     */
    @FXML
    @OnScreen
    JFXButton editButton;

    /**
     * Current user avatar image. Displays the avatar of the currently logged in user.
     *
     * @since 2.0
     */
    @FXML
    @OnScreen
    ImageView userImage;

    /**
     * Update available image. Displays a warning icon if there is a launcher update available.
     *
     * @since 2.0
     */
    @FXML
    @OnScreen
    ImageView updateImgView;

    /**
     * Player name label. Displays the user name of the currently logged in user.
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    Label playerLabel;

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

    IPCClient discordRpcClient = null;

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

        // Set up Discord rich presence
        try {
            discordRpcClient = new IPCClient( 841860482029846528L );
            discordRpcClient.setListener( new IPCListener()
            {
                @Override
                public void onReady( IPCClient client )
                {
                    RichPresence.Builder builder = new RichPresence.Builder();
                    builder.setState( "In Menus" )
                           .setDetails( "At Mod Pack Selection" )
                           .setStartTimestamp( OffsetDateTime.now() )
                           .setLargeImage( "mica_minecraft_launcher", "Mica Minecraft Launcher" )
                           .setSmallImage( "mica_minecraft_launcher", "Mica Minecraft Launcher" );
                    client.sendRichPresence( builder.build() );
                }
            } );
            discordRpcClient.connect();
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Unable to setup Discord rich presence!" );
            Logger.logThrowable( e );
        }

        // Configure exit button
        exitBtn.setOnAction( event -> LauncherCore.closeApp() );

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
                }
            }
            catch ( Exception e ) {
                Logger.logError( "An error occurred while checking for an updated launcher version!" );
                Logger.logThrowable( e );
            }
        } );

        // Configure settings button
        settingsBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToSettingsGui();
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to load settings GUI due to an incomplete response from the GUI subsystem." );
                Logger.logThrowable( e );
            }
        } ) );

        // Configure modpacks edit button
        editButton.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToEditModpacksGui();
            }
            catch ( IOException e ) {
                Logger.logError(
                        "Unable to load edit mod-packs GUI due to an incomplete response from the GUI subsystem." );
                Logger.logThrowable( e );
            }
        } ) );

        // Configure logout button
        logoutBtn.setOnAction( actionEvent -> {
            AuthManager.logout();
            LauncherCore.restartApp();
        } );

        // Populate list of modpacks
        populateModpackDropdown();

        // Configure play button
        playBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            Platform.setImplicitExit( false );
            GameModPack installedModPackByFriendlyName = GameModPackManager.getInstalledModPackByFriendlyName(
                    packSelection.getSelectionModel().getSelectedItem() );
            if ( discordRpcClient != null ) {
                try {
                    RichPresence.Builder builder = new RichPresence.Builder();
                    builder.setState( "In Game (Minecraft)" )
                           .setDetails( "Mod Pack: " + installedModPackByFriendlyName.getPackName() )
                           .setStartTimestamp( OffsetDateTime.now() )
                           .setLargeImage( "mica_minecraft_launcher", "Mica Minecraft Launcher" )
                           .setSmallImage( "mica_minecraft_launcher", "Mica Minecraft Launcher" );
                    discordRpcClient.sendRichPresence( builder.build() );
                }
                catch ( Exception e ) {
                    Logger.logWarningSilent( "Unable to update Discord rich presence!" );
                    Logger.logThrowable( e );
                }
            }
            LauncherCore.play( installedModPackByFriendlyName, () -> {
                try {
                    MCLauncherGuiController.goToMainGui();
                    MCLauncherGuiController.requestFocus();
                }
                catch ( IOException e ) {
                    Logger.logError( "Unable to load main GUI due to an incomplete response from the GUI subsystem." );
                    Logger.logThrowable( e );
                    LauncherCore.closeApp();
                }
            } );
        } ) );

        // Configure user label
        playerLabel.setText( AuthManager.getLoggedInAccount().getFriendlyName() );

        // Configure user image
        userImage.setImage( new Image(
                GUIConstants.URL_MINECRAFT_USER_ICONS.replace( GUIConstants.URL_MINECRAFT_USER_ICONS_USER_REPLACE_KEY,
                                                               AuthManager.getLoggedInAccount()
                                                                          .getUserIdentifier() ) ) );

        // Configure ENTER key to press play button
        scene.setOnKeyPressed( keyEvent -> {
            if ( keyEvent.getCode() == KeyCode.ENTER ) {
                keyEvent.consume();
                playBtn.fire();
            }
        } );

        // Configure F5 key to refresh
        scene.setOnKeyPressed( keyEvent -> {
            if ( keyEvent.getCode() == KeyCode.F5 ) {
                keyEvent.consume();
                SystemUtilities.spawnNewTask( () -> {
                    GameModPackManager.fetchModPackInfo();
                    populateModpackDropdown();
                    MCLauncherGuiController.goToGui( this );
                } );
            }
        } );
    }

    /**
     * Abstract method: This method must perform preparations of the environment, such as enabling menu bars, context
     * menus, or other OS-specific enhancements.
     */
    @Override
    void loadEnvironment() {

    }

    /**
     * Abstract method: This method returns a boolean indicating if a warning should be shown to the user before closing
     * the window while displaying the stage/GUI.
     *
     * @return boolean indicating if window close warning should be shown
     */
    @Override
    boolean warnOnExit() {
        return false;
    }

    /**
     * Custom change listener for handling a change in the selection of the mod pack list. Updates the mod pack logo and
     * information for the newly selected mod pack.
     *
     * @since 1.0
     */
    private final ChangeListener< Number > packSelectionChangeListener = ( observableValue, oldVal, newVal ) -> {
        // Get selected mod pack
        GameModPack selectedGameModPack = GameModPackManager.getInstalledModPackByFriendlyName(
                packSelection.getValue() );

        // Load modpack logo and set in GUI
        Image packLogoImg;
        if ( selectedGameModPack != null ) {
            packLogoImg = new Image( selectedGameModPack.getPackLogoURL() );
        }
        else {
            packLogoImg = new Image( ModPackConstants.MODPACK_DEFAULT_LOGO_URL );
        }
        GUIUtilities.JFXPlatformRun( () -> {
            packLogo.setImage( packLogoImg );

            // Set modpack background image on root pane
            if ( selectedGameModPack != null ) {
                rootPane.setStyle( rootPane.getStyle() +
                                           "-fx-background-image: url('" +
                                           selectedGameModPack.getPackBackgroundURL() +
                                           "');" );
            }
            else {
                rootPane.setStyle( rootPane.getStyle() +
                                           "-fx-background-image: url('" +
                                           ModPackConstants.MODPACK_DEFAULT_BG_URL +
                                           "');" );
            }

            rootPane.setStyle( rootPane.getStyle() + "-fx-background-size: cover; -fx-background-repeat: no-repeat;" );
        } );
    };

    public void selectModpack( GameModPack modPack ) {
        // Select supplied modpack
        packSelection.getSelectionModel().selectFirst();
        packSelection.getSelectionModel().select( modPack.getFriendlyName() );
    }

    /**
     * Populates the contents of the mod pack dropdown list, or disables the pack selection list and displays a message
     * if no mod packs are installed.
     *
     * @since 1.0
     */
    private void populateModpackDropdown() {
        // Get list of modpack names
        List< String > modpackList = GameModPackManager.getInstalledModPackFriendlyNames();

        // Reset modpack selector
        GUIUtilities.JFXPlatformRun( () -> {
            packSelection.getSelectionModel().selectedIndexProperty().removeListener( packSelectionChangeListener );
            packSelection.getItems().clear();
        } );

        if ( modpackList.size() > 0 ) {
            GUIUtilities.JFXPlatformRun( () -> {
                packSelection.setDisable( false );
                packSelection.getItems().addAll( modpackList );
                packSelection.getSelectionModel().selectedIndexProperty().addListener( packSelectionChangeListener );
                packSelection.getSelectionModel().selectFirst();
            } );
        }
        else {
            GUIUtilities.JFXPlatformRun( () -> {
                packSelection.getItems().add( "No modpacks installed!" );
                packSelection.getSelectionModel().selectFirst();
                packSelection.setDisable( true );
                packLogo.setImage( new Image( GUIConstants.URL_MINECRAFT_NO_MOD_PACK_IMAGE ) );
                rootPane.setStyle( rootPane.getStyle() +
                                           "-fx-background-image: url('" +
                                           ModPackConstants.MODPACK_DEFAULT_BG_URL +
                                           "');" );
                rootPane.setStyle(
                        rootPane.getStyle() + "-fx-background-size: cover; -fx-background-repeat: no-repeat;" );
            } );
        }
    }
}
