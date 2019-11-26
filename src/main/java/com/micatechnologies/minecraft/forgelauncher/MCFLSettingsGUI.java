package com.micatechnologies.minecraft.forgelauncher;

import com.google.common.primitives.Doubles;
import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXChipView;
import com.jfoenix.controls.JFXComboBox;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;

/**
 * GUI for launcher settings. Allows launcher configuration.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class MCFLSettingsGUI extends MCFLGenericGUI {

    /**
     * Button text for save button.
     */
    public static final String SAVE_BUTTON_TEXT = "Save";

    /**
     * Button text for save button after a successful save.
     */
    public static final String SAVED_BUTTON_TEXT = "Saved!";

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
    void create() {
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
        modpackList.getChips().addAll( MCFLApp.getLauncherConfig().getModpacks() );
        modpackList.setOnKeyPressed( event -> dirty = true );
        // TODO: Configure chips view chip size to fit length of string

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
        return new int[]{ 500, 500 };
    }
}
