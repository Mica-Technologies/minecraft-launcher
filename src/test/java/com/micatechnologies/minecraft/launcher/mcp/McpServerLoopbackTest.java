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

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.mcp.approval.McpAuthorizer;
import com.micatechnologies.minecraft.launcher.mcp.approval.McpRiskClass;
import com.micatechnologies.minecraft.launcher.mcp.protocol.McpErrors;
import com.micatechnologies.minecraft.launcher.mcp.resources.McpResourceRegistry;
import com.micatechnologies.minecraft.launcher.mcp.session.McpSessionRegistry;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpCallContext;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpTool;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpToolRegistry;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpToolResult;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for {@link McpServer} over a real loopback socket.
 *
 * <p>Every other MCP test is a unit test of one layer. This one is the proof that the layers
 * are actually wired to each other: a real HTTP request, through the admission checks, the
 * codec, the session registry, the approval gate, and into a tool — and back. A stack where
 * each piece is individually correct but connected wrongly would pass all the others and fail
 * here.</p>
 *
 * <p>It binds an ephemeral port on the loopback interface, so it neither depends on a fixed
 * port being free nor touches the network. It is deliberately part of the default suite rather
 * than gated: the admission checks it exercises are security behaviour, and security behaviour
 * that only runs when someone remembers to opt in is not really tested.</p>
 */
class McpServerLoopbackTest
{
    private McpServer server;
    private int port;
    private String token;
    private HttpClient client;
    private boolean toolRan;
    private volatile boolean approve = true;

    @TempDir
    Path tempDir;

    /** Minimal tool used to prove a call reaches the far end of the stack. */
    private final class EchoTool implements McpTool
    {
        @Override
        public String name() { return "echo"; }

        @Override
        public String title() { return "Echo"; }

        @Override
        public String description() { return "Returns what it was given"; }

        @Override
        public JsonObject inputSchema()
        {
            JsonObject schema = new JsonObject();
            schema.addProperty( "type", "object" );
            return schema;
        }

        @Override
        public McpRiskClass riskClass() { return McpRiskClass.READ_ONLY; }

        @Override
        public McpToolResult invoke( McpCallContext context, JsonObject arguments )
        {
            toolRan = true;
            return McpToolResult.text( "echo:" + context.clientName() );
        }
    }

    @BeforeEach
    void startServer() throws IOException
    {
        McpToolRegistry tools = new McpToolRegistry();
        tools.register( new EchoTool() );
        McpAuthorizer authorizer = ( tool, context, arguments ) -> approve;

        server = new McpServer( tools, new McpResourceRegistry(), authorizer );
        port = server.start( 0, tempDir.resolve( McpEndpointFile.FILENAME ) );
        token = server.getToken();
        client = HttpClient.newBuilder().connectTimeout( Duration.ofSeconds( 5 ) ).build();
        toolRan = false;
        approve = true;
    }

    @AfterEach
    void stopServer()
    {
        if ( server != null ) {
            server.stop();
        }
    }

    // region lifecycle

    @Test
    void theServerBindsAnEphemeralLoopbackPort()
    {
        assertTrue( server.isRunning() );
        assertTrue( port > 0 );
        assertNotNull( token );
        assertEquals( 64, token.length(), "a 256-bit token, hex encoded" );
    }

    @Test
    void theEndpointFileIsPublishedAndRemovedOnStop()
    {
        Path path = tempDir.resolve( McpEndpointFile.FILENAME );
        McpEndpointFile.Endpoint endpoint = McpEndpointFile.readLive( path );
        assertNotNull( endpoint );
        assertEquals( port, endpoint.port() );
        assertEquals( token, endpoint.token() );

        server.stop();
        assertFalse( Files.exists( path ), "a stale endpoint file would misdirect the next client" );
        assertFalse( server.isRunning() );
    }

    @Test
    void startingTwiceIsRefused()
    {
        assertThrows( IllegalStateException.class, () -> server.start( 0, null ) );
    }

