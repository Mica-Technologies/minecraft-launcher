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

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.nativejavafx.taskbar.TaskbarProgressbar;
import com.nativejavafx.taskbar.TaskbarProgressbarFactory;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

import java.awt.*;
import java.io.IOException;
import java.net.URI;

/**
 * Manages checking for launcher updates and configuring the UI to notify the user when an update is available.
 * Extracted from the main GUI controller to separate update-check logic from UI layout.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
public class UpdateCheckManager
{
    /**
     * Performs an asynchronous update check and configures the provided UI elements to show an update notification if a
     * newer version is available.
     *
     * @param updateImgView the ImageView to show/hide for update notification
     * @param stage         the owning stage (for dialogs and taskbar integration)
     * @param taskbarRef    a single-element array to receive the TaskbarProgressbar reference, or null
     */
    public static void checkAndConfigureUI( ImageView updateImgView, Stage stage,
                                             TaskbarProgressbar[] taskbarRef )
    {
        updateImgView.setVisible( false );
        SystemUtilities.spawnNewTask( () -> {
            try {
                String version = LauncherConstants.LAUNCHER_APPLICATION_VERSION;
                String latestVersionURL = UpdateCheckUtilities.getLatestReleaseURL();
                String latestVersion = UpdateCheckUtilities.getLatestReleaseVersion();

                if ( VersionUtilities.compareVersionNumbers( version, latestVersion ) == -1 ) {
                    TaskbarProgressbar taskbarProgressbar = null;
                    if ( TaskbarProgressbar.isSupported() ) {
                        taskbarProgressbar = TaskbarProgressbarFactory.getTaskbarProgressbar( stage );
                    }
                    if ( taskbarRef != null && taskbarProgressbar != null ) {
                        taskbarRef[ 0 ] = taskbarProgressbar;
                    }

                    final TaskbarProgressbar finalTaskbar = taskbarProgressbar;
                    GUIUtilities.JFXPlatformRun( () -> {
                        updateImgView.setVisible( true );
                        updateImgView.setOnMouseClicked( mouseEvent -> SystemUtilities.spawnNewTask( () -> {
                            int response = GUIUtilities.showQuestionMessage( "Update Available",
                                                                             "Update Ready to Download",
                                                                             "An update has been found and is ready to be downloaded and installed.",
                                                                             "Update Now", "Update Later", stage );
                            if ( response == 1 ) {
                                try {
                                    Desktop.getDesktop().browse( URI.create( latestVersionURL ) );
                                }
                                catch ( IOException e ) {
                                    Logger.logError( "Unable to open your browser. Please visit " +
                                                             latestVersionURL +
                                                             " to download the latest launcher updates!" );
                                    Logger.logThrowable( e );
                                }
                            }
                        } ) );

                        if ( finalTaskbar != null ) {
                            finalTaskbar.showFullErrorProgress();
                        }
                    } );
                }
            }
            catch ( Exception e ) {
                Logger.logError( "An error occurred while checking for an updated launcher version!" );
                Logger.logThrowable( e );
            }
        } );
    }
}
