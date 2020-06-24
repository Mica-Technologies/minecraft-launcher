package com.micatechnologies.minecraft.forgelauncher.gui;


import com.google.common.primitives.Doubles;
import com.jfoenix.controls.*;
import com.micatechnologies.minecraft.forgelauncher.LauncherApp;
import com.micatechnologies.minecraft.forgelauncher.config.ConfigurationManager;
import com.micatechnologies.minecraft.forgelauncher.LauncherConstants;
import com.micatechnologies.minecraft.forgelauncher.utilities.GuiUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.LogUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.SystemUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.OnScreen;
import com.micatechnologies.minecraft.forgelauncher.utilities.objects.Pair;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

/**
 * Launcher settings window class.
 *
 * @author Mica Technologies
 * @version 2.0
 * @editors hawka97
 * @since 1.0
 * @creator hawka97
 */
public class SettingsWindow extends AbstractWindow
{

    @FXML @OnScreen
    JFXComboBox< String > minRamBox;

    @FXML @OnScreen
    JFXComboBox< String > maxRamBox;

    @FXML @OnScreen
    JFXCheckBox debugCheckBox;

    @FXML @OnScreen
    JFXCheckBox windowResizeCheckBox;

    @FXML @OnScreen
    JFXButton resetLauncherBtn;

    @FXML @OnScreen
    JFXButton resetRuntimeBtn;

    @FXML @OnScreen
    JFXButton saveBtn;

    @FXML  @OnScreen
    JFXButton returnBtn;

    @FXML @OnScreen
    Label versionLabel;

    boolean dirty = false;

    /**
     * Implementation of abstract method that returns the file name of the FXML associated with this class.
     *
     * @return FXML file name
     *
     * @since 1.0
     */
    @Override
    String getFXMLResourcePath() {
        return "settingsGUI.fxml";
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
        // Configure window close
        currentJFXStage.setOnCloseRequest( windowEvent -> {
            windowEvent.consume();
            returnBtn.fire();
        } );

        // Configure return button
        returnBtn.setOnAction( actionEvent -> SystemUtils.spawnNewTask( () -> {
            if ( dirty ) {
                int response = GuiUtils.showQuestionMessage( "Save?", "Unsaved Changes", "Are you sure you want to exit without saving changes?", "Save", "Exit", getCurrentJFXStage() );
                if ( response == 1 ) {
                    GuiUtils.JFXPlatformRun( () -> saveBtn.fire() );
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
            LauncherApp.getLauncherConfig().setMinRAM( ConfigurationManager.MIN_RAM_OPTIONS[ minRamBox.getSelectionModel().getSelectedIndex() ] );

            // Store max ram to config
            LauncherApp.getLauncherConfig().setMaxRAM( ConfigurationManager.MAX_RAM_OPTIONS[ maxRamBox.getSelectionModel().getSelectedIndex() ] );

            // Store debug mode to config
            LauncherApp.getLauncherConfig().setDebug( debugCheckBox.isSelected() );

            // Store resizable windows to config
            LauncherApp.getLauncherConfig().setResizableguis( windowResizeCheckBox.isSelected() );

            // Save config to disk
            LauncherApp.saveConfig();

            // Reset dirty flag (changes have been saved)
            setEdited( false );

            // Change save button text to indicate successful save
            GuiUtils.JFXPlatformRun( () -> saveBtn.setText( "Saved" ) );

            // Force window changes apply
            GUIController.refreshWindowConfiguration();

            // Schedule save button text to revert to normal after 5s
            SystemUtils.spawnNewTask( () -> {
                try {
                    Thread.sleep( 5000 );
                }
                catch ( InterruptedException ignored ) {
                    LogUtils.logDebug( "An error occurred while waiting to reset the save button text from \"Saved\" " +
                                               "to \"Save\"." );
                }
                GuiUtils.JFXPlatformRun( () -> saveBtn.setText( "Save" ) );
            } );
        } ) );

        // Configure reset launcher button
        resetLauncherBtn.setOnAction( actionEvent -> SystemUtils.spawnNewTask( () -> {
            int response = GuiUtils.showQuestionMessage( "Continue?", "Entering the Danger Zone", "Are you sure you'd like to reset the launcher? This may take a few minutes!", "Reset", "Back to Safety", getCurrentJFXStage() );
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
                LogUtils.logError( "An error occurred while resetting the launcher. Will continue to attempt!" );
            }
        } ) );

        // Configure reset runtime button
        resetRuntimeBtn.setOnAction( event -> SystemUtils.spawnNewTask( () -> {
            int response = GuiUtils.showQuestionMessage( "Continue?", "Entering the Danger Zone", "Are you sure you'd like to reset the runtime? This may take a few minutes!", "Reset", "Back to Safety", getCurrentJFXStage() );
            if ( response != 1 ) {
                return;
            }

            hide();
            try {
                LauncherApp.clearLocalJDK();
            }
            catch ( IOException e ) {
                LogUtils.logError( "Unable to clear previous runtime from disk. Will continue to attempt reset!" );
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
        String[] minRAMOptions = new String[ ConfigurationManager.MIN_RAM_OPTIONS.length ];
        for ( int i = 0; i < minRAMOptions.length; i++ ) {
            minRAMOptions[ i ] = String.valueOf( ConfigurationManager.MIN_RAM_OPTIONS[ i ] );
        }
        minRamBox.getItems().addAll( minRAMOptions );
        minRamBox.getSelectionModel().select( Doubles.asList( ConfigurationManager.MIN_RAM_OPTIONS ).indexOf( LauncherApp.getLauncherConfig().getMinRAM() ) );
        minRamBox.getSelectionModel().selectedItemProperty().addListener( ( observable, oldValue, newValue ) -> setEdited( true ) );

        // Populate and configure maximum RAM dropdown
        String[] maxRAMOptions = new String[ ConfigurationManager.MAX_RAM_OPTIONS.length ];
        for ( int i = 0; i < maxRAMOptions.length; i++ ) {
            maxRAMOptions[ i ] = String.valueOf( ConfigurationManager.MAX_RAM_OPTIONS[ i ] );
        }
        maxRamBox.getItems().addAll( maxRAMOptions );
        maxRamBox.getSelectionModel().select( Doubles.asList( ConfigurationManager.MAX_RAM_OPTIONS ).indexOf( LauncherApp.getLauncherConfig().getMaxRAM() ) );
        maxRamBox.getSelectionModel().selectedItemProperty().addListener( ( observable, oldValue, newValue ) -> setEdited( true ) );
    }

    private void setEdited( boolean edited ) {
        if ( org.apache.commons.lang3.SystemUtils.IS_OS_MAC ) getNSWindow().setDocumentEdited( edited );
        dirty = edited;
    }
}
