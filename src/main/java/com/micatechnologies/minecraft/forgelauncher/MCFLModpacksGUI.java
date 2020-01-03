package com.micatechnologies.minecraft.forgelauncher;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXComboBox;
import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpack;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

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
     * Root window pane
     */
    @FXML
    public AnchorPane rootPane;

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
     * Current modpack logo
     */
    @FXML
    public ImageView packLogo;

    /**
     * Text for selecting modpack
     */
    public Label upperText;

    /**
     * Handle the creation and initial configuration of GUI controls/elements.
     *
     * @since 1.0
     */
    @Override
    void create( Stage stage ) {
        // Configure exit button
        stage.setOnCloseRequest( event -> {
            new Thread( () -> {
                Platform.setImplicitExit( true );
                System.exit( 0 );
            } ).start();
        } );
        exitButton.setOnAction( event -> getCurrentStage().fireEvent( new WindowEvent( getCurrentStage(), WindowEvent.WINDOW_CLOSE_REQUEST ) ) );

        // Configure settings button
        settingsButton.setOnAction( event -> {
            hide();
            new Thread( () -> {
                // Open settings GUI and disable main window
                MCFLSettingsGUI MCFLSettingsGUI = new MCFLSettingsGUI();
                MCFLSettingsGUI.open();

                // Wait for settings to close, then enable main window again
                new Thread( () -> {
                    try {
                        MCFLSettingsGUI.closedLatch.await();
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
                    populateModpacksDropdown();
                } ).start();

            } ).start();
        } );

        // Configure logout button
        logoutBtn.setOnAction( event -> new Thread( () -> {
            MCFLApp.logoutCurrentUser();
            Platform.setImplicitExit( true );
            close();
        } ).start() );

        // Populate modpacks dropdown
        populateModpacksDropdown();

        // Configure play button
        playBtn.setOnAction( event -> new Thread( () -> {
            Platform.setImplicitExit( false );
            hide();
            MCFLApp.play( packList.getSelectionModel().getSelectedIndex(), this );
            show();
        } ).start() );

        // Configure user image
        userIcon.setImage( new Image( MCFLConstants.URL_MINECRAFT_USER_ICONS.replace( "user", MCFLApp.getCurrentUser().getUserIdentifier() ) ) );

        // Configure user label
        userMsg.setText( "Hello, " + MCFLApp.getCurrentUser().getFriendlyName() );

        // Configure ENTER key to press play
        rootPane.setOnKeyPressed( event -> {
            if ( event.getCode() == KeyCode.ENTER ) {
                event.consume();
                playBtn.fire();
            }
        } );
    }

    private ChangeListener< Number > packSelectChangeListener = new ChangeListener< Number >() {
        @Override
        public void changed( ObservableValue< ? extends Number > observable, Number oldValue, Number newValue ) {
            packLogo.setImage( new Image( MCFLApp.getModpacks().get( packList.getSelectionModel().getSelectedIndex() == -1 ? 0 : packList.getSelectionModel().getSelectedIndex() ).getPackLogoURL() ) );
        }
    };

    private void populateModpacksDropdown() {
        // Create list of modpack names
        List< String > modpacksList = new ArrayList<>();
        for ( MCForgeModpack modpack : MCFLApp.getModpacks() ) {
            modpacksList.add( modpack.getPackName() );
        }

        // Reset modpack selector
        packList.getSelectionModel().selectedIndexProperty().removeListener( packSelectChangeListener );
        packList.getItems().clear();

        // If modpacks list not empty, add to modpack selector
        if ( modpacksList.size() > 0 ) {
            Platform.runLater( () -> {
                packList.setDisable( false );
                packList.getItems().addAll( modpacksList );
                packList.getSelectionModel().selectedIndexProperty().addListener( packSelectChangeListener );
                packList.getSelectionModel().selectFirst();
            } );
        }
        // If no modpacks, show message
        else {
            Platform.runLater( () -> {
                packList.getItems().add( "No configured modpacks!" );
                packList.getSelectionModel().selectFirst();
                packList.setDisable( true );
                packLogo.setImage( new Image( MCFLConstants.URL_MINECRAFT_NO_MODPACK_IMAGE ) );
            } );
        }
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
        Platform.runLater( () -> {
            upperText.setTextFill( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
            rootPane.setBackground( new Background( new BackgroundFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ), CornerRadii.EMPTY, Insets.EMPTY ) ) );
            packList.setUnFocusColor( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
        } );
    }

    @Override
    void enableDarkMode() {
        Platform.runLater( () -> {
            upperText.setTextFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
            rootPane.setBackground( new Background( new BackgroundFill( Color.web( MCFLConstants.GUI_DARK_COLOR ), CornerRadii.EMPTY, Insets.EMPTY ) ) );
            packList.setUnFocusColor( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
        } );
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

    @Override
    Pane getRootPane() {
        return rootPane;
    }
}
