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
 * JSON-RPC 2.0 and MCP error codes.
 * <p>
 * The negative range {@code -32768..-32000} is reserved by the JSON-RPC specification; the
 * codes below {@code -32000} in the MCP section are application-defined and chosen to match
 * what MCP clients already expect.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class McpErrors
{
    /** The request was not valid JSON. The response carries a {@code null} id. */
    public static final int PARSE_ERROR = -32700;

    /** The request was valid JSON but not a valid JSON-RPC request object. */
    public static final int INVALID_REQUEST = -32600;

    /** The method name is not one this server implements. */
    public static final int METHOD_NOT_FOUND = -32601;

    /** The method exists but the parameters are missing, mistyped, or out of range. */
    public static final int INVALID_PARAMS = -32602;

    /** The server failed while handling an otherwise well-formed request. */
    public static final int INTERNAL_ERROR = -32603;

    /** The requested resource URI does not correspond to anything this server exposes. */
    public static final int RESOURCE_NOT_FOUND = -32002;

    /**
     * The caller is not permitted to invoke this tool — the user denied consent, the tool is
     * disabled by policy, or no GUI was available to ask and the request failed closed.
     */
    public static final int REQUEST_DENIED = -32003;

    /**
     * Not instantiable.
     */
    private McpErrors()
    {
        throw new AssertionError( "McpErrors is a constants holder and must not be instantiated" );
    }
}
