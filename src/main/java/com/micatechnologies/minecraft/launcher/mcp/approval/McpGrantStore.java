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

package com.micatechnologies.minecraft.launcher.mcp.approval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The consent a user has granted for the current run: which client may call which tool, and
 * until when.
 * <p>
 * Grants are keyed to the <b>client name plus the tool</b> rather than to a transport
 * connection. MCP clients reconnect frequently, and a connection-scoped grant would re-prompt
 * every time — which trains a user to click through prompts without reading them, defeating the
 * point of asking. The TTL is what keeps "allow for this session" from quietly becoming
 * permanent.
 * <p>
 * Grants live in memory only. Nothing here survives a launcher restart: persistent permission
 * is the per-tool policy in Settings, which is a separate, deliberate choice by the user.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class McpGrantStore
{
    /**
     * Joins the client and tool halves of a key. NUL cannot appear in a tool name — the
     * registry's name pattern excludes it — and a printable separator would be ambiguous: with
     * a space, a client called {@code "a b"} calling {@code c} and a client called {@code "a"}
     * calling {@code "b c"} would produce the same key.
     */
    private static final char KEY_SEPARATOR = '\0';

    /** Expiry time in epoch milliseconds, keyed by client and tool. */
    private final Map< String, Long > expiryByKey = new ConcurrentHashMap<>();

    /**
     * One live grant, for display on the Settings page.
     *
     * @param clientName  the client the grant belongs to
     * @param toolName    the tool it covers
     * @param expiresAtMs when it lapses, in epoch milliseconds
     *
     * @since 3.0
     */
    public record Grant( String clientName, String toolName, long expiresAtMs )
    {
    }

    /**
     * Records consent for a client and tool.
     *
     * @param clientName  the client's self-reported name
     * @param toolName    the tool consented to
     * @param expiresAtMs when the grant lapses, in epoch milliseconds
     *
     * @since 3.0
     */
    public void grant( String clientName, String toolName, long expiresAtMs )
    {
        String key = keyOf( clientName, toolName );
        if ( key == null ) {
            return;
        }
        expiryByKey.put( key, expiresAtMs );
    }

    /**
     * Returns when a grant lapses.
     *
     * @param clientName the client's self-reported name
     * @param toolName   the tool
     *
     * @return the expiry in epoch milliseconds, or {@code 0} when no grant exists
     *
     * @since 3.0
     */
    public long expiryFor( String clientName, String toolName )
    {
        String key = keyOf( clientName, toolName );
        if ( key == null ) {
            return 0L;
        }
        Long expiry = expiryByKey.get( key );
        return expiry == null ? 0L : expiry;
    }

    /**
     * Drops every grant that has already lapsed.
     * <p>
     * Expired grants never authorize anything — {@link McpApprovalEngine} checks the deadline —
     * so this is housekeeping rather than enforcement. It matters for the Settings page, which
     * should not list consent the user no longer has.
     *
     * @param nowMs the current time, in epoch milliseconds
     *
     * @since 3.0
     */
    public void purgeExpired( long nowMs )
    {
        expiryByKey.entrySet().removeIf( entry -> entry.getValue() <= nowMs );
    }

    /**
     * Revokes every grant. Called when the server stops and when the user clears permissions.
     *
     * @since 3.0
     */
    public void revokeAll()
    {
        expiryByKey.clear();
    }

    /**
     * Revokes every grant held by one client.
     *
     * @param clientName the client to revoke
     *
     * @since 3.0
     */
    public void revokeClient( String clientName )
    {
        String prefix = normalize( clientName );
        if ( prefix == null ) {
            return;
        }
        expiryByKey.keySet().removeIf( key -> key.startsWith( prefix + KEY_SEPARATOR ) );
    }

    /**
     * Lists the grants that are still live, for the Settings page.
     *
     * @param nowMs the current time, in epoch milliseconds
     *
     * @return the live grants
     *
     * @since 3.0
     */
    public List< Grant > liveGrants( long nowMs )
    {
        List< Grant > grants = new ArrayList<>();
        for ( Map.Entry< String, Long > entry : expiryByKey.entrySet() ) {
            if ( entry.getValue() <= nowMs ) {
                continue;
            }
            int separator = entry.getKey().indexOf( KEY_SEPARATOR );
            if ( separator < 0 ) {
                continue;
            }
            grants.add( new Grant( entry.getKey().substring( 0, separator ),
                                   entry.getKey().substring( separator + 1 ),
                                   entry.getValue() ) );
        }
        return grants;
    }

    /**
     * Returns how many grants are recorded, expired ones included.
     *
     * @return the recorded grant count
     *
     * @since 3.0
     */
    public int size()
    {
        return expiryByKey.size();
    }

    /**
     * Builds the composite key.
     * <p>
     * See {@link #KEY_SEPARATOR} for why the halves are joined with a NUL rather than a
     * printable character.
     *
     * @param clientName the client's self-reported name
     * @param toolName   the tool
     *
     * @return the key, or {@code null} when either half is missing
     */
    private static String keyOf( String clientName, String toolName )
    {
        String client = normalize( clientName );
        String tool = normalize( toolName );
        if ( client == null || tool == null ) {
            return null;
        }
        return client + KEY_SEPARATOR + tool;
    }

    /**
     * Trims and lower-cases a key component, returning {@code null} when it is unusable.
     *
     * @param value the component
     *
     * @return the normalized component, or {@code null}
     */
    private static String normalize( String value )
    {
        if ( value == null || value.isBlank() ) {
            return null;
        }
        return value.trim().toLowerCase( Locale.ROOT );
    }
}
