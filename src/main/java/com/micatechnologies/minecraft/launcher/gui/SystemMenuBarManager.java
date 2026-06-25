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
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.game.modpack.import_.MmcjsonImporter;
import com.micatechnologies.minecraft.launcher.utilities.AnnouncementManager;
import com.micatechnologies.minecraft.launcher.utilities.LauncherUriHandler;
import com.micatechnologies.minecraft.launcher.utilities.NotificationManager;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.Pane;
import org.apache.commons.lang3.SystemUtils;

import java.awt.Desktop;
import java.awt.desktop.QuitStrategy;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * macOS-only system menu bar wiring. On macOS this installs a {@link MenuBar} with
 * {@code useSystemMenuBar = true} so the launcher's actions appear in the screen-top menu
 * bar Mac users expect; on Windows and Linux every method here is a no-op and the in-window
 * navbar remains the canonical action surface.
 *
 * <p>The same {@code MenuBar} instance lives for the launcher's whole session — JavaFX
 * nodes can only have one parent at a time, so {@link #attachTo(Pane)} reparents the bar
 * to each new scene as the user navigates between screens. Re-creating per scene was
 * tried first but produced a brief flicker in the macOS system menu on every screen
 * change.</p>
 *
 * <p>All actions reach the same underlying controller methods as the in-window navbar,
 * so no functionality lives only here — macOS users get a richer system menu, every
 * other user is unaffected.</p>
 */
public final class SystemMenuBarManager
{
    private static MenuBar instance      = null;
    private static Pane    currentParent = null;

    /** Max packs shown in the Play Recent submenu. */
    private static final int RECENT_MAX = 8;

    // Dynamic menu nodes — held so refreshDynamicMenus() can re-sync them with current
    // state (recent-pack list, selected theme, toggle states) on each navigation, since
    // the native macOS menu bar doesn't fire JavaFX's Menu onShowing reliably.
    private static Menu                       recentMenu      = null;
    private static Map< String, RadioMenuItem > themeItems    = null;
    private static CheckMenuItem              discordItem     = null;
    private static CheckMenuItem              consoleItem     = null;
    private static CheckMenuItem              backgroundsItem = null;

    private SystemMenuBarManager() { /* static-only */ }

    /**
     * Registers the macOS application-menu callbacks for About / Preferences / Quit via the
     * {@link Desktop} API. macOS routes the system-supplied menu items in the "Mica Minecraft"
     * application menu through these handlers; Windows / Linux either don't support them or
     * never reach them. Call exactly once during app startup.
     */
    public static void installDesktopHandlers()
    {
        // OPEN_URI handler is the cross-platform piece — on macOS it makes mmcl:// links
        // delivered to an already-running launcher work correctly. Win / Linux Desktop
        // doesn't currently surface the action, so this is effectively macOS-only today,
        // but the API is portable so we don't gate it on SystemUtils.IS_OS_MAC.
        if ( Desktop.isDesktopSupported() ) {
            try {
                Desktop desktop = Desktop.getDesktop();
                if ( desktop.isSupported( Desktop.Action.APP_OPEN_URI ) ) {
                    desktop.setOpenURIHandler( event -> {
                        String uri = event.getURI() == null ? null : event.getURI().toString();
                        if ( uri == null ) {
                            return;
                        }
                        // macOS delivers cold-start mmcl:// URIs through this handler (not argv —
                        // that's the Win/Linux path). If the main GUI isn't up yet, stash via
                        // the same pendingLauncherUri channel the argv parser uses; LauncherSession
                        // dispatches once auth + modpack list are ready. If the launcher is
                        // already running, dispatch immediately.
                        if ( MCLauncherGuiController.getTopStageOrNull() == null ) {
                            LauncherCore.setPendingLauncherUri( uri );
                        }
                        else {
                            LauncherUriHandler.handle( uri );
                        }
                    } );
                }
                // OPEN_FILE handler — symmetric to OPEN_URI for double-click / drag-drop
                // delivery of .mmcjson modpack manifests. macOS delivers these via
                // Launch Services (registered through jpackage --file-associations);
                // Win / Linux today route file-association double-clicks through argv,
                // which the cold-start path will eventually consume separately.
                if ( desktop.isSupported( Desktop.Action.APP_OPEN_FILE ) ) {
                    desktop.setOpenFileHandler( event -> {
                        if ( event == null || event.getFiles() == null ) return;
                        for ( java.io.File file : event.getFiles() ) {
                            if ( file == null || !isMmcjsonFile( file ) ) continue;
                            // Same defer-vs-dispatch split as OPEN_URI: if the main GUI
                            // isn't up yet, queue for LauncherSession to drain after
                            // auth + modpack list are ready. If already running, kick
                            // the import on a worker thread so we don't block the AWT
                            // event-dispatch thread on disk I/O + network.
                            if ( MCLauncherGuiController.getTopStageOrNull() == null ) {
                                LauncherCore.addPendingMmcjsonFile( file );
                            }
                            else {
                                SystemUtilities.spawnNewTask( () -> dispatchMmcjsonImport( file ) );
                            }
                        }
                    } );
                }
            }
            catch ( Exception | Error e ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.menu.installUriFileHandlerFailed", e.getMessage() ) );
            }
        }

        if ( !SystemUtils.IS_OS_MAC ) {
            return;
        }
        if ( !Desktop.isDesktopSupported() ) {
            return;
        }

        Desktop desktop = Desktop.getDesktop();

        try {
            if ( desktop.isSupported( Desktop.Action.APP_ABOUT ) ) {
                desktop.setAboutHandler( e -> showAboutDialog() );
            }
            if ( desktop.isSupported( Desktop.Action.APP_PREFERENCES ) ) {
                desktop.setPreferencesHandler( e -> openSettings() );
            }
            if ( desktop.isSupported( Desktop.Action.APP_QUIT_HANDLER ) ) {
                // closeApp() runs cleanup + System.exit, so we never return from this handler.
                // The QuitResponse parameter is unused — the system can't outrace System.exit.
                desktop.setQuitHandler( ( e, response ) -> LauncherCore.closeApp() );
            }
            if ( desktop.isSupported( Desktop.Action.APP_QUIT_STRATEGY ) ) {
                // Belt-and-suspenders: if the quit handler ever fails to fire, fall back to
                // System.exit instead of the JVM's "close all windows" default, which would
                // skip our cleanup.
                desktop.setQuitStrategy( QuitStrategy.CLOSE_ALL_WINDOWS );
            }
        }
        catch ( Exception | Error e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.menu.installDesktopHandlersFailed", e.getMessage() ) );
        }
    }

    /**
     * Lazily creates the singleton menu bar on first call (macOS only). Subsequent calls are
     * cheap no-ops. Safe to call from any thread — internally hops to the FX thread.
     */
    public static void ensureCreated()
    {
        if ( !SystemUtils.IS_OS_MAC ) {
            return;
        }
        if ( instance != null ) {
            return;
        }
        GUIUtilities.JFXPlatformRun( () -> {
            if ( instance == null ) {
                instance = buildMenuBar();
                instance.setUseSystemMenuBar( true );
                // managed=false: don't reserve layout space for the in-window peer.
                // visible=false: don't render it either. The system-menu-bar hook reads the
                // MenuBar's menu data structure, not the node's visibility, so when
                // useSystemMenuBar takes effect (properly bundled .app, correct AWT/FX init
                // order) the macOS screen-top bar still shows the menus. When it doesn't
                // (dev launch where Desktop.getDesktop() initialized NSApp before JavaFX's
                // Glass backend could claim the main menu), the MenuBarSkin falls back to
                // in-window rendering — without these flags the menu buttons appear as
                // three transparent, unstyled nodes at (0,0) of whatever pane we're parented
                // to, overlapping content and (when clicked) wedging the FX thread mid
                // popup-teardown as the menu bar is reparented across scene changes.
                instance.setManaged( false );
                instance.setVisible( false );
            }
        } );
    }

    /**
     * Moves the singleton menu bar into {@code root}'s children, removing it from its
     * previous parent (if any). On Win / Linux this is a no-op. Call from the FX thread
     * during scene transitions (after the scene's setup completes).
     */
    public static void attachTo( Pane root )
    {
        if ( !SystemUtils.IS_OS_MAC ) {
            return;
        }
        if ( root == null || instance == null ) {
            return;
        }
        if ( currentParent == root ) {
            return;
        }
        if ( currentParent != null ) {
            currentParent.getChildren().remove( instance );
        }
        root.getChildren().add( instance );
        currentParent = root;

        // The native macOS bar doesn't fire JavaFX's Menu onShowing reliably, so re-sync the
        // dynamic content (recent packs + quick-settings state) on each navigation instead.
        refreshDynamicMenus();
    }

    // =========================================================================================
    //  Menu construction
    // =========================================================================================

    private static MenuBar buildMenuBar()
    {
        MenuBar bar = new MenuBar();
        bar.getMenus().addAll( buildModpacksMenu(), buildGameMenu(), buildViewMenu(),
                               buildWindowMenu(), buildHelpMenu() );
        return bar;
    }

    /**
     * Builds the "Modpacks" top-level menu (library, editor, refresh actions).
     *
     * @return the populated {@link Menu}
     */
    private static Menu buildModpacksMenu()
    {
        Menu menu = new Menu( LocalizationManager.get( "menu.modpacks.title" ) );

        MenuItem home = new MenuItem( LocalizationManager.get( "menu.modpacks.home" ) );
        home.setAccelerator( new KeyCodeCombination( KeyCode.H, KeyCombination.SHORTCUT_DOWN,
                                                     KeyCombination.SHIFT_DOWN ) );
        home.setOnAction( e -> goHome() );

        // "Open Browse" routes to the install + manage screen — formerly "Library",
        // renamed so the main menu's wordmark area can use "Library" as the screen
        // name for the home/installed-packs surface.
        MenuItem library = new MenuItem( LocalizationManager.get( "menu.modpacks.browse" ) );
        library.setAccelerator( new KeyCodeCombination( KeyCode.L, KeyCombination.SHORTCUT_DOWN ) );
        library.setOnAction( e -> openLibrary() );

        MenuItem refresh = new MenuItem( LocalizationManager.get( "menu.modpacks.refresh" ) );
        refresh.setAccelerator( new KeyCodeCombination( KeyCode.R, KeyCombination.SHORTCUT_DOWN ) );
        refresh.setOnAction( e -> refreshMain() );

        // Populated now and re-populated on each navigation via refreshDynamicMenus().
        recentMenu = new Menu( LocalizationManager.get( "menu.modpacks.playRecent" ) );
        applyRecents( gatherRecents() );

        menu.getItems().addAll( home, library, refresh, new SeparatorMenuItem(), recentMenu );
        return menu;
    }

    /**
     * Builds the "Game" top-level menu.
     *
     * @return the populated {@link Menu}
     */
    private static Menu buildGameMenu()
    {
        Menu menu = new Menu( LocalizationManager.get( "menu.game.title" ) );

        MenuItem runtimes = new MenuItem( LocalizationManager.get( "menu.game.runtimes" ) );
        runtimes.setOnAction( e -> openRuntime() );

        MenuItem editor = new MenuItem( LocalizationManager.get( "menu.game.editor" ) );
        editor.setOnAction( e -> openEditor() );

        menu.getItems().addAll( runtimes, editor );
        return menu;
    }

    /**
     * Builds the "View" top-level menu.
     *
     * @return the populated {@link Menu}
     */
    private static Menu buildViewMenu()
    {
        Menu menu = new Menu( LocalizationManager.get( "menu.view.title" ) );

        // Theme submenu — radio items mirroring the Settings screen's theme combo. Both use
        // the raw ALLOWED_THEMES display names (the Settings combo doesn't localize them
        // either), so a value flows straight through to ConfigManager.setTheme.
        Menu themeMenu = new Menu( LocalizationManager.get( "menu.view.theme" ) );
        ToggleGroup themeGroup = new ToggleGroup();
        themeItems = new LinkedHashMap<>();
        String currentTheme = ConfigManager.getTheme();
        for ( String theme : ConfigConstants.ALLOWED_THEMES ) {
            RadioMenuItem item = new RadioMenuItem( theme );
            item.setToggleGroup( themeGroup );
            item.setSelected( theme.equals( currentTheme ) );
            item.setOnAction( e -> applyTheme( theme ) );
            themeItems.put( theme, item );
            themeMenu.getItems().add( item );
        }

        discordItem = new CheckMenuItem( LocalizationManager.get( "menu.view.discordRpc" ) );
        discordItem.setSelected( ConfigManager.getDiscordRpcEnable() );
        discordItem.setOnAction( e -> toggleDiscordRpc( discordItem.isSelected() ) );

        consoleItem = new CheckMenuItem( LocalizationManager.get( "menu.view.inGameConsole" ) );
        consoleItem.setSelected( ConfigManager.getInGameConsoleEnable() );
        consoleItem.setOnAction( e -> ConfigManager.setInGameConsoleEnable( consoleItem.isSelected() ) );

        backgroundsItem = new CheckMenuItem( LocalizationManager.get( "menu.view.packBackgrounds" ) );
        backgroundsItem.setSelected( ConfigManager.getShowPackBackgrounds() );
        backgroundsItem.setOnAction( e -> ConfigManager.setShowPackBackgrounds( backgroundsItem.isSelected() ) );

        menu.getItems().addAll( themeMenu, new SeparatorMenuItem(),
                                discordItem, consoleItem, backgroundsItem );
        return menu;
    }

    /**
     * Builds the "Window" top-level menu.
     *
     * @return the populated {@link Menu}
     */
    private static Menu buildWindowMenu()
    {
        Menu menu = new Menu( LocalizationManager.get( "menu.window.title" ) );

        MenuItem minimize = new MenuItem( LocalizationManager.get( "menu.window.minimize" ) );
        minimize.setAccelerator( new KeyCodeCombination( KeyCode.M, KeyCombination.SHORTCUT_DOWN ) );
        minimize.setOnAction( e -> withTopStage( stage -> stage.setIconified( true ) ) );

        MenuItem zoom = new MenuItem( LocalizationManager.get( "menu.window.zoom" ) );
        zoom.setOnAction( e -> withTopStage( stage -> stage.setMaximized( !stage.isMaximized() ) ) );

        menu.getItems().addAll( minimize, zoom );
        return menu;
    }

    /**
     * Builds the "Help" top-level menu (help window, about dialog).
     *
     * @return the populated {@link Menu}
     */
    private static Menu buildHelpMenu()
    {
        Menu menu = new Menu( LocalizationManager.get( "menu.help.title" ) );

        MenuItem help = new MenuItem( LocalizationManager.get( "menu.help.help" ) );
        // Cmd+? is the macOS convention for app help. SLASH + SHIFT_DOWN produces "?".
        help.setAccelerator( new KeyCodeCombination( KeyCode.SLASH,
                                                     KeyCombination.SHORTCUT_DOWN,
                                                     KeyCombination.SHIFT_DOWN ) );
        help.setOnAction( e -> MCLauncherHelpWindow.show( HelpTopic.GETTING_STARTED ) );

        MenuItem website = new MenuItem( LocalizationManager.get( "menu.help.website" ) );
        website.setOnAction( e -> openUrl( "https://micatechnologies.com" ) );

        menu.getItems().addAll( help, website );
        return menu;
    }

    // =========================================================================================
    //  Dynamic menu refresh — recent packs + quick-settings state, re-synced on each
    //  navigation (the native macOS bar doesn't fire JavaFX's Menu onShowing reliably).
    //  Runs on the FX thread, called from attachTo.
    // =========================================================================================

    private static void refreshDynamicMenus()
    {
        if ( !SystemUtils.IS_OS_MAC || instance == null ) {
            return;
        }
        if ( themeItems != null ) {
            String current = ConfigManager.getTheme();
            themeItems.forEach( ( theme, item ) -> item.setSelected( theme.equals( current ) ) );
        }
        if ( discordItem != null ) {
            discordItem.setSelected( ConfigManager.getDiscordRpcEnable() );
        }
        if ( consoleItem != null ) {
            consoleItem.setSelected( ConfigManager.getInGameConsoleEnable() );
        }
        if ( backgroundsItem != null ) {
            backgroundsItem.setSelected( ConfigManager.getShowPackBackgrounds() );
        }
        applyRecents( gatherRecents() );
    }

    /** Installed packs that have been played at least once, newest first, capped at
     *  {@link #RECENT_MAX}. getLastPlayedMs caches its .launch_history read after the first
     *  call, and the main screen's last-played sort has usually warmed it already, so this
     *  is cheap enough to run on the FX thread. */
    private static java.util.List< GameModPack > gatherRecents()
    {
        return GameModPackManager.getInstalledModPacks().stream()
                .filter( p -> p != null && !p.isNeverPlayed() )
                .sorted( java.util.Comparator.comparingLong( GameModPack::getLastPlayedMs ).reversed() )
                .limit( RECENT_MAX )
                .collect( java.util.stream.Collectors.toList() );
    }

    /** Rebuilds the Play Recent submenu. ⌘1–⌘9 quick-launch the first nine entries; an
     *  empty history shows a single disabled placeholder. */
    private static void applyRecents( java.util.List< GameModPack > recents )
    {
        if ( recentMenu == null ) {
            return;
        }
        recentMenu.getItems().clear();
        if ( recents.isEmpty() ) {
            MenuItem none = new MenuItem( LocalizationManager.get( "menu.modpacks.playRecent.empty" ) );
            none.setDisable( true );
            recentMenu.getItems().add( none );
            return;
        }
        int index = 0;
        for ( GameModPack pack : recents ) {
            MenuItem item = new MenuItem( pack.getFriendlyName() );
            if ( index < 9 ) {
                item.setAccelerator( new KeyCodeCombination( KeyCode.valueOf( "DIGIT" + ( index + 1 ) ),
                                                             KeyCombination.SHORTCUT_DOWN ) );
            }
            item.setOnAction( e -> playPack( pack ) );
            recentMenu.getItems().add( item );
            index++;
        }
    }

    // =========================================================================================
    //  Action handlers — same flows as the in-window navbar buttons
    // =========================================================================================

    private static void openSettings()
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToSettingsGui();
            }
            catch ( IOException e ) {
                Logger.logError( LocalizationManager.get( "log.menu.openSettingsFailed" ) );
                Logger.logThrowable( e );
            }
        } );
    }

    /**
     * Menu action: navigates to the Game Library screen.
     */
    private static void openLibrary()
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToGameLibraryGui();
            }
            catch ( IOException e ) {
                Logger.logError( LocalizationManager.get( "log.menu.openLibraryFailed" ) );
                Logger.logThrowable( e );
            }
        } );
    }

    /**
     * Menu action: navigates to the Java runtime management screen.
     */
    private static void openRuntime()
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToRuntimeGui();
            }
            catch ( IOException e ) {
                Logger.logError( LocalizationManager.get( "log.menu.openRuntimeFailed" ) );
                Logger.logThrowable( e );
            }
        } );
    }

    /**
     * Menu action: triggers a refresh of the main-menu modpack list.
     */
    private static void refreshMain()
    {
        SystemUtilities.spawnNewTask( () -> {
            AnnouncementManager.checkAnnouncements();
            GameModPackManager.fetchModPackInfo();
            try {
                MCLauncherGuiController.goToMainGui();
            }
            catch ( Exception e ) {
                Logger.logError( LocalizationManager.get( "log.menu.refreshMainFailed" ) );
                Logger.logThrowable( e );
            }
        } );
    }

    /**
     * Menu action: navigates back to the main menu.
     */
    private static void goHome()
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToMainGui();
            }
            catch ( Exception e ) {
                Logger.logError( LocalizationManager.get( "log.menu.openHomeFailed" ) );
                Logger.logThrowable( e );
            }
        } );
    }

    /**
     * Menu action: opens the modpack editor.
     */
    private static void openEditor()
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToModPackEditorGui();
            }
            catch ( IOException e ) {
                Logger.logError( LocalizationManager.get( "log.menu.openEditorFailed" ) );
                Logger.logThrowable( e );
            }
        } );
    }

    /** Launches {@code pack} off the FX thread — {@link LauncherCore#play} blocks on the
     *  auth refresh + launch pipeline and drives its own launch-progress window. Mirrors the
     *  in-window hero/library card Play handler. */
    private static void playPack( GameModPack pack )
    {
        if ( pack == null ) {
            return;
        }
        SystemUtilities.spawnNewTask( () -> {
            try {
                LauncherCore.play( pack );
            }
            catch ( Throwable t ) {
                Logger.logError( LocalizationManager.format( "log.menu.launchPackFailed", pack.getPackName() ) );
                Logger.logThrowable( t );
            }
        } );
    }

    /** Persists the theme and repaints immediately, same as the Settings screen's combo. */
    private static void applyTheme( String theme )
    {
        ConfigManager.setTheme( theme );
        MCLauncherGuiController.forceThemeRefresh();
    }

    /** Mirrors the Settings screen's Discord toggle: persist, and tear down the live
     *  presence when disabling. Re-enabling resumes on the next screen-presence update. */
    private static void toggleDiscordRpc( boolean enabled )
    {
        ConfigManager.setDiscordRpcEnable( enabled );
        if ( !enabled ) {
            com.micatechnologies.minecraft.launcher.utilities.DiscordRpcUtility.exit();
        }
    }

    /** Runs {@code action} against the launcher's top stage on the FX thread, if it exists. */
    private static void withTopStage( java.util.function.Consumer< javafx.stage.Stage > action )
    {
        javafx.stage.Stage top = MCLauncherGuiController.getTopStageOrNull();
        if ( top == null ) {
            return;
        }
        GUIUtilities.JFXPlatformRun( () -> action.accept( top ) );
    }

    /**
     * Opens a URL in the user's default browser, guarded by
     * {@link java.awt.Desktop#isDesktopSupported()}.
     *
     * @param url the http(s) URL to open
     */
    private static void openUrl( String url )
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                if ( Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported( Desktop.Action.BROWSE ) ) {
                    Desktop.getDesktop().browse( URI.create( url ) );
                }
            }
            catch ( IOException e ) {
                Logger.logError( LocalizationManager.format( "log.menu.openUrlFailed", url ) );
                Logger.logThrowable( e );
            }
        } );
    }

    /** True when {@code file} ends with the canonical {@code .mmcjson} extension
     *  (case-insensitive). The OPEN_FILE handler ignores anything else so a stray
     *  drag of an unrelated file onto the dock icon doesn't trip the importer. */
    private static boolean isMmcjsonFile( java.io.File file )
    {
        if ( file == null ) return false;
        String name = file.getName();
        return name != null
                && name.toLowerCase( java.util.Locale.ROOT )
                       .endsWith( ModPackConstants.MODPACK_FILE_EXTENSION );
    }

    /** Runs an {@link MmcjsonImporter#importMmcjsonFile(java.io.File)} and toasts
     *  the result. Public-static so {@link com.micatechnologies.minecraft.launcher.LauncherSession}
     *  can drain {@link LauncherCore#consumePendingMmcjsonFiles()} through the same
     *  code path the immediate-dispatch branch uses. Always called from a worker
     *  thread — the importer touches disk + network through the install pipeline
     *  and would block the FX / AWT thread if invoked synchronously. */
    public static void dispatchMmcjsonImport( java.io.File file )
    {
        try {
            MmcjsonImporter.importMmcjsonFile( file );
            NotificationManager.success(
                    LocalizationManager.get( "notification.uri.modpackAdded.title" ),
                    LocalizationManager.get( "notification.uri.modpackAdded.body" ) );
            // Refresh the main GUI so the new pack appears in the hero-card grid
            // immediately rather than after the user navigates away and back.
            GUIUtilities.JFXPlatformRun( () -> {
                try {
                    MCLauncherGuiController.goToMainGui();
                }
                catch ( Exception ignored ) { /* user may not be on main; that's fine */ }
            } );
        }
        catch ( MmcjsonImporter.ImportException ie ) {
            Logger.logErrorSilent( LocalizationManager.format( "log.menu.mmcjsonImportFailed",
                                           ( file == null ? "<null>" : file.getName() ),
                                           ie.getMessage() ) );
            NotificationManager.error(
                    LocalizationManager.get( "notification.uri.modpackAddFailed.title" ),
                    ie.getMessage() );
        }
        catch ( Exception e ) {
            Logger.logErrorSilent( LocalizationManager.format( "log.menu.mmcjsonImportUnexpected", e.getMessage() ) );
            Logger.logThrowable( e );
            NotificationManager.error(
                    LocalizationManager.get( "notification.uri.modpackAddFailed.title" ),
                    LocalizationManager.get( "notification.uri.modpackAddFailed.body" ) );
        }
    }

    /**
     * Menu action: shows the application "About" dialog.
     */
    private static void showAboutDialog()
    {
        GUIUtilities.JFXPlatformRun( () -> {
            Alert about = new Alert( Alert.AlertType.INFORMATION );
            about.setTitle( LocalizationManager.format( "dialog.about.title",
                                                        LauncherConstants.LAUNCHER_APPLICATION_NAME ) );
            about.setHeaderText( LauncherConstants.LAUNCHER_APPLICATION_NAME );
            about.setContentText( LocalizationManager.format( "dialog.about.body",
                                                              LauncherConstants.LAUNCHER_APPLICATION_VERSION ) );
            // Initialize the dialog as a child of the launcher's top stage so it inherits theming
            // and stays modal to the right window.
            javafx.stage.Stage top = MCLauncherGuiController.getTopStageOrNull();
            if ( top != null ) {
                about.initOwner( top );
            }
            about.showAndWait();
        } );
    }
}
