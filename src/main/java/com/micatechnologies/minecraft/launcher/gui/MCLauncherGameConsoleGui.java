/*
 * Copyright (c) 2026 Mica Technologies
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 */

package com.micatechnologies.minecraft.launcher.gui;

import com.micatechnologies.minecraft.launcher.LauncherCore;
import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.game.crash.CrashDiagnosis;
import com.micatechnologies.minecraft.launcher.game.crash.CrashReportAnalyzer;
import com.micatechnologies.minecraft.launcher.game.crash.Suggestion;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXTextField;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Controller for the in-game console GUI. Shows game log output in real time, provides a kill button, and
 * displays crash reports when the game exits abnormally.
 *
 * @author Mica Technologies
 * @version 3.0
 * @since 3.0
 */
public class MCLauncherGameConsoleGui extends MCLauncherAbstractGui
{
    private static final int FLUSH_INTERVAL_MS = 150;

    @SuppressWarnings( "unused" )
    @FXML
    Label titleLabel;

    @SuppressWarnings( "unused" )
    @FXML
    Label statusLabel;

    @SuppressWarnings( "unused" )
    @FXML
    Label uptimeLabel;

    @SuppressWarnings( "unused" )
    @FXML
    TextArea logArea;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton killBtn;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton closeBtn;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton copyBtn;

    @SuppressWarnings( "unused" )
    @FXML
    MFXButton crashReportBtn;

    @SuppressWarnings( "unused" )
    @FXML
    Label truncationLabel;

    @SuppressWarnings( "unused" )
    @FXML
    Hyperlink openLogLink;

    // ===== Crash diagnosis card =====
    @SuppressWarnings( "unused" ) @FXML VBox  diagnosisCard;
    @SuppressWarnings( "unused" ) @FXML Label diagnosisTitleLabel;
    @SuppressWarnings( "unused" ) @FXML Label diagnosisSummaryLabel;
    @SuppressWarnings( "unused" ) @FXML HBox  diagnosisActionsBox;

    @SuppressWarnings( "unused" ) @FXML Label helpBtn;

    // ===== Search / filter / auto-pin toolbar =====
    @SuppressWarnings( "unused" ) @FXML MFXTextField searchField;
    @SuppressWarnings( "unused" ) @FXML Label searchStatusLabel;
    @SuppressWarnings( "unused" ) @FXML MFXButton searchPrevBtn;
    @SuppressWarnings( "unused" ) @FXML MFXButton searchNextBtn;
    @SuppressWarnings( "unused" ) @FXML CheckBox autoPinCheckBox;

    private Process gameProcess;
    private long startTimeMs;
    private volatile boolean processRunning = false;
    private final StringBuilder fullLogContent = new StringBuilder();
    private String crashReportContent = null;
    private boolean showingCrashReport = false;
    private File logFile;
    private BufferedWriter logFileWriter;
    /** Guards all access to {@link #logFileWriter} — written from both stream
     *  readers (stdout + stderr) and flushed from the UI flush thread, so the
     *  non-thread-safe BufferedWriter needs serializing. */
    private final Object logFileLock = new Object();

    /** Hard cap on the in-memory captured-log buffer. A long modded session can
     *  emit hundreds of MB of log text; the complete log is already persisted to
     *  the per-session log file, so the in-memory copy only needs enough tail for
     *  the "switch back from crash view" display. Past the trigger the oldest
     *  content is dropped down to the retain size (trimmed to a line boundary). */
    private static final int FULL_LOG_TRIGGER_CHARS = 5_000_000;
    private static final int FULL_LOG_RETAIN_CHARS = 4_000_000;

    private final ConcurrentLinkedQueue< String > pendingLines = new ConcurrentLinkedQueue<>();
    private int displayLineCount = 0;
    private boolean truncated = false;

    /**
     * Callback interface for notifying when the game process exits.
     */
    @FunctionalInterface
    public interface GameExitCallback
    {
        void onGameExit( int exitCode );
    }

    private GameExitCallback exitCallback;

    public MCLauncherGameConsoleGui( Stage stage ) throws IOException {
        super( stage );
    }

    @Override
    String getSceneFxmlPath() {
        return "gui/gameConsoleGUI.fxml";
    }

    @Override
    String getSceneName() {
        return "Game Console";
    }

