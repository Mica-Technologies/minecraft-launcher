package com.micatechnologies.minecraft.forgelauncher.gui;


import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXPasswordField;
import com.jfoenix.controls.JFXTextField;
import com.micatechnologies.minecraft.forgelauncher.LauncherApp;
import com.micatechnologies.minecraft.forgelauncher.LauncherConstants;
import com.micatechnologies.minecraft.forgelauncher.auth.MinecraftAccount;
import com.micatechnologies.minecraft.forgelauncher.auth.MinecraftAccountService;
import com.micatechnologies.minecraft.forgelauncher.exceptions.FLAuthenticationException;
import com.micatechnologies.minecraft.forgelauncher.utilities.GUIUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.SystemUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.Pair;
import javafx.fxml.FXML;
import javafx.stage.WindowEvent;

import java.util.concurrent.CountDownLatch;

public class LoginGUI extends GenericGUI {
    @FXML
    JFXTextField emailField;

    @FXML
    JFXPasswordField passwordField;

    @FXML
    JFXCheckBox rememberMeCheckBox;

    @FXML
    JFXButton loginBtn;

    @FXML
    JFXButton exitBtn;

    private final CountDownLatch loginSuccessLatch = new CountDownLatch( 1 );

    @Override
    String getFXMLResourcePath() {
        return "loginGUI.fxml";
    }

    @Override
    Pair< Integer, Integer > getWindowSize() {
        return new Pair<>( 600, 600 );
    }

    @Override
    void setupWindow() {
        // Configure exit button and window close
        currentJFXStage.setOnCloseRequest( windowEvent -> SystemUtils.spawnNewTask( LauncherApp::closeApp ) );
        exitBtn.setOnAction( actionEvent -> currentJFXStage.fireEvent( new WindowEvent( currentJFXStage, WindowEvent.WINDOW_CLOSE_REQUEST ) ) );

        // Configure login button
        loginBtn.setOnAction( actionEvent -> SystemUtils.spawnNewTask( () -> {
            // Lock fields
            emailField.setDisable( true );
            passwordField.setDisable( true );

            // Get login information from fields
            String email = emailField.getText();
            String password = passwordField.getText();

            // Create account to test authorization
            MinecraftAccount authAccount = new MinecraftAccount( email );
            try {
                // Attempt login
                boolean authSuccess = MinecraftAccountService.usernamePasswordAuth( authAccount, password, LauncherApp.getClientToken() );

                // If successful, register login with app and save account if applicable
                if ( authSuccess ) {
                    if ( rememberMeCheckBox.isSelected() ) {
                        MinecraftAccount.writeToFile( LauncherConstants.LAUNCHER_CLIENT_SAVED_USER_FILE, authAccount );
                    }
                    loggedIn = authAccount;
                    loginSuccessLatch.countDown();
                }
                else {
                    handleBadLogin();
                }
            }
            catch ( FLAuthenticationException e ) {
                e.printStackTrace();
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

    private void handleBadLogin() {
        // Show try again message
        GUIUtils.JFXPlatformRun( () -> {
            loginBtn.setText( "Try Again" );
            passwordField.clear();
        } );

        // Reset log in button text in 5 seconds
        SystemUtils.spawnNewTask( () -> {
            try {
                Thread.sleep( 5000 );
            }
            catch ( InterruptedException ignored ) {
            }
            GUIUtils.JFXPlatformRun( () -> loginBtn.setText( "Log In" ) );
        } );
    }

    private MinecraftAccount loggedIn;

    public MinecraftAccount waitForLoginInfo() throws InterruptedException {
        // Wait for login success
        loginSuccessLatch.await();

        // Return logged in account
        return loggedIn;
    }
}
