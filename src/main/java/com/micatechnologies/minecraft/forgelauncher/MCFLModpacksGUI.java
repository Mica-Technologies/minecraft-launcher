package com.micatechnologies.minecraft.forgelauncher;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXComboBox;
import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpack;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.stage.Modality;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI for logged in users. Allows settings button, modpack selection, play button, etc.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class MCFLModpacksGUI extends MCFLGenericGUI {

    /**
     * Combo box for choosing modpack
     */
    @FXML
    JFXComboBox< String > packList;

    /**
     * User label/message text
     */
    @FXML
    public Label userMsg;

    /**
     * Play button
     */
    @FXML
    public Button playBtn;

    /**
     * Exit button
     */
    @FXML
    public JFXButton exitButton;

    /**
     * Settings button
     */
    @FXML
    public JFXButton settingsButton;

    /**
     * Logout button
     */
    @FXML
    public Button logoutBtn;

    /**
     * User icon image
     */
    @FXML
    public ImageView userIcon;

    /**
     * Handle the creation and initial configuration of GUI controls/elements.
     *
     * @since 1.0
     */
    @Override
    void create() {
        // Configure exit button
        exitButton.setOnAction( event -> new Thread( () -> {
            Platform.setImplicitExit( true );
            System.exit( 0 );
        } ).start() );

        // Configure settings button
        settingsButton.setOnAction( event -> new Thread( () -> {
            MCFLSettingsGUI MCFLSettingsGUI = new MCFLSettingsGUI();
            MCFLSettingsGUI.open();
            MCFLSettingsGUI.getCurrentStage().initModality( Modality.APPLICATION_MODAL );
            MCFLSettingsGUI.getCurrentStage().initOwner( this.getCurrentStage() );
        } ).start() );

        // Configure logout button
        logoutBtn.setOnAction( event -> new Thread( MCFLApp::logoutCurrentUser ).start() );

        // Populate modpacks dropdown
        List< String > modpacksList = new ArrayList<>();
        for ( MCForgeModpack modpack : MCFLApp.getModpacks() ) {
            modpacksList.add( modpack.getPackName() );
        }
        packList.getItems().addAll( modpacksList );
        // TODO: Add listener to decorate window/change image based on modpack

        // Configure play button
        playBtn.setOnAction( event -> new Thread( () -> {
            Platform.setImplicitExit( false );
            hide();
            MCFLApp.play( packList.getSelectionModel().getSelectedIndex(), this );
            show();
        } ).start() );

        // Configure user image
        // TODO: Configure userIcon with image for current logged in user

        // Configure user label
        userMsg.setText( "Hello, " + MCFLApp.getCurrentUser().getFriendlyName() );
    }

    /**
     * Set the selected modpack index
     *
     * @param packIndex modpack index
     */
    public void setSelectedModpackIndex( int packIndex ) {
        Platform.runLater( () -> packList.getSelectionModel().select( packIndex ) );
    }

    /**
     * Create the FXMLLoader for showing the JavaFX stage
     *
     * @return created FXMLLoader
     *
     * @since 1.0
     */
    @Override
    FXMLLoader getFXMLLoader() {
        FXMLLoader fxmll = new FXMLLoader();
        fxmll.setLocation( getClass().getClassLoader().getResource( "LauncherModpackGUI.fxml" ) );
        fxmll.setController( this );
        return fxmll;
    }

    /**
     * Get the width and height of the JavaFX stage
     *
     * @return [width, height] of JavaFX stage
     *
     * @since 1.0
     */
    @Override
    int[] getSize() {
        return new int[]{ 650, 425 };
    }

    @Override
    void enableLightMode() {

    }

    @Override
    void enableDarkMode() {

    }

    /**
     * Open the GUI and automatically select the modpack index supplied
     *
     * @param modpackIndex modpack index
     */
    public void open( int modpackIndex ) {
        // Open GUI
        super.open();

        // Wait for GUI to be ready
        if ( readyLatch.getCount() > 0 ) {
            try {
                readyLatch.await();
            }
            catch ( InterruptedException ignored ) {
            }
        }

        // Select first (as backup), then select supplied index
        packList.getSelectionModel().selectFirst();
        packList.getSelectionModel().select( modpackIndex );
    }
}
