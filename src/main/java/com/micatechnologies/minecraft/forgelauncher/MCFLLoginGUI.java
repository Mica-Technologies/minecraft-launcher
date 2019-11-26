package com.micatechnologies.minecraft.forgelauncher;

import com.jfoenix.controls.*;
import com.micatechnologies.minecraft.authlib.MCAuthAccount;
import com.micatechnologies.minecraft.authlib.MCAuthException;
import com.micatechnologies.minecraft.authlib.MCAuthService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;

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
     * TODO: Implement this exit button in GUI or bad things will happen
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
    void create() {
        // Configure login button
        loginButton.setOnAction( event -> {
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
                    loginButton.setText( BAD_LOGIN_BUTTON_TEXT );
                    new Thread( () -> {
                        try {
                            Thread.sleep( 5000 );
                        }
                        catch ( InterruptedException ignored ) {
                        }
                        loginButton.setText( LOGIN_BUTTON_TEXT );
                    } );
                }
            }
            // If authentication caused exception, show try again on button
            catch ( MCAuthException e ) {
                loginButton.setText( BAD_LOGIN_BUTTON_TEXT );
                new Thread( () -> {
                    try {
                        Thread.sleep( 5000 );
                    }
                    catch ( InterruptedException ignored ) {
                    }
                    loginButton.setText( LOGIN_BUTTON_TEXT );
                } );
            }
        } );

        // Configure exit button
        exitButton.setOnAction( event -> {
            Platform.setImplicitExit( true );
            System.exit( 0 );
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
        return new int[]{ 500, 500 };
    }
}
