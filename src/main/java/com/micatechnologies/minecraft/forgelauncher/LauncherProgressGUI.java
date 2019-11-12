package com.micatechnologies.minecraft.forgelauncher;

import com.gluonhq.charm.glisten.control.Avatar;
import com.gluonhq.charm.glisten.control.ProgressBar;
import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class LauncherProgressGUI extends Application implements Initializable {

    @FXML
    public  Label          upperText;

    @FXML
    public  Label          lowerText;

    @FXML
    public  ProgressBar    progressBar;

    @FXML
    public  Avatar         userIcon;

    private Stage          currStage  = null;

    public  CountDownLatch readyLatch = new CountDownLatch( 1 );

    public static void main( String[] args ) {
        launch( args );
    }

    public Stage getCurrStage() {
        return currStage;
    }

    @Override
    public void start( Stage primaryStage ) throws IOException {
        // Get FXML File
        FXMLLoader fxmlLoader = new FXMLLoader();
        fxmlLoader.setLocation(
            getClass().getClassLoader().getResource( "LauncherProgressGUI.fxml" ) );
        fxmlLoader.setController( this );
        AnchorPane pane = fxmlLoader.load();

        // Configure Window
        primaryStage.setTitle( "Loading - " + LauncherConstants.LAUNCHER_TITLE );
        primaryStage.setScene( new Scene( pane, 645, 424 ) );
        primaryStage.initStyle( StageStyle.UNIFIED );

        // Show Window
        currStage = primaryStage;
        primaryStage.show();
        readyLatch.countDown();
    }

    @Override
    public void initialize( final URL url, final ResourceBundle resourceBundle ) {
        // Set default GUI options
        upperText.setText( "Loading, please wait..." );
        lowerText.setText( "--" );
        progressBar.setProgress( ProgressBar.INDETERMINATE_PROGRESS );
        userIcon.setImage( new Image( "no_user.png" ) );
    }
}
