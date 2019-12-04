package com.micatechnologies.minecraft.forgelauncher;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXTextArea;
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
import org.apache.commons.httpclient.util.URIUtil;
import org.apache.commons.io.FileUtils;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
    public JFXTextArea sha1;

    @FXML
    public JFXTextArea url;

    @FXML
    public JFXButton downHashBtn;

    @FXML
    public JFXTextField startURL;

    @FXML
    public Label label1;

    @FXML
    public Label label2;

    @FXML
    public Label label3;

    @FXML
    public Label label4;

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
                event.consume();
                close();
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
                    // Create a new trust manager that trust all certificates
                    TrustManager[] trustAllCerts = new TrustManager[]{
                            new X509TrustManager() {
                                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                                    return null;
                                }

                                public void checkClientTrusted(
                                        java.security.cert.X509Certificate[] certs, String authType ) {
                                }

                                public void checkServerTrusted(
                                        java.security.cert.X509Certificate[] certs, String authType ) {
                                }
                            }
                    };

                    // Activate the new trust manager
                    try {
                        SSLContext sc = SSLContext.getInstance( "SSL" );
                        sc.init( null, trustAllCerts, new java.security.SecureRandom() );
                        HttpsURLConnection.setDefaultSSLSocketFactory( sc.getSocketFactory() );
                    }
                    catch ( Exception e ) {
                    }

                    for ( ; ; ) {
                        starting = starting.replaceAll( "files/", "download/" ) + "/file";
                        URL url = new URL( starting );
                        connection = ( HttpURLConnection ) url.openConnection();
                        connection.addRequestProperty( "authority", "www.curseforge.com" );
                        connection.addRequestProperty( "user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/74.0.3729.169 Safari/537.36" );
                        connection.addRequestProperty( "referer", starting.replaceAll( "/file", "" ) );
                        connection.addRequestProperty( "accept-language", "en-US,en;q=0.9" );
                        connection.addRequestProperty( "cookie", "__cfduid=db772e0b75914f1e55d317a6241049b631575325937; Unique_ID_v2=71f7681abb414685951fda9eb6411240; ResponsiveSwitch.DesktopMode=1; __utmz=94490894.1575325938.1.1.utmcsr=(direct)|utmccn=(direct)|utmcmd=(none); _ga=GA1.2.1223710242.1575325938; _gid=GA1.2.1892128923.1575325938; cdmgeo=us; __gads=ID=71afbed91813359b:T=1575325940:S=ALNI_MY7d5PqYNAFG9YP5cuuKcdlQV0W6A; __cf_bm=f50699c1a4c2e1b5373bf6c95618feb148b273c3-1575332952-1800-Aam8vxdNcmgvag0esrDFcgdX4XWgcGOhJwg0Aazih3wqZtApmyDd/3nzU7wy7tW9mHiqVwHreDVxl6/lPo/eziw=; AWSALB=ayd2ivum2qlVXwXr5FRgSjsBvR6wwvtKLj656X1wvYxhj3FAsb/FyOly0xRkSGlFRV9wWtp658cSjQye899qE7kgvgJegNzO2MdXz2oZ9oGbmydN+mgDkcPkz+gL; __utma=94490894.1223710242.1575325938.1575325938.1575332953.2; __utmc=94490894; __utmt=1; __utmt_b=1; __utmb=94490894.2.10.1575332953" );
                        connection.setRequestMethod( "GET" );
                        connection.setInstanceFollowRedirects( false );
                        String redirectLocation = connection.getHeaderField( "Location" );
                        if ( redirectLocation == null ) break;
                        starting = redirectLocation;
                    }
                    starting = starting.replace( "/file", "" ).replaceAll( " ", "+" ).replaceFirst( "edge", "media" ).replaceFirst( "download", "files" );
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

                    Platform.runLater( () -> sha1.setText( formatter.toString() ) );
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
            url.setStyle( "-fx-text-inner-color: " + MCFLConstants.GUI_DARK_COLOR );
            sha1.setStyle( "-fx-text-inner-color: " + MCFLConstants.GUI_DARK_COLOR );
            startURL.setStyle( "-fx-text-inner-color: " + MCFLConstants.GUI_DARK_COLOR );
            label1.setTextFill( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
            label2.setTextFill( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
            label3.setTextFill( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
            label4.setTextFill( Color.web( MCFLConstants.GUI_DARK_COLOR ) );

        } );

    }

    @Override
    void enableDarkMode() {
        Platform.runLater( () -> {
            rootPane.setBackground( new Background( new BackgroundFill( Color.web( MCFLConstants.GUI_DARK_COLOR ), CornerRadii.EMPTY, Insets.EMPTY ) ) );
            url.setStyle( "-fx-text-inner-color: " + MCFLConstants.GUI_LIGHT_COLOR );
            sha1.setStyle( "-fx-text-inner-color: " + MCFLConstants.GUI_LIGHT_COLOR );
            startURL.setStyle( "-fx-text-inner-color: " + MCFLConstants.GUI_LIGHT_COLOR );
            label1.setTextFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
            label2.setTextFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
            label3.setTextFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
            label4.setTextFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
        } );
    }
}
