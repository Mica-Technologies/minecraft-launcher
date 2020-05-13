package com.micatechnologies.minecraft.forgelauncher.gui;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXTextField;
import com.micatechnologies.minecraft.forgelauncher.MCFLApp;
import com.micatechnologies.minecraft.forgelauncher.modpack.MCForgeModpack;
import com.micatechnologies.minecraft.forgelauncher.utilities.Pair;
import com.micatechnologies.minecraft.forgelauncher.utilities.SystemUtils;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.text.Font;
import javafx.stage.WindowEvent;

public class EditModpacksGUI extends GenericGUI {

    @FXML
    ListView< String > modpackList;

    @FXML
    JFXButton urlAddBtn;

    @FXML
    JFXButton listAddBtn;

    @FXML
    JFXTextField urlAddBox;

    @FXML
    JFXComboBox< String > listAddBox;

    @FXML
    JFXButton returnBtn;

    static class XCell extends ListCell< String > {
        HBox hbox = new HBox();
        Label label = new Label( "" );
        Pane pane = new Pane();
        Button button = new Button( "X" );

        public XCell() {
            super();

            pane.setPrefWidth( 10 );
            label.setStyle( "-fx-text-fill:black!important;" );
            button.setStyle( "-fx-text-fill:red!important;-fx-font-size:8;" );
            hbox.getChildren().addAll( label, pane, button );
            HBox.setHgrow( pane, Priority.ALWAYS );
            button.setOnAction( event -> getListView().getItems().remove( getItem() ) );
        }

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

    @Override
    String getFXMLResourcePath() {
        return "editGUI.fxml";
    }

    @Override
    Pair< Integer, Integer > getWindowSize() {
        return new Pair<>( 600, 600 );
    }

    private void loadModpackList() {
        modpackList.setCellFactory( stringListView -> new XCell() );
        // Add each modpack by name and version
        for ( MCForgeModpack pack : MCFLApp.getModpacks() ) {
            modpackList.getItems().add( pack.getPackName() + ": v" + pack.getPackVersion() );
        }
    }

    @Override
    void setupWindow() {
        // Configure return button and window close
        currentJFXStage.setOnCloseRequest( windowEvent -> SystemUtils.spawnNewTask( this::close ) );
        returnBtn.setOnAction( actionEvent -> currentJFXStage.fireEvent( new WindowEvent( currentJFXStage, WindowEvent.WINDOW_CLOSE_REQUEST ) ) );

        // Populate mod pack list
        loadModpackList();

        // Configure add by URL button
        urlAddBtn.setOnAction(actionEvent -> {
            // Store URL from textbox
            String newURL = urlAddBox.getText();

            
        });
    }
}