    @Override
    void setup() {
        stage.setOnCloseRequest( windowEvent -> {
            windowEvent.consume();
            if ( processRunning ) {
                int response = GUIUtilities.showQuestionMessage(
                        LocalizationManager.get( "dialog.gameConsole.stillRunning.title" ),
                        LocalizationManager.get( "dialog.gameConsole.stillRunning.header" ),
                        LocalizationManager.get( "dialog.gameConsole.stillRunning.body" ),
                        LocalizationManager.get( "dialog.gameConsole.stillRunning.button.stopAndClose" ),
                        LocalizationManager.get( "dialog.gameConsole.stillRunning.button.closeOnly" ),
                        stage );
                if ( response == 1 ) {
                    killGame();
                    closeLogFileWriter();
                    LauncherCore.closeApp();
                }
                else if ( response == 2 ) {
                    // Detach: close the launcher but leave the game running
                    closeLogFileWriter();
                    LauncherCore.closeApp();
                }
                // Cancel (0) -- do nothing
            }
            else {
                closeLogFileWriter();
                LauncherCore.closeApp();
            }
        } );

        killBtn.setOnAction( event -> SystemUtilities.spawnNewTask( this::killGame ) );

        closeBtn.setOnAction( event -> SystemUtilities.spawnNewTask( this::returnToMain ) );
        closeBtn.setDisable( true );

        copyBtn.setOnAction( event -> {
            String text = logArea.getText();
            if ( text != null && !text.isEmpty() ) {
                ClipboardContent content = new ClipboardContent();
                content.putString( text );
                Clipboard.getSystemClipboard().setContent( content );
                // Brief visual feedback
                String originalText = copyBtn.getText();
                copyBtn.setText( LocalizationManager.get( "console.copyBtn.success" ) );
                SystemUtilities.spawnNewTask( () -> {
                    try { Thread.sleep( 1500 ); } catch ( InterruptedException ignored ) {}
                    GUIUtilities.JFXPlatformRun( () -> copyBtn.setText( originalText ) );
                } );
            }
        } );

        crashReportBtn.setOnAction( event -> {
            if ( showingCrashReport ) {
                logArea.setText( getDisplayLog() );
                logArea.positionCaret( logArea.getText().length() );
                crashReportBtn.setText( LocalizationManager.get( "console.crashReportBtn.crashReport" ) );
                showingCrashReport = false;
            }
            else {
                if ( crashReportContent != null ) {
                    logArea.setText( crashReportContent );
                    logArea.positionCaret( 0 );
                    crashReportBtn.setText( LocalizationManager.get( "console.crashReportBtn.gameLog" ) );
                    showingCrashReport = true;
                }
            }
        } );

        // Initially hide truncation UI
        truncationLabel.setVisible( false );
        truncationLabel.setManaged( false );
        openLogLink.setVisible( false );
        openLogLink.setManaged( false );
        openLogLink.setOnAction( event -> openLogFileInEditor() );

        // Wire the navbar help button.
        if ( helpBtn != null ) {
            helpBtn.setOnMouseClicked( e -> MCLauncherHelpWindow.show( getHelpTopic() ) );
            helpBtn.setCursor( javafx.scene.Cursor.HAND );
            TooltipManager.install( helpBtn, "Open the help window for this screen." );
        }

        // Search bar — find-next on Enter or click, find-prev on the
        // left button. Highlights the matched substring via selectRange
        // and scrolls it into view. Empty query clears any highlight.
        if ( searchField != null ) {
            searchField.setOnAction( e -> findInLog( searchField.getText(), true ) );
            searchField.textProperty().addListener( ( obs, oldV, newV ) -> {
                if ( newV == null || newV.isEmpty() ) {
                    searchStatusLabel.setText( "" );
                    // Clear the highlight by collapsing the selection.
                    logArea.deselect();
                }
                else {
                    findInLog( newV, true );
                }
            } );
        }
        if ( searchPrevBtn != null ) {
            searchPrevBtn.setOnAction( e -> findInLog( searchField.getText(), false ) );
        }
        if ( searchNextBtn != null ) {
            searchNextBtn.setOnAction( e -> findInLog( searchField.getText(), true ) );
        }

        // Auto-pin: when ON (default), every appendToDisplay scrolls to
        // the latest line. When OFF, the caret stays where the user
        // last positioned it. Smart auto-disable: if the user scrolls
        // up or selects text mid-stream the auto-pin checkbox clicks
        // off so their reading flow isn't yanked back to the bottom.
        if ( autoPinCheckBox != null ) {
            logArea.addEventFilter( ScrollEvent.SCROLL, e -> {
                if ( e.getDeltaY() > 0 && autoPinCheckBox.isSelected() ) {
                    autoPinCheckBox.setSelected( false );
                }
            } );
            logArea.addEventFilter( MouseEvent.MOUSE_PRESSED, e -> {
                // Clicking inside the log area also implies "I'm
                // reading, don't yank me back" — same heuristic the
                // major terminal emulators use.
                if ( autoPinCheckBox.isSelected() ) {
                    autoPinCheckBox.setSelected( false );
                }
            } );
        }

        // Right-click → Copy Line context menu on the log area. The
        // built-in TextArea menu has Copy / Paste / etc. but no
        // line-grabber; users debugging crashes regularly want a
        // single mod-id or path off one line without dragging-to-select.
        ContextMenu logMenu = new ContextMenu();
        MenuItem copyLineItem = new MenuItem( LocalizationManager.get( "console.contextMenu.copyLine" ) );
        copyLineItem.setOnAction( e -> copyCurrentLineToClipboard() );
        MenuItem copyAllItem = new MenuItem( LocalizationManager.get( "console.contextMenu.copyAll" ) );
        copyAllItem.setOnAction( e -> copyAllToClipboard() );
        logMenu.getItems().addAll( copyLineItem, copyAllItem );
        logArea.setContextMenu( logMenu );
    }

