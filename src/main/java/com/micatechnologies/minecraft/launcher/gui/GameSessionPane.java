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

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.crash.CrashDiagnosis;
import com.micatechnologies.minecraft.launcher.game.crash.CrashReportAnalyzer;
import com.micatechnologies.minecraft.launcher.game.crash.Suggestion;
import com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker;
import com.micatechnologies.minecraft.launcher.game.session.GameLog;
import com.micatechnologies.minecraft.launcher.game.session.GameSession;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import io.github.palexdev.materialfx.controls.MFXButton;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.awt.Desktop;
import java.util.List;
import java.util.function.Consumer;

/**
 * One game in the Running Games window: its launch steps while it prepares, then its live log,
 * and how it ended.
 *
 * <p>Everything shown comes from the {@link GameSession}, which keeps capturing the game's
 * output whether or not this pane exists. A pane opened mid-game starts from the log captured
 * so far and continues from there. Build, use and dispose on the FX thread.</p>
 *
 * @since 2026.10
 */
final class GameSessionPane
{
    private final GameSession session;
    private final BorderPane  root = new BorderPane();

    private final Label  statusChip = new Label();
    private final Label  uptime     = new Label();
    private final StackPane center  = new StackPane();

    // Preparing
    private final VBox   stepsBox = new VBox( 10 );
    private final VBox   progressView;
    private       LaunchProgressTracker boundTracker;
    private final LaunchProgressTracker.Listener trackerListener = step -> queueStepsRefresh();
    private       boolean stepsRefreshQueued;

    // Running and after
    private final VBox      logView;
    private final VBox      diagnosisCard = new VBox( 6 );
    private final TextArea  logArea = new TextArea();
    private final TextField search = new TextField();
    private final Label     searchStatus = new Label();
    private final CheckBox  autoScroll = new CheckBox( LocalizationManager.get( "console.autoPin.label" ) );
    private final Label     truncated = new Label();
    private final Hyperlink openFileLink = new Hyperlink( LocalizationManager.get( "console.openLogLink.text" ) );
    private final MFXButton crashToggle = new MFXButton();
    private       GameLog   boundLog;
    private       Runnable  unsubscribeLog;
    private       int       displayLines;
    private       boolean   showingCrashReport;

    // Footer
    private final MFXButton cancelBtn = new MFXButton( LocalizationManager.get( "session.button.cancel" ) );
    private final MFXButton stopBtn   = new MFXButton( LocalizationManager.get( "session.button.stop" ) );
    private final MFXButton killBtn   = new MFXButton( LocalizationManager.get( "session.button.kill" ) );
    private final MFXButton copyBtn   = new MFXButton( LocalizationManager.get( "console.copyBtn.label" ) );
    private final MFXButton fileBtn   = new MFXButton( LocalizationManager.get( "session.button.openLogFile" ) );

    private final Timeline uptimeTicker;
    private final Consumer< GameSession > sessionListener =
            s -> Platform.runLater( this::refreshFromSession );
    private       boolean disposed;
    private       boolean diagnosed;

