package com.micatechnologies.minecraft.forgelauncher.gui;


import com.google.common.primitives.Doubles;
import com.jfoenix.controls.*;
import com.micatechnologies.minecraft.forgelauncher.LauncherApp;
import com.micatechnologies.minecraft.forgelauncher.LauncherConfiguration;
import com.micatechnologies.minecraft.forgelauncher.LauncherConstants;
import com.micatechnologies.minecraft.forgelauncher.utilities.GUIUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.Logger;
import com.micatechnologies.minecraft.forgelauncher.utilities.SystemUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.Pair;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

public class SettingsGUI extends GenericGUI {

    @FXML
    JFXComboBox< String > minRamBox;

    @FXML
    JFXComboBox< String > maxRamBox;

    @FXML
    JFXCheckBox debugCheckBox;

    @FXML
    JFXCheckBox windowResizeCheckBox;

    @FXML
    JFXButton resetLauncherBtn;

    @FXML
    JFXButton resetRuntimeBtn;

    @FXML
    JFXButton saveBtn;

    @FXML
    JFXButton returnBtn;

    @FXML
    Label versionLabel;

    boolean dirty = false;

    @Override
    String getFXMLResourcePath() {
        return "settingsGUI.fxml";
    }

    @Override
    Pair< Integer, Integer > getWindowSize() {
        return new Pair<>( 600, 600 );
    }

    @Override
    void setupWindow() {
        // Configure window close
        currentJFXStage.setOnCloseRequest( windowEvent -> {
            windowEvent.consume();
            returnBtn.fire();
        } );

        // Configure return button
        returnBtn.setOnAction( actionEvent -> SystemUtils.spawnNewTask( () -> {
            if ( dirty ) {
                int response = GUIUtils.showQuestionMessage( "Save?", "Unsaved Changes", "Are you sure you want to exit without saving changes?", "Save", "Exit", getCurrentJFXStage() );
                if ( response == 1 ) {
                    GUIUtils.JFXPlatformRun( () -> saveBtn.fire() );
                    close();
                }
                else if ( response == 2 ) {
                    close();
                }
            }
            else {
                close();
            }
        } ) );

        // Configure save button
        saveBtn.setOnAction( actionEvent -> SystemUtils.spawnNewTask( () -> {
            // Store min ram to config
            LauncherApp.getLauncherConfig().setMinRAM( LauncherConfiguration.MIN_RAM_OPTIONS[ minRamBox.getSelectionModel().getSelectedIndex() ] );

            // Store max ram to config
            LauncherApp.getLauncherConfig().setMaxRAM( LauncherConfiguration.MAX_RAM_OPTIONS[ maxRamBox.getSelectionModel().getSelectedIndex() ] );

            // Store debug mode to config
            LauncherApp.getLauncherConfig().setDebug( debugCheckBox.isSelected() );

            // Store resizable windows to config
            LauncherApp.getLauncherConfig().setResizableguis( windowResizeCheckBox.isSelected() );

            // Save config to disk
            LauncherApp.saveConfig();

            // Reset dirty flag (changes have been saved)
            setEdited( false );

            // Change save button text to indicate successful save
            GUIUtils.JFXPlatformRun( () -> saveBtn.setText( "Saved" ) );

            // Force window changes apply
            GUIController.refreshWindowConfiguration();

            // Schedule save button text to revert to normal after 5s
            SystemUtils.spawnNewTask( () -> {
                try {
                    Thread.sleep( 5000 );
                }
                catch ( InterruptedException ignored ) {
                }
                GUIUtils.JFXPlatformRun( () -> saveBtn.setText( "Save" ) );
            } );
        } ) );

        // Configure reset launcher button
        resetLauncherBtn.setOnAction( actionEvent -> SystemUtils.spawnNewTask( () -> {
            int response = GUIUtils.showQuestionMessage( "Continue?", "Entering the Danger Zone", "Are you sure you'd like to reset the launcher? This may take a few minutes!", "Reset", "Back to Safety", getCurrentJFXStage() );
            if ( response != 1 ) {
                return;
            }

            hide();
            try {
                LauncherApp.logoutCurrentUser();
                FileUtils.deleteDirectory( new File( LauncherApp.getInstallPath() ) );
                close();
            }
            catch ( IOException e ) {
                Logger.logError( "An error occurred while resetting the launcher. Will continue to attempt!" );
            }
        } ) );

        // Configure reset runtime button
        resetRuntimeBtn.setOnAction( event -> SystemUtils.spawnNewTask( () -> {
            int response = GUIUtils.showQuestionMessage( "Continue?", "Entering the Danger Zone", "Are you sure you'd like to reset the runtime? This may take a few minutes!", "Reset", "Back to Safety", getCurrentJFXStage() );
            if ( response != 1 ) {
                return;
            }

            hide();
            try {
                LauncherApp.clearLocalJDK();
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to clear previous runtime from disk. Will continue to attempt reset!" );
            }
            LauncherApp.doLocalJDK();
            show();
        } ) );

        // Load version information
        String v = LauncherConstants.LAUNCHER_APPLICATION_VERSION;
        versionLabel.setText( "Version: " + v );

        // Set and configure resizable windows check box
        windowResizeCheckBox.setSelected( LauncherApp.getLauncherConfig().getResizableguis() );
        windowResizeCheckBox.setOnAction( actionEvent -> setEdited( true ) );

        // Set and configure debug mode check box
        debugCheckBox.setSelected( LauncherApp.getLauncherConfig().getDebug() );
        debugCheckBox.setOnAction( actionEvent -> setEdited( true ) );

        // Populate and configure minimum RAM dropdown
        String[] minRAMOptions = new String[ LauncherConfiguration.MIN_RAM_OPTIONS.length ];
        for ( int i = 0; i < minRAMOptions.length; i++ ) {
            minRAMOptions[ i ] = String.valueOf( LauncherConfiguration.MIN_RAM_OPTIONS[ i ] );
        }
        minRamBox.getItems().addAll( minRAMOptions );
        minRamBox.getSelectionModel().select( Doubles.asList( LauncherConfiguration.MIN_RAM_OPTIONS ).indexOf( LauncherApp.getLauncherConfig().getMinRAM() ) );
        minRamBox.getSelectionModel().selectedItemProperty().addListener( ( observable, oldValue, newValue ) -> setEdited( true ) );

        // Populate and configure maximum RAM dropdown
        String[] maxRAMOptions = new String[ LauncherConfiguration.MAX_RAM_OPTIONS.length ];
        for ( int i = 0; i < maxRAMOptions.length; i++ ) {
            maxRAMOptions[ i ] = String.valueOf( LauncherConfiguration.MAX_RAM_OPTIONS[ i ] );
        }
        maxRamBox.getItems().addAll( maxRAMOptions );
        maxRamBox.getSelectionModel().select( Doubles.asList( LauncherConfiguration.MAX_RAM_OPTIONS ).indexOf( LauncherApp.getLauncherConfig().getMaxRAM() ) );
        maxRamBox.getSelectionModel().selectedItemProperty().addListener( ( observable, oldValue, newValue ) -> setEdited( true ) );
    }

    private void setEdited( boolean edited ) {
        if ( org.apache.commons.lang3.SystemUtils.IS_OS_MAC ) getNSWindow().setDocumentEdited( edited );
        dirty = edited;
    }
}