    /** Searches the log TextArea for {@code needle} starting from the
     *  current caret position (forward search) or scanning backwards
     *  to find the previous occurrence (when {@code forward} is false).
     *  Wraps around at the end / start so repeated Enter presses cycle
     *  through all matches. */
    private void findInLog( String needle, boolean forward )
    {
        if ( logArea == null || needle == null || needle.isEmpty() ) return;
        String haystack = logArea.getText();
        if ( haystack == null || haystack.isEmpty() ) {
            searchStatusLabel.setText( LocalizationManager.get( "console.search.noMatch" ) );
            return;
        }
        String lowerHay = haystack.toLowerCase();
        String lowerNeedle = needle.toLowerCase();
        int caret = logArea.getCaretPosition();
        int idx;
        if ( forward ) {
            idx = lowerHay.indexOf( lowerNeedle, caret );
            if ( idx < 0 ) idx = lowerHay.indexOf( lowerNeedle );  // wrap
        }
        else {
            // Look BEFORE the current selection start so repeated
            // back-presses walk backwards through matches.
            int from = Math.max( 0, logArea.getSelection().getStart() - 1 );
            idx = lowerHay.lastIndexOf( lowerNeedle, from );
            if ( idx < 0 ) idx = lowerHay.lastIndexOf( lowerNeedle );  // wrap
        }
        if ( idx < 0 ) {
            searchStatusLabel.setText( LocalizationManager.get( "console.search.noMatch" ) );
            return;
        }
        logArea.selectRange( idx, idx + needle.length() );
        searchStatusLabel.setText( "" );
        // Disable auto-pin while the user is searching — being scrolled
        // away mid-search is hostile.
        if ( autoPinCheckBox != null && autoPinCheckBox.isSelected() ) {
            autoPinCheckBox.setSelected( false );
        }
    }

    /** Copies the line at the current caret position. Convenience
     *  beyond the built-in TextArea copy which only handles selection. */
    private void copyCurrentLineToClipboard()
    {
        String text = logArea.getText();
        if ( text == null || text.isEmpty() ) return;
        int caret = logArea.getCaretPosition();
        int lineStart = text.lastIndexOf( '\n', Math.max( 0, caret - 1 ) ) + 1;
        int lineEnd = text.indexOf( '\n', caret );
        if ( lineEnd < 0 ) lineEnd = text.length();
        String line = text.substring( lineStart, lineEnd );
        ClipboardContent content = new ClipboardContent();
        content.putString( line );
        Clipboard.getSystemClipboard().setContent( content );
    }

    private void copyAllToClipboard()
    {
        String text = logArea.getText();
        if ( text == null || text.isEmpty() ) return;
        ClipboardContent content = new ClipboardContent();
        content.putString( text );
        Clipboard.getSystemClipboard().setContent( content );
    }

