/*
 * Copyright (c) 2021 Mica Technologies
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.micatechnologies.minecraft.launcher.gui;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXPasswordField;
import com.jfoenix.controls.JFXTextField;
import com.micatechnologies.minecraft.launcher.LauncherCore;
import com.micatechnologies.minecraft.launcher.exceptions.AuthException;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.auth.AuthAccountMojang;
import com.micatechnologies.minecraft.launcher.game.auth.AuthManager;
import com.micatechnologies.minecraft.launcher.game.auth.AuthService;
import com.micatechnologies.minecraft.launcher.utilities.GUIUtilities;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import com.micatechnologies.minecraft.launcher.utilities.annotations.OnScreen;
import javafx.fxml.FXML;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

public class MCLauncherLoginGui extends MCLauncherAbstractGui
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
     * Constructor for abstract scene class that initializes {@link #scene} and sets <code>this</code> as the FXML
     * controller.
     *
     * @throws IOException if unable to load FXML file specified
     */
    public MCLauncherLoginGui( Stage stage) throws IOException {
        super(stage);
    }

    /**
     * Abstract method: This method must return the resource path for the JavaFX scene FXML file.
     *
     * @return JavaFX scene FXML resource path
     */
    @Override
    String getSceneFxmlPath() {
        return "gui/loginGUI.fxml";
    }

    /**
     * Abstract method: This method must return the name of the JavaFX scene.
     *
     * @return Java FX scene name
     */
    @Override
    String getSceneName() {
        return "Login";
    }

    /**
     * Abstract method: This method must perform initialization and setup of the scene and @FXML components.
     */
    @Override
    void setup() {
        // Configure window close
        stage.setOnCloseRequest( windowEvent -> {
            windowEvent.consume();
            exitBtn.fire();
        } );

        // Configure login button
        loginBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            // Lock fields
            emailField.setDisable( true );
            passwordField.setDisable( true );

            // Get login information from fields
            String email = emailField.getText();
            String password = passwordField.getText();

            // Create account to test authorization
            AuthAccountMojang authAccountMojang = new AuthAccountMojang( email );

            // Attempt login
            boolean authSuccess = false;
            try {
                authSuccess = AuthService.usernamePasswordAuth( authAccountMojang, password );
            }
            catch ( AuthException e ) {
                Logger.logError( "An authentication error has occurred." );
                e.printStackTrace();
            }

            // If successful, register login with app and save account if applicable
            if ( authSuccess ) {
                AuthManager.login( authAccountMojang, rememberMeCheckBox.isSelected() );

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
        scene.setOnKeyPressed( keyEvent -> {
            if ( keyEvent.getCode() == KeyCode.ENTER && ( emailField.isFocused() || passwordField.isFocused() ) ) {
                keyEvent.consume();
                loginBtn.fire();
            }
        } );

        // Configure exit button
        exitBtn.setOnAction( event -> LauncherCore.closeApp() );
    }

    /**
     * Abstract method: This method must perform preparations of the environment, such as enabling menu bars, context
     * menus, or other OS-specific enhancements.
     */
    @Override
    void loadEnvironment() {

    }

    /**
     * Abstract method: This method returns a boolean indicating if a warning should be shown to the user before closing
     * the window while displaying the stage/GUI.
     *
     * @return boolean indicating if window close warning should be shown
     */
    @Override
    boolean warnOnExit() {
        return false;
    }

    /**
     * Method to handle the processing of a bad login. This method should show a warning to the end-user, and reset the
     * password field.
     *
     * @since 1.0
     */
    private void handleBadLogin() {
        // Show try again message
        GUIUtilities.JFXPlatformRun( () -> {
            loginBtn.setText( "Try Again" );
            passwordField.clear();
        } );

        // Reset log in button text in 5 seconds
        SystemUtilities.spawnNewTask( () -> {
            try {
                Thread.sleep( 5000 );
            }
            catch ( InterruptedException e ) {
                Logger.logDebug( "An error occurred while waiting to reset the login button text from \"Try " +
                                         "Again\" to \"Log In\"." );
            }
            GUIUtilities.JFXPlatformRun( () -> loginBtn.setText( "Log In" ) );
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
