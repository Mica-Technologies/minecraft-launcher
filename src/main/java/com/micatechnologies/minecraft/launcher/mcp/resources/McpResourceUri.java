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

package com.micatechnologies.minecraft.launcher.mcp.resources;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Matching and building for the launcher's {@code mica://} resource URIs.
 * <p>
 * Templates look like {@code mica://modpack/{friendlyName}/manifest}. Matching a concrete URI
 * against one yields the decoded placeholder values, which then become a lookup key — a
 * modpack's friendly name, for instance.
 * <p>
 * <b>This is a containment gate, not just a parser.</b> A placeholder value comes from a
 * model-chosen URI and is used to select a modpack, which in turn names a directory. A value
 * that survives matching is guaranteed to be a single non-blank path segment: values
 * containing a slash, a backslash, a NUL, or a control character are rejected, as are
 * {@code "."} and {@code ".."}. The percent-decoding happens <em>before</em> those checks, so
 * {@code %2e%2e%2f} is rejected exactly like {@code ../} — checking the encoded form first is
 * the classic way this kind of gate is bypassed.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class McpResourceUri
{
    /** The scheme every launcher resource URI uses. */
    public static final String SCHEME = "mica://";

    /**
     * Matches a concrete URI against a template.
     *
     * @param template the template, e.g. {@code mica://modpack/{friendlyName}/manifest}
     * @param uri      the concrete URI to match
     *
     * @return the decoded placeholder values keyed by placeholder name, or {@code null} when
     *         the URI does not match the template or any extracted value fails the containment
     *         checks
     *
     * @since 3.0
     */
    public static Map< String, String > match( String template, String uri )
    {
        if ( template == null || uri == null ) {
            return null;
        }

        String[] templateParts = template.split( "/", -1 );
        String[] uriParts = uri.split( "/", -1 );
        if ( templateParts.length != uriParts.length ) {
            return null;
        }

        Map< String, String > values = new LinkedHashMap<>();
        for ( int i = 0; i < templateParts.length; i++ ) {
            String templatePart = templateParts[ i ];
            String uriPart = uriParts[ i ];

            if ( templatePart.length() > 2 && templatePart.startsWith( "{" ) && templatePart.endsWith( "}" ) ) {
                String key = templatePart.substring( 1, templatePart.length() - 1 );
                String decoded = decode( uriPart );
                if ( !isSafeSegment( decoded ) ) {
                    return null;
                }
                values.put( key, decoded );
            }
            else if ( !templatePart.equals( uriPart ) ) {
                return null;
            }
        }
        return Collections.unmodifiableMap( values );
    }

    /**
     * Reports whether a template contains at least one placeholder.
     *
     * @param template the template to inspect
     *
     * @return {@code true} when the template is parameterized
     *
     * @since 3.0
     */
    public static boolean isTemplate( String template )
    {
        return template != null && template.contains( "{" ) && template.contains( "}" );
    }

    /**
     * Builds a concrete URI by substituting one value into a single-placeholder template,
     * percent-encoding it so names containing spaces or reserved characters round-trip.
     *
     * @param template the template to fill
     * @param value    the value to substitute
     *
     * @return the concrete URI
     *
     * @throws IllegalArgumentException if the template or value is {@code null}, or the value
     *                                  is not a safe single segment
     * @since 3.0
     */
    public static String build( String template, String value )
    {
        if ( template == null || value == null ) {
            throw new IllegalArgumentException( "A template and value are required" );
        }
        if ( !isSafeSegment( value ) ) {
            throw new IllegalArgumentException( "Value is not a valid URI segment" );
        }
        return template.replaceAll( "\\{[^/{}]+}", encode( value ) );
    }

    /**
     * Reports whether a decoded value is a single, safe path segment.
     * <p>
     * Rejects blank values, {@code "."} and {@code ".."}, anything containing a slash,
     * backslash, or NUL, and anything containing a control character.
     *
     * @param decoded the already-decoded value to check
     *
     * @return {@code true} when the value is safe to use as a lookup key
     *
     * @since 3.0
     */
    public static boolean isSafeSegment( String decoded )
    {
        if ( decoded == null || decoded.isBlank() ) {
            return false;
        }
        if ( decoded.equals( "." ) || decoded.equals( ".." ) ) {
            return false;
        }
        for ( int i = 0; i < decoded.length(); i++ ) {
            char c = decoded.charAt( i );
            if ( c == '/' || c == '\\' || c == '\0' || Character.isISOControl( c ) ) {
                return false;
            }
        }
        return true;
    }

    /**
     * Percent-decodes a URI segment, returning it unchanged when it is not valid encoding.
     * <p>
     * Returning the raw text on a decode failure is the safe direction: the containment checks
     * then run against text that still contains the offending percent sequence, so a
     * deliberately malformed encoding cannot slip a separator through as an unchecked value.
     *
     * @param segment the segment to decode
     *
     * @return the decoded segment
     */
    private static String decode( String segment )
    {
        try {
            return URLDecoder.decode( segment, StandardCharsets.UTF_8 );
        }
        catch ( Exception e ) {
            return segment;
        }
    }

    /**
     * Percent-encodes a URI segment. {@link URLEncoder} targets form encoding, which renders a
     * space as {@code +}; that is wrong inside a path, so it is corrected here.
     *
     * @param value the value to encode
     *
     * @return the encoded segment
     */
    private static String encode( String value )
    {
        return URLEncoder.encode( value, StandardCharsets.UTF_8 ).replace( "+", "%20" );
    }

    /**
     * Not instantiable.
     */
    private McpResourceUri()
    {
        throw new AssertionError( "McpResourceUri is a utility class and must not be instantiated" );
    }
}