    @Override
    void afterShow() {
    }

    @Override
    void cleanup() {
        processRunning = false;
        closeLogFileWriter();
    }

    @Override
    HelpTopic getHelpTopic() { return HelpTopic.GAME_CONSOLE; }

    /**
     * Attaches this console to a running game process and starts reading its output.
     *
     * @param process      the game Process
     * @param packName     the modpack name for the title
     * @param exitCallback optional callback when game exits
     */
    public void attachToProcess( Process process, String packName, GameExitCallback exitCallback ) {
        this.gameProcess = process;
        this.startTimeMs = System.currentTimeMillis();
        this.processRunning = true;
        this.exitCallback = exitCallback;
        this.fullLogContent.setLength( 0 );
        this.displayLineCount = 0;
        this.truncated = false;

        // Create log file for the full output
        initLogFile( packName );

        GUIUtilities.JFXPlatformRun( () -> {
            titleLabel.setText( LocalizationManager.format( "console.title.console", packName ) );
            statusLabel.setText( LocalizationManager.get( "console.status.running" ) );
            logArea.clear();
            truncationLabel.setVisible( false );
            truncationLabel.setManaged( false );
            openLogLink.setVisible( false );
            openLogLink.setManaged( false );
        } );

        // Start stdout reader thread
        SystemUtilities.spawnNewTask( () -> readStream( process.getInputStream() ) );

        // Start stderr reader thread
        SystemUtilities.spawnNewTask( () -> readStream( process.getErrorStream() ) );

        // Start the UI flush timer thread
        Thread flushThread = new Thread( () -> {
            while ( processRunning || !pendingLines.isEmpty() ) {
                try {
                    Thread.sleep( FLUSH_INTERVAL_MS );
                }
                catch ( InterruptedException ignored ) {
                    break;
                }
                flushPendingLines();
                flushLogFile();
            }
            // Final flush after process ends
            flushPendingLines();
            flushLogFile();
        } );
        flushThread.setDaemon( true );
        flushThread.start();

        // Start uptime counter thread
        Thread uptimeThread = new Thread( () -> {
            while ( processRunning ) {
                long elapsed = System.currentTimeMillis() - startTimeMs;
                long seconds = ( elapsed / 1000 ) % 60;
                long minutes = ( elapsed / 60000 ) % 60;
                long hours = elapsed / 3600000;
                String timeStr = hours > 0 ?
                                 String.format( "Uptime: %d:%02d:%02d", hours, minutes, seconds ) :
                                 String.format( "Uptime: %d:%02d", minutes, seconds );
                Platform.runLater( () -> uptimeLabel.setText( timeStr ) );
                try {
                    Thread.sleep( 1000 );
                }
                catch ( InterruptedException ignored ) {
                    break;
                }
            }
        } );
        uptimeThread.setDaemon( true );
        uptimeThread.start();

        // Start process monitor thread
        SystemUtilities.spawnNewTask( () -> {
            try {
                int exitCode = process.waitFor();
                processRunning = false;
                boolean crashed = exitCode != 0;

                // Build session duration string
                long elapsed = System.currentTimeMillis() - startTimeMs;
                long seconds = ( elapsed / 1000 ) % 60;
                long minutes = ( elapsed / 60000 ) % 60;
                long hours = elapsed / 3600000;
                String durationStr = hours > 0 ?
                                     String.format( "%dh %dm %ds", hours, minutes, seconds ) :
                                     minutes > 0 ?
                                     String.format( "%dm %ds", minutes, seconds ) :
                                     String.format( "%ds", seconds );

                // Wait briefly for final log lines to flush
                Thread.sleep( FLUSH_INTERVAL_MS * 2 );

                GUIUtilities.JFXPlatformRun( () -> {
                    killBtn.setDisable( true );
                    killBtn.setVisible( false );
                    killBtn.setManaged( false );
                    closeBtn.setDisable( false );
                    closeBtn.setText( LocalizationManager.get( "console.closeBtn.return" ) );

                    if ( crashed ) {
                        titleLabel.setText( LocalizationManager.get( "console.title.crashed" ) );
                        statusLabel.setText( LocalizationManager.format( "console.status.exitCode", exitCode ) );
                        appendToDisplay( "\n--- Game crashed with exit code " + exitCode +
                                                 " (session: " + durationStr + ") ---\n" );
                    }
                    else {
                        titleLabel.setText( LocalizationManager.get( "console.title.complete" ) );
                        statusLabel.setText( LocalizationManager.format( "console.status.playedFor", durationStr ) );
                        uptimeLabel.setText( "" );
                        appendToDisplay( "\n--- Game exited normally (session: " + durationStr + ") ---\n" );
                    }
                } );

                if ( exitCallback != null ) {
                    exitCallback.onGameExit( exitCode );
                }
            }
            catch ( InterruptedException e ) {
                Logger.logWarningSilent( "Game process monitor interrupted." );
            }
        } );
    }

