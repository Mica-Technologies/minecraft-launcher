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

/**
 * The user-settable approval policy for one tool, as chosen in the MCP Settings page.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public enum McpApprovalPolicy
{
    /** Run without asking. */
    ALWAYS_ALLOW,

    /**
     * Prompt for consent on every call. Not satisfied by a session grant: the consent dialog
     * does not offer "Allow for this session" for a tool with this policy.
     */
    ASK,

    /** Refuse unconditionally. Not overridable by a session grant. */
    DISABLED
}
