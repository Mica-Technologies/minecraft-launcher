package com.micatechnologies.minecraft.forgelauncher;

import java.util.concurrent.CountDownLatch;
import javafx.application.Application;
import javafx.stage.Stage;

public class LauncherModpackGUI extends Application {


    private Stage          currStage  = null;

    public  CountDownLatch readyLatch = new CountDownLatch( 1 );

    public static void main( String[] args ) {
        launch( args );
    }

    @Override
    public void start( Stage primaryStage ) {

    }
}
