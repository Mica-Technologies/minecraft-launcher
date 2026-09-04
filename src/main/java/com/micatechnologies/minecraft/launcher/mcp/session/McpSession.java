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

import com.micatechnologies.minecraft.launcher.mcp.tools.McpCallContext;

import java.util.concurrent.atomic.AtomicLong;

/**
 * One connected MCP client, from the {@code initialize} handshake until the transport drops.
 * <p>
 * Sessions are what the Settings page lists, so the counters here exist to be shown to the
 * user: which client is connected, how long it has been, and how much it has done. A session
 * deliberately holds no credential material — the bearer token that authenticated the
 * connection is verified by the transport and never stored here, because this object is passed
 * into tool code.
 * <p>
 * Instances are shared between the transport thread and the tool executor, so the mutable
 * counters are atomic and the client identity is written once during the handshake.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class McpSession
{
    /** Launcher-assigned identifier, unique within this launcher run. */
    private final String id;

    /** When the transport accepted this session, in epoch milliseconds. */
    private final long connectedAtMs;

    /** How many tool calls this session has made. */
    private final AtomicLong callCount = new AtomicLong();

    /** When this session was last active, in epoch milliseconds. */
    private final AtomicLong lastActivityMs;

    /**
     * The client's self-reported name from {@code initialize}. Untrusted: it is display text
     * and a grant key, never an authorization input.
     */
    private volatile String clientName = "";

    /** The client's self-reported version from {@code initialize}. Untrusted, display only. */
    private volatile String clientVersion = "";

    /** Whether the {@code initialize} handshake has completed. */
    private volatile boolean initialized = false;

    /**
     * Constructs a session.
     *
     * @param id            the launcher-assigned session identifier
     * @param connectedAtMs when the transport accepted the session, in epoch milliseconds
     *
     * @since 3.0
     */
    public McpSession( String id, long connectedAtMs )
    {
        this.id = id == null ? "" : id;
        this.connectedAtMs = connectedAtMs;
        this.lastActivityMs = new AtomicLong( connectedAtMs );
    }

    /**
     * Returns the launcher-assigned session identifier.
     *
     * @return the session id
     *
     * @since 3.0
     */
    public String getId()
    {
        return id;
    }

    /**
     * Returns when the transport accepted this session.
     *
     * @return the connection time, in epoch milliseconds
     *
     * @since 3.0
     */
    public long getConnectedAtMs()
    {
        return connectedAtMs;
    }

    /**
     * Returns how many tool calls this session has made.
     *
     * @return the tool-call count
     *
     * @since 3.0
     */
    public long getCallCount()
    {
        return callCount.get();
    }

    /**
     * Returns when this session was last active.
     *
     * @return the last-activity time, in epoch milliseconds
     *
     * @since 3.0
     */
    public long getLastActivityMs()
    {
        return lastActivityMs.get();
    }

    /**
     * Returns the client's self-reported name.
     * <p>
     * This is untrusted text supplied by the client. It is safe to show the user and to use as
     * half of a session-grant key, but it must never be treated as an authorization input — any
     * client can claim any name.
     *
     * @return the client name, or {@code ""} before the handshake
     *
     * @since 3.0
     */
    public String getClientName()
    {
        return clientName;
    }

    /**
     * Returns the client's self-reported version. Untrusted; display only.
     *
     * @return the client version, or {@code ""} before the handshake
     *
     * @since 3.0
     */
    public String getClientVersion()
    {
        return clientVersion;
    }

    /**
     * Reports whether the {@code initialize} handshake has completed.
     *
     * @return {@code true} once the client has initialized
     *
     * @since 3.0
     */
    public boolean isInitialized()
    {
        return initialized;
    }

    /**
     * Records the client identity from the {@code initialize} handshake and marks the session
     * initialized.
     *
     * @param name    the client's self-reported name
     * @param version the client's self-reported version
     *
     * @since 3.0
     */
    public void initialize( String name, String version )
    {
        this.clientName = name == null ? "" : name;
        this.clientVersion = version == null ? "" : version;
        this.initialized = true;
    }

    /**
     * Records that this session was active, optionally counting a tool call.
     *
     * @param nowMs        the current time, in epoch milliseconds
     * @param wasToolCall  whether the activity was a tool invocation
     *
     * @since 3.0
     */
    public void recordActivity( long nowMs, boolean wasToolCall )
    {
        lastActivityMs.set( nowMs );
        if ( wasToolCall ) {
            callCount.incrementAndGet();
        }
    }

    /**
     * Builds the call context handed to tools for this session.
     *
     * @return the call context
     *
     * @since 3.0
     */
    public McpCallContext toCallContext()
    {
        return new McpCallContext( clientName, id );
    }
}
