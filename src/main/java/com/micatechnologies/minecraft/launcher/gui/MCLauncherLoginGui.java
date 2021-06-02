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

import com.micatechnologies.minecraft.launcher.LauncherCore;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthResult;
import com.micatechnologies.minecraft.launcher.utilities.AuthUtilities;
import com.micatechnologies.minecraft.launcher.utilities.DiscordRpcUtility;
import com.micatechnologies.minecraft.launcher.utilities.GUIUtilities;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXTextField;
import io.github.palexdev.materialfx.controls.MFXToggleButton;
import javafx.fxml.FXML;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import net.hycrafthd.minecraft_authenticator.Constants;
import net.hycrafthd.minecraft_authenticator.microsoft.service.MicrosoftService;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.*;

public class MCLauncherLoginGui extends MCLauncherAbstractGui
{
    /**
     * Mojang/Minecraft account username. For Mojang accounts, this is the account email address. For old Minecraft
     * accounts, this is the player's username.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXTextField emailField;

    /**
     * Mojang/Minecraft account password.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXTextField passwordField;

    /**
     * Account remember me option check box. If checked, this enabled saving of the account on persistent storage so it
     * can be used automatically.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXToggleButton rememberMeCheckBox;

    /**
     * Login button. This button initiates the login process.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton loginBtn;

    /**
     * MS (Microsoft) Login button. This button initiates the login process for a Microsoft account.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton msLoginBtn;

    /**
     * Exit button. This button closes the window, but in most cases the result is the application closing as well.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXButton exitBtn;

    /**
     * Authentication web view area.
     *
     * @since 2021.2
     */
    @SuppressWarnings( "unused" )
    @FXML
    WebView authWebView;

    /**
     * Main authentication pane.
     *
     * @since 2021.2
     */
    @FXML
    @SuppressWarnings( "unused" )
    GridPane mainAuthPane;

    /**
     * Microsoft authentication pane.
     *
     * @since 2021.2
     */
    @FXML
    @SuppressWarnings( "unused" )
    GridPane msAuthPane;

    /**
     * Microsoft Account stay logged in option check box. If checked, this enabled saving of the account on persistent
     * storage so it can be used automatically.
     *
     * @since 2021.2
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXToggleButton msStayLoggedInCheckBox;

    private final CountDownLatch loginSuccessLatch        = new CountDownLatch( 1 );
    private final AtomicBoolean  waitingOnWebViewResponse = new AtomicBoolean( false );

    /**
     * Constructor for abstract scene class that initializes {@link #scene} and sets <code>this</code> as the FXML
     * controller.
     *
     * @throws IOException if unable to load FXML file specified
     */
    public MCLauncherLoginGui( Stage stage ) throws IOException {
        super( stage );
    }

