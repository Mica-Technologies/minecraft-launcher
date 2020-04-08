package com.micatechnologies.minecraft.forgelauncher;

import javafx.application.Platform;

public class FLGUIController {
    public static void JFXPlatformRun(Runnable r) {
        try {
            Platform.runLater( r );
        }
        catch (Exception e) {
            Platform.startup( r );
        }
    }
}
