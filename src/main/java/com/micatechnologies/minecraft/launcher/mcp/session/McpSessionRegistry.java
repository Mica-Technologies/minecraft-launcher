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

package com.micatechnologies.minecraft.launcher.mcp.session;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live MCP sessions, as shown on the Settings page.
 * <p>
 * Sessions are capped at {@link #MAX_SESSIONS}. A local process that can authenticate could
 * otherwise open sessions without limit, and each one is a live handle on the launcher; the cap
 * turns that from unbounded growth into a visible refusal.
 * <p>
 * Session ids come from {@link SecureRandom} rather than a counter, so a client cannot guess
 * another session's id and adopt its handshake state.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class McpSessionRegistry
{
    /** Most sessions allowed at once. */
    public static final int MAX_SESSIONS = 8;

    /** Live sessions, keyed by session id. */
    private final Map< String, McpSession > sessions = new ConcurrentHashMap<>();

    /** Source of unguessable session ids. */
    private final SecureRandom random = new SecureRandom();

    /**
     * Creates and registers a session.
     *
     * @param nowMs the current time, in epoch milliseconds
     *
     * @return the new session, or {@code null} when {@link #MAX_SESSIONS} is already reached
     *
     * @since 3.0
     */
    public McpSession create( long nowMs )
    {
        if ( sessions.size() >= MAX_SESSIONS ) {
            return null;
        }
        byte[] bytes = new byte[ 16 ];
        random.nextBytes( bytes );
        String id = HexFormat.of().formatHex( bytes );
        McpSession session = new McpSession( id, nowMs );
        sessions.put( id, session );
        return session;
    }

    /**
     * Looks up a session by id.
     *
     * @param id the session id; {@code null} yields {@code null}
     *
     * @return the session, or {@code null} when no such session is live
     *
     * @since 3.0
     */
    public McpSession find( String id )
    {
        return id == null ? null : sessions.get( id );
    }

    /**
     * Ends a session.
     *
     * @param id the session id
     *
     * @since 3.0
     */
    public void remove( String id )
    {
        if ( id != null ) {
            sessions.remove( id );
        }
    }

    /**
     * Ends every session. Called when the server stops.
     *
     * @since 3.0
     */
    public void clear()
    {
        sessions.clear();
    }

    /**
     * Returns every live session, for the Settings page.
     *
     * @return an unmodifiable snapshot of the live sessions
     *
     * @since 3.0
     */
    public List< McpSession > all()
    {
        return Collections.unmodifiableList( new ArrayList<>( sessions.values() ) );
    }

    /**
     * Returns how many sessions are live.
     *
     * @return the session count
     *
     * @since 3.0
     */
    public int size()
    {
        return sessions.size();
    }
}
