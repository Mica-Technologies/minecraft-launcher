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
import com.nativejavafx.taskbar.TaskbarProgressbar;
import com.nativejavafx.taskbar.TaskbarProgressbarFactory;
import io.github.palexdev.materialfx.controls.MFXProgressBar;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * Progress GUI with three text labels:
 * <ul>
 *   <li><b>upperLabel</b> -- overall task title (e.g. "Launching: Forge 1.15.2")</li>
 *   <li><b>sectionLabel</b> -- current step heading (e.g. "Downloading mods...")</li>
 *   <li><b>detailLabel</b> -- granular file-level detail below progress bar (e.g. "Verified jna-4.4.0.jar")</li>
 * </ul>
 */
public class MCLauncherProgressGui extends MCLauncherAbstractGui
{
    /** Overall task title above everything. */
    @SuppressWarnings( "unused" )
    @FXML
    Label upperLabel;

    /** Current section/step heading between the title and progress bar. */
    @SuppressWarnings( "unused" )
    @FXML
    Label sectionLabel;

    /** File-level detail below the progress bar. */
    @SuppressWarnings( "unused" )
    @FXML
    Label detailLabel;

    /** Progress bar. */
    @SuppressWarnings( "unused" )
    @FXML
    MFXProgressBar progressBar;

    /** Download speed and ETA info below the detail label. */
    @SuppressWarnings( "unused" )
    @FXML
    Label speedLabel;

    private TaskbarProgressbar taskbarProgressbar = null;

    public MCLauncherProgressGui( Stage stage ) throws IOException {
        super( stage );
    }

    public MCLauncherProgressGui( Stage stage, double width, double height ) throws IOException {
        super( stage, width, height );
    }

    @Override
    String getSceneFxmlPath() {
        return "gui/progressGUI.fxml";
    }

    @Override
    String getSceneName() {
        return "Loading";
    }

    @Override
    void setup() {
        stage.setOnCloseRequest( windowEvent -> {
            windowEvent.consume();
            LauncherCore.closeApp();
        } );
        // Note: taskbar progress bar is initialized in afterShow() after the stage is visible,
        // to avoid native access violations from uninitialized window handles.
    }

    @Override
    void afterShow() {
        setUpperLabelText( "Just a Moment" );
        setSectionText( "" );
        setDetailText( "" );
        setSpeedText( "" );
        setProgress( 0.0 );

        // Initialize taskbar progress bar AFTER the stage is shown, so the native HWND is valid.
        try {
            if ( TaskbarProgressbar.isSupported() ) {
                taskbarProgressbar = TaskbarProgressbarFactory.getTaskbarProgressbar( stage );
            }
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Unable to initialize taskbar progress bar: " + e.getMessage() );
            taskbarProgressbar = null;
        }
    }

    @Override
    void cleanup() {
        if ( taskbarProgressbar != null ) {
            GUIUtilities.JFXPlatformRun( () -> {
                try {
                    taskbarProgressbar.stopProgress();
                    taskbarProgressbar.closeOperations();
                }
                catch ( Exception | Error e ) {
                    Logger.logWarningSilent( "Failed to clean up taskbar progress bar." );
                }
                taskbarProgressbar = null;
            } );
        }
    }

    @Override
    HelpTopic getHelpTopic() { return HelpTopic.GETTING_STARTED; }

    /**
     * Sets the overall task title (top line). E.g. "Launching: Forge 1.15.2" or "Signing In".
     */
    public void setUpperLabelText( String text ) {
        GUIUtilities.JFXPlatformRun( () -> upperLabel.setText( text ) );
    }

    /**
     * Sets the current section heading (between title and progress bar). E.g. "Downloading mods..."
     */
    public void setSectionText( String text ) {
        GUIUtilities.JFXPlatformRun( () -> sectionLabel.setText( text ) );
    }

    /**
     * Sets the detail text below the progress bar. E.g. "Verified library jna-4.4.0.jar"
     */
    public void setDetailText( String text ) {
        GUIUtilities.JFXPlatformRun( () -> detailLabel.setText( text ) );
    }

    /**
     * Sets the speed/ETA info text. E.g. "2.4 MB/s -- 3:42 remaining -- 12/150 files"
     */
    public void setSpeedText( String text ) {
        GUIUtilities.JFXPlatformRun( () -> speedLabel.setText( text ) );
    }

    /**
     * Sets the lower label text. For backward compatibility, this updates the section label.
     * Direct callers should prefer {@link #setSectionText(String)} or {@link #setDetailText(String)}.
     */
    public void setLowerLabelText( String text ) {
        setSectionText( text );
    }

    /**
     * Sets both upper and section labels. For backward compatibility with existing callers.
     */
    public void setLabelTexts( String upper, String lower ) {
        setUpperLabelText( upper );
        setSectionText( lower );
    }

    /**
     * Sets the progress bar value (0-100 scale, or INDETERMINATE_PROGRESS).
     */
    public void setProgress( double progress ) {
        final double baseProgValue = ( progress == MFXProgressBar.INDETERMINATE_PROGRESS ) ?
                                     ( progress ) :
                                     ( progress / GameModPackProgressProvider.PROGRESS_PERCENT_BASE );

        GUIUtilities.JFXPlatformRun( () -> {
            progressBar.setProgress( baseProgValue );

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
            catch ( Exception | Error e ) {
                // Catch both Exception and Error (including native RuntimeException from BridJ/COM)
                // to prevent taskbar progress issues from crashing the entire application.
                Logger.logWarningSilent( "Failed to update taskbar progress bar, disabling it." );
                taskbarProgressbar = null;
            }
        } );
    }
}
