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

package com.micatechnologies.minecraft.launcher.mcp.transport;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link McpHttpGuard} — the admission checks on every inbound MCP HTTP request.
 *
 * <p>Why this matters: the realistic attacker against a loopback listener is not a remote
 * host, it is <b>a web page the user already has open</b>. A page can issue cross-origin
 * requests to {@code 127.0.0.1}, and DNS rebinding lets it do so under a hostname it
 * controls. Binding loopback stops none of that. These checks do.</p>
 *
 * <p>The token comparison is constant time for a specific reason worth restating: a loopback
 * attacker can make unlimited attempts, so an early-exit comparison leaking the matching
 * prefix length is enough to recover the token byte by byte.</p>
 */
class McpHttpGuardTest
{
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    // region origin policy

    /** Non-browser clients send no Origin at all, and they are the intended callers. */
    @Test
    void anAbsentOriginIsAllowed()
    {
        assertTrue( McpHttpGuard.isAllowedOrigin( null ) );
        assertTrue( McpHttpGuard.isAllowedOrigin( "" ) );
        assertTrue( McpHttpGuard.isAllowedOrigin( "   " ) );
    }

    @Test
    void loopbackOriginsAreAllowed()
    {
        assertTrue( McpHttpGuard.isAllowedOrigin( "http://localhost:3000" ) );
        assertTrue( McpHttpGuard.isAllowedOrigin( "http://127.0.0.1:8080" ) );
        assertTrue( McpHttpGuard.isAllowedOrigin( "http://localhost" ) );
        assertTrue( McpHttpGuard.isAllowedOrigin( "https://127.0.0.1:443" ) );
        assertTrue( McpHttpGuard.isAllowedOrigin( "HTTP://LOCALHOST:3000" ) );
    }

    /**
     * The DNS-rebinding case. A rebound page resolves its own hostname to 127.0.0.1, but the
     * browser still sends the attacker's origin — which is what makes this check work.
     */
    @Test
    void aRemoteOriginIsRefusedEvenWhenItResolvesToLoopback()
    {
        assertFalse( McpHttpGuard.isAllowedOrigin( "http://evil.example" ) );
        assertFalse( McpHttpGuard.isAllowedOrigin( "https://rebind.attacker.test:8080" ) );
    }

    /** A sandboxed iframe or file: page sends "null" — a browser withholding who it is. */
    @Test
    void aNullOriginIsRefused()
    {
        assertFalse( McpHttpGuard.isAllowedOrigin( "null" ) );
        assertFalse( McpHttpGuard.isAllowedOrigin( "NULL" ) );
    }

    /**
     * A hostname that merely contains a loopback name is not loopback. Substring matching here
     * would accept {@code http://localhost.evil.example}.
     */
    @Test
    void aHostnameThatOnlyLooksLoopbackIsRefused()
    {
        assertFalse( McpHttpGuard.isAllowedOrigin( "http://localhost.evil.example" ) );
        assertFalse( McpHttpGuard.isAllowedOrigin( "http://127.0.0.1.evil.example" ) );
        assertFalse( McpHttpGuard.isAllowedOrigin( "http://notlocalhost" ) );
        assertFalse( McpHttpGuard.isAllowedOrigin( "http://evil.example/?x=localhost" ) );
    }

    @Test
    void nonHttpSchemesAreRefused()
    {
        assertFalse( McpHttpGuard.isAllowedOrigin( "file://localhost" ) );
        assertFalse( McpHttpGuard.isAllowedOrigin( "chrome-extension://abcdef" ) );
        assertFalse( McpHttpGuard.isAllowedOrigin( "localhost:3000" ) );
    }

    @Test
    void ipv6LoopbackIsAllowed()
    {
        assertTrue( McpHttpGuard.isAllowedOrigin( "http://[::1]:3000" ) );
        assertTrue( McpHttpGuard.isAllowedOrigin( "http://[::1]" ) );
    }

    // endregion

    // region bearer token

    @Test
    void aBearerTokenIsExtracted()
    {
        assertEquals( TOKEN, McpHttpGuard.bearerTokenOf( "Bearer " + TOKEN ) );
        assertEquals( TOKEN, McpHttpGuard.bearerTokenOf( "bearer " + TOKEN ) );
        assertEquals( TOKEN, McpHttpGuard.bearerTokenOf( "  Bearer   " + TOKEN + "  " ) );
    }

