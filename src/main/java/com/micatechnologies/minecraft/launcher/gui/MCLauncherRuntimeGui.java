/*
 * Copyright (c) 2026 Mica Technologies
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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.RuntimeManager;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import io.github.palexdev.materialfx.controls.MFXButton;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Controller for the runtime management GUI screen. Allows users to view, refresh, and delete installed Java runtimes.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2025.1
 */
public class MCLauncherRuntimeGui extends MCLauncherAbstractGui
{
    /**
     * Optional announcement banner shown at the top of the screen, populated by
     * shared navbar logic when the launcher has an announcement to surface.
     *
     * @since 2025.1
     */
    @SuppressWarnings( "unused" )
    @FXML
    Label announcement;

    /**
     * Layout row backing the {@link #announcement} banner. Collapsed when there
     * is no announcement so the banner reserves no vertical space.
     *
     * @since 2025.1
     */
    @SuppressWarnings( "unused" )
    @FXML
    javafx.scene.layout.RowConstraints announcementRow;

    /**
     * Navbar help button, wired in {@link #setup()} to open the help window for
     * this screen's {@link #getHelpTopic() help topic}.
     *
     * @since 2025.1
     */
    @SuppressWarnings( "unused" )
    @FXML
    Label helpBtn;

    /**
     * Navbar offline indicator, driven by {@link OfflineIndicator} to reflect
     * the launcher's online/offline state.
     *
     * @since 2025.1
     */
    @SuppressWarnings( "unused" )
    @FXML
    Label offlineLabel;

    /**
     * Status line that reports the outcome of the most recent action (count of
     * installed runtimes, deletion results, or error messages).
     *
     * @since 2025.1
     */
    @SuppressWarnings( "unused" )
    @FXML
    Label statusLabel;

    /**
     * Static informational text describing the runtime management screen.
     *
     * @since 2025.1
     */
    @SuppressWarnings( "unused" )
    @FXML
    Label infoLabel;

    /**
     * List view showing one row per installed Java runtime (component, version,
     * and on-disk size). Selection here drives the per-runtime delete action.
     *
     * @since 2025.1
     */
    @SuppressWarnings( "unused" )
    @FXML
    ListView< String > runtimeListView;

    /**
     * Button that re-scans and reloads the installed-runtime list.
     *
     * @since 2025.1
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton refreshBtn;

    /**
     * Button that deletes the runtime currently selected in
     * {@link #runtimeListView}, after a confirmation prompt.
     *
     * @since 2025.1
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton deleteBtn;

    /**
     * Button that deletes every installed runtime, after a confirmation prompt.
     *
     * @since 2025.1
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton deleteAllBtn;

    /**
     * Button that returns to the settings screen.
     *
     * @since 2025.1
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton returnBtn;

    /**
     * The currently loaded list of runtime info maps. Reassigned by
     * {@link #refreshRuntimeList()} which can run on background threads, and read by
     * the delete handlers, so it is {@code volatile} and is snapshotted into a local
     * before being indexed to avoid a refresh swapping it mid-operation.
     */
    private volatile List< Map< String, String > > currentRuntimes;

    /**
     * Constructs the runtime management GUI bound to the given stage, using the
     * abstract GUI's default scene dimensions.
     *
     * @param stage the JavaFX stage that hosts this screen
     *
     * @throws IOException if the backing FXML resource fails to load
     *
     * @since 2025.1
     */
    public MCLauncherRuntimeGui( Stage stage ) throws IOException {
        super( stage );
    }

    /**
     * {@inheritDoc}
     *
     * @return the classpath-relative path to this screen's FXML layout
     *         ({@code gui/runtimeManagementGUI.fxml})
     *
     * @since 2025.1
     */
    @Override
    String getSceneFxmlPath() {
        return "gui/runtimeManagementGUI.fxml";
    }

