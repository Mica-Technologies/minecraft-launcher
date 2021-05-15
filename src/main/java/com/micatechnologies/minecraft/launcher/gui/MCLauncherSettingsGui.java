/*
 * Copyright (c) 2021 Mica Technologies
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

package com.micatechnologies.minecraft.launcher.gui;

import com.micatechnologies.minecraft.launcher.LauncherCore;
import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.RuntimeManager;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.game.auth.AuthManager;
import com.micatechnologies.minecraft.launcher.utilities.GUIUtilities;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import com.micatechnologies.minecraft.launcher.utilities.annotations.OnScreen;
import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXCheckbox;
import io.github.palexdev.materialfx.controls.MFXToggleButton;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.stage.Stage;
import org.codehaus.plexus.util.FileUtils;

import java.io.IOException;

public class MCLauncherSettingsGui extends MCLauncherAbstractGui
{
    @FXML
    @OnScreen
    Spinner< Double > minRamGb;

    @FXML
    @OnScreen
    Spinner< Double > maxRamGb;

    @FXML
    @OnScreen
    MFXToggleButton debugCheckBox;

    @FXML
    @OnScreen
    MFXToggleButton windowResizeCheckBox;

    @FXML
    @OnScreen
    MFXButton resetLauncherBtn;

    @FXML
    @OnScreen
    MFXButton resetRuntimeBtn;

    @FXML
    @OnScreen
    MFXButton saveBtn;

    @FXML
    @OnScreen
    MFXButton returnBtn;

    @FXML
    @OnScreen
    Label versionLabel;

    boolean dirty = false;

    /**
     * Constructor for abstract scene class that initializes {@link #scene} and sets <code>this</code> as the FXML
     * controller.
     *
     * @throws IOException if unable to load FXML file specified
     */
    public MCLauncherSettingsGui( Stage stage ) throws IOException {
        super( stage );
    }

    /**
     * Abstract method: This method must return the resource path for the JavaFX scene FXML file.
     *
     * @return JavaFX scene FXML resource path
     */
    @Override
    String getSceneFxmlPath() {
        return "gui/settingsGUI.fxml";
    }

    /**
     * Abstract method: This method must return the name of the JavaFX scene.
     *
     * @return Java FX scene name
     */
    @Override
    String getSceneName() {
        return "Settings";
    }

    /**
     * Abstract method: This method must perform initialization and setup of the scene and @FXML components.
     */
    @Override
    void setup() {
        // Configure window close
        stage.setOnCloseRequest( windowEvent -> {
            windowEvent.consume();
            returnBtn.fire();
        } );

        // Configure return button
        returnBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            if ( dirty ) {
                int response = GUIUtilities.showQuestionMessage( "Save?", "Unsaved Changes",
                                                                 "Are you sure you want to return without saving " +
                                                                         "changes?",
                                                                 "Save", "Return", stage );
                if ( response == 1 ) {
                    GUIUtilities.JFXPlatformRun( () -> saveBtn.fire() );
                    try {
                        MCLauncherGuiController.goToMainGui();
                    }
                    catch ( IOException e ) {
                        Logger.logError( "Unable to open the main application GUI!" );
                        Logger.logThrowable( e );
                    }
                }
                else if ( response == 2 ) {
                    try {
                        MCLauncherGuiController.goToMainGui();
                    }
                    catch ( IOException e ) {
                        Logger.logError( "Unable to open the main application GUI!" );
                        Logger.logThrowable( e );
                    }
                }
            }
            else {
                try {
                    MCLauncherGuiController.goToMainGui();
                }
                catch ( IOException e ) {
                    Logger.logError( "Unable to open the main application GUI!" );
                    Logger.logThrowable( e );
                }
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
            GUIUtilities.JFXPlatformRun( () -> stage.setResizable( ConfigManager.getResizableWindows() ) );

            // Reset dirty flag (changes have been saved)
            GUIUtilities.JFXPlatformRun( () -> setEdited( false ) );

            // Change save button text to indicate successful save
            GUIUtilities.JFXPlatformRun( () -> saveBtn.setText( "Saved" ) );

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
                                                             "Reset", "Back to Safety", stage );
            if ( response != 1 ) {
                return;
            }

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
            if ( dirty ) {
                int response = GUIUtilities.showQuestionMessage( "Save?", "Unsaved Changes",
                                                                 "Are you sure you want to reset the runtime without " +
                                                                         "saving changes?",
                                                                 "Save & Reset Runtime", "Reset Runtime", stage );
                // Save first
                if ( response == 1 ) {
                    GUIUtilities.JFXPlatformRun( () -> saveBtn.fire() );
                }
                // Cancel
                else if ( response != 2 ) {
                    return;
                }
            }

            int response = GUIUtilities.showQuestionMessage( "Continue?", "Entering the Danger Zone",
                                                             "Are you sure you'd like to reset the runtime? This may take a few minutes!",
                                                             "Reset", "Back to Safety", stage );
            if ( response != 1 ) {
                return;
            }

            try {
                RuntimeManager.clearJre8();
            }
            catch ( IOException e ) {
                Logger.logError(
                        "The runtime could not be deleted due to an IO exception. Continuing runtime verification..." );
            }
            RuntimeManager.verifyJre8();

            //Return to the settings window
            try {
                MCLauncherGuiController.goToSettingsGui();
            }
            catch ( IOException e ) {
                Logger.logError( "Oops! Unable to reload settings GUI" );
                Logger.logThrowable( e );
                LauncherCore.closeApp();
            }
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
                new SpinnerValueFactory.DoubleSpinnerValueFactory( LauncherConstants.SETTINGS_MIN_RAM_MIN,
                                                                   correctedMaxForMin, minRamGbVal, 0.1 ) );
        minRamGb.getValueFactory().valueProperty().addListener( ( observable, oldValue, newValue ) -> {
            setEdited( true );
            double newValWithMinForMax = Math.max( LauncherConstants.SETTINGS_MAX_RAM_MIN, newValue );
            ( ( SpinnerValueFactory.DoubleSpinnerValueFactory ) maxRamGb.getValueFactory() ).setMin(
                    newValWithMinForMax );
        } );

        // Populate and configure maximum RAM dropdown
        maxRamGb.setEditable( true );
        double correctedMinForMax = Math.max( LauncherConstants.SETTINGS_MAX_RAM_MIN, minRamGbVal );
        maxRamGb.setValueFactory( new SpinnerValueFactory.DoubleSpinnerValueFactory( correctedMinForMax,
                                                                                     LauncherConstants.SETTINGS_MAX_RAM_MAX,
                                                                                     maxRamGbVal, 0.1 ) );
        maxRamGb.getValueFactory().valueProperty().addListener( ( observable, oldValue, newValue ) -> {
            setEdited( true );
            double newValWithMaxForMin = Math.min( LauncherConstants.SETTINGS_MIN_RAM_MAX, newValue );
            ( ( SpinnerValueFactory.DoubleSpinnerValueFactory ) minRamGb.getValueFactory() ).setMax(
                    newValWithMaxForMin );
        } );
    }

    /**
     * Abstract method: This method must perform preparations of the environment, such as enabling menu bars, context
     * menus, or other OS-specific enhancements.
     */
    @Override
    void loadEnvironment() {

    }

    @Override
    void afterShow() {

    }

    /**
     * Abstract method: This method returns a boolean indicating if a warning should be shown to the user before closing
     * the window while displaying the stage/GUI.
     *
     * @return boolean indicating if window close warning should be shown
     */
    @Override
    boolean warnOnExit() {
        return dirty;
    }

    private void setEdited( boolean edited ) {
        dirty = edited;
    }
}
