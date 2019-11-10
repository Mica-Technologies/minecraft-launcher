package com.micatechnologies.minecraft.forgelauncher;

import com.gluonhq.charm.glisten.control.Avatar;
import com.gluonhq.charm.glisten.control.ProgressBar;
import java.io.IOException;
import java.net.URL;
import java.util.Objects;
import java.util.ResourceBundle;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class LauncherProgressGUI extends Application implements Runnable, Initializable {

    @FXML
    public Label       upperText;

    @FXML
    public Label       lowerText;

    @FXML
    public ProgressBar progressBar;

    @FXML
    public Avatar      userIcon;

    public void setUpperText( String text ) {
        Platform.runLater( () -> upperText.setText( text ) );
    }

    public void setLowerText( String text ) {
        Platform.runLater( () -> lowerText.setText( text ) );
    }

    public void setProgressBarProgress( double progress ) {
        Platform.runLater( () -> progressBar.setProgress( progress ) );
    }

    public void setUserIcon( Image icon ) {
        Platform.runLater( () -> userIcon.setImage( icon ) );
    }

    public synchronized boolean isLoaded() {
        return loaded;
    }

    public static void main( String[] args ) {
        launch( args );
    }

    @Override
    public void start( Stage primaryStage ) throws IOException {
        // Get FXML File
        Parent root = FXMLLoader.load( Objects.requireNonNull(
            getClass().getClassLoader().getResource( "LauncherProgressGUI.fxml" ) ) );

        // Configure Window
        primaryStage.setTitle( "Loading - " + LauncherConstants.LAUNCHER_TITLE );
        primaryStage.setScene( new Scene( root, 645, 424 ) );

        // Show Window
        primaryStage.show();

        // Set done loading flag
        synchronized ( this ) {
            loaded = true;
        }
    }

    private boolean loaded = false;

    @Override
    public void run() {
        Application.launch();
        initialize( null, null );
    }

    @Override
    public void initialize( final URL url, final ResourceBundle resourceBundle ) {
        // Set default GUI options
        upperText.setText( "Loading, please wait..." );
        lowerText.setText( "--" );
        progressBar.setProgress( ProgressBar.INDETERMINATE_PROGRESS );
        userIcon.setImage( new Image( "no_user.png" ) );

        // Run start
    }
}
