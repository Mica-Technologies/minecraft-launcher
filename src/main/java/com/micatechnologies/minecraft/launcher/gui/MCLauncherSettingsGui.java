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
import java.util.List;
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
    MFXToggleButton discordInvitesCheckBox;

    @SuppressWarnings( "unused" )
    @FXML
    MFXToggleButton enhancedLoggingCheckBox;

    @SuppressWarnings( "unused" )
    @FXML
    MFXToggleButton inGameConsoleCheckBox;

    @SuppressWarnings( "unused" )
    @FXML
    MFXToggleButton batteryThrottleCheckBox;

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

    /** Appearance toggle: when off, modpack / version cards on the main menu
     *  and Library view render with the procedural gradient only — no
     *  background-image overlay. Backed by
     *  {@link ConfigManager#getShowPackBackgrounds}, default on.
     *
     *  @since 2026.3 */
    @SuppressWarnings( "unused" )
    @FXML
    io.github.palexdev.materialfx.controls.MFXToggleButton showPackBackgroundsToggle;

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
     * Security tab: mmcl:// deep-link kill switch. Mirrors
     * {@link ConfigManager#getUriHandlerEnabled()} —
     * see the {@code LauncherUriHandler} HTTPS + trusted-host gate.
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXToggleButton uriHandlerToggle;

    /**
     * Advanced tab: launcher-wide "Verify all game files" button.
     * Triggers a force-FULL verify across every installed modpack — runs
     * {@code pack.verifyAllFilesNow()} on each via
     * {@link com.micatechnologies.minecraft.launcher.utilities.VerifyAction#runForPacks}.
     * Reuses the launch-progress GUI for per-pack progress.
     *
     * @since 2026.3
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton verifyAllGameFilesBtn;

    /**
     * Advanced tab: launcher-wide default for the security-scan frequency.
     * The four-option combo backs
     * {@link com.micatechnologies.minecraft.launcher.config.ConfigManager#setDefaultScanFrequency}.
     * Per-pack overrides live in the modpack-detail-modal Advanced section.
     *
     * @since 2026.3
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXComboBox< String > defaultScanFrequencyCombo;

    /**
     * Advanced tab: CurseForge Core API key input + save / clear buttons +
     * status label. The field is a password-type input so the key isn't
     * shoulder-surfable while the user is typing it. Stored encrypted-at-
     * rest via {@link com.micatechnologies.minecraft.launcher.config.ConfigManager#setCurseForgeApiKey}
     * using the same machine-bound AES-256-GCM primitive that protects
     * the cached Minecraft auth tokens.
     *
     * @since 2026.3
     */
    @SuppressWarnings( "unused" )
    @FXML
    io.github.palexdev.materialfx.controls.MFXPasswordField curseForgeApiKeyField;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton curseForgeApiKeySaveBtn;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton curseForgeApiKeyClearBtn;

    @SuppressWarnings( "unused" )
    @FXML
    Label curseForgeApiKeyStatusLabel;

    /**
     * Navigation buttons for settings category sidebar.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton navAccount, navGame, navAppearance, navAdvanced, navNetwork, navSecurity, navSystem, navDiscord, navRgb, navAbout;

    // RGB tab controls — populated by setupRgbTab() and bound to the
    // RGB-integration settings under config keys "rgbEnable", "rgbBackend",
    // "rgbUsePackColors", "rgbHighlightKeys".
    @SuppressWarnings( "unused" ) @FXML io.github.palexdev.materialfx.controls.MFXToggleButton rgbEnableToggle;
    @SuppressWarnings( "unused" ) @FXML io.github.palexdev.materialfx.controls.MFXComboBox< String > rgbBackendCombo;
    @SuppressWarnings( "unused" ) @FXML javafx.scene.control.Label rgbStatusChip;
    @SuppressWarnings( "unused" ) @FXML io.github.palexdev.materialfx.controls.MFXToggleButton rgbUsePackColorsToggle;
    @SuppressWarnings( "unused" ) @FXML io.github.palexdev.materialfx.controls.MFXToggleButton rgbHighlightKeysToggle;
    @SuppressWarnings( "unused" ) @FXML MFXButton rgbTestBtn;

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
    @SuppressWarnings( "unused" ) @FXML MFXToggleButton launcherUpdateCheckBox;
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

            // Store Discord invites enable to config
            ConfigManager.setDiscordInvitesEnable( discordInvitesCheckBox.isSelected() );

            // Store resizable windows to config
            ConfigManager.setResizableWindows( windowResizeCheckBox.isSelected() );
            GUIUtilities.JFXPlatformRun( () -> stage.setResizable( ConfigManager.getResizableWindows() ) );

            // Store enhanced logging to config
            ConfigManager.setEnhancedLogging( enhancedLoggingCheckBox.isSelected() );
            ConfigManager.setInGameConsoleEnable( inGameConsoleCheckBox.isSelected() );

            // Store battery throttle preference
            ConfigManager.setBatteryThrottleEnable( batteryThrottleCheckBox.isSelected() );

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

        // Set and configure Discord RPC check box. Previously disabled in dev mode to
        // keep the prod Discord app cleaner, but there's no actual technical restriction
        // — the IPC client doesn't care about LAUNCHER_IS_DEV. With dev-mode RPC unlocked,
        // contributors can verify rich-presence behavior end-to-end without needing a
        // production build. (If a distinct dev Discord application is set up later, swap
        // DiscordRpcUtility.CLIENT_ID to a LAUNCHER_IS_DEV-gated picker so dev sessions
        // surface as "Mica Minecraft DEV" instead of polluting the prod app's stats.)
        discordCheckBox.setSelected( ConfigManager.getDiscordRpcEnable() );

        // Set and configure Discord invites check box
        discordInvitesCheckBox.setSelected( ConfigManager.getDiscordInvitesEnable() );

        // Populate theme selection dropdown
        themeSelection.getItems().clear();
        themeSelection.getItems().addAll( ConfigConstants.ALLOWED_THEMES );

        // Pack-background display toggle (Appearance tab). Writes back to config
        // on change so the next card-grid rebuild on the main menu / Library
        // view picks up the new state. Already-rendered cards keep their
        // current visuals until the user navigates away and back — no need to
        // force a global rebuild from the Settings panel.
        if ( showPackBackgroundsToggle != null ) {
            showPackBackgroundsToggle.setSelected( ConfigManager.getShowPackBackgrounds() );
            showPackBackgroundsToggle.selectedProperty().addListener(
                    ( obs, oldV, newV ) -> ConfigManager.setShowPackBackgrounds(
                            Boolean.TRUE.equals( newV ) ) );
        }

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

        // Set and configure battery throttle check box
        batteryThrottleCheckBox.setSelected( ConfigManager.getBatteryThrottleEnable() );

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

        // mmcl:// deep-link toggle. Read once into the checkbox; listener writes back on
        // change. Defaults true via ConfigConstants.URI_HANDLER_ENABLED_DEFAULT, so the
        // box is checked for fresh installs.
        if ( uriHandlerToggle != null ) {
            uriHandlerToggle.setSelected( ConfigManager.getUriHandlerEnabled() );
            uriHandlerToggle.selectedProperty().addListener(
                    ( obs, oldV, newV ) -> ConfigManager.setUriHandlerEnabled( newV ) );
        }

        // Default scan-frequency combo. Stored as the enum name; combo is keyed by
        // display label so renames of the user-facing copy don't shift the index.
        if ( defaultScanFrequencyCombo != null ) {
            com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency[] freqValues =
                    com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency.values();
            java.util.List< String > labels = new java.util.ArrayList<>();
            for ( var f : freqValues ) labels.add( f.displayLabel() );
            defaultScanFrequencyCombo.setItems( javafx.collections.FXCollections.observableArrayList( labels ) );
            com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency current =
                    com.micatechnologies.minecraft.launcher.config.ConfigManager.getDefaultScanFrequency();
            defaultScanFrequencyCombo.selectItem( current.displayLabel() );
            defaultScanFrequencyCombo.setOnAction( e -> {
                String selected = defaultScanFrequencyCombo.getSelectedItem();
                if ( selected == null ) return;
                for ( var f : freqValues ) {
                    if ( f.displayLabel().equals( selected ) ) {
                        com.micatechnologies.minecraft.launcher.config.ConfigManager
                                .setDefaultScanFrequency( f );
                        break;
                    }
                }
            } );
        }

        // CurseForge API key input. The field deliberately never displays the
        // stored value (the password field would show its existing contents,
        // which means a shoulder-surfer or screen-recording at the Settings
        // screen would leak the key). Instead, the status label below the
        // field tells the user whether a key is already configured, and
        // typing a new value + clicking Save replaces it. Clear wipes the
        // stored value without exposing it.
        if ( curseForgeApiKeyField != null && curseForgeApiKeySaveBtn != null
                && curseForgeApiKeyClearBtn != null && curseForgeApiKeyStatusLabel != null ) {
            refreshCurseForgeApiKeyStatus();
            curseForgeApiKeySaveBtn.setOnAction( e -> {
                String entered = curseForgeApiKeyField.getText();
                if ( entered == null || entered.isBlank() ) {
                    return;
                }
                com.micatechnologies.minecraft.launcher.config.ConfigManager
                        .setCurseForgeApiKey( entered.trim() );
                curseForgeApiKeyField.clear();
                refreshCurseForgeApiKeyStatus();
                com.micatechnologies.minecraft.launcher.utilities.NotificationManager.success(
                        "CurseForge key saved",
                        "Stored encrypted on this machine. CurseForge URL imports will now "
                                + "fetch project metadata for the confirmation preview." );
            } );
            curseForgeApiKeyClearBtn.setOnAction( e -> {
                com.micatechnologies.minecraft.launcher.config.ConfigManager
                        .setCurseForgeApiKey( null );
                curseForgeApiKeyField.clear();
                refreshCurseForgeApiKeyStatus();
            } );
        }

        // Verify-all-game-files button. Synchronously iterates every installed pack and
        // runs a force-FULL verify on each via VerifyAction, with the launch progress
        // GUI driving the per-pack progress. Dispatch happens off the FX thread inside
        // VerifyAction.runForPacks.
        if ( verifyAllGameFilesBtn != null ) {
            verifyAllGameFilesBtn.setOnAction( e -> {
                java.util.List< com.micatechnologies.minecraft.launcher.game.modpack.GameModPack > installed =
                        com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager
                                .getInstalledModPacks();
                if ( installed == null || installed.isEmpty() ) {
                    com.micatechnologies.minecraft.launcher.utilities.NotificationManager.info(
                            "Nothing to verify",
                            "No modpacks are installed." );
                    return;
                }
                com.micatechnologies.minecraft.launcher.utilities.VerifyAction.runForPacks(
                        installed );
            } );
        }

        // Wire up sidebar navigation buttons. The visual order here must
        // match the ScrollPane order inside settingsContent — showCategory
        // looks up navButtons[index] to apply the "selected" style.
        navButtons = new MFXButton[]{ navAccount, navGame, navAppearance, navAdvanced, navNetwork, navSecurity, navSystem, navDiscord, navRgb, navAbout };
        navAccount.setOnAction( e -> showCategory( 0 ) );
        navGame.setOnAction( e -> showCategory( 1 ) );
        navAppearance.setOnAction( e -> showCategory( 2 ) );
        navAdvanced.setOnAction( e -> showCategory( 3 ) );
        navNetwork.setOnAction( e -> showCategory( 4 ) );
        navSecurity.setOnAction( e -> showCategory( 5 ) );
        navSystem.setOnAction( e -> showCategory( 6 ) );
        navDiscord.setOnAction( e -> showCategory( 7 ) );
        navRgb.setOnAction( e -> showCategory( 8 ) );
        navAbout.setOnAction( e -> showCategory( 9 ) );

        setupAboutTab();
        setupRgbTab();

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
     *              6=System, 7=Discord, 8=RGB, 9=About)
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
    /**
     * Updates the CurseForge API key status label to reflect what's currently
     * stored in config. We never display the actual key value — the label
     * just shows "configured" vs "not set" so a shoulder-surfer at the
     * Settings screen can't read off the credential.
     */
    private void refreshCurseForgeApiKeyStatus()
    {
        if ( curseForgeApiKeyStatusLabel == null ) return;
        boolean configured = com.micatechnologies.minecraft.launcher.config.ConfigManager
                .hasCurseForgeApiKey();
        if ( configured ) {
            curseForgeApiKeyStatusLabel.setText(
                    "An encrypted API key is currently saved on this machine." );
        }
        else {
            curseForgeApiKeyStatusLabel.setText(
                    "No API key is configured. CurseForge URL imports will fall back to the manual-download workaround." );
        }
    }

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
        if ( launcherUpdateCheckBox != null ) {
            launcherUpdateCheckBox.setSelected( ConfigManager.getLauncherUpdateCheckEnabled() );
            launcherUpdateCheckBox.selectedProperty().addListener(
                    ( obs, oldV, newV ) -> ConfigManager.setLauncherUpdateCheckEnabled( newV ) );
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
     * Populates the RGB Lighting tab. Binds the master enable toggle, the
     * backend combo, the in-game-effect toggles, and the test button to
     * the underlying {@code rgb*} config keys + the {@link com.micatechnologies.minecraft.launcher.rgb.RgbController}
     * singleton.
     *
     * <p>Backend changes are applied immediately: flipping the master
     * toggle or picking a different backend stops the current
     * controller and restarts on the new choice. The status chip is
     * refreshed on every change so users get instant feedback (e.g.
     * "Connected: OpenRGB" → "Not detected" when the OpenRGB server
     * isn't running).</p>
     *
     * <p>All control wiring is null-guarded — if the FXML hasn't been
     * updated to include the RGB pane fields (e.g. running against an
     * older FXML in dev), the method silently no-ops on the missing
     * controls rather than NPE'ing the whole settings GUI.</p>
     */
    private void setupRgbTab()
    {
        // Backend combo first — populated before the toggles so when we
        // wire the enable toggle below, applying the current backend
        // picks the right combo entry.
        if ( rgbBackendCombo != null ) {
            rgbBackendCombo.setItems( javafx.collections.FXCollections.observableArrayList(
                    "Auto", "OpenRGB", "Razer Chroma", "None" ) );
            rgbBackendCombo.selectItem( labelForBackend( ConfigManager.getRgbBackend() ) );
            rgbBackendCombo.setOnAction( e -> {
                String label = rgbBackendCombo.getValue();
                ConfigManager.setRgbBackend( backendForLabel( label ) );
                restartRgbController();
            } );
        }

        if ( rgbEnableToggle != null ) {
            rgbEnableToggle.setSelected( ConfigManager.getRgbEnable() );
            rgbEnableToggle.selectedProperty().addListener( ( obs, oldV, newV ) -> {
                ConfigManager.setRgbEnable( newV );
                restartRgbController();
            } );
        }

        if ( rgbUsePackColorsToggle != null ) {
            rgbUsePackColorsToggle.setSelected( ConfigManager.getRgbUsePackColors() );
            rgbUsePackColorsToggle.selectedProperty().addListener(
                    ( obs, oldV, newV ) -> ConfigManager.setRgbUsePackColors( newV ) );
        }

        if ( rgbHighlightKeysToggle != null ) {
            rgbHighlightKeysToggle.setSelected( ConfigManager.getRgbHighlightKeys() );
            rgbHighlightKeysToggle.selectedProperty().addListener(
                    ( obs, oldV, newV ) -> ConfigManager.setRgbHighlightKeys( newV ) );
        }

        if ( rgbTestBtn != null ) {
            rgbTestBtn.setOnAction( e -> runRgbConnectionTest() );
        }

        refreshRgbStatusChip();
    }

    /** Maps the config-stored backend identifier to the user-facing
     *  combo label. Defaults to "Auto" for unknown values. */
    private static String labelForBackend( String backend )
    {
        return switch ( backend == null ? "" : backend ) {
            case com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_OPENRGB -> "OpenRGB";
            case com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_CHROMA  -> "Razer Chroma";
            case com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_NONE    -> "None";
            default -> "Auto";
        };
    }

    /** Inverse of {@link #labelForBackend}. */
    private static String backendForLabel( String label )
    {
        return switch ( label == null ? "" : label ) {
            case "OpenRGB"      -> com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_OPENRGB;
            case "Razer Chroma" -> com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_CHROMA;
            case "None"         -> com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_NONE;
            default             -> com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_AUTO;
        };
    }

    /** Stops the RgbController and restarts it with whatever the current
     *  config says — used when the user toggles the master enable or
     *  picks a different backend. Off the FX thread because the backend
     *  start() may do socket I/O which we don't want to block GUI updates
     *  on. */
    private void restartRgbController()
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                com.micatechnologies.minecraft.launcher.rgb.RgbController controller =
                        com.micatechnologies.minecraft.launcher.rgb.RgbController.getInstance();
                controller.stop();
                if ( ConfigManager.getRgbEnable() ) {
                    controller.start(
                            com.micatechnologies.minecraft.launcher.rgb.RgbBackendRegistry
                                    .resolveFromConfig() );
                }
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( "RGB controller restart from Settings threw", t );
            }
            GUIUtilities.JFXPlatformRun( this::refreshRgbStatusChip );
        } );
    }

    /** Recomputes and re-applies the RGB status chip text + style. Safe
     *  to call from any thread; FX work is dispatched. */
    private void refreshRgbStatusChip()
    {
        if ( rgbStatusChip == null ) return;
        GUIUtilities.JFXPlatformRun( () -> {
            com.micatechnologies.minecraft.launcher.rgb.RgbController.Status s =
                    com.micatechnologies.minecraft.launcher.rgb.RgbController.getInstance().status();
            String text;
            String styleSuffix;
            if ( !ConfigManager.getRgbEnable() ) {
                text = "Disabled";
                styleSuffix = ""; // base muted chip
            }
            else if ( !s.running() || "None".equals( s.backendName() ) ) {
                text = "Not detected";
                styleSuffix = "-warn";
            }
            else {
                text = switch ( s.health() ) {
                    case HEALTHY  -> "Connected: " + s.backendName();
                    case DEGRADED -> "Degraded: " + s.backendName();
                    case DEAD     -> "Unavailable: " + s.backendName();
                };
                styleSuffix = switch ( s.health() ) {
                    case HEALTHY  -> "-success";
                    case DEGRADED -> "-warn";
                    case DEAD     -> "-danger";
                };
            }
            rgbStatusChip.setText( text );
            rgbStatusChip.getStyleClass().removeIf( c -> c.startsWith( "stat-chip-" ) );
            if ( !styleSuffix.isEmpty() ) {
                rgbStatusChip.getStyleClass().add( "stat-chip" + styleSuffix );
            }
        } );
    }

    /** Flashes the launcher's accent color on every connected device
     *  for ~2 seconds, then restores whatever effect was active. Lets
     *  the user confirm the backend is reaching their keyboard before
     *  relying on it during a launch. */
    private void runRgbConnectionTest()
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                com.micatechnologies.minecraft.launcher.rgb.RgbController controller =
                        com.micatechnologies.minecraft.launcher.rgb.RgbController.getInstance();
                // Make sure the controller is running before the test — if
                // the user toggled enable on but hasn't navigated away from
                // settings since, the restart task may still be in flight.
                if ( !ConfigManager.getRgbEnable() ) {
                    com.micatechnologies.minecraft.launcher.utilities.NotificationManager.warn(
                            "RGB disabled",
                            "Turn on \"Enable RGB lighting\" first, then try the test again." );
                    return;
                }
                com.micatechnologies.minecraft.launcher.rgb.RgbEffect prior = controller.activeEffect();
                // Picking a fixed magenta so the test reads as a deliberate
                // signal — using the launcher theme's accent color would
                // produce a different result per theme and the user might
                // miss whether it actually fired.
                com.micatechnologies.minecraft.launcher.rgb.effects.SolidEffect test =
                        new com.micatechnologies.minecraft.launcher.rgb.effects.SolidEffect(
                                "Connection Test",
                                new com.micatechnologies.minecraft.launcher.rgb.RgbColor( 255, 0, 255 ) );
                controller.setEffect( test );
                Thread.sleep( 2_000L );
                controller.setEffect( prior ); // restore (null = stop)
            }
            catch ( InterruptedException ie ) {
                Thread.currentThread().interrupt();
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( "RGB connection test threw", t );
            }
            GUIUtilities.JFXPlatformRun( this::refreshRgbStatusChip );
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

        // Layer the supplemental heuristics on top so the user-triggered scan
        // surfaces the same broader findings the per-launch scan does. Counted
        // separately in the output line so "Stage 1 / Stage 2 / Supplemental"
        // reads as three independent signals.
        int supplementalHigh = 0;
        int supplementalMedium = 0;
        try {
            List< com.micatechnologies.minecraft.launcher.security.SupplementalScanner.Finding > extras =
                    com.micatechnologies.minecraft.launcher.security.SupplementalScanner.scanFolder(
                            scanFolder.toPath(), new ArrayList<>(), scanCoreCount );
            for ( var f : extras ) {
                if ( f.severity()
                        == com.micatechnologies.minecraft.launcher.security.SupplementalScanner.Severity.HIGH ) {
                    supplementalHigh++;
                }
                else {
                    supplementalMedium++;
                }
                logOutput.apply( "Supplemental scan: " + f );
            }
        }
        catch ( IOException e ) {
            logOutput.apply( "Supplemental scan I/O error: " + e.getMessage() );
        }

        int stage1 = scanResults.getStage1Detections() == null ? 0 : scanResults.getStage1Detections().size();
        int stage2 = scanResults.getStage2Detections() == null ? 0 : scanResults.getStage2Detections().size();
        int total = stage1 + stage2 + supplementalHigh + supplementalMedium;
        if ( total > 0 ) {
            logOutput.apply( "Infections found — stage1=" + stage1 + ", stage2=" + stage2
                                     + ", supplemental-high=" + supplementalHigh
                                     + ", supplemental-warn=" + supplementalMedium );
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
        // Wire smooth-scroll on every tab's inner ScrollPane so wheel scrolling
        // in settings feels the same as the modpack list / library / help WebView
        // instead of JavaFX's default discrete-tick behavior. Using lookupAll
        // on the JavaFX-supplied .scroll-pane CSS class catches all nine tab
        // panes (and any future ones added to the FXML) without forcing the
        // controller to inject each by fx:id.
        for ( javafx.scene.Node node : rootPane.lookupAll( ".scroll-pane" ) ) {
            if ( node instanceof javafx.scene.control.ScrollPane sp ) {
                SmoothScroll.install( sp );
            }
        }

        // Select current theme in dropdown. Match case-insensitively against the canonical
        // ALLOWED_THEMES list so themes with mixed-case display names (e.g. "Native (Mica)")
        // survive a round-trip through config without being mangled by single-word
        // capitalization normalization. The previous logic always reset such themes to
        // Automatic on every launch because StringUtils.capitalize lowercased everything
        // past the first character.
        String currentConfigTheme = ConfigManager.getTheme();
        String safeCurrentConfigTheme = ConfigConstants.ALLOWED_THEMES.stream()
                .filter( t -> t.equalsIgnoreCase( currentConfigTheme ) )
                .findFirst()
                .orElse( null );

        if ( safeCurrentConfigTheme == null ) {
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
        TooltipManager.install( discordInvitesCheckBox,
                "When in-game, friends see a Join Game button on your Discord status that installs and launches the same modpack on their machine." );
        TooltipManager.install( enhancedLoggingCheckBox,
                "Writes additional diagnostic info to log files." );
        TooltipManager.install( inGameConsoleCheckBox,
                "Shows a real-time game output console while playing. Useful for debugging." );
        TooltipManager.install( batteryThrottleCheckBox,
                "Slows downloads on laptops running on battery to conserve power. "
                        + "Desktops and devices on AC are never throttled." );
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
        if ( discordInvitesCheckBox.isSelected() != ConfigManager.getDiscordInvitesEnable() ) return true;
        if ( windowResizeCheckBox.isSelected() != ConfigManager.getResizableWindows() ) return true;
        if ( enhancedLoggingCheckBox.isSelected() != ConfigManager.getEnhancedLogging() ) return true;
        if ( inGameConsoleCheckBox.isSelected() != ConfigManager.getInGameConsoleEnable() ) return true;
        if ( batteryThrottleCheckBox.isSelected() != ConfigManager.getBatteryThrottleEnable() ) return true;
        if ( lwjglArmPatchCheckBox.isSelected() != ConfigManager.getLwjglArmPatchEnable() ) return true;
        String selectedTheme = themeSelection.getSelectedItem();
        if ( selectedTheme != null && !selectedTheme.equalsIgnoreCase( ConfigManager.getTheme() ) ) return true;
        return false;
    }
}
