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
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager;
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
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXComboBox;
import io.github.palexdev.materialfx.controls.MFXProgressBar;
import io.github.palexdev.materialfx.controls.MFXToggleButton;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
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
    private javafx.beans.value.ChangeListener< Double > minRamListener;
    private javafx.beans.value.ChangeListener< Double > maxRamListener;

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
    MFXToggleButton inGameConsoleCheckBox;

    @SuppressWarnings( "unused" )
    @FXML
    MFXToggleButton lwjglArmPatchCheckBox;

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
    MFXButton manageRuntimeBtn;

    @SuppressWarnings( "unused" )
    @FXML
    MFXToggleButton proxyEnableCheckBox;

    @SuppressWarnings( "unused" )
    @FXML
    javafx.scene.control.TextField proxyHostField;

    @SuppressWarnings( "unused" )
    @FXML
    Spinner< Integer > proxyPortSpinner;

    @SuppressWarnings( "unused" )
    @FXML
    MFXComboBox< String > proxyTypeSelection;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton exportSettingsBtn;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton importSettingsBtn;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton openFolderBtn;

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

    @SuppressWarnings( "unused" )
    @FXML
    MFXComboBox< String > jvmPresetSelection;

    /**
     * Announcement banner.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    Label announcement;

    /**
     * Help button in the top navbar. The button is declared in FXML to suppress the
     * corner-overlay injection done by {@link MCLauncherGuiWindow}; we wire its click
     * handler here using the same pattern as the main menu so the help topic comes
     * from {@link #getHelpTopic()}.
     *
     * @since 3.3
     */
    @SuppressWarnings( "unused" )
    @FXML
    Label helpBtn;

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

    /**
     * Navigation buttons for settings category sidebar.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton navAccount, navGame, navAppearance, navAdvanced, navNetwork, navSecurity, navSystem, navAbout;

    /**
     * StackPane containing the category content panes.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    StackPane settingsContent;

    /**
     * About / Attributions tab FXML controls.
     *
     * @since 3.2
     */
    @SuppressWarnings( "unused" ) @FXML Label     aboutAppNameLabel;
    @SuppressWarnings( "unused" ) @FXML Label     aboutVersionLabel;
    @SuppressWarnings( "unused" ) @FXML MFXButton aboutWebsiteBtn;
    @SuppressWarnings( "unused" ) @FXML MFXButton aboutSourceBtn;

    /**
     * Account tab FXML controls.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    javafx.scene.image.ImageView accountAvatar;

    @SuppressWarnings( "unused" )
    @FXML
    Label accountNameLabel, accountUuidLabel;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton minecraftNetBtn, msAccountBtn, logoutBtn;

    /**
     * Array of nav buttons in category order, populated during setup.
     */
    private MFXButton[] navButtons;

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
        // Configure window close -- X button should close the app, not navigate back
        stage.setOnCloseRequest( windowEvent -> {
            windowEvent.consume();
            SystemUtilities.spawnNewTask( () -> {
                if ( hasUnsavedChanges() ) {
                    int response = GUIUtilities.showQuestionMessage( "Unsaved Changes", "You have unsaved changes",
                                                                     "Would you like to save before closing?",
                                                                     "Save & Close", "Close Without Saving", stage );
                    if ( response == 1 ) {
                        GUIUtilities.JFXPlatformRun( () -> saveBtn.fire() );
                        LauncherCore.closeApp();
                    }
                    else if ( response == 2 ) {
                        LauncherCore.closeApp();
                    }
                    // Cancel (0) -- do nothing, stay on settings
                }
                else {
                    LauncherCore.closeApp();
                }
            } );
        } );

        // Configure return button
        returnBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            if ( hasUnsavedChanges() ) {
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
            ConfigManager.setInGameConsoleEnable( inGameConsoleCheckBox.isSelected() );

            // Store LWJGL ARM64 patching to config
            ConfigManager.setLwjglArmPatchEnable( lwjglArmPatchCheckBox.isSelected() );

            // Store theme selection
            if ( ConfigConstants.ALLOWED_THEMES.contains( themeSelection.getSelectedItem() ) ) {
                ConfigManager.setTheme( themeSelection.getSelectedItem() );
                MCLauncherGuiController.forceThemeRefresh();
            }

            // Store proxy settings
            ConfigManager.setProxyEnable( proxyEnableCheckBox.isSelected() );
            ConfigManager.setProxyHost( proxyHostField.getText() );
            ConfigManager.setProxyPort( proxyPortSpinner.getValue() );
            String selectedProxyType = proxyTypeSelection.getSelectedItem();
            if ( selectedProxyType != null ) {
                ConfigManager.setProxyType( selectedProxyType );
            }
            NetworkUtilities.reloadProxy();

            // Store JVM preset selection
            String selectedPreset = jvmPresetSelection.getSelectedItem();
            if ( selectedPreset != null ) {
                for ( int i = 0; i < ConfigConstants.JVM_PRESET_NAMES.length; i++ ) {
                    if ( ConfigConstants.JVM_PRESET_NAMES[i].equals( selectedPreset ) ) {
                        ConfigManager.setCustomJvmArgs( ConfigConstants.JVM_PRESET_ARGS[i] );
                        break;
                    }
                }
            }

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
            if ( hasUnsavedChanges() ) {
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

            // Clear all installed runtimes
            java.util.List< java.util.Map< String, String > > runtimes = RuntimeManager.getInstalledRuntimes();
            for ( java.util.Map< String, String > rt : runtimes ) {
                try {
                    RuntimeManager.clearRuntime( rt.get( "component" ) );
                }
                catch ( IOException e ) {
                    Logger.logError( "Failed to delete " + rt.get( "component" ) + " runtime: " +
                                             e.getMessage() );
                }
            }
            Logger.logStd( "All runtimes cleared. They will be re-downloaded when a modpack is launched." );

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

        // Configure manage runtimes button
        manageRuntimeBtn.setOnAction( event -> SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToRuntimeGui();
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to open runtime management GUI." );
                Logger.logThrowable( e );
            }
        } ) );

        // Configure open launcher folder button
        exportSettingsBtn.setOnAction( event -> {
            javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
            fileChooser.setTitle( "Export Settings" );
            fileChooser.setInitialFileName( "launcher-settings.json" );
            fileChooser.getExtensionFilters().add(
                    new javafx.stage.FileChooser.ExtensionFilter( "JSON Files", "*.json" ) );
            java.io.File file = fileChooser.showSaveDialog( stage );
            if ( file != null ) {
                SystemUtilities.spawnNewTask( () -> {
                    boolean ok = ConfigManager.exportConfig( file );
                    GUIUtilities.JFXPlatformRun( () -> {
                        if ( ok ) {
                            exportSettingsBtn.setText( "Exported!" );
                        }
                        else {
                            exportSettingsBtn.setText( "Failed" );
                        }
                    } );
                    SystemUtilities.spawnNewTask( () -> {
                        try { Thread.sleep( 2000 ); } catch ( InterruptedException ignored ) {}
                        GUIUtilities.JFXPlatformRun( () -> exportSettingsBtn.setText( "Export Settings" ) );
                    } );
                } );
            }
        } );

        importSettingsBtn.setOnAction( event -> {
            javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
            fileChooser.setTitle( "Import Settings" );
            fileChooser.getExtensionFilters().add(
                    new javafx.stage.FileChooser.ExtensionFilter( "JSON Files", "*.json" ) );
            java.io.File file = fileChooser.showOpenDialog( stage );
            if ( file != null ) {
                SystemUtilities.spawnNewTask( () -> {
                    boolean ok = ConfigManager.importConfig( file );
                    GUIUtilities.JFXPlatformRun( () -> {
                        if ( ok ) {
                            importSettingsBtn.setText( "Imported!" );
                            // Reload the settings GUI to reflect imported values
                            try {
                                MCLauncherGuiController.goToSettingsGui();
                            }
                            catch ( IOException e ) {
                                Logger.logErrorSilent( "Unable to reload settings after import." );
                            }
                        }
                        else {
                            importSettingsBtn.setText( "Failed" );
                            SystemUtilities.spawnNewTask( () -> {
                                try { Thread.sleep( 2000 ); } catch ( InterruptedException ignored ) {}
                                GUIUtilities.JFXPlatformRun(
                                        () -> importSettingsBtn.setText( "Import Settings" ) );
                            } );
                        }
                    } );
                } );
            }
        } );

        openFolderBtn.setOnAction( event -> SystemUtilities.spawnNewTask( () -> {
            try {
                java.io.File launcherFolder = SynchronizedFileManager.getSynchronizedFile(
                        LocalPathManager.getLauncherLocalPath() );
                if ( !launcherFolder.exists() ) {
                    launcherFolder.mkdirs();
                }
                java.awt.Desktop.getDesktop().open( launcherFolder );
            }
            catch ( Exception e ) {
                Logger.logError( "Unable to open the launcher folder." );
                Logger.logThrowable( e );
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
        if ( LauncherConstants.LAUNCHER_IS_DEV ) {
            versionLabel.setText( "Software Version: DEVELOPMENT MODE" );
        }
        else {
            versionLabel.setText( "Software Version: " + LauncherConstants.LAUNCHER_APPLICATION_VERSION );
        }

        // Set and configure resizable windows check box
        windowResizeCheckBox.setSelected( ConfigManager.getResizableWindows() );

        // Set and configure debug mode check box
        debugCheckBox.setSelected( ConfigManager.getDebugLogging() );
        debugCheckBox.setDisable( LauncherConstants.LAUNCHER_IS_DEV );

        // Set and configure Discord RPC check box
        discordCheckBox.setSelected( ConfigManager.getDiscordRpcEnable() );
        discordCheckBox.setDisable( LauncherConstants.LAUNCHER_IS_DEV );

        // Populate theme selection dropdown
        themeSelection.getItems().clear();
        themeSelection.getItems().addAll( ConfigConstants.ALLOWED_THEMES );

        // Populate JVM preset selection dropdown
        jvmPresetSelection.getItems().clear();
        jvmPresetSelection.getItems().addAll( ConfigConstants.JVM_PRESET_NAMES );
        // Detect which preset matches the current JVM args (if any)
        String currentArgs = ConfigManager.getCustomJvmArgs();
        boolean matched = false;
        for ( int i = 0; i < ConfigConstants.JVM_PRESET_ARGS.length; i++ ) {
            if ( ConfigConstants.JVM_PRESET_ARGS[i].equals( currentArgs ) ) {
                jvmPresetSelection.selectItem( ConfigConstants.JVM_PRESET_NAMES[i] );
                matched = true;
                break;
            }
        }
        if ( !matched ) {
            // Custom args that don't match any preset — show Performance as closest
            jvmPresetSelection.selectItem( ConfigConstants.JVM_PRESET_PERFORMANCE );
        }

        // Set and configure enhanced logging check box
        enhancedLoggingCheckBox.setSelected( ConfigManager.getEnhancedLogging() );

        // Set and configure in-game console check box
        inGameConsoleCheckBox.setSelected( ConfigManager.getInGameConsoleEnable() );

        // Set and configure LWJGL ARM64 patching check box
        lwjglArmPatchCheckBox.setSelected( ConfigManager.getLwjglArmPatchEnable() );

        // Populate proxy settings
        proxyEnableCheckBox.setSelected( ConfigManager.getProxyEnable() );
        proxyHostField.setText( ConfigManager.getProxyHost() );
        proxyPortSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory( 1, 65535, ConfigManager.getProxyPort() ) );
        proxyPortSpinner.setEditable( true );
        proxyTypeSelection.getItems().clear();
        proxyTypeSelection.getItems().addAll( ConfigConstants.PROXY_TYPES );
        proxyTypeSelection.selectItem( ConfigManager.getProxyType() );

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
        minRamListener = ( observable, oldValue, newValue ) -> {
            double newValWithMinForMax = Math.max( LauncherConstants.SETTINGS_MAX_RAM_MIN, newValue );
            ( ( SpinnerValueFactory.DoubleSpinnerValueFactory ) maxRamGb.getValueFactory() ).setMin(
                    newValWithMinForMax );
        };
        minRamGb.getValueFactory().valueProperty().addListener( minRamListener );

        // Populate and configure maximum RAM dropdown
        maxRamGb.setEditable( true );
        double correctedMinForMax = Math.max( LauncherConstants.SETTINGS_MAX_RAM_MIN, minRamGbVal );
        maxRamGb.setValueFactory( new SpinnerValueFactory.DoubleSpinnerValueFactory( correctedMinForMax,
                                                                                     LauncherConstants.SETTINGS_MAX_RAM_MAX,
                                                                                     maxRamGbVal, 0.1 ) );
        maxRamListener = ( observable, oldValue, newValue ) -> {
            double newValWithMaxForMin = Math.min( LauncherConstants.SETTINGS_MIN_RAM_MAX, newValue );
            ( ( SpinnerValueFactory.DoubleSpinnerValueFactory ) minRamGb.getValueFactory() ).setMax(
                    newValWithMaxForMin );
        };
        maxRamGb.getValueFactory().valueProperty().addListener( maxRamListener );

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

        // Wire up sidebar navigation buttons
        navButtons = new MFXButton[]{ navAccount, navGame, navAppearance, navAdvanced, navNetwork, navSecurity, navSystem, navAbout };
        navAccount.setOnAction( e -> showCategory( 0 ) );
        navGame.setOnAction( e -> showCategory( 1 ) );
        navAppearance.setOnAction( e -> showCategory( 2 ) );
        navAdvanced.setOnAction( e -> showCategory( 3 ) );
        navNetwork.setOnAction( e -> showCategory( 4 ) );
        navSecurity.setOnAction( e -> showCategory( 5 ) );
        navSystem.setOnAction( e -> showCategory( 6 ) );
        navAbout.setOnAction( e -> showCategory( 7 ) );

        setupAboutTab();

        // Populate account tab
        setupAccountTab();

        // Show Account category by default
        showCategory( 0 );
    }

    /**
     * Switches the visible settings category pane to the one at the given index and updates the nav button selection
     * state.
     *
     * @param index the zero-based category index (0=Account, 1=Game, 2=Appearance, 3=Advanced, 4=Network, 5=Security,
     *              6=System, 7=About)
     *
     * @since 3.0
     */
    void showCategory( int index )
    {
        ObservableList< Node > children = settingsContent.getChildren();
        for ( int i = 0; i < children.size(); i++ ) {
            boolean active = ( i == index );
            children.get( i ).setVisible( active );
            children.get( i ).setManaged( active );
        }
        for ( MFXButton btn : navButtons ) {
            btn.getStyleClass().remove( "selected" );
        }
        if ( index >= 0 && index < navButtons.length ) {
            navButtons[index].getStyleClass().add( "selected" );
        }
    }

    /**
     * Populates the Account settings tab with player info, helpful links, and a confirmed logout button.
     *
     * @since 3.0
     */
    private void setupAccountTab()
    {
        // Player profile
        var user = MCLauncherAuthManager.getLoggedInUser();
        accountNameLabel.setText( user.name() );
        accountUuidLabel.setText( "UUID: " + user.uuid() );
        accountAvatar.setImage( new javafx.scene.image.Image(
                com.micatechnologies.minecraft.launcher.consts.GUIConstants.URL_MINECRAFT_USER_ICONS
                        .replace( com.micatechnologies.minecraft.launcher.consts.GUIConstants.URL_MINECRAFT_USER_ICONS_USER_REPLACE_KEY,
                                  user.uuid() ) ) );

        // Helpful links
        minecraftNetBtn.setOnAction( e -> SystemUtilities.spawnNewTask( () -> {
            try {
                java.awt.Desktop.getDesktop().browse( java.net.URI.create( "https://www.minecraft.net/en-us/profile" ) );
            }
            catch ( IOException ex ) {
                Logger.logError( "Unable to open browser." );
                Logger.logThrowable( ex );
            }
        } ) );
        msAccountBtn.setOnAction( e -> SystemUtilities.spawnNewTask( () -> {
            try {
                java.awt.Desktop.getDesktop().browse( java.net.URI.create( "https://account.microsoft.com/" ) );
            }
            catch ( IOException ex ) {
                Logger.logError( "Unable to open browser." );
                Logger.logThrowable( ex );
            }
        } ) );

        // Logout with confirmation
        logoutBtn.setOnAction( e -> {
            javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.CONFIRMATION );
            confirm.setTitle( "Log Out" );
            confirm.setHeaderText( "Are you sure you want to log out?" );
            confirm.setContentText( "You will need to sign in with your Microsoft account again." );
            confirm.initOwner( stage );
            confirm.showAndWait().ifPresent( response -> {
                if ( response == javafx.scene.control.ButtonType.OK ) {
                    MCLauncherAuthManager.logout();
                    LauncherCore.restartApp();
                }
            } );
        } );
    }

    /**
     * Populates the About / Attributions tab. Sets the version label and wires the two action
     * buttons (Visit Website + View Source). The trademark notice and open-source acknowledgments
     * are static text in the FXML — no controller wiring needed.
     */
    private void setupAboutTab()
    {
        if ( aboutVersionLabel != null ) {
            aboutVersionLabel.setText( "Version " + LauncherConstants.LAUNCHER_APPLICATION_VERSION );
        }
        if ( aboutWebsiteBtn != null ) {
            aboutWebsiteBtn.setOnAction( e -> SystemUtilities.spawnNewTask( () -> {
                try {
                    java.awt.Desktop.getDesktop().browse(
                            java.net.URI.create( "https://micatechnologies.com" ) );
                }
                catch ( Exception ex ) {
                    Logger.logWarningSilent( "Couldn't open website: " + ex.getMessage() );
                }
            } ) );
        }
        if ( aboutSourceBtn != null ) {
            aboutSourceBtn.setOnAction( e -> SystemUtilities.spawnNewTask( () -> {
                try {
                    java.awt.Desktop.getDesktop().browse(
                            java.net.URI.create( "https://github.com/MicaTechnologies/Mica-Minecraft-Launcher" ) );
                }
                catch ( Exception ex ) {
                    Logger.logWarningSilent( "Couldn't open source repo: " + ex.getMessage() );
                }
            } ) );
        }
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

        // Install tooltips on all settings controls
        TooltipManager.install( minRamGb, "Minimum RAM allocated to Minecraft (GB). Recommended: 2-4 GB." );
        TooltipManager.install( maxRamGb,
                "Maximum RAM Minecraft can use (GB). Set 4-8 GB for large modpacks." );
        TooltipManager.install( debugCheckBox,
                "Enables verbose logging output. Useful for troubleshooting issues." );
        TooltipManager.install( windowResizeCheckBox,
                "Allows the launcher window to be resized freely." );
        TooltipManager.install( discordCheckBox,
                "Shows your current modpack and play status in Discord." );
        TooltipManager.install( enhancedLoggingCheckBox,
                "Writes additional diagnostic info to log files." );
        TooltipManager.install( inGameConsoleCheckBox,
                "Shows a real-time game output console while playing. Useful for debugging." );
        TooltipManager.install( themeSelection,
                "Choose the launcher's visual theme. Automatic matches your OS setting." );
        TooltipManager.install( jvmPresetSelection,
                "JVM tuning flags. Performance (Aikar's Flags) is recommended for most users." );
        TooltipManager.install( proxyEnableCheckBox,
                "Enable if you're behind a corporate or school proxy/firewall." );
        TooltipManager.install( proxyHostField,
                "The proxy server hostname or IP address (e.g. proxy.example.com or 10.0.0.1)." );
        TooltipManager.install( proxyPortSpinner,
                "The proxy server port number (commonly 8080 for HTTP, 1080 for SOCKS)." );
        TooltipManager.install( proxyTypeSelection,
                "HTTP for web proxies, SOCKS for lower-level network proxies." );
        TooltipManager.install( resetLauncherBtn,
                "Deletes all launcher data and resets to defaults. Use as a last resort!" );
        TooltipManager.install( resetRuntimeBtn,
                "Removes all cached Java runtimes. They will re-download on next launch." );
        TooltipManager.install( exportSettingsBtn,
                "Save your current launcher settings to a JSON file for backup or sharing." );
        TooltipManager.install( importSettingsBtn,
                "Load settings from a previously exported JSON file." );

        // Navbar help button — same pattern the main menu uses (Label with .helpButton
        // styleClass). MCLauncherGuiWindow.injectHelpButton() detects the navbar entry
        // and skips its corner-overlay fallback. Wire the click handler + tooltip here.
        if ( helpBtn != null ) {
            helpBtn.setOnMouseClicked( e -> MCLauncherHelpWindow.show( getHelpTopic() ) );
            helpBtn.setCursor( javafx.scene.Cursor.HAND );
            TooltipManager.install( helpBtn, "Open the help window for this screen." );
        }
    }

    @Override
    void cleanup() {
        if ( minRamGb != null && minRamGb.getValueFactory() != null && minRamListener != null ) {
            minRamGb.getValueFactory().valueProperty().removeListener( minRamListener );
        }
        if ( maxRamGb != null && maxRamGb.getValueFactory() != null && maxRamListener != null ) {
            maxRamGb.getValueFactory().valueProperty().removeListener( maxRamListener );
        }
    }

    @Override
    HelpTopic getHelpTopic() { return HelpTopic.SETTINGS; }

    /**
     * Checks whether any settings in the UI differ from the persisted config values.
     *
     * @return true if any setting has been changed by the user
     *
     * @since 3.0
     */
    private boolean hasUnsavedChanges() {
        long currentMinRamMb = ( long ) ( minRamGb.getValue() * 1024 );
        long currentMaxRamMb = ( long ) ( maxRamGb.getValue() * 1024 );
        if ( currentMinRamMb != ConfigManager.getMinRam() ) return true;
        if ( currentMaxRamMb != ConfigManager.getMaxRam() ) return true;
        if ( debugCheckBox.isSelected() != ConfigManager.getDebugLogging() ) return true;
        if ( discordCheckBox.isSelected() != ConfigManager.getDiscordRpcEnable() ) return true;
        if ( windowResizeCheckBox.isSelected() != ConfigManager.getResizableWindows() ) return true;
        if ( enhancedLoggingCheckBox.isSelected() != ConfigManager.getEnhancedLogging() ) return true;
        if ( inGameConsoleCheckBox.isSelected() != ConfigManager.getInGameConsoleEnable() ) return true;
        if ( lwjglArmPatchCheckBox.isSelected() != ConfigManager.getLwjglArmPatchEnable() ) return true;
        String selectedTheme = themeSelection.getSelectedItem();
        if ( selectedTheme != null && !selectedTheme.equalsIgnoreCase( ConfigManager.getTheme() ) ) return true;
        return false;
    }
}
