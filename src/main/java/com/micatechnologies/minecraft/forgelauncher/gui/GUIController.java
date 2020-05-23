package com.micatechnologies.minecraft.forgelauncher.gui;

import com.micatechnologies.minecraft.forgelauncher.LauncherApp;
import com.micatechnologies.minecraft.forgelauncher.utilities.GUIUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.Pair;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Class for managing and controlling GUI components and related functionality.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class GUIController {

    private static double lastCustomX = -1;
    private static double lastCustomY = -1;

    private static final ArrayList< GenericGUI > windowList = new ArrayList<>();

    public synchronized static void registerWindow( GenericGUI window ) {
        windowList.add( window );
    }

    public synchronized static void unregisterWindow( GenericGUI window ) {
        windowList.remove( window );
    }

    public synchronized static void doForAllWindows( Consumer< GenericGUI > task ) {
        GUIUtils.JFXPlatformRun( () -> windowList.forEach( task ) );
    }

    public static void setCustomWindowLocations( double x, double y ) {
        lastCustomX = x;
        lastCustomY = y;
    }

    public static Pair< Double, Double > getCustomWindowLocation() {
        return new Pair<>( lastCustomX, lastCustomY );
    }

    public synchronized static void closeAllWindows() {
        doForAllWindows( GenericGUI::close );
    }

    public synchronized static void refreshWindowConfiguration() {
        // Set window resize mode
        GUIUtils.JFXPlatformRun( () -> doForAllWindows( flGenericGUI -> flGenericGUI.getCurrentJFXStage().setResizable( LauncherApp.getLauncherConfig().getResizableguis() ) ) );
    }
}
