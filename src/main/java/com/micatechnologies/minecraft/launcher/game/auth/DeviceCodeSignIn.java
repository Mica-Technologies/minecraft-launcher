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

package com.micatechnologies.minecraft.launcher.game.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.BooleanSupplier;

/**
 * Microsoft sign-in with a code (OAuth 2.0 device authorization grant): the launcher shows a short
 * code, the user enters it at {@code microsoft.com/link} on any phone or computer, and the
 * launcher receives a refresh token. Made for the Steam Deck, where typing a password with a
 * controller is painful (plan decision D16).
 *
 * <p>Uses the same Microsoft account service, client ID and scope as the browser sign-in in
 * {@code minecraft_authenticator}, so the refresh token it returns is one that library already
 * knows how to redeem: {@link MCLauncherAuthManager} hands it over as a saved authentication file
 * and the library runs the usual Xbox Live, XSTS and Minecraft steps.
 *
 * @since 2026.11
 */
public final class DeviceCodeSignIn
{
    /** The Microsoft account client ID the launcher signs in with (the library's default). */
    static final String CLIENT_ID = "00000000402b5328";
    /** The scope the library's browser sign-in asks for. */
    static final String SCOPE = "XboxLive.signin offline_access";
    static final String CONNECT_URL = "https://login.live.com/oauth20_connect.srf";
    static final String TOKEN_URL = "https://login.live.com/oauth20_token.srf";
    static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";

    /** Posts a form and returns the response; {@link #standardHttp()} in production. */
    @FunctionalInterface
    public interface Http
    {
        /**
         * @param url  the endpoint
         * @param form the form fields
         *
         * @return the response body (also for error statuses: OAuth errors come as JSON)
         *
         * @throws IOException when the request fails
         */
        String post( String url, Map< String, String > form ) throws IOException;
    }

    /** Waits between polls; {@link Thread#sleep} in production. */
    @FunctionalInterface
    public interface Sleeper
    {
        /**
         * @param millis how long
         *
         * @throws InterruptedException when interrupted
         */
        void sleep( long millis ) throws InterruptedException;
    }

    /**
     * What the user needs: the code and where to enter it.
     *
     * @param userCode        the code to show, such as {@code ABCD-1234}
     * @param deviceCode      the secret the launcher polls with (never shown)
     * @param verificationUri where to enter the code
     * @param intervalSeconds how often Microsoft allows polling
     * @param expiresSeconds  how long the code is valid
     *
     * @since 2026.11
     */
    public record DeviceCode( String userCode, String deviceCode, String verificationUri, int intervalSeconds,
                              int expiresSeconds )
    {
        @Override
        public String toString()
        {
            // Never log the device code itself.
            return "DeviceCode[userCode=" + userCode + ", verificationUri=" + verificationUri + "]";
        }
    }

    /** How waiting for the user ended without a token. */
    public enum Failure
    {
        /** The code expired before the user entered it. */
        EXPIRED,
        /** The user declined on Microsoft's page. */
        DECLINED,
        /** The user cancelled in the launcher. */
        CANCELLED,
        /** Microsoft answered with something unexpected. */
        ERROR
    }

    /** Thrown when no token was obtained. */
    public static final class SignInException extends Exception
    {
        private final Failure failure;

        /**
         * @param failure why
         * @param message details for the log
         */
        public SignInException( Failure failure, String message )
        {
            super( message );
            this.failure = failure;
        }

        /** @return why no token was obtained */
        public Failure failure()
        {
            return failure;
        }
    }

    private final Http http;
    private final Sleeper sleeper;

    /**
     * A sign-in using the given transport.
     *
     * @param http    the HTTP transport
     * @param sleeper how to wait between polls
     *
     * @since 2026.11
     */
    public DeviceCodeSignIn( Http http, Sleeper sleeper )
    {
        this.http = http;
        this.sleeper = sleeper;
    }

    /**
     * The sign-in the launcher uses: Java's HTTP client and real waiting.
     *
     * @return the sign-in
     *
     * @since 2026.11
     */
    public static DeviceCodeSignIn standard()
    {
        return new DeviceCodeSignIn( standardHttp(), Thread::sleep );
    }

    /**
     * Asks Microsoft for a code to show the user.
     *
     * @return the code
     *
     * @throws IOException       when Microsoft can't be reached
     * @throws SignInException   when the answer has no code
     * @since 2026.11
     */
    public DeviceCode start() throws IOException, SignInException
    {
        Map< String, String > form = new LinkedHashMap<>();
        form.put( "client_id", CLIENT_ID );
        form.put( "scope", SCOPE );
        form.put( "response_type", "device_code" );
        JsonObject json = parse( http.post( CONNECT_URL, form ) );
        String userCode = string( json, "user_code" );
        String deviceCode = string( json, "device_code" );
        String uri = string( json, "verification_uri" );
        if ( userCode == null || deviceCode == null || uri == null ) {
            throw new SignInException( Failure.ERROR, "No device code in the answer: " + errorOf( json ) );
        }
        return new DeviceCode( userCode, deviceCode, uri, Math.max( 1, number( json, "interval", 5 ) ),
                               number( json, "expires_in", 900 ) );
    }