    /**
     * Constructor for abstract scene class that initializes {@link #scene} and sets <code>this</code> as the FXML
     * controller.
     *
     * @throws IOException if unable to load FXML file specified
     */
    @SuppressWarnings( "unused" )
    public MCLauncherLoginGui( Stage stage, double width, double height ) throws IOException {
        super( stage, width, height );
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

        // Setup auth web view engine
        authWebView.getEngine().setJavaScriptEnabled( true );
        authWebView.getEngine().setUserAgent( "AppleWebKit/537.44" );

        // Configure login button
        loginBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            // Lock fields
            emailField.setDisable( true );
            passwordField.setDisable( true );

            // Get login information from fields
            String email = emailField.getText();
            String password = passwordField.getText();

            // Attempt login
            MCLauncherAuthResult authResult = MCLauncherAuthManager.loginWithMojangAccount( email, password,
                                                                                            rememberMeCheckBox.isSelected() );

            // Check login result
            boolean authSuccess = AuthUtilities.checkAuthResponse( authResult );

            // If successful, register login with app and save account if applicable
            if ( authSuccess ) {
                loginSuccessLatch.countDown();
            }
            else {
                handleBadLogin( 0 );
            }

            // Unlock fields
            emailField.setDisable( false );
            passwordField.setDisable( false );
        } ) );

        // Hide Microsoft login area by default
        msAuthPane.setVisible( false );
        mainAuthPane.setVisible( true );

        // Configure Microsoft login button
        msLoginBtn.setOnAction( actionEvent -> {
            // Show MS login page
            msAuthPane.setVisible( true );
            mainAuthPane.setVisible( false );
            msStayLoggedInCheckBox.setSelected( rememberMeCheckBox.isSelected() );

            // Load MS login website
            CookieManager cookieManager = new CookieManager();
            CookieHandler.setDefault( cookieManager );
            authWebView.getEngine().load( MicrosoftService.oAuthLoginUrl().toString() );

            // Set waiting on web view response flag to true
            waitingOnWebViewResponse.set( true );

            // Configure web view listener
            authWebView.getEngine().getLoadWorker().stateProperty().addListener( ( observable, oldValue, newValue ) -> {
                // Only run handler if expecting a response
                if ( waitingOnWebViewResponse.get() ) {
                    // Check if page is on callback/redirect URI
                    String location = authWebView.getEngine().getLocation();
                    if ( location.startsWith( Constants.MICROSOFT_OAUTH_REDIRECT_URL ) ) {
                        // Set waiting on web view response flag to false
                        waitingOnWebViewResponse.set( false );

                        // Clear web view and return to regular login view
                        authWebView.getEngine().load( "about:blank" );
                        msAuthPane.setVisible( false );
                        mainAuthPane.setVisible( true );

                        SystemUtilities.spawnNewTask( () -> {
                            // Split URL into parameters
                            String locationParamsString = location.substring( location.indexOf( "?" ) + 1 );
                            Map< String, List< String > > locationParamsList = Pattern.compile( "&" )
                                                                                      .splitAsStream(
                                                                                              locationParamsString )
                                                                                      .map( s -> Arrays.copyOf(
                                                                                              s.split( "=", 2 ), 2 ) )
                                                                                      .collect( groupingBy(
                                                                                              s -> URLDecoder.decode(
                                                                                                      s[ 0 ],
                                                                                                      Charset.defaultCharset() ),
                                                                                              mapping(
                                                                                                      s -> URLDecoder.decode(
                                                                                                              s[ 1 ],
                                                                                                              Charset.defaultCharset() ),
                                                                                                      toList() ) ) );

                            // Handle response and error, if applicable
                            if ( !locationParamsList.containsKey( "error" ) &&
                                    locationParamsList.containsKey( "code" ) ) {
                                String authCode = locationParamsList.get( "code" ).get( 0 );

                                // Attempt login
                                MCLauncherAuthResult authResult = MCLauncherAuthManager.loginWithMicrosoftAccount(
                                        authCode, msStayLoggedInCheckBox.isSelected() );

                                // Check login result
                                boolean authSuccess = AuthUtilities.checkAuthResponse( authResult );

                                // If successful, register login with app and save account if applicable
                                if ( authSuccess ) {
                                    loginSuccessLatch.countDown();
                                }
                                else {
                                    handleBadLogin( 1 );
                                }
                            }
                            else {
                                // Get error description
                                String errorDescription = "(Unknown)";
                                if ( locationParamsList.containsKey( "error_description" ) ) {
                                    errorDescription = locationParamsList.get( "error_description" ).get( 0 );
                                }

                                if ( errorDescription.contains( "user has denied access" ) ) {
                                    Logger.logDebug(
                                            "User cancelled or denied during the Microsoft authentication flow!" );
                                }
                                else {
                                    Logger.logError( "A Microsoft account login error occurred: " + errorDescription );
                                }
                            }
                        } );
                    }
                }
            } );
        } );

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

    @Override
    void afterShow() {
        // Set Discord rich presence
        SystemUtilities.spawnNewTask(
                () -> DiscordRpcUtility.setRichPresence( "Logging In", "Logging In", OffsetDateTime.now(),
                                                         "mica_minecraft_launcher", "Mica Minecraft Launcher",
                                                         "mica_minecraft_launcher", "Mica Minecraft Launcher" ) );
    }

    /**
     * Method to handle the processing of a bad login. This method should show a warning to the end-user, and reset the
     * password field.
     *
     * @since 1.0
     */
    private void handleBadLogin( int index ) {
        if ( index == 0 ) {
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
                GUIUtilities.JFXPlatformRun( () -> loginBtn.setText( "Log In (Mojang)" ) );
            } );
        }
        else if ( index == 1 ) {
            // Show try again message
            GUIUtilities.JFXPlatformRun( () -> msLoginBtn.setText( "Try Again" ) );

            // Reset log in button text in 5 seconds
            SystemUtilities.spawnNewTask( () -> {
                try {
                    Thread.sleep( 5000 );
                }
                catch ( InterruptedException e ) {
                    Logger.logDebug( "An error occurred while waiting to reset the login button text from \"Try " +
                                             "Again\" to \"Log In\"." );
                }
                GUIUtilities.JFXPlatformRun( () -> msLoginBtn.setText( "Log In (Microsoft)" ) );
            } );
        }
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
