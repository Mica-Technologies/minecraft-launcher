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
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.RuntimeManager;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.utilities.AnnouncementManager;
import com.micatechnologies.minecraft.launcher.utilities.DiscordRpcUtility;
import com.micatechnologies.minecraft.launcher.utilities.GUIUtilities;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXComboBox;
import io.github.palexdev.materialfx.controls.MFXProgressBar;
import io.github.palexdev.materialfx.controls.MFXToggleButton;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.RowConstraints;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import me.cortex.jarscanner.Constants;
import me.cortex.jarscanner.Main;
import me.cortex.jarscanner.Progress;
import me.cortex.jarscanner.Results;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.Precision;
import org.codehaus.plexus.util.FileUtils;
import oshi.SystemInfo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.function.Function;

public class MCLauncherSettingsGui extends MCLauncherAbstractGui
{
    @SuppressWarnings( "unused" )
    @FXML
    Spinner< Double > minRamGb;

    @SuppressWarnings( "unused" )
    @FXML
    Spinner< Double > maxRamGb;

    @SuppressWarnings( "unused" )
    @FXML
    MFXToggleButton debugCheckBox;

    @SuppressWarnings( "unused" )
    @FXML
    MFXToggleButton windowResizeCheckBox;

    @SuppressWarnings( "unused" )
    @FXML
    MFXToggleButton discordCheckBox;

    @SuppressWarnings( "unused" )
    @FXML
    MFXToggleButton enhancedLoggingCheckBox;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton scanFolderBtn;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton scanBtn;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton resetLauncherBtn;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton resetRuntimeBtn;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton saveBtn;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton returnBtn;

    @SuppressWarnings( "unused" )
    @FXML
    Label versionLabel;

    @SuppressWarnings( "unused" )
    @FXML
    Label sysRamLabel;

    @SuppressWarnings( "unused" )
    @FXML
    MFXComboBox< String > themeSelection;

    /**
     * Announcement banner.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    Label announcement;

    /**
     * Announcement banner row constraints.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    RowConstraints announcementRow;

    /**
     * Progress bar. Displays the scan's progress on a scale from 0 to 1.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXProgressBar scanProgressBar;

    /**
     * Scan output label.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    Label scanOutputLabel;

    /**
     * Scan output label.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    Label scanFolderLabel;

    boolean dirty            = false;
    boolean scanning         = false;
    boolean scanningCanceled = false;
    File    scanFolder       = null;

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
     * Constructor for abstract scene class that initializes {@link #scene} and sets <code>this</code> as the FXML
     * controller.
     *
     * @throws IOException if unable to load FXML file specified
     */
    @SuppressWarnings( "unused" )
    public MCLauncherSettingsGui( Stage stage, double width, double height ) throws IOException {
        super( stage, width, height );
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
                                                                         "changes?", "Save", "Return", stage );
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

            // Store Discord RPC enable to config and stop Discord RPC if required
            ConfigManager.setDiscordRpcEnable( discordCheckBox.isSelected() );
            if ( !discordCheckBox.isSelected() ) {
                DiscordRpcUtility.exit();
            }

            // Store resizable windows to config
            ConfigManager.setResizableWindows( windowResizeCheckBox.isSelected() );
            GUIUtilities.JFXPlatformRun( () -> stage.setResizable( ConfigManager.getResizableWindows() ) );

            // Store enhanced logging to config
            ConfigManager.setEnhancedLogging( enhancedLoggingCheckBox.isSelected() );

            // Store theme selection
            if ( ConfigConstants.ALLOWED_THEMES.contains( themeSelection.getSelectedItem() ) ) {
                ConfigManager.setTheme( themeSelection.getSelectedItem() );
                MCLauncherGuiController.forceThemeRefresh();
            }

            // Reset dirty flag (changes have been saved)
            setEdited( false );

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

            try {
                LauncherCore.cleanupApp();
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( "Unable to cleanup launcher systems before resetting the launcher. Some " +
                                                 "files may not be removed or reset!" );
            }
            try {
                FileUtils.deleteDirectory(
                        SynchronizedFileManager.getSynchronizedFile( LocalPathManager.getLauncherLocalPath() ) );
            }
            catch ( IOException e ) {
                Logger.logError( "An error prevented some or all of the launcher files from being removed or reset!" );
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
                                                                         "saving changes?", "Save & Reset Runtime",
                                                                 "Reset Runtime", stage );
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

