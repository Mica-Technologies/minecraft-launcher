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

package com.micatechnologies.minecraft.launcher.mcp.transport;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.mcp.McpEndpointFile;
import com.micatechnologies.minecraft.launcher.mcp.McpServer;
import com.micatechnologies.minecraft.launcher.mcp.approval.McpRiskClass;
import com.micatechnologies.minecraft.launcher.mcp.protocol.McpErrors;
import com.micatechnologies.minecraft.launcher.mcp.resources.McpResourceRegistry;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpCallContext;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpTool;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpToolRegistry;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpToolResult;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for {@link StdioProxy} — the {@code launcher --mcp} relay — driven against
 * a real {@link McpServer} on a loopback socket.
 *
 * <p>This is the path an MCP client actually takes. Clients launch their server as a
 * subprocess and speak newline-delimited JSON-RPC over stdio; the launcher cannot be that
 * subprocess, because it enforces single-instance, so everything a user does through Claude
 * Code goes through this relay.</p>
 *
 * <p>Two properties are worth more than the rest. <b>stdout carries protocol and nothing
 * else</b> — a single stray diagnostic line there corrupts the JSON-RPC stream and the client
 * simply breaks, which is why every message here is checked for parseability rather than just
 * presence. And <b>a client waiting on an id must always get that id back</b>, including when
 * the launcher goes away mid-conversation; a reply that silently never arrives hangs the
 * client forever.</p>
 */
class StdioProxyTest
{
    private McpServer server;
    private Path endpointPath;

    @TempDir
    Path tempDir;

    /** Minimal tool, so a relayed call has somewhere to land. */
    private record EchoTool() implements McpTool
    {
        @Override
        public String name() { return "echo"; }

        @Override
        public String title() { return "Echo"; }

