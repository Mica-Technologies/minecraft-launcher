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
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.BorderLayout;
import com.googlecode.lanterna.gui2.DefaultWindowManager;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowListenerAdapter;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
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
 * Entry point + shell for the full-screen terminal UI (Lanterna). Renders a Library list of
 * installed modpacks and a live status bar (running count · total playtime · signed-in user) over
 * the real terminal captured in {@link TuiMode}. Launcher logging is routed to a file in TUI mode so
 * it can't corrupt the screen.
 *
 * <p>This is the foundation shell; launch/logs, install/browse, and settings views are layered on in
 * follow-up steps. {@link #run()} blocks (running the Lanterna event loop on the calling thread)
 * until the user quits with {@code q}, after which the launcher shuts down.</p>
 *
 * @since 2026.6
 */
public final class TuiApp
{
    private TuiApp() { /* static-only */ }

    private static volatile Label statusBar;
    private static volatile MultiWindowTextGUI gui;
    private static volatile ScheduledExecutorService refresher;

    /** Builds the screen and runs the event loop until the user quits. */
    public static void run() throws Exception
    {
        DefaultTerminalFactory factory =
                new DefaultTerminalFactory( TuiMode.realOut(), TuiMode.realIn(), StandardCharsets.UTF_8 );
        // Render inline in the invoking terminal rather than popping a Swing window. Lanterna's
        // default heuristic falls back to a SwingTerminalFrame on Windows when it can't positively
        // detect a console; force the native text terminal instead. If that genuinely can't attach
        // (e.g. output is piped / no real console), degrade to the windowed emulator so the TUI
        // still runs rather than crashing.
        factory.setForceTextTerminal( true );
        Terminal terminal;
        try {
            terminal = factory.createTerminal();
        }
        catch ( Throwable nativeFail ) {
            com.micatechnologies.minecraft.launcher.files.Logger.logWarningSilent(
                    "TUI: native terminal unavailable (" + nativeFail + "); using windowed emulator." );
            factory.setForceTextTerminal( false );
            terminal = factory.createTerminal();
        }
        Screen screen = new TerminalScreen( terminal );
        screen.startScreen();
        try {
            gui = new MultiWindowTextGUI( screen, new DefaultWindowManager(),
                                         new EmptySpace( TextColor.ANSI.BLACK ) );
            BasicWindow window = buildWindow();
            startStatusRefresh();
            gui.addWindowAndWait( window );
        }
        finally {
            stopStatusRefresh();
            screen.stopScreen();
        }
    }

    private static BasicWindow buildWindow()
    {
        BasicWindow window = new BasicWindow();
        window.setHints( List.of( Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS,
                                  Window.Hint.FIT_TERMINAL_WINDOW ) );

        Panel root = new Panel( new BorderLayout() );

        Label header = new Label( "  Mica Minecraft Launcher — Library      [↑↓] navigate   [q] quit" );
        header.addStyle( SGR.BOLD );
        root.addComponent( header, BorderLayout.Location.TOP );

        ActionListBox list = new ActionListBox();
        List< GameModPack > packs = GameModPackManager.getInstalledModPacks();
        if ( packs.isEmpty() ) {
            list.addItem( "(no modpacks installed — install some via the GUI or Browse later)",
                          () -> { } );
        }
        else {
            for ( GameModPack pack : packs ) {
                list.addItem( formatPackRow( pack ), () -> { /* details / launch land in step 2 */ } );
            }
        }
        root.addComponent( list.withBorder( Borders.singleLine( "Installed Packs" ) ),
                           BorderLayout.Location.CENTER );

        statusBar = new Label( statusLine() );
        root.addComponent( statusBar, BorderLayout.Location.BOTTOM );

        window.setComponent( root );
        window.addWindowListener( new WindowListenerAdapter()
        {
            @Override
            public void onUnhandledInput( Window basePane, KeyStroke key, AtomicBoolean handled )
            {
                if ( key.getKeyType() == KeyType.Character && key.getCharacter() != null
                        && ( key.getCharacter() == 'q' || key.getCharacter() == 'Q' ) ) {
                    window.close();
                    handled.set( true );
                }
            }
        } );
        return window;
    }

    /** One Library row: name, version, last-played, total playtime. */
    private static String formatPackRow( GameModPack pack )
    {
        String name = safe( pack::getFriendlyName, pack.getPackName() );
        String version = safe( pack::getPackVersion, "?" );
        String lastPlayed = safe( pack::getLastPlayedFormatted, "" );
        String playtime = formatDuration( pack.getTotalPlayTimeMs() );
        return String.format( "%-30s  v%-12s  last: %-12s  %s",
                              truncate( name, 30 ), truncate( version, 12 ), truncate( lastPlayed, 12 ),
                              playtime );
    }

    /** Bottom status line: running count · total playtime across all packs · signed-in user. */
    static String statusLine()
    {
        long totalMs = 0;
        for ( GameModPack pack : GameModPackManager.getInstalledModPacks() ) {
            totalMs += pack.getTotalPlayTimeMs();
        }
        var user = MCLauncherAuthManager.getLoggedInUser();
        String userName = ( user != null && user.name() != null ) ? user.name() : "(signed out)";
        // Running count is wired up with the launch flow in step 2; 0 for now.
        return "  0 running   ·   total playtime " + formatDuration( totalMs )
                + "   ·   " + userName;
    }

    private static void startStatusRefresh()
    {
        refresher = Executors.newSingleThreadScheduledExecutor( r -> {
            Thread t = new Thread( r, "tui-status-refresh" );
            t.setDaemon( true );
            return t;
        } );
        refresher.scheduleAtFixedRate( () -> {
            MultiWindowTextGUI g = gui;
            Label sb = statusBar;
            if ( g != null && sb != null ) {
                g.getGUIThread().invokeLater( () -> sb.setText( statusLine() ) );
            }
        }, 1, 1, TimeUnit.SECONDS );
    }

    private static void stopStatusRefresh()
    {
        ScheduledExecutorService r = refresher;
        if ( r != null ) {
            r.shutdownNow();
        }
    }

    // ---- small formatting helpers ----

    /** Formats a duration in ms as a compact "Xh Ym" / "Ym" / "<1m". */
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

    /** Calls a possibly-throwing getter, returning a fallback on any failure. */
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
