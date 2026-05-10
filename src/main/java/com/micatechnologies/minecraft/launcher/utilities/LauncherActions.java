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
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.gui.GUIUtilities;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiController;
import javafx.application.Platform;

import java.awt.Desktop;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.io.File;
import java.util.Objects;

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

        MenuItem show = new MenuItem( "Show " + LauncherConstants.LAUNCHER_APPLICATION_NAME );
        show.addActionListener( e -> showLauncher() );

        MenuItem playLast = new MenuItem( "Play Last Modpack" );
        playLast.addActionListener( e -> playLastModpack() );

        MenuItem openMods = new MenuItem( "Open Last Pack's Mods Folder" );
        openMods.addActionListener( e -> openLastModsFolder() );

        MenuItem quit = new MenuItem( "Quit " + LauncherConstants.LAUNCHER_APPLICATION_NAME );
        quit.addActionListener( e -> LauncherCore.closeApp() );

        menu.add( show );
        menu.addSeparator();
        menu.add( playLast );
        menu.add( openMods );
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
            NotificationManager.warn( "No recent modpack",
                                      "Open the launcher and play a modpack at least once to enable Play Last." );
            return;
        }
        ConfigManager.setLastModPackSelected( pack.getPackName() );
        final GameModPack finalPack = pack;
        SystemUtilities.spawnNewTask( () -> {
            Platform.setImplicitExit( false );
            SystemUtilities.spawnNewTask( () ->
                DiscordRpcUtility.setGamePresence( finalPack.getPackName(),
                                                  finalPack.getCustomDiscordRpc() ) );
            LauncherCore.play( finalPack, () -> GUIUtilities.JFXPlatformRun( () -> {
                try {
                    Objects.requireNonNull( MCLauncherGuiController.getTopStageOrNull() ).show();
                    MCLauncherGuiController.goToMainGui();
                    MCLauncherGuiController.requestFocus();
                }
                catch ( Exception ex ) {
                    Logger.logError( "Unable to return to main GUI after shell-menu Play Last." );
                    Logger.logThrowable( ex );
                }
            } ) );
        } );
    }

    /** Open the last-played modpack's mods folder in the OS file manager. Creates the
     *  folder if it doesn't exist (consistent with the in-window context-menu equivalent).
     *  Warns via toast if no last-played pack is known. */
    public static void openLastModsFolder()
    {
        GameModPack pack = lastPlayedModpack();
        if ( pack == null ) {
            NotificationManager.warn( "No recent modpack",
                                      "Play a modpack at least once before opening its mods folder." );
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
                Logger.logWarningSilent( "Unable to open mods folder from shell menu: " + ex.getMessage() );
            }
        } );
    }

    // =========================================================================================
    //  Helpers
    // =========================================================================================

    /** Resolves the {@link GameModPack} for {@link ConfigManager#getLastModPackSelected()} —
     *  tries pack name first, then friendly name. Returns {@code null} if the user hasn't
     *  played anything yet or the saved pack is no longer installed. */
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
