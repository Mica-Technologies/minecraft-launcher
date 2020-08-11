/*
 * Copyright (c) 2020 Mica Technologies
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.micatechnologies.minecraft.forgelauncher.gui;

import com.jfoenix.controls.*;
import com.micatechnologies.minecraft.forgelauncher.LauncherCore;
import com.micatechnologies.minecraft.forgelauncher.config.ConfigManager;
import com.micatechnologies.minecraft.forgelauncher.consts.LauncherConstants;
import com.micatechnologies.minecraft.forgelauncher.files.LocalPathManager;
import com.micatechnologies.minecraft.forgelauncher.files.RuntimeManager;
import com.micatechnologies.minecraft.forgelauncher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.forgelauncher.game.auth.AuthManager;
import com.micatechnologies.minecraft.forgelauncher.utilities.GUIUtilities;
import com.micatechnologies.minecraft.forgelauncher.files.Logger;
import com.micatechnologies.minecraft.forgelauncher.utilities.SystemUtilities;
import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.OnScreen;
import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.RunsOnJFXThread;
import com.micatechnologies.minecraft.forgelauncher.utilities.objects.Pair;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import org.apache.commons.io.FileUtils;

import java.io.IOException;

/**
 * Launcher settings window class.
 *
 * @author Mica Technologies
 * @version 2.0
 * @editors hawka97
 * @creator hawka97
 * @since 1.0
 */
public class SettingsWindow extends AbstractWindow
{

    @FXML
    @OnScreen
    Spinner< Double > minRamGb;

    @FXML
    @OnScreen
    Spinner< Double > maxRamGb;

    @FXML
    @OnScreen
    JFXCheckBox debugCheckBox;

    @FXML
    @OnScreen
    JFXCheckBox windowResizeCheckBox;

    @FXML
    @OnScreen
    JFXButton resetLauncherBtn;

    @FXML
    @OnScreen
    JFXButton resetRuntimeBtn;

    @FXML
    @OnScreen
    JFXButton saveBtn;

    @FXML
    @OnScreen
    JFXButton returnBtn;

    @FXML
    @OnScreen
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
        return "gui/settingsGUI.fxml";
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
    @RunsOnJFXThread
    void setupWindow() {
        // Set window title
        currentJFXStage.setTitle( LauncherConstants.LAUNCHER_APPLICATION_NAME + " | Settings" );

        // Configure window close
        currentJFXStage.setOnCloseRequest( windowEvent -> {
            windowEvent.consume();
            returnBtn.fire();
        } );

        // Configure return button
        returnBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            if ( dirty ) {
                int response = GUIUtilities.showQuestionMessage( "Save?", "Unsaved Changes",
                                                                 "Are you sure you want to exit without saving changes?",
                                                                 "Save", "Exit", getCurrentJFXStage() );
                if ( response == 1 ) {
                    GUIUtilities.JFXPlatformRun( () -> saveBtn.fire() );
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
        saveBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            // Store min ram to config
            final long minRamMb = ( long ) ( minRamGb.getValue() * 1024 );
            ConfigManager.setMinRam( minRamMb );

            // Store max ram to config
            final long maxRamMb = ( long ) ( maxRamGb.getValue() * 1024 );
            ConfigManager.setMaxRam( maxRamMb );

            // Store debug mode to config
            ConfigManager.setDebugLogging( debugCheckBox.isSelected() );

            // Store resizable windows to config
            ConfigManager.setResizableWindows( windowResizeCheckBox.isSelected() );

            // Reset dirty flag (changes have been saved)
            GUIUtilities.JFXPlatformRun( ()->setEdited( false ) );

            // Change save button text to indicate successful save
            GUIUtilities.JFXPlatformRun( () -> saveBtn.setText( "Saved" ) );

            // Force window changes apply
            GUIController.refreshWindowConfiguration();

            // Schedule save button text to revert to normal after 5s
            SystemUtilities.spawnNewTask( () -> {
                try {
                    Thread.sleep( 5000 );
                }
                catch ( InterruptedException ignored ) {
                    Logger.logDebug( "An error occurred while waiting to reset the save button text from \"Saved\" " +
                                             "to \"Save\"." );
                }
                GUIUtilities.JFXPlatformRun( () -> saveBtn.setText( "Save" ) );
            } );
        } ) );

