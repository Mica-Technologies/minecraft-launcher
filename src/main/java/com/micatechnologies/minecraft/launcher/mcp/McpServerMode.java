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

/**
 * How the MCP server is deployed, corresponding to the plan's section 3.
 * <p>
 * Every mode executes tools inside the one launcher process that owns the modpack folders. The
 * launcher enforces single-instance because concurrent processes mutating the same folders is
 * unsafe, so a standalone MCP process touching packs directly is not an option — the stdio
 * shim of mode B proxies into the running launcher rather than doing its own work.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public enum McpServerMode
{
    /** Not running. No listener binds and no endpoint file exists. */
    OFF,

    /** Runs on a daemon thread inside the GUI launcher, for as long as the launcher is open. */
    WITH_LAUNCHER,

    /** Runs with the launcher resident in the tray, surviving the main window being closed. */
    TRAY_BACKGROUND
}
