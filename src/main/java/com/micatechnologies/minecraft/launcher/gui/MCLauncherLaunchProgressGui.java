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
import com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker;
import com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker.Step;
import com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker.State;
import com.micatechnologies.minecraft.launcher.utilities.TaskbarProgressManager;
import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXProgressBar;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.EnumMap;

/**
 * Step-list launch progress GUI — replaces {@link MCLauncherProgressGui} for the
 * Play→game-JVM pipeline once the underlying launch flow can drive concurrent
 * branches. Renders one row per {@link Step} in the attached
 * {@link LaunchProgressTracker}; rows update independently as each branch
 * fires state changes against the tracker.
 *
 * <p>Row layout per step:</p>
 * <pre>
 *   [ ●  ]   Step display label
 *            Sub-text (current activity)              [ ────── progress bar ]
 * </pre>
 *
 * <p>Status icons by {@link State}: {@code ○} pending, {@code ●} running,
 * {@code ✓} done, {@code ✗} failed. Skipped steps are typically omitted from
 * the tracker's step list and therefore don't render a row at all — this is
 * how vanilla packs hide the Forge stages cleanly.</p>
 *
 * <p>Step 1 of the 3.2 refactor: this GUI exists and renders correctly when
 * driven, but isn't wired into the launch flow yet. Step 2 swaps it in for
 * the existing {@link MCLauncherProgressGui} on the Play path.</p>
 */
public class MCLauncherLaunchProgressGui extends MCLauncherAbstractGui
{
    @SuppressWarnings( "unused" ) @FXML Label titleLabel;
    @SuppressWarnings( "unused" ) @FXML Label subtitleLabel;
    @SuppressWarnings( "unused" ) @FXML VBox rowsBox;
    @SuppressWarnings( "unused" ) @FXML HBox cancelBtnRow;
    @SuppressWarnings( "unused" ) @FXML MFXButton cancelBtn;

    /** Per-step row widgets, populated in {@link #attachToTracker(LaunchProgressTracker)}.
     *  Holding references avoids walking rowsBox.getChildren() on every state
     *  change — a state callback fires once per step transition (~6 steps × a
     *  handful of transitions each over the launch), so it's not load-bearing
     *  for performance, but the direct map also lets us reason about the row
     *  set independently of FXML node ordering. */
    private final EnumMap< LaunchProgressTracker.StepId, RowWidgets > rowWidgets =
            new EnumMap<>( LaunchProgressTracker.StepId.class );

    /** Tracker the GUI is currently subscribed to. Held so detach can deregister
     *  the listener on screen transition and we don't accumulate live listeners
     *  against a torn-down GUI. */
    private LaunchProgressTracker attachedTracker;
    private LaunchProgressTracker.Listener attachedListener;

    /** Coalesces the per-file progress storm. A warm launch fires thousands of
     *  tracker notifications in a tight burst (one per verified asset — ~3,500-4,500
     *  for a modern asset index); a naive {@code Platform.runLater} per event floods
     *  the FX queue and re-runs the native taskbar update each time, freezing the
     *  progress window. Instead we schedule at most one pending FX flush at a time
     *  (compareAndSet gate): when it runs it re-renders all rows from the tracker's
     *  current state and refreshes the taskbar once, then re-arms. This naturally
     *  throttles updates to the FX thread's frame rate. */
    private final java.util.concurrent.atomic.AtomicBoolean progressFlushScheduled =
            new java.util.concurrent.atomic.AtomicBoolean( false );

    public MCLauncherLaunchProgressGui( Stage stage ) throws IOException
    {
        super( stage );
    }

    @Override
    String getSceneFxmlPath() { return "gui/launchProgressGUI.fxml"; }

    @Override
    String getSceneName() { return "Launching"; }

    @Override
    HelpTopic getHelpTopic() { return HelpTopic.GETTING_STARTED; }

    @Override
    void setup()
    {
        stage.setOnCloseRequest( windowEvent -> {
            windowEvent.consume();
            LauncherCore.closeApp();
        } );
    }

    @Override
    void afterShow()
    {
        // Attach the shared taskbar wrapper so a coarse "is the launcher
        // busy?" indicator is visible on Win11's taskbar / macOS dock even
        // when the launcher window itself isn't focused. The per-step
        // detail lives in this GUI; the taskbar gets the rolled-up avg.
        TaskbarProgressManager.attach( stage );
    }

    @Override
    void cleanup()
    {
        // Drop the tracker subscription so the listener doesn't keep
        // a reference to this GUI alive past scene transition.
        detachFromTracker();
        GUIUtilities.JFXPlatformRun( TaskbarProgressManager::stop );
    }

    /** Sets the title line (e.g. "Launching Forge 1.16.5"). */
    public void setTitle( String text )
    {
        GUIUtilities.JFXPlatformRun( () -> titleLabel.setText( text == null ? "" : text ) );
    }

    /** Sets the optional subtitle under the title (e.g. version string).
     *  Empty / null hides the subtitle row. */
    public void setSubtitle( String text )
    {
        GUIUtilities.JFXPlatformRun( () -> {
            boolean shown = text != null && !text.isEmpty();
            subtitleLabel.setText( shown ? text : "" );
            subtitleLabel.setVisible( shown );
            subtitleLabel.setManaged( shown );
        } );
    }

