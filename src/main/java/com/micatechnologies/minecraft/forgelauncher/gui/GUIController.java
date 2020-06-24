package com.micatechnologies.minecraft.forgelauncher.gui;

import com.micatechnologies.minecraft.forgelauncher.LauncherApp;
import com.micatechnologies.minecraft.forgelauncher.utilities.GuiUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.objects.Pair;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Class for managing and controlling GUI components and related functionality.
 *
 * @author Mica Technologies
 * @version 1.0
 * @editors hawka97
 * @creator hawka97
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
    private static final ArrayList< AbstractWindow > windowList = new ArrayList<>();

    /**
     * Registers the specified window to the controller window list.
     *
     * @param window window to register
     *
     * @since 1.0
     */
    public synchronized static void registerWindow( AbstractWindow window ) {
        windowList.add( window );
    }

    /**
     * Unregisters the specified window from the controller window list.
     *
     * @param window window to unregister
     *
     * @since 1.0
     */
    public synchronized static void unregisterWindow( AbstractWindow window ) {
        windowList.remove( window );
    }

    /**
     * Performs the specified consumer task for all windows that are registered to the controller.
     *
     * @param task task to perform for all windows
     *
     * @since 1.0
     */
    public synchronized static void doForAllWindows( Consumer< AbstractWindow > task ) {
        GuiUtils.JFXPlatformRun( () -> windowList.forEach( task ) );
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
     * Closes all windows that are registered to the controller.
     *
     * @since 1.0
     */
    public synchronized static void closeAllWindows() {
        doForAllWindows( AbstractWindow::close );
    }

    /**
     * Refreshes the window configuration of all windows that are registered to the controller.
     *
     * @since 1.0
     */
    public synchronized static void refreshWindowConfiguration() {
        // Set window resize mode
        GuiUtils.JFXPlatformRun( () -> doForAllWindows( flGenericGUI -> flGenericGUI.getCurrentJFXStage().setResizable(
                LauncherApp.getLauncherConfig().getResizableguis() ) ) );
    }

    /**
     * Gets the top most stage of windows that are registered to the controller. If no registered windows have focus,
     * the stage of any registered window may be returned.
     *
     * @return window stage
     *
     * @since 1.0
     */
    public synchronized static Stage getTopStageOrNull() {
        Stage getStage = null;
        for ( AbstractWindow gui : windowList ) {
            getStage = gui.getCurrentJFXStage();
            if ( gui.getCurrentJFXStage().isFocused() ) {
                break;
            }
        }
        return getStage;
    }
}