    /**
     * Convenience overload without exit callback.
     */
    public void attachToProcess( Process process, String packName ) {
        attachToProcess( process, packName, null );
    }

    /**
     * Shows a crash report in the console. Can be called after the game has exited. The crash text
     * is also run through {@link CrashReportAnalyzer} and any non-UNKNOWN diagnosis is surfaced
     * in the panel above the log area. Use {@link #showCrashReport(String, GameModPack, int)}
     * to enable pack-specific suggestions (open mods folder, etc.); this overload uses a null
     * pack and exit-code 0 fallback.
     *
     * @param crashReport the crash report text content
     */
    public void showCrashReport( String crashReport ) {
        showCrashReport( crashReport, null, 0 );
    }

    /**
     * Shows a crash report and renders the pack-aware diagnosis. Used by the in-game-console
     * exit handler in {@link LauncherCore#play}.
     */
    public void showCrashReport( String crashReport, GameModPack pack, int exitCode ) {
        this.crashReportContent = crashReport;
        GUIUtilities.JFXPlatformRun( () -> {
            crashReportBtn.setVisible( true );
            crashReportBtn.setManaged( true );

            // Auto-switch to crash report view
            logArea.setText( crashReport );
            logArea.positionCaret( 0 );
            crashReportBtn.setText( LocalizationManager.get( "console.crashReportBtn.gameLog" ) );
            showingCrashReport = true;

            populateDiagnosisCard( CrashReportAnalyzer.analyze( crashReport, pack, exitCode ) );
        } );
    }

    /**
     * Shows this console in crash-report-only mode (no live process attached). Used when the in-game console
     * setting is disabled but a crash occurred.
     *
     * @param packName    the modpack name
     * @param exitCode    the exit code from the process
     * @param crashReport the crash report content (may be null)
     * @param gameLog     the captured game log (may be null)
     */
    public void showCrashOnly( String packName, int exitCode, String crashReport, String gameLog ) {
        showCrashOnly( packName, exitCode, crashReport, gameLog, null );
    }

    /**
     * Pack-aware crash-only mode. {@code pack} is used by {@link CrashReportAnalyzer} to build
     * suggestions that reference the pack's install folder. Pass {@code null} if the pack
     * reference isn't available; the analyzer falls back to non-pack-specific suggestions.
     */
    public void showCrashOnly( String packName, int exitCode, String crashReport, String gameLog,
                                GameModPack pack ) {
        GUIUtilities.JFXPlatformRun( () -> {
            titleLabel.setText( LocalizationManager.format( "console.title.crashedWithPack", packName ) );
            statusLabel.setText( LocalizationManager.format( "console.status.exitCode", exitCode ) );
            killBtn.setDisable( true );
            closeBtn.setDisable( false );
            uptimeLabel.setText( "" );

            // Diagnose from whichever text we have. Crash report is preferred (more structured);
            // game log is a noisy fallback that still has useful exception lines; with neither
            // we synthesize an "exit code N" message and the analyzer returns UNKNOWN.
            String analyzeText = crashReport != null ? crashReport
                                                     : ( gameLog != null ? gameLog : "" );
            populateDiagnosisCard( CrashReportAnalyzer.analyze( analyzeText, pack, exitCode ) );

            if ( crashReport != null ) {
                crashReportContent = crashReport;
                logArea.setText( crashReport );
                crashReportBtn.setVisible( true );
                crashReportBtn.setManaged( true );
                crashReportBtn.setText( LocalizationManager.get( "console.crashReportBtn.gameLog" ) );
                showingCrashReport = true;

                if ( gameLog != null ) {
                    fullLogContent.append( gameLog );
                }
            }
            else if ( gameLog != null ) {
                logArea.setText( gameLog );
                fullLogContent.append( gameLog );
            }
            else {
                logArea.setText( LocalizationManager.format( "console.log.crashedExitCodeNoReport", exitCode ) );
            }
        } );
    }

