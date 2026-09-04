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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;

/**
 * Framing and validation for JSON-RPC 2.0 messages.
 * <p>
 * This class is deliberately pure: it takes text in and hands back either a validated
 * {@link JsonRpcMessage} or a ready-to-send error object, and it touches no launcher state.
 * That is what lets the protocol layer be exercised exhaustively by unit tests without a
 * socket, which matters because it is the outermost layer — everything it lets through
 * reaches code that can launch games and delete modpacks.
 * <p>
 * <b>Batching is rejected.</b> The MCP specification removed JSON-RPC batch support, so a
 * top-level array is answered with {@link McpErrors#INVALID_REQUEST} rather than being
 * partially processed.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class JsonRpcCodec
{
    /** The only JSON-RPC version this server speaks. */
    public static final String JSONRPC_VERSION = "2.0";

    /**
     * The outcome of parsing one inbound message: either a validated message or an error
     * object to send straight back to the caller. Exactly one of the two is non-{@code null}.
     *
     * @param message the validated message, or {@code null} when parsing failed
     * @param error   the error response to send, or {@code null} when parsing succeeded
     *
     * @since 3.0
     */
    public record Parse( JsonRpcMessage message, JsonObject error )
    {
        /**
         * Reports whether the message parsed successfully.
         *
         * @return {@code true} when {@link #message()} is available
         *
         * @since 3.0
         */
        public boolean ok()
        {
            return message != null;
        }
    }

    /**
     * Parses and validates one inbound JSON-RPC message.
     * <p>
     * The request id is echoed in the error response whenever it could be read and is itself
     * valid; it is {@code null} otherwise, as the JSON-RPC specification requires for a
     * message whose id is unknowable.
     *
     * @param raw the raw message text; {@code null} and blank are both treated as a parse error
     *
     * @return the validated message, or an error response to send back
     *
     * @since 3.0
     */
    public static Parse parse( String raw )
    {
        if ( raw == null || raw.isBlank() ) {
            return rejected( null, McpErrors.PARSE_ERROR, "Empty request" );
        }

        JsonElement root;
        try {
            root = JsonParser.parseString( raw );
        }
        catch ( Exception e ) {
            return rejected( null, McpErrors.PARSE_ERROR, "Malformed JSON" );
        }

        if ( root == null || root.isJsonNull() ) {
            return rejected( null, McpErrors.PARSE_ERROR, "Malformed JSON" );
        }
        if ( root.isJsonArray() ) {
            return rejected( null, McpErrors.INVALID_REQUEST, "Batch requests are not supported" );
        }
        if ( !root.isJsonObject() ) {
            return rejected( null, McpErrors.INVALID_REQUEST, "Request must be a JSON object" );
        }

        JsonObject obj = root.getAsJsonObject();

        // Read the id first so it can be echoed on any subsequent validation failure. An id
        // that is present but not a string or number is itself a protocol violation, and must
        // not be echoed back -- doing so would reflect arbitrary caller-supplied structure.
        JsonElement id = null;
        if ( obj.has( "id" ) ) {
            JsonElement rawId = obj.get( "id" );
            if ( rawId.isJsonNull() ) {
                return rejected( null, McpErrors.INVALID_REQUEST, "Request id must not be null" );
            }
            if ( !rawId.isJsonPrimitive() ||
                    !( rawId.getAsJsonPrimitive().isString() || rawId.getAsJsonPrimitive().isNumber() ) ) {
                return rejected( null, McpErrors.INVALID_REQUEST, "Request id must be a string or number" );
            }
            id = rawId;
        }

        if ( !obj.has( "jsonrpc" ) || !obj.get( "jsonrpc" ).isJsonPrimitive() ||
                !JSONRPC_VERSION.equals( obj.get( "jsonrpc" ).getAsString() ) ) {
            return rejected( id, McpErrors.INVALID_REQUEST, "Missing or unsupported \"jsonrpc\" version" );
        }

        if ( !obj.has( "method" ) || !obj.get( "method" ).isJsonPrimitive() ||
                !obj.get( "method" ).getAsJsonPrimitive().isString() ) {
            return rejected( id, McpErrors.INVALID_REQUEST, "Missing or non-string \"method\"" );
        }
        String method = obj.get( "method" ).getAsString();
        if ( method.isBlank() ) {
            return rejected( id, McpErrors.INVALID_REQUEST, "Method name must not be blank" );
        }

        JsonElement params = null;
        if ( obj.has( "params" ) && !obj.get( "params" ).isJsonNull() ) {
            params = obj.get( "params" );
            if ( !params.isJsonObject() && !params.isJsonArray() ) {
                return rejected( id, McpErrors.INVALID_REQUEST, "\"params\" must be an object or array" );
            }
        }

        return new Parse( new JsonRpcMessage( id, method, params ), null );
    }

    /**
     * Builds a successful JSON-RPC response.
     *
     * @param id     the id of the request being answered
     * @param result the result value; {@code null} is sent as an empty object, which is what
     *               MCP expects for methods such as {@code ping} that return nothing
     *
     * @return the response object
     *
     * @since 3.0
     */
    public static JsonObject result( JsonElement id, JsonElement result )
    {
        JsonObject response = new JsonObject();
        response.addProperty( "jsonrpc", JSONRPC_VERSION );
        response.add( "id", id );
        response.add( "result", result == null ? new JsonObject() : result );
        return response;
    }

    /**
     * Builds a JSON-RPC error response.
     *
     * @param id      the id of the request being answered, or {@code null} when it is unknowable
     * @param code    one of the codes in {@link McpErrors}
     * @param message a short, human-readable summary
     *
     * @return the error response object
     *
     * @since 3.0
     */
    public static JsonObject error( JsonElement id, int code, String message )
    {
        return error( id, code, message, null );
    }

    /**
     * Builds a JSON-RPC error response carrying additional structured data.
     * <p>
     * Callers are responsible for ensuring {@code data} contains nothing sensitive — error
     * payloads cross the same trust boundary as results.
     *
     * @param id      the id of the request being answered, or {@code null} when it is unknowable
     * @param code    one of the codes in {@link McpErrors}
     * @param message a short, human-readable summary
     * @param data    optional structured detail, or {@code null} to omit the field
     *
     * @return the error response object
     *
     * @since 3.0
     */
    public static JsonObject error( JsonElement id, int code, String message, JsonElement data )
    {
        JsonObject error = new JsonObject();
        error.addProperty( "code", code );
        error.addProperty( "message", message == null ? "" : message );
        if ( data != null ) {
            error.add( "data", data );
        }

        JsonObject response = new JsonObject();
        response.addProperty( "jsonrpc", JSONRPC_VERSION );
        response.add( "id", id );
        response.add( "error", error );
        return response;
    }

    /**
     * Serializes a response object to compact JSON for the wire.
     *
     * @param response the response to encode
     *
     * @return the encoded JSON text
     *
     * @since 3.0
     */
    public static String encode( JsonObject response )
    {
        return JSONUtilities.getGson().toJson( response );
    }

    /**
     * Builds a failed {@link Parse} carrying an error response.
     *
     * @param id      the id to echo, or {@code null}
     * @param code    the error code
     * @param message the error message
     *
     * @return the failed parse outcome
     */
    private static Parse rejected( JsonElement id, int code, String message )
    {
        return new Parse( null, error( id, code, message ) );
    }

    /**
     * Not instantiable.
     */
    private JsonRpcCodec()
    {
        throw new AssertionError( "JsonRpcCodec is a utility class and must not be instantiated" );
    }
}
