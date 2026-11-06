/*
 * Copyright (c) 2026 Mica Technologies
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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.session.GameSessionRegistry;
import com.micatechnologies.minecraft.launcher.update.InstallKind;
import com.micatechnologies.minecraft.launcher.update.LauncherRelease;
import com.micatechnologies.minecraft.launcher.update.LauncherUpdater;
import com.micatechnologies.minecraft.launcher.update.ReleaseAsset;
import com.micatechnologies.minecraft.launcher.update.StagedUpdate;
import com.micatechnologies.minecraft.launcher.update.UpdateCancelledException;
import com.micatechnologies.minecraft.launcher.update.UpdateStager;
import com.micatechnologies.minecraft.launcher.utilities.DownloadTracker;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.Locale;

/**
 * The launcher's update dialog for installs it can update itself: offers the update, downloads
 * and verifies it with progress, then offers to restart (plan phase 2). One dialog walks through
 * the states: available, downloading, ready, failed. Restarting stays disabled while games run
 * (plan decision D5); "Later" leaves the update staged, and it is installed when the launcher
 * quits.
 *
 * <p>Every method runs on the JavaFX thread; the download and the install run on their own
 * threads.
 *
 * @since 2026.11
 */
public final class MCLauncherUpdateDialog
{
    private static final double BYTES_PER_MB = 1024.0 * 1024.0;

    private final LauncherRelease release;
    private final ReleaseAsset asset;
    private final InstallKind kind;
    private final Alert alert;
    private final Label body = new Label();
    private final ProgressBar progress = new ProgressBar( 0 );
    private final Label status = new Label();
    private final Runnable sessionListener = () -> GUIUtilities.JFXPlatformRun( this::refreshRestartButton );

    private Thread worker;
    private Timeline poller;
    private StagedUpdate staged;
    private ButtonType restartButton;

    private MCLauncherUpdateDialog( LauncherRelease release, ReleaseAsset asset, InstallKind kind, Stage owner )
    {
        this.release = release;
        this.asset = asset;
        this.kind = kind;
        alert = new Alert( Alert.AlertType.NONE );
        alert.setTitle( LocalizationManager.get( "update.dialog.title" ) );
        alert.initStyle( StageStyle.UTILITY );
        alert.initModality( Modality.WINDOW_MODAL );
        if ( owner != null ) {
            alert.initOwner( owner );
        }
        body.setWrapText( true );
        status.setWrapText( true );
        progress.setMaxWidth( Double.MAX_VALUE );
        VBox content = new VBox( 12, body, progress, status );
        content.setPrefWidth( 460 );
        alert.getDialogPane().setContent( content );
        alert.setOnHidden( e -> stop() );
        GUIUtilities.themeAlertChrome( alert );
    }

    /**
     * Shows the dialog for an update the launcher can install itself.
     *
     * @param release the release
     * @param asset   the release file for this install
     * @param kind    this install's kind
     * @param owner   the window it belongs to, or {@code null}
     *
     * @since 2026.11
     */
    public static void show( LauncherRelease release, ReleaseAsset asset, InstallKind kind, Stage owner )
    {
        MCLauncherUpdateDialog dialog = new MCLauncherUpdateDialog( release, asset, kind, owner );
        dialog.showAvailable();
        dialog.alert.show();
    }

    /**
     * Tells a Flatpak user that their software store updates the launcher (plan decision D3).
     *
     * @param version the available version
     * @param owner   the window it belongs to, or {@code null}
     *
     * @since 2026.11
     */
    public static void showSoftwareStoreNotice( String version, Stage owner )
    {
        Alert notice = new Alert( Alert.AlertType.INFORMATION );
        notice.setTitle( LocalizationManager.get( "update.dialog.title" ) );
        notice.setHeaderText( LocalizationManager.format( "update.dialog.store.header", version ) );
        notice.setContentText( LocalizationManager.get( "update.dialog.store.body" ) );
        notice.initStyle( StageStyle.UTILITY );
        notice.initModality( Modality.WINDOW_MODAL );
        if ( owner != null ) {
            notice.initOwner( owner );
        }
        notice.getButtonTypes().setAll(
                new ButtonType( LocalizationManager.get( "update.dialog.button.close" ),
                                ButtonBar.ButtonData.CANCEL_CLOSE ) );
        GUIUtilities.themeAlertChrome( notice );
        notice.show();
    }

