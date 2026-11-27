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

import com.micatechnologies.minecraft.launcher.steam.SteamProcess;
import com.micatechnologies.minecraft.launcher.utilities.StartupDiagnostics;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/**
 * Under gamescope (the Steam Deck's Game Mode), makes the launcher's windows fill the screen.
 *
 * <p>gamescope shows one window at a time, scaled to the screen with its shape kept, and has no
 * window manager to maximize anything. A window that isn't the screen's shape got black bars at
 * its sides. So there the main window and every resizable window opened after it are set to the
 * screen's bounds as they are shown. Fixed-size dialogs keep their size: stretched, their few
 * controls would sit in a corner of an empty screen.
 *
 * @since 2026.11
 */
public final class GamescopeWindows
{
    /** Marks a window already set up, so it isn't set up twice. */
    private static final String FILLED_KEY = "mmcl.gamescope.filled";

    private static Boolean active;

    private static boolean installed;

    private GamescopeWindows() { /* static-only */ }

    /**
     * Whether the launcher runs under gamescope. Read once.
     *
     * @return {@code true} under gamescope
     *
     * @since 2026.11
     */
    public static synchronized boolean active()
    {
        if ( active == null ) {
            active = SteamProcess.underGamescope( System.getenv() );
        }
        return active;
    }

    /**
     * Under gamescope, fills the screen with {@code main} and with every resizable window shown
     * from now on. Does nothing elsewhere. Call on the FX thread before {@code main} is shown.
     *
     * @param main the launcher's main window
     *
     * @since 2026.11
     */
    public static synchronized void install( Stage main )
    {
        if ( !active() ) {
            return;
        }
        fill( main );
        if ( installed ) {
            return;
        }
        installed = true;
        Rectangle2D screen = Screen.getPrimary().getBounds();
        StartupDiagnostics.record( "Running under gamescope (Steam Game Mode): windows fill the "
                                   + (int) screen.getWidth() + "x" + (int) screen.getHeight() + " screen" );
        Window.getWindows().addListener( ( ListChangeListener< Window > ) change -> {
            while ( change.next() ) {
                for ( Window window : change.getAddedSubList() ) {
                    if ( window instanceof Stage stage && stage.isResizable() ) {
                        fill( stage );
                    }
                }
            }
        } );
    }

    /** Sets the stage to the screen's bounds now, once it is shown (showing can re-center or
     *  re-size it) and once more on the next pulse. */
    private static void fill( Stage stage )
    {
        if ( stage.getProperties().putIfAbsent( FILLED_KEY, Boolean.TRUE ) != null ) {
            return;
        }
        toScreen( stage );
        stage.addEventHandler( WindowEvent.WINDOW_SHOWN, event -> {
            toScreen( stage );
            Platform.runLater( () -> toScreen( stage ) );
        } );
    }

    private static void toScreen( Stage stage )
    {
        Rectangle2D screen = Screen.getPrimary().getBounds();
        stage.setX( screen.getMinX() );
        stage.setY( screen.getMinY() );
        stage.setWidth( screen.getWidth() );
        stage.setHeight( screen.getHeight() );
    }
}
