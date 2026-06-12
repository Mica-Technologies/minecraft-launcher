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

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.LauncherCore;
import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.gui.GUIUtilities;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiController;
import javafx.application.Platform;

import java.awt.Desktop;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Shared action surface for the cross-platform menus the launcher hangs off the OS shell —
 * the system-tray right-click menu (Win / Linux KDE) and the macOS dock menu. Both have
 * exactly the same set of items, so the menu structure and action handlers live here
 * once.
 *
 * <p>Each action is safe to invoke from any thread; UI-touching work is wrapped in
 * {@link GUIUtilities#JFXPlatformRun} and long-running work is spawned via
 * {@link SystemUtilities#spawnNewTask} internally.</p>
 */
public final class LauncherActions
{
    /** Max packs listed in the dock "Play Recent" submenu. */
    private static final int RECENT_MAX = 8;

    private LauncherActions() { /* static-only */ }

    // =========================================================================================
    //  Menu factory
    // =========================================================================================

    /**
     * Builds a fresh AWT {@link PopupMenu} populated with the standard launcher actions:
     * Show / Play Last / Open Last Mods Folder / Quit. Each call returns a new menu instance;
     * AWT's API requires a distinct menu per attachment point (one for the tray icon, one
     * for the dock).
     */
    public static PopupMenu buildSharedMenu()
    {
        PopupMenu menu = new PopupMenu();

        MenuItem show = new MenuItem( LocalizationManager.format(
                "menu.dock.show", LauncherConstants.LAUNCHER_APPLICATION_NAME ) );
        show.addActionListener( e -> showLauncher() );

        MenuItem playLast = new MenuItem( LocalizationManager.get( "menu.dock.playLast" ) );
        playLast.addActionListener( e -> playLastModpack() );

        MenuItem openMods = new MenuItem( LocalizationManager.get( "menu.dock.openMods" ) );
        openMods.addActionListener( e -> openLastModsFolder() );

        MenuItem quit = new MenuItem( LocalizationManager.format(
                "menu.dock.quit", LauncherConstants.LAUNCHER_APPLICATION_NAME ) );
        quit.addActionListener( e -> LauncherCore.closeApp() );

        menu.add( show );
        menu.addSeparator();
        menu.add( playLast );
        menu.add( openMods );
        menu.addSeparator();
        menu.add( quit );
        return menu;
    }

    /**
     * Builds the macOS dock right-click menu — a superset of {@link #buildSharedMenu()} that
     * swaps the single "Play Last" entry for a "Play Recent" submenu of the user's recent packs
     * (newest first, capped at {@value #RECENT_MAX}) and adds quick navigation to Browse and
     * Settings. {@link JumpListManager#refresh()} rebuilds + reinstalls this on each launch so
     * the recents stay current — AWT's {@link PopupMenu} has no on-show hook to refresh lazily.
     */
    public static PopupMenu buildDockMenu()
    {
        PopupMenu menu = new PopupMenu();

        MenuItem show = new MenuItem( LocalizationManager.format(
                "menu.dock.show", LauncherConstants.LAUNCHER_APPLICATION_NAME ) );
        show.addActionListener( e -> showLauncher() );

        Menu recent = new Menu( LocalizationManager.get( "menu.modpacks.playRecent" ) );
        List< GameModPack > recents = recentModpacks();
        if ( recents.isEmpty() ) {
            MenuItem none = new MenuItem( LocalizationManager.get( "menu.modpacks.playRecent.empty" ) );
            none.setEnabled( false );
            recent.add( none );
        }
        else {
            for ( GameModPack pack : recents ) {
                MenuItem item = new MenuItem( pack.getFriendlyName() );
                item.addActionListener( e -> playModpack( pack ) );
                recent.add( item );
            }
        }

        MenuItem openMods = new MenuItem( LocalizationManager.get( "menu.dock.openMods" ) );
        openMods.addActionListener( e -> openLastModsFolder() );

        MenuItem browse = new MenuItem( LocalizationManager.get( "menu.modpacks.browse" ) );
        browse.addActionListener( e -> openBrowse() );

        MenuItem settings = new MenuItem( LocalizationManager.get( "main.navbar.settings" ) );
        settings.addActionListener( e -> openSettings() );

        MenuItem quit = new MenuItem( LocalizationManager.format(
                "menu.dock.quit", LauncherConstants.LAUNCHER_APPLICATION_NAME ) );
        quit.addActionListener( e -> LauncherCore.closeApp() );

        menu.add( show );
        menu.addSeparator();
        menu.add( recent );
        menu.add( openMods );
        menu.addSeparator();
        menu.add( browse );
        menu.add( settings );
        menu.addSeparator();
        menu.add( quit );
        return menu;
    }

    // =========================================================================================
    //  Actions
    // =========================================================================================

    /** Bring the launcher window to focus. Click-through from tray double-click, dock click,
     *  and the "Show launcher" menu item. */
    public static void showLauncher()
    {
        GUIUtilities.JFXPlatformRun( MCLauncherGuiController::requestFocus );
    }

    /** Launch the user's most recent modpack with the same flow as the in-window hero card's
     *  Play button. Warns via toast if no last-played pack is known. */
    public static void playLastModpack()
    {
        GameModPack pack = lastPlayedModpack();
        if ( pack == null ) {
            NotificationManager.warn( LocalizationManager.get( "notification.shell.noRecentModpackPlay.title" ),
                                      LocalizationManager.get( "notification.shell.noRecentModpackPlay.body" ) );
            return;
        }
        playModpack( pack );
    }

    /** Launches a specific modpack with the same flow as {@link #playLastModpack()} — used by
     *  the dock "Play Recent" submenu. Records it as the last-selected pack, sets Discord
     *  presence, and returns focus to the main GUI when the launch hands off. No-op for null. */
    public static void playModpack( GameModPack pack )
    {
        if ( pack == null ) {
            return;
        }
        ConfigManager.setLastModPackSelected( pack.getPackName() );
        SystemUtilities.spawnNewTask( () -> {
            Platform.setImplicitExit( false );
            SystemUtilities.spawnNewTask( () -> DiscordRpcUtility.setGamePresence( pack ) );
            LauncherCore.play( pack, () -> GUIUtilities.JFXPlatformRun( () -> {
                try {
                    Objects.requireNonNull( MCLauncherGuiController.getTopStageOrNull() ).show();
                    MCLauncherGuiController.goToMainGui();
                    MCLauncherGuiController.requestFocus();
                }
                catch ( Exception ex ) {
                    Logger.logError( LocalizationManager.get( "log.launcherActions.returnMainGuiFailed" ) );
                    Logger.logThrowable( ex );
                }
            } ) );
        } );
    }

    /** Opens the Browse (install / manage) screen. Used by the dock menu. */
    public static void openBrowse()
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToGameLibraryGui();
            }
            catch ( Exception ex ) {
                Logger.logError( LocalizationManager.get( "log.launcherActions.openBrowseFailed" ) );
                Logger.logThrowable( ex );
            }
        } );
    }

    /** Opens the Settings screen. Used by the dock menu. */
    public static void openSettings()
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToSettingsGui();
            }
            catch ( Exception ex ) {
                Logger.logError( LocalizationManager.get( "log.launcherActions.openSettingsFailed" ) );
                Logger.logThrowable( ex );
            }
        } );
    }

    /** Opens the help window at the getting-started topic. Used by the macOS native toolbar. */
    public static void openHelp()
    {
        GUIUtilities.JFXPlatformRun( () -> {
            try {
                com.micatechnologies.minecraft.launcher.gui.MCLauncherHelpWindow.show(
                        com.micatechnologies.minecraft.launcher.gui.HelpTopic.GETTING_STARTED );
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.launcherActions.openHelpFailed", t.getMessage() ) );
            }
        } );
    }

    /** Open the last-played modpack's mods folder in the OS file manager. Creates the
     *  folder if it doesn't exist (consistent with the in-window context-menu equivalent).
     *  Warns via toast if no last-played pack is known. */
    public static void openLastModsFolder()
    {
        GameModPack pack = lastPlayedModpack();
        if ( pack == null ) {
            NotificationManager.warn( LocalizationManager.get( "notification.shell.noRecentModpackMods.title" ),
                                      LocalizationManager.get( "notification.shell.noRecentModpackMods.body" ) );
            return;
        }
        SystemUtilities.spawnNewTask( () -> {
            try {
                File folder = new File( pack.getPackRootFolder() + File.separator + "mods" );
                if ( !folder.exists() ) {
                    folder.mkdirs();
                }
                Desktop.getDesktop().open( folder );
            }
            catch ( Exception ex ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.launcherActions.openModsFolderFailed", ex.getMessage() ) );
            }
        } );
    }

    // =========================================================================================
    //  Helpers
    // =========================================================================================

    /** Resolves the {@link GameModPack} for {@link ConfigManager#getLastModPackSelected()} —
     *  tries pack name first, then friendly name. Returns {@code null} if the user hasn't
     *  played anything yet or the saved pack is no longer installed. */
    /** Installed packs that have been played at least once, newest first, capped at
     *  {@link #RECENT_MAX} — the dock "Play Recent" submenu source. getLastPlayedMs caches
     *  its history read, so this is cheap on the worker thread JumpListManager runs it on. */
    private static List< GameModPack > recentModpacks()
    {
        return GameModPackManager.getInstalledModPacks().stream()
                .filter( p -> p != null && !p.isNeverPlayed() )
                .sorted( Comparator.comparingLong( GameModPack::getLastPlayedMs ).reversed() )
                .limit( RECENT_MAX )
                .collect( Collectors.toList() );
    }

    private static GameModPack lastPlayedModpack()
    {
        String lastName = ConfigManager.getLastModPackSelected();
        if ( lastName == null || lastName.isBlank() ) {
            return null;
        }
        GameModPack pack = GameModPackManager.getInstalledModPackByName( lastName );
        if ( pack != null ) {
            return pack;
        }
        for ( GameModPack p : GameModPackManager.getInstalledModPacks() ) {
            if ( lastName.equals( p.getFriendlyName() ) ) {
                return p;
            }
        }
        return null;
    }
}
