package com.micatechnologies.minecraft.forgelauncher;

import com.google.common.primitives.Doubles;
import com.jfoenix.controls.*;
import com.micatechnologies.minecraft.forgelauncher.utilities.FLSystemUtils;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

/**
 * GUI for launcher settings. Allows launcher configuration.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class MCFLSettingsGUI extends MCFLGenericGUI {

    /**
     * Root window pane
     */
    @FXML
    public AnchorPane rootPane;

    /**
     * Button text for save button.
     */
    public static final String SAVE_BUTTON_TEXT = "Save";

    /**
     * Button text for save button after a successful save.
     */
    public static final String SAVED_BUTTON_TEXT = "Saved!";

    @FXML
    public Label minRAMLabel;

    @FXML
    public Label maxRAMLabel;

    @FXML
    public Label modpacksLabel;

    @FXML
    public Pane clickPane;

    @FXML
    public JFXCheckBox debugModeCheck;

    @FXML
    public JFXCheckBox resizableWindowCheck;

    @FXML
    public JFXButton resetRuntimeBtn;

    @FXML
    public JFXButton resetLauncherBtn;

    @FXML
    public Label versionLabel;

    /**
     * Button to close settings/return
     */
    @FXML
    public JFXButton returnBtn;

    /**
     * Button to save settings
     */
    @FXML
    public JFXButton saveBtn;

    /**
     * Combo box to pick minimum RAM
     */
    @FXML
    public JFXComboBox< String > minRAM;

    /**
     * Combo box to pick maximum RAM
     */
    @FXML
    public JFXComboBox< String > maxRAM;

    /**
     * Chip view to configure/add/remove modpacks
     */
    @FXML
    public JFXChipView< String > modpackList;

    /**
     * Flag to indicate a change in the configuration.
     * This allows a reminder when closing with saving.
     */
    public boolean dirty = false;

    /**
     * Initialize the min/max RAM dropdowns, modpack chip view,
     * save button and return button.
     *
     * @since 1.0
     */
    @Override
    void create( Stage stage ) {
        // Populate and configure minimum RAM dropdown
        String[] minRAMOptions = new String[ MCFLConfiguration.MIN_RAM_OPTIONS.length ];
        for ( int i = 0; i < minRAMOptions.length; i++ ) {
            minRAMOptions[ i ] = MCFLConfiguration.MIN_RAM_OPTIONS[ i ] + " GB";
        }
        minRAM.getItems().addAll( minRAMOptions );
        minRAM.getSelectionModel().select( Doubles.asList( MCFLConfiguration.MIN_RAM_OPTIONS ).indexOf( MCFLApp.getLauncherConfig().getMinRAM() ) );
        minRAM.getSelectionModel().selectedItemProperty().addListener( ( observable, oldValue, newValue ) -> setEdited( true ) );

        // Populate and configure maximum RAM dropdown
        String[] maxRAMOptions = new String[ MCFLConfiguration.MAX_RAM_OPTIONS.length ];
        for ( int i = 0; i < maxRAMOptions.length; i++ ) {
            maxRAMOptions[ i ] = MCFLConfiguration.MAX_RAM_OPTIONS[ i ] + " GB";
        }
        maxRAM.getItems().addAll( maxRAMOptions );
        maxRAM.getSelectionModel().select( Doubles.asList( MCFLConfiguration.MAX_RAM_OPTIONS ).indexOf( MCFLApp.getLauncherConfig().getMaxRAM() ) );
        maxRAM.getSelectionModel().selectedItemProperty().addListener( ( observable, oldValue, newValue ) -> setEdited( true ) );

        // Populate and configure modpacks chip view
        modpackList.setChipFactory( ( stringJFXChipView, s ) -> {
            JFXChip< String > newChip = new JFXDefaultChip<>( stringJFXChipView, s );
            // Very scary, but it works
            ( ( Label ) ( ( HBox ) newChip.getChildrenUnmodifiable().get( 0 ) ).getChildren().get( 0 ) ).setMaxWidth( 1000.0 );
            return newChip;
        } );
        modpackList.getChips().addAll( MCFLApp.getLauncherConfig().getModpacks() );
        modpackList.getChips().addListener( ( ListChangeListener< String > ) c -> {
            setEdited( true );
        } );

        // Set and configure debug mode checkbox
        debugModeCheck.setSelected( MCFLApp.getLauncherConfig().getDebug() );
        debugModeCheck.setOnAction( event -> {
            setEdited( true );
        } );

        // Set and configure resizable windows checkbox
        resizableWindowCheck.setSelected( MCFLApp.getLauncherConfig().getResizableguis() );
        resizableWindowCheck.setOnAction( event -> {
            setEdited( true );
        } );

        // Load version info
        Package p = getClass().getPackage();
        String version = p.getImplementationVersion();
        versionLabel.setText( "Version: " + version );

        // Configure click pane
        clickPane.setOnMouseDragged( event -> {
            hide();
            new Thread( () -> {
                // Open admin GUI and disable main window
                MCFLAdminGUI MCFLAdminGUI = new MCFLAdminGUI();
                MCFLAdminGUI.open();

                // Wait for settings to close, then enable main window again
                new Thread( () -> {
                    try {
                        MCFLAdminGUI.closedLatch.await();
                    }
                    catch ( InterruptedException e ) {
                    }
                    show();
                } ).start();
            } ).start();
        } );

        // Configure reset runtime button
        resetRuntimeBtn.setOnAction( event -> new Thread( () -> {
            int response = MCFLGUIController.showQuestionMessage( "Continue?", "Entering the Danger Zone", "Are you sure you'd like to reset the runtime? This may take a few minutes!", "Reset", "Back to Safety", getCurrentStage() );
            if ( response != 1 ) {
                return;
            }

            hide();
            try {
                MCFLApp.clearLocalJDK();
            }
            catch ( IOException e ) {
                MCFLLogger.error( "Unable to clear previous runtime from disk. Will continue to attempt reset!", 700, getCurrentStage() );
            }
            MCFLApp.doLocalJDK();
            show();
        } ).start() );

        // Configure reset launcher button
        resetLauncherBtn.setOnAction( event -> new Thread( () -> {
            int response = MCFLGUIController.showQuestionMessage( "Continue?", "Entering the Danger Zone", "Are you sure you'd like to reset the launcher? This may take a few minutes!", "Reset", "Back to Safety", getCurrentStage() );
            if ( response != 1 ) {
                return;
            }

            hide();
            try {
                MCFLApp.logoutCurrentUser();
                FileUtils.deleteDirectory( new File( MCFLApp.getInstallPath() ) );
                close();
            }
            catch ( IOException e ) {
                MCFLLogger.error( "An error occurred while resetting the launcher. Will continue to attempt!", 700, getCurrentStage() );
            }
        } ).start() );

        // Configure save button
        saveBtn.setOnAction( event -> {
            // Store min ram to config
            MCFLApp.getLauncherConfig().setMinRAM( MCFLConfiguration.MIN_RAM_OPTIONS[ minRAM.getSelectionModel().getSelectedIndex() ] );

            // Store max ram to config
            MCFLApp.getLauncherConfig().setMaxRAM( MCFLConfiguration.MAX_RAM_OPTIONS[ maxRAM.getSelectionModel().getSelectedIndex() ] );

            // Store modpack list to config
            MCFLApp.getLauncherConfig().getModpacks().clear();
            MCFLApp.getLauncherConfig().getModpacks().addAll( modpackList.getChips() );

            // Store debug mode to config
            MCFLApp.getLauncherConfig().setDebug( debugModeCheck.isSelected() );

            // Store resizable windows to config
            MCFLApp.getLauncherConfig().setResizableguis( resizableWindowCheck.isSelected() );

            // Save config to disk
            MCFLApp.saveConfig();

            // Reset dirty flag (changes have been saved)
            setEdited( false );

            // Change save button text to indicate successful save
            saveBtn.setText( SAVED_BUTTON_TEXT );

            // Force window changes apply
            doWindowConfigUpdates();

            // Schedule save button text to revert to normal after 5s
            new Thread( () -> {
                try {
                    Thread.sleep( 5000 );
                }
                catch ( InterruptedException ignored ) {
                }
                Platform.runLater( () -> saveBtn.setText( SAVE_BUTTON_TEXT ) );
            } ).start();
        } );

        // Configure return button
        returnBtn.setOnAction( event -> {
            new Thread( () -> {
                if ( dirty ) {
                    int response = MCFLGUIController.showQuestionMessage( "Save?", "Unsaved Changes", "Are you sure you want to exit without saving changes?", "Save", "Exit", getCurrentStage() );
                    if ( response == 1 ) {
                        Platform.runLater( () -> saveBtn.fire() );
                        close();
                    }
                    else if ( response == 2 ) {
                        close();
                    }
                }
                else {
                    close();
                }
            } ).start();
        } );
        stage.setOnCloseRequest( event -> {
            event.consume();
            returnBtn.fire();
        } );
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
        fxmll.setLocation( getClass().getClassLoader().getResource( "LauncherSettingsGUI.fxml" ) );
        fxmll.setController( this );
        return fxmll;
    }

    private void setEdited( boolean edited ) {
        if ( FLSystemUtils.isMac() ) getNativeMacWindow().setDocumentEdited( edited );
        dirty = edited;
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
        return new int[]{ 800, 425 };
    }

    @Override
    void enableLightMode() {
        Platform.runLater( () -> {
            rootPane.setBackground( new Background( new BackgroundFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ), CornerRadii.EMPTY, Insets.EMPTY ) ) );
            minRAMLabel.setTextFill( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
            maxRAMLabel.setTextFill( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
            versionLabel.setTextFill( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
            modpacksLabel.setTextFill( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
            resizableWindowCheck.setTextFill( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
            debugModeCheck.setTextFill( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
            resizableWindowCheck.setUnCheckedColor( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
            debugModeCheck.setUnCheckedColor( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
        } );
    }

    @Override
    void enableDarkMode() {
        Platform.runLater( () -> {
            rootPane.setBackground( new Background( new BackgroundFill( Color.web( MCFLConstants.GUI_DARK_COLOR ), CornerRadii.EMPTY, Insets.EMPTY ) ) );
            minRAMLabel.setTextFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
            maxRAMLabel.setTextFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
            versionLabel.setTextFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
            modpacksLabel.setTextFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
            resizableWindowCheck.setTextFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
            debugModeCheck.setTextFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
            resizableWindowCheck.setUnCheckedColor( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
            debugModeCheck.setUnCheckedColor( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
        } );
    }

    @Override
    Pane getRootPane() {
        return rootPane;
    }

    @Override
    void onShow() {

    }
}
