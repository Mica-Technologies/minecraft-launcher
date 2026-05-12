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
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.utilities.AnnouncementManager;
import com.micatechnologies.minecraft.launcher.utilities.LauncherUriHandler;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import javafx.scene.control.Alert;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.Pane;
import org.apache.commons.lang3.SystemUtils;

import java.awt.Desktop;
import java.awt.desktop.QuitStrategy;
import java.io.IOException;
import java.net.URI;

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
            }
            catch ( Exception | Error e ) {
                Logger.logWarningSilent( "Unable to install OPEN_URI handler: " + e.getMessage() );
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
            Logger.logWarningSilent( "Unable to install macOS Desktop handlers: " + e.getMessage() );
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
    }

    // =========================================================================================
    //  Menu construction
    // =========================================================================================

    private static MenuBar buildMenuBar()
    {
        MenuBar bar = new MenuBar();
        bar.getMenus().addAll( buildModpacksMenu(), buildGameMenu(), buildHelpMenu() );
        return bar;
    }

    private static Menu buildModpacksMenu()
    {
        Menu menu = new Menu( "Browse" );

        // "Open Browse" routes to the install + manage screen — formerly "Library",
        // renamed so the main menu's wordmark area can use "Library" as the screen
        // name for the home/installed-packs surface.
        MenuItem library = new MenuItem( "Open Browse…" );
        library.setAccelerator( new KeyCodeCombination( KeyCode.L, KeyCombination.SHORTCUT_DOWN ) );
        library.setOnAction( e -> openLibrary() );

        MenuItem refresh = new MenuItem( "Refresh Pack List" );
        refresh.setAccelerator( new KeyCodeCombination( KeyCode.R, KeyCombination.SHORTCUT_DOWN ) );
        refresh.setOnAction( e -> refreshMain() );

        menu.getItems().addAll( library, new SeparatorMenuItem(), refresh );
        return menu;
    }

    private static Menu buildGameMenu()
    {
        Menu menu = new Menu( "Game" );

        MenuItem runtimes = new MenuItem( "Runtime Management…" );
        runtimes.setOnAction( e -> openRuntime() );

        menu.getItems().add( runtimes );
        return menu;
    }

    private static Menu buildHelpMenu()
    {
        Menu menu = new Menu( "Help" );

        MenuItem help = new MenuItem( "Mica Minecraft Help" );
        // Cmd+? is the macOS convention for app help. SLASH + SHIFT_DOWN produces "?".
        help.setAccelerator( new KeyCodeCombination( KeyCode.SLASH,
                                                     KeyCombination.SHORTCUT_DOWN,
                                                     KeyCombination.SHIFT_DOWN ) );
        help.setOnAction( e -> MCLauncherHelpWindow.show( HelpTopic.GETTING_STARTED ) );

        MenuItem website = new MenuItem( "Visit Mica Technologies" );
        website.setOnAction( e -> openUrl( "https://micatechnologies.com" ) );

        menu.getItems().addAll( help, website );
        return menu;
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
                Logger.logError( "Unable to open Settings from system menu." );
                Logger.logThrowable( e );
            }
        } );
    }

    private static void openLibrary()
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToGameLibraryGui();
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to open Library from system menu." );
                Logger.logThrowable( e );
            }
        } );
    }

    private static void openRuntime()
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToRuntimeGui();
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to open Runtime Management from system menu." );
                Logger.logThrowable( e );
            }
        } );
    }

    private static void refreshMain()
    {
        SystemUtilities.spawnNewTask( () -> {
            AnnouncementManager.checkAnnouncements();
            GameModPackManager.fetchModPackInfo();
            try {
                MCLauncherGuiController.goToMainGui();
            }
            catch ( Exception e ) {
                Logger.logError( "Unable to refresh main GUI from system menu." );
                Logger.logThrowable( e );
            }
        } );
    }

    private static void openUrl( String url )
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                if ( Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported( Desktop.Action.BROWSE ) ) {
                    Desktop.getDesktop().browse( URI.create( url ) );
                }
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to open URL: " + url );
                Logger.logThrowable( e );
            }
        } );
    }

    private static void showAboutDialog()
    {
        GUIUtilities.JFXPlatformRun( () -> {
            Alert about = new Alert( Alert.AlertType.INFORMATION );
            about.setTitle( "About " + LauncherConstants.LAUNCHER_APPLICATION_NAME );
            about.setHeaderText( LauncherConstants.LAUNCHER_APPLICATION_NAME );
            about.setContentText( "Version " + LauncherConstants.LAUNCHER_APPLICATION_VERSION + "\n" +
                                  "© 2021–2026 Mica Technologies\n" +
                                  "https://micatechnologies.com" );
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
