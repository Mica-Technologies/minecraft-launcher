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

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXTextField;
import com.micatechnologies.minecraft.forgelauncher.consts.LauncherConstants;
import com.micatechnologies.minecraft.forgelauncher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.forgelauncher.utilities.SystemUtilities;
import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.OnScreen;
import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.RunsOnJFXThread;
import com.micatechnologies.minecraft.forgelauncher.utilities.objects.Pair;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.stage.WindowEvent;

/**
 * Modpack installation (add/remove) window class.
 *
 * @author Mica Technologies
 * @version 1.0
 * @editors hawka97
 * @creator hawka97
 * @since 2.0
 */
public class EditModpacksWindow extends AbstractWindow
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
     * Custom {@link ListCell<String>} implementation that includes a button to remove the affiliated item from the
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
        Pane pane = new Pane();
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
                GameModPackManager.uninstallModPackByFriendlyName( label.getText() );
                loadModPackList();
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
     * Implementation of abstract method that returns the file name of the FXML associated with this class.
     *
     * @return FXML file name
     *
     * @since 1.0
     */
    @Override
    String getFXMLResourcePath() {
        return "editGUI.fxml";
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
     * Populates the installed mod pack list and installable mod pack lists.
     *
     * @since 1.0
     */
    private void loadModPackList() {
        // Set installed mod pack list cell factory
        modpackList.setCellFactory( stringListView -> new XCell() );

        // Add installed mod packs to list
        modpackList.getItems().clear();
        modpackList.getItems().addAll( GameModPackManager.getInstalledModPackFriendlyNames() );

        // Add available mod packs to list
        listAddBox.getItems().clear();
        listAddBox.getItems().addAll( GameModPackManager.getAvailableModPackFriendlyNames() );
    }

    /**
     * Implementation of abstract method that handles the setup and population of elements on the window.
     *
     * @since 1.0
     */
    @Override @RunsOnJFXThread
    void setupWindow() {
        // Set window title
        currentJFXStage.setTitle( LauncherConstants.LAUNCHER_APPLICATION_NAME + " | Mod Packs" );

        // Configure return button and window close
        currentJFXStage.setOnCloseRequest( windowEvent -> SystemUtilities.spawnNewTask( this::close ) );
        returnBtn.setOnAction( actionEvent -> currentJFXStage
                .fireEvent( new WindowEvent( currentJFXStage, WindowEvent.WINDOW_CLOSE_REQUEST ) ) );

        // Populate mod pack lists
        loadModPackList();

        // Configure add by URL button
        urlAddBtn.setOnAction( actionEvent -> {
            GameModPackManager.installModPackByURL( urlAddBox.getText() );
            loadModPackList();
        } );

        // Configure add by List button
        listAddBtn.setOnAction( actionEvent -> {
            GameModPackManager.installModPackByFriendlyName( listAddBox.getValue() );
            loadModPackList();
        } );
    }
}

