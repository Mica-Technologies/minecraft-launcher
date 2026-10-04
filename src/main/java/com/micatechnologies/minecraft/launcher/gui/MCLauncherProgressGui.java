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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.LauncherCore;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackProgressProvider;
import com.micatechnologies.minecraft.launcher.utilities.TaskbarProgressManager;
import io.github.palexdev.materialfx.controls.MFXButton;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.shape.Ellipse;
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

    /** Progress bar: Material 3 Expressive's wavy indicator. */
    @SuppressWarnings( "unused" )
    @FXML
    WavyProgressBar progressBar;

    /** Download speed and ETA info below the detail label. */
    @SuppressWarnings( "unused" )
    @FXML
    Label speedLabel;

    /** The three Minecraft blocks at the top of the card, and their ground shadows. Animated in
     *  {@link #afterShow()} with a staggered hop so the screen feels alive during long work. */
    @SuppressWarnings( "unused" ) @FXML Group voxelCube1;
    @SuppressWarnings( "unused" ) @FXML Group voxelCube2;
    @SuppressWarnings( "unused" ) @FXML Group voxelCube3;
    @SuppressWarnings( "unused" ) @FXML Ellipse voxelShadow1;
    @SuppressWarnings( "unused" ) @FXML Ellipse voxelShadow2;
    @SuppressWarnings( "unused" ) @FXML Ellipse voxelShadow3;
    /** Cancel button, and the row that holds it: hidden and unmanaged unless a caller opts in via
     *  {@link #setCancelHandler}, so the card only grows to fit it when it's offered. */
    @SuppressWarnings( "unused" ) @FXML MFXButton cancelBtn;
    @SuppressWarnings( "unused" ) @FXML HBox cancelBtnRow;
    /** Running hop animations, held so {@link #cleanup()} can stop them on scene transition. */
    private final List< Timeline > voxelAnimations = new ArrayList<>();

    /**
     * Constructs the progress GUI bound to the given stage, using the abstract
     * GUI's default scene dimensions.
     *
     * @param stage the JavaFX stage that hosts this screen
     *
     * @throws IOException if the backing FXML resource fails to load
     */
    public MCLauncherProgressGui( Stage stage ) throws IOException {
        super( stage );
    }

    /**
     * Constructs the progress GUI bound to the given stage with an explicit
     * initial scene size.
     *
     * @param stage  the JavaFX stage that hosts this screen
     * @param width  the initial scene width, in pixels
     * @param height the initial scene height, in pixels
     *
     * @throws IOException if the backing FXML resource fails to load
     */
    public MCLauncherProgressGui( Stage stage, double width, double height ) throws IOException {
        super( stage, width, height );
    }

    /**
     * {@inheritDoc}
     *
     * @return the classpath-relative path to this screen's FXML layout
     *         ({@code gui/progressGUI.fxml})
     */
    @Override
    String getSceneFxmlPath() {
        return "gui/progressGUI.fxml";
    }

    /**
     * {@inheritDoc}
     *
     * @return the human-readable scene name shown in the window title
     *         ({@code "Loading"})
     */
    @Override
    String getSceneName() {
        return LocalizationManager.get( "window.title.loading" );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Wires the OS close button (X) to close the entire application, since
     * this screen represents a blocking operation that shouldn't be dismissed
     * in isolation. The taskbar progress overlay is intentionally not attached
     * here — that happens in {@link #afterShow()} once the stage is visible, to
     * avoid native access violations from uninitialized window handles.</p>
     */
    @Override
    void setup() {
        stage.setOnCloseRequest( windowEvent -> {
            windowEvent.consume();
            LauncherCore.closeApp();
        } );
        // Note: taskbar progress bar is attached in afterShow() after the stage is visible,
        // to avoid native access violations from uninitialized window handles.
    }

    /**
     * {@inheritDoc}
     *
     * <p>Starts the voxel bounce animation, resets all labels and the progress
     * bar to their initial empty/zero state, and attaches the shared taskbar
     * progress wrapper now that the stage is realized.</p>
     */
    @Override
    void afterShow() {
        startVoxelBounceAnimation();

        setUpperLabelText( LocalizationManager.get( "progress.justAMoment" ) );
        setSectionText( "" );
        setDetailText( "" );
        setSpeedText( "" );

        // Attach the shared taskbar wrapper to this stage on first appearance. Idempotent
        // across screens — TaskbarProgressManager owns one wrapper for the app lifetime,
        // so leaving and re-entering this screen never spins up a competing instance.
        TaskbarProgressManager.attach( stage );

        setProgress( 0.0 );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Clears the OS-level taskbar progress overlay and stops the voxel bounce
     * timelines so they don't keep ticking on a hidden or disposed scene. The
     * taskbar wrapper itself is not closed here — {@code TaskbarProgressManager}
     * keeps it alive for the application lifetime so scene transitions never race
     * a release against a fresh init.</p>
     */
    @Override
    void cleanup() {
        // Always clear the OS-level overlay before the next scene takes over. We don't
        // close the wrapper here — TaskbarProgressManager keeps it alive until app exit
        // so transitions never race a release against a fresh init.
        GUIUtilities.JFXPlatformRun( TaskbarProgressManager::stop );
        stopVoxelBounceAnimation();
    }

    /**
     * Starts the staggered hop on the three blocks: each one squashes slightly as it lands, leaps
     * with an ease-out, falls with an ease-in and rests a beat, while its shadow shrinks and fades
     * as it rises. The second and third blocks start 150 ms and 300 ms after the first, so the
     * cluster moves as a wave. Only transforms and opacity change, so layout stays put.
     */
    private void startVoxelBounceAnimation() {
        stopVoxelBounceAnimation();
        if ( Motion.isReduced() ) {
            return;
        }
        startBounceOn( voxelCube1, voxelShadow1, 0 );
        startBounceOn( voxelCube2, voxelShadow2, 150 );
        startBounceOn( voxelCube3, voxelShadow3, 300 );
    }

    /**
     * Starts the indefinite hop on one block, offset by a start delay. No-op for a missing block.
     *
     * @param cube    the block to animate; ignored when {@code null}
     * @param shadow  its ground shadow, or {@code null}
     * @param delayMs the start delay, in milliseconds
     */
    private void startBounceOn( Group cube, Ellipse shadow, int delayMs ) {
        if ( cube == null ) {
            return;
        }
        Interpolator rise = Interpolator.SPLINE( 0.2, 0.0, 0.0, 1.0 );   // ease-out
        Interpolator fall = Interpolator.SPLINE( 0.4, 0.0, 1.0, 1.0 );   // ease-in
        List< KeyFrame > frames = new ArrayList<>();
        frames.add( new KeyFrame( Duration.ZERO,
                new KeyValue( cube.translateYProperty(), 0 ),
                new KeyValue( cube.scaleYProperty(), 0.9 ),
                new KeyValue( cube.scaleXProperty(), 1.06 ) ) );
        frames.add( new KeyFrame( Duration.millis( 90 ),
                new KeyValue( cube.scaleYProperty(), 1.0, rise ),
                new KeyValue( cube.scaleXProperty(), 1.0, rise ) ) );
        frames.add( new KeyFrame( Duration.millis( 380 ), new KeyValue( cube.translateYProperty(), -14, rise ) ) );
        frames.add( new KeyFrame( Duration.millis( 660 ), new KeyValue( cube.translateYProperty(), 0, fall ) ) );
        frames.add( new KeyFrame( Duration.millis( 1000 ) ) );
        if ( shadow != null ) {
            frames.add( new KeyFrame( Duration.ZERO,
                    new KeyValue( shadow.scaleXProperty(), 1.0 ), new KeyValue( shadow.opacityProperty(), 1.0 ) ) );
            frames.add( new KeyFrame( Duration.millis( 380 ),
                    new KeyValue( shadow.scaleXProperty(), 0.6, rise ), new KeyValue( shadow.opacityProperty(), 0.45, rise ) ) );
            frames.add( new KeyFrame( Duration.millis( 660 ),
                    new KeyValue( shadow.scaleXProperty(), 1.0, fall ), new KeyValue( shadow.opacityProperty(), 1.0, fall ) ) );
        }
        Timeline hop = new Timeline( frames.toArray( new KeyFrame[ 0 ] ) );
        hop.setCycleCount( Animation.INDEFINITE );
        hop.setDelay( Duration.millis( delayMs ) );
        hop.play();
        voxelAnimations.add( hop );
    }

    /** Stops every running voxel bounce. Called from {@link #cleanup()} so the timelines
     *  don't keep ticking on a hidden / disposed scene. */
    private void stopVoxelBounceAnimation() {
        for ( Timeline tt : voxelAnimations ) {
            try {
                tt.stop();
            }
            catch ( Exception | Error ignored ) { /* best-effort */ }
        }
        voxelAnimations.clear();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link HelpTopic#GETTING_STARTED}, the help topic shown for the
     *         progress screen
     */
    @Override
    HelpTopic getHelpTopic() { return HelpTopic.GETTING_STARTED; }

    /** Disable toolbar navigation while a blocking operation is in progress. */
    @Override
    boolean allowsToolbarNavigation() { return false; }

    /**
     * Sets the overall task title (top line). E.g. "Launching: Forge 1.15.2" or "Signing In".
     *
     * @param text the title text to display
     */
    public void setUpperLabelText( String text ) {
        GUIUtilities.JFXPlatformRun( () -> upperLabel.setText( text ) );
    }

    /**
     * Sets the current section heading (between title and progress bar). E.g. "Downloading mods..."
     *
     * @param text the section heading text to display
     */
    public void setSectionText( String text ) {
        GUIUtilities.JFXPlatformRun( () -> sectionLabel.setText( text ) );
    }

    /**
     * Sets the detail text below the progress bar. E.g. "Verified library jna-4.4.0.jar"
     *
     * @param text the file-level detail text to display
     */
    public void setDetailText( String text ) {
        GUIUtilities.JFXPlatformRun( () -> detailLabel.setText( text ) );
    }

    /**
     * Sets the speed/ETA info text. E.g. "2.4 MB/s -- 3:42 remaining -- 12/150 files"
     *
     * @param text the speed/ETA info text to display
     */
    public void setSpeedText( String text ) {
        GUIUtilities.JFXPlatformRun( () -> {
            speedLabel.setText( text );
            // A chip with nothing in it is just an empty pill: hide it until there's text.
            boolean any = text != null && !text.isEmpty();
            speedLabel.setVisible( any );
            speedLabel.setManaged( any );
        } );
    }

    /**
     * Sets the lower label text. For backward compatibility, this updates the section label.
     * Direct callers should prefer {@link #setSectionText(String)} or {@link #setDetailText(String)}.
     *
     * @param text the lower label text to display (routed to the section label)
     */
    public void setLowerLabelText( String text ) {
        setSectionText( text );
    }

    /**
     * Sets both upper and section labels. For backward compatibility with existing callers.
     *
     * @param upper the overall task title (top line)
     * @param lower the section heading (routed to the section label)
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
     * Sets the progress bar value, also mirroring it onto the OS taskbar progress
     * overlay. The value is on a 0-100 scale (normalized internally against
     * {@link GameModPackProgressProvider#PROGRESS_PERCENT_BASE} to the 0.0-1.0
     * range the bar expects), or {@link ProgressIndicator#INDETERMINATE_PROGRESS} to
     * show an indeterminate animation.
     *
     * @param progress the progress value on a 0-100 scale, or
     *                 {@link ProgressIndicator#INDETERMINATE_PROGRESS}
     */
    public void setProgress( double progress ) {
        final double baseProgValue = ( progress == ProgressIndicator.INDETERMINATE_PROGRESS ) ?
                                     ( progress ) :
                                     ( progress / GameModPackProgressProvider.PROGRESS_PERCENT_BASE );

        GUIUtilities.JFXPlatformRun( () -> {
            progressBar.setProgress( baseProgValue );
            TaskbarProgressManager.setProgress( baseProgValue );
        } );
    }
}