    // =========================================================================================
    //  Crash diagnosis card rendering
    // =========================================================================================

    /**
     * Builds the diagnosis panel above the log area from a {@link CrashDiagnosis}. Sets title,
     * summary, a styleClass that drives the panel color (severity), and one button per
     * {@link Suggestion} (primary suggestion gets the brand-blue primary button style; the
     * rest are tonal). Must be called on the FX thread.
     */
    private void populateDiagnosisCard( CrashDiagnosis diagnosis )
    {
        if ( diagnosis == null ) {
            diagnosisCard.setVisible( false );
            diagnosisCard.setManaged( false );
            return;
        }

        diagnosisCard.setVisible( true );
        diagnosisCard.setManaged( true );

        diagnosisTitleLabel.setText( diagnosis.title() );
        diagnosisSummaryLabel.setText( diagnosis.summary() );

        // Severity drives the card color via styleClass — defined in ui-base.css. We clear any
        // previous severity class first so re-populating the card on a new crash doesn't stack
        // colors. The "crashDiagnosisCard" base class is left alone.
        diagnosisCard.getStyleClass().removeAll( "crashSeverityCritical", "crashSeverityWarning",
                                                  "crashSeverityInfo" );
        diagnosisCard.getStyleClass().add( severityStyleClass( diagnosis.severity() ) );

        // Rebuild the action button row. Each Suggestion with an action becomes a button;
        // hint-only suggestions (action == null) become a muted Label.
        diagnosisActionsBox.getChildren().clear();
        for ( Suggestion suggestion : diagnosis.suggestions() ) {
            if ( suggestion.action() == null ) {
                Label hint = new Label( suggestion.label() );
                hint.getStyleClass().add( "muted" );
                diagnosisActionsBox.getChildren().add( hint );
                continue;
            }
            MFXButton btn = new MFXButton( suggestion.label() );
            btn.setPrefHeight( 30.0 );
            if ( suggestion.primary() ) {
                btn.getStyleClass().add( "primary" );
            }
            btn.setOnAction( e -> SystemUtilities.spawnNewTask( suggestion.action() ) );
            diagnosisActionsBox.getChildren().add( btn );
        }
    }

    private static String severityStyleClass( CrashDiagnosis.Severity severity )
    {
        return switch ( severity ) {
            case CRITICAL -> "crashSeverityCritical";
            case WARNING  -> "crashSeverityWarning";
            case INFO     -> "crashSeverityInfo";
        };
    }

    /**
     * Reads lines from the given stream and queues them for batched UI updates. This method never blocks on
     * the JavaFX thread, so the game process is never stalled by UI rendering.
     */
    private void readStream( InputStream inputStream ) {
        try ( BufferedReader reader = new BufferedReader( new InputStreamReader( inputStream ) ) ) {
            String line;
            while ( ( line = reader.readLine() ) != null ) {
                // Strip --accessToken / --clientToken / legacy session tokens from every
                // captured line before it lands in the in-memory buffer, the on-disk log
                // file, or the visible TextArea. Minecraft's own logger redacts in modern
                // versions, but the JVM startup banner and some mods echo argv verbatim,
                // and the in-game "Copy" button takes from logArea.getText() — which
                // would otherwise hand a forum-pasted log straight to anyone reading it.
                String safe = com.micatechnologies.minecraft.launcher.utilities.SensitiveDataRedactor
                        .redact( line );
                synchronized ( fullLogContent ) {
                    fullLogContent.append( safe ).append( '\n' );
                    // Bound the in-memory buffer — drop the oldest content past the
                    // trigger, trimmed to a line boundary. The full log lives in the
                    // session log file.
                    if ( fullLogContent.length() > FULL_LOG_TRIGGER_CHARS ) {
                        int dropTo = fullLogContent.length() - FULL_LOG_RETAIN_CHARS;
                        int nl = fullLogContent.indexOf( "\n", dropTo );
                        fullLogContent.delete( 0, nl >= 0 ? nl + 1 : dropTo );
                    }
                }
                writeToLogFile( safe );
                if ( !showingCrashReport ) {
                    pendingLines.add( safe );
                }
            }
        }
        catch ( IOException e ) {
            // Stream closed when process exits
        }
    }

