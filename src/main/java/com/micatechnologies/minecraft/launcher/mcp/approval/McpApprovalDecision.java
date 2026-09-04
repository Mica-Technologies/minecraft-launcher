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
 * What the launcher should do with one tool call, as resolved by {@link McpApprovalEngine}.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public enum McpApprovalDecision
{
    /** Run the tool immediately. */
    ALLOW,

    /** Show the consent dialog and run only if the user agrees. */
    PROMPT,

    /** Refuse and return {@code McpErrors.REQUEST_DENIED}. Never falls back to running. */
    DENY
}
