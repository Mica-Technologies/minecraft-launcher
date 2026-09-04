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

package com.micatechnologies.minecraft.launcher.mcp.protocol;

/**
 * The JSON-RPC method names the launcher's MCP server understands.
 * <p>
 * The launcher is an MCP <em>server</em> only — it never acts as a client — so this is the
 * complete set of inbound methods. Anything not listed here is answered with
 * {@link McpErrors#METHOD_NOT_FOUND}.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class McpMethods
{
    /** Opening handshake; carries the client's protocol version and {@code clientInfo}. */
    public static final String INITIALIZE = "initialize";

    /** Client notification that the handshake is complete and normal traffic may begin. */
    public static final String NOTIFICATIONS_INITIALIZED = "notifications/initialized";

    /** Liveness check. Takes no parameters and returns an empty result. */
    public static final String PING = "ping";

    /** Enumerates the tools this server exposes, with their input schemas. */
    public static final String TOOLS_LIST = "tools/list";

    /** Invokes one tool by name with an arguments object. */
    public static final String TOOLS_CALL = "tools/call";

    /** Enumerates concrete, directly readable resources. */
    public static final String RESOURCES_LIST = "resources/list";

    /** Reads one resource by URI. */
    public static final String RESOURCES_READ = "resources/read";

    /** Enumerates URI templates for resources that are parameterized (e.g. per modpack). */
    public static final String RESOURCES_TEMPLATES_LIST = "resources/templates/list";

    /** Enumerates canned prompts. Optional; see the plan's section 7.6. */
    public static final String PROMPTS_LIST = "prompts/list";

    /** Retrieves one canned prompt by name. Optional; see the plan's section 7.6. */
    public static final String PROMPTS_GET = "prompts/get";

    /**
     * Not instantiable.
     */
    private McpMethods()
    {
        throw new AssertionError( "McpMethods is a constants holder and must not be instantiated" );
    }
}
