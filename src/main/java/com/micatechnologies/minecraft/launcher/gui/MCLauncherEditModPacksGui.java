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

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXTextField;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.utilities.GUIUtilities;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import com.micatechnologies.minecraft.launcher.utilities.annotations.OnScreen;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.io.IOException;
import java.util.List;

public class MCLauncherEditModPacksGui extends MCLauncherAbstractGui
{
    /**
     * List view of currently installed mod packs.
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    ListView< String > modpackList;

    /**
     * Add mod pack by URL button. This button processes the value in {@link #urlAddBox}.
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    JFXButton urlAddBtn;

    /**
     * Add mod pack by list button. This button processes the selected combo box entry of {@link #listAddBox}.
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    JFXButton listAddBtn;

    /**
     * Add mod pack by URL text field. This text field accepts entries for URLs that are to be added to the list of
     * installed mod packs.
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    JFXTextField urlAddBox;

    /**
     * Add mod pack by list dropdown box. This combo box lists mod packs that are approved and available for easy
     * install that have not already been installed.
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    JFXComboBox< String > listAddBox;

    /**
     * Return to main window button
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    JFXButton returnBtn;

    /**
     * Constructor for abstract scene class that initializes {@link #scene} and sets <code>this</code> as the FXML
     * controller.
     *
     * @throws IOException if unable to load FXML file specified
     */
    public MCLauncherEditModPacksGui( Stage stage) throws IOException {
        super(stage);
    }

    /**
     * Custom {@link ListCell <String>} implementation that includes a button to remove the affiliated item from the
     * parent list.
     *
     * @version 1.0
     * @since 1.0
     */
    class XCell extends ListCell< String >
    {
        /**
         * Layout container for list cell.
         *
         * @since 1.0
         */
        HBox hbox = new HBox();

        /**
         * Label for list cell value (string).
         *
         * @since 1.0
         */
        Label label = new Label( "" );

        /**
         * Pane for separation between list cell value (string) and added button.
         *
         * @since 1.0
         */
        Pane   pane   = new Pane();
        /**
         * Added button for removing the associated item in the list.
         *
         * @since 1.0
         */
        Button button = new Button( "X" );

        /**
         * Constructor for custom list cell implementation that populates and styles each element.
         *
         * @since 1.0
         */
        public XCell() {
            super();

            pane.setPrefWidth( 10 );
            label.setStyle( "-fx-text-fill:black!important;" );
            button.setStyle( "-fx-text-fill:red!important;-fx-font-size:8;" );
            hbox.getChildren().addAll( label, pane, button );
            HBox.setHgrow( pane, Priority.ALWAYS );
            button.setOnAction( event -> {
                getListView().getItems().remove( getItem() );
                SystemUtilities.spawnNewTask( () -> {
                    GameModPackManager.uninstallModPackByFriendlyName( label.getText() );
                    loadModPackList();
                } );
            } );
        }

        /**
         * Overridden method for handling updates to the item in the list cell.
         *
         * @param item  new list cell value (string)
         * @param empty true if list cell should be empty
         *
         * @since 1.0
         */
        @Override
        protected void updateItem( String item, boolean empty ) {
            super.updateItem( item, empty );
            setText( null );
            setGraphic( null );

            if ( item != null && !empty ) {
                label.setText( item );
                label.setPrefHeight( button.getPrefHeight() );
                setGraphic( hbox );
            }
        }
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

    /**
     * Abstract method: This method must perform initialization and setup of the scene and @FXML components.
     */
    @Override
    void setup() {
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

        // Populate mod pack lists
        loadModPackList();

        // Configure add by URL button
        urlAddBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            rootPane.setDisable( true );
            GameModPackManager.installModPackByURL( urlAddBox.getText() );
            loadModPackList();
            MCLauncherGuiController.goToGui( this );
            rootPane.setDisable( false );
        } ) );

        // Configure add by List button
        listAddBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            rootPane.setDisable( true );
            GameModPackManager.installModPackByFriendlyName( listAddBox.getValue() );
            loadModPackList();
            MCLauncherGuiController.goToGui( this );
            rootPane.setDisable( false );
        } ) );
    }

    /**
     * Abstract method: This method must perform preparations of the environment, such as enabling menu bars, context
     * menus, or other OS-specific enhancements.
     */
    @Override
    void loadEnvironment() {

    }

    /**
     * Abstract method: This method returns a boolean indicating if a warning should be shown to the user before closing
     * the window while displaying the stage/GUI.
     *
     * @return boolean indicating if window close warning should be shown
     */
    @Override
    boolean warnOnExit() {
        return false;
    }

    /**
     * Populates the installed mod pack list and installable mod pack lists.
     *
     * @since 1.0
     */
    private void loadModPackList() {
        // Get information before locking and updating GUI
        final List< String > installedModPackFriendlyNames = GameModPackManager.getInstalledModPackFriendlyNames();
        final List< String > availableModPackFriendlyNames = GameModPackManager.getAvailableModPackFriendlyNames();

        GUIUtilities.JFXPlatformRun( () -> {
            // Lock window during load
            modpackList.setDisable( true );
            urlAddBtn.setDisable( true );
            listAddBox.setDisable( true );
            listAddBtn.setDisable( true );
            returnBtn.setDisable( true );

            // Set installed mod pack list cell factory
            modpackList.setCellFactory( stringListView -> new MCLauncherEditModPacksGui.XCell() );

            // Add installed mod packs to list
            modpackList.getItems().clear();
            modpackList.getItems().addAll( installedModPackFriendlyNames );

            // Add available mod packs to list
            listAddBox.getItems().clear();
            listAddBox.getItems().addAll( availableModPackFriendlyNames );

            // Unlock window when done
            modpackList.setDisable( false );
            urlAddBtn.setDisable( false );
            listAddBox.setDisable( false );
            listAddBtn.setDisable( false );
            returnBtn.setDisable( false );
        } );
    }
}
