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

import javax.imageio.ImageIO;
import java.awt.Desktop;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.File;
import java.io.InputStream;

/**
 * Cross-platform native notification sink. Routes calls to the host OS notification system via
 * {@link java.awt.SystemTray} / {@link TrayIcon#displayMessage}:
 *
 * <ul>
 *     <li><b>Windows 10/11:</b> toast notifications via the Action Center, grouped under the
 *         launcher's AppUserModelID (set in {@code LauncherCore.applySystemProperties}).</li>
 *     <li><b>macOS:</b> Java AWT routes {@code displayMessage} through Notification Center.</li>
 *     <li><b>Linux:</b> works on KDE Plasma and any DE exposing the legacy
 *         {@code SystemTray} D-Bus protocol. Modern GNOME/Wayland returns
 *         {@code SystemTray.isSupported() == false} and we transparently fall back to
 *         a log line — no error, no missing-notification crash.</li>
 * </ul>
 *
 * <p>The tray icon is created lazily on the first notification and kept alive for the rest of
 * the session. Add/remove around each notification would race on some platforms — Windows
 * specifically can drop the balloon when {@code Shell_NotifyIcon(NIM_DELETE)} fires immediately
 * after {@code NIM_MODIFY}. A persistent icon also lays groundwork for Tier 2 "system tray
 * with quick-play menu" work without breaking the call sites here.</p>
 *
 * <p>Released exactly once in {@link com.micatechnologies.minecraft.launcher.LauncherCore#cleanupApp()}
 * via {@link #shutdown()}.</p>
 */
public final class NotificationManager
{
    private static volatile TrayIcon trayIcon    = null;
    private static volatile boolean  initialized = false;
    private static volatile boolean  disabled    = false;

    private NotificationManager() { /* static-only */ }

    /** Neutral informational toast. */
    public static void info( String title, String body ) {
        display( title, body, TrayIcon.MessageType.INFO );
    }

    /** Positive outcome ("Modpack ready", "Login successful"). Visually identical to {@link #info} on
     *  most platforms — the OS doesn't distinguish "success" from "info" at the API level. */
    public static void success( String title, String body ) {
        display( title, body, TrayIcon.MessageType.INFO );
    }

    /** Yellow / amber warning. */
    public static void warn( String title, String body ) {
        display( title, body, TrayIcon.MessageType.WARNING );
    }

    /** Red error toast. */
    public static void error( String title, String body ) {
        display( title, body, TrayIcon.MessageType.ERROR );
    }

    private static synchronized void display( String title, String body, TrayIcon.MessageType type )
    {
        if ( disabled ) {
            logFallback( title, body );
            return;
        }
        if ( !ensureInitialized() ) {
            logFallback( title, body );
            return;
        }
        try {
            trayIcon.displayMessage( title, body, type );
        }
        catch ( Exception | Error e ) {
            Logger.logWarningSilent( "Notification display failed: " + e.getMessage() );
            logFallback( title, body );
        }
    }

    /** Always log notifications too — gives a paper trail when the user reports "I missed a toast"
     *  and is the only path on platforms where {@code SystemTray} isn't available. */
    private static void logFallback( String title, String body )
    {
        Logger.logStd( "[notify] " + title + ( body == null || body.isBlank() ? "" : " — " + body ) );
    }

    /** Lazy first-use init. Returns true if {@link #trayIcon} is usable. Subsequent calls are cheap
     *  bool checks on {@link #initialized}. */
    private static boolean ensureInitialized()
    {
        if ( initialized ) {
            return trayIcon != null;
        }
        initialized = true;

        if ( !SystemTray.isSupported() ) {
            disabled = true;
            Logger.logDebug( "System tray not supported on this platform — notifications will log only." );
            return false;
        }

        try {
            Image image = loadTrayImage();
            if ( image == null ) {
                Logger.logWarningSilent( "Notification tray image missing from resources; disabling notifications." );
                disabled = true;
                return false;
            }

            trayIcon = new TrayIcon( image, LauncherConstants.LAUNCHER_APPLICATION_NAME );
            trayIcon.setImageAutoSize( true );

            // Left-click / double-click → focus the launcher window.
            trayIcon.addActionListener( e ->
                GUIUtilities.JFXPlatformRun( MCLauncherGuiController::requestFocus ) );

            // Right-click → popup menu with the common cross-app actions. We rebuild this once
            // at icon-creation time rather than on every right-click because AWT PopupMenu doesn't
            // expose an on-show hook; items reflect "no last modpack" via toasts when fired
            // rather than via greyed-out items.
            trayIcon.setPopupMenu( buildTrayPopupMenu() );

            SystemTray.getSystemTray().add( trayIcon );
            return true;
        }
        catch ( Exception | Error e ) {
            Logger.logWarningSilent( "Unable to initialize system tray icon: " + e.getMessage() );
            trayIcon = null;
            disabled = true;
            return false;
        }
    }