    /**
     * Drains all pending log lines and pushes them to the TextArea in a single UI update.
     */
    private void flushPendingLines() {
        if ( pendingLines.isEmpty() ) {
            return;
        }

        // Drain all pending lines into a local list
        List< String > batch = new ArrayList<>();
        String line;
        while ( ( line = pendingLines.poll() ) != null ) {
            batch.add( line );
        }

        if ( batch.isEmpty() ) {
            return;
        }

        // Build the batch text
        StringBuilder batchText = new StringBuilder();
        for ( String l : batch ) {
            batchText.append( l ).append( '\n' );
        }
        int batchLineCount = batch.size();

        Platform.runLater( () -> {
            if ( showingCrashReport ) {
                return;
            }
            appendToDisplay( batchText.toString() );
            displayLineCount += batchLineCount;
            trimDisplayIfNeeded();
        } );
    }

    /**
     * Appends text to the log TextArea. Auto-pins to the bottom only
     * when {@link #autoPinCheckBox} is selected (the default) — when
     * the user has toggled it off (or scrolled up mid-stream),
     * appendText still adds the line but the caret stays put.
     * TextArea.appendText DOES move the caret natively so we restore
     * the caret + scroll position from the snapshot taken before the
     * append.
     */
    private void appendToDisplay( String text ) {
        boolean autoPin = autoPinCheckBox == null || autoPinCheckBox.isSelected();
        if ( autoPin ) {
            logArea.appendText( text );
            return;
        }
        int caret = logArea.getCaretPosition();
        int anchor = logArea.getAnchor();
        double scrollTop = logArea.getScrollTop();
        logArea.appendText( text );
        // Restore — appendText would otherwise jump the caret to the
        // end and the renderer would scroll to follow.
        logArea.positionCaret( caret );
        if ( anchor != caret ) {
            logArea.selectRange( anchor, caret );
        }
        logArea.setScrollTop( scrollTop );
    }

    /**
     * Trims the TextArea to the configured max-lines (Settings → Game
     * Console → Log buffer size) if it has grown too large, and shows
     * the truncation notice with a link to the full log file. 0 means
     * unlimited — the buffer grows without bound (use sparingly on
     * long sessions; JavaFX's TextArea is fine up to a few MB of text
     * but degrades past that).
     */
    private void trimDisplayIfNeeded() {
        int maxLines = ConfigManager.getConsoleLogMaxLines();
        if ( maxLines <= 0 ) {
            return;
        }
        // Trim with slack so the O(n) scan amortizes: only fire once we're ~20%
        // over the cap, then drop back to maxLines, rather than trimming a line
        // at a time on every 150 ms flush once steadily at the limit.
        int slack = Math.max( 50, maxLines / 5 );
        if ( displayLineCount <= maxLines + slack ) {
            return;
        }

        String current = logArea.getText();
        int linesToDrop = displayLineCount - maxLines;
        int idx = 0;
        for ( int i = 0; i < linesToDrop && idx < current.length(); i++ ) {
            int next = current.indexOf( '\n', idx );
            if ( next == -1 ) {
                break;
            }
            idx = next + 1;
        }

        if ( idx > 0 && idx <= current.length() ) {
            // deleteText avoids the getText()+substring()+setText() full copy (and
            // the undo/selection reset that setText forces).
            logArea.deleteText( 0, idx );
            displayLineCount = maxLines;
            // Only re-pin to the bottom when auto-pin is on. Otherwise
            // the user gets snapped down whenever a trim fires, which
            // contradicts the auto-pin-off intent.
            if ( autoPinCheckBox == null || autoPinCheckBox.isSelected() ) {
                logArea.positionCaret( logArea.getLength() );
            }
        }

        if ( !truncated ) {
            truncated = true;
            truncationLabel.setText( LocalizationManager.format( "console.truncationLabel", maxLines ) );
            truncationLabel.setVisible( true );
            truncationLabel.setManaged( true );
            if ( logFile != null ) {
                openLogLink.setText( LocalizationManager.get( "console.openLogLink.text" ) );
                openLogLink.setVisible( true );
                openLogLink.setManaged( true );
            }
        }
    }

