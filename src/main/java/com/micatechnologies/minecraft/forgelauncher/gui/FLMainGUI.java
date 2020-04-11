package com.micatechnologies.minecraft.forgelauncher.gui;


import com.micatechnologies.minecraft.forgelauncher.utilities.Pair;

public class FLMainGUI extends FLGenericGUI {
    @Override
    String getFXMLResourcePath() {
        return "MFLMainUI.fxml";
    }

    @Override
    Pair< Integer, Integer > getWindowSize() {
        return new Pair<>( 600,600 );
    }

    @Override
    void setupWindow() {
    }
}