    /**
     * {@inheritDoc}
     *
     * @return the human-readable scene name shown in the window title
     *         ({@code "Runtime Management"})
     *
     * @since 2025.1
     */
    @Override
    String getSceneName() {
        return "Runtime Management";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Wires the OS close button to close the application, the return button
     * back to settings, the navbar help button, the offline indicator, global
     * keyboard shortcuts, and the refresh / delete / delete-all actions. The
     * delete handlers snapshot {@link #currentRuntimes} on the FX thread to
     * avoid a concurrent refresh swapping the list mid-operation, prompt for
     * confirmation, then run the deletion off the FX thread. Finishes with an
     * initial {@link #refreshRuntimeList()} to populate the list.</p>
     *
     * @since 2025.1
     */
    @Override
    void setup() {
        // Configure window close -- X button closes the app
        stage.setOnCloseRequest( windowEvent -> {
            windowEvent.consume();
            LauncherCore.closeApp();
        } );

        // Return button
        returnBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToSettingsGui();
            }
            catch ( IOException e ) {
                Logger.logError( LocalizationManager.get( "log.runtime.returnToSettingsFailed" ) );
                Logger.logThrowable( e );
            }
        } ) );

        // Wire the navbar help button.
        if ( helpBtn != null ) {
            helpBtn.setOnMouseClicked( e -> MCLauncherHelpWindow.show( getHelpTopic() ) );
            helpBtn.setCursor( javafx.scene.Cursor.HAND );
            TooltipManager.install( helpBtn, LocalizationManager.get( "tooltip.common.help" ) );
        }

        OfflineIndicator.applyTo( offlineLabel );

        // Cross-platform shortcuts.
        KeyboardShortcutManager.installGlobalShortcuts( scene, this::getHelpTopic );