    @Test
    void aNonBearerCredentialYieldsNoToken()
    {
        assertNull( McpHttpGuard.bearerTokenOf( null ) );
        assertNull( McpHttpGuard.bearerTokenOf( "" ) );
        assertNull( McpHttpGuard.bearerTokenOf( "Basic dXNlcjpwYXNz" ) );
        assertNull( McpHttpGuard.bearerTokenOf( "Bearer" ) );
        assertNull( McpHttpGuard.bearerTokenOf( "Bearer " ) );
        assertNull( McpHttpGuard.bearerTokenOf( TOKEN ) );
    }

    @Test
    void matchingTokensAreAccepted()
    {
        assertTrue( McpHttpGuard.tokensMatch( TOKEN, TOKEN ) );
    }

    @Test
    void anyDifferenceRejects()
    {
        assertFalse( McpHttpGuard.tokensMatch( TOKEN, TOKEN.substring( 0, TOKEN.length() - 1 ) + "0" ) );
        assertFalse( McpHttpGuard.tokensMatch( TOKEN, "9" + TOKEN.substring( 1 ) ) );
        assertFalse( McpHttpGuard.tokensMatch( TOKEN, TOKEN + "x" ) );
        assertFalse( McpHttpGuard.tokensMatch( TOKEN, TOKEN.toUpperCase() ) );
    }

    /**
     * An unset expected token must never match, or a server that failed to generate one would
     * accept every caller.
     */
    @Test
    void anEmptyOrMissingExpectedTokenNeverMatches()
    {
        assertFalse( McpHttpGuard.tokensMatch( null, TOKEN ) );
        assertFalse( McpHttpGuard.tokensMatch( "", "" ) );
        assertFalse( McpHttpGuard.tokensMatch( "", TOKEN ) );
        assertFalse( McpHttpGuard.tokensMatch( TOKEN, null ) );
    }

    // endregion

    // region content type

    @Test
    void jsonContentTypesAreAccepted()
    {
        assertTrue( McpHttpGuard.isJsonContentType( "application/json" ) );
        assertTrue( McpHttpGuard.isJsonContentType( "application/json; charset=utf-8" ) );
        assertTrue( McpHttpGuard.isJsonContentType( "  APPLICATION/JSON  " ) );
    }

    @Test
    void otherContentTypesAreRefused()
    {
        assertFalse( McpHttpGuard.isJsonContentType( null ) );
        assertFalse( McpHttpGuard.isJsonContentType( "text/plain" ) );
        assertFalse( McpHttpGuard.isJsonContentType( "application/x-www-form-urlencoded" ) );
        assertFalse( McpHttpGuard.isJsonContentType( "application/jsonx" ) );
        assertFalse( McpHttpGuard.isJsonContentType( "multipart/form-data" ) );
    }

    /**
     * The form content types are the ones a browser can send cross-origin without a preflight,
     * so refusing them is a second line of defence behind the origin check.
     */
    @Test
    void theSimpleRequestContentTypesAreRefused()
    {
        for ( String type : new String[]{ "text/plain", "application/x-www-form-urlencoded",
                                          "multipart/form-data" } ) {
            assertEquals( McpHttpGuard.Verdict.UNSUPPORTED_MEDIA_TYPE,
                          McpHttpGuard.admit( post( "Bearer " + TOKEN, null, type, 10 ), TOKEN ), type );
        }
    }

    // endregion

    // region loopback

    @Test
    void loopbackAddressesAreRecognised() throws Exception
    {
        assertTrue( McpHttpGuard.isLoopback( InetAddress.getByName( "127.0.0.1" ) ) );
        assertTrue( McpHttpGuard.isLoopback( InetAddress.getByName( "::1" ) ) );
    }

    /** An unresolved peer must never read as trusted. */
    @Test
    void anUnknownPeerIsNotLoopback() throws Exception
    {
        assertFalse( McpHttpGuard.isLoopback( null ) );
        assertFalse( McpHttpGuard.isLoopback( InetAddress.getByName( "93.184.216.34" ) ) );
    }

    // endregion

    // region admission

    @Test
    void aWellFormedLocalRequestIsAdmitted()
    {
        assertEquals( McpHttpGuard.Verdict.ALLOW,
                      McpHttpGuard.admit( post( "Bearer " + TOKEN, null, "application/json", 128 ), TOKEN ) );
    }