    /**
     * @param session the game this pane shows
     */
    GameSessionPane( GameSession session )
    {
        this.session = session;

        // ---- Header ----
        Label title = new Label( session.packName() );
        title.getStyleClass().add( "heading-h3" );
        Label subtitle = new Label( session.accountName() == null
                                    ? LocalizationManager.get( "session.subtitle.server" )
                                    : LocalizationManager.format( "session.subtitle.playingAs", session.accountName() ) );
        subtitle.getStyleClass().add( "muted" );
        VBox titles = new VBox( 2, title, subtitle );
        HBox header = new HBox( 12 );
        header.setAlignment( Pos.CENTER_LEFT );
        if ( session.accountUuid() != null ) {
            ImageView avatar = new ImageView( AvatarImages.get( session.accountUuid() ) );
            avatar.setFitWidth( 36 );
            avatar.setFitHeight( 36 );
            avatar.setClip( new Circle( 18, 18, 18 ) );
            header.getChildren().add( avatar );
        }
        Region spacer = new Region();
        HBox.setHgrow( spacer, Priority.ALWAYS );
        statusChip.getStyleClass().add( "stat-chip" );
        uptime.getStyleClass().add( "muted" );
        header.getChildren().addAll( titles, spacer, uptime, statusChip );
        header.setPadding( new Insets( 0, 0, 12, 0 ) );

        // ---- Preparing: the launch steps ----
        Label preparing = new Label( LocalizationManager.get( "session.preparing.heading" ) );
        preparing.getStyleClass().add( "heading-h3" );
        cancelBtn.setOnAction( e -> session.cancel() );
        progressView = new VBox( 14, preparing, stepsBox, cancelBtn );
        progressView.setPadding( new Insets( 8 ) );

        // ---- Running: the log ----
        diagnosisCard.getStyleClass().addAll( "card", "crashDiagnosisCard" );
        diagnosisCard.setVisible( false );
        diagnosisCard.setManaged( false );

        search.setPromptText( LocalizationManager.get( "console.search.placeholder" ) );
        HBox.setHgrow( search, Priority.ALWAYS );
        search.setOnAction( e -> find( true ) );
        MFXButton prev = new MFXButton( LocalizationManager.get( "console.search.prev" ) );
        prev.setOnAction( e -> find( false ) );
        MFXButton next = new MFXButton( LocalizationManager.get( "console.search.next" ) );
        next.setOnAction( e -> find( true ) );
        searchStatus.getStyleClass().add( "muted" );
        autoScroll.setSelected( true );
        crashToggle.setVisible( false );
        crashToggle.setManaged( false );
        crashToggle.setOnAction( e -> toggleCrashReport() );
        HBox toolbar = new HBox( 8, search, searchStatus, prev, next, autoScroll, crashToggle );
        toolbar.setAlignment( Pos.CENTER_LEFT );

        logArea.setEditable( false );
        logArea.setWrapText( true );
        logArea.getStyleClass().add( "text-mono" );
        logArea.getStyleClass().add( "type-body-small" );
        VBox.setVgrow( logArea, Priority.ALWAYS );

        truncated.getStyleClass().add( "subtle" );
        truncated.setVisible( false );
        truncated.setManaged( false );
        openFileLink.setVisible( false );
        openFileLink.setManaged( false );
        openFileLink.setOnAction( e -> openLogFile() );
        HBox notices = new HBox( 6, truncated, openFileLink );
        notices.setAlignment( Pos.CENTER_LEFT );

        logView = new VBox( 8, diagnosisCard, toolbar, logArea, notices );

        center.getChildren().addAll( progressView, logView );

        // ---- Footer ----
        copyBtn.setOnAction( e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString( logArea.getText() );
            Clipboard.getSystemClipboard().setContent( content );
        } );
        fileBtn.setOnAction( e -> openLogFile() );
        stopBtn.setOnAction( e -> session.stop( false ) );
        killBtn.getStyleClass().add( "dangerZone" );
        killBtn.setOnAction( e -> session.stop( true ) );
        Region footerSpacer = new Region();
        HBox.setHgrow( footerSpacer, Priority.ALWAYS );
        HBox footer = new HBox( 8, copyBtn, fileBtn, footerSpacer, stopBtn, killBtn );
        footer.setAlignment( Pos.CENTER_LEFT );
        footer.setPadding( new Insets( 12, 0, 0, 0 ) );

        root.setTop( header );
        root.setCenter( center );
        root.setBottom( footer );
        root.setPadding( new Insets( 16 ) );
        root.getStyleClass().add( "gameSessionPane" );

        uptimeTicker = new Timeline( new KeyFrame( Duration.seconds( 1 ), e -> refreshUptime() ) );
        uptimeTicker.setCycleCount( Timeline.INDEFINITE );

