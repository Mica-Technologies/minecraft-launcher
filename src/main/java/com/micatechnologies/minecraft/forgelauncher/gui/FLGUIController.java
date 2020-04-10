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

/**
 * Class for managing and controlling GUI components and related functionality.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class FLGUIController {
    private ArrayList< FLGenericGUI > windowList = new ArrayList<>();

    public synchronized void registerWindow( FLGenericGUI window ) {
        windowList.add( window );
    }

    public synchronized void unregisterWindow( FLGenericGUI window ) {
        windowList.remove( window );
    }

    public synchronized int getWindowCount() {
        return windowList.size();
    }
}
