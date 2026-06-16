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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthResult;
import com.micatechnologies.minecraft.launcher.utilities.AnnouncementManager;
import com.micatechnologies.minecraft.launcher.utilities.AuthUtilities;
import com.micatechnologies.minecraft.launcher.utilities.DiscordRpcUtility;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import io.github.palexdev.materialfx.controls.*;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.RowConstraints;
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
     * Microsoft Account stay logged in option check box. If checked, this enabled saving of the account on persistent
     * storage so it can be used automatically.
     *
     * @since 2021.2
     */
    @SuppressWarnings( "unused" )
    @FXML
    MFXToggleButton msStayLoggedInCheckBox;

    /**
     * Announcement banner.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    Label announcement;

    /**
     * Announcement banner row constraints.
     *
     * @since 3.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    RowConstraints announcementRow;

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

        // Grow the stage to the login FXML's preferred size if it's currently
        // smaller. The progress screen opens the stage at the global PREF_HEIGHT
        // (~800), which is shorter than the login screen needs to fit the
        // Microsoft sign-in WebView without an internal scrollbar. setScene()
        // doesn't resize the stage, so without this shim the FXML's prefHeight
        // is effectively ignored on the progress -> login transition and the
        // WebView opens clipped. Only grow, never shrink — a user who has
        // resized the window larger keeps their layout.
        double prefH = rootPane.getPrefHeight();
        double prefW = rootPane.getPrefWidth();
        if ( !Double.isNaN( prefH ) && prefH > 0 && stage.getHeight() < prefH ) {
            stage.setHeight( prefH );
        }
        if ( !Double.isNaN( prefW ) && prefW > 0 && stage.getWidth() < prefW ) {
            stage.setWidth( prefW );
        }

        // Setup auth web view engine
        authWebView.getEngine().setJavaScriptEnabled( true );
        authWebView.getEngine().setUserAgent( "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0" );

        // Attach the OAuth-callback listener exactly once. Previously this was
        // registered inside loadMsAuthFrame(), which was re-entered on every
        // failed login via handleBadLogin() — each retry stacked another
        // listener on the same stateProperty, so after N failures all N
        // listeners would fire on the next navigation, all N would spawn an
        // auth-code-redemption task, and only the first could succeed (MS
        // codes are single-use). Subsequent redemptions failed and recursed
        // back through handleBadLogin, multiplying the leak. Registering once
        // here keeps the handler-set small and predictable.
        attachOAuthCallbackListener();

        // Display announcements if present
        String announcementText = AnnouncementManager.getAnnouncementLogin();
        if ( announcementText.length() > 0 ) {
            announcement.setText( announcementText );
            announcement.setMinHeight( 30 );
            announcementRow.setMinHeight( 30 );
        }
        else {
            announcement.setMaxHeight( 0 );
            announcementRow.setMaxHeight( 0 );
        }

        // Configure exit button
        exitBtn.setOnAction( event -> LauncherCore.closeApp() );
    }

    @Override
    void afterShow() {
        // Smooth wheel-scroll inside the MS sign-in WebView so account-picker
        // and consent screens scroll the same way as the help WebView and the
        // app's other surfaces, instead of JavaFX's default discrete bumps.
        SmoothScroll.install( authWebView );

        // Load MS auth
        loadMsAuthFrame();

        // Set Discord rich presence
        SystemUtilities.spawnNewTask( () -> DiscordRpcUtility.setMenuPresence( LocalizationManager.get( "login.discord.loggingIn" ) ) );
    }

    @Override
    void cleanup() {

    }

    @Override
    HelpTopic getHelpTopic() { return HelpTopic.LOGIN; }

    /**
     * Loads (or reloads) the Microsoft OAuth sign-in page into the embedded
     * WebView. Must run on the FX application thread — callers from background
     * threads (e.g. {@link #handleBadLogin}) marshal via {@link Platform#runLater}.
     */
    private void loadMsAuthFrame() {
        // Load MS login website
        CookieManager cookieManager = new CookieManager();
        CookieHandler.setDefault( cookieManager );
        // Force the account picker on every visit. JavaFX WebView keeps its own
        // cookie jar in com.sun.webkit.network.CookieManager which is *not*
        // touched by CookieHandler.setDefault above (the new java.net.CookieManager
        // applies to HttpURLConnection traffic, not WebView). The MS sign-in
        // cookies therefore survive a logout-then-relaunch-of-the-login-screen
        // within a single launcher session, and without prompt= MS silently
        // honours the cached session and redirects straight to the OAuth
        // callback — i.e. it "signs the user back in" before they can pick a
        // different account. prompt=select_account forces MS to render the
        // account picker even when the session is live, so logging out and
        // signing in as someone else is a single click instead of impossible.
        String loginUrl = MicrosoftService.oAuthLoginUrl().toString() + "&prompt=select_account";
        waitingOnWebViewResponse.set( true );
        authWebView.getEngine().load( loginUrl );
    }

    /**
     * Wires the WebView load-worker state listener that watches for the
     * Microsoft OAuth callback URL and kicks off the auth-code redemption.
     * Called exactly once from {@link #setup()}.
     */
    private void attachOAuthCallbackListener() {
        authWebView.getEngine().getLoadWorker().stateProperty().addListener( ( observable, oldValue, newValue ) -> {
            // Only run handler if expecting a response
            if ( !waitingOnWebViewResponse.get() ) {
                return;
            }
            // Check if page is on callback/redirect URI. getLocation() is null before the
            // first load completes and during some transitional states, so guard against it
            // to avoid an NPE on the FX thread.
            String location = authWebView.getEngine().getLocation();
            if ( location == null || !location.startsWith( Constants.MICROSOFT_OAUTH_REDIRECT_URL ) ) {
                return;
            }
            // Set waiting on web view response flag to false
            waitingOnWebViewResponse.set( false );

            // Clear web view and return to regular login view
            authWebView.getEngine().load( "about:blank" );

            SystemUtilities.spawnNewTask( () -> {
                // Split URL into parameters
                String locationParamsString = location.substring( location.indexOf( "?" ) + 1 );
                Map< String, List< String > > locationParamsList = Pattern.compile( "&" )
                                                                          .splitAsStream( locationParamsString )
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
                if ( !locationParamsList.containsKey( "error" ) && locationParamsList.containsKey( "code" ) ) {
                    String authCode = locationParamsList.get( "code" ).get( 0 );

                    // Attempt login
                    MCLauncherAuthResult authResult = MCLauncherAuthManager.loginWithMicrosoftAccount( authCode,
                                                                                                       msStayLoggedInCheckBox.isSelected() );

                    // Check login result
                    boolean authSuccess = AuthUtilities.checkAuthResponse( authResult );

                    // If successful, register login with app and save account if applicable
                    if ( authSuccess ) {
                        loginSuccessLatch.countDown();
                    }
                    else {
                        handleBadLogin();
                    }
                }
                else {
                    // Get error description
                    String errorDescription = LocalizationManager.get( "login.error.unknown" );
                    if ( locationParamsList.containsKey( "error_description" ) ) {
                        errorDescription = locationParamsList.get( "error_description" ).get( 0 );
                    }

                    if ( errorDescription.contains( "user has denied access" ) ) {
                        Logger.logDebug( LocalizationManager.get( "log.login.userCancelled" ) );
                    }
                    else {
                        Logger.logError( LocalizationManager.format( "log.login.msLoginError", errorDescription ) );
                    }
                    // The OAuth flow returned an error parameter (server rejected
                    // the request, user cancelled, etc.). WebView is now sitting
                    // on about:blank — without re-loading the sign-in URL the
                    // user is stuck on a blank screen. Reload via the same
                    // recovery path used for downstream auth failures.
                    handleBadLogin();
                }
            } );
        } );
    }

    /**
     * Method to handle the processing of a bad login. This method should show a warning to the end-user, and reset the
     * password field. Called from the auth-code-redemption background task, so
     * the WebView reload is marshalled back to the FX application thread —
     * without this trip, {@link javafx.scene.web.WebEngine#load} throws
     * {@code IllegalStateException: Not on FX application thread} and the user
     * is left staring at a blank login surface with no way to retry.
     *
     * @since 1.0
     */
    private void handleBadLogin() {
        Logger.logError( LocalizationManager.get( "log.login.failed" ) );
        Platform.runLater( this::loadMsAuthFrame );
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
