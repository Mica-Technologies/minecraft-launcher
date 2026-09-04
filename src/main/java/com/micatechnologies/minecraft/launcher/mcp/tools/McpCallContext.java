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

package com.micatechnologies.minecraft.launcher.mcp.tools;

/**
 * Who is making a tool call.
 * <p>
 * Carried into every {@link McpTool#invoke} so a tool can name the caller in a consent prompt
 * or an audit-log entry. It deliberately holds no credential material — not the MCP bearer
 * token, not the account UUID — because it is passed to every tool implementation, including
 * ones added later by someone who has not read the plan's section 5.6.
 *
 * @param clientName the client's self-reported name from the {@code initialize} handshake,
 *                   e.g. {@code "Claude Code"}; never {@code null}, but never trusted for
 *                   anything but display
 * @param sessionId  the launcher-assigned session identifier
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public record McpCallContext( String clientName, String sessionId )
{
    /**
     * Constructs a call context, coalescing a missing client name to a neutral placeholder so
     * prompts and logs never render "null".
     *
     * @param clientName the client's self-reported name
     * @param sessionId  the launcher-assigned session identifier
     *
     * @since 3.0
     */
    public McpCallContext
    {
        clientName = clientName == null || clientName.isBlank() ? "Unknown client" : clientName;
        sessionId = sessionId == null ? "" : sessionId;
    }
}
