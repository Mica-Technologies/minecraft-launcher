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
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.utilities.AnnouncementManager;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXComboBox;
import io.github.palexdev.materialfx.controls.MFXListView;
import io.github.palexdev.materialfx.controls.MFXTextField;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MCLauncherEditModPacksGui extends MCLauncherAbstractGui
{
    /**
     * List view of currently installed mod packs.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXListView< String > modpackList;

    /**
     * Add mod pack by URL button. This button processes the value in {@link #urlAddBox}.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton urlAddBtn;

    /**
     * Add mod pack by list button. This button processes the selected combo box entry of {@link #listAddBox}.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton listAddBtn;

    /**
     * Add mod pack by URL text field. This text field accepts entries for URLs that are to be added to the list of
     * installed mod packs.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXTextField urlAddBox;

    /**
     * Add mod pack by list dropdown box. This combo box lists mod packs that are approved and available for easy
     * install that have not already been installed.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXComboBox< String > listAddBox;

    /**
     * Return to main window button
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton returnBtn;

    /**
     * Remove selected mod packs button
     *
     * @since 2.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton removeSelectedBtn;

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

    private static final String UNAVAILABLE_PREFIX = "(Unavailable) ";

    /**
     * Constructor for abstract scene class that initializes {@link #scene} and sets <code>this</code> as the FXML
     * controller.
     *
     * @throws IOException if unable to load FXML file specified
     */
    public MCLauncherEditModPacksGui( Stage stage ) throws IOException {
        super( stage );
    }

    /**
     * Constructor for abstract scene class that initializes {@link #scene} and sets <code>this</code> as the FXML
     * controller.
     *
     * @throws IOException if unable to load FXML file specified
     */
    @SuppressWarnings( "unused" )
    public MCLauncherEditModPacksGui( Stage stage, double width, double height ) throws IOException {
        super( stage, width, height );
    }

    /**
     * Abstract method: This method must return the resource path for the JavaFX scene FXML file.
     *
     * @return JavaFX scene FXML resource path
     */
    @Override
    String getSceneFxmlPath() {
        return "gui/editGUI.fxml";
    }

    /**
     * Abstract method: This method must return the name of the JavaFX scene.
     *
     * @return Java FX scene name
     */
    @Override
    String getSceneName() {
        return "Edit Mod Packs";
    }

    void uninstallModPack( String name, boolean byUrl ) {
        if ( byUrl ) {
            GameModPackManager.uninstallModPackByURL( name );
        }
        else {
            GameModPackManager.uninstallModPackByFriendlyName( name );
        }
        try {
            MCLauncherGuiController.goToEditModpacksGui();
        }
        catch ( IOException e ) {
            Logger.logError( "Oops! Unable to reload edit mod packs GUI" );
            Logger.logThrowable( e );
            LauncherCore.closeApp();
        }
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

        // Display announcements if present
        String announcementText = AnnouncementManager.getAnnouncementModpacksEdit();
        if ( announcementText.length() > 0 ) {
            announcement.setText( announcementText );
            announcement.setMinHeight( 30 );
            announcementRow.setMinHeight( 30 );
        }
        else {
            announcement.setMaxHeight( 0 );
            announcementRow.setMaxHeight( 0 );
        }

        // Configure return button and window close
        returnBtn.setOnAction( actionEvent -> {
            try {
                MCLauncherGuiController.goToMainGui();
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to load main GUI due to an incomplete response from the GUI subsystem." );
                Logger.logThrowable( e );
            }
        } );

        // Configure add by URL button
        urlAddBtn.setDisable( AnnouncementManager.getDisableModpacksEdit() );
        urlAddBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            GameModPackManager.installModPackByURL( urlAddBox.getText() );
            try {
                MCLauncherGuiController.goToEditModpacksGui();
            }
            catch ( IOException e ) {
                Logger.logError( "Oops! Unable to reload edit mod packs GUI" );
                Logger.logThrowable( e );
                LauncherCore.closeApp();
            }
        } ) );

        // Configure add by List button
        listAddBtn.setDisable( AnnouncementManager.getDisableModpacksEdit() );
        listAddBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            GameModPackManager.installModPackByFriendlyName( listAddBox.getSelectedItem() );
            try {
                MCLauncherGuiController.goToEditModpacksGui();
            }
            catch ( IOException e ) {
                Logger.logError( "Oops! Unable to reload edit mod packs GUI" );
                Logger.logThrowable( e );
                LauncherCore.closeApp();
            }
        } ) );

        // Configure remove selected button
        removeSelectedBtn.setDisable( AnnouncementManager.getDisableModpacksEdit() );
        removeSelectedBtn.setOnAction( actionEvent -> {
            List< String > selectedItems = modpackList.getSelectionModel().getSelectedValues();
            for ( String selectedItem : selectedItems ) {
                modpackList.getItems().remove( selectedItem );
                SystemUtilities.spawnNewTask( () -> {
                    boolean isUnavailable = selectedItem.contains( UNAVAILABLE_PREFIX );
                    String preppedEventItem = selectedItem;
                    if ( isUnavailable ) {
                        preppedEventItem = selectedItem.substring(
                                selectedItem.indexOf( UNAVAILABLE_PREFIX ) + UNAVAILABLE_PREFIX.length() );
                    }
                    uninstallModPack( preppedEventItem, isUnavailable );
                } );
            }

        } );

        // Loads lists
        loadModPackList();
    }

    @Override
    void afterShow() {

    }

    @Override
    void cleanup() {

    }

    /**
     * Populates the installed mod pack list and installable mod pack lists.
     *
     * @since 1.0
     */
    private void loadModPackList() {
        // Get information before locking and updating GUI
        final List< String > installedModPackURLS = GameModPackManager.getInstalledModPackURLs();
        final List< String > availableModPackFriendlyNames = GameModPackManager.getAvailableModPackFriendlyNames();

        // Lock window during load
        modpackList.setDisable( true );
        urlAddBtn.setDisable( true );
        listAddBox.setDisable( true );
        listAddBtn.setDisable( true );
        returnBtn.setDisable( true );

        // Build list of installed mod packs to show (including unavailable)
        List< String > installedModPackFriendlyNames = new ArrayList<>();
        for ( String modPackUrl : installedModPackURLS ) {
            GameModPack modPackFromUrl = GameModPackManager.getInstalledModPackByURL( modPackUrl );
            if ( modPackFromUrl != null && modPackFromUrl.getFriendlyName() != null ) {
                installedModPackFriendlyNames.add( modPackFromUrl.getFriendlyName() );
            }
            else {
                installedModPackFriendlyNames.add( UNAVAILABLE_PREFIX + modPackUrl );
            }
        }

        // Add installed mod packs to list
        modpackList.setItems( FXCollections.observableList( installedModPackFriendlyNames ) );

        // Add available mod packs to list
        listAddBox.setItems( FXCollections.observableList( availableModPackFriendlyNames ) );

        // Unlock window when done
        modpackList.setDisable( false );
        urlAddBtn.setDisable( false );
        listAddBox.setDisable( false );
        listAddBtn.setDisable( false );
        returnBtn.setDisable( false );
    }
}
