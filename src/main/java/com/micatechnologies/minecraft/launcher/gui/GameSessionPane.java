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
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
    /** Set while a steps refresh is queued on the FX thread; tracker events arrive off it. */
    private final AtomicBoolean stepsRefreshQueued = new AtomicBoolean();
    /** The bound tracker's rows, built once per tracker and updated in place. */
    private final Map< LaunchProgressTracker.StepId, StepRow > stepRows =
            new EnumMap<>( LaunchProgressTracker.StepId.class );

    // Running and after
    private final VBox      logView;
    private final VBox      diagnosisCard = new VBox( 6 );
    private final SessionLogView logArea = new SessionLogView();
    private final io.github.palexdev.materialfx.controls.MFXTextField search =
            new io.github.palexdev.materialfx.controls.MFXTextField();
    private final Label     searchStatus = new Label();
    private final FilterChip autoScroll = new FilterChip( LocalizationManager.get( "console.autoPin.label" ) );
    private final Label     truncated = new Label();
    private final Hyperlink openFileLink = new Hyperlink( LocalizationManager.get( "console.openLogLink.text" ) );
    private final MFXButton crashToggle = new MFXButton();
    private       GameLog   boundLog;
    private       Runnable  unsubscribeLog;
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
        statusChip.getStyleClass().addAll( "stat-chip", "sessionStatusChip" );
        uptime.getStyleClass().add( "muted" );
        header.getChildren().addAll( titles, spacer, uptime, statusChip );
        header.getStyleClass().add( "sessionHeader" );
        BorderPane.setMargin( header, new Insets( 0, 0, 12, 0 ) );

        // ---- Preparing: the launch steps ----
        Label preparing = new Label( LocalizationManager.get( "session.preparing.heading" ) );
        preparing.getStyleClass().add( "heading-h3" );
        cancelBtn.setOnAction( e -> session.cancel() );
        progressView = new VBox( 14, preparing, stepsBox, cancelBtn );
        progressView.getStyleClass().add( "sessionSteps" );
        progressView.setMaxHeight( javafx.scene.layout.Region.USE_PREF_SIZE );
        StackPane.setAlignment( progressView, Pos.TOP_LEFT );

        // ---- Running: the log ----
        diagnosisCard.getStyleClass().addAll( "card", "crashDiagnosisCard" );
        diagnosisCard.setVisible( false );
        diagnosisCard.setManaged( false );

        search.setPromptText( LocalizationManager.get( "console.search.placeholder" ) );
        search.setFloatMode( io.github.palexdev.materialfx.enums.FloatMode.DISABLED );
        search.getStyleClass().add( "no-floating-label" );
        search.setMinHeight( 40 );
        search.setMaxWidth( Double.MAX_VALUE );
        SearchFields.decorate( search );
        HBox.setHgrow( search, Priority.ALWAYS );
        search.setOnAction( e -> find( true ) );
        MFXButton prev = new MFXButton( LocalizationManager.get( "console.search.prev" ) );
        prev.setOnAction( e -> find( false ) );
        IconButtons.decorate( prev, LauncherIcons.ARROW_UP, LocalizationManager.get( "session.search.previousMatch" ) );
        MFXButton next = new MFXButton( LocalizationManager.get( "console.search.next" ) );
        next.setOnAction( e -> find( true ) );
        IconButtons.decorate( next, LauncherIcons.ARROW_DOWN, LocalizationManager.get( "session.search.nextMatch" ) );
        crashToggle.getStyleClass().add( "tonalBtn" );
        searchStatus.getStyleClass().add( "muted" );
        autoScroll.setSelected( true );
        crashToggle.setVisible( false );
        crashToggle.setManaged( false );
        crashToggle.setOnAction( e -> toggleCrashReport() );
        HBox toolbar = new HBox( 8, search, searchStatus, prev, next, autoScroll, crashToggle );
        toolbar.setAlignment( Pos.CENTER_LEFT );

        VBox.setVgrow( logArea.node(), Priority.ALWAYS );
        // Turning auto-scroll back on jumps to the newest line rather than waiting for the next.
        autoScroll.selectedProperty().addListener( ( obs, was, on ) -> {
            if ( on && !showingCrashReport ) {
                logArea.scrollToEnd();
            }
        } );

        truncated.getStyleClass().add( "subtle" );
        truncated.setVisible( false );
        truncated.setManaged( false );
        openFileLink.setVisible( false );
        openFileLink.setManaged( false );
        openFileLink.setOnAction( e -> openLogFile() );
        HBox notices = new HBox( 6, truncated, openFileLink );
        notices.setAlignment( Pos.CENTER_LEFT );

        logView = new VBox( 8, diagnosisCard, toolbar, logArea.node(), notices );

        center.getChildren().addAll( progressView, logView );

        // ---- Footer ----
        copyBtn.setOnAction( e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString( logArea.text() );
            Clipboard.getSystemClipboard().setContent( content );
        } );
        IconButtons.decorate( copyBtn, LauncherIcons.COPY, null );
        fileBtn.setOnAction( e -> openLogFile() );
        fileBtn.getStyleClass().add( "textBtn" );
        stopBtn.setOnAction( e -> session.stop( false ) );
        killBtn.getStyleClass().add( "errorTonalBtn" );
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
        // The tracker's step list is fixed, so its rows are built once here and only updated
        // afterwards: rebuilding them per event made fresh labels and an animated bar each time.
        stepsBox.getChildren().clear();
        stepRows.clear();
        if ( tracker != null ) {
            for ( LaunchProgressTracker.Step step : tracker.steps() ) {
                StepRow row = new StepRow( step );
                stepRows.put( step.id(), row );
                stepsBox.getChildren().add( row.container );
            }
            tracker.addListener( trackerListener );
        }
        refreshSteps();
    }

    /**
     * Coalesces a burst of tracker updates into one repaint. Called off the FX thread; only the
     * first event since the last repaint posts one, the rest are picked up by it.
     */
    private void queueStepsRefresh()
    {
        if ( stepsRefreshQueued.compareAndSet( false, true ) ) {
            Platform.runLater( () -> {
                stepsRefreshQueued.set( false );
                refreshSteps();
            } );
        }
    }

    private void refreshSteps()
    {
        if ( disposed || boundTracker == null ) {
            return;
        }
        for ( LaunchProgressTracker.Step step : boundTracker.steps() ) {
            StepRow row = stepRows.get( step.id() );
            if ( row != null ) {
                row.render( step );
            }
        }
    }

    /** One launch step's row: its badge, label, detail line and (while running) progress bar. */
    private static final class StepRow
    {
        final HBox            container;
        final StepBadge       icon;
        final Label           sub = new Label();
        final WavyProgressBar bar = new WavyProgressBar();

        StepRow( LaunchProgressTracker.Step step )
        {
            icon = new StepBadge( step.state() );
            Label label = new Label( step.displayLabel() );
            sub.getStyleClass().add( "muted" );
            sub.setWrapText( true );
            bar.setMaxWidth( Double.MAX_VALUE );
            VBox text = new VBox( 2, label, sub, bar );
            HBox.setHgrow( text, Priority.ALWAYS );
            container = new HBox( 10, icon, text );
            container.setAlignment( Pos.TOP_LEFT );
        }

        void render( LaunchProgressTracker.Step step )
        {
            icon.setState( step.state() );
            String detail = step.state() == LaunchProgressTracker.State.FAILED ? step.errorMessage() : step.subText();
            boolean hasDetail = detail != null && !detail.isBlank();
            sub.setText( hasDetail ? detail : "" );
            setShown( sub, hasDetail );
            boolean running = step.state() == LaunchProgressTracker.State.RUNNING;
            if ( running ) {
                bar.setProgress( step.progress() > 0 ? step.progress() : -1 );
            }
            setShown( bar, running );
        }
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
                // GameLog already batches lines (one call per flush), so each batch is one UI
                // update.
                Platform.runLater( () -> append( lines ) );
            }
        }, ConfigManager.getConsoleLogMaxLines() );
        unsubscribeLog = sub.cancel();
        showLogText( sub.snapshot(), sub.clipped() );
        // An ended log keeps only its tail in memory; the rest comes back from the file.
        if ( log.isClosed() && log.isTruncated() ) {
            loadFullLog( log );
        }
    }

    /**
     * Shows a log already cut to Settings' line limit. Cutting happens before the text gets here
     * (in {@link GameLog#subscribe(GameLog.Listener, int)}, {@link GameLog#tail(int)} or on a
     * worker): copying and scanning a multi-megabyte capture on the UI thread to keep a few
     * thousand lines stalled it.
     *
     * @param shown   the lines to show
     * @param clipped whether older lines were left out
     */
    private void showLogText( String shown, boolean clipped )
    {
        logArea.setText( shown );
        if ( clipped ) {
            showTruncated( ConfigManager.getConsoleLogMaxLines() );
        }
        if ( autoScroll.isSelected() ) {
            logArea.scrollToEnd();
        }
    }

    /**
     * Reads an ended log's full text from its file and cuts it to the line limit off the UI
     * thread, then shows it if the pane still shows that log.
     *
     * @param log the ended log
     */
    private void loadFullLog( GameLog log )
    {
        int maxLines = ConfigManager.getConsoleLogMaxLines();
        SystemUtilities.spawnNewTask( () -> {
            String full = log.fullText();
            int drop = LogTrimPolicy.tailStart( full, maxLines );
            String shown = drop > 0 ? full.substring( drop ) : full;
            Platform.runLater( () -> {
                if ( !disposed && boundLog == log && !showingCrashReport ) {
                    showLogText( shown, drop > 0 );
                }
            } );
        } );
    }

    private void append( List< String > lines )
    {
        if ( disposed || showingCrashReport ) {
            return;
        }
        int maxLines = ConfigManager.getConsoleLogMaxLines();
        // Past the limit's slack the oldest lines are dropped; the full log is in the file.
        if ( logArea.append( lines, maxLines, autoScroll.isSelected() ) > 0 ) {
            showTruncated( maxLines );
        }
    }

    /** Shows the "older entries are truncated" note, with the link to the full file. */
    private void showTruncated( int maxLines )
    {
        if ( !truncated.isVisible() ) {
            truncated.setText( LocalizationManager.format( "console.truncationLabel", maxLines ) );
            setShown( truncated, true );
            setShown( openFileLink, boundLog != null && boundLog.file() != null );
        }
    }

    private void find( boolean forward )
    {
        String needle = search.getText();
        if ( needle == null || needle.isEmpty() || logArea.lineCount() == 0 ) {
            searchStatus.setText( "" );
            return;
        }
        int[] position = logArea.find( needle, forward );
        if ( position == null ) {
            searchStatus.setText( LocalizationManager.get( "session.search.noMatches" ) );
            return;
        }
        searchStatus.setText( LocalizationManager.format( "session.search.matchCount", position[ 0 ], position[ 1 ] ) );
        autoScroll.setSelected( false );
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
        GameLog log = boundLog;
        // Reading the log (the full text may come from its file) and running every detector over
        // it can take a while on a big log, so both happen off the UI thread.
        SystemUtilities.spawnNewTask( () -> {
            String analyze = report != null ? report : ( log == null ? "" : log.fullText() );
            CrashDiagnosis diagnosis;
            try {
                diagnosis = CrashReportAnalyzer.analyze( analyze, session.pack(), session.exitCode() );
            }
            catch ( RuntimeException e ) {
                diagnosis = null;
            }
            CrashDiagnosis found = diagnosis;
            boolean logBlank = log == null || log.text().isBlank();
            Platform.runLater( () -> {
                if ( !disposed ) {
                    showDiagnosis( report, found, logBlank );
                }
            } );
        } );
    }

    /**
     * Shows the diagnosis card for an analyzed crash.
     *
     * @param report    the crash report, or {@code null} when the game left none
     * @param diagnosis what the analyzer made of it, or {@code null} when it failed
     * @param logBlank  whether the game logged nothing, so the crash report should open instead
     */
    private void showDiagnosis( String report, CrashDiagnosis diagnosis, boolean logBlank )
    {
        diagnosisCard.getChildren().clear();
        if ( diagnosis != null ) {
            Label t = new Label( diagnosis.title() );
            t.getStyleClass().addAll( "heading-h3", "crashDiagnosisTitle" );
            Label s = new Label( diagnosis.summary() );
            s.getStyleClass().add( "crashDiagnosisSummary" );
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
            if ( logBlank && !showingCrashReport ) {
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
            logArea.scrollToStart();
            crashToggle.setText( LocalizationManager.get( "console.crashReportBtn.gameLog" ) );
        }
        else {
            if ( boundLog != null ) {
                GameLog.Tail tail = boundLog.tail( ConfigManager.getConsoleLogMaxLines() );
                showLogText( tail.text(), tail.clipped() );
            }
            else {
                showLogText( "", false );
            }
            if ( boundLog != null && boundLog.isClosed() && boundLog.isTruncated() ) {
                loadFullLog( boundLog );
            }
            crashToggle.setText( LocalizationManager.get( "console.crashReportBtn.crashReport" ) );
        }
    }

    private static void setShown( javafx.scene.Node node, boolean shown )
    {
        node.setVisible( shown );
        node.setManaged( shown );
    }
}
