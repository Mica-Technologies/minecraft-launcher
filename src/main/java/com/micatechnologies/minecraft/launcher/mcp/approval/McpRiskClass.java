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
 * How much damage a tool can do, which decides what the launcher asks the user before running
 * it.
 * <p>
 * Every tool declares exactly one of these. The class alone determines the default policy; the
 * user can override it per tool in Settings.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public enum McpRiskClass
{
    /** No state change — lists, reads, searches, diagnostics. */
    READ_ONLY( McpApprovalPolicy.ALWAYS_ALLOW ),

    /** Changes launcher or pack state recoverably — install, create, fork, edit settings. */
    MUTATING( McpApprovalPolicy.ASK ),

    /** Deletes or overwrites user data — uninstall, delete files, restore a backup. */
    DESTRUCTIVE( McpApprovalPolicy.ASK ),

    /** Runs third-party code or spawns processes — launch a game, stop a game. */
    EXECUTE( McpApprovalPolicy.ASK );

    /** The policy applied when the user has set none for a tool of this class. */
    private final McpApprovalPolicy defaultPolicy;

    /**
     * Constructs a risk class.
     *
     * @param defaultPolicy the policy applied when the user has set none
     */
    McpRiskClass( McpApprovalPolicy defaultPolicy )
    {
        this.defaultPolicy = defaultPolicy;
    }

    /**
     * Returns the policy applied to a tool of this class when the user has set none.
     *
     * @return the default policy; {@link McpApprovalPolicy#ASK} for everything except
     *         {@link #READ_ONLY}
     *
     * @since 3.0
     */
    public McpApprovalPolicy getDefaultPolicy()
    {
        return defaultPolicy;
    }

    /**
     * Reports whether a tool of this class can change anything at all.
     * <p>
     * Used to decide whether a call needs to serialize behind the single-threaded tool
     * executor's write path and whether it is worth an audit-log entry.
     *
     * @return {@code true} for everything except {@link #READ_ONLY}
     *
     * @since 3.0
     */
    public boolean mutatesState()
    {
        return this != READ_ONLY;
    }
}