        // Configure reset launcher button
        resetLauncherBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            int response = GUIUtilities.showQuestionMessage( "Continue?", "Entering the Danger Zone",
                                                             "Are you sure you'd like to reset the launcher? This may take a few minutes!",
                                                             "Reset", "Back to Safety", getCurrentJFXStage() );
            if ( response != 1 ) {
                return;
            }

            hide();
            AuthManager.logout();
            try {
                FileUtils.deleteDirectory(
                        SynchronizedFileManager.getSynchronizedFile( LocalPathManager.getLauncherLocalPath() ) );
            }
            catch ( IOException e ) {
                Logger.logError( "An error occurred while resetting the launcher. Will continue to attempt!" );
            }
            finally {
                LauncherCore.restartApp();
            }
        } ) );

        // Configure reset runtime button
        resetRuntimeBtn.setOnAction( event -> SystemUtilities.spawnNewTask( () -> {
            int response = GUIUtilities.showQuestionMessage( "Continue?", "Entering the Danger Zone",
                                                             "Are you sure you'd like to reset the runtime? This may take a few minutes!",
                                                             "Reset", "Back to Safety", getCurrentJFXStage() );
            if ( response != 1 ) {
                return;
            }

            hide();
            try {
                RuntimeManager.clearJre8();
            }
            catch ( IOException e ) {
                Logger.logError(
                        "The runtime could not be deleted due to an IO exception. Continuing runtime verification..." );
            }
            RuntimeManager.verifyJre8();
            show();
        } ) );

        // Load version information
        String v = LauncherConstants.LAUNCHER_APPLICATION_VERSION;
        versionLabel.setText( "Software Version: " + v );

        // Set and configure resizable windows check box
        windowResizeCheckBox.setSelected( ConfigManager.getResizableWindows() );
        windowResizeCheckBox.setOnAction( actionEvent -> setEdited( true ) );

        // Set and configure debug mode check box
        debugCheckBox.setSelected( ConfigManager.getDebugLogging() );
        debugCheckBox.setOnAction( actionEvent -> setEdited( true ) );

        // Calculate configured RAM amounts in GB
        final double minRamGbVal = ConfigManager.getMinRam() / 1024.0;
        final double maxRamGbVal = ConfigManager.getMaxRam() / 1024.0;

        // Populate and configure minimum RAM dropdown
        minRamGb.setEditable( true );
        double correctedMaxForMin = Math.min( LauncherConstants.SETTINGS_MIN_RAM_MAX, maxRamGbVal );
        minRamGb.setValueFactory(
                new SpinnerValueFactory.DoubleSpinnerValueFactory( LauncherConstants.SETTINGS_MIN_RAM_MIN, correctedMaxForMin,
                                                                   minRamGbVal, 0.1 ) );
        minRamGb.getValueFactory().valueProperty().addListener( ( observable, oldValue, newValue ) -> {
            setEdited( true );
            double newValWithMinForMax = Math.max( LauncherConstants.SETTINGS_MAX_RAM_MIN, newValue );
            ( ( SpinnerValueFactory.DoubleSpinnerValueFactory ) maxRamGb.getValueFactory() ).setMin(
                    newValWithMinForMax );
        } );

        // Populate and configure maximum RAM dropdown
        maxRamGb.setEditable( true );
        double correctedMinForMax = Math.max(LauncherConstants.SETTINGS_MAX_RAM_MIN, minRamGbVal);
        maxRamGb.setValueFactory(
                new SpinnerValueFactory.DoubleSpinnerValueFactory( correctedMinForMax, LauncherConstants.SETTINGS_MAX_RAM_MAX,
                                                                   maxRamGbVal, 0.1 ) );
        maxRamGb.getValueFactory().valueProperty().addListener( ( observable, oldValue, newValue ) -> {
            setEdited( true );
            double newValWithMaxForMin = Math.min( LauncherConstants.SETTINGS_MIN_RAM_MAX, newValue );
            ( ( SpinnerValueFactory.DoubleSpinnerValueFactory ) minRamGb.getValueFactory() ).setMax(
                    newValWithMaxForMin );
        } );
    }

    private void setEdited( boolean edited ) {
        if ( org.apache.commons.lang3.SystemUtils.IS_OS_MAC ) {
            getNSWindow().setDocumentEdited( edited );
        }
        dirty = edited;
    }
}