        // Display announcements if present
        String announcementText = AnnouncementManager.getAnnouncementConfig();
        if ( announcementText.length() > 0 ) {
            announcement.setText( announcementText );
            announcement.setMinHeight( 30 );
            announcementRow.setMinHeight( 30 );
        }
        else {
            announcement.setMaxHeight( 0 );
            announcementRow.setMaxHeight( 0 );
        }

        // Load version information
        String jreVersionInfo = "Game JRE Version: " + RuntimeManager.getJre8Version();
        if ( LauncherConstants.LAUNCHER_IS_DEV ) {
            versionLabel.setText( "Software Version: DEVELOPMENT MODE\n" + jreVersionInfo );
        }
        else {
            versionLabel.setText(
                    "Software Version: " + LauncherConstants.LAUNCHER_APPLICATION_VERSION + "\n" + jreVersionInfo );
        }

        // Set and configure resizable windows check box
        windowResizeCheckBox.setSelected( ConfigManager.getResizableWindows() );
        windowResizeCheckBox.setOnAction( actionEvent -> setEdited( true ) );

        // Set and configure debug mode check box
        debugCheckBox.setSelected( ConfigManager.getDebugLogging() );
        debugCheckBox.setOnAction( actionEvent -> setEdited( true ) );
        debugCheckBox.setDisable( LauncherConstants.LAUNCHER_IS_DEV );

        // Set and configure Discord RPC check box
        discordCheckBox.setSelected( ConfigManager.getDiscordRpcEnable() );
        discordCheckBox.setOnAction( actionEvent -> setEdited( true ) );
        discordCheckBox.setDisable( LauncherConstants.LAUNCHER_IS_DEV );

        // Populate theme selection dropdown
        themeSelection.getItems().clear();
        themeSelection.getItems().addAll( ConfigConstants.ALLOWED_THEMES );

        // Set and configure enhanced logging check box
        enhancedLoggingCheckBox.setSelected( ConfigManager.getEnhancedLogging() );
        enhancedLoggingCheckBox.setOnAction( actionEvent -> setEdited( true ) );

        // Load system RAM config label
        SystemInfo systemInfo = new SystemInfo();
        long memTotalRaw = systemInfo.getHardware().getMemory().getTotal();
        long memAvailRaw = systemInfo.getHardware().getMemory().getAvailable();
        double memTotal = memTotalRaw / 1024.0 / 1024.0 / 1024.0;
        double memAvail = memAvailRaw / 1024.0 / 1024.0 / 1024.0;
        double memUsed = memTotal - memAvail;
        sysRamLabel.setText( "You have " +
                                     Precision.round( memTotal, 2 ) +
                                     " GB RAM. You're currently using " +
                                     Precision.round( memUsed, 2 ) +
                                     " GB" +
                                     "." );

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

