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

import com.micatechnologies.minecraft.launcher.utilities.SensitiveDataRedactor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * What MCP clients have actually done this run — the plan's section 6.4.
 * <p>
 * Consent answers "may this happen?"; this answers "what happened?". Without it, a user who
 * clicked through a prompt three hours ago has no way to find out what they agreed to, which
 * makes every approval decision unreviewable after the moment it is made.
 * <p>
 * <b>Entries are redacted on the way in, not on the way out.</b> Every field passes through
 * {@link SensitiveDataRedactor#redactStrict} before it is stored, so a token that reached an
 * argument summary is never held in memory by this class at all — the same invariant
 * {@code McpToolResult} enforces at the wire, applied here because a Settings page is one
 * screenshot away from a support thread.
 * <p>
 * The log is in-memory and bounded. It is a review surface for the session, not an audit trail
 * to be relied on after a restart; pack-mutating calls additionally reach the launcher's own
 * log and {@code ModPackAuditLog}, which do persist.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class McpActivityLog
{
    /** Most entries retained. Older ones are dropped as new ones arrive. */
    public static final int MAX_ENTRIES = 200;

    /** How a call was resolved. */
    public enum Decision
    {
        /** Refused by a content gate, before the user was asked. */
        REFUSED,

        /** Refused by policy or by the user at the consent prompt. */
        DENIED,

        /** Ran, and succeeded. */
        ALLOWED,

        /** Ran, and failed. */
        FAILED
    }

    /**
     * One recorded call.
     *
     * @param timestampMs when it happened, in epoch milliseconds
     * @param clientName  the client's self-reported name
     * @param toolName    the tool called
     * @param decision    how it was resolved
     * @param detail      a short redacted summary, or {@code ""}
     *
     * @since 3.0
     */
    public record Entry( long timestampMs, String clientName, String toolName, Decision decision,
                         String detail )
    {
    }

    /** Newest last. Bounded to {@link #MAX_ENTRIES}. */
    private final Deque< Entry > entries = new ArrayDeque<>();

    /**
     * Records one call, redacting every text field first.
     *
     * @param timestampMs when it happened, in epoch milliseconds
     * @param clientName  the client's self-reported name
     * @param toolName    the tool called
     * @param decision    how it was resolved
     * @param detail      a short summary; redacted before storage
     *
     * @since 3.0
     */
    public synchronized void record( long timestampMs, String clientName, String toolName,
                                     Decision decision, String detail )
    {
        if ( toolName == null || decision == null ) {
            return;
        }
        entries.addLast( new Entry( timestampMs,
                                    redact( clientName ),
                                    redact( toolName ),
                                    decision,
                                    redact( detail ) ) );
        while ( entries.size() > MAX_ENTRIES ) {
            entries.removeFirst();
        }
    }

    /**
     * Returns the recorded calls, newest first.
     *
     * @return a snapshot of the log
     *
     * @since 3.0
     */
    public synchronized List< Entry > recent()
    {
        List< Entry > snapshot = new ArrayList<>( entries );
        java.util.Collections.reverse( snapshot );
        return snapshot;
    }

    /**
     * Returns how many entries are held.
     *
     * @return the entry count
     *
     * @since 3.0
     */
    public synchronized int size()
    {
        return entries.size();
    }

    /**
     * Discards every entry.
     *
     * @since 3.0
     */
    public synchronized void clear()
    {
        entries.clear();
    }

    /**
     * Strips credentials from a field, coalescing {@code null} to the empty string.
     *
     * @param value the field
     *
     * @return the redacted field
     */
    private static String redact( String value )
    {
        return value == null ? "" : SensitiveDataRedactor.redactStrict( value );
    }
}
