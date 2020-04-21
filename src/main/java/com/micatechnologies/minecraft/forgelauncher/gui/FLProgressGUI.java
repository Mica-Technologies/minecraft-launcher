package com.micatechnologies.minecraft.forgelauncher.gui;


import com.jfoenix.controls.JFXProgressBar;
import com.micatechnologies.minecraft.forgelauncher.modpack.MCForgeModpackProgressProvider;
import com.micatechnologies.minecraft.forgelauncher.utilities.FLGUIUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.FLLogger;
import com.micatechnologies.minecraft.forgelauncher.utilities.FLSystemUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.Pair;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

public class FLProgressGUI extends FLGenericGUI {

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
        currentJFXStage.setOnCloseRequest( event -> FLSystemUtils.spawnNewTask( () -> {
            int response = FLGUIUtils.showQuestionMessage( "Close?", "Launcher is Busy", "Are you sure you want to cancel while a task is running?", "Yes", "No", getCurrentJFXStage() );
            if ( response == 1 ) {
                Platform.setImplicitExit( true );
            }
        } ) );
    }

    public void setUpperLabelText( String text ) {
        FLGUIUtils.JFXPlatformRun( () -> upperLabel.setText( text ) );
    }

    public void setLowerLabelText( String text ) {
        FLGUIUtils.JFXPlatformRun( () -> lowerLabel.setText( text ) );
    }

    public void setProgress( double progress ) {
        // Update progress bar
        FLGUIUtils.JFXPlatformRun( () -> progressBar.setProgress( progress / MCForgeModpackProgressProvider.PROGRESS_PERCENT_BASE ) );

        // Print progress to logs
        FLSystemUtils.spawnNewTask( () -> {
            if ( upperLabel != null && lowerLabel != null ) {
                FLLogger.logDebug( upperLabel.getText() + ", " + lowerLabel.getText() + ": " + progress + "%" );
            }
        } );
    }
}
