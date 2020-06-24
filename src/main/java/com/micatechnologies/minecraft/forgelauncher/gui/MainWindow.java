package com.micatechnologies.minecraft.forgelauncher.gui;


import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXComboBox;
import com.micatechnologies.minecraft.forgelauncher.LauncherApp;
import com.micatechnologies.minecraft.forgelauncher.LauncherConstants;
import com.micatechnologies.minecraft.forgelauncher.modpack.ModPack;
import com.micatechnologies.minecraft.forgelauncher.modpack.MCForgeModpackConsts;
import com.micatechnologies.minecraft.forgelauncher.modpack.ModPackInstallManager;
import com.micatechnologies.minecraft.forgelauncher.utilities.GuiUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.LogUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.SystemUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.OnScreen;
import com.micatechnologies.minecraft.forgelauncher.utilities.objects.Pair;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.WindowEvent;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;

/**
 * Launcher main/home window class.
 *
 * @author Mica Technologies
 * @version 3.0
 * @editors hawka97
 * @since 1.0
 * @creator hawka97
 */
public class MainWindow extends AbstractWindow
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
     * Implementation of abstract method that returns the file name of the FXML associated with this class.
     *
     * @return FXML file name
     *
     * @since 1.0
     */
    @Override
    String getFXMLResourcePath() {
        return "mainGUI.fxml";
    }

    /**
     * Implementation of abstract method that returns the minimum (and initial) size used for the window.
     *
     * @return window size as integer pair
     *
     * @since 1.0
     */
    @Override
    Pair< Integer, Integer > getWindowSize() {
        return new Pair<>( 600, 600 );
    }

    /**
     * Implementation of abstract method that handles the setup and population of elements on the window.
     *
     * @since 1.0
     */
    @Override
    void setupWindow() {
        // Configure exit button and window close
        currentJFXStage.setOnCloseRequest( windowEvent -> SystemUtils.spawnNewTask( LauncherApp::closeApp ) );
        exitBtn.setOnAction( actionEvent -> currentJFXStage
                .fireEvent( new WindowEvent( currentJFXStage, WindowEvent.WINDOW_CLOSE_REQUEST ) ) );

        // Check for launcher update and show image if there is one
        updateImgView.setVisible( false );
        SystemUtils.spawnNewTask( () -> {
            try {
                // Get current version
                String version = LauncherConstants.LAUNCHER_APPLICATION_VERSION;

                // Get latest version
                URLConnection con = new URL( LauncherConstants.UPDATE_CHECK_REDIRECT_URL ).openConnection();
                con.connect();
                InputStream is = con.getInputStream();
                String latestVersionURL = con.getURL().toExternalForm();
                String[] latestVersionURLParts = latestVersionURL.split( "/" );
                String latestVersion = latestVersionURLParts[ latestVersionURLParts.length - 1 ];
                is.close();

                // Check if current version is less than latest
                if ( SystemUtils.compareVersionNumbers( version, latestVersion ) == -1 ) {
                    updateImgView.setVisible( true );
                    updateImgView.setOnMouseClicked( mouseEvent -> SystemUtils.spawnNewTask( () -> {
                        int response = GuiUtils.showQuestionMessage( "Update Available", "Update Ready to Download",
                                                                     "An update has been found and is ready to be downloaded and installed.",
                                                                     "Update Now", "Update Later",
                                                                     getCurrentJFXStage() );
                        if ( response == 1 ) {
                            try {
                                Desktop.getDesktop().browse( URI.create( latestVersionURL ) );
                            }
                            catch ( IOException e ) {
                                LogUtils.logError( "Unable to open your browser. Please visit " + latestVersionURL +
                                                           " to download the latest launcher updates!" );
                            }
                        }
                    } ) );
                }
            }
            catch ( Exception e ) {
                e.printStackTrace();
                LogUtils.logError( "An error occurred while checking for an updated launcher version!" );
            }
        } );

        // Configure settings button
        settingsBtn.setOnAction( actionEvent -> SystemUtils.spawnNewTask( () -> {
            Platform.setImplicitExit( false );
            hide();

            // Open settings GUI and disable main window
            SettingsWindow flSettingsWindow = new SettingsWindow();
            flSettingsWindow.show();

            // Wait for settings to close, then enable main window again
            SystemUtils.spawnNewTask( () -> {
                try {
                    flSettingsWindow.closedLatch.await();
                }
                catch ( InterruptedException e ) {
                    LogUtils.logError( "Unable to wait for settings GUI before showing main window again!" );
                }

                // If loop login is true, launcher reset, need to go to login screen
                if ( LauncherApp.getLoopLogin() ) {
                    new Thread( () -> {
                        Platform.setImplicitExit( true );
                        close();
                    } ).start();
                }

                LauncherApp.buildMemoryModpackList();
                show();
                populateModpackDropdown();
            } );
        } ) );

        // Configure modpacks edit button
        editButton.setOnAction( actionEvent -> SystemUtils.spawnNewTask( () -> {
            // Create new edit GUI
            EditModpacksWindow editModpacksWindow = new EditModpacksWindow();
            editModpacksWindow.show();
            rootPane.setDisable( true );

            SystemUtils.spawnNewTask( () -> {
                try {
                    editModpacksWindow.closedLatch.await();
                }
                catch ( InterruptedException e ) {
                    LogUtils.logError( "Unable to wait for settings GUI before showing main window again!" );
                }

                // Refresh main GUI and show again
                LauncherApp.buildMemoryModpackList();
                show();
                rootPane.setDisable( false );
                populateModpackDropdown();
            } );

        } ) );

        // Configure logout button
        logoutBtn.setOnAction( actionEvent -> {
            LauncherApp.logoutCurrentUser();
            close();
        } );

        // Populate list of modpacks
        populateModpackDropdown();

        // Configure play button
        playBtn.setOnAction( actionEvent -> SystemUtils.spawnNewTask( () -> {
            Platform.setImplicitExit( false );
            hide();
            LauncherApp.play( packSelection.getSelectionModel().getSelectedItem(), this );
            show();
        } ) );

        // Configure user label
        playerLabel.setText( LauncherApp.getCurrentUser().getFriendlyName() );

        // Configure user image
        userImage.setImage( new Image( LauncherConstants.URL_MINECRAFT_USER_ICONS
                                               .replace( "user", LauncherApp.getCurrentUser().getUserIdentifier() ) ) );

        // Configure ENTER key to press login button
        rootPane.setOnKeyPressed( keyEvent -> {
            keyEvent.consume();
            playBtn.fire();
        } );
    }

    /**
     * Custom change listener for handling a change in the selection of the mod pack list. Updates the mod pack logo and
     * information for the newly selected mod pack.
     *
     * @since 1.0
     */
    private final ChangeListener< Number > packSelectionChangeListener = ( observableValue, oldVal, newVal ) -> {
        // Get selected mod pack
        ModPack selectedModPack = ModPackInstallManager.getInstalledModPackByFriendlyName( packSelection.getValue() );

        // Load modpack logo and set in GUI
        Image packLogoImg;
        if ( selectedModPack != null ) {
            packLogoImg = new Image( selectedModPack.getPackLogoURL() );
        }
        else {
            packLogoImg = new Image( MCForgeModpackConsts.MODPACK_DEFAULT_LOGO_URL );
        }
        GuiUtils.JFXPlatformRun( () -> {
            packLogo.setImage( packLogoImg );

            // Set modpack background image on root pane
            if ( selectedModPack != null ) {
                rootPane.setStyle(
                        rootPane.getStyle() + "-fx-background-image: url('" + selectedModPack.getPackBackgroundURL() +
                                "');" );
            }
            else {
                rootPane.setStyle( rootPane.getStyle() + "-fx-background-image: url('" +
                                           MCForgeModpackConsts.MODPACK_DEFAULT_BG_URL + "');" );
            }

            rootPane.setStyle( rootPane.getStyle() + "-fx-background-size: cover; -fx-background-repeat: no-repeat;" );
        } );
    };

    /**
     * Populates the contents of the mod pack dropdown list, or disables the pack selection list and displays a message
     * if no mod packs are installed.
     *
     * @since 1.0
     */
    private void populateModpackDropdown() {
        // Get list of modpack names
        List< String > modpackList = ModPackInstallManager.getInstalledModPackFriendlyNames();

        // Reset modpack selector
        GuiUtils.JFXPlatformRun( () -> {
            packSelection.getSelectionModel().selectedIndexProperty().removeListener( packSelectionChangeListener );
            packSelection.getItems().clear();
        } );

        if ( modpackList.size() > 0 ) {
            GuiUtils.JFXPlatformRun( () -> {
                packSelection.setDisable( false );
                packSelection.getItems().addAll( modpackList );
                packSelection.getSelectionModel().selectedIndexProperty().addListener( packSelectionChangeListener );
                packSelection.getSelectionModel().selectFirst();
            } );
        }
        else {
            GuiUtils.JFXPlatformRun( () -> {
                packSelection.getItems().add( "No modpacks installed!" );
                packSelection.getSelectionModel().selectFirst();
                packSelection.setDisable( true );
                packLogo.setImage( new Image( LauncherConstants.URL_MINECRAFT_NO_MODPACK_IMAGE ) );
                rootPane.setStyle( rootPane.getStyle() + "-fx-background-image: url('" +
                                           MCForgeModpackConsts.MODPACK_DEFAULT_BG_URL + "');" );
                rootPane.setStyle(
                        rootPane.getStyle() + "-fx-background-size: cover; -fx-background-repeat: no-repeat;" );
            } );
        }
    }

    /**
     * Shows the window with the desired mod pack initially selected.
     *
     * @param modpack mod pack to select
     *
     * @since 1.0
     */
    public void show( String modpack ) {
        // Do standard show method tasks
        super.show();

        // Select supplied modpack
        packSelection.getSelectionModel().selectFirst();
        packSelection.getSelectionModel().select( modpack );
    }
}
