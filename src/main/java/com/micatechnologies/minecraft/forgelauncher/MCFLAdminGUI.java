package com.micatechnologies.minecraft.forgelauncher;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXTextField;
import com.micatechnologies.minecraft.forgemodpacklib.MCModpackOSUtils;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Formatter;

/**
 * GUI for admin/helper tools.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class MCFLAdminGUI extends MCFLGenericGUI {

    /**
     * Root window pane
     */
    @FXML
    public AnchorPane rootPane;

    /**
     * Exit button
     */
    @FXML
    public JFXButton exitButton;

    @FXML
    public Label sha1;

    @FXML
    public Label url;

    @FXML
    public JFXButton downHashBtn;

    @FXML
    public JFXTextField startURL;

    /**
     * Handle the creation and initial configuration of GUI controls/elements.
     *
     * @since 1.0
     */
    @Override
    void create( Stage stage ) {
        // Configure exit button
        stage.setOnCloseRequest( event -> {
            new Thread( () -> {
                Platform.setImplicitExit( true );
                System.exit( 0 );
            } ).start();
        } );
        exitButton.setOnAction( event -> getCurrentStage().fireEvent( new WindowEvent( getCurrentStage(), WindowEvent.WINDOW_CLOSE_REQUEST ) ) );

        // Configure download/hash button
        downHashBtn.setOnAction( event -> {
            new Thread( () -> {
                // Get final URL from redirects, etc
                String starting = startURL.getText();
                try {
                    HttpURLConnection connection = null;
                    for ( ; ; ) {
                        URL url = new URL( starting );
                        connection = ( HttpURLConnection ) url.openConnection();
                        connection.setInstanceFollowRedirects( false );
                        String redirectLocation = connection.getHeaderField( "Location" );
                        if ( redirectLocation == null ) break;
                        starting = redirectLocation;
                    }
                    String finalStarting = starting;
                    Platform.runLater( () -> url.setText( finalStarting ) );
                }
                catch ( Exception e ) {
                    MCFLLogger.error( "Unable to process your request for final URL!", -1, getCurrentStage() );
                }

                // Download file and get sha1
                try {
                    File temp = new File( System.getProperty( "user.home" ) + File.separator + ".tempHashFile" );
                    FileUtils.copyURLToFile( new URL( starting ), temp );

                    final MessageDigest messageDigest = MessageDigest.getInstance( "SHA1" );
                    InputStream is = new BufferedInputStream( new FileInputStream( temp ) );
                    final byte[] buffer = new byte[ 1024 ];
                    for ( int read = 0; ( read = is.read( buffer ) ) != -1; ) {
                        messageDigest.update( buffer, 0, read );
                    }

                    Formatter formatter = new Formatter();
                    for ( final byte b : messageDigest.digest() ) {
                        formatter.format( "%02x", b );
                    }

                    sha1.setText( formatter.toString() );
                }
                catch ( Exception e ) {
                    e.printStackTrace();
                    MCFLLogger.error( "Unable to process your request for hash!", -1, getCurrentStage() );
                }
            } ).start();
        } );
    }

    /**
     * Create the FXMLLoader for showing the JavaFX stage
     *
     * @return created FXMLLoader
     *
     * @since 1.0
     */
    @Override
    FXMLLoader getFXMLLoader() {
        FXMLLoader fxmll = new FXMLLoader();
        fxmll.setLocation( getClass().getClassLoader().getResource( "LauncherAdminGUI.fxml" ) );
        fxmll.setController( this );
        return fxmll;
    }

    /**
     * Get the width and height of the JavaFX stage
     *
     * @return [width, height] of JavaFX stage
     *
     * @since 1.0
     */
    @Override
    int[] getSize() {
        return new int[]{ 650, 425 };
    }

    @Override
    void enableLightMode() {
        Platform.runLater( () -> {
            rootPane.setBackground( new Background( new BackgroundFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ), CornerRadii.EMPTY, Insets.EMPTY ) ) );
            url.setTextFill( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
            sha1.setTextFill( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
        } );

    }

    @Override
    void enableDarkMode() {
        Platform.runLater( () -> {
            rootPane.setBackground( new Background( new BackgroundFill( Color.web( MCFLConstants.GUI_DARK_COLOR ), CornerRadii.EMPTY, Insets.EMPTY ) ) );
            url.setTextFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
            sha1.setTextFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
        } );
    }
}
