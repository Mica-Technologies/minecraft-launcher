package com.micatechnologies.minecraft.forgelauncher.gui;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.Region;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Class for managing and controlling GUI components and related functionality.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class FLGUIController {
    private static final ArrayList< FLGenericGUI > windowList = new ArrayList<>();

    public synchronized static void registerWindow( FLGenericGUI window ) {
        windowList.add( window );
    }

    public synchronized static void unregisterWindow( FLGenericGUI window ) {
        windowList.remove( window );
    }

    public synchronized static int getWindowCount() {
        return windowList.size();
    }

    public synchronized static void doForAllWindows( Consumer< FLGenericGUI > task ) {
        windowList.forEach( task );
    }

    public synchronized static void closeAllWindows() {
        doForAllWindows( FLGenericGUI::close );
    }
}