        // Refresh button
        refreshBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( this::refreshRuntimeList ) );

        // Delete selected runtime
        deleteBtn.setOnAction( actionEvent -> {
            // Resolve the selection on the FX thread (where the action fires) against a
            // single snapshot of currentRuntimes, so a concurrent refreshRuntimeList()
            // can't reassign the list between reading the selected index and indexing it
            // -- which would otherwise delete the wrong runtime.
            int selectedIndex = runtimeListView.getSelectionModel().getSelectedIndex();
            List< Map< String, String > > snapshot = currentRuntimes;
            if ( selectedIndex < 0 || snapshot == null || selectedIndex >= snapshot.size() ) {
                statusLabel.setText( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "runtime.status.noneSelected" ) );
                return;
            }

            String component = snapshot.get( selectedIndex ).get( "component" );

            SystemUtilities.spawnNewTask( () -> {
            int response = GUIUtilities.showQuestionMessage(
                    LocalizationManager.get( "dialog.runtime.delete.title" ),
                    LocalizationManager.format( "dialog.runtime.delete.header", component ),
                    LocalizationManager.get( "dialog.runtime.delete.body" ),
                    LocalizationManager.get( "dialog.runtime.delete.button.delete" ),
                    LocalizationManager.get( "dialog.button.cancel" ), stage );
            if ( response != 1 ) {
                return;
            }

            try {
                RuntimeManager.clearRuntime( component );
                GUIUtilities.JFXPlatformRun(
                        () -> statusLabel.setText( LocalizationManager.format( "runtime.status.deleted", component ) ) );
            }
            catch ( IOException e ) {
                Logger.logError( LocalizationManager.format( "log.runtime.deleteFailed", component ) );
                Logger.logThrowable( e );
                GUIUtilities.JFXPlatformRun(
                        () -> statusLabel.setText( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "runtime.status.deleteFailed" ) ) );
            }

            refreshRuntimeList();
            } );
        } );

        // Delete all runtimes
        deleteAllBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            // Snapshot once so the iteration below can't be disturbed by a concurrent
            // refreshRuntimeList() reassigning currentRuntimes.
            List< Map< String, String > > snapshot = currentRuntimes;
            if ( snapshot == null || snapshot.isEmpty() ) {
                GUIUtilities.JFXPlatformRun(
                        () -> statusLabel.setText( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "runtime.status.noneInstalled" ) ) );
                return;
            }

            int response = GUIUtilities.showQuestionMessage(
                    LocalizationManager.get( "dialog.runtime.deleteAll.title" ),
                    LocalizationManager.get( "dialog.runtime.deleteAll.header" ),
                    LocalizationManager.get( "dialog.runtime.deleteAll.body" ),
                    LocalizationManager.get( "dialog.runtime.deleteAll.button.deleteAll" ),
                    LocalizationManager.get( "dialog.button.cancel" ), stage );
            if ( response != 1 ) {
                return;
            }

            for ( Map< String, String > rt : snapshot ) {
                try {
                    RuntimeManager.clearRuntime( rt.get( "component" ) );
                }
                catch ( IOException e ) {
                    Logger.logError( LocalizationManager.format( "log.runtime.deleteFailed", rt.get( "component" ) ) );
                }
            }

            GUIUtilities.JFXPlatformRun(
                    () -> statusLabel.setText( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "runtime.status.allDeleted" ) ) );
            refreshRuntimeList();
        } ) );

        // Initial load
        refreshRuntimeList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>No post-show work is required for this screen.</p>
     *
     * @since 2025.1
     */
    @Override
    void afterShow() {
        // Nothing needed after show
    }

    /**
     * {@inheritDoc}
     *
     * <p>This screen holds no scene-scoped resources to release.</p>
     *
     * @since 2025.1
     */
    @Override
    void cleanup() {
        // Nothing to clean up
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link HelpTopic#RUNTIME_MANAGEMENT}, the help topic shown for the
     *         runtime management screen
     *
     * @since 2025.1
     */
    @Override
    HelpTopic getHelpTopic() { return HelpTopic.RUNTIME_MANAGEMENT; }

    /**
     * {@inheritDoc}
     *
     * <p>Disables toolbar navigation while a runtime is being installed or
     * verified.</p>
     *
     * @return {@code false}; toolbar navigation is not permitted from this screen
     *
     * @since 2025.1
     */
    @Override
    boolean allowsToolbarNavigation() { return false; }

    /**
     * Re-scans the installed Java runtimes via {@link RuntimeManager}, rebuilds
     * the {@link #runtimeListView} contents (component, optional verified
     * version, and on-disk size per row), installs the empty-state placeholder,
     * and updates {@link #statusLabel} with the installed count or an
     * empty-state message. The list-view mutation and status update are
     * marshalled onto the JavaFX application thread; this method itself may be
     * invoked from a background task.
     *
     * @since 2025.1
     */
    private void refreshRuntimeList() {
        currentRuntimes = RuntimeManager.getInstalledRuntimes();

        ObservableList< String > items = FXCollections.observableArrayList();
        for ( Map< String, String > rt : currentRuntimes ) {
            String display = rt.get( "component" );
            String version = rt.get( "version" );
            if ( version != null && !version.equals( "Not verified" ) ) {
                display += " (" + version + ")";
            }
            display += "  -  " + rt.get( "sizeMB" ) + " MB";
            items.add( display );
        }

        GUIUtilities.JFXPlatformRun( () -> {
            runtimeListView.setItems( items );
            ensureEmptyPlaceholder();
            if ( !currentRuntimes.isEmpty() ) {
                statusLabel.setText( LocalizationManager.format( "runtime.status.countInstalled", currentRuntimes.size() ) );
            }
            else {
                statusLabel.setText( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "runtime.status.noneInstalled" ) );
            }
        } );
    }

    /** Builds + installs the empty-state placeholder shown by the runtime
     *  {@link ListView} when {@code currentRuntimes} is empty. Replaces the
     *  prior approach of jamming "(No runtimes installed)" into the list
     *  itself, which looked clickable but did nothing and offered no path
     *  forward. {@code setPlaceholder} is the JavaFX-native channel for
     *  empty-state content — visible only when the list has zero items, so
     *  this is safe to call on every refresh. */
    private void ensureEmptyPlaceholder()
    {
        if ( runtimeListView.getPlaceholder() != null ) return;
        javafx.scene.control.Label heading = new javafx.scene.control.Label( LocalizationManager.get( "runtime.empty.heading" ) );
        heading.getStyleClass().add( "heading-h3" );
        javafx.scene.control.Label body = new javafx.scene.control.Label(
                LocalizationManager.get( "runtime.empty.body" ) );
        body.getStyleClass().add( "muted" );
        body.setWrapText( true );
        body.setMaxWidth( 420 );
        body.setStyle( "-fx-text-alignment: center;" );
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox( 8, heading, body );
        box.setAlignment( javafx.geometry.Pos.CENTER );
        box.setPadding( new javafx.geometry.Insets( 24 ) );
        runtimeListView.setPlaceholder( box );
    }
}
