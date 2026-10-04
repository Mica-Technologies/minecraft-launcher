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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link McpSessionRegistry} and the one-time handshake on {@link McpSession}.
 *
 * <p>Sessions have to end. Every {@code --mcp} relay restart opens a new one, so a registry that
 * only ever grows fills its cap and then locks every client out — that is the failure these
 * tests pin: idle expiry, explicit removal, and eviction at the cap instead of refusal.</p>
 */
class McpSessionRegistryTest
{
    private static final long NOW = 1_700_000_000_000L;

    private McpSessionRegistry registry;

    @BeforeEach
    void setUp()
    {
        registry = new McpSessionRegistry();
    }

    // region creation and lookup

    @Test
    void aCreatedSessionCanBeFound()
    {
        McpSession session = registry.create( NOW );
        assertNotNull( session );
        assertSame( session, registry.find( session.getId(), NOW ) );
        assertEquals( 1, registry.size() );
    }

    @Test
    void sessionIdsAreUnguessableAndDistinct()
    {
        Set< String > ids = new HashSet<>();
        for ( int i = 0; i < McpSessionRegistry.MAX_SESSIONS; i++ ) {
            String id = registry.create( NOW ).getId();
            assertEquals( 32, id.length(), "128 random bits, hex encoded" );
            ids.add( id );
        }
        assertEquals( McpSessionRegistry.MAX_SESSIONS, ids.size() );
    }

    @Test
    void anUnknownOrNullIdFindsNothing()
    {
        registry.create( NOW );
        assertNull( registry.find( "nope", NOW ) );
        assertNull( registry.find( null, NOW ) );
    }

    // endregion

    // region ending sessions

    @Test
    void aRemovedSessionIsGone()
    {
        McpSession session = registry.create( NOW );
        assertTrue( registry.remove( session.getId() ) );
        assertNull( registry.find( session.getId(), NOW ) );
        assertFalse( registry.remove( session.getId() ), "removing twice reports nothing removed" );
        assertFalse( registry.remove( null ) );
    }

    @Test
    void aSessionIdleForTheTimeoutExpiresOnLookup()
    {
        McpSession session = registry.create( NOW );
        long justBefore = NOW + McpSessionRegistry.IDLE_TIMEOUT_MS - 1;
        assertSame( session, registry.find( session.getId(), justBefore ) );

        assertNull( registry.find( session.getId(), NOW + McpSessionRegistry.IDLE_TIMEOUT_MS ) );
        assertEquals( 0, registry.size(), "an expired session is removed, not just hidden" );
    }

    @Test
    void activityKeepsASessionAlive()
    {
        McpSession session = registry.create( NOW );
        long later = NOW + McpSessionRegistry.IDLE_TIMEOUT_MS - 1;
        session.recordActivity( later, false );

        assertSame( session, registry.find( session.getId(), later + McpSessionRegistry.IDLE_TIMEOUT_MS - 1 ) );
    }

    @Test
    void evictIdleEndsOnlyIdleSessions()
    {
        McpSession idle = registry.create( NOW );
        McpSession active = registry.create( NOW );
        long later = NOW + McpSessionRegistry.IDLE_TIMEOUT_MS;
        active.recordActivity( later - 1, true );

        assertEquals( 1, registry.evictIdle( later ) );
        assertNull( registry.find( idle.getId() ) );
        assertSame( active, registry.find( active.getId() ) );
    }

    // endregion

    // region the cap

    /** The cap holds, but a new client is never refused: the oldest session makes room. */
    @Test
    void atTheCapTheLeastRecentlyActiveSessionIsEvicted()
    {
        List< McpSession > created = new ArrayList<>();
        for ( int i = 0; i < McpSessionRegistry.MAX_SESSIONS; i++ ) {
            created.add( registry.create( NOW + i ) );
        }
        // The first-created session is the most recently active one now.
        created.get( 0 ).recordActivity( NOW + 100, true );

        McpSession newcomer = registry.create( NOW + 200 );

        assertNotNull( newcomer );
        assertEquals( McpSessionRegistry.MAX_SESSIONS, registry.size() );
        assertNull( registry.find( created.get( 1 ).getId() ), "the least recently active goes" );
        assertSame( created.get( 0 ), registry.find( created.get( 0 ).getId() ) );
    }

    @Test
    void idleSessionsAreSweptBeforeAnyActiveOneIsEvicted()
    {
        for ( int i = 0; i < McpSessionRegistry.MAX_SESSIONS; i++ ) {
            registry.create( NOW );
        }
        registry.create( NOW + McpSessionRegistry.IDLE_TIMEOUT_MS );
        assertEquals( 1, registry.size(), "every idle session was swept to make room" );
    }

    @Test
    void repeatedRestartsNeverExceedTheCap()
    {
        for ( int i = 0; i < 100; i++ ) {
            assertNotNull( registry.create( NOW + i ) );
        }
        assertEquals( McpSessionRegistry.MAX_SESSIONS, registry.size() );
    }

    // endregion

    // region the handshake

    /** The client name keys session grants, so only the first handshake may set it. */
    @Test
    void onlyTheFirstHandshakeSetsTheClientIdentity()
    {
        McpSession session = registry.create( NOW );
        assertTrue( session.initialize( "Claude Code", "1.0" ) );
        assertFalse( session.initialize( "Impostor", "9.9" ) );

        assertEquals( "Claude Code", session.getClientName() );
        assertEquals( "1.0", session.getClientVersion() );
        assertEquals( "Claude Code", session.toCallContext().clientName() );
    }

    // endregion
}
