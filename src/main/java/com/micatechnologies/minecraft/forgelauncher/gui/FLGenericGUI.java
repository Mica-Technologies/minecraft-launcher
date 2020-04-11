package com.micatechnologies.minecraft.forgelauncher.gui;

import com.micatechnologies.minecraft.forgelauncher.utilities.Pair;
import javafx.application.Application;
import javafx.stage.Stage;

public class FLGenericGUI extends Application {
    private Stage currentStage = null;

    public void open() {

    }

    public void close() {

    }

    public void show() {

    }

    public void hide() {

    }

    abstract String getFXMLName();

    abstract Pair< Integer, Integer > getWindowSize();

    @Override
    public void start( Stage stage ) throws Exception {

    }
}
