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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
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
    MFXComboBox< String > consoleLogMaxLinesSelection;

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

    /** Save shortcut that also relaunches the launcher. Hidden in FXML; shown
     *  only while the language dropdown differs from the saved override, because
     *  a language change needs a relaunch to fully re-apply. */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton saveAndRestartBtn;

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
    MFXComboBox< String > languageSelection;

    /** Appearance toggle: when off, modpack / version cards on the main menu
     *  and Library view render with the procedural gradient only — no
     *  background-image overlay. Backed by
     *  {@link ConfigManager#getShowPackBackgrounds}, default on.
     *
     *  @since 2026.3 */
    @SuppressWarnings( "unused" )
    @FXML
    io.github.palexdev.materialfx.controls.MFXToggleButton showPackBackgroundsToggle;

    /** Appearance dropdown: how often a pack's logo + background cycle when the
     *  manifest declares multiple images (issue #43). Items are localized labels;
     *  the selected index maps to a token in
     *  {@link ConfigConstants#IMAGE_CYCLE_INTERVAL_OPTIONS}.
     *
     *  @since 3.6 */
    @SuppressWarnings( "unused" )
    @FXML
    MFXComboBox< String > imageCycleIntervalSelection;

    /** Appearance toggle: cycle a pack's images in a one-time shuffled order
     *  instead of manifest order. Backed by
     *  {@link ConfigManager#getImageCycleShuffle}, default off.
     *
     *  @since 3.6 */
    @SuppressWarnings( "unused" )
    @FXML
    io.github.palexdev.materialfx.controls.MFXToggleButton imageCycleShuffleToggle;

    @SuppressWarnings( "unused" )
    @FXML
    MFXComboBox< String > jvmPresetSelection;

    @SuppressWarnings( "unused" )
    @FXML
    io.github.palexdev.materialfx.controls.MFXButton generateJvmArgsBtn;

    @SuppressWarnings( "unused" )
    @FXML
    javafx.scene.control.Label generateJvmArgsHint;

    // Pack-backup policy controls (Advanced tab).
    @SuppressWarnings( "unused" )
    @FXML
    io.github.palexdev.materialfx.controls.MFXToggleButton autoBackupToggle;

    @SuppressWarnings( "unused" )
    @FXML
    io.github.palexdev.materialfx.controls.MFXToggleButton backupIncludeSavesToggle;

    @SuppressWarnings( "unused" )
    @FXML
    javafx.scene.control.Spinner< Integer > backupMaxCountSpinner;

    @SuppressWarnings( "unused" )
    @FXML
    javafx.scene.control.Spinner< Integer > backupMaxAgeDaysSpinner;

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

    @SuppressWarnings( "unused" )
    @FXML
    Label offlineLabel;

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
    @SuppressWarnings( "unused" ) @FXML io.github.palexdev.materialfx.controls.MFXToggleButton rgbMenuEffectToggle;
    @SuppressWarnings( "unused" ) @FXML io.github.palexdev.materialfx.controls.MFXComboBox< String > rgbEffectStyleSelection;
    @SuppressWarnings( "unused" ) @FXML io.github.palexdev.materialfx.controls.MFXToggleButton rgbUsePackColorsToggle;
    @SuppressWarnings( "unused" ) @FXML io.github.palexdev.materialfx.controls.MFXToggleButton rgbHighlightKeysToggle;
    @SuppressWarnings( "unused" ) @FXML MFXButton rgbTestBtn;

    // Per-backend Auto-mode integration toggles — let mixed-vendor rigs
    // run several backends at once (Razer + Windows DL etc.), or disable
    // an installed-but-unwanted vendor without leaving Auto.
    @SuppressWarnings( "unused" ) @FXML javafx.scene.control.Label rgbAutoToggleHeader;
    @SuppressWarnings( "unused" ) @FXML javafx.scene.layout.VBox rgbAutoToggleBox;
    @SuppressWarnings( "unused" ) @FXML io.github.palexdev.materialfx.controls.MFXToggleButton rgbEnableOpenRgbToggle;
    @SuppressWarnings( "unused" ) @FXML io.github.palexdev.materialfx.controls.MFXToggleButton rgbEnableChromaNativeToggle;
    @SuppressWarnings( "unused" ) @FXML io.github.palexdev.materialfx.controls.MFXToggleButton rgbEnableChromaRestToggle;
    @SuppressWarnings( "unused" ) @FXML io.github.palexdev.materialfx.controls.MFXToggleButton rgbEnableWindowsDlToggle;
    @SuppressWarnings( "unused" ) @FXML io.github.palexdev.materialfx.controls.MFXToggleButton rgbEnableCorsairToggle;
    @SuppressWarnings( "unused" ) @FXML io.github.palexdev.materialfx.controls.MFXToggleButton rgbEnableAsusAuraToggle;

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
    MFXButton minecraftNetBtn, msAccountBtn, logoutBtn, addAccountBtn;

    @SuppressWarnings( "unused" )
    @FXML
    javafx.scene.layout.VBox savedAccountsList;

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
     * Current max-heap setting in whole gigabytes (floored to at least 1), read from the
     * {@link #maxRamGb} spinner when it has a value, otherwise from persisted config.
     *
     * <p>The JVM-args hint and the "generate recommended args" action both need this value, and the
     * hint's initial {@code updateHint.run()} fires partway through {@link #setup()} — <em>before</em>
     * the max-RAM spinner's value factory is installed, at which point {@link Spinner#getValue()}
     * returns {@code null}. Reading through this helper avoids the NPE that otherwise blocks the
     * whole Settings screen from opening; once the spinner is initialized its value-change listener
     * re-runs the hint with the live value.</p>
     *
     * @return max heap in GB, never less than 1
     */
    private int currentMaxRamGb()
    {
        Double value = ( maxRamGb != null ) ? maxRamGb.getValue() : null;
        double gb = ( value != null ) ? value : ( ConfigManager.getMaxRam() / 1024.0 );
        return ( int ) Math.max( 1L, Math.round( gb ) );
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
                    int response = GUIUtilities.showQuestionMessage(
                            LocalizationManager.get( "dialog.settings.unsavedOnClose.title" ),
                            LocalizationManager.get( "dialog.settings.unsavedOnClose.header" ),
                            LocalizationManager.get( "dialog.settings.unsavedOnClose.body" ),
                            LocalizationManager.get( "dialog.settings.unsavedOnClose.button.saveAndClose" ),
                            LocalizationManager.get( "dialog.settings.unsavedOnClose.button.closeWithoutSaving" ),
                            stage );
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
                int response = GUIUtilities.showQuestionMessage(
                        LocalizationManager.get( "dialog.settings.unsavedOnReturn.title" ),
                        LocalizationManager.get( "dialog.settings.unsavedOnReturn.header" ),
                        LocalizationManager.get( "dialog.settings.unsavedOnReturn.body" ),
                        LocalizationManager.get( "dialog.settings.unsavedOnReturn.button.save" ),
                        LocalizationManager.get( "dialog.settings.unsavedOnReturn.button.return" ),
                        stage );
                if ( response == 1 ) {
                    GUIUtilities.JFXPlatformRun( () -> saveBtn.fire() );
                    try {
                        MCLauncherGuiController.goToMainGui();
                    }
                    catch ( IOException e ) {
                        Logger.logError( LocalizationManager.get( "log.settings.openMainGuiFailed" ) );
                        Logger.logThrowable( e );
                    }
                }
                else if ( response == 2 ) {
                    try {
                        MCLauncherGuiController.goToMainGui();
                    }
                    catch ( IOException e ) {
                        Logger.logError( LocalizationManager.get( "log.settings.openMainGuiFailed" ) );
                        Logger.logThrowable( e );
                    }
                }
            }
            else {
                try {
                    MCLauncherGuiController.goToMainGui();
                }
                catch ( IOException e ) {
                    Logger.logError( LocalizationManager.get( "log.settings.openMainGuiFailed" ) );
                    Logger.logThrowable( e );
                }
            }
        } ) );

        // Configure save button
        saveBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            persistSettings();

            // Recompute the pending-language state: after a plain Save the saved
            // override now matches the dropdown, so the Save & Restart shortcut
            // hides (the user chose to defer the relaunch). It reappears if they
            // change the language again.
            GUIUtilities.JFXPlatformRun( this::refreshSaveAndRestartButton );

            // Change save button text to indicate successful save
            GUIUtilities.JFXPlatformRun( () -> saveBtn.setText( LocalizationManager.get( "settings.saveBtn.saved" ) ) );

            // Schedule save button text to revert to normal after 5s
            SystemUtilities.spawnNewTask( () -> {
                try {
                    Thread.sleep( 5000 );
                }
                catch ( InterruptedException ignored ) {
                    Logger.logDebug( LocalizationManager.get( "log.settings.saveBtnResetInterrupted" ) );
                }
                GUIUtilities.JFXPlatformRun( () -> saveBtn.setText( LocalizationManager.get( "settings.saveBtn.label" ) ) );
            } );
        } ) );

        // Configure save & restart button — same persistence as Save, then a full
        // process relaunch so a just-saved language change takes effect everywhere
        // immediately. A language switch can't be fully applied in-process:
        // LocalizationManager caches its bundle and binds static-final translation
        // fields at class-load against the launch-time locale, so the in-process
        // restart loop only partially re-localizes. relaunchApp() spawns a fresh
        // JVM (falling back to an in-process restart in dev where there's no exe to
        // respawn). Shown only while a language change is pending (see
        // refreshSaveAndRestartButton).
        saveAndRestartBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            persistSettings();
            LauncherCore.relaunchApp();
        } ) );

        // Configure reset launcher button
        resetLauncherBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            int response = GUIUtilities.showQuestionMessage(
                    LocalizationManager.get( "dialog.settings.resetLauncher.title" ),
                    LocalizationManager.get( "dialog.settings.resetLauncher.header" ),
                    LocalizationManager.get( "dialog.settings.resetLauncher.body" ),
                    LocalizationManager.get( "dialog.settings.resetLauncher.button.reset" ),
                    LocalizationManager.get( "dialog.settings.resetLauncher.button.back" ),
                    stage );
            if ( response != 1 ) {
                return;
            }

            try {
                LauncherCore.cleanupApp();
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( LocalizationManager.get( "log.settings.cleanupBeforeResetFailed" ) );
            }
            try {
                FileUtils.deleteDirectory(
                        SynchronizedFileManager.getSynchronizedFile( LocalPathManager.getLauncherLocalPath() ) );
            }
            catch ( IOException e ) {
                Logger.logError( LocalizationManager.get( "log.settings.resetFilesFailed" ) );
            }
            finally {
                LauncherCore.restartApp();
            }
        } ) );

        // Configure reset runtime button
        resetRuntimeBtn.setOnAction( event -> SystemUtilities.spawnNewTask( () -> {
            if ( hasUnsavedChanges() ) {
                int response = GUIUtilities.showQuestionMessage(
                        LocalizationManager.get( "dialog.settings.unsavedOnResetRuntime.title" ),
                        LocalizationManager.get( "dialog.settings.unsavedOnResetRuntime.header" ),
                        LocalizationManager.get( "dialog.settings.unsavedOnResetRuntime.body" ),
                        LocalizationManager.get( "dialog.settings.unsavedOnResetRuntime.button.saveAndReset" ),
                        LocalizationManager.get( "dialog.settings.unsavedOnResetRuntime.button.resetOnly" ),
                        stage );
                // Save first
                if ( response == 1 ) {
                    GUIUtilities.JFXPlatformRun( () -> saveBtn.fire() );
                }
                // Cancel
                else if ( response != 2 ) {
                    return;
                }
            }

            int response = GUIUtilities.showQuestionMessage(
                    LocalizationManager.get( "dialog.settings.resetRuntime.title" ),
                    LocalizationManager.get( "dialog.settings.resetRuntime.header" ),
                    LocalizationManager.get( "dialog.settings.resetRuntime.body" ),
                    LocalizationManager.get( "dialog.settings.resetRuntime.button.reset" ),
                    LocalizationManager.get( "dialog.settings.resetRuntime.button.back" ),
                    stage );
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
                    Logger.logError( LocalizationManager.format( "log.settings.deleteRuntimeFailed", rt.get( "component" ), e.getMessage() ) );
                }
            }
            Logger.logStd( LocalizationManager.get( "log.settings.runtimesCleared" ) );

            //Return to the settings window
            try {
                MCLauncherGuiController.goToSettingsGui();
            }
            catch ( IOException e ) {
                Logger.logError( LocalizationManager.get( "log.settings.reloadSettingsFailed" ) );
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
                Logger.logError( LocalizationManager.get( "log.settings.openRuntimeGuiFailed" ) );
                Logger.logThrowable( e );
            }
        } ) );

        // Configure open launcher folder button
        exportSettingsBtn.setOnAction( event -> {
            javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
            fileChooser.setTitle( LocalizationManager.get( "dialog.fileChooser.exportSettings.title" ) );
            fileChooser.setInitialFileName(
                    LocalizationManager.get( "dialog.settings.exportSettings.defaultFilename" ) );
            fileChooser.getExtensionFilters().add(
                    new javafx.stage.FileChooser.ExtensionFilter(
                            LocalizationManager.get( "dialog.fileChooser.json.filter" ), "*.json" ) );
            java.io.File file = fileChooser.showSaveDialog( stage );
            if ( file != null ) {
                SystemUtilities.spawnNewTask( () -> {
                    boolean ok = ConfigManager.exportConfig( file );
                    GUIUtilities.JFXPlatformRun( () -> {
                        if ( ok ) {
                            exportSettingsBtn.setText( LocalizationManager.get( "settings.exportBtn.success" ) );
                        }
                        else {
                            exportSettingsBtn.setText( LocalizationManager.get( "settings.exportBtn.failed" ) );
                        }
                    } );
                    SystemUtilities.spawnNewTask( () -> {
                        try { Thread.sleep( 2000 ); } catch ( InterruptedException ignored ) {}
                        GUIUtilities.JFXPlatformRun( () -> exportSettingsBtn.setText( LocalizationManager.get( "settings.exportBtn.label" ) ) );
                    } );
                } );
            }
        } );

        importSettingsBtn.setOnAction( event -> {
            javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
            fileChooser.setTitle( LocalizationManager.get( "settings.fileChooser.importTitle" ) );
            fileChooser.getExtensionFilters().add(
                    new javafx.stage.FileChooser.ExtensionFilter(
                            LocalizationManager.get( "dialog.fileChooser.json.filter" ), "*.json" ) );
            java.io.File file = fileChooser.showOpenDialog( stage );
            if ( file != null ) {
                SystemUtilities.spawnNewTask( () -> {
                    boolean ok = ConfigManager.importConfig( file );
                    GUIUtilities.JFXPlatformRun( () -> {
                        if ( ok ) {
                            importSettingsBtn.setText( LocalizationManager.get( "settings.importBtn.success" ) );
                            // Reload the settings GUI to reflect imported values
                            try {
                                MCLauncherGuiController.goToSettingsGui();
                            }
                            catch ( IOException e ) {
                                Logger.logErrorSilent( LocalizationManager.get( "log.settings.reloadAfterImportFailed" ) );
                            }
                        }
                        else {
                            importSettingsBtn.setText( LocalizationManager.get( "settings.importBtn.failed" ) );
                            SystemUtilities.spawnNewTask( () -> {
                                try { Thread.sleep( 2000 ); } catch ( InterruptedException ignored ) {}
                                GUIUtilities.JFXPlatformRun(
                                        () -> importSettingsBtn.setText( LocalizationManager.get( "settings.importBtn.label" ) ) );
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
                Logger.logError( LocalizationManager.get( "log.settings.openLauncherFolderFailed" ) );
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
            versionLabel.setText( LocalizationManager.get( "settings.versionLabel.devMode" ) );
        }
        else {
            versionLabel.setText( LocalizationManager.format( "settings.versionLabel.normal", LauncherConstants.LAUNCHER_APPLICATION_VERSION ) );
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

        // Populate language selection dropdown. First item is the "Use OS
        // Language (detected: <name>)" sentinel — selecting it clears the
        // config override so the next startup falls back to
        // LocaleBootstrap.detectOsLocale. Concrete entries follow, listed
        // in SupportedLocales by global speaker count. Display names are
        // written in the target language so the dropdown reads correctly
        // even when the user can't read the launcher's current UI
        // language (a Spanish speaker on an English-UI launcher sees
        // "Español" rather than "Spanish").
        if ( languageSelection != null ) {
            languageSelection.getItems().clear();
            String osDetectedName = com.micatechnologies.minecraft.launcher.consts.localization
                    .LocaleBootstrap.detectOsLocale()
                    .getDisplayName( java.util.Locale.ENGLISH );
            languageSelection.getItems().add(
                    com.micatechnologies.minecraft.launcher.consts.localization
                            .SupportedLocales.OS_DEFAULT_LABEL_PREFIX
                            + " (detected: " + osDetectedName + ")" );
            for ( var entry : com.micatechnologies.minecraft.launcher.consts.localization
                    .SupportedLocales.ENTRIES ) {
                languageSelection.getItems().add( entry.displayName() );
            }
            // Pick the item corresponding to the saved override, or the
            // OS-default sentinel when no override is set.
            String savedTag = ConfigManager.getLocaleOverride();
            if ( savedTag == null || savedTag.isBlank() ) {
                languageSelection.selectFirst();
            }
            else {
                String matchedDisplay = null;
                for ( var entry : com.micatechnologies.minecraft.launcher.consts.localization
                        .SupportedLocales.ENTRIES ) {
                    if ( entry.tag().equalsIgnoreCase( savedTag ) ) {
                        matchedDisplay = entry.displayName();
                        break;
                    }
                }
                if ( matchedDisplay != null ) {
                    languageSelection.selectItem( matchedDisplay );
                }
                else {
                    // Override is set to something we don't ship — fall back
                    // to the OS-default sentinel rather than silently leave
                    // it pointing nowhere.
                    languageSelection.selectFirst();
                }
            }

            // Reveal the Save & Restart shortcut whenever the dropdown moves to a
            // language different from the saved override (and hide it again on
            // revert). The listener fires after the initial selectItem/selectFirst
            // above, so seed the correct hidden state explicitly afterwards.
            languageSelection.selectedItemProperty().addListener(
                    ( obs, oldV, newV ) -> refreshSaveAndRestartButton() );
            refreshSaveAndRestartButton();
        }

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

        // Multi-image cycle interval (Appearance tab). The combo shows localized
        // labels; selection maps back to a canonical token. On change we write the
        // token AND reconfigure the live cycle clock so the new cadence takes effect
        // immediately without a restart.
        if ( imageCycleIntervalSelection != null ) {
            imageCycleIntervalSelection.getItems().clear();
            for ( String token : ConfigConstants.IMAGE_CYCLE_INTERVAL_OPTIONS ) {
                imageCycleIntervalSelection.getItems().add( cycleIntervalLabel( token ) );
            }
            String savedToken = ConfigManager.getImageCycleInterval();
            int savedIdx = ConfigConstants.IMAGE_CYCLE_INTERVAL_OPTIONS.indexOf( savedToken );
            imageCycleIntervalSelection.selectItem(
                    cycleIntervalLabel( savedIdx >= 0
                                        ? savedToken
                                        : ConfigConstants.IMAGE_CYCLE_INTERVAL_DEFAULT ) );
            // On change, map the chosen label back to its token, persist it, and
            // reconfigure the live clock so the new cadence applies without a restart.
            imageCycleIntervalSelection.setOnAction( e -> {
                String label = imageCycleIntervalSelection.getValue();
                for ( String token : ConfigConstants.IMAGE_CYCLE_INTERVAL_OPTIONS ) {
                    if ( cycleIntervalLabel( token ).equals( label ) ) {
                        ConfigManager.setImageCycleInterval( token );
                        ModpackImageCycleClock.getInstance().reconfigure();
                        break;
                    }
                }
            } );
        }

        // Multi-image cycle shuffle toggle (Appearance tab).
        if ( imageCycleShuffleToggle != null ) {
            imageCycleShuffleToggle.setSelected( ConfigManager.getImageCycleShuffle() );
            imageCycleShuffleToggle.selectedProperty().addListener(
                    ( obs, oldV, newV ) -> ConfigManager.setImageCycleShuffle(
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

        // "Generate recommended args" — produces a JVM args string tuned to
        // the host's CPU + total-RAM + the launcher's max-heap setting via
        // HardwareTunedJvmArgs. The static PRESET dropdown stays as a quick-
        // pick alternative; this button is for users who want something
        // tuned to their box rather than the one-size-fits-most Aikar's
        // flags. The summary hint below the button shows the detected
        // inputs so the user can sanity-check.
        if ( generateJvmArgsBtn != null ) {
            Runnable updateHint = () -> {
                int maxRam = currentMaxRamGb();
                if ( generateJvmArgsHint != null ) {
                    generateJvmArgsHint.setText( com.micatechnologies.minecraft.launcher.utilities
                            .HardwareTunedJvmArgs.summary( maxRam ) );
                }
            };
            updateHint.run();
            // Keep the hint synced when the user changes the max-RAM
            // spinner — the recommendation depends on it.
            maxRamGb.valueProperty().addListener( ( obs, oldV, newV ) -> updateHint.run() );
            TooltipManager.install( generateJvmArgsBtn,
                    LocalizationManager.get( "tooltip.settings.generateJvmArgs" ) );
            generateJvmArgsBtn.setOnAction( e -> SystemUtilities.spawnNewTask( () -> {
                int maxRam = currentMaxRamGb();
                String generated = com.micatechnologies.minecraft.launcher.utilities
                        .HardwareTunedJvmArgs.generate( maxRam );
                // Persist as customJvmArgs. The combo box's current
                // selection is left alone; the "matched" detection on
                // next Settings load will show "Performance" since the
                // generated string differs from every static preset.
                ConfigManager.setCustomJvmArgs( generated );
                com.micatechnologies.minecraft.launcher.utilities.NotificationManager.success(
                        LocalizationManager.get( "notification.settings.jvmArgsGenerated.title" ),
                        LocalizationManager.get( "notification.settings.jvmArgsGenerated.body" ) );
                Logger.logStd( LocalizationManager.format( "log.settings.generatedJvmArgs", generated ) );
            } ) );
        }

        // Set and configure enhanced logging check box
        enhancedLoggingCheckBox.setSelected( ConfigManager.getEnhancedLogging() );

        // Set and configure in-game console check box
        inGameConsoleCheckBox.setSelected( ConfigManager.getInGameConsoleEnable() );

        // Console log buffer-size dropdown. Presets live in ConfigConstants;
        // 0 maps to "Unlimited". Selection saved through the existing Save
        // button so it follows the rest of the settings flow.
        if ( consoleLogMaxLinesSelection != null ) {
            java.util.List< String > labels = new java.util.ArrayList<>();
            for ( int p : ConfigConstants.CONSOLE_LOG_MAX_LINES_PRESETS ) {
                labels.add( p <= 0
                        ? LocalizationManager.get( "settings.consoleLogMaxLines.unlimited" )
                        : LocalizationManager.format( "settings.consoleLogMaxLines.lines",
                                                        java.text.NumberFormat.getInstance().format( p ) ) );
            }
            consoleLogMaxLinesSelection.setItems(
                    javafx.collections.FXCollections.observableArrayList( labels ) );
            int current = ConfigManager.getConsoleLogMaxLines();
            int matchedIdx = 0;
            for ( int i = 0; i < ConfigConstants.CONSOLE_LOG_MAX_LINES_PRESETS.length; i++ ) {
                if ( ConfigConstants.CONSOLE_LOG_MAX_LINES_PRESETS[ i ] == current ) {
                    matchedIdx = i;
                    break;
                }
            }
            consoleLogMaxLinesSelection.selectItem( labels.get( matchedIdx ) );
        }

        // Set and configure battery throttle check box
        batteryThrottleCheckBox.setSelected( ConfigManager.getBatteryThrottleEnable() );

        // Set and configure LWJGL ARM64 patching check box
        lwjglArmPatchCheckBox.setSelected( ConfigManager.getLwjglArmPatchEnable() );

        // Pack-backup policy. Live listeners persist on change so the
        // settings panel doesn't need an explicit "save" — same pattern as
        // the other Advanced toggles above. Spinners are clamped at the
        // setter level (negative values floor to 0 which means "no cap").
        if ( autoBackupToggle != null ) {
            autoBackupToggle.setSelected( ConfigManager.getAutoBackupBeforeUpdate() );
            autoBackupToggle.selectedProperty().addListener( ( obs, oldV, newV ) ->
                    ConfigManager.setAutoBackupBeforeUpdate( Boolean.TRUE.equals( newV ) ) );
        }
        if ( backupIncludeSavesToggle != null ) {
            backupIncludeSavesToggle.setSelected( ConfigManager.getBackupIncludeSaves() );
            backupIncludeSavesToggle.selectedProperty().addListener( ( obs, oldV, newV ) ->
                    ConfigManager.setBackupIncludeSaves( Boolean.TRUE.equals( newV ) ) );
        }
        if ( backupMaxCountSpinner != null ) {
            backupMaxCountSpinner.setValueFactory( new SpinnerValueFactory.IntegerSpinnerValueFactory(
                    0, 100, ConfigManager.getMaxBackupsPerPack() ) );
            backupMaxCountSpinner.setEditable( true );
            backupMaxCountSpinner.valueProperty().addListener( ( obs, oldV, newV ) -> {
                if ( newV != null ) ConfigManager.setMaxBackupsPerPack( newV );
            } );
        }
        if ( backupMaxAgeDaysSpinner != null ) {
            backupMaxAgeDaysSpinner.setValueFactory( new SpinnerValueFactory.IntegerSpinnerValueFactory(
                    0, 365, ConfigManager.getMaxBackupAgeDays() ) );
            backupMaxAgeDaysSpinner.setEditable( true );
            backupMaxAgeDaysSpinner.valueProperty().addListener( ( obs, oldV, newV ) -> {
                if ( newV != null ) ConfigManager.setMaxBackupAgeDays( newV );
            } );
        }

        // Populate proxy settings
        proxyEnableCheckBox.setSelected( ConfigManager.getProxyEnable() );
        proxyHostField.setText( ConfigManager.getProxyHost() );
        proxyPortSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory( 1, 65535, ConfigManager.getProxyPort() ) );
        proxyPortSpinner.setEditable( true );
        proxyTypeSelection.getItems().clear();
        proxyTypeSelection.getItems().addAll( ConfigConstants.PROXY_TYPES );
        proxyTypeSelection.selectItem( ConfigManager.getProxyType() );

        // Load system RAM config label off the JavaFX Application Thread.
        // OSHI's first hardware query shells out to the OS and can stall
        // for several seconds (notably on macOS), which would freeze the
        // whole UI — including the menu-bar transition that opened this
        // screen — until it returns. Compute on a worker, then push the
        // result back to the label via the FX thread.
        SystemUtilities.spawnNewTask( () -> {
            try {
                SystemInfo systemInfo = new SystemInfo();
                long memTotalRaw = systemInfo.getHardware().getMemory().getTotal();
                long memAvailRaw = systemInfo.getHardware().getMemory().getAvailable();
                double memTotal = memTotalRaw / 1024.0 / 1024.0 / 1024.0;
                double memAvail = memAvailRaw / 1024.0 / 1024.0 / 1024.0;
                double memUsed = memTotal - memAvail;
                GUIUtilities.JFXPlatformRun( () -> sysRamLabel.setText(
                        LocalizationManager.format( "settings.sysRamLabel",
                                                    Precision.round( memTotal, 2 ),
                                                    Precision.round( memUsed, 2 ) ) ) );
            }
            catch ( Exception e ) {
                Logger.logError( LocalizationManager.get( "log.settings.queryMemoryFailed" ) );
                Logger.logThrowable( e );
            }
        } );

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
        scanFolderLabel.setText( LocalizationManager.format( "settings.scanFolderLabel", scanFolder ) );
        scanFolderBtn.setVisible( true );
        scanProgressBar.setVisible( false );
        scanOutputLabel.setVisible( false );
        scanFolderBtn.setOnAction( actionEvent -> {
            DirectoryChooser directoryChooser = new DirectoryChooser();
            directoryChooser.setTitle( LocalizationManager.get( "settings.scanFolderDialog.title" ) );
            directoryChooser.setInitialDirectory( scanFolder );
            File selectedDirectory = directoryChooser.showDialog( MCLauncherGuiController.getTopStageOrNull() );
            if ( selectedDirectory != null ) {
                scanFolder = selectedDirectory;
                scanFolderLabel.setText( LocalizationManager.format( "settings.scanFolderLabel", scanFolder ) );
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
                            scanBtn.setText( LocalizationManager.get( "settings.scanBtn.cancel" ) );
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
                            scanOutputLabel.setText( LocalizationManager.get( "settings.scanOutputLabel.failed" ) );
                            Logger.logError( LocalizationManager.get( "log.settings.scanFailed" ) );
                            Logger.logThrowable( e );
                        }
                        scanning = false;

                        // Restore GUI
                        GUIUtilities.JFXPlatformRun( () -> {
                            saveBtn.setDisable( false );
                            returnBtn.setDisable( false );
                            scanProgressBar.setVisible( false );
                            scanFolderBtn.setVisible( true );
                            scanBtn.setText( LocalizationManager.get( "settings.scanBtn.label" ) );
                            scanFolderBtn.setDisable( false );
                        } );
                    } );
                }
                else {
                    Logger.logWarning( LocalizationManager.get( "log.settings.noFolderSelected" ) );
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
                        LocalizationManager.get( "notification.settings.curseforgeKeySaved.title" ),
                        LocalizationManager.get( "notification.settings.curseforgeKeySaved.body" ) );
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
                            LocalizationManager.get( "notification.settings.nothingToVerify.title" ),
                            LocalizationManager.get( "notification.settings.nothingToVerify.body" ) );
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
                    LocalizationManager.get( "settings.curseforge.keyConfigured" ) );
        }
        else {
            curseForgeApiKeyStatusLabel.setText(
                    LocalizationManager.get( "settings.curseforge.keyNotConfigured" ) );
        }
    }

    private void setupAccountTab()
    {
        // Player profile
        var user = MCLauncherAuthManager.getLoggedInUser();
        accountNameLabel.setText( user.name() );
        accountUuidLabel.setText( LocalizationManager.format( "settings.accountUuidLabel", user.uuid() ) );
        // backgroundLoading = true so the avatar fetch happens off the
        // FX thread; otherwise the single-arg Image(url) constructor
        // blocks the JavaFX Application Thread on a network round-trip to
        // the avatar service (and hangs far longer on a slow/unreachable
        // network), freezing the Settings screen as it opens.
        accountAvatar.setImage( new javafx.scene.image.Image(
                com.micatechnologies.minecraft.launcher.consts.GUIConstants.URL_MINECRAFT_USER_ICONS
                        .replace( com.micatechnologies.minecraft.launcher.consts.GUIConstants.URL_MINECRAFT_USER_ICONS_USER_REPLACE_KEY,
                                  user.uuid() ), true ) );

        // Helpful links
        minecraftNetBtn.setOnAction( e -> SystemUtilities.spawnNewTask( () -> {
            try {
                java.awt.Desktop.getDesktop().browse( java.net.URI.create( "https://www.minecraft.net/en-us/profile" ) );
            }
            catch ( IOException ex ) {
                Logger.logError( LocalizationManager.get( "log.settings.openBrowserFailed" ) );
                Logger.logThrowable( ex );
            }
        } ) );
        msAccountBtn.setOnAction( e -> SystemUtilities.spawnNewTask( () -> {
            try {
                java.awt.Desktop.getDesktop().browse( java.net.URI.create( "https://account.microsoft.com/" ) );
            }
            catch ( IOException ex ) {
                Logger.logError( LocalizationManager.get( "log.settings.openBrowserFailed" ) );
                Logger.logThrowable( ex );
            }
        } ) );

        // Logout with confirmation
        logoutBtn.setOnAction( e -> {
            javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.CONFIRMATION );
            confirm.setTitle( LocalizationManager.get( "dialog.settings.logout.title" ) );
            confirm.setHeaderText( LocalizationManager.get( "dialog.settings.logout.header" ) );
            confirm.setContentText( LocalizationManager.get( "dialog.settings.logout.body" ) );
            confirm.initOwner( stage );
            confirm.showAndWait().ifPresent( response -> {
                if ( response == javafx.scene.control.ButtonType.OK ) {
                    MCLauncherAuthManager.logout();
                    LauncherCore.restartApp();
                }
            } );
        } );

        // "Add Another Account" — archives the current login so it can
        // be re-activated later from the Saved Accounts list, then
        // restarts the launcher to land back on the login screen.
        addAccountBtn.setOnAction( e -> {
            int response = GUIUtilities.showQuestionMessage(
                    LocalizationManager.get( "settings.savedAccounts.confirmAdd.title" ),
                    LocalizationManager.get( "settings.savedAccounts.confirmAdd.body" ),
                    "",
                    LocalizationManager.get( "settings.fxml.addAccount" ),
                    LocalizationManager.get( "dialog.button.cancel" ), stage );
            if ( response != 1 ) return;
            com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager.archiveAndLogout();
            LauncherCore.restartApp();
        } );

        rebuildSavedAccountsList();
    }

    /** Renders the Saved Accounts container's rows from the
     *  {@link com.micatechnologies.minecraft.launcher.game.auth.ProfileArchive}
     *  list. Skips the currently-active profile so we don't surface
     *  a "Switch" button pointing at the user's own already-active
     *  identity. */
    private void rebuildSavedAccountsList() {
        if ( savedAccountsList == null ) return;
        savedAccountsList.getChildren().clear();
        var active = MCLauncherAuthManager.getLoggedInUser();
        String activeUuid = active == null ? null : active.uuid();

        var profiles = com.micatechnologies.minecraft.launcher.game.auth.ProfileArchive.list();
        if ( profiles.isEmpty() ) {
            return;  // empty state: nothing to render; hint label is in FXML
        }
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat( "yyyy-MM-dd HH:mm" );
        for ( var entry : profiles ) {
            if ( activeUuid != null && activeUuid.equals( entry.uuid() ) ) continue;

            javafx.scene.layout.HBox row = new javafx.scene.layout.HBox( 8 );
            row.setAlignment( javafx.geometry.Pos.CENTER_LEFT );

            // Avatar — same Crafatar URL pattern the active player uses.
            javafx.scene.image.ImageView av = new javafx.scene.image.ImageView();
            av.setFitWidth( 32 );
            av.setFitHeight( 32 );
            av.setPreserveRatio( true );
            String avatarUrl = com.micatechnologies.minecraft.launcher.consts.GUIConstants.URL_MINECRAFT_USER_ICONS
                    .replace( com.micatechnologies.minecraft.launcher.consts.GUIConstants.URL_MINECRAFT_USER_ICONS_USER_REPLACE_KEY,
                              entry.uuid() );
            av.setImage( new javafx.scene.image.Image( avatarUrl, true ) );

            javafx.scene.layout.VBox info = new javafx.scene.layout.VBox( 2 );
            Label nameLbl = new Label( entry.displayName() == null || entry.displayName().isBlank()
                                                ? entry.uuid() : entry.displayName() );
            nameLbl.setStyle( "-fx-font-weight: bold;" );
            Label lastLbl = new Label( LocalizationManager.format( "settings.savedAccounts.lastUsed",
                    fmt.format( new java.util.Date( entry.lastUsedMs() ) ) ) );
            lastLbl.getStyleClass().add( "muted" );
            info.getChildren().addAll( nameLbl, lastLbl );
            javafx.scene.layout.HBox.setHgrow( info, javafx.scene.layout.Priority.ALWAYS );

            MFXButton switchBtn = new MFXButton( LocalizationManager.get( "settings.savedAccounts.switchBtn" ) );
            switchBtn.setPrefHeight( 28 );
            switchBtn.setOnAction( e -> SystemUtilities.spawnNewTask( () -> {
                boolean ok = MCLauncherAuthManager.switchToArchivedProfile( entry.uuid() );
                if ( ok ) {
                    GUIUtilities.JFXPlatformRun( LauncherCore::restartApp );
                }
            } ) );

            MFXButton forgetBtn = new MFXButton( LocalizationManager.get( "settings.savedAccounts.forgetBtn" ) );
            forgetBtn.setPrefHeight( 28 );
            forgetBtn.getStyleClass().add( "dangerZone" );
            forgetBtn.setOnAction( e -> SystemUtilities.spawnNewTask( () -> {
                int resp = GUIUtilities.showQuestionMessage(
                        LocalizationManager.get( "settings.savedAccounts.confirmForget.title" ),
                        LocalizationManager.format( "settings.savedAccounts.confirmForget.body",
                                                     entry.displayName() == null
                                                             ? entry.uuid() : entry.displayName() ),
                        "",
                        LocalizationManager.get( "settings.savedAccounts.forgetBtn" ),
                        LocalizationManager.get( "dialog.button.cancel" ), stage );
                if ( resp != 1 ) return;
                com.micatechnologies.minecraft.launcher.game.auth.ProfileArchive.forget( entry.uuid() );
                GUIUtilities.JFXPlatformRun( this::rebuildSavedAccountsList );
            } ) );

            row.getChildren().addAll( av, info, switchBtn, forgetBtn );
            savedAccountsList.getChildren().add( row );
        }
    }

    /**
     * Populates the About / Attributions tab. Sets the version label and wires the two action
     * buttons (Visit Website + View Source). The trademark notice and open-source acknowledgments
     * are static text in the FXML — no controller wiring needed.
     */
    private void setupAboutTab()
    {
        if ( aboutVersionLabel != null ) {
            aboutVersionLabel.setText( LocalizationManager.format( "settings.aboutVersionLabel", LauncherConstants.LAUNCHER_APPLICATION_VERSION ) );
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
                    Logger.logWarningSilent( LocalizationManager.format( "log.settings.openWebsiteFailed", ex.getMessage() ) );
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
                    Logger.logWarningSilent( LocalizationManager.format( "log.settings.openSourceRepoFailed", ex.getMessage() ) );
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
        //
        // Listener idempotency note: MFXComboBox.selectItem defers its
        // ActionEvent firing to the next FX pulse, so even though we
        // call setOnAction AFTER selectItem, the lambda still gets
        // invoked once at startup with the same value selectItem just
        // set. Razer Chroma's session model breaks if we open / close
        // sessions in rapid succession (Synapse silently drops the
        // most recent one), so every spurious "the value didn't really
        // change" restart was producing a connection-refused state on
        // the user's actual Test Connection click. The no-op guard
        // below compares against the persisted config value rather than
        // trusting the event-firing order.
        if ( rgbBackendCombo != null ) {
            rgbBackendCombo.setItems( javafx.collections.FXCollections.observableArrayList(
                    "Auto", "OpenRGB", "Razer Chroma (Native)", "Razer Chroma (REST)",
                    "Windows Dynamic Lighting", "Corsair iCUE", "ASUS Aura", "None" ) );
            rgbBackendCombo.selectItem( labelForBackend( ConfigManager.getRgbBackend() ) );
            rgbBackendCombo.setOnAction( e -> {
                String label = rgbBackendCombo.getValue();
                String newBackend = backendForLabel( label );
                if ( newBackend.equals( ConfigManager.getRgbBackend() ) ) {
                    return; // value didn't actually change — don't churn the controller
                }
                ConfigManager.setRgbBackend( newBackend );
                refreshAutoToggleVisibility();
                restartRgbController();
            } );
        }

        if ( rgbEnableToggle != null ) {
            rgbEnableToggle.setSelected( ConfigManager.getRgbEnable() );
            rgbEnableToggle.selectedProperty().addListener( ( obs, oldV, newV ) -> {
                if ( newV == null || newV == ConfigManager.getRgbEnable() ) {
                    return; // no real change — guard against spurious property fires
                }
                ConfigManager.setRgbEnable( newV );
                refreshAutoToggleVisibility();
                restartRgbController();
            } );
        }

        if ( rgbMenuEffectToggle != null ) {
            rgbMenuEffectToggle.setSelected( ConfigManager.getRgbMenuEffectEnable() );
            rgbMenuEffectToggle.selectedProperty().addListener( ( obs, oldV, newV ) -> {
                ConfigManager.setRgbMenuEffectEnable( newV );
                // Repaint immediately so the user sees the effect of
                // the toggle without having to leave/re-enter Settings.
                com.micatechnologies.minecraft.launcher.rgb.RgbIntegration.onMenu();
            } );
        }

        if ( rgbEffectStyleSelection != null ) {
            // Display names map to stable config-value identifiers.
            // LinkedHashMap so iteration order matches the order shown
            // in the dropdown.
            java.util.LinkedHashMap< String, String > styleDisplay = new java.util.LinkedHashMap<>();
            styleDisplay.put( "Solid",   ConfigConstants.RGB_EFFECT_STYLE_SOLID );
            styleDisplay.put( "Breathe", ConfigConstants.RGB_EFFECT_STYLE_BREATHE );
            styleDisplay.put( "Pulse",   ConfigConstants.RGB_EFFECT_STYLE_PULSE );
            styleDisplay.put( "Cycle",   ConfigConstants.RGB_EFFECT_STYLE_CYCLE );
            styleDisplay.put( "Rainbow", ConfigConstants.RGB_EFFECT_STYLE_RAINBOW );

            rgbEffectStyleSelection.getItems().clear();
            rgbEffectStyleSelection.getItems().addAll( styleDisplay.keySet() );

            String currentValue = ConfigManager.getRgbEffectStyle();
            for ( var entry : styleDisplay.entrySet() ) {
                if ( entry.getValue().equals( currentValue ) ) {
                    rgbEffectStyleSelection.selectItem( entry.getKey() );
                    break;
                }
            }

            rgbEffectStyleSelection.selectedItemProperty().addListener( ( obs, oldV, newV ) -> {
                if ( newV == null ) return;
                String value = styleDisplay.get( newV );
                if ( value == null ) return;
                ConfigManager.setRgbEffectStyle( value );
                // Immediate repaint so the user sees the new style
                // without leaving Settings.
                com.micatechnologies.minecraft.launcher.rgb.RgbIntegration.onMenu();
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

        // Per-backend Auto-mode integration toggles. Each one persists
        // its own enable flag and triggers a restart so the controller
        // immediately picks up the new active set. Same idempotency
        // guard as the master toggle — comparing against the persisted
        // value rather than oldV catches spurious property fires (the
        // pattern that previously caused Razer Synapse session churn).
        wireBackendToggle( rgbEnableOpenRgbToggle,
                ConfigManager.getRgbEnableOpenRgb(), ConfigManager::setRgbEnableOpenRgb,
                ConfigManager::getRgbEnableOpenRgb );
        wireBackendToggle( rgbEnableChromaNativeToggle,
                ConfigManager.getRgbEnableChromaNative(), ConfigManager::setRgbEnableChromaNative,
                ConfigManager::getRgbEnableChromaNative );
        wireBackendToggle( rgbEnableChromaRestToggle,
                ConfigManager.getRgbEnableChromaRest(), ConfigManager::setRgbEnableChromaRest,
                ConfigManager::getRgbEnableChromaRest );
        wireBackendToggle( rgbEnableWindowsDlToggle,
                ConfigManager.getRgbEnableWindowsDl(), ConfigManager::setRgbEnableWindowsDl,
                ConfigManager::getRgbEnableWindowsDl );
        wireBackendToggle( rgbEnableCorsairToggle,
                ConfigManager.getRgbEnableCorsair(), ConfigManager::setRgbEnableCorsair,
                ConfigManager::getRgbEnableCorsair );
        wireBackendToggle( rgbEnableAsusAuraToggle,
                ConfigManager.getRgbEnableAsusAura(), ConfigManager::setRgbEnableAsusAura,
                ConfigManager::getRgbEnableAsusAura );

        if ( rgbTestBtn != null ) {
            rgbTestBtn.setOnAction( e -> runRgbConnectionTest() );
        }

        refreshAutoToggleVisibility();
        refreshRgbStatusChip();
    }

    /** Wire one of the per-backend Auto-mode toggles. The supplied
     *  getter is consulted at event-fire time (not capture time) so
     *  the idempotency check sees the current persisted value rather
     *  than a stale snapshot. */
    private void wireBackendToggle( io.github.palexdev.materialfx.controls.MFXToggleButton toggle,
                                    boolean initialValue,
                                    java.util.function.Consumer< Boolean > setter,
                                    java.util.function.BooleanSupplier currentGetter )
    {
        if ( toggle == null ) return;
        toggle.setSelected( initialValue );
        toggle.selectedProperty().addListener( ( obs, oldV, newV ) -> {
            if ( newV == null || newV == currentGetter.getAsBoolean() ) {
                return; // no real change — guard against spurious property fires
            }
            setter.accept( newV );
            restartRgbController();
        } );
    }

    /** Auto-mode toggles are only meaningful when the backend choice is
     *  Auto — in Manual mode the user has explicitly picked one backend,
     *  so the integration filter doesn't apply. Hide them rather than
     *  disable so the section doesn't compete for attention. */
    private void refreshAutoToggleVisibility()
    {
        boolean isAuto = com.micatechnologies.minecraft.launcher.consts.ConfigConstants
                .RGB_BACKEND_AUTO.equals( ConfigManager.getRgbBackend() );
        boolean enabled = ConfigManager.getRgbEnable();
        boolean show = isAuto && enabled;
        if ( rgbAutoToggleHeader != null ) {
            rgbAutoToggleHeader.setVisible( show );
            rgbAutoToggleHeader.setManaged( show );
        }
        if ( rgbAutoToggleBox != null ) {
            rgbAutoToggleBox.setVisible( show );
            rgbAutoToggleBox.setManaged( show );
        }
    }

    /** Maps the config-stored backend identifier to the user-facing
     *  combo label. Defaults to "Auto" for unknown values. */
    private static String labelForBackend( String backend )
    {
        return switch ( backend == null ? "" : backend ) {
            case com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_OPENRGB       -> "OpenRGB";
            case com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_CHROMA_NATIVE -> "Razer Chroma (Native)";
            case com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_CHROMA        -> "Razer Chroma (REST)";
            case com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_WINDOWS_DL    -> "Windows Dynamic Lighting";
            case com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_CORSAIR       -> "Corsair iCUE";
            case com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_ASUS_AURA     -> "ASUS Aura";
            case com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_NONE          -> "None";
            default -> "Auto";
        };
    }

    /** Localized, human-readable label for an image-cycle interval token (e.g.
     *  {@code "30s"} → "30 seconds", {@code "never"} → "Never (use first)"). Keys
     *  live in the base bundle and fall back to English in untranslated locales. */
    private static String cycleIntervalLabel( String token )
    {
        return switch ( token == null ? "" : token ) {
            case "5s"  -> LocalizationManager.format( "settings.cycle.seconds", 5 );
            case "15s" -> LocalizationManager.format( "settings.cycle.seconds", 15 );
            case "30s" -> LocalizationManager.format( "settings.cycle.seconds", 30 );
            case "1m"  -> LocalizationManager.get( "settings.cycle.minute" );
            case "5m"  -> LocalizationManager.format( "settings.cycle.minutes", 5 );
            case "15m" -> LocalizationManager.format( "settings.cycle.minutes", 15 );
            case "30m" -> LocalizationManager.format( "settings.cycle.minutes", 30 );
            case "1h"  -> LocalizationManager.get( "settings.cycle.hour" );
            case "6h"  -> LocalizationManager.format( "settings.cycle.hours", 6 );
            case "12h" -> LocalizationManager.format( "settings.cycle.hours", 12 );
            case "1d"  -> LocalizationManager.get( "settings.cycle.daily" );
            case "7d"  -> LocalizationManager.get( "settings.cycle.weekly" );
            case "never" -> LocalizationManager.get( "settings.cycle.never" );
            default -> token;
        };
    }

    /** Inverse of {@link #labelForBackend}. */
    private static String backendForLabel( String label )
    {
        return switch ( label == null ? "" : label ) {
            case "OpenRGB"               -> com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_OPENRGB;
            case "Razer Chroma (Native)"    -> com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_CHROMA_NATIVE;
            case "Razer Chroma (REST)"      -> com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_CHROMA;
            case "Windows Dynamic Lighting" -> com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_WINDOWS_DL;
            case "Corsair iCUE"             -> com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_CORSAIR;
            case "ASUS Aura"                -> com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_ASUS_AURA;
            case "None"                     -> com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_NONE;
            default                      -> com.micatechnologies.minecraft.launcher.consts.ConfigConstants.RGB_BACKEND_AUTO;
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
                                    .resolveBackendsFromConfig() );
                }
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( LocalizationManager.get( "log.settings.rgbRestartThrew" ), t );
            }
            GUIUtilities.JFXPlatformRun( this::refreshRgbStatusChip );
        } );
    }

    /** Recomputes and re-applies the RGB status chip text + style. Safe
     *  to call from any thread; FX work is dispatched.
     *
     *  <p>For multi-backend sessions the chip shows the aggregate: one
     *  backend connected reads "Connected: OpenRGB"; multiple reads
     *  "Connected: OpenRGB +1" with the count of additional active
     *  backends. The chip style escalates to the worst per-backend
     *  health so any single backend going DEGRADED/DEAD surfaces
     *  visibly in the compact view.</p>
     */
    private void refreshRgbStatusChip()
    {
        if ( rgbStatusChip == null ) return;
        GUIUtilities.JFXPlatformRun( () -> {
            com.micatechnologies.minecraft.launcher.rgb.RgbController.Status s =
                    com.micatechnologies.minecraft.launcher.rgb.RgbController.getInstance().status();
            String text;
            String styleSuffix;
            if ( !ConfigManager.getRgbEnable() ) {
                text = LocalizationManager.get( "settings.rgb.status.disabled" );
                styleSuffix = ""; // base muted chip
            }
            else if ( !s.running() || s.backends().isEmpty() ) {
                text = LocalizationManager.get( "settings.rgb.status.notDetected" );
                styleSuffix = "-warn";
            }
            else {
                String label = s.primaryName();
                int extra = s.backends().size() - 1;
                if ( extra > 0 ) {
                    label = label + " +" + extra;
                }
                com.micatechnologies.minecraft.launcher.rgb.RgbBackendHealth.State worst = s.worstHealth();
                text = switch ( worst ) {
                    case HEALTHY  -> LocalizationManager.format( "settings.rgb.status.connected", label );
                    case DEGRADED -> LocalizationManager.format( "settings.rgb.status.degraded", label );
                    case DEAD     -> LocalizationManager.format( "settings.rgb.status.unavailable", label );
                };
                styleSuffix = switch ( worst ) {
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

    /** Flashes solid magenta (255, 0, 255) on every connected device
     *  for ~2 seconds, then restores whatever effect was active. Lets
     *  the user confirm the backend is reaching their keyboard before
     *  relying on it during a launch. Magenta is used (rather than the
     *  theme accent) so the flash reads as a deliberate signal regardless
     *  of which theme is active. */
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
                            LocalizationManager.get( "notification.settings.rgbDisabled.title" ),
                            LocalizationManager.get( "notification.settings.rgbDisabled.body" ) );
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
                Logger.logWarningSilent( LocalizationManager.get( "log.settings.rgbTestThrew" ), t );
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
                logOutput.apply( LocalizationManager.format( "settings.scan.supplemental", f ) );
            }
        }
        catch ( IOException e ) {
            logOutput.apply( LocalizationManager.format( "settings.scan.supplementalIoError", e.getMessage() ) );
        }

        int stage1 = scanResults.getStage1Detections() == null ? 0 : scanResults.getStage1Detections().size();
        int stage2 = scanResults.getStage2Detections() == null ? 0 : scanResults.getStage2Detections().size();
        int total = stage1 + stage2 + supplementalHigh + supplementalMedium;
        if ( total > 0 ) {
            logOutput.apply( LocalizationManager.format( "settings.scan.infectionsFound",
                                     stage1, stage2, supplementalHigh, supplementalMedium ) );
        }
        else if ( scanningCanceled ) {
            logOutput.apply( LocalizationManager.get( "settings.scan.canceled" ) );
            scanningCanceled = false;
        }
        else {
            logOutput.apply( LocalizationManager.get( "settings.scan.noInfections" ) );
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
        TooltipManager.install( minRamGb, LocalizationManager.get( "tooltip.settings.minRam" ) );
        TooltipManager.install( maxRamGb, LocalizationManager.get( "tooltip.settings.maxRam" ) );
        TooltipManager.install( debugCheckBox, LocalizationManager.get( "tooltip.settings.debug" ) );
        TooltipManager.install( windowResizeCheckBox,
                LocalizationManager.get( "tooltip.settings.windowResize" ) );
        TooltipManager.install( discordCheckBox, LocalizationManager.get( "tooltip.settings.discord" ) );
        TooltipManager.install( discordInvitesCheckBox,
                LocalizationManager.get( "tooltip.settings.discordInvites" ) );
        TooltipManager.install( enhancedLoggingCheckBox,
                LocalizationManager.get( "tooltip.settings.enhancedLogging" ) );
        TooltipManager.install( inGameConsoleCheckBox,
                LocalizationManager.get( "tooltip.settings.inGameConsole" ) );
        TooltipManager.install( batteryThrottleCheckBox,
                LocalizationManager.get( "tooltip.settings.batteryThrottle" ) );
        TooltipManager.install( themeSelection, LocalizationManager.get( "tooltip.settings.theme" ) );
        TooltipManager.install( jvmPresetSelection, LocalizationManager.get( "tooltip.settings.jvmPreset" ) );
        TooltipManager.install( proxyEnableCheckBox,
                LocalizationManager.get( "tooltip.settings.proxyEnable" ) );
        TooltipManager.install( proxyHostField, LocalizationManager.get( "tooltip.settings.proxyHost" ) );
        TooltipManager.install( proxyPortSpinner, LocalizationManager.get( "tooltip.settings.proxyPort" ) );
        TooltipManager.install( proxyTypeSelection, LocalizationManager.get( "tooltip.settings.proxyType" ) );
        TooltipManager.install( resetLauncherBtn,
                LocalizationManager.get( "tooltip.settings.resetLauncher" ) );
        TooltipManager.install( resetRuntimeBtn,
                LocalizationManager.get( "tooltip.settings.resetRuntime" ) );
        TooltipManager.install( exportSettingsBtn,
                LocalizationManager.get( "tooltip.settings.exportSettings" ) );
        TooltipManager.install( importSettingsBtn,
                LocalizationManager.get( "tooltip.settings.importSettings" ) );

        // Navbar help button — same pattern the main menu uses (Label with .helpButton
        // styleClass). MCLauncherGuiWindow.injectHelpButton() detects the navbar entry
        // and skips its corner-overlay fallback. Wire the click handler + tooltip here.
        if ( helpBtn != null ) {
            helpBtn.setOnMouseClicked( e -> MCLauncherHelpWindow.show( getHelpTopic() ) );
            helpBtn.setCursor( javafx.scene.Cursor.HAND );
            TooltipManager.install( helpBtn, LocalizationManager.get( "tooltip.common.help" ) );
        }

        OfflineIndicator.applyTo( offlineLabel );

        // Cross-platform shortcuts: Settings / Browse / Editor / Home / Help.
        KeyboardShortcutManager.installGlobalShortcuts( scene, this::getHelpTopic );
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
     * Guards an external navigation (a {@code mmcl://} deep-link launching a game) against
     * silently discarding unsaved settings edits. When there are pending changes, prompts
     * the user to save-and-continue, discard-and-continue, or cancel. Mirrors the
     * return-button / window-close unsaved-changes flows already wired in {@link #setup()}.
     *
     * <p>Invoked from the URI handler's worker thread, so the dialog (which blocks until
     * answered) runs off the FX thread exactly like the other two unsaved-changes prompts.</p>
     *
     * @return {@code true} to let the launch proceed (after optionally saving),
     *         {@code false} to stay on settings and abort the deep-link launch
     *
     * @since 3.5
     */
    @Override
    public boolean confirmNavigateAwayForDeepLink() {
        if ( !hasUnsavedChanges() ) {
            return true;
        }
        int response = GUIUtilities.showQuestionMessage(
                LocalizationManager.get( "dialog.settings.unsavedOnLaunch.title" ),
                LocalizationManager.get( "dialog.settings.unsavedOnLaunch.header" ),
                LocalizationManager.get( "dialog.settings.unsavedOnLaunch.body" ),
                LocalizationManager.get( "dialog.settings.unsavedOnLaunch.button.saveAndContinue" ),
                LocalizationManager.get( "dialog.settings.unsavedOnLaunch.button.discardAndContinue" ),
                stage );
        if ( response == 1 ) {
            // Save, then let the launch proceed. saveBtn's handler reads the control values
            // (which survive the upcoming scene swap), so firing it here is safe.
            GUIUtilities.JFXPlatformRun( () -> saveBtn.fire() );
            return true;
        }
        if ( response == 2 ) {
            // Discard unsaved edits and proceed.
            return true;
        }
        // Cancel (0) -- stay on settings, abort the launch.
        return false;
    }

    /**
     * Writes every settings control's current value back to {@link ConfigManager}
     * and applies the immediate, non-restart side effects (theme refresh, proxy
     * reload, stage resizability, RGB repaint). Shared by the Save and Save &amp;
     * Restart buttons so the two paths can never drift.
     *
     * <p>Runs on a background thread (both callers wrap it in
     * {@link SystemUtilities#spawnNewTask}); the few main-thread touches it needs
     * are individually marshalled via {@link GUIUtilities#JFXPlatformRun}. Reading
     * control values off the FX thread matches the long-standing save-handler
     * pattern — nothing mutates these controls concurrently while a save runs.</p>
     *
     * @since 2026.6
     */
    private void persistSettings() {
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

        // Console log buffer-size: map selected label back to its
        // preset value via index. Falls through silently when
        // selection is null (initial empty state).
        if ( consoleLogMaxLinesSelection != null
                && consoleLogMaxLinesSelection.getSelectedItem() != null ) {
            int idx = consoleLogMaxLinesSelection.getSelectedIndex();
            if ( idx >= 0 && idx < ConfigConstants.CONSOLE_LOG_MAX_LINES_PRESETS.length ) {
                ConfigManager.setConsoleLogMaxLines(
                        ConfigConstants.CONSOLE_LOG_MAX_LINES_PRESETS[ idx ] );
            }
        }

        // Store battery throttle preference
        ConfigManager.setBatteryThrottleEnable( batteryThrottleCheckBox.isSelected() );

        // Store LWJGL ARM64 patching to config
        ConfigManager.setLwjglArmPatchEnable( lwjglArmPatchCheckBox.isSelected() );

        // Store theme selection
        if ( ConfigConstants.ALLOWED_THEMES.contains( themeSelection.getSelectedItem() ) ) {
            ConfigManager.setTheme( themeSelection.getSelectedItem() );
            MCLauncherGuiController.forceThemeRefresh();
            // The menu RGB effect derives its accent from the active
            // theme — repaint so the user's keyboard reflects the new
            // theme immediately instead of waiting for the next idle
            // tick / game-exit transition.
            com.micatechnologies.minecraft.launcher.rgb.RgbIntegration.onMenu();
        }

        // Store language override. The first dropdown item is the
        // "Use OS Language" sentinel which clears the override; any
        // other selection maps back to a BCP-47 tag via the
        // SupportedLocales lookup. New value takes effect on the
        // next launcher restart — Locale.setDefault has already run
        // for this session and the 89 static-final translation
        // fields are locked at the launch-time bundle.
        if ( languageSelection != null && languageSelection.getSelectedItem() != null ) {
            ConfigManager.setLocaleOverride( selectedLanguageOverrideTag() );
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
    }

    /**
     * Resolves the BCP-47 override tag a language-dropdown display label maps to:
     * a concrete tag for a named locale, or the empty string for the "Use OS
     * Language" sentinel / any unrecognized label (which clears the override).
     * Mirrors the lookup {@link #persistSettings()} writes, so visibility logic
     * and the actual save agree on what "changed" means. Pure + static so it can
     * be unit-tested without an FX scene.
     *
     * @param selectedDisplay the dropdown's selected display label (may be null)
     *
     * @return the override tag for that label, or {@code ""} for OS-default
     */
    static String overrideTagForDisplay( String selectedDisplay ) {
        if ( selectedDisplay == null ) {
            return "";
        }
        for ( var entry : com.micatechnologies.minecraft.launcher.consts.localization
                .SupportedLocales.ENTRIES ) {
            if ( entry.displayName().equals( selectedDisplay ) ) {
                return entry.tag();
            }
        }
        // OS-default sentinel (or any unrecognized display) → clear the override.
        return "";
    }

    /**
     * Whether the dropdown selection represents a language change relative to the
     * saved override — i.e. the Save &amp; Restart shortcut should be shown. Pure +
     * static for unit testing; the live UI passes the current selection label and
     * {@code ConfigManager.getLocaleOverride()}.
     *
     * @param selectedDisplay the dropdown's selected display label
     * @param savedOverrideTag the persisted locale override (may be null/blank for OS-default)
     *
     * @return true when the selection differs from what is saved
     */
    static boolean isLanguageChangePending( String selectedDisplay, String savedOverrideTag ) {
        String saved = savedOverrideTag == null ? "" : savedOverrideTag;
        return !overrideTagForDisplay( selectedDisplay ).equalsIgnoreCase( saved );
    }

    /**
     * Resolves the BCP-47 override tag the language dropdown's current selection
     * maps to (or {@code ""} for OS-default). Instance wrapper over
     * {@link #overrideTagForDisplay(String)} used by {@link #persistSettings()}.
     *
     * @return the override tag for the current selection, or {@code ""} for OS-default
     */
    private String selectedLanguageOverrideTag() {
        if ( languageSelection == null ) {
            return "";
        }
        return overrideTagForDisplay( languageSelection.getSelectedItem() );
    }

    /**
     * Shows the Save &amp; Restart button only when the language dropdown's
     * resolved override tag differs from the saved override — i.e. a language
     * change is pending that a relaunch would apply. Toggles both visibility and
     * managed-ness so the button takes no layout space while hidden. Must run on
     * the FX thread.
     */
    private void refreshSaveAndRestartButton() {
        if ( saveAndRestartBtn == null ) {
            return;
        }
        String selectedDisplay = languageSelection == null ? null : languageSelection.getSelectedItem();
        boolean pending = isLanguageChangePending( selectedDisplay, ConfigManager.getLocaleOverride() );
        saveAndRestartBtn.setVisible( pending );
        saveAndRestartBtn.setManaged( pending );
    }

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
