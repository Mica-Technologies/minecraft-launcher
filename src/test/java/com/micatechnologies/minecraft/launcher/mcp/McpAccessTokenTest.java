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

package com.micatechnologies.minecraft.launcher.mcp;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link McpAccessToken} — the bearer token's lifecycle.
 *
 * <p>This token is persisted rather than rotated per launch, which is a deliberate trade. A
 * rotating token would mean re-editing every client's configuration file every time the
 * launcher started, which in practice means the feature goes unused or gets worked around. The
 * mitigations are the ones every local HTTP service uses: loopback only, stored with owner-only
 * permissions, and regenerable on demand.</p>
 *
 * <p>Given it is long-lived, two properties matter more than they would for an ephemeral one.
 * It must be <b>unguessable</b> — 256 bits from {@code SecureRandom}, never a counter or a
 * timestamp. And a stored value that is <b>malformed</b> must be replaced rather than served:
 * a truncated or hand-edited token would leave the server running with a credential no client
 * could match, which presents as a hang rather than as a misconfiguration.</p>
 */
class McpAccessTokenTest
{
    // region generation

    @Test
    void aGeneratedTokenIs256BitsOfHex()
    {
        String token = McpAccessToken.generate();
        assertEquals( 64, token.length() );
        assertTrue( token.matches( "[0-9a-f]{64}" ), token );
    }

    /** Unguessable, not merely unique — a counter would satisfy uniqueness and fail this. */
    @Test
    void generatedTokensDoNotRepeat()
    {
        Set< String > seen = new HashSet<>();
        for ( int i = 0; i < 500; i++ ) {
            assertTrue( seen.add( McpAccessToken.generate() ), "a token repeated" );
        }
    }

    // endregion

    // region ensure

    @Test
    void aStoredTokenIsReusedRatherThanReplaced()
    {
        String existing = McpAccessToken.generate();
        AtomicReference< String > written = new AtomicReference<>();
        assertEquals( existing, McpAccessToken.ensure( () -> existing, written::set ) );
        assertEquals( null, written.get(), "an existing token must not be overwritten" );
    }

    @Test
    void aFirstRunGeneratesAndPersists()
    {
        AtomicReference< String > stored = new AtomicReference<>( "" );
        String token = McpAccessToken.ensure( stored::get, stored::set );
        assertTrue( McpAccessToken.isWellFormed( token ) );
        assertEquals( token, stored.get(), "the generated token must be persisted" );
    }

    /**
     * The property that keeps a bad config from presenting as a hang. A truncated write or a
     * hand-edited value would otherwise leave the server demanding a credential no client can
     * produce.
     */
    @Test
    void aMalformedStoredTokenIsReplaced()
    {
        for ( String bad : new String[]{ null, "", "   ", "short", "not-hex-at-all",
                                         "0123456789abcdef",
                                         "z".repeat( 64 ),
                                         "0".repeat( 63 ),
                                         "0".repeat( 65 ) } ) {
            AtomicReference< String > stored = new AtomicReference<>( bad );
            String token = McpAccessToken.ensure( stored::get, stored::set );
            assertTrue( McpAccessToken.isWellFormed( token ), "for stored value: " + bad );
            assertNotEquals( bad, token );
        }
    }

    /** Upper-case hex is a legitimate token; it should not be discarded. */
    @Test
    void anUpperCaseStoredTokenIsAccepted()
    {
        String upper = McpAccessToken.generate().toUpperCase();
        assertEquals( upper, McpAccessToken.ensure( () -> upper, value -> { } ) );
    }

    /** A config read that throws must not stop the server from starting. */
    @Test
    void aFailedReadStillYieldsAUsableToken()
    {
        String token = McpAccessToken.ensure( () -> {
            throw new IllegalStateException( "config unavailable" );
        }, value -> { } );
        assertTrue( McpAccessToken.isWellFormed( token ) );
    }

    /** Nor must a failed write — this run simply uses a token the next run regenerates. */
    @Test
    void aFailedWriteStillYieldsAUsableToken()
    {
        String token = McpAccessToken.ensure( () -> "", value -> {
            throw new IllegalStateException( "disk full" );
        } );
        assertTrue( McpAccessToken.isWellFormed( token ) );
    }

    @Test
    void bothAccessorsAreRequired()
    {
        assertThrows( IllegalArgumentException.class,
                      () -> McpAccessToken.ensure( null, value -> { } ) );
        assertThrows( IllegalArgumentException.class,
                      () -> McpAccessToken.ensure( () -> "", null ) );
    }

    // endregion

    // region masking

    /**
     * Settings shows which token is in use without putting the whole thing on screen. Enough
     * prefix to tell two apart, not enough to use.
     */
    @Test
    void maskingShowsAPrefixAndHidesTheRest()
    {
        String token = "0123456789abcdef".repeat( 4 );
        String masked = McpAccessToken.mask( token );
        assertTrue( masked.startsWith( "01234567" ), masked );
        assertFalse( masked.contains( token ), masked );
        assertTrue( masked.contains( "64" ), "it should say how long the real one is: " + masked );
    }

    @Test
    void maskingHandlesAnAbsentToken()
    {
        assertEquals( "", McpAccessToken.mask( null ) );
        assertEquals( "", McpAccessToken.mask( "" ) );
    }

    @Test
    void maskingNeverOverrunsAShortValue()
    {
        assertFalse( McpAccessToken.mask( "abc" ).isEmpty() );
    }

    // endregion
}
