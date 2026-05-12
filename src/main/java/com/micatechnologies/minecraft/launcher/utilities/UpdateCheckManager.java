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
import com.micatechnologies.minecraft.launcher.gui.GUIUtilities;
import javafx.scene.Node;
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
    /** Track whether we've already toasted the user about this session's update check. Each main-menu
     *  load re-runs the check, but the user only wants ONE notification per launcher run — once
     *  dismissed/seen, it's just noise. */
    private static volatile boolean updateNotificationShown = false;

    /**
     * Performs an asynchronous update check and configures the provided UI elements to show an update notification if a
     * newer version is available. The "update available" cue is also pushed to the OS taskbar via
     * {@link TaskbarProgressManager#showFullError()} (red full-error overlay) and a native toast via
     * {@link NotificationManager#info}, so a user with the launcher minimized still sees the prompt.
     *
     * @param updateImgView the icon node to show/hide for update notification (typed as
     *                      {@link Node} so the FXML side can swap between ImageView /
     *                      SVGPath / etc. without churning this signature)
     * @param stage         the owning stage (for dialogs and taskbar integration)
     */
    public static void checkAndConfigureUI( Node updateImgView, Stage stage )
    {
        // Hide AND unmanage so the image doesn't reserve layout space when there's no update.
        updateImgView.setVisible( false );
        updateImgView.setManaged( false );
        SystemUtilities.spawnNewTask( () -> {
            try {
                String version = LauncherConstants.LAUNCHER_APPLICATION_VERSION;
                String latestVersionURL = UpdateCheckUtilities.getLatestReleaseURL();
                String latestVersion = UpdateCheckUtilities.getLatestReleaseVersion();

                if ( VersionUtilities.compareVersionNumbers( version, latestVersion ) == -1 ) {
                    TaskbarProgressManager.attach( stage );

                    GUIUtilities.JFXPlatformRun( () -> {
                        updateImgView.setVisible( true );
                        updateImgView.setManaged( true );
                        // Tooltip surfaces the same info the toast carries — visible on
                        // any later session (when the toast won't re-fire) so the icon
                        // remains discoverable as "click here to update" rather than
                        // an unlabelled glyph.
                        com.micatechnologies.minecraft.launcher.gui.TooltipManager.install(
                                updateImgView,
                                "Mica Launcher " + latestVersion + " is available — click to download." );
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

                        TaskbarProgressManager.showFullError();

                        if ( !updateNotificationShown ) {
                            updateNotificationShown = true;
                            NotificationManager.info(
                                    "Update available",
                                    "Mica Launcher " + latestVersion + " is ready. Click the update icon in the navbar to download." );
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
