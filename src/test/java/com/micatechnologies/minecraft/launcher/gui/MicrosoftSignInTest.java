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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MicrosoftSignIn#parseCallback}: what the OAuth redirect carried. Getting
 * this wrong either drops a good sign-in or tries to redeem an error as a code.
 */
class MicrosoftSignInTest
{
    private static final String REDIRECT = "https://login.live.com/oauth20_desktop.srf";

    @Test
    void aCodeIsReturned()
    {
        MicrosoftSignIn.Callback c = MicrosoftSignIn.parseCallback( REDIRECT + "?code=M.C123_abc&lc=1033" );
        assertEquals( "M.C123_abc", c.code() );
        assertNull( c.error() );
    }

    @Test
    void theCodeIsUrlDecoded()
    {
        assertEquals( "a b/c", MicrosoftSignIn.parseCallback( REDIRECT + "?code=a%20b%2Fc" ).code() );
    }

    @Test
    void anErrorWinsOverACode()
    {
        MicrosoftSignIn.Callback c = MicrosoftSignIn.parseCallback(
                REDIRECT + "?error=access_denied&error_description=The+user+has+denied+access+to+the+scope&code=x" );
        assertNull( c.code() );
        assertEquals( "The user has denied access to the scope", c.error() );
        assertTrue( c.userCancelled() );
    }

    @Test
    void aServerErrorIsNotACancellation()
    {
        MicrosoftSignIn.Callback c = MicrosoftSignIn.parseCallback(
                REDIRECT + "?error=server_error&error_description=Something%20broke" );
        assertEquals( "Something broke", c.error() );
        assertFalse( c.userCancelled() );
    }

    @Test
    void aRedirectWithNeitherIsAnUnknownError()
    {
        MicrosoftSignIn.Callback c = MicrosoftSignIn.parseCallback( REDIRECT + "?lc=1033" );
        assertNull( c.code() );
        assertEquals( "", c.error() );
        assertNull( MicrosoftSignIn.parseCallback( REDIRECT ).code() );
        assertNull( MicrosoftSignIn.parseCallback( null ).code() );
    }

    @Test
    void aFragmentIsIgnored()
    {
        assertEquals( "abc", MicrosoftSignIn.parseCallback( REDIRECT + "?code=abc#frag" ).code() );
    }
}