        @Override
        public String description() { return "Returns a fixed string"; }

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
            return McpToolResult.text( "echo:" + context.clientName() );
        }
    }

    @BeforeEach
    void startServer() throws IOException
    {
        McpToolRegistry tools = new McpToolRegistry();
        tools.register( new EchoTool() );
        server = new McpServer( tools, new McpResourceRegistry(), ( t, c, a ) -> true );
        endpointPath = tempDir.resolve( McpEndpointFile.FILENAME );
        server.start( 0, endpointPath );
    }

    @AfterEach
    void stopServer()
    {
        if ( server != null ) {
            server.stop();
        }
    }

    // region no launcher to talk to

    @Test
    void anAbsentEndpointFileReportsNoLauncherAndWritesNothingToStdout()
    {
        server.stop();
        Result result = relay( "" );
        assertEquals( StdioProxy.EXIT_NO_LAUNCHER, result.exitCode() );
        assertTrue( result.out().isEmpty(), "stdout must stay clean: " + result.out() );
        assertTrue( result.err().contains( "MCP server" ), result.err() );
    }

    /**
     * A file left behind by a crashed launcher must read as absent. Otherwise the relay would
     * connect to whatever now owns that port, carrying a token nobody there issued.
     */
    @Test
    void aStaleEndpointFileReportsNoLauncher() throws IOException
    {
        server.stop();
        Files.writeString( endpointPath,
                           "{\"port\":65000,\"token\":\"deadbeef\",\"pid\":" + deadPid() + "}",
                           StandardCharsets.UTF_8 );
        assertEquals( StdioProxy.EXIT_NO_LAUNCHER, relay( "" ).exitCode() );
    }

    // endregion

    // region relaying

    @Test
    void aPingIsRelayedAndAnswered()
    {
        Result result = relay( ping( 1 ) );
        assertEquals( 0, result.exitCode() );

        List< JsonObject > replies = result.parsedOut();
        assertEquals( 1, replies.size() );
        assertEquals( 1, replies.get( 0 ).get( "id" ).getAsInt() );
        assertTrue( replies.get( 0 ).has( "result" ) );
    }

    /**
     * The relay carries the session id forward on its own. Without that, every message after
     * the handshake would look like a fresh un-initialized session and be refused — the client
     * never sees the header, so it cannot do this itself.
     */
    @Test
    void theSessionIdIsCarriedForwardAcrossMessages()
    {
        Result result = relay( initialize( 1 ), toolCall( 2 ) );

        List< JsonObject > replies = result.parsedOut();
        assertEquals( 2, replies.size() );
        assertTrue( replies.get( 0 ).has( "result" ), "handshake failed: " + replies.get( 0 ) );

        JsonObject callReply = replies.get( 1 );
        assertFalse( callReply.has( "error" ), "the tool call was refused: " + callReply );
        assertEquals( "echo:Relay Test",
                      callReply.getAsJsonObject( "result" ).getAsJsonArray( "content" )
                              .get( 0 ).getAsJsonObject().get( "text" ).getAsString() );
    }

    /** A notification must not produce a reply line, or the client's stream desynchronizes. */
    @Test
    void aNotificationProducesNoOutputLine()
    {
        Result result = relay( initialize( 1 ),
                               "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                               ping( 2 ) );

        List< JsonObject > replies = result.parsedOut();
        assertEquals( 2, replies.size(), "expected exactly the handshake and ping replies" );
        assertEquals( 1, replies.get( 0 ).get( "id" ).getAsInt() );
        assertEquals( 2, replies.get( 1 ).get( "id" ).getAsInt() );
    }

    @Test
    void blankInputLinesAreSkipped()
    {
        Result result = relay( "", "   ", ping( 1 ), "" );
        assertEquals( 1, result.parsedOut().size() );
    }

    /** A malformed message is answered by the launcher, not swallowed by the relay. */
    @Test
    void malformedJsonIsRelayedAndAnsweredWithAParseError()
    {
        List< JsonObject > replies = relay( "{ not json" ).parsedOut();
        assertEquals( 1, replies.size() );
        assertEquals( McpErrors.PARSE_ERROR,
                      replies.get( 0 ).getAsJsonObject( "error" ).get( "code" ).getAsInt() );
    }

    // endregion

    // region the launcher going away

    /**
     * The property that keeps a client from hanging forever: when the launcher disappears
     * mid-conversation, the relay still answers, and it answers with the id the client is
     * waiting on. Recovering the id by re-parsing the outgoing line is why that works.
     */
    @Test
    void aLauncherThatDisappearsStillProducesAnErrorCarryingTheRequestId()
    {
        Result result = relay( this::killServerLeavingItsEndpointFile, ping( 77 ) );

        List< JsonObject > replies = result.parsedOut();
        assertEquals( 1, replies.size() );
        assertEquals( 77, replies.get( 0 ).get( "id" ).getAsInt() );
        assertEquals( McpErrors.INTERNAL_ERROR,
                      replies.get( 0 ).getAsJsonObject( "error" ).get( "code" ).getAsInt() );
    }

    /** Diagnostics about that failure go to stderr, never into the protocol stream. */
    @Test
    void failureDiagnosticsNeverReachStdout()
    {
        Result result = relay( this::killServerLeavingItsEndpointFile, ping( 1 ) );
        assertFalse( result.err().isEmpty(), "the failure should be reported somewhere" );
        for ( String line : result.out().split( "\n" ) ) {
            if ( !line.isBlank() ) {
                assertTrue( line.trim().startsWith( "{" ),
                            "stdout must carry protocol only, found: " + line );
            }
        }
    }

    // endregion

    // region helpers

    /** One relay run's captured streams and exit code. */
    private record Result( int exitCode, String out, String err )
    {
        /** Parses stdout as one JSON object per non-blank line. */
        List< JsonObject > parsedOut()
        {
            List< JsonObject > parsed = new ArrayList<>();
            for ( String line : out.split( "\n" ) ) {
                if ( line.isBlank() ) {
                    continue;
                }
                parsed.add( JSONUtilities.getGson().fromJson( line, JsonObject.class ) );
            }
            return parsed;
        }
    }

    /**
     * Simulates a launcher that dies mid-conversation: the port stops answering, but the
     * endpoint file it published is still on disk and still names a live process, so the relay
     * gets past discovery and fails at connect time — which is the case a client is actually
     * exposed to, and the one where a missing reply would hang it.
     */
    private void killServerLeavingItsEndpointFile()
    {
        String published;
        try {
            published = Files.readString( endpointPath, StandardCharsets.UTF_8 );
        }
        catch ( IOException e ) {
            throw new IllegalStateException( "the endpoint file should exist while running", e );
        }
        server.stop();
        try {
            Files.writeString( endpointPath, published, StandardCharsets.UTF_8 );
        }
        catch ( IOException e ) {
            throw new IllegalStateException( "could not restore the endpoint file", e );
        }
    }

    private Result relay( String... lines )
    {
        return relay( null, lines );
    }

    private Result relay( Runnable beforeSending, String... lines )
    {
        if ( beforeSending != null ) {
            beforeSending.run();
        }
        ByteArrayInputStream in = new ByteArrayInputStream(
                String.join( "\n", lines ).getBytes( StandardCharsets.UTF_8 ) );
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = StdioProxy.run( endpointPath, in,
                                   new PrintStream( out, true, StandardCharsets.UTF_8 ),
                                   new PrintStream( err, true, StandardCharsets.UTF_8 ) );
        return new Result( exit, out.toString( StandardCharsets.UTF_8 ),
                           err.toString( StandardCharsets.UTF_8 ) );
    }

    private static String ping( int id )
    {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"ping\"}";
    }

    private static String initialize( int id )
    {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"initialize\","
                + "\"params\":{\"clientInfo\":{\"name\":\"Relay Test\",\"version\":\"1.0\"}}}";
    }

    private static String toolCall( int id )
    {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo\",\"arguments\":{}}}";
    }

    private static long deadPid()
    {
        for ( long candidate = 900_000; candidate < 999_999; candidate++ ) {
            if ( ProcessHandle.of( candidate ).isEmpty() ) {
                return candidate;
            }
        }
        throw new IllegalStateException( "Could not find an unused process id" );
    }

    // endregion
}
