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

package com.micatechnologies.minecraft.launcher.tui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.BorderLayout;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.DefaultWindowManager;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowListenerAdapter;
import com.googlecode.lanterna.gui2.dialogs.ActionListDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point + shell for the full-screen terminal UI (Lanterna): a Library view of installed packs
 * (launch / view logs / stop), a Logs view tailing a running game's output, and a persistent status
 * bar (running count · live total playtime · signed-in user). Renders over the real terminal
 * captured in {@link TuiMode}; launcher logging is file-only in TUI mode so it can't corrupt the
 * screen.
 *
 * <p>{@link #run()} blocks (running the Lanterna event loop on the calling thread) until the user
 * quits with {@code q}; games keep running as detached child processes.</p>
 *
 * @since 2026.6
 */
public final class TuiApp
{
    private MultiWindowTextGUI gui;
    private BasicWindow window;
    private Panel content;          // swappable CENTER region
    private Label statusBar;
    private ScheduledExecutorService refresher;

    private enum View { LIBRARY, LOGS }
    private View currentView = View.LIBRARY;
    private GameModPack logsTarget;  // pack whose logs the Logs view is showing
    private TextBox logsBox;         // the read-only log display in the Logs view

    private TuiApp() { /* via run() */ }

    /** Builds the screen and runs the event loop until the user quits. */
    public static void run() throws Exception
    {
        new TuiApp().start();
    }

    private void start() throws Exception
    {
        DefaultTerminalFactory factory =
                new DefaultTerminalFactory( TuiMode.realOut(), TuiMode.realIn(), StandardCharsets.UTF_8 );
        // Render inline in the invoking terminal (macOS/Linux UnixTerminal). On Windows the native
        // console currently throws, so degrade to the windowed emulator rather than crashing.
        factory.setForceTextTerminal( true );
        Terminal terminal;
        try {
            terminal = factory.createTerminal();
        }
        catch ( Throwable nativeFail ) {
            Logger.logWarningSilent( "TUI: native terminal unavailable (" + nativeFail
                                             + "); using windowed emulator." );
            factory.setForceTextTerminal( false );
            terminal = factory.createTerminal();
        }
        Screen screen = new TerminalScreen( terminal );
        screen.startScreen();
        try {
            gui = new MultiWindowTextGUI( screen, new DefaultWindowManager(),
                                         new EmptySpace( TextColor.ANSI.BLACK ) );
            buildWindow();
            startRefresh();
            gui.addWindowAndWait( window );
        }
        finally {
            stopRefresh();
            screen.stopScreen();
        }
    }

    private void buildWindow()
    {
        window = new BasicWindow();
        window.setHints( List.of( Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS,
                                  Window.Hint.FIT_TERMINAL_WINDOW ) );

        Panel root = new Panel( new BorderLayout() );

        Label header = new Label(
                "  Mica Minecraft Launcher      [L] Library   [G] Logs      [Enter] actions   [q] Quit" );
        header.addStyle( SGR.BOLD );
        root.addComponent( header, BorderLayout.Location.TOP );

        content = new Panel( new BorderLayout() );
        root.addComponent( content, BorderLayout.Location.CENTER );

        statusBar = new Label( statusLine() );
        root.addComponent( statusBar, BorderLayout.Location.BOTTOM );

        window.setComponent( root );
        window.addWindowListener( new WindowListenerAdapter()
        {
            @Override
            public void onUnhandledInput( Window base, KeyStroke key, AtomicBoolean handled )
            {
                if ( key.getKeyType() != KeyType.Character || key.getCharacter() == null ) {
                    return;
                }
                char c = Character.toLowerCase( key.getCharacter() );
                if ( c == 'q' ) {
                    window.close();
                    handled.set( true );
                }
                else if ( c == 'l' ) {
                    showLibrary();
                    handled.set( true );
                }
                else if ( c == 'g' ) {
                    showLogs();
                    handled.set( true );
                }
            }
        } );

        showLibrary();
    }

    // ================================================================= Library

    private void showLibrary()
    {
        currentView = View.LIBRARY;
        content.removeAllComponents();

        ActionListBox list = new ActionListBox();
        List< GameModPack > packs = GameModPackManager.getInstalledModPacks();
        if ( packs.isEmpty() ) {
            list.addItem( "(no modpacks installed — Browse/Install lands in a later step)", () -> { } );
        }
        else {
            for ( GameModPack pack : packs ) {
                list.addItem( formatPackRow( pack ), () -> packActions( pack ) );
            }
        }
        content.addComponent( list.withBorder( Borders.singleLine( "Installed Packs" ) ),
                              BorderLayout.Location.CENTER );
        window.invalidate();
    }

    /** Per-pack action menu (launch / view logs / stop). */
    private void packActions( GameModPack pack )
    {
        boolean running = TuiRuntime.isRunning( pack );
        ActionListDialogBuilder b = new ActionListDialogBuilder()
                .setTitle( pack.getFriendlyName() )
                .setDescription( running ? "This pack is running." : "Choose an action" );
        if ( !running ) {
            b.addAction( "Launch", () -> launch( pack ) );
        }
        else {
            b.addAction( "View logs", () -> { logsTarget = pack; showLogs(); } );
            b.addAction( "Stop game", () -> {
                RunningGame g = TuiRuntime.runningFor( pack );
                if ( g != null ) {
                    g.stop();
                }
            } );
        }
        b.addAction( "Close", () -> { } );
        b.build().showDialog( gui );
    }

    private void launch( GameModPack pack )
    {
        if ( TuiRuntime.isRunning( pack ) ) {
            return;
        }
        BasicWindow progress = new BasicWindow( " Launching " + pack.getFriendlyName() + " " );
        progress.setHints( List.of( Window.Hint.CENTERED ) );
        Panel pp = new Panel( new LinearLayout( Direction.VERTICAL ) );
        Label section = new Label( "Preparing…" );
        Label detail = new Label( "" );
        Label pct = new Label( "0%" );
        pp.addComponent( section );
        pp.addComponent( detail );
        pp.addComponent( pct );
        progress.setComponent( pp.withBorder( Borders.singleLine() ) );
        gui.addWindow( progress );

        TuiProgressProvider provider = new TuiProgressProvider( ( percent, sec, det, status ) ->
                gui.getGUIThread().invokeLater( () -> {
                    section.setText( sec == null ? "" : sec );
                    String d = det == null ? "" : det;
                    if ( status != null && !status.isBlank() ) {
                        d = d.isEmpty() ? status : d + "   " + status;
                    }
                    detail.setText( d );
                    pct.setText( (int) Math.max( 0, Math.min( 100, percent ) ) + "%" );
                } ) );
        pack.setProgressProvider( provider );

        Thread launcher = new Thread( () -> {
            try {
                pack.startGame();
                Process proc = pack.getLastLaunchedProcess();
                if ( proc == null ) {
                    throw new IllegalStateException( "No game process was started." );
                }
                RunningGame game = new RunningGame( pack, proc );
                TuiRuntime.register( game, () -> gui.getGUIThread().invokeLater( this::onGamesChanged ) );
                gui.getGUIThread().invokeLater( () -> {
                    progress.close();
                    onGamesChanged();
                } );
            }
            catch ( Throwable t ) {
                Logger.logThrowable( t );
                gui.getGUIThread().invokeLater( () -> {
                    progress.close();
                    MessageDialog.showMessageDialog( gui, "Launch failed",
                                                     String.valueOf( t.getMessage() ),
                                                     MessageDialogButton.OK );
                } );
            }
        }, "tui-launch-" + pack.getPackSanitizedName() );
        launcher.setDaemon( true );
        launcher.start();
    }

    // ==================================================================== Logs

    private void showLogs()
    {
        currentView = View.LOGS;
        content.removeAllComponents();

        java.util.Collection< RunningGame > running = TuiRuntime.running();
        if ( running.isEmpty() ) {
            content.addComponent( new Label( "  No running games. Launch one from the Library (L)." )
                                          .withBorder( Borders.singleLine( "Logs" ) ),
                                  BorderLayout.Location.CENTER );
            logsBox = null;
            window.invalidate();
            return;
        }
        // Default the target to the first running game if none/stale selected.
        if ( logsTarget == null || !TuiRuntime.isRunning( logsTarget ) ) {
            logsTarget = running.iterator().next().pack();
        }

        Panel split = new Panel( new BorderLayout() );

        // Left: pick which running game to tail.
        ActionListBox picker = new ActionListBox( new TerminalSize( 26, 10 ) );
        for ( RunningGame g : running ) {
            GameModPack p = g.pack();
            picker.addItem( ( TuiRuntime.isRunning( p ) ? "● " : "  " ) + truncate( p.getFriendlyName(), 22 ),
                            () -> { logsTarget = p; refreshLogsBox(); } );
        }
        split.addComponent( picker.withBorder( Borders.singleLine( "Running" ) ),
                            BorderLayout.Location.LEFT );

        // Right: the tailed log.
        logsBox = new TextBox( new TerminalSize( 80, 20 ), TextBox.Style.MULTI_LINE );
        logsBox.setReadOnly( true );
        split.addComponent( logsBox.withBorder( Borders.singleLine( "Game Log" ) ),
                            BorderLayout.Location.CENTER );

        content.addComponent( split, BorderLayout.Location.CENTER );
        refreshLogsBox();
        window.invalidate();
    }

    /** Pushes the current logs target's buffered output into the log TextBox. */
    private void refreshLogsBox()
    {
        TextBox box = logsBox;
        GameModPack target = logsTarget;
        if ( box == null || target == null ) {
            return;
        }
        RunningGame g = TuiRuntime.runningFor( target );
        String text;
        if ( g == null ) {
            text = "(game exited)";
        }
        else {
            List< String > lines = g.logSnapshot();
            // Keep the last ~500 lines to avoid an enormous TextBox.
            int from = Math.max( 0, lines.size() - 500 );
            text = String.join( "\n", lines.subList( from, lines.size() ) );
        }
        box.setText( text );
        // Scroll to the bottom (newest output).
        box.setCaretPosition( box.getLineCount(), 0 );
    }

    // ================================================================== shared

    /** Called when a game starts or exits: refresh the visible view + the status bar. */
    private void onGamesChanged()
    {
        if ( currentView == View.LIBRARY ) {
            showLibrary();
        }
        else {
            showLogs();
        }
        statusBar.setText( statusLine() );
    }

    private void startRefresh()
    {
        refresher = Executors.newSingleThreadScheduledExecutor( r -> {
            Thread t = new Thread( r, "tui-refresh" );
            t.setDaemon( true );
            return t;
        } );
        refresher.scheduleAtFixedRate( () -> {
            MultiWindowTextGUI g = gui;
            if ( g == null ) {
                return;
            }
            g.getGUIThread().invokeLater( () -> {
                if ( statusBar != null ) {
                    statusBar.setText( statusLine() );
                }
                if ( currentView == View.LOGS ) {
                    refreshLogsBox();
                }
            } );
        }, 1, 1, TimeUnit.SECONDS );
    }

    private void stopRefresh()
    {
        ScheduledExecutorService r = refresher;
        if ( r != null ) {
            r.shutdownNow();
        }
    }

    // ---- formatting ----

    private static String formatPackRow( GameModPack pack )
    {
        String marker = TuiRuntime.isRunning( pack ) ? "● " : "  ";
        String name = safe( pack::getFriendlyName, pack.getPackName() );
        String version = safe( pack::getPackVersion, "?" );
        String lastPlayed = safe( pack::getLastPlayedFormatted, "" );
        String playtime = formatDuration( pack.getTotalPlayTimeMs() );
        return String.format( "%s%-28s  v%-12s  last: %-12s  %s",
                              marker, truncate( name, 28 ), truncate( version, 12 ),
                              truncate( lastPlayed, 12 ), playtime );
    }

    static String statusLine()
    {
        int running = TuiRuntime.runningCount();
        long totalMs = TuiRuntime.liveTotalPlaytimeMs();
        var user = MCLauncherAuthManager.getLoggedInUser();
        String userName = ( user != null && user.name() != null ) ? user.name() : "(signed out)";
        return "  " + running + " running   ·   total playtime " + formatDuration( totalMs )
                + "   ·   " + userName;
    }

    static String formatDuration( long ms )
    {
        if ( ms <= 0 ) {
            return "0m";
        }
        long minutes = ms / 60_000;
        long hours = minutes / 60;
        minutes %= 60;
        if ( hours > 0 ) {
            return hours + "h " + minutes + "m";
        }
        if ( minutes > 0 ) {
            return minutes + "m";
        }
        return "<1m";
    }

    private static String truncate( String s, int max )
    {
        if ( s == null ) {
            return "";
        }
        return s.length() <= max ? s : s.substring( 0, Math.max( 0, max - 1 ) ) + "…";
    }

    private static String safe( ThrowingSupplier getter, String fallback )
    {
        try {
            String v = getter.get();
            return v == null ? fallback : v;
        }
        catch ( Throwable t ) {
            return fallback;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier
    {
        String get() throws Exception;
    }
}
