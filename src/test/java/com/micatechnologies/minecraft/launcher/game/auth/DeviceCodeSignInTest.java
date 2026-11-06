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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeviceCodeSignInTest
{
    /** Answers posts from a script and records them; sleeping only adds up the time. */
    private static final class Script implements DeviceCodeSignIn.Http, DeviceCodeSignIn.Sleeper
    {
        final Deque< String > answers = new ArrayDeque<>();
        final List< String > urls = new ArrayList<>();
        final List< Map< String, String > > forms = new ArrayList<>();
        final List< Long > sleeps = new ArrayList<>();

        Script answer( String json )
        {
            answers.add( json );
            return this;
        }

        @Override
        public String post( String url, Map< String, String > form )
        {
            urls.add( url );
            forms.add( new LinkedHashMap<>( form ) );
            return answers.isEmpty() ? "{\"error\":\"authorization_pending\"}" : answers.poll();
        }

        @Override
        public void sleep( long millis )
        {
            sleeps.add( millis );
        }

        DeviceCodeSignIn signIn()
        {
            return new DeviceCodeSignIn( this, this );
        }
    }

    private static DeviceCodeSignIn.DeviceCode code( int interval, int expires )
    {
        return new DeviceCodeSignIn.DeviceCode( "ABCD-1234", "secret-device-code", "https://www.microsoft.com/link",
                                                interval, expires );
    }

    @Test
    void startAsksForACodeWithTheLibrarysClientAndScope() throws Exception
    {
        Script script = new Script().answer( """
                {"user_code":"ABCD-1234","device_code":"secret","verification_uri":"https://www.microsoft.com/link",
                 "interval":5,"expires_in":900}""" );

        DeviceCodeSignIn.DeviceCode code = script.signIn().start();

        assertEquals( "ABCD-1234", code.userCode() );
        assertEquals( "https://www.microsoft.com/link", code.verificationUri() );
        assertEquals( 5, code.intervalSeconds() );
        assertEquals( DeviceCodeSignIn.CONNECT_URL, script.urls.get( 0 ) );
        assertEquals( Map.of( "client_id", "00000000402b5328", "scope", "XboxLive.signin offline_access",
                              "response_type", "device_code" ), script.forms.get( 0 ) );
        assertFalse( code.toString().contains( "secret" ), "the device code never reaches a log" );
    }

    @Test
    void anAnswerWithoutACodeFails()
    {
        Script script = new Script().answer( "{\"error\":\"invalid_client\"}" );

        DeviceCodeSignIn.SignInException e = assertThrows( DeviceCodeSignIn.SignInException.class,
                                                           () -> script.signIn().start() );
        assertEquals( DeviceCodeSignIn.Failure.ERROR, e.failure() );
    }

    @Test
    void pollsUntilTheUserHasEnteredTheCode() throws Exception
    {
        Script script = new Script().answer( "{\"error\":\"authorization_pending\"}" )
                                    .answer( "{\"error\":\"authorization_pending\"}" )
                                    .answer( "{\"access_token\":\"a\",\"refresh_token\":\"refresh-1\"}" );

        String token = script.signIn().awaitRefreshToken( code( 5, 900 ), () -> false );

        assertEquals( "refresh-1", token );
        assertEquals( List.of( 5000L, 5000L, 5000L ), script.sleeps );
        assertEquals( DeviceCodeSignIn.GRANT_TYPE, script.forms.get( 0 ).get( "grant_type" ) );
        assertEquals( "secret-device-code", script.forms.get( 0 ).get( "device_code" ) );
    }

    @Test
    void slowDownWidensTheInterval() throws Exception
    {
        Script script = new Script().answer( "{\"error\":\"slow_down\"}" )
                                    .answer( "{\"refresh_token\":\"r\"}" );

        script.signIn().awaitRefreshToken( code( 5, 900 ), () -> false );

        assertEquals( List.of( 5000L, 10000L ), script.sleeps );
    }

    @Test
    void expiryDeclineAndCancelAreToldApart()
    {
        Script expired = new Script().answer( "{\"error\":\"expired_token\"}" );
        Script declined = new Script().answer( "{\"error\":\"access_denied\"}" );
        Script odd = new Script().answer( "{\"error\":\"invalid_grant\"}" );

        assertEquals( DeviceCodeSignIn.Failure.EXPIRED, failure( expired, code( 5, 900 ), false ) );
        assertEquals( DeviceCodeSignIn.Failure.DECLINED, failure( declined, code( 5, 900 ), false ) );
        assertEquals( DeviceCodeSignIn.Failure.ERROR, failure( odd, code( 5, 900 ), false ) );
        assertEquals( DeviceCodeSignIn.Failure.CANCELLED, failure( new Script(), code( 5, 900 ), true ) );
    }

    @Test
    void stopsWhenTheCodeRunsOut()
    {
        Script script = new Script();

        assertEquals( DeviceCodeSignIn.Failure.EXPIRED, failure( script, code( 5, 12 ), false ) );
        assertEquals( 3, script.urls.size() );
    }

    @Test
    void aNonJsonAnswerIsAnError()
    {
        Script script = new Script().answer( "<html>busy</html>" );

        assertThrows( IOException.class, () -> script.signIn().start() );
    }

    @Test
    void formsAreUrlEncoded()
    {
        Map< String, String > form = new LinkedHashMap<>();
        form.put( "scope", "XboxLive.signin offline_access" );
        form.put( "grant_type", DeviceCodeSignIn.GRANT_TYPE );

        assertEquals( "scope=XboxLive.signin+offline_access&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type"
                      + "%3Adevice_code", DeviceCodeSignIn.encode( form ) );
    }

    private static DeviceCodeSignIn.Failure failure( Script script, DeviceCodeSignIn.DeviceCode code,
                                                     boolean cancelled )
    {
        try {
            script.signIn().awaitRefreshToken( code, () -> cancelled );
            throw new AssertionError( "expected a failure" );
        }
        catch ( DeviceCodeSignIn.SignInException e ) {
            return e.failure();
        }
        catch ( Exception e ) {
            throw new AssertionError( e );
        }
    }
}
