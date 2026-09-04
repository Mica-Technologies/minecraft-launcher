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

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.mcp.approval.McpRiskClass;

/**
 * One capability the launcher exposes to MCP clients.
 * <p>
 * Implementations must be safe to call off the FX thread and must not assume a GUI exists.
 * Tool invocation is serialized through a single-threaded executor, so an implementation does
 * not need to guard against concurrent calls to itself — but it must not block indefinitely,
 * because doing so stalls every other tool call.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public interface McpTool
{
    /**
     * Returns the tool's wire name, as used in {@code tools/call}.
     * <p>
     * Must match {@link McpToolRegistry#VALID_TOOL_NAME} — lower-case letters, digits,
     * underscores and hyphens.
     *
     * @return the tool name
     *
     * @since 3.0
     */
    String name();

    /**
     * Returns a short human-readable title, shown in the Settings per-tool permission list.
     *
     * @return the display title
     *
     * @since 3.0
     */
    String title();

    /**
     * Returns the description shown to the model. This is what the model reasons over when
     * deciding whether to call the tool, so it should say what the tool does and what it
     * changes.
     *
     * @return the tool description
     *
     * @since 3.0
     */
    String description();

    /**
     * Returns the JSON Schema describing this tool's arguments object.
     *
     * @return the input schema
     *
     * @since 3.0
     */
    JsonObject inputSchema();

    /**
     * Returns how much damage this tool can do, which decides what consent is required.
     *
     * @return the risk class; never {@code null}
     *
     * @since 3.0
     */
    McpRiskClass riskClass();

    /**
     * Runs the tool.
     * <p>
     * A tool that cannot do what was asked should return {@link McpToolResult#error} rather
     * than throwing — that reports the reason to the model, which can then adjust, instead of
     * surfacing a protocol-level failure. Throwing is reserved for genuinely unexpected
     * conditions.
     *
     * @param context who is calling
     * @param arguments the arguments object, already validated as an object but not against
     *                  {@link #inputSchema()}
     *
     * @return the result
     *
     * @throws Exception if the tool fails unexpectedly; the dispatcher converts this into an
     *                   internal error without leaking the exception's detail to the client
     * @since 3.0
     */
    McpToolResult invoke( McpCallContext context, JsonObject arguments ) throws Exception;

    /**
     * Builds this tool's entry in a {@code tools/list} response.
     *
     * @return the descriptor object
     *
     * @since 3.0
     */
    default JsonObject describe()
    {
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty( "name", name() );
        descriptor.addProperty( "title", title() );
        descriptor.addProperty( "description", description() );
        descriptor.add( "inputSchema", inputSchema() );
        return descriptor;
    }
}
