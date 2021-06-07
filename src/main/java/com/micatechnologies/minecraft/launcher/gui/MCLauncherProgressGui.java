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
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackProgressProvider;
import com.micatechnologies.minecraft.launcher.utilities.GUIUtilities;
import com.nativejavafx.taskbar.TaskbarProgressbar;
import com.nativejavafx.taskbar.TaskbarProgressbarFactory;
import io.github.palexdev.materialfx.controls.MFXProgressBar;
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
    @SuppressWarnings( "unused" )
    @FXML
    Label upperLabel;

    /**
     * Lower label. Displays a brief description of the current task (detailed) that is being run. For example,
     * "Downloading Mod Name.jar".
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    Label lowerLabel;

    /**
     * Progress bar. Displays the task's progress on a scale from 0 to 1.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXProgressBar progressBar;

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

    private TaskbarProgressbar taskbarProgressbar = null;

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
     * Constructor for abstract scene class that initializes {@link #scene} and sets <code>this</code> as the FXML
     * controller.
     *
     * @throws IOException if unable to load FXML file specified
     */
    public MCLauncherProgressGui( Stage stage, double width, double height ) throws IOException {
        super( stage, width, height );
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
        // Configure window close
        stage.setOnCloseRequest( windowEvent -> {
            windowEvent.consume();
            LauncherCore.closeApp();
        } );

        // Setup taskbar progress bar
        if ( TaskbarProgressbar.isSupported() ) {
            taskbarProgressbar = TaskbarProgressbarFactory.getTaskbarProgressbar( stage );
        }
    }

    @Override
    void afterShow() {
        // Set filler display information
        setUpperLabelText( INITIAL_UPPER_LABEL_TEXT );
        setLowerLabelText( INITIAL_LOWER_LABEL_TEXT );
        setProgress( MFXProgressBar.INDETERMINATE_PROGRESS );
    }

    @Override
    void cleanup() {
        if ( taskbarProgressbar != null ) {
            GUIUtilities.JFXPlatformRun( () -> {
                taskbarProgressbar.stopProgress();
                taskbarProgressbar.closeOperations();
            } );
        }
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
        // Calculate proper value
        final double baseProgValue = ( progress == MFXProgressBar.INDETERMINATE_PROGRESS ) ?
                                     ( progress ) :
                                     ( progress / GameModPackProgressProvider.PROGRESS_PERCENT_BASE );

        // Update progress bar
        GUIUtilities.JFXPlatformRun( () -> {
            // Update GUI progress bar
            progressBar.setProgress( baseProgValue );

            // Update taskbar progress bar
            try {
                if ( taskbarProgressbar != null ) {
                    if ( baseProgValue == MFXProgressBar.INDETERMINATE_PROGRESS ) {
                        taskbarProgressbar.showIndeterminateProgress();
                    }
                    else {
                        taskbarProgressbar.showCustomProgress( baseProgValue, TaskbarProgressbar.Type.NORMAL );
                    }
                }
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( "Failed to update progress bar on taskbar icon!" );
                Logger.logThrowable( e );
            }
        } );

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
