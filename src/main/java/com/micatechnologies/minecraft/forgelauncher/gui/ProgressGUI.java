package com.micatechnologies.minecraft.forgelauncher.gui;


import com.jfoenix.controls.JFXProgressBar;
import com.micatechnologies.minecraft.forgelauncher.modpack.MCForgeModpackProgressProvider;
import com.micatechnologies.minecraft.forgelauncher.utilities.GUIUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.Logger;
import com.micatechnologies.minecraft.forgelauncher.utilities.SystemUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.Pair;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

public class ProgressGUI extends GenericGUI {

    @FXML
    Label upperLabel;

    @FXML
    Label lowerLabel;

    @FXML
    JFXProgressBar progressBar;

    @Override
    String getFXMLResourcePath() {
        return "progressGUI.fxml";
    }

    @Override
    Pair< Integer, Integer > getWindowSize() {
        return new Pair<>( 600, 600 );
    }

    @Override
    void setupWindow() {
        // Set filler display information
        setUpperLabelText( "Just a Moment" );
        setLowerLabelText( "Fetching progress information..." );
        setProgress( JFXProgressBar.INDETERMINATE_PROGRESS );

        // Configure exit button
        currentJFXStage.setOnCloseRequest( event -> SystemUtils.spawnNewTask( () -> {
            int response = GUIUtils.showQuestionMessage( "Close?", "Launcher is Busy", "Are you sure you want to cancel while a task is running?", "Yes", "No", getCurrentJFXStage() );
            if ( response == 1 ) {
                Platform.setImplicitExit( true );
            }
        } ) );
    }

    public void setUpperLabelText( String text ) {
        GUIUtils.JFXPlatformRun( () -> {
            upperLabel.setText( text );
        } );
    }

    public void setLowerLabelText( String text ) {
        GUIUtils.JFXPlatformRun( () -> {
            lowerLabel.setText( text );
        } );
    }

    public void setProgress( double progress ) {
        // Update progress bar
        GUIUtils.JFXPlatformRun( () -> progressBar.setProgress( progress / MCForgeModpackProgressProvider.PROGRESS_PERCENT_BASE ) );

        // Print progress to logs
        SystemUtils.spawnNewTask( () -> {
            if ( upperLabel != null && lowerLabel != null && readyLatch.getCount() == 0 ) {
                if ( progress > 0 ) {
                    Logger.logDebug( progress + "%: " + upperLabel.getText() + ", " + lowerLabel.getText() );
                }
                else {
                    Logger.logDebug( "Running: " + upperLabel.getText() + ", " + lowerLabel.getText() );
                }
            }
        } );
    }
}
