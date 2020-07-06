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


import com.jfoenix.controls.JFXProgressBar;
import com.micatechnologies.minecraft.forgelauncher.LauncherCore;
import com.micatechnologies.minecraft.forgelauncher.consts.LauncherConstants;
import com.micatechnologies.minecraft.forgelauncher.game.modpack.GameModPackProgressProvider;
import com.micatechnologies.minecraft.forgelauncher.utilities.GUIUtilities;
import com.micatechnologies.minecraft.forgelauncher.files.Logger;
import com.micatechnologies.minecraft.forgelauncher.utilities.SystemUtilities;
import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.OnScreen;
import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.RunsOnJFXThread;
import com.micatechnologies.minecraft.forgelauncher.utilities.objects.Pair;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

/**
 * Launcher progress window class.
 *
 * @author Mica Technologies
 * @version 2.0
 * @editors hawka97
 * @creator hawka97
 * @since 1.0
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
     * The initial text of the upper progress label.
     *
     * @since 2.0
     */
    private static String initialUpperLabelText = "Just a Moment";

    /**
     * The initial text of the lower progress label.
     *
     * @since 2.0
     */
    private static String initialLowerLabelText = "Fetching progress information...";

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
    @RunsOnJFXThread
    void setupWindow() {
        // Set window title
        currentJFXStage.setTitle( LauncherConstants.LAUNCHER_APPLICATION_NAME + " | Progress" );

        // Set filler display information
        setUpperLabelText( initialUpperLabelText );
        setLowerLabelText( initialLowerLabelText );
        setProgress( JFXProgressBar.INDETERMINATE_PROGRESS );

        // Configure exit button
        currentJFXStage.setOnCloseRequest( event -> SystemUtilities.spawnNewTask( () -> {
            int response = GUIUtilities.showQuestionMessage( "Close?", "Launcher is Busy",
                                                             "Are you sure you want to cancel while a task is running?",
                                                             "Yes", "No", getCurrentJFXStage() );
            if ( response == 1 ) {
                LauncherCore.closeApp();
            }
        } ) );
    }

    /**
     * Shows the window with the specified initial strings for the upper and lower progress window labels.
     *
     * @param upper initial upper label value
     * @param lower initial lower label value
     *
     * @since 2.0
     */
    public void show( String upper, String lower ) {
        initialUpperLabelText = upper;
        initialLowerLabelText = lower;
        show();
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
        if ( upperLabel != null && lowerLabel != null && readyLatch.getCount() == 0 ) {
            if ( progress >= 0 ) {
                Logger.logStd( upperLabel.getText() + ": " + lowerLabel.getText() + " (" + progress + "%)" );
            }
            else {
                Logger.logStd( upperLabel.getText() + ": " + lowerLabel.getText() + " (Running)" );
            }
        }
    }

}
