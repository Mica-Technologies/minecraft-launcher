package com.micatechnologies.minecraft.forgelauncher.gui;


import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXComboBox;
import com.micatechnologies.minecraft.forgelauncher.MCFLApp;
import com.micatechnologies.minecraft.forgelauncher.MCFLConstants;
import com.micatechnologies.minecraft.forgelauncher.modpack.MCForgeModpack;
import com.micatechnologies.minecraft.forgelauncher.modpack.MCForgeModpackConsts;
import com.micatechnologies.minecraft.forgelauncher.utilities.FLGUIUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.FLLogger;
import com.micatechnologies.minecraft.forgelauncher.utilities.FLSystemUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.Pair;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.WindowEvent;
import org.apache.commons.lang3.NotImplementedException;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;

public class FLMainGUI extends FLGenericGUI {

    @FXML
    ImageView packLogo;

    @FXML
    JFXComboBox< String > packSelection;

    @FXML
    JFXButton playBtn;

    @FXML
    JFXButton exitBtn;

    @FXML
    JFXButton settingsBtn;

    @FXML
    JFXButton logoutBtn;

    @FXML
    ImageView userImage;

    @FXML
    ImageView updateImgView;

    @FXML
    Label playerLabel;


    @Override
    String getFXMLResourcePath() {
        return "mainGUI.fxml";
    }

    @Override
    Pair< Integer, Integer > getWindowSize() {
        return new Pair<>( 600, 600 );
    }

    @Override
    void setupWindow() {
        // Configure exit button and window close
        currentJFXStage.setOnCloseRequest( windowEvent -> FLSystemUtils.spawnNewTask( MCFLApp::closeApp ) );
        exitBtn.setOnAction( actionEvent -> currentJFXStage.fireEvent( new WindowEvent( currentJFXStage, WindowEvent.WINDOW_CLOSE_REQUEST ) ) );

        // Check for launcher update and show image if there is one
        updateImgView.setVisible( false );
        FLSystemUtils.spawnNewTask( () -> {
            try {
                // Get current version
                String version = MCFLConstants.LAUNCHER_APPLICATION_VERSION;

                // Get latest version
                URLConnection con = new URL( MCFLConstants.UPDATE_CHECK_REDIRECT_URL ).openConnection();
                con.connect();
                InputStream is = con.getInputStream();
                String latestVersionURL = con.getURL().toExternalForm();
                String[] latestVersionURLParts = latestVersionURL.split( "/" );
                String latestVersion = latestVersionURLParts[ latestVersionURLParts.length - 1 ];
                is.close();

                // Check if current version is less than latest
                if ( FLSystemUtils.compareVersionNumbers( version, latestVersion ) == -1 ) {
                    updateImgView.setVisible( true );
                    updateImgView.setOnMouseClicked( mouseEvent -> FLSystemUtils.spawnNewTask( () -> {
                        int response = FLGUIUtils.showQuestionMessage( "Update Available", "Update Ready to Download", "An update has been found and is ready to be downloaded and installed.", "Update Now", "Update Later", getCurrentJFXStage() );
                        if ( response == 1 ) {
                            try {
                                Desktop.getDesktop().browse( URI.create( latestVersionURL ) );
                            }
                            catch ( IOException e ) {
                                FLLogger.logError( "Unable to open your browser. Please visit " + latestVersionURL + " to download the latest launcher updates!", getCurrentJFXStage() );
                            }
                        }
                    } ) );
                }
            }
            catch ( Exception e ) {
                e.printStackTrace();
                FLLogger.logError( "An error occurred while checking for an updated launcher version!" );
            }
        } );

        // Configure settings button
        settingsBtn.setOnAction( actionEvent -> FLSystemUtils.spawnNewTask( () -> {
            Platform.setImplicitExit( false );
            hide();

            // Open settings GUI and disable main window
            FLSettingsGUI flSettingsGUI = new FLSettingsGUI();
            flSettingsGUI.show();

            // Wait for settings to close, then enable main window again
            FLSystemUtils.spawnNewTask( () -> {
                try {
                    flSettingsGUI.closedLatch.await();
                }
                catch ( InterruptedException e ) {
                }

                // If loop login is true, launcher reset, need to go to login screen
                if ( MCFLApp.getLoopLogin() ) {
                    new Thread( () -> {
                        Platform.setImplicitExit( true );
                        close();
                    } ).start();
                }

                MCFLApp.buildMemoryModpackList();
                show();
                populateModpackDropdown();
            } );
        } ) );

        // Configure logout button
        logoutBtn.setOnAction( actionEvent -> {
            MCFLApp.logoutCurrentUser();
            close();
        } );

        // Populate list of modpacks
        populateModpackDropdown();

        // Configure play button
        playBtn.setOnAction( actionEvent -> FLSystemUtils.spawnNewTask( () -> {
            Platform.setImplicitExit( false );
            hide();
            MCFLApp.play( packSelection.getSelectionModel().getSelectedIndex(), this );
            show();
        } ) );

        // Configure user label
        playerLabel.setText( MCFLApp.getCurrentUser().getFriendlyName() );

        // Configure user image
        userImage.setImage( new Image( MCFLConstants.URL_MINECRAFT_USER_ICONS.replace( "user", MCFLApp.getCurrentUser().getUserIdentifier() ) ) );

        // Configure ENTER key to press login button
        rootPane.setOnKeyPressed( keyEvent -> {
            keyEvent.consume();
            playBtn.fire();
        } );
    }

