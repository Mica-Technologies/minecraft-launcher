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
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.utilities.GUIUtilities;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import io.github.palexdev.materialfx.controls.MFXButton;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
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
    private static final int MAX_DISPLAY_LINES = 10_000;
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
    MFXButton crashReportBtn;

    @SuppressWarnings( "unused" )
    @FXML
    Label truncationLabel;

    @SuppressWarnings( "unused" )
    @FXML
    Hyperlink openLogLink;

    private Process gameProcess;
    private long startTimeMs;
    private volatile boolean processRunning = false;
    private final StringBuilder fullLogContent = new StringBuilder();
    private String crashReportContent = null;
    private boolean showingCrashReport = false;
    private File logFile;
    private BufferedWriter logFileWriter;

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
                int response = GUIUtilities.showQuestionMessage( "Game Running",
                                                                  "The game is still running",
                                                                  "How would you like to close?",
                                                                  "Stop Game & Close",
                                                                  "Close Launcher Only", stage );
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

        crashReportBtn.setOnAction( event -> {
            if ( showingCrashReport ) {
                logArea.setText( getDisplayLog() );
                logArea.positionCaret( logArea.getText().length() );
                crashReportBtn.setText( "Crash Report" );
                showingCrashReport = false;
            }
            else {
                if ( crashReportContent != null ) {
                    logArea.setText( crashReportContent );
                    logArea.positionCaret( 0 );
                    crashReportBtn.setText( "Game Log" );
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
    }

    @Override
    void afterShow() {
    }

    @Override
    void cleanup() {
        processRunning = false;
        closeLogFileWriter();
    }

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
            titleLabel.setText( "Game Console: " + packName );
            statusLabel.setText( "Running" );
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
            }
            // Final flush after process ends
            flushPendingLines();
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
                    closeBtn.setText( "Return to Launcher" );

                    if ( crashed ) {
                        titleLabel.setText( "Game Crashed" );
                        statusLabel.setText( "Exit code " + exitCode );
                        appendToDisplay( "\n--- Game crashed with exit code " + exitCode +
                                                 " (session: " + durationStr + ") ---\n" );
                    }
                    else {
                        titleLabel.setText( "Game Session Complete" );
                        statusLabel.setText( "Played for " + durationStr );
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
     * Shows a crash report in the console. Can be called after the game has exited.
     *
     * @param crashReport the crash report text content
     */
    public void showCrashReport( String crashReport ) {
        this.crashReportContent = crashReport;
        GUIUtilities.JFXPlatformRun( () -> {
            crashReportBtn.setVisible( true );
            crashReportBtn.setManaged( true );

            // Auto-switch to crash report view
            logArea.setText( crashReport );
            logArea.positionCaret( 0 );
            crashReportBtn.setText( "Game Log" );
            showingCrashReport = true;
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
        GUIUtilities.JFXPlatformRun( () -> {
            titleLabel.setText( "Game Crashed: " + packName );
            statusLabel.setText( "Exit code " + exitCode );
            killBtn.setDisable( true );
            closeBtn.setDisable( false );
            uptimeLabel.setText( "" );

            if ( crashReport != null ) {
                crashReportContent = crashReport;
                logArea.setText( crashReport );
                crashReportBtn.setVisible( true );
                crashReportBtn.setManaged( true );
                crashReportBtn.setText( "Game Log" );
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
                logArea.setText( "Game crashed with exit code " + exitCode +
                                         ".\nNo crash report was generated." );
            }
        } );
    }

    /**
     * Reads lines from the given stream and queues them for batched UI updates. This method never blocks on
     * the JavaFX thread, so the game process is never stalled by UI rendering.
     */
    private void readStream( InputStream inputStream ) {
        try ( BufferedReader reader = new BufferedReader( new InputStreamReader( inputStream ) ) ) {
            String line;
            while ( ( line = reader.readLine() ) != null ) {
                synchronized ( fullLogContent ) {
                    fullLogContent.append( line ).append( '\n' );
                }
                writeToLogFile( line );
                if ( !showingCrashReport ) {
                    pendingLines.add( line );
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
     * Appends text to the log TextArea.
     */
    private void appendToDisplay( String text ) {
        logArea.appendText( text );
    }

    /**
     * Trims the TextArea to the last {@link #MAX_DISPLAY_LINES} lines if it has grown too large,
     * and shows the truncation notice with a link to the full log file.
     */
    private void trimDisplayIfNeeded() {
        if ( displayLineCount <= MAX_DISPLAY_LINES ) {
            return;
        }

        String current = logArea.getText();
        // Find the start position that keeps only the last MAX_DISPLAY_LINES lines
        int linesToDrop = displayLineCount - MAX_DISPLAY_LINES;
        int idx = 0;
        for ( int i = 0; i < linesToDrop && idx < current.length(); i++ ) {
            int next = current.indexOf( '\n', idx );
            if ( next == -1 ) {
                break;
            }
            idx = next + 1;
        }

        if ( idx > 0 && idx < current.length() ) {
            logArea.setText( current.substring( idx ) );
            displayLineCount = MAX_DISPLAY_LINES;
            logArea.positionCaret( logArea.getText().length() );
        }

        if ( !truncated ) {
            truncated = true;
            truncationLabel.setText( "Log exceeds " + MAX_DISPLAY_LINES +
                                             " lines. Older entries are truncated." );
            truncationLabel.setVisible( true );
            truncationLabel.setManaged( true );
            if ( logFile != null ) {
                openLogLink.setText( "Open full log file" );
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
            // If truncated, return only the last MAX_DISPLAY_LINES lines
            if ( truncated ) {
                int lineCount = 0;
                int idx = full.length();
                while ( idx > 0 && lineCount < MAX_DISPLAY_LINES ) {
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
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "Could not create game log file: " + e.getMessage() );
            logFile = null;
            logFileWriter = null;
        }
    }

    private void writeToLogFile( String line ) {
        if ( logFileWriter == null ) {
            return;
        }
        try {
            logFileWriter.write( line );
            logFileWriter.newLine();
            logFileWriter.flush();
        }
        catch ( IOException e ) {
            // Silently ignore file write errors to avoid impacting game performance
        }
    }

    private void closeLogFileWriter() {
        if ( logFileWriter != null ) {
            try {
                logFileWriter.close();
            }
            catch ( IOException ignored ) {
            }
            logFileWriter = null;
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
                statusLabel.setText( "Killed" );
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