    /**
     * Wires the GUI to a tracker. Walks the tracker's step list once to
     * build the row widgets, then subscribes for live updates. Replacing
     * the tracker on a re-attach detaches the previous one cleanly.
     */
    public void attachToTracker( LaunchProgressTracker tracker )
    {
        GUIUtilities.JFXPlatformRun( () -> {
            detachFromTracker();
            this.attachedTracker = tracker;
            rowsBox.getChildren().clear();
            rowWidgets.clear();
            for ( Step step : tracker.steps() ) {
                RowWidgets row = buildRow( step );
                rowsBox.getChildren().add( row.container );
                rowWidgets.put( step.id(), row );
                renderRow( row, step );
            }
            attachedListener = step -> {
                // Coalesce: only the first event since the last flush queues an FX
                // runnable; the rest fall through (their state is picked up when the
                // pending flush reads the tracker's current rows).
                if ( progressFlushScheduled.compareAndSet( false, true ) ) {
                    Platform.runLater( () -> {
                        progressFlushScheduled.set( false );
                        LaunchProgressTracker t = attachedTracker;
                        if ( t != null ) {
                            for ( Step s : t.steps() ) {
                                RowWidgets row = rowWidgets.get( s.id() );
                                if ( row != null ) renderRow( row, s );
                            }
                        }
                        refreshOverallTaskbarProgress();
                    } );
                }
            };
            tracker.addListener( attachedListener );
            refreshOverallTaskbarProgress();
        } );
    }

    private void detachFromTracker()
    {
        if ( attachedTracker != null && attachedListener != null ) {
            attachedTracker.removeListener( attachedListener );
        }
        attachedTracker = null;
        attachedListener = null;
    }

