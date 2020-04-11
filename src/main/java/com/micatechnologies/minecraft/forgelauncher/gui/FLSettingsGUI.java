package com.micatechnologies.minecraft.forgelauncher.gui;


import com.micatechnologies.minecraft.forgelauncher.utilities.Pair;

public class FLSettingsGUI extends FLGenericGUI {
    @Override
    String getFXMLResourcePath() {
        return "LauncherSettingsGUI.fxml";
    }

    @Override
    Pair< Integer, Integer > getWindowSize() {
        return new Pair<>( 600,600 );
    }

    @Override
    void setupWindow() {
    }
}
