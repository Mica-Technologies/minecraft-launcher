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
 * A session ends in one of three ways: the client ends it ({@code DELETE /mcp}), it sits idle
 * for {@link #IDLE_TIMEOUT_MS}, or the cap needs room. Without these, every client restart —
 * each {@code --mcp} relay process starts a fresh session — would hold a slot until the
 * launcher exited, and the cap would eventually lock every client out.
 * <p>
 * Sessions are capped at {@link #MAX_SESSIONS}. A local process that can authenticate could
 * otherwise open sessions without limit, and each one is a live handle on the launcher. At the
 * cap the least-recently-active session is evicted rather than the newcomer refused: a client
 * whose session was evicted gets {@code 404} on its next request, and the MCP transport
 * specification requires it to answer that by initializing again, so eviction costs it a
 * handshake while refusal would cost a fresh client the feature.
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

    /**
     * How long a session may go without a request before it is ended (30 minutes).
     * <p>
     * Long enough that a person reading a reply between prompts keeps their session; a client
     * that comes back later simply initializes again.
     */
    public static final long IDLE_TIMEOUT_MS = 30L * 60L * 1000L;

    /** Live sessions, keyed by session id. */
    private final Map< String, McpSession > sessions = new ConcurrentHashMap<>();

    /** Source of unguessable session ids. */
    private final SecureRandom random = new SecureRandom();

    /**
     * Creates and registers a session, first ending idle ones and, at the cap, the
     * least-recently-active one.
     *
     * @param nowMs the current time, in epoch milliseconds
     *
     * @return the new session; never {@code null}
     *
     * @since 3.0
     */
    public synchronized McpSession create( long nowMs )
    {
        evictIdle( nowMs );
        while ( sessions.size() >= MAX_SESSIONS ) {
            McpSession oldest = null;
            for ( McpSession candidate : sessions.values() ) {
                if ( oldest == null || candidate.getLastActivityMs() < oldest.getLastActivityMs() ) {
                    oldest = candidate;
                }
            }
            if ( oldest == null ) {
                break;
            }
            sessions.remove( oldest.getId() );
        }

        byte[] bytes = new byte[ 16 ];
        random.nextBytes( bytes );
        String id = HexFormat.of().formatHex( bytes );
        McpSession session = new McpSession( id, nowMs );
        sessions.put( id, session );
        return session;
    }

    /**
     * Looks up a session by id, without regard to idleness.
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
     * Looks up a session by id, ending it instead when it has been idle too long.
     *
     * @param id    the session id; {@code null} yields {@code null}
     * @param nowMs the current time, in epoch milliseconds
     *
     * @return the session, or {@code null} when no such session is live or it just expired
     *
     * @since 2026.10
     */
    public McpSession find( String id, long nowMs )
    {
        McpSession session = find( id );
        if ( session != null && isIdle( session, nowMs ) ) {
            sessions.remove( id, session );
            return null;
        }
        return session;
    }

    /**
     * Ends a session.
     *
     * @param id the session id
     *
     * @return {@code true} when a live session was ended
     *
     * @since 3.0
     */
    public boolean remove( String id )
    {
        return id != null && sessions.remove( id ) != null;
    }

    /**
     * Ends every session that has been idle for {@link #IDLE_TIMEOUT_MS} or longer.
     *
     * @param nowMs the current time, in epoch milliseconds
     *
     * @return how many sessions were ended
     *
     * @since 2026.10
     */
    public int evictIdle( long nowMs )
    {
        int evicted = 0;
        for ( McpSession session : sessions.values() ) {
            if ( isIdle( session, nowMs ) && sessions.remove( session.getId(), session ) ) {
                evicted++;
            }
        }
        return evicted;
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

    /**
     * Reports whether a session has gone {@link #IDLE_TIMEOUT_MS} without activity.
     *
     * @param session the session
     * @param nowMs   the current time, in epoch milliseconds
     *
     * @return {@code true} when it should be ended
     */
    private static boolean isIdle( McpSession session, long nowMs )
    {
        return nowMs - session.getLastActivityMs() >= IDLE_TIMEOUT_MS;
    }
}