        session.addListener( sessionListener );
        refreshFromSession();
    }

    /** @return the pane's root node */
    BorderPane root()
    {
        return root;
    }

    /** @return the session shown */
    GameSession session()
    {
        return session;
    }

    /**
     * Stops listening to the session, its tracker and its log. The game is unaffected.
     */
    void dispose()
    {
        disposed = true;
        uptimeTicker.stop();
        session.removeListener( sessionListener );
        if ( boundTracker != null ) {
            boundTracker.removeListener( trackerListener );
        }
        if ( unsubscribeLog != null ) {
            unsubscribeLog.run();
        }
    }

    // ------------------------------------------------------------------ session state

    private void refreshFromSession()
    {
        if ( disposed ) {
            return;
        }
        GameSession.Phase phase = session.phase();
        statusChip.setText( LocalizationManager.get( "session.status." + phase.name().toLowerCase( java.util.Locale.ROOT ) ) );
        statusChip.getStyleClass().removeIf( c -> c.startsWith( "sessionStatus-" ) );
        statusChip.getStyleClass().add( "sessionStatus-" + phase.name().toLowerCase( java.util.Locale.ROOT ) );

        bindTracker( session.tracker() );
        bindLog( session.log() );

        boolean preparing = phase == GameSession.Phase.PREPARING;
        boolean running = phase == GameSession.Phase.RUNNING;
        progressView.setVisible( preparing );
        logView.setVisible( !preparing );
        cancelBtn.setDisable( session.isCancelled() );
        setShown( stopBtn, running );
        setShown( killBtn, running );
        setShown( copyBtn, !preparing );
        setShown( fileBtn, boundLog != null && boundLog.file() != null );

        if ( running ) {
            uptimeTicker.play();
        }
        else {
            uptimeTicker.stop();
        }
        refreshUptime();

        if ( phase == GameSession.Phase.CRASHED && !diagnosed ) {
            showDiagnosis();
        }
        if ( ( phase == GameSession.Phase.FAILED || phase == GameSession.Phase.CANCELLED ) && boundLog == null ) {
            logArea.setText( LocalizationManager.get( phase == GameSession.Phase.FAILED
                                                      ? "session.log.failed" : "session.log.cancelled" ) );
        }
    }

    private void refreshUptime()
    {
        long start = session.startedMs();
        if ( start <= 0 ) {
            uptime.setText( "" );
            return;
        }
        long end = session.phase().isActive() ? System.currentTimeMillis() : session.endedMs();
        uptime.setText( formatDuration( Math.max( 0, end - start ) ) );
    }

    /**
     * Formats a duration as {@code m:ss} or {@code h:mm:ss}. Pure, for testing. (The console's
     * old MessageFormat strings didn't zero-pad, so they showed "5:3" for five minutes three.)
     *
     * @param ms the duration
     *
     * @return the clock text
     */
    static String formatDuration( long ms )
    {
        long seconds = ( ms / 1000 ) % 60;
        long minutes = ( ms / 60000 ) % 60;
        long hours = ms / 3600000;
        String clock = hours > 0 ? String.format( java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds )
                                 : String.format( java.util.Locale.ROOT, "%d:%02d", minutes, seconds );
        return LocalizationManager.format( "session.uptime", clock );
    }

    // ------------------------------------------------------------------ launch steps

    private void bindTracker( LaunchProgressTracker tracker )
    {
        if ( tracker == boundTracker ) {
            return;
        }
        if ( boundTracker != null ) {
            boundTracker.removeListener( trackerListener );
        }
        boundTracker = tracker;
        if ( tracker != null ) {
            tracker.addListener( trackerListener );
        }
        refreshSteps();
    }

    /** Coalesces a burst of tracker updates into one repaint. */
    private void queueStepsRefresh()
    {
        Platform.runLater( () -> {
            if ( stepsRefreshQueued ) {
                return;
            }
            stepsRefreshQueued = true;
            Platform.runLater( () -> {
                stepsRefreshQueued = false;
                refreshSteps();
            } );
        } );
    }

    private void refreshSteps()
    {
        if ( disposed ) {
            return;
        }
        stepsBox.getChildren().clear();
        if ( boundTracker == null ) {
            return;
        }
        for ( LaunchProgressTracker.Step step : boundTracker.steps() ) {
            stepsBox.getChildren().add( stepRow( step ) );
        }
    }

    private static HBox stepRow( LaunchProgressTracker.Step step )
    {
        StepBadge icon = new StepBadge( step.state() );
        Label label = new Label( step.displayLabel() );
        VBox text = new VBox( 2, label );
        String detail = step.state() == LaunchProgressTracker.State.FAILED ? step.errorMessage() : step.subText();
        if ( detail != null && !detail.isBlank() ) {
            Label sub = new Label( detail );
            sub.getStyleClass().add( "muted" );
            sub.setWrapText( true );
            text.getChildren().add( sub );
        }
        if ( step.state() == LaunchProgressTracker.State.RUNNING ) {
            WavyProgressBar bar = new WavyProgressBar( step.progress() > 0 ? step.progress() : -1 );
            bar.setMaxWidth( Double.MAX_VALUE );
            text.getChildren().add( bar );
        }
        HBox.setHgrow( text, Priority.ALWAYS );
        HBox row = new HBox( 10, icon, text );
        row.setAlignment( Pos.TOP_LEFT );
        return row;
    }

    // ------------------------------------------------------------------ log

    private void bindLog( GameLog log )
    {
        if ( log == null || log == boundLog ) {
            return;
        }
        boundLog = log;
        GameLog.Subscription sub = log.subscribe( new GameLog.Listener()
        {
            @Override
            public void onLines( List< String > lines )
            {
                StringBuilder text = new StringBuilder();
                for ( String l : lines ) {
                    text.append( l ).append( '\n' );
                }
                int count = lines.size();
                Platform.runLater( () -> append( text.toString(), count ) );
            }
        } );
        unsubscribeLog = sub.cancel();
        logArea.setText( sub.snapshot() );
        displayLines = countLines( sub.snapshot() );
        trimIfNeeded();
        if ( autoScroll.isSelected() ) {
            logArea.positionCaret( logArea.getLength() );
        }
    }

    private void append( String text, int lines )
    {
        if ( disposed || showingCrashReport ) {
            return;
        }
        if ( autoScroll.isSelected() ) {
            logArea.appendText( text );
        }
        else {
            double scrollTop = logArea.getScrollTop();
            int caret = logArea.getCaretPosition();
            logArea.appendText( text );
            logArea.positionCaret( caret );
            logArea.setScrollTop( scrollTop );
        }
        displayLines += lines;
        trimIfNeeded();
    }

    /** Keeps the visible log within Settings' line limit; the full log is in the file. */
    private void trimIfNeeded()
    {
        int maxLines = ConfigManager.getConsoleLogMaxLines();
        if ( !LogTrimPolicy.shouldTrimDisplay( displayLines, maxLines ) ) {
            return;
        }
        int idx = LogTrimPolicy.displayDropOffset( logArea.getText(), displayLines - maxLines );
        if ( idx > 0 && idx <= logArea.getLength() ) {
            logArea.deleteText( 0, idx );
            displayLines = maxLines;
        }
        if ( !truncated.isVisible() ) {
            truncated.setText( LocalizationManager.format( "console.truncationLabel", maxLines ) );
            setShown( truncated, true );
            setShown( openFileLink, boundLog != null && boundLog.file() != null );
        }
    }

    private static int countLines( String text )
    {
        int n = 0;
        for ( int i = 0; i < text.length(); i++ ) {
            if ( text.charAt( i ) == '\n' ) {
                n++;
            }
        }
        return n;
    }

    private void find( boolean forward )
    {
        String needle = search.getText();
        String hay = logArea.getText();
        if ( needle == null || needle.isEmpty() || hay.isEmpty() ) {
            searchStatus.setText( "" );
            return;
        }
        String lowerHay = hay.toLowerCase( java.util.Locale.ROOT );
        String lowerNeedle = needle.toLowerCase( java.util.Locale.ROOT );
        int from = logArea.getSelection().getLength() > 0
                   ? ( forward ? logArea.getSelection().getEnd() : logArea.getSelection().getStart() - 1 )
                   : ( forward ? 0 : hay.length() );
        int idx = forward ? lowerHay.indexOf( lowerNeedle, Math.max( 0, from ) )
                          : lowerHay.lastIndexOf( lowerNeedle, Math.max( 0, from ) );
        if ( idx < 0 ) {
            idx = forward ? lowerHay.indexOf( lowerNeedle ) : lowerHay.lastIndexOf( lowerNeedle );
        }
        if ( idx < 0 ) {
            searchStatus.setText( LocalizationManager.get( "session.search.noMatches" ) );
            return;
        }
        searchStatus.setText( "" );
        autoScroll.setSelected( false );
        logArea.selectRange( idx, idx + needle.length() );
    }

    private void openLogFile()
    {
        if ( boundLog == null || boundLog.file() == null ) {
            return;
        }
        java.io.File file = boundLog.file().toFile();
        SystemUtilities.spawnNewTask( () -> {
            try {
                Desktop.getDesktop().open( file );
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.console.openLogFileFailed", e.getMessage() ) );
            }
        } );
    }

    // ------------------------------------------------------------------ crash

    private void showDiagnosis()
    {
        diagnosed = true;
        String report = session.crashReport();
        String analyze = report != null ? report : ( boundLog != null ? boundLog.text() : "" );
        CrashDiagnosis diagnosis;
        try {
            diagnosis = CrashReportAnalyzer.analyze( analyze, session.pack(), session.exitCode() );
        }
        catch ( RuntimeException e ) {
            diagnosis = null;
        }
        diagnosisCard.getChildren().clear();
        if ( diagnosis != null ) {
            Label t = new Label( diagnosis.title() );
            t.getStyleClass().addAll( "heading-h3", "crashDiagnosisTitle" );
            Label s = new Label( diagnosis.summary() );
            s.setWrapText( true );
            HBox actions = new HBox( 6 );
            actions.setAlignment( Pos.CENTER_LEFT );
            for ( Suggestion suggestion : diagnosis.suggestions() ) {
                if ( suggestion.action() == null ) {
                    Label hint = new Label( suggestion.label() );
                    hint.getStyleClass().add( "muted" );
                    actions.getChildren().add( hint );
                    continue;
                }
                MFXButton b = new MFXButton( suggestion.label() );
                if ( suggestion.primary() ) {
                    b.getStyleClass().add( "primary" );
                }
                b.setOnAction( e -> SystemUtilities.spawnNewTask( suggestion.action() ) );
                actions.getChildren().add( b );
            }
            diagnosisCard.getStyleClass().removeAll( "crashSeverityCritical", "crashSeverityWarning", "crashSeverityInfo" );
            diagnosisCard.getStyleClass().add( switch ( diagnosis.severity() ) {
                case CRITICAL -> "crashSeverityCritical";
                case WARNING -> "crashSeverityWarning";
                case INFO -> "crashSeverityInfo";
            } );
            diagnosisCard.getChildren().addAll( t, s, actions );
            diagnosisCard.setPadding( new Insets( 12, 14, 12, 14 ) );
            setShown( diagnosisCard, true );
        }
        if ( report != null ) {
            crashToggle.setText( LocalizationManager.get( "console.crashReportBtn.crashReport" ) );
            setShown( crashToggle, true );
            // Nothing captured (the game died before logging) leaves an empty log; open on the
            // crash report instead.
            if ( boundLog == null || boundLog.text().isBlank() ) {
                toggleCrashReport();
            }
        }
    }

    private void toggleCrashReport()
    {
        String report = session.crashReport();
        if ( report == null ) {
            return;
        }
        showingCrashReport = !showingCrashReport;
        if ( showingCrashReport ) {
            logArea.setText( report );
            logArea.positionCaret( 0 );
            crashToggle.setText( LocalizationManager.get( "console.crashReportBtn.gameLog" ) );
        }
        else {
            String text = boundLog != null ? boundLog.text() : "";
            logArea.setText( text );
            displayLines = countLines( text );
            trimIfNeeded();
            crashToggle.setText( LocalizationManager.get( "console.crashReportBtn.crashReport" ) );
        }
    }

    private static void setShown( javafx.scene.Node node, boolean shown )
    {
        node.setVisible( shown );
        node.setManaged( shown );
    }
}
