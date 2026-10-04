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

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Admission checks applied to every inbound MCP HTTP request, before a single byte of body is
 * parsed.
 * <p>
 * The listener binds loopback only, but binding is not the whole defence. The realistic
 * attacker here is not a remote host — it is <b>a web page the user already has open</b>.
 * A page can issue cross-origin requests to {@code 127.0.0.1}, and DNS rebinding lets it do so
 * with a hostname it controls. So this class rejects any request carrying a browser
 * {@code Origin} that is not itself loopback, requires a bearer token the page cannot read,
 * and refuses anything that does not look like a JSON-RPC call.
 * <p>
 * Every check is a pure function of the request's metadata, which is what allows the whole
 * admission table to be tested without opening a socket.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class McpHttpGuard
{
    /**
     * Largest request body accepted, in bytes. A JSON-RPC tool call is small; the cap exists so
     * a local process cannot exhaust launcher memory by streaming an unbounded body.
     */
    public static final long MAX_BODY_BYTES = 1L << 20;

    /** The media type a JSON-RPC request must declare. */
    public static final String JSON_CONTENT_TYPE = "application/json";

    /** The result of the admission check. */
    public enum Verdict
    {
        /** The request may be parsed and dispatched. */
        ALLOW,

        /** Missing or wrong bearer token. Answer {@code 401} with no detail. */
        UNAUTHORIZED,

        /** Non-loopback peer, or a browser origin that is not loopback. Answer {@code 403}. */
        FORBIDDEN,

        /** Neither a POST nor a session-ending DELETE. Answer {@code 405}. */
        METHOD_NOT_ALLOWED,

        /** Body exceeds {@link #MAX_BODY_BYTES}. Answer {@code 413}. */
        PAYLOAD_TOO_LARGE,

        /** Body is not declared as JSON. Answer {@code 415}. */
        UNSUPPORTED_MEDIA_TYPE
    }

    /**
     * The request metadata the admission check needs.
     *
     * @param httpMethod          the HTTP method, e.g. {@code POST}
     * @param authorizationHeader the {@code Authorization} header, or {@code null}
     * @param originHeader        the {@code Origin} header, or {@code null} when absent — which
     *                            is the normal case for a non-browser client
     * @param contentTypeHeader   the {@code Content-Type} header, or {@code null}
     * @param contentLength       the declared body length in bytes; negative when unknown
     * @param remoteIsLoopback    whether the peer address is a loopback address
     *
     * @since 3.0
     */
    public record Request( String httpMethod,
                           String authorizationHeader,
                           String originHeader,
                           String contentTypeHeader,
                           long contentLength,
                           boolean remoteIsLoopback )
    {
    }

    /**
     * Decides whether a request may proceed.
     * <p>
     * Ordering is deliberate. The peer and origin checks come first because they are absolute:
     * a request from off-loopback, or from a browser page on another origin, is refused
     * regardless of whether it happens to carry a valid token. Authentication comes next, so
     * nothing beyond it — method, size, media type — can be used to probe the server without a
     * token.
     *
     * @param request       the request metadata
     * @param expectedToken the per-launch bearer token the server issued
     *
     * @return the verdict
     *
     * @since 3.0
     */
    public static Verdict admit( Request request, String expectedToken )
    {
        if ( request == null || !request.remoteIsLoopback() ) {
            return Verdict.FORBIDDEN;
        }
        if ( !isAllowedOrigin( request.originHeader() ) ) {
            return Verdict.FORBIDDEN;
        }
        if ( !tokensMatch( expectedToken, bearerTokenOf( request.authorizationHeader() ) ) ) {
            return Verdict.UNAUTHORIZED;
        }
        if ( "DELETE".equalsIgnoreCase( request.httpMethod() ) ) {
            // Ending a session: no body, so the size and media-type checks do not apply.
            return Verdict.ALLOW;
        }
        if ( !"POST".equalsIgnoreCase( request.httpMethod() ) ) {
            return Verdict.METHOD_NOT_ALLOWED;
        }
        if ( request.contentLength() > MAX_BODY_BYTES ) {
            return Verdict.PAYLOAD_TOO_LARGE;
        }
        if ( !isJsonContentType( request.contentTypeHeader() ) ) {
            return Verdict.UNSUPPORTED_MEDIA_TYPE;
        }
        return Verdict.ALLOW;
    }

    /**
     * Reports whether an {@code Origin} header is acceptable.
     * <p>
     * An <b>absent</b> origin is allowed: non-browser clients do not send one, and they are the
     * intended callers. A <b>present</b> origin means a browser is calling, and is allowed only
     * when it is itself loopback — which is what blunts DNS rebinding, since a rebound page
     * still carries the attacker's own origin.
     *
     * @param origin the {@code Origin} header value, or {@code null}
     *
     * @return {@code true} when the request may proceed on origin grounds
     *
     * @since 3.0
     */
    public static boolean isAllowedOrigin( String origin )
    {
        if ( origin == null || origin.isBlank() ) {
            return true;
        }
        String value = origin.trim().toLowerCase( Locale.ROOT );

        // "null" is what a browser sends from a sandboxed iframe or a file: page. It conveys
        // that a browser is calling while withholding who it is, so it is refused.
        if ( value.equals( "null" ) ) {
            return false;
        }

        String hostPort = stripScheme( value );
        if ( hostPort == null ) {
            return false;
        }
        String host = hostOf( hostPort );
        return host.equals( "localhost" ) || host.equals( "127.0.0.1" ) || host.equals( "[::1]" )
                || host.equals( "::1" );
    }

    /**
     * Extracts the token from an {@code Authorization: Bearer <token>} header.
     *
     * @param authorizationHeader the header value, or {@code null}
     *
     * @return the token, or {@code null} when the header is missing or not a bearer credential
     *
     * @since 3.0
     */
    public static String bearerTokenOf( String authorizationHeader )
    {
        if ( authorizationHeader == null ) {
            return null;
        }
        String trimmed = authorizationHeader.trim();
        if ( trimmed.length() < 7 || !trimmed.substring( 0, 7 ).equalsIgnoreCase( "Bearer " ) ) {
            return null;
        }
        String token = trimmed.substring( 7 ).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Compares two tokens in constant time with respect to their content.
     * <p>
     * A naive {@link String#equals} returns as soon as it finds a differing character, which
     * leaks the length of the matching prefix to anything that can time the response. Since a
     * local attacker can make unlimited attempts against a loopback listener, that leak is
     * enough to recover a token byte by byte.
     * <p>
     * The comparison is constant time in the content; a length difference is still
     * distinguishable, which is the standard and accepted property of
     * {@link MessageDigest#isEqual}.
     *
     * @param expected  the token the server issued
     * @param presented the token the caller supplied
     *
     * @return {@code true} when both are present and equal
     *
     * @since 3.0
     */
    public static boolean tokensMatch( String expected, String presented )
    {
        if ( expected == null || presented == null || expected.isEmpty() ) {
            return false;
        }
        return MessageDigest.isEqual( expected.getBytes( StandardCharsets.UTF_8 ),
                                      presented.getBytes( StandardCharsets.UTF_8 ) );
    }

    /**
     * Reports whether a {@code Content-Type} declares JSON, ignoring any parameters such as
     * {@code ; charset=utf-8}.
     *
     * @param contentType the header value, or {@code null}
     *
     * @return {@code true} when the body is declared as JSON
     *
     * @since 3.0
     */
    public static boolean isJsonContentType( String contentType )
    {
        if ( contentType == null ) {
            return false;
        }
        String base = contentType.trim().toLowerCase( Locale.ROOT );
        int parameterStart = base.indexOf( ';' );
        if ( parameterStart >= 0 ) {
            base = base.substring( 0, parameterStart ).trim();
        }
        return base.equals( JSON_CONTENT_TYPE );
    }

    /**
     * Reports whether an address is loopback, treating {@code null} as not loopback so a
     * failure to resolve the peer never reads as trusted.
     *
     * @param address the peer address
     *
     * @return {@code true} when the address is a loopback address
     *
     * @since 3.0
     */
    public static boolean isLoopback( InetAddress address )
    {
        return address != null && address.isLoopbackAddress();
    }

    /**
     * Splits the host out of a {@code host[:port]} authority, handling the bracketed form an
     * IPv6 literal uses. Splitting on the first colon would truncate {@code [::1]} to
     * {@code [}, so the bracketed form is matched to its closing bracket instead.
     *
     * @param hostPort the authority component
     *
     * @return the host, including brackets for an IPv6 literal
     */
    private static String hostOf( String hostPort )
    {
        if ( hostPort.startsWith( "[" ) ) {
            int close = hostPort.indexOf( ']' );
            return close < 0 ? hostPort : hostPort.substring( 0, close + 1 );
        }
        int colon = hostPort.indexOf( ':' );
        return colon < 0 ? hostPort : hostPort.substring( 0, colon );
    }

    /**
     * Strips the scheme from an origin, rejecting schemes other than {@code http} and
     * {@code https}.
     *
     * @param origin the lower-cased origin
     *
     * @return the host and optional port, or {@code null} when the scheme is unacceptable
     */
    private static String stripScheme( String origin )
    {
        if ( origin.startsWith( "http://" ) ) {
            return origin.substring( 7 );
        }
        if ( origin.startsWith( "https://" ) ) {
            return origin.substring( 8 );
        }
        return null;
    }

    /**
     * Not instantiable.
     */
    private McpHttpGuard()
    {
        throw new AssertionError( "McpHttpGuard is a utility class and must not be instantiated" );
    }
}