    // ---- states ---------------------------------------------------------------------------

    private void showAvailable()
    {
        alert.setHeaderText( LocalizationManager.format( "update.dialog.available.header", release.version() ) );
        body.setText( LocalizationManager.get( "update.dialog.available.body" ) );
        hideProgress();
        ButtonType download = new ButtonType( LocalizationManager.get( "update.dialog.button.download" ),
                                              ButtonBar.ButtonData.OK_DONE );
        setButtons( download, later() );
        onPress( download, this::startDownload );
    }

    private void startDownload()
    {
        alert.setHeaderText( LocalizationManager.format( "update.dialog.downloading.header", release.version() ) );
        body.setText( "" );
        progress.setVisible( true );
        progress.setManaged( true );
        progress.setProgress( ProgressBar.INDETERMINATE_PROGRESS );
        status.setText( LocalizationManager.get( "update.dialog.downloading.starting" ) );
        setButtons( cancel() );

        DownloadTracker tracker = new DownloadTracker();
        poller = new Timeline( new KeyFrame( Duration.millis( 250 ), e -> showProgress( tracker ) ) );
        poller.setCycleCount( Timeline.INDEFINITE );
        poller.play();

        worker = new Thread( () -> {
            try {
                StagedUpdate result = UpdateStager.standard().stage( release.version(), kind, asset, tracker );
                GUIUtilities.JFXPlatformRun( () -> showReady( result ) );
            }
            catch ( UpdateStager.ChecksumMismatchException e ) {
                GUIUtilities.JFXPlatformRun(
                        () -> showFailed( LocalizationManager.get( "update.dialog.failed.checksum" ) ) );
            }
            catch ( Exception e ) {
                if ( Thread.currentThread().isInterrupted() ) {
                    return;   // Cancelled: the dialog is already closed.
                }
                Logger.logWarningSilent( LocalizationManager.format( "log.update.installFailed", e.getMessage() ) );
                GUIUtilities.JFXPlatformRun( () -> showFailed(
                        LocalizationManager.format( "update.dialog.failed.body", String.valueOf( e.getMessage() ) ) ) );
            }
        }, "Launcher-Update-Download" );
        worker.setDaemon( true );
        worker.start();
    }

    private void showProgress( DownloadTracker tracker )
    {
        long expected = tracker.getTotalBytesExpected() > 0 ? tracker.getTotalBytesExpected() : asset.size();
        long done = tracker.getTotalBytesDownloaded();
        if ( expected > 0 && done > 0 ) {
            progress.setProgress( Math.min( 1.0, ( double ) done / expected ) );
            status.setText( LocalizationManager.format( "update.dialog.downloading.progress", mb( done ),
                                                        mb( expected ) ) );
        }
    }

    private void showReady( StagedUpdate result )
    {
        stopPolling();
        staged = result;
        alert.setHeaderText( LocalizationManager.format( "update.dialog.ready.header", release.version() ) );
        body.setText( LocalizationManager.get( readyBodyKey( kind ) ) );
        hideProgress();
        restartButton = new ButtonType( LocalizationManager.get( restartButtonKey( kind ) ),
                                        ButtonBar.ButtonData.OK_DONE );
        setButtons( restartButton, later() );
        onPress( restartButton, this::installAndRestart );
        GameSessionRegistry.get().addListener( sessionListener );
        refreshRestartButton();
    }

    /** D5: no restart while a game runs; the button comes back as soon as the last one ends. */
    private void refreshRestartButton()
    {
        if ( restartButton == null ) {
            return;
        }
        Node button = alert.getDialogPane().lookupButton( restartButton );
        boolean gamesRunning = GameSessionRegistry.get().hasActive();
        if ( button != null ) {
            button.setDisable( gamesRunning );
        }
        status.setText( gamesRunning ? LocalizationManager.get( "update.dialog.ready.gamesRunning" ) : "" );
    }

