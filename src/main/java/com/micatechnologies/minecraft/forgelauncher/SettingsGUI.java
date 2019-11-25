package com.micatechnologies.minecraft.forgelauncher;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXChipView;
import com.jfoenix.controls.JFXComboBox;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;

/**
 * GUI for launcher settings. Allows launcher configuration.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class SettingsGUI extends MCFLGenericGUI {

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

        // Populate and configure maximum RAM dropdown
        String[] maxRAMOptions = new String[ MCFLConfiguration.MAX_RAM_OPTIONS.length ];
        for ( int i = 0; i < maxRAMOptions.length; i++ ) {
            maxRAMOptions[ i ] = MCFLConfiguration.MAX_RAM_OPTIONS[ i ] + " GB";
        }
        maxRAM.getItems().addAll( maxRAMOptions );

        // TODO: Configure Modpacks Chip View
        // TODO: Configure Save Button
        // TODO: Configure Return Button
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
