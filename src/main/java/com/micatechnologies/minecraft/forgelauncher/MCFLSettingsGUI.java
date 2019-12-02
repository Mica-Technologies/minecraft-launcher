package com.micatechnologies.minecraft.forgelauncher;

import com.google.common.primitives.Doubles;
import com.jfoenix.controls.*;
import com.micatechnologies.minecraft.forgemodpacklib.MCForgeMod;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;

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
        minRAM.getSelectionModel().selectedItemProperty().addListener( ( observable, oldValue, newValue ) -> dirty = true );

        // Populate and configure maximum RAM dropdown
        String[] maxRAMOptions = new String[ MCFLConfiguration.MAX_RAM_OPTIONS.length ];
        for ( int i = 0; i < maxRAMOptions.length; i++ ) {
            maxRAMOptions[ i ] = MCFLConfiguration.MAX_RAM_OPTIONS[ i ] + " GB";
        }
        maxRAM.getItems().addAll( maxRAMOptions );
        maxRAM.getSelectionModel().select( Doubles.asList( MCFLConfiguration.MAX_RAM_OPTIONS ).indexOf( MCFLApp.getLauncherConfig().getMaxRAM() ) );
        maxRAM.getSelectionModel().selectedItemProperty().addListener( ( observable, oldValue, newValue ) -> dirty = true );

        // Populate and configure modpacks chip view
        modpackList.setChipFactory( ( stringJFXChipView, s ) -> {
            JFXChip< String > newChip = new JFXDefaultChip<>( stringJFXChipView, s );
            // Very scary, but it works
            ( ( Label ) ( ( HBox ) newChip.getChildrenUnmodifiable().get( 0 ) ).getChildren().get( 0 ) ).setMaxWidth( 1000.0 );
            return newChip;
        } );
        modpackList.getChips().addAll( MCFLApp.getLauncherConfig().getModpacks() );
        modpackList.getChips().addListener( ( ListChangeListener< String > ) c -> {
            dirty = true;

            // Triger reindex of modpacks
            new Thread( () -> {
                Platform.runLater( () -> getCurrentStage().setAlwaysOnTop( false ) );
                MCFLApp.buildMemoryModpackList();
                Platform.runLater( () -> getCurrentStage().setAlwaysOnTop( true ) );
            } ).start();
        } );

        // Configure click pane
        clickPane.setOnMouseDragged( event -> {
            new Thread( () -> {
                MCFLAdminGUI adminGUI = new MCFLAdminGUI();
                adminGUI.open();
                adminGUI.getCurrentStage().initModality( Modality.APPLICATION_MODAL );
                adminGUI.getCurrentStage().initOwner( this.getCurrentStage() );
                adminGUI.getCurrentStage().setAlwaysOnTop( true );
                try {
                    adminGUI.closedLatch.await();
                }
                catch ( InterruptedException e ) {
                    System.err.println( "Unable to wait completion of GUI." );
                }
            } ).start();
        } );

        // Configure save button
        saveBtn.setOnAction( event -> {
            // Store min ram to config
            MCFLApp.getLauncherConfig().setMinRAM( MCFLConfiguration.MIN_RAM_OPTIONS[ minRAM.getSelectionModel().getSelectedIndex() ] );

            // Store max ram to config
            MCFLApp.getLauncherConfig().setMaxRAM( MCFLConfiguration.MAX_RAM_OPTIONS[ maxRAM.getSelectionModel().getSelectedIndex() ] );

            // Store modpack list to config
            MCFLApp.getLauncherConfig().getModpacks().clear();
            MCFLApp.getLauncherConfig().getModpacks().addAll( modpackList.getChips() );

            // Save config to disk
            MCFLApp.saveConfig();

            // Reset dirty flag (changes have been saved)
            dirty = false;

            // Change save button text to indicate successful save
            saveBtn.setText( SAVED_BUTTON_TEXT );

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
            modpacksLabel.setTextFill( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
        } );
    }

    @Override
    void enableDarkMode() {
        Platform.runLater( () -> {
            rootPane.setBackground( new Background( new BackgroundFill( Color.web( MCFLConstants.GUI_DARK_COLOR ), CornerRadii.EMPTY, Insets.EMPTY ) ) );
            minRAMLabel.setTextFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
            maxRAMLabel.setTextFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
            modpacksLabel.setTextFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
        } );
    }
}