    /** Shutdown paths call stop unconditionally, so a second call must be harmless. */
    @Test
    void stoppingTwiceIsHarmless()
    {
        server.stop();
        server.stop();
        assertFalse( server.isRunning() );
        assertNull( server.getToken() );
    }

    // endregion

    // region admission, over a real socket

    @Test
    void aRequestWithNoTokenIsRejected() throws Exception
    {
        HttpResponse< String > response = send( post( ping() ).header( "Content-Type", "application/json" ) );
        assertEquals( 401, response.statusCode() );
        assertTrue( response.body().isEmpty(), "a rejected caller learns nothing but the status" );
    }

    @Test
    void aRequestWithAWrongTokenIsRejected() throws Exception
    {
        HttpResponse< String > response = send( authorized( ping(), "0".repeat( 64 ) ) );
        assertEquals( 401, response.statusCode() );
    }

    /** The DNS-rebinding defence, end to end: a foreign origin loses even with a valid token. */
    @Test
    void aRequestFromAForeignOriginIsRejectedEvenWithAValidToken() throws Exception
    {
        HttpResponse< String > response = send(
                authorized( ping(), token ).header( "Origin", "http://evil.example" ) );
        assertEquals( 403, response.statusCode() );
    }

    @Test
    void aRequestFromALoopbackOriginIsAccepted() throws Exception
    {
        HttpResponse< String > response = send(
                authorized( ping(), token ).header( "Origin", "http://localhost:3000" ) );
        assertEquals( 200, response.statusCode() );
    }

    @Test
    void aGetIsRejected() throws Exception
    {
        HttpResponse< String > response = client.send(
                HttpRequest.newBuilder( endpoint() ).GET()
                        .header( "Authorization", "Bearer " + token ).build(),
                HttpResponse.BodyHandlers.ofString() );
        assertEquals( 405, response.statusCode() );
    }

    @Test
    void aNonJsonContentTypeIsRejected() throws Exception
    {
        HttpResponse< String > response = send(
                HttpRequest.newBuilder( endpoint() )
                        .POST( HttpRequest.BodyPublishers.ofString( ping() ) )
                        .header( "Authorization", "Bearer " + token )
                        .header( "Content-Type", "text/plain" ) );
        assertEquals( 415, response.statusCode() );
    }

    @Test
    void anOversizedBodyIsRejected() throws Exception
    {
        String huge = "{\"padding\":\"" + "x".repeat( (int) ( 1L << 20 ) ) + "\"}";
        HttpResponse< String > response = send( authorized( huge, token ) );
        assertEquals( 413, response.statusCode() );
    }

    // endregion

    // region protocol, over a real socket

    @Test
    void aPingIsAnswered() throws Exception
    {
        JsonObject body = jsonOf( send( authorized( ping(), token ) ) );
        assertEquals( "2.0", body.get( "jsonrpc" ).getAsString() );
        assertTrue( body.has( "result" ) );
    }

    @Test
    void malformedJsonIsAnsweredWithAParseError() throws Exception
    {
        JsonObject body = jsonOf( send( authorized( "{ not json", token ) ) );
        assertEquals( McpErrors.PARSE_ERROR, body.getAsJsonObject( "error" ).get( "code" ).getAsInt() );
    }