    /**
     * Returns the current full log content as a String for display when switching from crash report view.
     */
    private String getDisplayLog() {
        synchronized ( fullLogContent ) {
            String full = fullLogContent.toString();
            // If truncated, return only the last configured-max-lines.
            // 0 means unlimited — return the entire buffer.
            int maxLines = ConfigManager.getConsoleLogMaxLines();
            if ( truncated && maxLines > 0 ) {
                int lineCount = 0;
                int idx = full.length();
                while ( idx > 0 && lineCount < maxLines ) {
                    idx = full.lastIndexOf( '\n', idx - 1 );
                    if ( idx == -1 ) {
                        idx = 0;
                        break;
                    }
                    lineCount++;
                }
                return full.substring( idx > 0 ? idx + 1 : 0 );
            }
            return full;
        }
    }

    private void initLogFile( String packName ) {
        try {
            String logDir = LocalPathManager.getLauncherLogFolderPath();
            File dir = SynchronizedFileManager.getSynchronizedFile( logDir );
            if ( !dir.exists() ) {
                dir.mkdirs();
            }
            String safeName = packName.replaceAll( "[^a-zA-Z0-9._-]", "_" );
            String timestamp = new java.text.SimpleDateFormat( "yyyy-MM-dd--HH-mm-ss" ).format( new java.util.Date() );
            logFile = new File( dir, "game-" + safeName + "-" + timestamp + ".log" );
            logFileWriter = new BufferedWriter(
                    new OutputStreamWriter( new FileOutputStream( logFile ), StandardCharsets.UTF_8 ) );
            // Game log files can contain mod debug output, user-name strings, and other
            // PII that shouldn't be readable to sibling user accounts on shared
            // workstations. Restrict to owner-only — best-effort, no-op on filesystems
            // that don't support either POSIX or ACL permissioning.
            com.micatechnologies.minecraft.launcher.utilities.FilePermissions.applyOwnerOnly(
                    logFile.toPath() );
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "Could not create game log file: " + e.getMessage() );
            logFile = null;
            logFileWriter = null;
        }
    }

    private void writeToLogFile( String line ) {
        synchronized ( logFileLock ) {
            if ( logFileWriter == null ) {
                return;
            }
            try {
                // No per-line flush — that turned the BufferedWriter into an
                // unbuffered one (a syscall per log line during heavy mod output).
                // The flush thread flushes periodically; close() flushes the tail.
                logFileWriter.write( line );
                logFileWriter.newLine();
            }
            catch ( IOException e ) {
                // Silently ignore file write errors to avoid impacting game performance
            }
        }
    }

    /** Periodically flushes the buffered log writer (called from the UI flush
     *  thread) so log lines reach disk within the flush interval without paying
     *  a syscall per line. */
    private void flushLogFile() {
        synchronized ( logFileLock ) {
            if ( logFileWriter == null ) {
                return;
            }
            try {
                logFileWriter.flush();
            }
            catch ( IOException e ) {
                // Best-effort — a failed flush still gets a final shot at close().
            }
        }
    }

    private void closeLogFileWriter() {
        synchronized ( logFileLock ) {
            if ( logFileWriter != null ) {
                try {
                    logFileWriter.close();
                }
                catch ( IOException ignored ) {
                }
                logFileWriter = null;
            }
        }
    }

    private void openLogFileInEditor() {
        if ( logFile != null && logFile.exists() ) {
            SystemUtilities.spawnNewTask( () -> {
                try {
                    Desktop.getDesktop().open( logFile );
                }
                catch ( IOException e ) {
                    Logger.logWarningSilent( "Could not open log file: " + e.getMessage() );
                }
            } );
        }
    }

    private void killGame() {
        if ( gameProcess != null && gameProcess.isAlive() ) {
            gameProcess.destroyForcibly();
            processRunning = false;
            GUIUtilities.JFXPlatformRun( () -> {
                statusLabel.setText( LocalizationManager.get( "console.status.killed" ) );
                killBtn.setDisable( true );
                closeBtn.setDisable( false );
                appendToDisplay( "\n--- Game was killed by user ---\n" );
            } );
        }
    }

    private void returnToMain() {
        try {
            MCLauncherGuiController.goToMainGui();
        }
        catch ( IOException e ) {
            Logger.logError( "Unable to return to main GUI." );
            Logger.logThrowable( e );
        }
    }
}