    private void installAndRestart()
    {
        if ( GameSessionRegistry.get().hasActive() ) {
            refreshRestartButton();
            return;
        }
        setButtons();
        status.setText( LocalizationManager.get( "update.dialog.installing" ) );
        StagedUpdate toInstall = staged;
        Thread installer = new Thread( () -> {
            try {
                if ( LauncherUpdater.install( toInstall ) == LauncherUpdater.AfterInstall.RELAUNCH ) {
                    LauncherCore.relaunchApp();
                }
                else {
                    // A helper installs it once the launcher has exited, then starts it again.
                    LauncherCore.closeApp();
                }
            }
            catch ( UpdateCancelledException e ) {
                GUIUtilities.JFXPlatformRun(
                        () -> showFailed( LocalizationManager.get( "update.dialog.failed.cancelled" ) ) );
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.update.installFailed", e.getMessage() ) );
                GUIUtilities.JFXPlatformRun( () -> showFailed(
                        LocalizationManager.format( "update.dialog.failed.body", String.valueOf( e.getMessage() ) ) ) );
            }
        }, "Launcher-Update-Install" );
        installer.setDaemon( false );
        installer.start();
    }

    private void showFailed( String message )
    {
        stopPolling();
        GameSessionRegistry.get().removeListener( sessionListener );
        restartButton = null;
        alert.setHeaderText( LocalizationManager.get( "update.dialog.failed.header" ) );
        body.setText( message );
        hideProgress();
        ButtonType retry = new ButtonType( LocalizationManager.get( "update.dialog.button.retry" ),
                                           ButtonBar.ButtonData.OK_DONE );
        ButtonType page = new ButtonType( LocalizationManager.get( "update.dialog.button.releasePage" ),
                                          ButtonBar.ButtonData.OTHER );
        setButtons( retry, page, new ButtonType( LocalizationManager.get( "update.dialog.button.close" ),
                                                 ButtonBar.ButtonData.CANCEL_CLOSE ) );
        onPress( retry, this::startDownload );
        onPress( page, () -> GUIUtilities.openExternalUrl( release.pageUrl() ) );
    }

    // ---- helpers ---------------------------------------------------------------------------

    /**
     * The "ready" explanation for an install kind: installed on quit, or needing permission (then
     * never installed on quit), or the macOS installer package.
     *
     * @param kind the install kind
     *
     * @return the bundle key
     */
    static String readyBodyKey( InstallKind kind )
    {
        if ( kind == InstallKind.MAC_APP_PKG ) {
            return "update.dialog.ready.bodyPkg";
        }
        return LauncherUpdater.installsOnQuit( kind ) ? "update.dialog.ready.body"
                                                      : "update.dialog.ready.bodyPermission";
    }

    /**
     * The button that installs: "Restart now", or "Close and install" where the launcher does
     * not start again by itself (the macOS installer package).
     *
     * @param kind the install kind
     *
     * @return the bundle key
     */
    static String restartButtonKey( InstallKind kind )
    {
        return kind == InstallKind.MAC_APP_PKG ? "update.dialog.button.closeAndInstall"
                                               : "update.dialog.button.restart";
    }

    private ButtonType later()
    {
        return new ButtonType( LocalizationManager.get( "update.dialog.button.later" ),
                               ButtonBar.ButtonData.CANCEL_CLOSE );
    }

    private ButtonType cancel()
    {
        return new ButtonType( LocalizationManager.get( "dialog.button.cancel" ), ButtonBar.ButtonData.CANCEL_CLOSE );
    }

    private void setButtons( ButtonType... buttons )
    {
        alert.getButtonTypes().setAll( buttons );
    }

    /** Runs an action for a button without closing the dialog, as an Alert would. */
    private void onPress( ButtonType type, Runnable action )
    {
        Node button = alert.getDialogPane().lookupButton( type );
        if ( button != null ) {
            button.addEventFilter( ActionEvent.ACTION, e -> {
                e.consume();
                action.run();
            } );
        }
    }

    private void hideProgress()
    {
        progress.setVisible( false );
        progress.setManaged( false );
        status.setText( "" );
    }

    private void stopPolling()
    {
        if ( poller != null ) {
            poller.stop();
            poller = null;
        }
    }

    /** The dialog closed: cancel a download in flight and let go of the session listener. */
    private void stop()
    {
        stopPolling();
        GameSessionRegistry.get().removeListener( sessionListener );
        if ( worker != null && worker.isAlive() ) {
            worker.interrupt();
        }
    }

    private static String mb( long bytes )
    {
        return String.format( Locale.ROOT, "%.0f", bytes / BYTES_PER_MB );
    }
}
