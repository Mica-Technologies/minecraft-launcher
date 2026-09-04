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

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The MCP bearer token's lifecycle: generate once, keep, regenerate on request.
 * <p>
 * <b>Why this is persisted rather than rotated per launch.</b> A client's configuration names a
 * URL and a token, and it is edited by hand. A token that changed every launch would mean
 * re-editing every client's config file every time the launcher started — which in practice
 * means nobody uses the feature, or everyone works around it. A stable token is the same trade
 * every local HTTP service makes, and the mitigations are the ones they use too: it is
 * loopback-only, it is stored with owner-only permissions, and it can be regenerated from
 * Settings, which immediately invalidates every client still holding the old one.
 * <p>
 * 256 bits from {@link SecureRandom}, hex-encoded, matching the single-instance IPC token.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class McpAccessToken
{
    /** Token length in bytes before hex encoding. */
    private static final int TOKEN_BYTES = 32;

    /** Hex characters a valid token has. */
    public static final int TOKEN_LENGTH = TOKEN_BYTES * 2;

    /**
     * Generates a fresh token.
     *
     * @return a 64-character hex token
     *
     * @since 3.0
     */
    public static String generate()
    {
        byte[] bytes = new byte[ TOKEN_BYTES ];
        new SecureRandom().nextBytes( bytes );
        return HexFormat.of().formatHex( bytes );
    }

    /**
     * Returns the stored token, generating and storing one when none exists yet.
     * <p>
     * A stored value that is not a well-formed token — blank, truncated by a partial write, or
     * hand-edited — is replaced rather than used. Serving a malformed token would leave the
     * server running with a credential no client could ever match, which looks like a hang
     * rather than a misconfiguration.
     *
     * @param read  reads the stored token
     * @param write stores a newly generated token
     *
     * @return a usable token
     *
     * @throws IllegalArgumentException if either accessor is {@code null}
     * @since 3.0
     */
    public static String ensure( Supplier< String > read, Consumer< String > write )
    {
        if ( read == null || write == null ) {
            throw new IllegalArgumentException( "A reader and writer are required" );
        }
        String existing;
        try {
            existing = read.get();
        }
        catch ( Exception e ) {
            existing = null;
        }
        if ( isWellFormed( existing ) ) {
            return existing;
        }
        String fresh = generate();
        try {
            write.accept( fresh );
        }
        catch ( Exception ignored ) {
            // A failed persist still yields a usable token for this run; the next launch simply
            // generates another. Refusing to start over a config write would be the worse
            // trade.
        }
        return fresh;
    }

    /**
     * Reports whether a stored value is a well-formed token.
     *
     * @param token the stored value
     *
     * @return {@code true} when it is exactly {@link #TOKEN_LENGTH} hex characters
     *
     * @since 3.0
     */
    public static boolean isWellFormed( String token )
    {
        if ( token == null || token.length() != TOKEN_LENGTH ) {
            return false;
        }
        for ( int i = 0; i < token.length(); i++ ) {
            char c = token.charAt( i );
            boolean hex = ( c >= '0' && c <= '9' ) || ( c >= 'a' && c <= 'f' ) || ( c >= 'A' && c <= 'F' );
            if ( !hex ) {
                return false;
            }
        }
        return true;
    }

    /**
     * Renders a token for display with all but its first few characters hidden, so a Settings
     * pane can show <em>which</em> token is in use without putting the whole thing on screen.
     *
     * @param token the token
     *
     * @return the masked form
     *
     * @since 3.0
     */
    public static String mask( String token )
    {
        if ( token == null || token.isEmpty() ) {
            return "";
        }
        int shown = Math.min( 8, token.length() );
        return token.substring( 0, shown ) + "… (" + token.length() + " characters)";
    }

    /**
     * Not instantiable.
     */
    private McpAccessToken()
    {
        throw new AssertionError( "McpAccessToken is a utility class and must not be instantiated" );
    }
}
