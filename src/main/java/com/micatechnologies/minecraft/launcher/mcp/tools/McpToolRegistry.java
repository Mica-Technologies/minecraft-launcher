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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The set of tools this server exposes, and the lookup behind {@code tools/call}.
 * <p>
 * Registration order is preserved so {@code tools/list} is stable between calls — a client
 * that diffs the list should not see churn just because a hash order changed.
 * <p>
 * Registration is strict on purpose: a duplicate or malformed tool name is a programming error
 * caught at startup rather than a tool that silently shadows another one at runtime.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class McpToolRegistry
{
    /**
     * The permitted shape of a tool name. Deliberately narrow: tool names appear in consent
     * dialogs and audit-log lines, so control characters, whitespace, and shell metacharacters
     * have no business in them.
     */
    public static final Pattern VALID_TOOL_NAME = Pattern.compile( "^[a-z0-9](?:[a-z0-9_-]{0,62}[a-z0-9])?$" );

    /** Registered tools, keyed by name, in registration order. */
    private final Map< String, McpTool > tools = new LinkedHashMap<>();

    /**
     * Registers a tool.
     *
     * @param tool the tool to register
     *
     * @throws IllegalArgumentException if the tool, its name, or its risk class is missing, or
     *                                  the name does not match {@link #VALID_TOOL_NAME}
     * @throws IllegalStateException    if a tool with the same name is already registered
     * @since 3.0
     */
    public void register( McpTool tool )
    {
        if ( tool == null ) {
            throw new IllegalArgumentException( "Cannot register a null tool" );
        }
        String name = tool.name();
        if ( name == null || !VALID_TOOL_NAME.matcher( name ).matches() ) {
            throw new IllegalArgumentException( "Invalid MCP tool name: " + name );
        }
        if ( tool.riskClass() == null ) {
            throw new IllegalArgumentException( "Tool " + name + " must declare a risk class" );
        }
        if ( tools.containsKey( name ) ) {
            throw new IllegalStateException( "Duplicate MCP tool name: " + name );
        }
        tools.put( name, tool );
    }

    /**
     * Looks up a tool by name.
     *
     * @param name the tool name; {@code null} yields {@code null}
     *
     * @return the tool, or {@code null} when no tool of that name is registered
     *
     * @since 3.0
     */
    public McpTool find( String name )
    {
        return name == null ? null : tools.get( name );
    }

    /**
     * Returns every registered tool, in registration order.
     *
     * @return an unmodifiable view of the registered tools
     *
     * @since 3.0
     */
    public List< McpTool > all()
    {
        return Collections.unmodifiableList( new ArrayList<>( tools.values() ) );
    }

    /**
     * Returns how many tools are registered.
     *
     * @return the tool count
     *
     * @since 3.0
     */
    public int size()
    {
        return tools.size();
    }

    /**
     * Builds the {@code tools/list} result.
     *
     * @return the result object, carrying a {@code tools} array in registration order
     *
     * @since 3.0
     */
    public JsonObject listResult()
    {
        JsonArray array = new JsonArray();
        for ( McpTool tool : tools.values() ) {
            array.add( tool.describe() );
        }
        JsonObject result = new JsonObject();
        result.add( "tools", array );
        return result;
    }
}
