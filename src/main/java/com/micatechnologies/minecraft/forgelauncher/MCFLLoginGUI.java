package com.micatechnologies.minecraft.forgelauncher;

import com.jfoenix.controls.*;
import com.micatechnologies.minecraft.authlib.MCAuthAccount;
import com.micatechnologies.minecraft.authlib.MCAuthException;
import com.micatechnologies.minecraft.authlib.MCAuthService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Skin;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.util.concurrent.CountDownLatch;

/**
 * GUI for launcher login. Allows launcher login.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class MCFLLoginGUI extends MCFLGenericGUI {

    /**
     * Text to display on login button
     */
    private final static String LOGIN_BUTTON_TEXT = "Login";

    /**
     * Text to display on login button after login failure
     */
    private final static String BAD_LOGIN_BUTTON_TEXT = "Try Again";

    /**
     * Root window pane
     */
    @FXML
    public AnchorPane rootPane;
    /**
     * Label text for email
     */
    @FXML
    public Label emailLabel;
    /**
     * Label text for password
     */
    @FXML
    public Label passwordLabel;
    /**
     * Email address (or username) field for login
     */
    @FXML
    public JFXTextField emailField;

    /**
     * Password field for login
     */
    @FXML
    public JFXPasswordField passwordField;

    /**
     * Remember me checkbox for login
     */
    @FXML
    public JFXCheckBox rememberCheckBox;

    /**
     * Login button
     */
    @FXML
    public JFXButton loginButton;

    /**
     * Exit button
     */
    @FXML
    public JFXButton exitButton;

    /**
     * Latch for waiting until successful login
     */
    public CountDownLatch loginSuccessLatch = new CountDownLatch( 1 );

    /**
     * Handle the creation and initial configuration of GUI controls/elements.
     *
     * @since 1.0
     */
    @Override
    void create( Stage stage ) {
        // Configure login button
        loginButton.setOnAction( event -> {
            new Thread( () -> {
                // Create temp copy of email and password

                String u = emailField.getText();
                String p = passwordField.getText();

                // Create account to authorize with
                MCAuthAccount loggingIn = new MCAuthAccount( u );
                try {
                    // Attempt login with username and password
                    boolean auth = MCAuthService.usernamePasswordAuth( loggingIn, p, MCFLApp.getClientToken() );

                    // If successful, register login with app, save account if
                    // remember me checkbox selected, then continue.
                    if ( auth ) {
                        if ( rememberCheckBox.isSelected() ) {
                            MCAuthAccount.writeToFile( MCFLConstants.LAUNCHER_CLIENT_SAVED_USER_FILE, loggingIn );
                        }
                        MCFLApp.loginUser( loggingIn );
                        loginSuccessLatch.countDown();
                    }
                    // If authentication failed, show try again on button
                    else {
                        Platform.runLater( () -> {
                            loginButton.setText( BAD_LOGIN_BUTTON_TEXT );
                        } );
                        new Thread( () -> {
                            try {
                                Thread.sleep( 5000 );
                            }
                            catch ( InterruptedException ignored ) {
                            }
                            Platform.runLater( () -> {
                                loginButton.setText( LOGIN_BUTTON_TEXT );
                            } );
                        } ).start();
                    }
                }
                // If authentication caused exception, show try again on button
                catch ( MCAuthException e ) {
                    Platform.runLater( () -> {
                        loginButton.setText( BAD_LOGIN_BUTTON_TEXT );
                    } );
                    new Thread( () -> {
                        try {
                            Thread.sleep( 5000 );
                        }
                        catch ( InterruptedException ignored ) {
                        }
                        Platform.runLater( () -> {
                            loginButton.setText( LOGIN_BUTTON_TEXT );
                        } );
                    } ).start();
                }
            } ).start();
        } );

        // Configure exit button
        stage.setOnCloseRequest( event -> {
            new Thread( () -> {
                Platform.setImplicitExit( true );
                System.exit( 0 );
            } ).start();
        } );
        exitButton.setOnAction( event -> getCurrentStage().fireEvent( new WindowEvent( getCurrentStage(), WindowEvent.WINDOW_CLOSE_REQUEST ) ) );

        // Configure ENTER key to press login
        rootPane.setOnKeyPressed( event -> {
            if ( event.getCode() == KeyCode.ENTER ) {
                event.consume();
                loginButton.fire();
            }
        } );

        // CONFIGURE TEMPORARY MESSAGE FOR REMEMBER ME CHECK BOX
        //rememberCheckBox.setOnAction( event -> new Thread( () -> MCFLLogger.error( "Unfortunately, this functionality is not responding. Please try again soon!", 800, getCurrentStage() ) ).start() );
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
        fxmll.setLocation( getClass().getClassLoader().getResource( "LauncherLoginGUI.fxml" ) );
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
            rememberCheckBox.setTextFill( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
            rootPane.setBackground( new Background( new BackgroundFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ), CornerRadii.EMPTY, Insets.EMPTY ) ) );
            emailLabel.setTextFill( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
            passwordLabel.setTextFill( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
            emailField.setStyle( "-fx-text-inner-color: " + MCFLConstants.GUI_DARK_COLOR );
            passwordField.setStyle( "-fx-text-inner-color: " + MCFLConstants.GUI_DARK_COLOR );
            rememberCheckBox.setUnCheckedColor( Color.web( MCFLConstants.GUI_DARK_COLOR ) );
        } );

    }

    @Override
    void enableDarkMode() {
        Platform.runLater( () -> {
            rememberCheckBox.setTextFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
            rootPane.setBackground( new Background( new BackgroundFill( Color.web( MCFLConstants.GUI_DARK_COLOR ), CornerRadii.EMPTY, Insets.EMPTY ) ) );
            emailLabel.setTextFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
            passwordLabel.setTextFill( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
            emailField.setStyle( "-fx-text-inner-color: " + MCFLConstants.GUI_LIGHT_COLOR );
            passwordField.setStyle( "-fx-text-inner-color: " + MCFLConstants.GUI_LIGHT_COLOR );
            rememberCheckBox.setUnCheckedColor( Color.web( MCFLConstants.GUI_LIGHT_COLOR ) );
        } );
    }
}
