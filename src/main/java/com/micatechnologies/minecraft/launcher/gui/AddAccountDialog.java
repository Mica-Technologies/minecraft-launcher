/*
 * Copyright (c) 2026 Mica Technologies
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

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthResult;
import com.micatechnologies.minecraft.launcher.utilities.AuthUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NotificationManager;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * A window for signing in one more Microsoft account, over whatever screen is showing.
 *
 * <p>Adding an account used to sign the current one out and restart the launcher into the
 * full-screen login. This window signs the new account in alongside the others and leaves the
 * default account alone (unless there was none). It also serves "Sign in again" for an
 * account whose saved credentials Microsoft rejected: signing that account in replaces its
 * credentials.</p>
 *
 * @since 2026.10
 */
public final class AddAccountDialog
{
    private AddAccountDialog() { }

    /**
     * Opens the window. Must be called on the FX thread.
     *
     * @param owner the window to center over and block, or {@code null}
     *
     * @since 2026.10
     */
    public static void show( Window owner )
    {
        Stage stage = new Stage();
        stage.initModality( Modality.WINDOW_MODAL );
        if ( owner != null ) {
            stage.initOwner( owner );
        }
        stage.setTitle( LocalizationManager.get( "account.add.title" ) );

        SignInPanel signIn = new SignInPanel( "account.add.title", "account.add.hint", "dialog.button.cancel" );
        signIn.dismissButton().setOnAction( e -> stage.close() );

        StackPane root = new StackPane( signIn.root() );
        root.setPadding( new Insets( 20 ) );
        root.getStyleClass().addAll( "rootPane", "hero-surface" );
        stage.setScene( new Scene( UiScale.wrap( root ), 1000 * UiScale.get(), 800 * UiScale.get() ) );
        UiScale.install( stage.getScene() );
        ScaledMinSize.follow( stage, 860, 680 );
        MCLauncherGuiWindow.installCurrentThemeStylesheets( root );
        stage.setOnShown( e -> com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                .applyTitleBarDarkMode( stage, !GUIUtilities.isLightChrome( ConfigManager.getTheme() ) ) );
        // Leave nothing running in the WebView once the window is gone.
        stage.setOnHidden( e -> signIn.webView().getEngine().load( "about:blank" ) );

        signIn.onCallback( callback -> {
            if ( callback.userCancelled() ) {
                stage.close();
                return;
            }
            if ( callback.code() == null ) {
                Logger.logError( LocalizationManager.format( "log.login.msLoginError", callback.error() ) );
                if ( stage.isShowing() ) {
                    signIn.failAndRetry( "signIn.failed" );
                }
                return;
            }
            signIn.showRedeeming();
            boolean save = signIn.remember();
            SystemUtilities.spawnNewTask( () -> {
                MCLauncherAuthResult result = MCLauncherAuthManager.addAccountWithMicrosoft( callback.code(), save );
                boolean ok = AuthUtilities.checkAuthResponse( result );
                GUIUtilities.JFXPlatformRun( () -> {
                    if ( ok ) {
                        stage.close();
                        NotificationManager.success( LocalizationManager.get( "account.add.done.title" ),
                                                     LocalizationManager.format( "account.add.done.body",
                                                                                 result.getMinecraftUser().name() ) );
                    }
                    else if ( stage.isShowing() ) {
                        // Closed while redeeming: retrying would reload the sign-in page into
                        // the hidden WebView, undoing the about:blank it was left on.
                        signIn.failAndRetry( result == MCLauncherAuthResult.ERROR_NOT_OWNED
                                             ? "signIn.notOwned" : "signIn.failed" );
                    }
                } );
            } );
        } );

        stage.show();
        signIn.loadSignIn();
    }
}
