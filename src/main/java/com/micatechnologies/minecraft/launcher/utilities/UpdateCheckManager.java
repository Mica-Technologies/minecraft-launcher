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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
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

        // Honour the user's "check for launcher updates" setting. When the user has
        // explicitly turned this off in Settings, skip the entire async fetch — no
        // network call, no toast, no icon — and leave the icon node hidden.
        if ( !com.micatechnologies.minecraft.launcher.config.ConfigManager
                .getLauncherUpdateCheckEnabled() ) {
            return;
        }

        // Skip the update check for unstamped local builds. The dev-style version
        // strings ("0.0.0-no-git-tag", "0.0.0-dev") compare as older than any
        // tagged release, so the version-comparison branch below would
        // unconditionally light up the navbar icon + the red taskbar overlay
        // even when the developer is actually running a tip-of-tree build that's
        // ahead of every public release. Real releases stamp a meaningful
        // version via Maven's -Drevision (resolved from git describe in CI), so
        // a release build never hits this guard.
        String version = LauncherConstants.LAUNCHER_APPLICATION_VERSION;
        if ( version == null
                || version.startsWith( "0.0.0" )
                || version.contains( "no-git-tag" )
                || version.contains( "-dev" ) ) {
            Logger.logDebug( LocalizationManager.format( "log.updateCheck.skipUnstampedBuild", version ) );
            return;
        }

        final String installedVersion = version;
        SystemUtilities.spawnNewTask( () -> {
            try {
                String latestVersionURL = UpdateCheckUtilities.getLatestReleaseURL();
                String latestVersion = UpdateCheckUtilities.getLatestReleaseVersion();

                if ( VersionUtilities.compareVersionNumbers( installedVersion, latestVersion ) == -1 ) {
                    TaskbarProgressManager.attach( stage );

                    GUIUtilities.JFXPlatformRun( () -> {
                        // On macOS, when the native title-bar toolbar is installed, surface the
                        // update as a native toolbar item instead of the in-window navbar glyph —
                        // the glyph lives in the navbar band the toolbar overlays, so it would
                        // overlap the account item. The toolbar item opens the same prompt.
                        // Everywhere else (and if the native toolbar didn't install), use the
                        // JavaFX glyph as before.
                        if ( com.micatechnologies.minecraft.launcher.utilities.MacOsToolbarManager
                                .isInstalled() ) {
                            com.micatechnologies.minecraft.launcher.utilities.MacOsToolbarManager
                                    .setUpdateAvailable( true, latestVersionURL, stage );
                        }
                        else {
                            updateImgView.setVisible( true );
                            updateImgView.setManaged( true );
                            // Tooltip surfaces the same info the toast carries — visible on
                            // any later session (when the toast won't re-fire) so the icon
                            // remains discoverable as "click here to update" rather than
                            // an unlabelled glyph.
                            com.micatechnologies.minecraft.launcher.gui.TooltipManager.install(
                                    updateImgView,
                                    com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager
                                            .format( "tooltip.update.available", latestVersion ) );
                            updateImgView.setOnMouseClicked(
                                    mouseEvent -> promptAndOpenUpdate( latestVersionURL, stage ) );
                        }

                        TaskbarProgressManager.showFullError();

                        if ( !updateNotificationShown ) {
                            updateNotificationShown = true;
                            NotificationManager.info(
                                    LocalizationManager.get( "notification.update.available.title" ),
                                    LocalizationManager.format( "notification.update.available.body", latestVersion ) );
                        }
                    } );
                }
            }
            catch ( Exception e ) {
                Logger.logError( LocalizationManager.get( "log.updateCheck.checkError" ) );
                Logger.logThrowable( e );
            }
        } );
    }

    /**
     * Prompts the user to download an available launcher update and, on confirmation, opens the
     * release URL in the system browser. Shared by the JavaFX navbar update glyph and the macOS
     * native title-bar update item so both behave identically. Runs the dialog + browse off the FX
     * thread; the URL is gated to http(s) (defense-in-depth, matching manifest-URL handling).
     *
     * @param latestVersionURL the release page URL (GitHub {@code html_url}) to open
     * @param stage            the owning stage for the confirmation dialog
     *
     * @since 2026.6
     */
    public static void promptAndOpenUpdate( String latestVersionURL, Stage stage )
    {
        SystemUtilities.spawnNewTask( () -> {
            int response = GUIUtilities.showQuestionMessage(
                    LocalizationManager.get( "dialog.update.available.title" ),
                    LocalizationManager.get( "dialog.update.available.header" ),
                    LocalizationManager.get( "dialog.update.available.body" ),
                    LocalizationManager.get( "dialog.update.available.button.now" ),
                    LocalizationManager.get( "dialog.update.available.button.later" ),
                    stage );
            if ( response == 1 ) {
                // latestVersionURL is GitHub's `html_url` from the release JSON. GitHub is trusted
                // infrastructure, but defense-in-depth: refuse anything other than http/https before
                // handing to Desktop.browse, matching the gate used for manifest-supplied URLs.
                if ( latestVersionURL == null
                        || !( latestVersionURL.startsWith( "https://" )
                              || latestVersionURL.startsWith( "http://" ) ) ) {
                    Logger.logError( LocalizationManager.format( "log.updateCheck.refuseNonHttpUrl",
                                                                 latestVersionURL ) );
                }
                else {
                    try {
                        Desktop.getDesktop().browse( URI.create( latestVersionURL ) );
                    }
                    catch ( IOException e ) {
                        Logger.logError( LocalizationManager.format( "log.updateCheck.browserOpenFailed",
                                                                     latestVersionURL ) );
                        Logger.logThrowable( e );
                    }
                }
            }
        } );
    }
}
