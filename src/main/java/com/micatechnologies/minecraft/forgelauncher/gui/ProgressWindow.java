package com.micatechnologies.minecraft.forgelauncher.gui;


import com.jfoenix.controls.JFXProgressBar;
import com.micatechnologies.minecraft.forgelauncher.modpack.MCForgeModpackProgressProvider;
import com.micatechnologies.minecraft.forgelauncher.utilities.GuiUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.LogUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.SystemUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.OnScreen;
import com.micatechnologies.minecraft.forgelauncher.utilities.objects.Pair;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

/**
 * Launcher progress window class.
 *
 * @author Mica Technologies
 * @version 2.0
 * @editors hawka97
 * @since 1.0
 * @creator hawka97
 */
public class ProgressWindow extends AbstractWindow
{

    /**
     * Upper label. Displays a brief description of the task (as a whole) that is being run. For example, "Downloading
     * mod updates..."
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    Label upperLabel;

    /**
     * Lower label. Displays a brief description of the current task (detailed) that is being run. For example,
     * "Downloading Mod Name.jar".
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    Label lowerLabel;

    /**
     * Progress bar. Displays the task's progress on a scale from 0 to 1.
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    JFXProgressBar progressBar;

    /**
     * Implementation of abstract method that returns the file name of the FXML associated with this class.
     *
     * @return FXML file name
     *
     * @since 1.0
     */
    @Override
    String getFXMLResourcePath() {
        return "progressGUI.fxml";
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
     * Implementation of abstract method that handles the setup and population of elements on the window.
     *
     * @since 1.0
     */
    @Override
    void setupWindow() {
        // Set filler display information
        setUpperLabelText( "Just a Moment" );
        setLowerLabelText( "Fetching progress information..." );
        setProgress( JFXProgressBar.INDETERMINATE_PROGRESS );

        // Configure exit button
        currentJFXStage.setOnCloseRequest( event -> SystemUtils.spawnNewTask( () -> {
            int response = GuiUtils.showQuestionMessage( "Close?", "Launcher is Busy",
                                                         "Are you sure you want to cancel while a task is running?",
                                                         "Yes", "No", getCurrentJFXStage() );
            if ( response == 1 ) {
                Platform.setImplicitExit( true );
            }
        } ) );
    }

    /**
     * Sets the text of the upper label. Upper label text should be a description of the task as a whole. For example,
     * "Downloading mods..."
     *
     * @param text upper label text
     *
     * @since 1.0
     */
    public void setUpperLabelText( String text ) {
        GuiUtils.JFXPlatformRun( () -> upperLabel.setText( text ) );
    }

    /**
     * Sets the text of the lower label. Lower label text should be a specific description of the task as it progresses.
     * For example, "Downloading Mod Name.jar"
     *
     * @param text lower label text
     *
     * @since 1.0
     */
    public void setLowerLabelText( String text ) {
        GuiUtils.JFXPlatformRun( () -> lowerLabel.setText( text ) );
    }

    /**
     * Sets the value of the progress bar, and prints a text status update to the console/log file.
     *
     * @param progress progress value
     *
     * @since 1.0
     */
    public void setProgress( double progress ) {
        // Update progress bar
        GuiUtils.JFXPlatformRun(
                () -> progressBar.setProgress( progress / MCForgeModpackProgressProvider.PROGRESS_PERCENT_BASE ) );

        // Print progress to logs
        SystemUtils.spawnNewTask( () -> {
            if ( upperLabel != null && lowerLabel != null && readyLatch.getCount() == 0 ) {
                if ( progress > 0 ) {
                    LogUtils.logDebug( progress + "%: " + upperLabel.getText() + ", " + lowerLabel.getText() );
                }
                else {
                    LogUtils.logDebug( "Running: " + upperLabel.getText() + ", " + lowerLabel.getText() );
                }
            }
        } );
    }
}