    private final ChangeListener< Number > packSelectionChangeListener = ( observableValue, oldVal, newVal ) -> {
        // Load modpack logo and set in GUI
        Image packLogoImg = new Image( MCFLApp.getModpacks().get( packSelection.getSelectionModel().getSelectedIndex() == -1 ? 0 : packSelection.getSelectionModel().getSelectedIndex() ).getPackLogoURL() );
        FLGUIUtils.JFXPlatformRun( () -> {
            packLogo.setImage( packLogoImg );

            // Set modpack background image on root pane
            rootPane.setStyle( rootPane.getStyle() + "-fx-background-image: url('" + MCFLApp.getModpacks().get( packSelection.getSelectionModel().getSelectedIndex() == -1 ? 0 : packSelection.getSelectionModel().getSelectedIndex() ).getPackBackgroundURL() + "');" );
            rootPane.setStyle( rootPane.getStyle() + "-fx-background-size: cover; -fx-background-repeat: no-repeat;" );
        } );
    };

    private void populateModpackDropdown() {
        // Create list of modpack names
        List< String > modpackList = new ArrayList<>();
        for ( MCForgeModpack modpack : MCFLApp.getModpacks() ) {
            modpackList.add( modpack.getPackName() );
        }

        // Reset modpack selector
        FLGUIUtils.JFXPlatformRun( () -> {
            packSelection.getSelectionModel().selectedIndexProperty().removeListener( packSelectionChangeListener );
            packSelection.getItems().clear();
        } );

        if ( modpackList.size() > 0 ) {
            FLGUIUtils.JFXPlatformRun( () -> {
                packSelection.setDisable( false );
                packSelection.getItems().addAll( modpackList );
                packSelection.getSelectionModel().selectedIndexProperty().addListener( packSelectionChangeListener );
                packSelection.getSelectionModel().selectFirst();
            } );
        }
        else {
            FLGUIUtils.JFXPlatformRun( () -> {
                packSelection.getItems().add( "No modpacks installed!" );
                packSelection.getSelectionModel().selectFirst();
                packSelection.setDisable( true );
                packLogo.setImage( new Image( MCFLConstants.URL_MINECRAFT_NO_MODPACK_IMAGE ) );
                rootPane.setStyle( rootPane.getStyle() + "-fx-background-image: url('" + MCForgeModpackConsts.MODPACK_DEFAULT_BG_URL + "');" );
                rootPane.setStyle( rootPane.getStyle() + "-fx-background-size: cover; -fx-background-repeat: no-repeat;" );
            } );
        }
    }

    public void show( int modpack ) {
        // Do standard show method tasks
        super.show();

        // Select supplied modpack
        packSelection.getSelectionModel().selectFirst();
        packSelection.getSelectionModel().select( modpack );
    }
}