        // Configure scan buttons/labels
        scanning = false;
        scanFolder = SynchronizedFileManager.getSynchronizedFile( LocalPathManager.getLauncherLocalPath() );
        scanFolderLabel.setText( "Scan Folder: " + scanFolder );
        scanFolderBtn.setVisible( true );
        scanProgressBar.setVisible( false );
        scanOutputLabel.setVisible( false );
        scanFolderBtn.setOnAction( actionEvent -> {
            DirectoryChooser directoryChooser = new DirectoryChooser();
            directoryChooser.setTitle( "Select a folder to scan" );
            directoryChooser.setInitialDirectory( scanFolder );
            File selectedDirectory = directoryChooser.showDialog( MCLauncherGuiController.getTopStageOrNull() );
            if ( selectedDirectory != null ) {
                scanFolder = selectedDirectory;
                scanFolderLabel.setText( "Scan Folder: " + scanFolder );
            }
        } );
        scanBtn.setOnAction( actionEvent -> {
            if ( scanning ) {
                Main.cancelScanIfRunning();
                scanningCanceled = true;
            }
            else {
                if ( scanFolder != null ) {
                    SystemUtilities.spawnNewTask( () -> {
                        // Update GUI
                        GUIUtilities.JFXPlatformRun( () -> {
                            scanBtn.setText( "Cancel" );
                            scanFolderBtn.setDisable( true );
                            scanFolderBtn.setVisible( false );
                            scanProgressBar.setVisible( true );
                            scanOutputLabel.setVisible( true );
                            saveBtn.setDisable( true );
                            returnBtn.setDisable( true );
                        } );

                        // Run scan
                        scanning = true;
                        try {
                            scanProgressBar.setProgress( 0.0 );
                            scanSelectedFolder();
                            scanProgressBar.setProgress( 1.0 );
                        }
                        catch ( Exception e ) {
                            scanOutputLabel.setText( "Scan failed (exception)! See log file for details." );
                            Logger.logError( "Scan failed (exception)!" );
                            Logger.logThrowable( e );
                        }
                        scanning = false;

                        // Restore GUI
                        GUIUtilities.JFXPlatformRun( () -> {
                            saveBtn.setDisable( false );
                            returnBtn.setDisable( false );
                            scanProgressBar.setVisible( false );
                            scanFolderBtn.setVisible( true );
                            scanBtn.setText( "Scan" );
                            scanFolderBtn.setDisable( false );
                        } );
                    } );
                }
                else {
                    Logger.logWarning( "No folder selected. Select a folder to scan!" );
                }
            }
        } );
    }

    /**
     * Scans the user-selected folder for any infections identified by the {@link me.cortex.jarscanner.Detector} class.
     *
     * @throws ModpackException     if any infections are found
     * @throws IOException          if any I/O errors occur while scanning
     * @throws InterruptedException if the current thread is interrupted while scanning
     */
    public void scanSelectedFolder() throws ModpackException, IOException, InterruptedException {
        int halfCoreCount = Runtime.getRuntime().availableProcessors() / 2;
        int scanCoreCount = Math.min( 1, halfCoreCount );
        boolean emitWalkErrors = true;
        Function< String, String > logOutput = ( out ) -> {
            String processedOut = out.replace( Constants.ANSI_RED, "" )
                                     .replace( Constants.ANSI_GREEN, "" )
                                     .replace( Constants.ANSI_WHITE, "" )
                                     .replace( Constants.ANSI_RESET, "" );
            GUIUtilities.JFXPlatformRun( () -> scanOutputLabel.setText( processedOut ) );
            return out;
        };
        Function< Progress, Progress > progressOutput = ( progress ) -> {
            if (progress.getTotalFiles() <= 0.0){
                GUIUtilities.JFXPlatformRun( () -> scanProgressBar.setProgress( ProgressIndicator.INDETERMINATE_PROGRESS ) );
            } else{
                final double currentProgress = progress.getFilesProcessed() / progress.getTotalFiles();
                GUIUtilities.JFXPlatformRun( () -> scanProgressBar.setProgress( currentProgress ) );
            }
            return progress;
        };
        Results scanResults = Main.run( scanCoreCount, scanFolder.toPath(), emitWalkErrors, new ArrayList<>(),
                                        logOutput, progressOutput );
        if ( scanResults.getStage1Detections() != null &&
                !scanResults.getStage1Detections().isEmpty() &&
                scanResults.getStage2Detections() != null &&
                !scanResults.getStage2Detections().isEmpty() ) {
            int infectionCount = scanResults.getStage1Detections().size() + scanResults.getStage2Detections().size();
            logOutput.apply( "Stage 1 and 2 infections found: " + infectionCount );
        }
        else if ( scanResults.getStage1Detections() != null && !scanResults.getStage1Detections().isEmpty() ) {
            int infectionCount = scanResults.getStage1Detections().size();
            logOutput.apply( "Stage 1 infections found: " + infectionCount );
        }
        else if ( scanResults.getStage2Detections() != null && !scanResults.getStage2Detections().isEmpty() ) {
            int infectionCount = scanResults.getStage2Detections().size();
            logOutput.apply( "Stage 2 infections found: " + infectionCount );
        }
        else if ( scanningCanceled ) {
            logOutput.apply( "Scan canceled!" );
            scanningCanceled = false;
        }
        else {
            logOutput.apply( "No infections found!" );
        }
    }

    @Override
    void afterShow() {
        // Select current theme in dropdown
        String currentConfigTheme = ConfigManager.getTheme();

        // Convert to standard capital first letter, lowercase rest format
        String safeCurrentConfigTheme = StringUtils.capitalize( currentConfigTheme.toLowerCase() );

        // Check if valid option
        if ( !ConfigConstants.ALLOWED_THEMES.contains( safeCurrentConfigTheme ) ) {
            safeCurrentConfigTheme = ConfigConstants.THEME_AUTOMATIC;
            ConfigManager.setTheme( ConfigConstants.THEME_AUTOMATIC );
        }

        themeSelection.selectItem( safeCurrentConfigTheme );
        themeSelection.getSelectionModel().selectItem( safeCurrentConfigTheme );

        // Add theme selection change listener
        themeSelection.getSelectionModel()
                      .selectedIndexProperty()
                      .addListener( ( observable, oldValue, newValue ) -> setEdited( true ) );
    }

    @Override
    void cleanup() {

    }

    private void setEdited( boolean edited ) {
        dirty = edited;
    }
}