    @Test
    void aNonLoopbackPeerIsForbidden()
    {
        McpHttpGuard.Request request = new McpHttpGuard.Request(
                "POST", "Bearer " + TOKEN, null, "application/json", 10, false );
        assertEquals( McpHttpGuard.Verdict.FORBIDDEN, McpHttpGuard.admit( request, TOKEN ) );
    }

    @Test
    void aNullRequestIsForbidden()
    {
        assertEquals( McpHttpGuard.Verdict.FORBIDDEN, McpHttpGuard.admit( null, TOKEN ) );
    }

    /**
     * Ordering property: a foreign origin is refused even carrying a valid token. A page that
     * somehow learned the token still must not be able to drive the server.
     */
    @Test
    void aForeignOriginIsForbiddenEvenWithAValidToken()
    {
        assertEquals( McpHttpGuard.Verdict.FORBIDDEN,
                      McpHttpGuard.admit( post( "Bearer " + TOKEN, "http://evil.example",
                                                "application/json", 10 ), TOKEN ) );
    }

    @Test
    void aMissingOrWrongTokenIsUnauthorized()
    {
        assertEquals( McpHttpGuard.Verdict.UNAUTHORIZED,
                      McpHttpGuard.admit( post( null, null, "application/json", 10 ), TOKEN ) );
        assertEquals( McpHttpGuard.Verdict.UNAUTHORIZED,
                      McpHttpGuard.admit( post( "Bearer wrong", null, "application/json", 10 ), TOKEN ) );
    }

    /**
     * Ordering property: authentication precedes the method, size, and media-type checks, so
     * an unauthenticated caller cannot use their verdicts to probe the server.
     */
    @Test
    void anUnauthenticatedRequestIsRejectedBeforeAnyOtherCheck()
    {
        McpHttpGuard.Request malformed = new McpHttpGuard.Request(
                "GET", null, null, "text/plain", MAX_PLUS_ONE, true );
        assertEquals( McpHttpGuard.Verdict.UNAUTHORIZED, McpHttpGuard.admit( malformed, TOKEN ) );
    }

    @Test
    void nonPostMethodsAreRefused()
    {
        for ( String method : new String[]{ "GET", "PUT", "DELETE", "OPTIONS", "HEAD" } ) {
            McpHttpGuard.Request request = new McpHttpGuard.Request(
                    method, "Bearer " + TOKEN, null, "application/json", 10, true );
            assertEquals( McpHttpGuard.Verdict.METHOD_NOT_ALLOWED, McpHttpGuard.admit( request, TOKEN ),
                          method );
        }
    }

    @Test
    void anOversizedBodyIsRefused()
    {
        assertEquals( McpHttpGuard.Verdict.PAYLOAD_TOO_LARGE,
                      McpHttpGuard.admit( post( "Bearer " + TOKEN, null, "application/json",
                                                MAX_PLUS_ONE ), TOKEN ) );
    }

    @Test
    void aBodyExactlyAtTheCapIsAccepted()
    {
        assertEquals( McpHttpGuard.Verdict.ALLOW,
                      McpHttpGuard.admit( post( "Bearer " + TOKEN, null, "application/json",
                                                McpHttpGuard.MAX_BODY_BYTES ), TOKEN ) );
    }

    /** An unknown length (chunked encoding) passes the declared-size check and is capped later. */
    @Test
    void anUnknownBodyLengthPassesTheDeclaredSizeCheck()
    {
        assertEquals( McpHttpGuard.Verdict.ALLOW,
                      McpHttpGuard.admit( post( "Bearer " + TOKEN, null, "application/json", -1 ), TOKEN ) );
    }

    // endregion

    private static final long MAX_PLUS_ONE = McpHttpGuard.MAX_BODY_BYTES + 1;

    /**
     * Builds a loopback POST request with the given headers.
     *
     * @param authorization the {@code Authorization} header, or {@code null}
     * @param origin        the {@code Origin} header, or {@code null}
     * @param contentType   the {@code Content-Type} header, or {@code null}
     * @param length        the declared body length
     *
     * @return the request metadata
     */
    private static McpHttpGuard.Request post( String authorization, String origin, String contentType,
                                              long length )
    {
        return new McpHttpGuard.Request( "POST", authorization, origin, contentType, length, true );
    }
}
