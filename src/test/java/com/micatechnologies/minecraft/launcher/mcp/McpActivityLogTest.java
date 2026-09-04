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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link McpActivityLog} — the record of what MCP clients actually did.
 *
 * <p>Consent answers "may this happen?"; this answers "what happened?". Without it a user who
 * clicked through a prompt hours ago has no way to find out what they agreed to, which makes
 * every approval unreviewable after the moment it was given.</p>
 *
 * <p>The property that gets the most attention here is <b>redaction on the way in</b>. This
 * log is rendered in Settings, and a Settings page is one screenshot away from a support
 * thread — so a token that reached an argument summary must never be held by this class at
 * all, rather than being filtered when displayed. Testing that means asserting on what is
 * stored, not on what is printed.</p>
 */
class McpActivityLogTest
{
    private static final long NOW = 1_700_000_000_000L;

    private McpActivityLog log;

    @BeforeEach
    void setUp()
    {
        log = new McpActivityLog();
    }

    // region recording

    @Test
    void aCallIsRecorded()
    {
        log.record( NOW, "Claude Code", "list_modpacks", McpActivityLog.Decision.ALLOWED, "" );

        List< McpActivityLog.Entry > entries = log.recent();
        assertEquals( 1, entries.size() );
        assertEquals( "Claude Code", entries.get( 0 ).clientName() );
        assertEquals( "list_modpacks", entries.get( 0 ).toolName() );
        assertEquals( McpActivityLog.Decision.ALLOWED, entries.get( 0 ).decision() );
        assertEquals( NOW, entries.get( 0 ).timestampMs() );
    }

    /** Newest first, because that is what a user scanning for "what just happened" wants. */
    @Test
    void theNewestEntryComesFirst()
    {
        log.record( NOW, "A", "first_tool", McpActivityLog.Decision.ALLOWED, "" );
        log.record( NOW + 1, "B", "second_tool", McpActivityLog.Decision.DENIED, "" );

        List< McpActivityLog.Entry > entries = log.recent();
        assertEquals( "second_tool", entries.get( 0 ).toolName() );
        assertEquals( "first_tool", entries.get( 1 ).toolName() );
    }

    @Test
    void everyDecisionKindIsRecorded()
    {
        for ( McpActivityLog.Decision decision : McpActivityLog.Decision.values() ) {
            log.record( NOW, "A", "tool", decision, "" );
        }
        assertEquals( McpActivityLog.Decision.values().length, log.size() );
    }

    @Test
    void anEntryWithNoToolOrDecisionIsIgnored()
    {
        log.record( NOW, "A", null, McpActivityLog.Decision.ALLOWED, "" );
        log.record( NOW, "A", "tool", null, "" );
        assertEquals( 0, log.size() );
    }

    @Test
    void nullTextFieldsBecomeEmptyRatherThanNull()
    {
        log.record( NOW, null, "tool", McpActivityLog.Decision.ALLOWED, null );
        assertEquals( "", log.recent().get( 0 ).clientName() );
        assertEquals( "", log.recent().get( 0 ).detail() );
    }

    @Test
    void clearingDiscardsEverything()
    {
        log.record( NOW, "A", "tool", McpActivityLog.Decision.ALLOWED, "" );
        log.clear();
        assertEquals( 0, log.size() );
        assertTrue( log.recent().isEmpty() );
    }

    // endregion

    // region bounding

    /**
     * A long-lived launcher session with a chatty client would otherwise grow this without
     * limit. The cap keeps it a review surface rather than a memory leak.
     */
    @Test
    void theLogIsBoundedAndDropsTheOldestEntries()
    {
        for ( int i = 0; i < McpActivityLog.MAX_ENTRIES + 50; i++ ) {
            log.record( NOW + i, "A", "tool_" + i, McpActivityLog.Decision.ALLOWED, "" );
        }
        assertEquals( McpActivityLog.MAX_ENTRIES, log.size() );

        List< McpActivityLog.Entry > entries = log.recent();
        assertEquals( "tool_" + ( McpActivityLog.MAX_ENTRIES + 49 ), entries.get( 0 ).toolName() );
        assertEquals( "tool_50", entries.get( entries.size() - 1 ).toolName(),
                      "the oldest retained entry should be the 51st recorded" );
    }

    // endregion

    // region redaction on the way in

    /**
     * The property this class exists to guarantee. A credential that reached a detail string
     * must not be <em>stored</em> — filtering it at render time would still leave it in memory
     * and in any heap dump.
     */
    @Test
    void anAccessTokenIsStrippedBeforeItIsStored()
    {
        log.record( NOW, "Claude Code", "launch_modpack", McpActivityLog.Decision.ALLOWED,
                    "ran with --accessToken eyJhbGciOiJIUzI1NiJ9.leaked.value" );

        String stored = log.recent().get( 0 ).detail();
        assertFalse( stored.contains( "eyJhbGciOiJIUzI1NiJ9.leaked.value" ), stored );
        assertTrue( stored.contains( "[REDACTED]" ), stored );
    }

    @Test
    void anAccountUuidIsStrippedBeforeItIsStored()
    {
        log.record( NOW, "Claude Code", "get_launcher_status", McpActivityLog.Decision.ALLOWED,
                    "player 069a79f4-44e9-4726-a5be-fca90e38aaf5" );
        assertFalse( log.recent().get( 0 ).detail()
                             .contains( "069a79f4-44e9-4726-a5be-fca90e38aaf5" ) );
    }

    /**
     * The client name is attacker-supplied — it comes from the handshake and any client can
     * claim any name — so it faces the same filter as the detail rather than being trusted
     * because it looks like an identity.
     */
    @Test
    void aCredentialShapedClientNameIsAlsoStripped()
    {
        log.record( NOW, "069a79f4-44e9-4726-a5be-fca90e38aaf5", "list_modpacks",
                    McpActivityLog.Decision.ALLOWED, "" );
        assertFalse( log.recent().get( 0 ).clientName()
                             .contains( "069a79f4-44e9-4726-a5be-fca90e38aaf5" ) );
    }

    /**
     * SHA-1 survives, matching the rest of the redaction policy: asset and library checksums
     * are common in these messages and are not credentials.
     */
    @Test
    void aSha1IsNotMistakenForACredential()
    {
        String sha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
        log.record( NOW, "A", "tool", McpActivityLog.Decision.ALLOWED, "verified " + sha1 );
        assertTrue( log.recent().get( 0 ).detail().contains( sha1 ) );
    }

    // endregion
}
