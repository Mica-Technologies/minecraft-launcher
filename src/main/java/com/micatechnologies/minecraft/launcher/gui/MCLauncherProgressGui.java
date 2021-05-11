package com.micatechnologies.minecraft.launcher.gui;

import com.jfoenix.controls.JFXProgressBar;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackProgressProvider;
import com.micatechnologies.minecraft.launcher.utilities.GUIUtilities;
import com.micatechnologies.minecraft.launcher.utilities.annotations.OnScreen;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.stage.Stage;

import java.io.IOException;

public class MCLauncherProgressGui extends MCLauncherAbstractGui
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
     * The initial text of the upper progress label.
     *
     * @since 2.0
     */
    private static final String INITIAL_UPPER_LABEL_TEXT = "Just a Moment";

    /**
     * The initial text of the lower progress label.
     *
     * @since 2.0
     */
    private static final String INITIAL_LOWER_LABEL_TEXT = "Fetching progress information...";

    /**
     * Constructor for abstract scene class that initializes {@link #scene} and sets <code>this</code> as the FXML
     * controller.
     *
     * @throws IOException if unable to load FXML file specified
     */
    public MCLauncherProgressGui( Stage stage ) throws IOException {
        super( stage );
    }

    /**
     * This method must return the resource path for the JavaFX scene FXML file.
     *
     * @return JavaFX scene FXML resource path
     */
    @Override
    String getSceneFxmlPath() {
        return "gui/progressGUI.fxml";
    }

    /**
     * This method must return the name of the JavaFX scene.
     *
     * @return Java FX scene name
     */
    @Override
    String getSceneName() {
        return "Loading";
    }

    /**
     * This method must perform initialization and setup of the scene and @FXML components.
     */
    @Override
    void setup() {
        // Set filler display information
        setUpperLabelText( INITIAL_UPPER_LABEL_TEXT );
        setLowerLabelText( INITIAL_LOWER_LABEL_TEXT );
        setProgress( JFXProgressBar.INDETERMINATE_PROGRESS );
    }

    /**
     * This method must perform preparations of the environment, such as enabling menu bars, context menus, or other
     * OS-specific enhancements.
     */
    @Override
    void loadEnvironment() {

    }

    /**
     * This method returns a boolean indicating if a warning should be shown to the user before closing the window while
     * displaying the stage/GUI.
     *
     * @return boolean indicating if window close warning should be shown
     */
    @Override
    boolean warnOnExit() {
        return true;
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
        GUIUtilities.JFXPlatformRun( () -> upperLabel.setText( text ) );
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
        GUIUtilities.JFXPlatformRun( () -> lowerLabel.setText( text ) );
    }

    public void setLabelTexts( String upper, String lower ) {
        setUpperLabelText( upper );
        setLowerLabelText( lower );
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
        GUIUtilities.JFXPlatformRun(
                () -> progressBar.setProgress( progress / GameModPackProgressProvider.PROGRESS_PERCENT_BASE ) );

        // Print progress to logs
        if ( upperLabel != null && lowerLabel != null ) {
            if ( progress >= 0 ) {
                Logger.logStd( upperLabel.getText() + ": " + lowerLabel.getText() + " (" + progress + "%)" );
            }
            else {
                Logger.logStd( upperLabel.getText() + ": " + lowerLabel.getText() + " (Running)" );
            }
        }
    }
}
