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

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link JsonRpcCodec} — the outermost layer of the MCP server.
 *
 * <p>Why this matters: everything this class lets through reaches code that can launch
 * games, install modpacks, and delete user data. It is the only place where arbitrary
 * bytes off a socket become a structured request, so its rejection behaviour is a security
 * boundary, not a formatting concern.</p>
 *
 * <p>Two rejections are load-bearing rather than pedantic. <b>Batches</b> are refused
 * outright — MCP dropped batch support, and a half-processed batch would mean some tools
 * ran and some did not with no coherent way to report that. And an <b>id of the wrong
 * type</b> is never echoed back: echoing caller-supplied objects or arrays into a response
 * turns the id field into a reflection primitive.</p>
 */
class JsonRpcCodecTest
{
    private static final String OK_REQUEST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

    // region accepted messages

    @Test
    void aWellFormedRequestParses()
    {
        JsonRpcCodec.Parse parse = JsonRpcCodec.parse( OK_REQUEST );
        assertTrue( parse.ok() );
        assertEquals( "ping", parse.message().method() );
        assertEquals( 1, parse.message().id().getAsInt() );
        assertFalse( parse.message().isNotification() );
    }

    @Test
    void aStringIdIsAccepted()
    {
        JsonRpcCodec.Parse parse = JsonRpcCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":\"abc-123\",\"method\":\"ping\"}" );
        assertTrue( parse.ok() );
        assertEquals( "abc-123", parse.message().id().getAsString() );
    }

    @Test
    void aMessageWithNoIdIsANotification()
    {
        JsonRpcCodec.Parse parse = JsonRpcCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}" );
        assertTrue( parse.ok() );
        assertTrue( parse.message().isNotification() );
        assertNull( parse.message().id() );
    }

    @Test
    void objectParamsAreCarriedThrough()
    {
        JsonRpcCodec.Parse parse = JsonRpcCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"," +
                        "\"params\":{\"name\":\"list_modpacks\"}}" );
        assertTrue( parse.ok() );
        assertEquals( "list_modpacks", parse.message().paramsObject().get( "name" ).getAsString() );
    }

    @Test
    void arrayParamsAreAcceptedButReadAsAnEmptyObject()
    {
        JsonRpcCodec.Parse parse = JsonRpcCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":[1,2]}" );
        assertTrue( parse.ok() );
        assertTrue( parse.message().params().isJsonArray() );
        assertEquals( 0, parse.message().paramsObject().size() );
    }

    @Test
    void absentParamsReadAsAnEmptyObject()
    {
        JsonRpcCodec.Parse parse = JsonRpcCodec.parse( OK_REQUEST );
        assertNull( parse.message().params() );
        assertEquals( 0, parse.message().paramsObject().size() );
    }

    /** An explicit JSON null for params is equivalent to omitting the field. */
    @Test
    void nullParamsAreTreatedAsAbsent()
    {
        JsonRpcCodec.Parse parse = JsonRpcCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":null}" );
        assertTrue( parse.ok() );
        assertNull( parse.message().params() );
    }

    @Test
    void unknownTopLevelFieldsAreIgnoredRatherThanRejected()
    {
        JsonRpcCodec.Parse parse = JsonRpcCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"extra\":\"ignored\"}" );
        assertTrue( parse.ok() );
    }

    // endregion

    // region rejected messages

    @Test
    void nullInputIsAParseError()
    {
        assertRejected( JsonRpcCodec.parse( null ), McpErrors.PARSE_ERROR );
    }

    @Test
    void blankInputIsAParseError()
    {
        assertRejected( JsonRpcCodec.parse( "   " ), McpErrors.PARSE_ERROR );
    }

    @Test
    void malformedJsonIsAParseError()
    {
        assertRejected( JsonRpcCodec.parse( "{\"jsonrpc\":" ), McpErrors.PARSE_ERROR );
    }

    @Test
    void aBareJsonNullIsAParseError()
    {
        assertRejected( JsonRpcCodec.parse( "null" ), McpErrors.PARSE_ERROR );
    }

    /**
     * MCP removed JSON-RPC batching. Accepting a batch would mean partially applying it with
     * no coherent way to report which halves ran.
     */
    @Test
    void aBatchIsRejectedOutright()
    {
        assertRejected( JsonRpcCodec.parse( "[" + OK_REQUEST + "," + OK_REQUEST + "]" ),
                        McpErrors.INVALID_REQUEST );
    }

    @Test
    void anEmptyBatchIsAlsoRejected()
    {
        assertRejected( JsonRpcCodec.parse( "[]" ), McpErrors.INVALID_REQUEST );
    }

    @Test
    void aScalarRootIsRejected()
    {
        assertRejected( JsonRpcCodec.parse( "42" ), McpErrors.INVALID_REQUEST );
        assertRejected( JsonRpcCodec.parse( "\"hello\"" ), McpErrors.INVALID_REQUEST );
    }

    @Test
    void aMissingJsonrpcVersionIsRejected()
    {
        assertRejected( JsonRpcCodec.parse( "{\"id\":1,\"method\":\"ping\"}" ),
                        McpErrors.INVALID_REQUEST );
    }

    @Test
    void aWrongJsonrpcVersionIsRejected()
    {
        assertRejected( JsonRpcCodec.parse( "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}" ),
                        McpErrors.INVALID_REQUEST );
    }

    @Test
    void aMissingMethodIsRejected()
    {
        assertRejected( JsonRpcCodec.parse( "{\"jsonrpc\":\"2.0\",\"id\":1}" ),
                        McpErrors.INVALID_REQUEST );
    }

    @Test
    void aNonStringMethodIsRejected()
    {
        assertRejected( JsonRpcCodec.parse( "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":7}" ),
                        McpErrors.INVALID_REQUEST );
    }

    @Test
    void aBlankMethodIsRejected()
    {
        assertRejected( JsonRpcCodec.parse( "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"  \"}" ),
                        McpErrors.INVALID_REQUEST );
    }

    /**
     * A present-but-null id is neither a request nor a notification. Treating it as a
     * notification would silently swallow a call the client is waiting on.
     */
    @Test
    void anExplicitlyNullIdIsRejected()
    {
        assertRejected( JsonRpcCodec.parse( "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}" ),
                        McpErrors.INVALID_REQUEST );
    }

    @Test
    void aStructuredIdIsRejected()
    {
        assertRejected( JsonRpcCodec.parse( "{\"jsonrpc\":\"2.0\",\"id\":{\"a\":1},\"method\":\"ping\"}" ),
                        McpErrors.INVALID_REQUEST );
        assertRejected( JsonRpcCodec.parse( "{\"jsonrpc\":\"2.0\",\"id\":[1],\"method\":\"ping\"}" ),
                        McpErrors.INVALID_REQUEST );
    }

    @Test
    void aBooleanIdIsRejected()
    {
        assertRejected( JsonRpcCodec.parse( "{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"ping\"}" ),
                        McpErrors.INVALID_REQUEST );
    }

    @Test
    void nonStructuredParamsAreRejected()
    {
        assertRejected( JsonRpcCodec.parse(
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":\"nope\"}" ),
                        McpErrors.INVALID_REQUEST );
    }

    // endregion

    // region id echoing

    /**
     * A readable, valid id is echoed on a later validation failure so the client can match the
     * error to the call it made.
     */
    @Test
    void aValidIdIsEchoedOnALaterValidationFailure()
    {
        JsonRpcCodec.Parse parse = JsonRpcCodec.parse( "{\"jsonrpc\":\"1.0\",\"id\":77,\"method\":\"ping\"}" );
        assertFalse( parse.ok() );
        assertEquals( 77, parse.error().get( "id" ).getAsInt() );
    }

    /**
     * The security-relevant half: an id of the wrong type is <b>never</b> reflected back.
     * Echoing caller-supplied structure would make the id field a reflection primitive.
     */
    @Test
    void anInvalidIdIsNeverEchoed()
    {
        JsonRpcCodec.Parse parse = JsonRpcCodec.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":{\"evil\":\"payload\"},\"method\":\"ping\"}" );
        assertFalse( parse.ok() );
        assertTrue( parse.error().get( "id" ).isJsonNull() );
        assertFalse( JsonRpcCodec.encode( parse.error() ).contains( "evil" ) );
    }

    @Test
    void aParseErrorCarriesANullId()
    {
        JsonRpcCodec.Parse parse = JsonRpcCodec.parse( "{ broken" );
        assertTrue( parse.error().get( "id" ).isJsonNull() );
    }

    // endregion

    // region response building

    @Test
    void aResultResponseHasTheExpectedShape()
    {
        JsonObject payload = new JsonObject();
        payload.addProperty( "ok", true );
        JsonObject response = JsonRpcCodec.result( new JsonPrimitive( 5 ), payload );

        assertEquals( "2.0", response.get( "jsonrpc" ).getAsString() );
        assertEquals( 5, response.get( "id" ).getAsInt() );
        assertTrue( response.getAsJsonObject( "result" ).get( "ok" ).getAsBoolean() );
        assertFalse( response.has( "error" ) );
    }

    /** {@code ping} and similar no-op methods answer with an empty object, not a JSON null. */
    @Test
    void aNullResultBecomesAnEmptyObject()
    {
        JsonObject response = JsonRpcCodec.result( new JsonPrimitive( 5 ), null );
        assertTrue( response.get( "result" ).isJsonObject() );
        assertEquals( 0, response.getAsJsonObject( "result" ).size() );
    }

    @Test
    void anErrorResponseHasTheExpectedShape()
    {
        JsonObject response = JsonRpcCodec.error( new JsonPrimitive( "x" ),
                                                  McpErrors.METHOD_NOT_FOUND, "No such method" );
        assertEquals( "2.0", response.get( "jsonrpc" ).getAsString() );
        assertEquals( "x", response.get( "id" ).getAsString() );
        assertEquals( McpErrors.METHOD_NOT_FOUND, response.getAsJsonObject( "error" ).get( "code" ).getAsInt() );
        assertEquals( "No such method", response.getAsJsonObject( "error" ).get( "message" ).getAsString() );
        assertFalse( response.has( "result" ) );
        assertFalse( response.getAsJsonObject( "error" ).has( "data" ) );
    }

    @Test
    void errorDataIsIncludedOnlyWhenSupplied()
    {
        JsonObject data = new JsonObject();
        data.addProperty( "tool", "list_modpacks" );
        JsonObject response = JsonRpcCodec.error( null, McpErrors.INTERNAL_ERROR, "boom", data );
        assertEquals( "list_modpacks",
                      response.getAsJsonObject( "error" ).getAsJsonObject( "data" ).get( "tool" ).getAsString() );
    }

    @Test
    void aNullErrorMessageBecomesAnEmptyString()
    {
        JsonObject response = JsonRpcCodec.error( null, McpErrors.INTERNAL_ERROR, null );
        assertEquals( "", response.getAsJsonObject( "error" ).get( "message" ).getAsString() );
    }

    /** The id field is always present, as JSON null, even when it could not be determined. */
    @Test
    void anErrorWithNoIdStillCarriesTheIdField()
    {
        JsonObject response = JsonRpcCodec.error( null, McpErrors.PARSE_ERROR, "bad" );
        assertTrue( response.has( "id" ) );
        assertTrue( response.get( "id" ).isJsonNull() );
    }

    @Test
    void encodeProducesCompactJsonThatParsesBack()
    {
        String encoded = JsonRpcCodec.encode( JsonRpcCodec.result( new JsonPrimitive( 1 ), null ) );
        assertFalse( encoded.contains( "\n" ) );
        assertTrue( JsonRpcCodec.parse( "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}" ).ok() );
        assertTrue( encoded.contains( "\"jsonrpc\":\"2.0\"" ) );
    }

    // endregion

    /**
     * Asserts that a parse failed with the expected error code and produced no message.
     *
     * @param parse        the parse outcome under test
     * @param expectedCode the JSON-RPC error code the rejection should carry
     */
    private static void assertRejected( JsonRpcCodec.Parse parse, int expectedCode )
    {
        assertFalse( parse.ok(), "expected rejection" );
        assertNull( parse.message() );
        assertEquals( expectedCode, parse.error().getAsJsonObject( "error" ).get( "code" ).getAsInt() );
    }
}
