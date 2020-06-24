package com.micatechnologies.minecraft.forgelauncher.gui;


import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXPasswordField;
import com.jfoenix.controls.JFXTextField;
import com.micatechnologies.minecraft.forgelauncher.LauncherApp;
import com.micatechnologies.minecraft.forgelauncher.auth.AuthAccount;
import com.micatechnologies.minecraft.forgelauncher.auth.AuthService;
import com.micatechnologies.minecraft.forgelauncher.auth.AuthManager;
import com.micatechnologies.minecraft.forgelauncher.exceptions.AuthException;
import com.micatechnologies.minecraft.forgelauncher.utilities.GuiUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.LogUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.SystemUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.OnScreen;
import com.micatechnologies.minecraft.forgelauncher.utilities.objects.Pair;
import javafx.fxml.FXML;
import javafx.stage.WindowEvent;

import java.util.concurrent.CountDownLatch;

/**
 * Launcher authentication/login window class.
 *
 * @author Mica Technologies
 * @version 2.0
 * @editors hawka97
 * @creator hawka97
 * @since 1.0
 */
public class LoginWindow extends AbstractWindow
{
    /**
     * Mojang/Minecraft account username. For Mojang accounts, this is the account email address. For old Minecraft
     * accounts, this is the player's username.
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    JFXTextField emailField;

    /**
     * Mojang/Minecraft account password.
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    JFXPasswordField passwordField;

    /**
     * Account remember me option check box. If checked, this enabled saving of the account on persistent storage so it
     * can be used automatically.
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    JFXCheckBox rememberMeCheckBox;

    /**
     * Login button. This button initiates the login process.
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    JFXButton loginBtn;

    /**
     * Exit button. This button closes the window, but in most cases the result is the application closing as well.
     *
     * @since 1.0
     */
    @FXML
    @OnScreen
    JFXButton exitBtn;

    private final CountDownLatch loginSuccessLatch = new CountDownLatch( 1 );

    /**
     * Implementation of abstract method that returns the file name of the FXML associated with this class.
     *
     * @return FXML file name
     *
     * @since 1.0
     */
    @Override
    String getFXMLResourcePath() {
        return "loginGUI.fxml";
    }

    /**
     * Implementation of abstract method that returns the minimum (and initial) size used for the window.
     *
     * @return window size as integer pair
     *
     * @since 1.0
     */
    @Override
    Pair< Integer, Integer > getWindowSize() {
        return new Pair<>( 600, 600 );
    }

    /**
     * Implementation of abstract method that handles the setup and population of elements on the window.
     *
     * @since 1.0
     */
    @Override
    void setupWindow() {
        // Configure exit button and window close
        currentJFXStage.setOnCloseRequest( windowEvent -> SystemUtils.spawnNewTask( LauncherApp::closeApp ) );
        exitBtn.setOnAction( actionEvent -> currentJFXStage
                .fireEvent( new WindowEvent( currentJFXStage, WindowEvent.WINDOW_CLOSE_REQUEST ) ) );

        // Configure login button
        loginBtn.setOnAction( actionEvent -> SystemUtils.spawnNewTask( () -> {
            // Lock fields
            emailField.setDisable( true );
            passwordField.setDisable( true );

            // Get login information from fields
            String email = emailField.getText();
            String password = passwordField.getText();

            // Create account to test authorization
            AuthAccount authAccount = new AuthAccount( email );

            // Attempt login
            boolean authSuccess = false;
            try {
                authSuccess = AuthService
                        .usernamePasswordAuth( authAccount, password, LauncherApp.getClientToken() );
            }
            catch ( AuthException e ) {
                LogUtils.logError( "An authentication error has occurred." );
                e.printStackTrace();
            }

            // If successful, register login with app and save account if applicable
            if ( authSuccess ) {
                AuthManager.login( authAccount, rememberMeCheckBox.isSelected() );

                loginSuccessLatch.countDown();
            }
            else {
                handleBadLogin();
            }

            // Unlock fields
            emailField.setDisable( false );
            passwordField.setDisable( false );
        } ) );

        // Configure ENTER key to press login button
        rootPane.setOnKeyPressed( keyEvent -> {
            keyEvent.consume();
            loginBtn.fire();
        } );
    }

    /**
     * Method to handle the processing of a bad login. This method should show a warning to the end-user, and reset the
     * password field.
     *
     * @since 1.0
     */
    private void handleBadLogin() {
        // Show try again message
        GuiUtils.JFXPlatformRun( () -> {
            loginBtn.setText( "Try Again" );
            passwordField.clear();
        } );

        // Reset log in button text in 5 seconds
        SystemUtils.spawnNewTask( () -> {
            try {
                Thread.sleep( 5000 );
            }
            catch ( InterruptedException e ) {
                LogUtils.logDebug( "An error occurred while waiting to reset the login button text from \"Try " +
                                           "Again\" to \"Log In\"." );
            }
            GuiUtils.JFXPlatformRun( () -> loginBtn.setText( "Log In" ) );
        } );
    }

    /**
     * Method that blocks until there is a successful login registered and processed.
     *
     * @throws InterruptedException if unable to wait for successful login
     */
    public void waitForLoginSuccess() throws InterruptedException {
        // Wait for login success
        loginSuccessLatch.await();
    }
}
