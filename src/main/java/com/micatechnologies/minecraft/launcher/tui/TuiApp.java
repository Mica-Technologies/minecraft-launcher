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
import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
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
    private Screen screen;          // backing screen, read for the current terminal width
    private BasicWindow window;
    private Panel content;          // swappable CENTER region
    private Label headerLabel;      // top nav bar (re-rendered on language change / resize)
    private Label statusBar;
    private ScheduledExecutorService refresher;

    private enum View { LIBRARY, BROWSE, LOGS, SETTINGS }
    private View currentView = View.LIBRARY;
    private GameModPack logsTarget;  // pack whose logs the Logs view is showing
    private TextBox logsBox;         // the read-only log display in the Logs view
    private int     lastLogSize = -1; // last-rendered log line count, so we only refresh on new output
    private ActionListBox settingsList; // current Settings list, so a rebuild can keep the selection

    private TuiApp() { /* via run() */ }

    /** Localized string lookup shorthands. */
    private static String loc( String key ) { return LocalizationManager.get( key ); }
    private static String locf( String key, Object... args ) { return LocalizationManager.format( key, args ); }

    /** Builds the screen and runs the event loop until the user quits. */
    public static void run() throws Exception
    {
        new TuiApp().start();
    }

    /**
     * Initializes the terminal screen and Lanterna GUI, builds the main window,
     * and runs the TUI event loop until exit.
     *
     * @throws Exception if the terminal screen or GUI cannot be initialized
     */
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
            Logger.logWarningSilent( locf( "log.tui.nativeTerminalUnavailable", String.valueOf( nativeFail ) ) );
            // Tell the user on the real console why a separate window is opening instead of
            // rendering inline — otherwise the Swing fallback looks like a mystery (this happens
            // under IDE run consoles and anywhere there's no controlling /dev/tty for stty).
            TuiMode.realOut().println( loc( "tui.fallback.windowed" ) );
            factory.setForceTextTerminal( false );
            terminal = factory.createTerminal();
        }
        screen = new TerminalScreen( terminal );
        screen.startScreen();
        try {
            gui = new MultiWindowTextGUI( screen, new DefaultWindowManager(),
                                         new EmptySpace( TuiTheme.background() ) );
            gui.setTheme( TuiTheme.build() );
            buildWindow();
            startRefresh();
            runEventLoop();
        }
        finally {
            stopRefresh();
            screen.stopScreen();
        }
    }

    /** Drives the Lanterna event loop manually (instead of {@code addWindowAndWait}) so a stray
     *  exception from one component's input handling is logged and swallowed rather than tearing
     *  down the whole terminal UI. Runs until the main window closes or the terminal goes away. */
    private void runEventLoop()
    {
        gui.addWindow( window );
        com.googlecode.lanterna.gui2.TextGUIThread guiThread = gui.getGUIThread();
        while ( gui.getWindows().contains( window ) ) {
            try {
                guiThread.processEventsAndUpdate();
            }
            catch ( java.io.EOFException terminalClosed ) {
                break;
            }
            catch ( java.io.IOException io ) {
                Logger.logWarningSilent( locf( "log.tui.terminalIoError", io.getMessage() ) );
                break;
            }
            catch ( Throwable t ) {
                // A component blew up on some input/update — keep the UI alive.
                Logger.logThrowable( t );
            }
            try {
                // Poll cadence for the manual event loop. 20 ms (~50 Hz) keeps key/scroll response
                // imperceptibly snappy while avoiding the ~200 Hz idle spin a 5 ms sleep caused —
                // noticeably easier on laptop battery when the TUI is just sitting open.
                Thread.sleep( 20 );
            }
            catch ( InterruptedException ie ) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Builds the main TUI window: header bar, content panel, and key/resize
     * listeners.
     */
    private void buildWindow()
    {
        window = new BasicWindow();
        window.setHints( List.of( Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS,
                                  Window.Hint.FIT_TERMINAL_WINDOW ) );

        Panel root = new Panel( new BorderLayout() );

        headerLabel = new Label( headerText() );
        headerLabel.addStyle( SGR.BOLD );
        root.addComponent( headerLabel, BorderLayout.Location.TOP );

        content = new Panel( new BorderLayout() );
        root.addComponent( content, BorderLayout.Location.CENTER );

        statusBar = new Label( statusLine() );
        root.addComponent( statusBar, BorderLayout.Location.BOTTOM );

        window.setComponent( root );
        window.addWindowListener( new WindowListenerAdapter()
        {
            /**
             * Global key handler for the main window; intercepts navigation/quit keys
             * before they reach focused components.
             *
             * @param base         the window receiving the input
             * @param key          the key stroke
             * @param deliverEvent  set to {@code false} to consume the key
             */
            @Override
            public void onInput( Window base, KeyStroke key, AtomicBoolean deliverEvent )
            {
                // Handle the global navigation keys BEFORE the focused component sees them.
                // The focused ActionListBox does first-letter item navigation, so handling these
                // in onUnhandledInput let it swallow the key first — e.g. pressing 'L' in Settings
                // jumped to the "Language" row instead of returning to the Library. Consuming the
                // key here (deliverEvent=false) keeps the shortcuts working from every view.
                if ( handleGlobalKey( key ) ) {
                    deliverEvent.set( false );
                }
            }

            /**
             * Re-renders size-dependent content (e.g. the header) when the terminal is
             * resized.
             *
             * @param base    the window being resized
             * @param oldSize the previous terminal size
             * @param newSize the new terminal size
             */
            @Override
            public void onResized( Window base, TerminalSize oldSize, TerminalSize newSize )
            {
                // Re-pick the header density for the new width so hints never truncate mid-word.
                if ( headerLabel != null && newSize != null ) {
                    headerLabel.setText( headerText( newSize.getColumns() ) );
                }
            }
        } );

        showLibrary();
    }

    /**
     * Global navigation shortcuts, intercepted before the focused component so list views can't
     * swallow them. {@code q} quits; {@code L/B/G/S} switch views; {@code n} cycles the Logs target;
     * {@code Esc} backs out of any sub-view to the Library (the TUI's home — on the Library itself
     * Esc does nothing, so it can't be an accidental quit).
     *
     * @return {@code true} if the key was a global shortcut and should not reach the focused component
     */
    private boolean handleGlobalKey( KeyStroke key )
    {
        if ( key.getKeyType() == KeyType.Escape ) {
            if ( currentView != View.LIBRARY ) {
                showLibrary();
                return true;
            }
            return false;
        }
        if ( key.getKeyType() != KeyType.Character || key.getCharacter() == null ) {
            return false;
        }
        switch ( Character.toLowerCase( key.getCharacter() ) ) {
            case 'q' -> { window.close();  return true; }
            case 'l' -> { showLibrary();   return true; }
            case 'b' -> { showBrowse();    return true; }
            case 'g' -> { showLogs();      return true; }
            case 's' -> { showSettings();  return true; }
            case 'n' -> {
                if ( currentView == View.LOGS ) {
                    cycleLogsTarget();
                    return true;
                }
                return false;
            }
            default -> { return false; }
        }
    }

    /**
     * Builds the top nav-bar header string (app name + current screen/context).
     *
     * @return the header text for the current state
     */
    private String headerText()
    {
        return headerText( currentColumns() );
    }

    /** Builds the top nav bar, degrading gracefully as the terminal narrows so the shortcuts never
     *  get truncated mid-hint: full (with app name + Enter hint) → drop those → letters only →
     *  bare minimum. */
    private String headerText( int cols )
    {
        String views = "[L] " + loc( "tui.view.library" ) + "  [B] " + loc( "tui.view.browse" )
                + "  [G] " + loc( "tui.view.logs" ) + "  [S] " + loc( "tui.view.settings" );
        String back = "[Esc] " + loc( "tui.nav.back" );
        String quit = "[q] " + loc( "tui.nav.quit" );

        String full = "  " + loc( "tui.appName" ) + "   " + views + "    [Enter] "
                + loc( "tui.nav.actions" ) + "  " + back + "  " + quit;
        if ( full.length() <= cols ) {
            return full;
        }
        String medium = "  " + views + "    " + back + "  " + quit;   // drop app name + Enter hint
        if ( medium.length() <= cols ) {
            return medium;
        }
        String compact = "  [L] [B] [G] [S]    " + back + "  " + quit;   // letters only
        if ( compact.length() <= cols ) {
            return compact;
        }
        return "  [L][B][G][S]  [Esc][q]";   // bare minimum for very narrow terminals
    }

    /** Current terminal width in columns (falls back to a wide default before the screen is ready). */
    private int currentColumns()
    {
        try {
            if ( screen != null && screen.getTerminalSize() != null ) {
                return screen.getTerminalSize().getColumns();
            }
        }
        catch ( Throwable ignored ) {
            // size unavailable (e.g. screen torn down) — fall through to the default
        }
        return 120;
    }

    // ================================================================= Library

    private void showLibrary()
    {
        currentView = View.LIBRARY;
        content.removeAllComponents();

        ActionListBox list = new ActionListBox();
        List< GameModPack > packs = GameModPackManager.getInstalledModPacks();
        if ( packs.isEmpty() ) {
            list.addItem( loc( "tui.library.empty" ), () -> { } );
        }
        else {
            for ( GameModPack pack : packs ) {
                list.addItem( formatPackRow( pack ), () -> packActions( pack ) );
            }
        }
        content.addComponent( list.withBorder( Borders.singleLine( loc( "tui.library.title" ) ) ),
                              BorderLayout.Location.CENTER );
        window.invalidate();
        window.setFocusedInteractable( list );
    }

    /** Per-pack action menu (launch / view logs / stop). */
    private void packActions( GameModPack pack )
    {
        boolean running = TuiRuntime.isRunning( pack );
        ActionListDialogBuilder b = new ActionListDialogBuilder()
                .setTitle( pack.getFriendlyName() )
                .setDescription( running ? loc( "tui.actions.running" ) : loc( "tui.actions.choose" ) );
        if ( !running ) {
            b.addAction( loc( "tui.action.launch" ), () -> launch( pack ) );
            b.addAction( loc( "tui.action.uninstall" ), () -> confirmUninstall( pack ) );
        }
        else {
            b.addAction( loc( "tui.action.viewLogs" ), () -> { logsTarget = pack; showLogs(); } );
            b.addAction( loc( "tui.action.stop" ), () -> {
                RunningGame g = TuiRuntime.runningFor( pack );
                if ( g != null ) {
                    g.stop();
                }
            } );
        }
        b.addAction( loc( "tui.action.close" ), () -> { } );
        b.build().showDialog( gui );
    }

    /**
     * Prompts for confirmation and, if accepted, uninstalls the given pack.
     *
     * @param pack the pack to uninstall
     */
    private void confirmUninstall( GameModPack pack )
    {
        MessageDialogButton r = MessageDialog.showMessageDialog( gui, loc( "tui.uninstall.title" ),
                locf( "tui.uninstall.confirm", pack.getFriendlyName() ),
                MessageDialogButton.Yes, MessageDialogButton.No );
        if ( r != MessageDialogButton.Yes ) {
            return;
        }
        runWithSpinner( locf( "tui.uninstall.working", pack.getFriendlyName() ),
                        () -> GameModPackManager.uninstallModPack( pack ),
                        this::showLibrary );
    }

    /**
     * Launches the given pack from the TUI, routing progress through the
     * TUI progress provider.
     *
     * @param pack the pack to launch
     */
    private void launch( GameModPack pack )
    {
        if ( TuiRuntime.isRunning( pack ) ) {
            return;
        }
        BasicWindow progress = new BasicWindow( " " + locf( "tui.launch.title", pack.getFriendlyName() ) + " " );
        progress.setHints( List.of( Window.Hint.CENTERED ) );
        Panel pp = new Panel( new LinearLayout( Direction.VERTICAL ) );
        Label section = new Label( loc( "tui.launch.preparing" ) );
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
                    throw new IllegalStateException( loc( "tui.launch.noProcess" ) );
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
                    MessageDialog.showMessageDialog( gui, loc( "tui.launch.failed" ),
                                                     String.valueOf( t.getMessage() ),
                                                     MessageDialogButton.OK );
                } );
            }
            finally {
                // Release the progress provider (and the TUI progress labels it
                // captures) now the launch is done. Swap rather than set(null) so the
                // cached launcher's lastLaunchedProcess stays intact for the running
                // game; getLastLaunchedProcess() above already ran.
                pack.swapProgressProviderTransiently( null );
            }
        }, "tui-launch-" + pack.getPackSanitizedName() );
        launcher.setDaemon( true );
        launcher.start();
    }

    // ================================================================== Browse

    private void showBrowse()
    {
        currentView = View.BROWSE;
        content.removeAllComponents();

        ActionListBox list = new ActionListBox();
        list.addItem( "＋  " + loc( "tui.browse.installFromUrl" ), this::installByUrl );

        java.util.concurrent.CompletableFuture< Void > fetch = GameModPackManager.getAvailableFetchFuture();
        boolean ready = fetch == null || fetch.isDone();
        if ( !ready ) {
            list.addItem( loc( "tui.browse.loading" ), () -> { } );
            // Rebuild Browse once the background fetch completes.
            fetch.whenComplete( ( v, t ) -> gui.getGUIThread().invokeLater( () -> {
                if ( currentView == View.BROWSE ) {
                    showBrowse();
                }
            } ) );
        }
        else {
            int shown = 0;
            for ( GameModPack pack : GameModPackManager.getAvailableModPacksIfReady() ) {
                String url = pack.getManifestUrl();
                if ( url != null && GameModPackManager.getInstalledModPackByURL( url ) != null ) {
                    continue;   // already installed — it lives in the Library
                }
                GameModPack p = pack;
                list.addItem( formatAvailableRow( p ), () -> confirmInstall( p ) );
                shown++;
            }
            if ( shown == 0 ) {
                list.addItem( loc( "tui.browse.allInstalled" ), () -> { } );
            }
        }
        content.addComponent( list.withBorder( Borders.singleLine( loc( "tui.browse.title" ) ) ),
                              BorderLayout.Location.CENTER );
        window.invalidate();
        window.setFocusedInteractable( list );
    }

    /**
     * Prompts for confirmation and, if accepted, installs the given available pack.
     *
     * @param pack the pack to install
     */
    private void confirmInstall( GameModPack pack )
    {
        MessageDialogButton r = MessageDialog.showMessageDialog( gui, loc( "tui.install.title" ),
                locf( "tui.install.confirm", pack.getFriendlyName() ),
                MessageDialogButton.Yes, MessageDialogButton.No );
        if ( r != MessageDialogButton.Yes ) {
            return;
        }
        runWithSpinner( locf( "tui.install.working", pack.getFriendlyName() ),
                        () -> GameModPackManager.installModPack( pack ),
                        this::showBrowse );
    }

    /**
     * Prompts for a manifest URL and adds/installs the pack it points to.
     */
    private void installByUrl()
    {
        String url = new com.googlecode.lanterna.gui2.dialogs.TextInputDialogBuilder()
                .setTitle( loc( "tui.install.url.title" ) )
                .setDescription( loc( "tui.install.url.prompt" ) )
                .build()
                .showDialog( gui );
        if ( url == null || url.isBlank() ) {
            return;
        }
        String u = url.trim();
        runWithSpinner( loc( "tui.install.url.working" ),
                        () -> GameModPackManager.installModPackByURL( u ),
                        this::showBrowse );
    }

    /**
     * Formats a single available-pack row (name + version) for the list view.
     *
     * @param pack the pack to format
     *
     * @return the display row text
     */
    private static String formatAvailableRow( GameModPack pack )
    {
        String name = safe( pack::getFriendlyName, pack.getPackName() );
        String version = safe( pack::getPackVersion, "?" );
        return String.format( "%-34s  v%s", truncate( name, 34 ), truncate( version, 14 ) );
    }

    // ================================================================ Settings

    private void showSettings()
    {
        // Remember the selected row so a rebuild (after a toggle / value change / game-state
        // refresh) keeps the user where they were instead of snapping back to the top.
        int keepIndex = ( currentView == View.SETTINGS && settingsList != null )
                ? settingsList.getSelectedIndex() : -1;
        currentView = View.SETTINGS;
        content.removeAllComponents();
        ActionListBox list = new ActionListBox();

        // Toggles (Enter flips). Numbers/strings prompt for a new value.
        boolRow( list, loc( "tui.setting.discord" ),
                 ConfigManager::getDiscordRpcEnable, ConfigManager::setDiscordRpcEnable );
        boolRow( list, loc( "tui.setting.console" ),
                 ConfigManager::getInGameConsoleEnable, ConfigManager::setInGameConsoleEnable );
        boolRow( list, loc( "tui.setting.updateCheck" ),
                 ConfigManager::getLauncherUpdateCheckEnabled, ConfigManager::setLauncherUpdateCheckEnabled );
        boolRow( list, loc( "tui.setting.autoBackup" ),
                 ConfigManager::getAutoBackupBeforeUpdate, ConfigManager::setAutoBackupBeforeUpdate );
        boolRow( list, loc( "tui.setting.proxyEnable" ),
                 ConfigManager::getProxyEnable, ConfigManager::setProxyEnable );

        longRow( list, loc( "tui.setting.minRam" ), ConfigManager::getMinRam, ConfigManager::setMinRam );
        longRow( list, loc( "tui.setting.maxRam" ), ConfigManager::getMaxRam, ConfigManager::setMaxRam );
        intRow( list, loc( "tui.setting.maxBackups" ),
                ConfigManager::getMaxBackupsPerPack, ConfigManager::setMaxBackupsPerPack );
        intRow( list, loc( "tui.setting.proxyPort" ), ConfigManager::getProxyPort, ConfigManager::setProxyPort );

        stringRow( list, loc( "tui.setting.jvmArgs" ), ConfigManager::getCustomJvmArgs, ConfigManager::setCustomJvmArgs );
        stringRow( list, loc( "tui.setting.proxyHost" ), ConfigManager::getProxyHost, ConfigManager::setProxyHost );
        themeRow( list );
        localeRow( list );

        settingsList = list;
        if ( keepIndex >= 0 && list.getItemCount() > 0 ) {
            list.setSelectedIndex( Math.min( keepIndex, list.getItemCount() - 1 ) );
        }
        content.addComponent( list.withBorder( Borders.singleLine( loc( "tui.settings.title" ) ) ),
                              BorderLayout.Location.CENTER );
        window.invalidate();
        window.setFocusedInteractable( list );
    }

    private void boolRow( ActionListBox list, String label,
                          java.util.function.BooleanSupplier get, java.util.function.Consumer< Boolean > set )
    {
        boolean val = get.getAsBoolean();
        list.addItem( String.format( "%-30s %s", label, val ? loc( "tui.settings.on" ) : loc( "tui.settings.off" ) ), () -> {
            set.accept( !val );
            showSettings();
        } );
    }

    private void longRow( ActionListBox list, String label,
                          java.util.function.LongSupplier get, java.util.function.LongConsumer set )
    {
        long val = get.getAsLong();
        list.addItem( String.format( "%-30s %d", label, val ), () -> {
            String input = prompt( label, String.valueOf( val ) );
            if ( input != null ) {
                try {
                    set.accept( Long.parseLong( input.trim() ) );
                }
                catch ( NumberFormatException e ) {
                    error( loc( "tui.error.number" ) );
                }
                catch ( Throwable t ) {
                    error( String.valueOf( t.getMessage() ) );
                }
            }
            showSettings();
        } );
    }

    private void intRow( ActionListBox list, String label,
                         java.util.function.IntSupplier get, java.util.function.IntConsumer set )
    {
        int val = get.getAsInt();
        list.addItem( String.format( "%-30s %d", label, val ), () -> {
            String input = prompt( label, String.valueOf( val ) );
            if ( input != null ) {
                try {
                    set.accept( Integer.parseInt( input.trim() ) );
                }
                catch ( NumberFormatException e ) {
                    error( loc( "tui.error.number" ) );
                }
                catch ( Throwable t ) {
                    error( String.valueOf( t.getMessage() ) );
                }
            }
            showSettings();
        } );
    }

    private void stringRow( ActionListBox list, String label,
                            java.util.function.Supplier< String > get, java.util.function.Consumer< String > set )
    {
        String val = get.get();
        String shown = ( val == null || val.isEmpty() ) ? loc( "tui.settings.empty" ) : truncate( val, 40 );
        list.addItem( String.format( "%-30s %s", label, shown ), () -> {
            String input = prompt( label, val == null ? "" : val );
            if ( input != null ) {
                try {
                    set.accept( input );
                }
                catch ( Throwable t ) {
                    error( String.valueOf( t.getMessage() ) );   // e.g. JVM-args validation rejects it
                }
            }
            showSettings();
        } );
    }

    /** Theme is a fixed-choice row: picking one applies it AND re-themes the CLI live (the CLI's
     *  light/dark look is derived from this setting — see {@link TuiTheme}). */
    private void themeRow( ActionListBox list )
    {
        list.addItem( String.format( "%-30s %s", loc( "tui.setting.theme" ), ConfigManager.getTheme() ), () -> {
            ActionListDialogBuilder b = new ActionListDialogBuilder().setTitle( loc( "tui.setting.theme" ) );
            for ( String opt : new String[] {
                    ConfigConstants.THEME_DARK, ConfigConstants.THEME_LIGHT, ConfigConstants.THEME_AUTOMATIC,
                    ConfigConstants.THEME_NATIVE, ConfigConstants.THEME_BLUE_GRAY,
                    ConfigConstants.THEME_ORANGE_PURPLE, ConfigConstants.THEME_CREEPER } ) {
                b.addAction( opt, () -> {
                    ConfigManager.setTheme( opt );
                    applyTheme();
                    showSettings();
                } );
            }
            b.build().showDialog( gui );
        } );
    }

    /** Re-applies the CLI light/dark theme (e.g. after the Theme setting changes). */
    private void applyTheme()
    {
        if ( gui != null ) {
            gui.setTheme( TuiTheme.build() );
            if ( window != null ) {
                window.invalidate();
            }
        }
    }

    /** Language is a fixed-choice row: "Use OS Language", English, then every translated locale.
     *  Picking one persists the override and switches the active bundle live. */
    private void localeRow( ActionListBox list )
    {
        list.addItem( String.format( "%-30s %s", loc( "tui.setting.language" ), currentLocaleLabel() ), () -> {
            ActionListDialogBuilder b = new ActionListDialogBuilder().setTitle( loc( "tui.setting.language" ) );
            b.addAction( loc( "tui.locale.osDefault" ), () -> applyLocaleChoice( "" ) );
            b.addAction( "English", () -> applyLocaleChoice( "en-US" ) );
            for ( com.micatechnologies.minecraft.launcher.consts.localization.SupportedLocales.Entry e
                    : com.micatechnologies.minecraft.launcher.consts.localization.SupportedLocales.ENTRIES ) {
                b.addAction( e.displayName(), () -> applyLocaleChoice( e.tag() ) );
            }
            b.build().showDialog( gui );
        } );
    }

    /** Persists the locale override, re-resolves the effective locale (override → OS → en-US), switches
     *  the active translation bundle, and re-renders the UI in the new language. */
    private void applyLocaleChoice( String tag )
    {
        ConfigManager.setLocaleOverride( tag );
        com.micatechnologies.minecraft.launcher.consts.localization.LocaleBootstrap.apply( tag );
        LocalizationManager.setLocale( java.util.Locale.getDefault() );
        if ( headerLabel != null ) {
            headerLabel.setText( headerText() );
        }
        if ( statusBar != null ) {
            statusBar.setText( statusLine() );
        }
        showSettings();
    }

    /**
     * Returns the display label for the currently-selected locale.
     *
     * @return the current locale's label
     */
    private static String currentLocaleLabel()
    {
        String tag = ConfigManager.getLocaleOverride();
        if ( tag == null || tag.isBlank() ) {
            return loc( "tui.locale.osDefault" );
        }
        for ( com.micatechnologies.minecraft.launcher.consts.localization.SupportedLocales.Entry e
                : com.micatechnologies.minecraft.launcher.consts.localization.SupportedLocales.ENTRIES ) {
            if ( e.tag().equals( tag ) ) {
                return e.displayName();
            }
        }
        return tag;   // e.g. en-US
    }

    /**
     * Shows a modal single-line text-input dialog.
     *
     * @param title   the dialog title / prompt
     * @param initial the initial field value
     *
     * @return the entered string, or {@code null} if cancelled
     */
    private String prompt( String title, String initial )
    {
        return new com.googlecode.lanterna.gui2.dialogs.TextInputDialogBuilder()
                .setTitle( title )
                .setInitialContent( initial == null ? "" : initial )
                .build()
                .showDialog( gui );
    }

    /**
     * Shows a modal error message dialog.
     *
     * @param msg the error message to display
     */
    private void error( String msg )
    {
        MessageDialog.showMessageDialog( gui, loc( "tui.error.invalid.title" ),
                                         msg == null ? loc( "tui.error.invalid.body" ) : msg,
                                         MessageDialogButton.OK );
    }

    // ==================================================================== Logs

    private void showLogs()
    {
        currentView = View.LOGS;
        content.removeAllComponents();

        java.util.Collection< RunningGame > running = TuiRuntime.running();
        if ( running.isEmpty() ) {
            content.addComponent( new Label( "  " + loc( "tui.logs.none" ) )
                                          .withBorder( Borders.singleLine( loc( "tui.logs.title" ) ) ),
                                  BorderLayout.Location.CENTER );
            logsBox = null;
            window.invalidate();
            return;
        }
        // Default the target to the first running game if none/stale selected.
        if ( logsTarget == null || !TuiRuntime.isRunning( logsTarget ) ) {
            logsTarget = running.iterator().next().pack();
        }

        // The log box is the focused, scrollable component — a read-only multi-line TextBox scrolls
        // with the arrow keys ONLY while it holds focus, so we make it the sole interactable here
        // (no left-hand picker stealing focus/arrows). Multiple running games are cycled with 'n'.
        String title = locf( "tui.logs.header", truncate( logsTarget.getFriendlyName(), 32 ) )
                + ( running.size() > 1 ? "   " + locf( "tui.logs.next", running.size() ) : "" )
                + "   [↑↓←→] " + loc( "tui.logs.scrollHint" );
        logsBox = new LogBox( new TerminalSize( 100, 24 ) );
        content.addComponent( logsBox.withBorder( Borders.singleLine( title ) ),
                              BorderLayout.Location.CENTER );

        lastLogSize = -1;   // force a render of the freshly-built box
        refreshLogsBox();
        window.invalidate();
        // Focus the log so the arrow keys scroll it immediately.
        window.setFocusedInteractable( logsBox );
    }

    /** Cycles the Logs view to the next running game (bound to 'n'). */
    private void cycleLogsTarget()
    {
        List< RunningGame > running = new java.util.ArrayList<>( TuiRuntime.running() );
        if ( running.size() < 2 ) {
            return;
        }
        String currentUrl = logsTarget == null ? null : logsTarget.getManifestUrl();
        int idx = 0;
        for ( int i = 0; i < running.size(); i++ ) {
            String url = running.get( i ).pack().getManifestUrl();
            if ( url != null && url.equals( currentUrl ) ) {
                idx = i;
                break;
            }
        }
        logsTarget = running.get( ( idx + 1 ) % running.size() ).pack();
        showLogs();
    }

    /** Pushes the current logs target's buffered output into the log TextBox — but only when new
     *  lines have arrived, so the per-second refresh doesn't fight the user's manual scrolling. */
    private void refreshLogsBox()
    {
        TextBox box = logsBox;
        GameModPack target = logsTarget;
        if ( box == null || target == null ) {
            return;
        }
        RunningGame g = TuiRuntime.runningFor( target );
        if ( g == null ) {
            if ( lastLogSize != -2 ) {
                box.setText( loc( "tui.logs.exited" ) );
                lastLogSize = -2;
            }
            return;
        }
        List< String > lines = g.logSnapshot();
        if ( lines.size() == lastLogSize ) {
            return;   // nothing new — leave the user's scroll position alone
        }
        // Follow the newest output only when the caret is on the last line. If the user has scrolled
        // up, freeze the view (don't reload) so they can read — and DON'T advance lastLogSize, so
        // scrolling back to the bottom re-engages following and reloads the latest tail.
        boolean atBottom = box.getCaretPosition().getRow() >= box.getLineCount() - 1;
        if ( !atBottom ) {
            return;
        }
        lastLogSize = lines.size();
        // Keep the last ~500 lines to avoid an enormous TextBox.
        int from = Math.max( 0, lines.size() - 500 );
        box.setText( String.join( "\n", lines.subList( from, lines.size() ) ) );
        // Auto-scroll to the newest line. The caret line must be a VALID index (lineCount-1) — using
        // lineCount itself put the caret one line past the end, and arrowing into it threw an
        // out-of-bounds that tore down the UI.
        box.setCaretPosition( Math.max( 0, box.getLineCount() - 1 ), 0 );
    }

    /**
     * The log display: a multi-line {@link TextBox} that reads like a pager — arrow / page keys move
     * the caret (so it scrolls AND we can read the position to decide whether to follow new output),
     * but every editing keystroke is swallowed and focus is never surrendered at the edges. Non-nav
     * keys fall through to the window so {@code q}/{@code l}/{@code g}/{@code n} still work.
     */
    private static final class LogBox extends TextBox
    {
        LogBox( TerminalSize size )
        {
            super( size, Style.MULTI_LINE );
        }

        @Override
        public synchronized com.googlecode.lanterna.gui2.Interactable.Result handleKeyStroke( KeyStroke ks )
        {
            switch ( ks.getKeyType() ) {
                case ArrowUp:
                case ArrowDown:
                case ArrowLeft:
                case ArrowRight:
                case PageUp:
                case PageDown:
                case Home:
                case End: {
                    com.googlecode.lanterna.gui2.Interactable.Result r = super.handleKeyStroke( ks );
                    // Stay put at the edges rather than handing focus to a neighbour.
                    if ( r == com.googlecode.lanterna.gui2.Interactable.Result.MOVE_FOCUS_UP
                            || r == com.googlecode.lanterna.gui2.Interactable.Result.MOVE_FOCUS_DOWN
                            || r == com.googlecode.lanterna.gui2.Interactable.Result.MOVE_FOCUS_LEFT
                            || r == com.googlecode.lanterna.gui2.Interactable.Result.MOVE_FOCUS_RIGHT ) {
                        return com.googlecode.lanterna.gui2.Interactable.Result.HANDLED;
                    }
                    return r;
                }
                default:
                    // Block editing; let q / l / g / n reach the window's unhandled-input handler.
                    return com.googlecode.lanterna.gui2.Interactable.Result.UNHANDLED;
            }
        }
    }

    // ================================================================== shared

    /** Called when a game starts or exits: refresh the views that reflect running state (Library's
     *  running marker, the Logs view) plus the status bar. Browse and Settings don't show running
     *  state, so we leave them untouched rather than rebuilding and losing the user's place. */
    private void onGamesChanged()
    {
        switch ( currentView ) {
            case LIBRARY -> showLibrary();
            case LOGS -> showLogs();
            case BROWSE, SETTINGS -> { /* no running-state shown here — don't disrupt the user */ }
        }
        statusBar.setText( statusLine() );
    }

    /** Runs a blocking task off the GUI thread behind a centered "working" modal, then closes it and
     *  either reports the error or runs {@code onDone} (on the GUI thread). Used for install /
     *  uninstall, which don't expose a progress callback. */
    private void runWithSpinner( String message, Runnable work, Runnable onDone )
    {
        BasicWindow modal = new BasicWindow();
        modal.setHints( List.of( Window.Hint.CENTERED ) );
        modal.setComponent( new Label( "  " + message + "  " ).withBorder( Borders.singleLine() ) );
        gui.addWindow( modal );

        Thread t = new Thread( () -> {
            Throwable error = null;
            try {
                work.run();
            }
            catch ( Throwable ex ) {
                error = ex;
                Logger.logThrowable( ex );
            }
            final Throwable finalError = error;
            gui.getGUIThread().invokeLater( () -> {
                modal.close();
                if ( finalError != null ) {
                    MessageDialog.showMessageDialog( gui, loc( "tui.generic.failed" ),
                                                     String.valueOf( finalError.getMessage() ),
                                                     MessageDialogButton.OK );
                }
                else if ( onDone != null ) {
                    onDone.run();
                }
                statusBar.setText( statusLine() );
            } );
        }, "tui-task" );
        t.setDaemon( true );
        t.start();
    }

    /**
     * Starts the ~1 Hz background refresher that re-renders running-game state
     * and logs.
     */
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

    /**
     * Stops the background refresher started by {@link #startRefresh()}.
     */
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
        return String.format( "%s%-28s  v%-12s  %s %-12s  %s",
                              marker, truncate( name, 28 ), truncate( version, 12 ),
                              loc( "tui.row.lastPlayed" ), truncate( lastPlayed, 12 ), playtime );
    }

    static String statusLine()
    {
        int running = TuiRuntime.runningCount();
        long totalMs = TuiRuntime.liveTotalPlaytimeMs();
        var user = MCLauncherAuthManager.getLoggedInUser();
        String userName = ( user != null && user.name() != null )
                ? user.name() : loc( "tui.status.signedOut" );
        return "  " + locf( "tui.status.line", running, formatDuration( totalMs ), userName );
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

    /**
     * Truncates a string to at most {@code max} characters (with an ellipsis when
     * shortened).
     *
     * @param s   the string to truncate (may be {@code null})
     * @param max the maximum length
     *
     * @return the truncated string
     */
    private static String truncate( String s, int max )
    {
        if ( s == null ) {
            return "";
        }
        return s.length() <= max ? s : s.substring( 0, Math.max( 0, max - 1 ) ) + "…";
    }

    /**
     * Invokes a value supplier, returning {@code fallback} if it throws — so a
     * single failing accessor can't break a row render.
     *
     * @param getter   the value supplier
     * @param fallback the value to return on failure
     *
     * @return the supplied value, or {@code fallback} on error
     */
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
