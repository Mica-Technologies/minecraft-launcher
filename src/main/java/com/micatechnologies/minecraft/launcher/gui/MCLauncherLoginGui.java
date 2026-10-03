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
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * JavaFX GUI controller for the launcher's Microsoft account sign-in screen.
 *
 * <p>This scene embeds a {@link WebView} that hosts the Microsoft OAuth sign-in flow. When the WebView navigates to the
 * OAuth redirect URL, the attached load-worker listener extracts the returned authorization code and redeems it via
 * {@link MCLauncherAuthManager} on a background task. A successful login (optionally persisted when the "stay logged in"
 * toggle is selected) releases {@link #loginSuccessLatch}, unblocking callers of {@link #waitForLoginSuccess()}; a failed
 * login reloads the sign-in page so the user can retry.</p>
 *
 * <p>While this screen is shown, navigation away via the macOS title-bar toolbar is disabled so the user cannot bypass
 * authentication, and the launcher's Discord rich presence reflects the logging-in state.</p>
 *
 * @see MCLauncherAbstractGui
 */
public class MCLauncherLoginGui extends MCLauncherAbstractGui
{
    /**
     * Exit button. This button closes the window, but in most cases the result is the application closing as well.
     *
     * @since 1.0
     */
    @SuppressWarnings( "unused" )
    @FXML
    StackPane signInHost;

    /** The sign-in card, shared in look and behaviour with the "Add account" window. */
    private SignInPanel signIn;

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

    /**
     * One-shot latch that is counted down when a Microsoft sign-in completes successfully and the resulting account has
     * been registered with the launcher. Callers of {@link #waitForLoginSuccess()} block on this latch until login
     * succeeds, allowing the launcher boot sequence to gate on the user authenticating.
     */
    private final CountDownLatch loginSuccessLatch        = new CountDownLatch( 1 );


    /**
     * Constructor for abstract scene class that initializes {@link #scene} and sets <code>this</code> as the FXML
     * controller.
     *
     * @param stage the JavaFX {@link Stage} that will host this login scene
     *
     * @throws IOException if unable to load FXML file specified
     */
    public MCLauncherLoginGui( Stage stage ) throws IOException {
        super( stage );
    }

    /**
     * Constructor for abstract scene class that initializes {@link #scene} and sets <code>this</code> as the FXML
     * controller, sizing the scene to the supplied width and height.
     *
     * @param stage  the JavaFX {@link Stage} that will host this login scene
     * @param width  the initial scene width, in pixels
     * @param height the initial scene height, in pixels
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
        return LocalizationManager.get( "window.title.login" );
    }

    /**
     * Abstract method: This method must perform initialization and setup of the scene and @FXML components.
     */
    @Override
    void setup() {
        // Configure window close
        stage.setOnCloseRequest( windowEvent -> {
            windowEvent.consume();
            signIn.dismissButton().fire();
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

        signIn = new SignInPanel( "signIn.login.heading", "signIn.login.subtitle", "login.exitBtn" );
        signInHost.getChildren().setAll( signIn.root() );

        // Attach the OAuth-callback listener exactly once. Registering it on every page
        // load once stacked a listener per failed attempt, so the next redirect spawned
        // several redemptions of a single-use code.
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
        signIn.dismissButton().setOnAction( event -> LauncherCore.closeApp() );
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation installs smooth wheel-scrolling on the sign-in {@link WebView}, loads the Microsoft OAuth
     * sign-in page, and sets the launcher's Discord rich-presence status to indicate the user is logging in.</p>
     */
    @Override
    void afterShow() {
        // Load MS auth
        loadMsAuthFrame();

        // Set Discord rich presence
        SystemUtilities.spawnNewTask( () -> DiscordRpcUtility.setMenuPresence( LocalizationManager.get( "login.discord.loggingIn" ) ) );
    }

    /**
     * {@inheritDoc}
     *
     * <p>The login scene holds no resources requiring teardown, so this implementation is intentionally empty.</p>
     */
    @Override
    void cleanup() {

    }

    /**
     * {@inheritDoc}
     *
     * @return {@link HelpTopic#LOGIN}, the help topic associated with the login screen
     */
    @Override
    HelpTopic getHelpTopic() { return HelpTopic.LOGIN; }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code false} to block the macOS title-bar toolbar's Browse / Settings / Account items on this screen
     * — leaving them clickable would let the user skip sign-in and reach the app.</p>
     *
     * @return {@code false}; navigation away from the login screen via the toolbar is disallowed until sign-in completes
     */
    @Override
    boolean allowsToolbarNavigation() { return false; }

    /**
     * Loads (or reloads) the Microsoft OAuth sign-in page into the embedded
     * WebView. Must run on the FX application thread.
     */
    private void loadMsAuthFrame() {
        // java.net cookies only; the WebView keeps its own jar, which is why the sign-in URL
        // forces Microsoft's account picker (see MicrosoftSignIn.loginUrl).
        CookieHandler.setDefault( new CookieManager() );
        signIn.loadSignIn();
    }

    /**
     * Wires the WebView load-worker state listener that watches for the
     * Microsoft OAuth callback URL and kicks off the auth-code redemption.
     * Called exactly once from {@link #setup()}.
     */
    private void attachOAuthCallbackListener() {
        signIn.onCallback( callback -> {
            if ( callback.code() == null ) {
                if ( callback.userCancelled() ) {
                    Logger.logDebug( LocalizationManager.get( "log.login.userCancelled" ) );
                }
                else {
                    Logger.logError( LocalizationManager.format( "log.login.msLoginError",
                                                                 callback.error().isEmpty()
                                                                 ? LocalizationManager.get( "login.error.unknown" )
                                                                 : callback.error() ) );
                }
                // The WebView is on about:blank now; reload the sign-in page so the user isn't
                // left on a blank screen. A cancellation isn't an error worth showing.
                if ( callback.userCancelled() ) {
                    signIn.loadSignIn();
                }
                else {
                    signIn.failAndRetry( "signIn.failed" );
                }
                return;
            }
            signIn.showRedeeming();
            boolean remember = signIn.remember();
            SystemUtilities.spawnNewTask( () -> {
                MCLauncherAuthResult authResult = MCLauncherAuthManager.loginWithMicrosoftAccount( callback.code(),
                                                                                                   remember );
                if ( AuthUtilities.checkAuthResponse( authResult ) ) {
                    loginSuccessLatch.countDown();
                }
                else {
                    Logger.logError( LocalizationManager.get( "log.login.failed" ) );
                    Platform.runLater( () -> signIn.failAndRetry(
                            authResult == MCLauncherAuthResult.ERROR_NOT_OWNED ? "signIn.notOwned" : "signIn.failed" ) );
                }
            } );
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