    /**
     * Waits for the user to enter the code, polling at the pace Microsoft asks for.
     *
     * @param code      the code from {@link #start()}
     * @param cancelled whether the user cancelled in the launcher, checked before every poll
     *
     * @return the refresh token
     *
     * @throws SignInException      when the code expired, was declined or cancelled
     * @throws IOException          when Microsoft can't be reached
     * @throws InterruptedException when interrupted while waiting
     * @since 2026.11
     */
    public String awaitRefreshToken( DeviceCode code, BooleanSupplier cancelled )
            throws SignInException, IOException, InterruptedException
    {
        long intervalMs = code.intervalSeconds() * 1000L;
        long remainingMs = code.expiresSeconds() * 1000L;
        Map< String, String > form = new LinkedHashMap<>();
        form.put( "client_id", CLIENT_ID );
        form.put( "grant_type", GRANT_TYPE );
        form.put( "device_code", code.deviceCode() );
        while ( remainingMs > 0 ) {
            if ( cancelled.getAsBoolean() ) {
                throw new SignInException( Failure.CANCELLED, "Cancelled" );
            }
            sleeper.sleep( intervalMs );
            remainingMs -= intervalMs;
            if ( cancelled.getAsBoolean() ) {
                throw new SignInException( Failure.CANCELLED, "Cancelled" );
            }
            JsonObject json = parse( http.post( TOKEN_URL, form ) );
            String refreshToken = string( json, "refresh_token" );
            if ( refreshToken != null ) {
                return refreshToken;
            }
            String error = errorOf( json );
            switch ( error ) {
                case "authorization_pending" -> { }
                case "slow_down" -> intervalMs += 5_000L;
                case "expired_token" -> throw new SignInException( Failure.EXPIRED, error );
                case "access_denied", "authorization_declined" -> throw new SignInException( Failure.DECLINED, error );
                default -> throw new SignInException( Failure.ERROR, error );
            }
        }
        throw new SignInException( Failure.EXPIRED, "The code expired" );
    }

    /**
     * Java's HTTP client posting {@code application/x-www-form-urlencoded}, with the system
     * proxy settings and a 15-second timeout.
     *
     * @return the transport
     *
     * @since 2026.11
     */
    public static Http standardHttp()
    {
        HttpClient client = HttpClient.newBuilder().connectTimeout( Duration.ofSeconds( 10 ) )
                                      .followRedirects( HttpClient.Redirect.NORMAL ).build();
        return ( url, form ) -> {
            HttpRequest request = HttpRequest.newBuilder( URI.create( url ) )
                                             .timeout( Duration.ofSeconds( 15 ) )
                                             .header( "Content-Type", "application/x-www-form-urlencoded" )
                                             .header( "Accept", "application/json" )
                                             .POST( HttpRequest.BodyPublishers.ofString( encode( form ) ) )
                                             .build();
            try {
                return client.send( request, HttpResponse.BodyHandlers.ofString() ).body();
            }
            catch ( InterruptedException e ) {
                Thread.currentThread().interrupt();
                throw new IOException( "Interrupted", e );
            }
        };
    }

    static String encode( Map< String, String > form )
    {
        StringJoiner body = new StringJoiner( "&" );
        form.forEach( ( k, v ) -> body.add( URLEncoder.encode( k, StandardCharsets.UTF_8 ) + "="
                                            + URLEncoder.encode( v, StandardCharsets.UTF_8 ) ) );
        return body.toString();
    }

    private static JsonObject parse( String body ) throws IOException
    {
        try {
            JsonElement element = JsonParser.parseString( body == null ? "" : body );
            if ( element.isJsonObject() ) {
                return element.getAsJsonObject();
            }
        }
        catch ( RuntimeException ignored ) {
            // Fall through.
        }
        throw new IOException( "Not a JSON answer from Microsoft" );
    }

    private static String errorOf( JsonObject json )
    {
        String error = string( json, "error" );
        return error == null ? "unknown" : error;
    }

    private static String string( JsonObject json, String key )
    {
        JsonElement v = json.get( key );
        return v != null && v.isJsonPrimitive() && !v.getAsString().isBlank() ? v.getAsString() : null;
    }

    private static int number( JsonObject json, String key, int fallback )
    {
        JsonElement v = json.get( key );
        try {
            return v != null && v.isJsonPrimitive() ? v.getAsInt() : fallback;
        }
        catch ( NumberFormatException e ) {
            return fallback;
        }
    }
}