    /** A notification must not be answered, so the transport reports accepted-no-content. */
    @Test
    void aNotificationGetsNoResponseBody() throws Exception
    {
        String sessionId = handshake();
        HttpResponse< String > response = send( authorized(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", token )
                                                        .header( "Mcp-Session-Id", sessionId ) );
        assertEquals( 202, response.statusCode() );
        assertTrue( response.body().isEmpty() );
    }

    /**
     * The full path: handshake, carry the session id, call a tool, and see it run. This is the
     * test that would fail if any two layers were wired to each other incorrectly.
     */
    @Test
    void aHandshakeThenAToolCallReachesTheTool() throws Exception
    {
        HttpResponse< String > handshake = send( authorized( initialize(), token ) );
        assertEquals( 200, handshake.statusCode() );

        String sessionId = handshake.headers().firstValue( "Mcp-Session-Id" ).orElse( null );
        assertNotNull( sessionId, "the handshake must hand back a session id" );
        assertEquals( McpRequestHandler.PROTOCOL_VERSION,
                      jsonOf( handshake ).getAsJsonObject( "result" ).get( "protocolVersion" ).getAsString() );

        JsonObject result = jsonOf( send( authorized( callEcho(), token )
                                                  .header( "Mcp-Session-Id", sessionId ) ) )
                .getAsJsonObject( "result" );

        assertTrue( toolRan );
        assertEquals( "echo:Loopback Test",
                      result.getAsJsonArray( "content" ).get( 0 ).getAsJsonObject()
                              .get( "text" ).getAsString() );
    }

    /** Without the session id the request is refused with 400, and creates no session. */
    @Test
    void aToolCallWithoutTheSessionIdIsRefused() throws Exception
    {
        send( authorized( initialize(), token ) );
        HttpResponse< String > response = send( authorized( callEcho(), token ) );

        assertEquals( 400, response.statusCode() );
        assertEquals( McpErrors.INVALID_REQUEST,
                      jsonOf( response ).getAsJsonObject( "error" ).get( "code" ).getAsInt() );
        assertFalse( toolRan );
        assertEquals( 1, server.getSessions().size(), "only the handshake creates a session" );
    }

    /** An id the server does not hold gets 404, which tells the client to initialize again. */
    @Test
    void anUnknownSessionIdIsAnswered404() throws Exception
    {
        HttpResponse< String > response = send( authorized( callEcho(), token )
                                                        .header( "Mcp-Session-Id", "0".repeat( 32 ) ) );

        assertEquals( 404, response.statusCode() );
        assertEquals( McpErrors.SESSION_NOT_FOUND,
                      jsonOf( response ).getAsJsonObject( "error" ).get( "code" ).getAsInt() );
        assertFalse( toolRan );
        assertEquals( 0, server.getSessions().size() );
    }

    /** Requests that are not a handshake never create sessions, so they cannot fill the cap. */
    @Test
    void sessionlessRequestsDoNotCreateSessions() throws Exception
    {
        for ( int i = 0; i < 20; i++ ) {
            send( authorized( ping(), token ) );
            send( authorized( callEcho(), token ) );
        }
        assertEquals( 0, server.getSessions().size() );
    }

    /**
     * Each client restart starts a new session. Past the cap the oldest is evicted rather than
     * the newcomer refused, so restarts can never lock clients out.
     */
    @Test
    void handshakesPastTheCapEvictTheOldestRatherThanRefusing() throws Exception
    {
        String first = handshake();
        for ( int i = 0; i < McpSessionRegistry.MAX_SESSIONS + 4; i++ ) {
            HttpResponse< String > response = send( authorized( initialize(), token ) );
            assertEquals( 200, response.statusCode() );
            assertFalse( jsonOf( response ).has( "error" ), response.body() );
        }
        assertEquals( McpSessionRegistry.MAX_SESSIONS, server.getSessions().size() );
        assertEquals( 404, send( authorized( callEcho(), token ).header( "Mcp-Session-Id", first ) )
                .statusCode() );
    }

    @Test
    void aDeleteEndsTheSession() throws Exception
    {
        String sessionId = handshake();

        assertEquals( 204, delete( sessionId ).statusCode() );
        assertEquals( 0, server.getSessions().size() );
        assertEquals( 404, delete( sessionId ).statusCode(), "it is already gone" );
        assertEquals( 404, send( authorized( callEcho(), token ).header( "Mcp-Session-Id", sessionId ) )
                .statusCode() );
    }

    @Test
    void aDeleteWithoutASessionIdIsABadRequest() throws Exception
    {
        assertEquals( 400, delete( null ).statusCode() );
    }

    @Test
    void aDeleteWithoutTheTokenIsRejected() throws Exception
    {
        String sessionId = handshake();
        HttpResponse< String > response = client.send(
                HttpRequest.newBuilder( endpoint() ).DELETE()
                        .header( "Mcp-Session-Id", sessionId ).build(),
                HttpResponse.BodyHandlers.ofString() );
        assertEquals( 401, response.statusCode() );
        assertEquals( 1, server.getSessions().size() );
    }

    /**
     * A repeated handshake on a live session must not rename the client: the name is half of
     * every session-grant key.
     */
    @Test
    void reInitializingASessionCannotChangeTheClientName() throws Exception
    {
        String sessionId = handshake();
        send( authorized( "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"initialize\"," +
                                  "\"params\":{\"clientInfo\":{\"name\":\"Impostor\",\"version\":\"9\"}}}",
                          token ).header( "Mcp-Session-Id", sessionId ) );

        assertEquals( 1, server.getSessions().size() );
        assertEquals( "Loopback Test", server.getSessions().get( 0 ).getClientName() );
    }

    @Test
    void aDeniedToolCallIsReportedAndNeverRuns() throws Exception
    {
        String sessionId = send( authorized( initialize(), token ) )
                .headers().firstValue( "Mcp-Session-Id" ).orElseThrow();
        approve = false;

        JsonObject body = jsonOf( send( authorized( callEcho(), token )
                                                .header( "Mcp-Session-Id", sessionId ) ) );

        assertEquals( McpErrors.REQUEST_DENIED, body.getAsJsonObject( "error" ).get( "code" ).getAsInt() );
        assertFalse( toolRan );
    }

    @Test
    void aHandshakeRegistersALiveSession() throws Exception
    {
        assertEquals( 0, server.getSessions().size() );
        send( authorized( initialize(), token ) );
        assertEquals( 1, server.getSessions().size() );
        assertEquals( "Loopback Test", server.getSessions().get( 0 ).getClientName() );
    }

    // endregion

    // region helpers

    private String handshake() throws Exception
    {
        return send( authorized( initialize(), token ) )
                .headers().firstValue( "Mcp-Session-Id" ).orElseThrow();
    }

    private HttpResponse< String > delete( String sessionId ) throws Exception
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder( endpoint() ).DELETE()
                .header( "Authorization", "Bearer " + token );
        if ( sessionId != null ) {
            builder.header( "Mcp-Session-Id", sessionId );
        }
        return send( builder );
    }

    private URI endpoint()
    {
        return URI.create( "http://127.0.0.1:" + port + "/mcp" );
    }

    private HttpRequest.Builder post( String body )
    {
        return HttpRequest.newBuilder( endpoint() ).POST( HttpRequest.BodyPublishers.ofString( body ) );
    }

    private HttpRequest.Builder authorized( String body, String bearer )
    {
        return post( body ).header( "Authorization", "Bearer " + bearer )
                .header( "Content-Type", "application/json" );
    }

    private HttpResponse< String > send( HttpRequest.Builder builder ) throws Exception
    {
        return client.send( builder.timeout( Duration.ofSeconds( 10 ) ).build(),
                            HttpResponse.BodyHandlers.ofString() );
    }

    private static JsonObject jsonOf( HttpResponse< String > response )
    {
        return JSONUtilities.getGson().fromJson( response.body(), JsonObject.class );
    }

    private static String ping()
    {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
    }

    private static String initialize()
    {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"," +
                "\"params\":{\"clientInfo\":{\"name\":\"Loopback Test\",\"version\":\"1.0\"}}}";
    }

    private static String callEcho()
    {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"," +
                "\"params\":{\"name\":\"echo\",\"arguments\":{}}}";
    }

    // endregion
}
