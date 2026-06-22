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
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackProgressProvider;
import com.micatechnologies.minecraft.launcher.utilities.TaskbarProgressManager;
import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXProgressBar;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.fxml.FXML;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

    /** Three iso-cube voxel groups at the top of the progress card. Animated in
     *  {@link #afterShow()} with a staggered bounce so the screen feels alive while a
     *  long-running download is in flight. */
    @SuppressWarnings( "unused" ) @FXML Group voxelCube1;
    @SuppressWarnings( "unused" ) @FXML Group voxelCube2;
    @SuppressWarnings( "unused" ) @FXML Group voxelCube3;

    /** Cancel button at the bottom of the progress card. Hidden by default; callers
     *  that want cancellation opt in via {@link #setCancelHandler}. */
    @SuppressWarnings( "unused" ) @FXML MFXButton cancelBtn;

    /** Container for the cancel button — toggled visible + managed by
     *  {@link #setCancelHandler}. Wrapping the button in an HBox lets us add top
     *  padding that's only allocated when the cancel button is actually shown
     *  (the row collapses to zero height when the HBox is unmanaged). */
    @SuppressWarnings( "unused" ) @FXML HBox cancelBtnRow;

    /** Running animations on the voxel cubes. Held so {@link #cleanup()} can stop them
     *  on scene transition rather than leaking timeline state across scene changes. */
    private final List< TranslateTransition > voxelAnimations = new ArrayList<>();

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
        // Note: taskbar progress bar is attached in afterShow() after the stage is visible,
        // to avoid native access violations from uninitialized window handles.
    }

    @Override
    void afterShow() {
        startVoxelBounceAnimation();

        setUpperLabelText( "Just a Moment" );
        setSectionText( "" );
        setDetailText( "" );
        setSpeedText( "" );

        // Attach the shared taskbar wrapper to this stage on first appearance. Idempotent
        // across screens — TaskbarProgressManager owns one wrapper for the app lifetime,
        // so leaving and re-entering this screen never spins up a competing instance.
        TaskbarProgressManager.attach( stage );

        setProgress( 0.0 );
    }

    @Override
    void cleanup() {
        // Always clear the OS-level overlay before the next scene takes over. We don't
        // close the wrapper here — TaskbarProgressManager keeps it alive until app exit
        // so transitions never race a release against a fresh init.
        GUIUtilities.JFXPlatformRun( TaskbarProgressManager::stop );
        stopVoxelBounceAnimation();
    }

    /**
     * Kicks off the staggered bounce animation on the three voxel cubes at the top of the
     * progress card. Each cube bobs up-down (Y translate 0 → -8 → 0) on an indefinite
     * cycle, with the second and third cubes starting ~150 ms and ~300 ms after the first
     * so the cluster looks like a Mexican wave rather than three things blinking in sync.
     *
     * <p>Easing is {@link Interpolator#EASE_BOTH} for a gentle, "watching a loading dots"
     * feel rather than a snappy bounce. Translates use the Node's {@code translateY}
     * property which doesn't affect layout — the HBox row size stays stable; only the
     * cube's draw position moves within its layout slot. That's why the row's
     * {@code prefHeight} got bumped up a few px in the FXML — to give the cubes 8 px of
     * vertical travel room without clipping at the top edge of the row.</p>
     */
    private void startVoxelBounceAnimation() {
        stopVoxelBounceAnimation();
        startBounceOn( voxelCube1,   0 );
        startBounceOn( voxelCube2, 150 );
        startBounceOn( voxelCube3, 300 );
    }

    private void startBounceOn( Group cube, int delayMs ) {
        if ( cube == null ) {
            return;
        }
        TranslateTransition tt = new TranslateTransition( Duration.millis( 500 ), cube );
        tt.setFromY( 0 );
        tt.setToY( -8 );
        tt.setAutoReverse( true );
        tt.setCycleCount( Animation.INDEFINITE );
        tt.setInterpolator( Interpolator.EASE_BOTH );
        tt.setDelay( Duration.millis( delayMs ) );
        tt.play();
        voxelAnimations.add( tt );
    }

    /** Stops every running voxel bounce. Called from {@link #cleanup()} so the timelines
     *  don't keep ticking on a hidden / disposed scene. */
    private void stopVoxelBounceAnimation() {
        for ( TranslateTransition tt : voxelAnimations ) {
            try {
                tt.stop();
            }
            catch ( Exception | Error ignored ) { /* best-effort */ }
        }
        voxelAnimations.clear();
    }

    @Override
    HelpTopic getHelpTopic() { return HelpTopic.GETTING_STARTED; }

    /** Disable toolbar navigation while a blocking operation is in progress. */
    @Override
    boolean allowsToolbarNavigation() { return false; }

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
     * Shows the Cancel button at the bottom of the progress card and wires its action
     * to the supplied handler. Pass {@code null} to hide the button again (the default).
     *
     * <p>The button visually disables and changes its label to "Cancelling…" once
     * clicked, since cancellation may take a beat to land (the worker thread might be
     * mid-HTTP-read when the interrupt fires and only respond at the next checkpoint).
     * That keeps the user from spam-clicking and lets them know the request was heard.
     *
     * @param handler the action to run when the user clicks Cancel; null hides the button
     */
    public void setCancelHandler( Runnable handler )
    {
        GUIUtilities.JFXPlatformRun( () -> {
            if ( cancelBtn == null || cancelBtnRow == null ) return;
            if ( handler == null ) {
                // Toggle the WRAPPING HBox managed/visible (not just the button) so
                // its top padding collapses too. Leaving the row managed with the
                // button hidden would still reserve ~14 px of vertical space at the
                // bottom of the card from the HBox's own insets.
                cancelBtnRow.setVisible( false );
                cancelBtnRow.setManaged( false );
                cancelBtn.setOnAction( null );
                cancelBtn.setText( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "progress.cancelBtn.label" ) );
                cancelBtn.setDisable( false );
                return;
            }
            cancelBtnRow.setVisible( true );
            cancelBtnRow.setManaged( true );
            cancelBtn.setText( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "progress.cancelBtn.label" ) );
            cancelBtn.setDisable( false );
            cancelBtn.setOnAction( e -> {
                // Optimistic UI: immediately reflect "we heard you" so the user doesn't
                // wonder if their click registered. The actual abort happens off-thread.
                cancelBtn.setText( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "progress.cancelBtn.cancelling" ) );
                cancelBtn.setDisable( true );
                // Guard the handler (mirrors MCLauncherLaunchProgressGui): a throw here would
                // otherwise leave the button stuck on "Cancelling…". Restore it so the user
                // can retry.
                try {
                    handler.run();
                }
                catch ( Throwable t ) {
                    cancelBtn.setText( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "progress.cancelBtn.label" ) );
                    cancelBtn.setDisable( false );
                }
            } );
        } );
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
            TaskbarProgressManager.setProgress( baseProgValue );
        } );
    }
}