    /**
     * Shows the Cancel button at the bottom of the card and wires its action
     * to the supplied handler. Pass {@code null} to hide the button. The
     * button visually disables on click so a spam-clicker doesn't fire the
     * handler repeatedly while cancellation is taking a beat to land.
     */
    public void setCancelHandler( Runnable handler )
    {
        GUIUtilities.JFXPlatformRun( () -> {
            if ( cancelBtn == null || cancelBtnRow == null ) return;
            if ( handler == null ) {
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
                cancelBtn.setText( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "progress.cancelBtn.cancelling" ) );
                cancelBtn.setDisable( true );
                try { handler.run(); }
                catch ( Throwable ignored ) { /* best-effort */ }
            } );
        } );
    }

    // =========================================================================
    //  Row construction + rendering
    // =========================================================================

    /** Bundles the per-step node references so renderRow doesn't have to walk
     *  the row's children to find each widget on every update. */
    private static final class RowWidgets
    {
        final HBox container;
        final Label statusIcon;
        final Label titleLabel;
        final Label subTextLabel;
        final MFXProgressBar progressBar;
        /** PauseTransition that hides the bar shortly after a RUNNING → DONE
         *  transition, giving the eye time to register the bar at 100% before
         *  it disappears. Lazy — only allocated when first needed. */
        javafx.animation.PauseTransition doneHidePause;

        RowWidgets( HBox container, Label statusIcon, Label titleLabel,
                    Label subTextLabel, MFXProgressBar progressBar )
        {
            this.container = container;
            this.statusIcon = statusIcon;
            this.titleLabel = titleLabel;
            this.subTextLabel = subTextLabel;
            this.progressBar = progressBar;
        }
    }

    private RowWidgets buildRow( Step step )
    {
        Label statusIcon = new Label( iconFor( step.state() ) );
        statusIcon.getStyleClass().add( "launchStepIcon" );
        statusIcon.setMinWidth( 28 );
        statusIcon.setAlignment( Pos.CENTER );

        Label titleLabel = new Label( step.displayLabel() );
        titleLabel.getStyleClass().add( "heading-h3" );

        // Sub-text and progress bar stay managed-but-possibly-invisible across
        // every state so the row's overall height doesn't change as a row
        // transitions PENDING → RUNNING → DONE. Hiding via setManaged(false)
        // would collapse the slot out of layout entirely, and the row would
        // grow when the bar / sub-text first appeared and shrink again when
        // it went away on completion — the user-visible symptom was the
        // whole rows column "breathing" as states settled in over the launch.
        // Keep the layout slot fixed; toggle only the rendered visibility.
        Label subText = new Label( step.subText() );
        subText.getStyleClass().add( "subtle" );
        subText.setStyle( "-fx-font-size: 11px;" );
        subText.setVisible( !step.subText().isEmpty() );

        MFXProgressBar bar = new MFXProgressBar();
        bar.setPrefWidth( 200 );
        bar.setProgress( step.progress() );
        bar.setVisible( step.state() == State.RUNNING );

        VBox textCol = new VBox( 2, titleLabel, subText );
        textCol.setAlignment( Pos.CENTER_LEFT );
        HBox.setHgrow( textCol, Priority.ALWAYS );

        Region spacer = new Region();
        HBox.setHgrow( spacer, Priority.SOMETIMES );

        HBox row = new HBox( 12, statusIcon, textCol, spacer, bar );
        row.setAlignment( Pos.CENTER_LEFT );
        row.getStyleClass().add( "launchStepRow" );

        return new RowWidgets( row, statusIcon, titleLabel, subText, bar );
    }

    private void renderRow( RowWidgets row, Step step )
    {
        State state = step.state();
        row.statusIcon.setText( iconFor( state ) );
        row.statusIcon.getStyleClass().removeAll(
                "launchStepIcon-pending", "launchStepIcon-running",
                "launchStepIcon-done", "launchStepIcon-failed",
                "launchStepIcon-skipped" );
        row.statusIcon.getStyleClass().add( iconStyleClassFor( state ) );

        String sub = step.subText();
        // Failure overrides whatever sub-text was in flight — the error
        // message is more useful than the last in-progress filename.
        if ( state == State.FAILED && step.errorMessage() != null && !step.errorMessage().isEmpty() ) {
            sub = step.errorMessage();
        }
        row.subTextLabel.setText( sub );
        row.subTextLabel.setVisible( !sub.isEmpty() );
        // Note: deliberately NOT touching setManaged on either the sub-text
        // label or the progress bar — both stay managed=true for the row's
        // lifetime so the row's height never changes across state
        // transitions. See the comment in buildRow().

        // Progress bar visibility:
        //  - RUNNING: visible, value tracks step.progress()
        //  - DONE: snap to 100% and hide after a short pause so the user sees
        //          the filled state before the checkmark takes over. Sub-second
        //          steps were finishing so fast the bar was being hidden before
        //          any value above empty ever rendered.
        //  - PENDING / FAILED / SKIPPED: hidden
        if ( state == State.RUNNING ) {
            cancelDoneHide( row );
            row.progressBar.setVisible( true );
            row.progressBar.setProgress( step.progress() );
        }
        else if ( state == State.DONE ) {
            if ( row.progressBar.isVisible() ) {
                // Transitioning from RUNNING → DONE. Snap full, then hide on a beat.
                row.progressBar.setProgress( 1.0 );
                scheduleDoneHide( row );
            }
            else {
                // Already hidden (e.g. row was rendered as DONE on first build —
                // unlikely but defensive). Leave hidden.
                cancelDoneHide( row );
            }
        }
        else {
            // PENDING / FAILED / SKIPPED — hide immediately.
            cancelDoneHide( row );
            row.progressBar.setVisible( false );
        }
    }

    /** Schedules (or restarts) the brief 100%-snap hold before the bar fades
     *  out of the row. {@link RowWidgets#doneHidePause} is allocated lazily
     *  so rows whose steps run-and-finish faster than the hold duration
     *  don't pay the allocation cost up front. */
    private static void scheduleDoneHide( RowWidgets row )
    {
        if ( row.doneHidePause == null ) {
            row.doneHidePause = new javafx.animation.PauseTransition(
                    javafx.util.Duration.millis( 160 ) );
            row.doneHidePause.setOnFinished( e -> row.progressBar.setVisible( false ) );
        }
        row.doneHidePause.playFromStart();
    }

    /** Cancels any in-flight done-hide PauseTransition. Called on transitions
     *  back to RUNNING (re-entering a re-rendered step) and on any non-DONE
     *  state so the scheduled hide doesn't fire on a row that's already
     *  pending or running again. */
    private static void cancelDoneHide( RowWidgets row )
    {
        if ( row.doneHidePause != null ) {
            row.doneHidePause.stop();
        }
    }

    private static String iconFor( State state )
    {
        return switch ( state ) {
            case PENDING -> "○";
            case RUNNING -> "●";
            case DONE    -> "✓";
            case FAILED  -> "✗";
            case SKIPPED -> "—";
        };
    }

    private static String iconStyleClassFor( State state )
    {
        return switch ( state ) {
            case PENDING -> "launchStepIcon-pending";
            case RUNNING -> "launchStepIcon-running";
            case DONE    -> "launchStepIcon-done";
            case FAILED  -> "launchStepIcon-failed";
            case SKIPPED -> "launchStepIcon-skipped";
        };
    }

    /** Pushes a coarse rolled-up fraction to the OS-level taskbar overlay.
     *  Averages each row's contribution: done/failed/skipped count as fully
     *  accounted (1.0), running rows contribute their progress, and pending
     *  rows count as 0. {@link TaskbarProgressManager#setProgress} takes the
     *  fraction directly (0..1), not a percent. Cheap recompute on every
     *  state change. */
    private void refreshOverallTaskbarProgress()
    {
        if ( attachedTracker == null ) return;
        int total = 0;
        double accumulated = 0;
        for ( Step s : attachedTracker.steps() ) {
            total++;
            switch ( s.state() ) {
                case DONE, FAILED, SKIPPED -> accumulated += 1.0;
                case RUNNING -> accumulated += s.progress();
                case PENDING -> { /* counts toward total only */ }
            }
        }
        if ( total > 0 ) {
            TaskbarProgressManager.setProgress( accumulated / total );
        }
    }
}