    /** Loads {@code micaminecraftlauncher.png} from the classpath as an AWT Image. Returns
     *  {@code null} on any failure — callers downgrade to log fallback. */
    private static Image loadTrayImage()
    {
        try ( InputStream is = NotificationManager.class.getClassLoader()
                                                       .getResourceAsStream( "micaminecraftlauncher.png" ) ) {
            if ( is == null ) {
                return null;
            }
            return ImageIO.read( is );
        }
        catch ( Exception e ) {
            return null;
        }
    }

    // =========================================================================================
    //  Tray right-click menu
    // =========================================================================================

    /**
     * Builds the AWT {@link PopupMenu} that hangs off the tray icon. Items mirror the most
     * common cross-app actions a user would reach for without having to focus the launcher
     * window first: bring it to focus, replay their last modpack, jump to the last pack's
     * mods folder, or quit cleanly.
     *
     * <p>AWT {@code PopupMenu} doesn't surface an on-show event, so we can't disable items
     * based on whether a last-played pack exists. Instead, the action handlers themselves
     * emit a {@link #warn(String, String) warn toast} when invoked without a pack — feels
     * the same in practice and avoids rebuilding the menu on every right-click.</p>
     */
    private static PopupMenu buildTrayPopupMenu()
    {
        PopupMenu menu = new PopupMenu();

        MenuItem show = new MenuItem( "Show " + LauncherConstants.LAUNCHER_APPLICATION_NAME );
        show.addActionListener( e -> GUIUtilities.JFXPlatformRun( MCLauncherGuiController::requestFocus ) );

        MenuItem playLast = new MenuItem( "Play Last Modpack" );
        playLast.addActionListener( e -> trayPlayLast() );

        MenuItem openMods = new MenuItem( "Open Last Pack's Mods Folder" );
        openMods.addActionListener( e -> trayOpenLastModsFolder() );

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

    /** Tray "Play Last Modpack" handler. Same launch flow as the in-window hero card's Play
     *  button — sets implicit-exit-false so the launcher can hide during the game, kicks off
     *  the play pipeline, and returns to the main GUI on game exit. Warning toast if there's
     *  no last-played pack to launch. */
    private static void trayPlayLast()
    {
        GameModPack pack = lastPlayedModpack();
        if ( pack == null ) {
            warn( "No recent modpack",
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
                    java.util.Objects.requireNonNull( MCLauncherGuiController.getTopStageOrNull() ).show();
                    MCLauncherGuiController.goToMainGui();
                    MCLauncherGuiController.requestFocus();
                }
                catch ( Exception ex ) {
                    Logger.logError( "Unable to return to main GUI after tray Play Last." );
                    Logger.logThrowable( ex );
                }
            } ) );
        } );
    }

    /** Tray "Open Last Pack's Mods Folder" handler. Creates the folder if it doesn't exist yet
     *  (consistent with the in-window context-menu equivalent). Warning toast on no last pack. */
    private static void trayOpenLastModsFolder()
    {
        GameModPack pack = lastPlayedModpack();
        if ( pack == null ) {
            warn( "No recent modpack",
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
                Logger.logWarningSilent( "Unable to open mods folder from tray: " + ex.getMessage() );
            }
        } );
    }

    /** Removes the tray icon and resets state. Call exactly once at app exit. Idempotent. */
    public static synchronized void shutdown()
    {
        if ( trayIcon != null ) {
            try {
                SystemTray.getSystemTray().remove( trayIcon );
            }
            catch ( Exception | Error ignored ) { /* best-effort */ }
            trayIcon = null;
        }
        initialized = false;
        disabled = false;
    }
}
