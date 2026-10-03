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

import javafx.scene.web.WebView;
import net.hycrafthd.minecraft_authenticator.Constants;
import net.hycrafthd.minecraft_authenticator.microsoft.service.MicrosoftService;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The Microsoft sign-in page in an embedded {@link WebView}, shared by the full-screen login
 * screen and the "Add account" window: the sign-in URL, and the listener that catches the
 * OAuth redirect and hands back its authorization code or error.
 *
 * @since 2026.10
 */
final class MicrosoftSignIn
{
    /** Desktop browser user agent; Microsoft's sign-in pages degrade for unknown agents. */
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0";

    /**
     * What the OAuth redirect carried.
     *
     * @param code  the authorization code, or {@code null} when the sign-in failed
     * @param error Microsoft's error description, or {@code null}
     *
     * @since 2026.10
     */
    record Callback( String code, String error )
    {
        /**
         * @return whether the user dismissed the sign-in rather than it failing
         *
         * @since 2026.10
         */
        boolean userCancelled()
        {
            return error != null && error.contains( "user has denied access" );
        }
    }

    private MicrosoftSignIn() { }

    /**
     * The sign-in URL. {@code prompt=select_account} forces Microsoft's account picker even
     * when the WebView still holds a live Microsoft session, which is what makes signing in
     * a second, different account possible at all: without it Microsoft silently signs the
     * cached account back in.
     *
     * @return the URL to load
     *
     * @since 2026.10
     */
    static String loginUrl()
    {
        return MicrosoftService.oAuthLoginUrl().toString() + "&prompt=select_account";
    }

    /**
     * Parses the query of the OAuth redirect URL. Pure, for testing. An {@code error}
     * parameter wins over a {@code code}; a redirect with neither is an unknown error.
     *
     * @param location the redirect URL the WebView landed on
     *
     * @return the code or the error
     *
     * @since 2026.10
     */
    static Callback parseCallback( String location )
    {
        String code = null;
        String error = null;
        boolean sawError = false;
        int q = location == null ? -1 : location.indexOf( '?' );
        if ( q >= 0 ) {
            String query = location.substring( q + 1 );
            int hash = query.indexOf( '#' );
            if ( hash >= 0 ) {
                query = query.substring( 0, hash );
            }
            for ( String pair : query.split( "&" ) ) {
                int eq = pair.indexOf( '=' );
                String name = URLDecoder.decode( eq < 0 ? pair : pair.substring( 0, eq ), StandardCharsets.UTF_8 );
                String value = eq < 0 ? "" : URLDecoder.decode( pair.substring( eq + 1 ), StandardCharsets.UTF_8 );
                switch ( name ) {
                    case "code" -> {
                        if ( code == null ) code = value;
                    }
                    case "error" -> sawError = true;
                    case "error_description" -> error = value;
                    default -> { /* state, session_state, ... */ }
                }
            }
        }
        if ( sawError || code == null || code.isEmpty() ) {
            return new Callback( null, error != null ? error : "" );
        }
        return new Callback( code, null );
    }

    /**
     * Watches a WebView for the OAuth redirect. While {@code waiting} is set, the first load
     * that lands on the redirect URL clears {@code waiting}, blanks the view and passes the
     * parsed result to {@code onCallback} on the FX thread. Attach once per WebView.
     *
     * @param view       the sign-in WebView
     * @param waiting    set by the caller when it loads the sign-in page
     * @param onCallback receives the code or error
     *
     * @since 2026.10
     */
    static void attach( WebView view, AtomicBoolean waiting, Consumer< Callback > onCallback )
    {
        view.getEngine().getLoadWorker().stateProperty().addListener( ( obs, oldState, newState ) -> {
            if ( !waiting.get() ) {
                return;
            }
            // getLocation() is null before the first load and in some transitional states.
            String location = view.getEngine().getLocation();
            if ( location == null || !location.startsWith( Constants.MICROSOFT_OAUTH_REDIRECT_URL ) ) {
                return;
            }
            waiting.set( false );
            view.getEngine().load( "about:blank" );
            onCallback.accept( parseCallback( location ) );
        } );
    }
}
