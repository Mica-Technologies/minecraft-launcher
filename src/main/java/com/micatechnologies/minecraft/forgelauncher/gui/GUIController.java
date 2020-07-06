/*
 * Copyright (c) 2020 Mica Technologies
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

package com.micatechnologies.minecraft.forgelauncher.gui;

import com.micatechnologies.minecraft.forgelauncher.config.ConfigManager;
import com.micatechnologies.minecraft.forgelauncher.utilities.GUIUtilities;
import com.micatechnologies.minecraft.forgelauncher.utilities.objects.Pair;
import javafx.stage.Stage;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Class for managing and controlling GUI components and related functionality.
 *
 * @author Mica Technologies
 * @version 1.0
 * @editors hawka97
 * @creator hawka97
 * @since 2.0
 */
public class GUIController
{
    /**
     * The X value of the most recently moved window. This value is stored to allow for windows to open near each
     * other.
     *
     * @since 1.0
     */
    private static double lastCustomX = -1;

    /**
     * The Y value of the most recently moved window. This value is stored to allow for windows to open near each
     * other.
     *
     * @since 1.0
     */
    private static double lastCustomY = -1;

    /**
     * List of windows that have been registered using {@link #registerWindow(AbstractWindow)}.
     *
     * @since 1.0
     */
    private static final List< AbstractWindow > windowList = Collections.synchronizedList( new ArrayList<>() );

    /**
     * Registers the specified window to the controller window list.
     *
     * @param window window to register
     *
     * @since 1.0
     */
    public static void registerWindow( AbstractWindow window ) {
        windowList.add( window );
    }

    /**
     * Unregisters the specified window from the controller window list.
     *
     * @param window window to unregister
     *
     * @since 1.0
     */
    public static void unregisterWindow( AbstractWindow window ) {
        windowList.remove( window );
    }

    /**
     * Performs the specified consumer task for all windows that are registered to the controller.
     *
     * @param task task to perform for all windows
     *
     * @since 1.0
     */
    public static void doForAllWindows( Consumer< AbstractWindow > task ) {
        GUIUtilities.JFXPlatformRun( () -> windowList.forEach( task ) );
    }

    public static void closeAllWindows() {
        List< AbstractWindow > abstractWindowList = new ArrayList<>( windowList );
        for ( AbstractWindow window : abstractWindowList ) {
            window.close();
        }
    }

    /**
     * Sets the stored X and Y coordinate values for the last moved window location.
     *
     * @param x window X coordinate
     * @param y window Y coordinate
     *
     * @since 1.0
     */
    public static void setCustomWindowLocations( double x, double y ) {
        lastCustomX = x;
        lastCustomY = y;
    }

    /**
     * Gets the last moved window X and Y coordinates as a pair.
     *
     * @return last moved window coordinates
     *
     * @since 1.0
     */
    public static Pair< Double, Double > getCustomWindowLocation() {
        return new Pair<>( lastCustomX, lastCustomY );
    }

    /**
     * Refreshes the window configuration of all windows that are registered to the controller.
     *
     * @since 1.0
     */
    public static void refreshWindowConfiguration() {
        // Set window resize mode
        GUIUtilities
                .JFXPlatformRun( () -> doForAllWindows( flGenericGUI -> flGenericGUI.getCurrentJFXStage().setResizable(
                        ConfigManager.getResizableWindows() ) ) );
    }

    /**
     * Gets the top most stage of windows that are registered to the controller. If no registered windows have focus,
     * the stage of any registered window may be returned.
     *
     * @return window stage
     *
     * @since 1.0
     */
    public static Stage getTopStageOrNull() {
        Stage getStage = null;
        for ( AbstractWindow gui : windowList ) {
            getStage = gui.getCurrentJFXStage();
            if ( gui.getCurrentJFXStage().isFocused() ) {
                break;
            }
        }
        return getStage;
    }

    /**
     * Returns a boolean indicating if the appliciation in its current state should use graphical user interfaces.
     *
     * @return true if GUIs should be used
     *
     * @since 1.0
     */
    public static boolean shouldCreateGui() {
        return !GraphicsEnvironment.isHeadless();
    }
}
