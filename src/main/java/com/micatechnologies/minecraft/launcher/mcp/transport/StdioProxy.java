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
import com.micatechnologies.minecraft.launcher.mcp.protocol.JsonRpcCodec;
import com.micatechnologies.minecraft.launcher.mcp.protocol.McpErrors;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Bridges an MCP client's stdio pipe to a running launcher's loopback HTTP server.
 * <p>
 * MCP clients overwhelmingly launch servers as a subprocess and speak newline-delimited
 * JSON-RPC over stdin and stdout. The launcher cannot <em>be</em> that subprocess — it enforces
 * single-instance because concurrent processes mutating the same modpack folders is unsafe — so
 * {@code launcher --mcp} instead runs this thin relay, which forwards each line to the launcher
 * that actually owns the files and writes the reply back.
 * <p>
 * <b>Nothing is interpreted on the way through.</b> The proxy does not parse, validate, or
 * rewrite messages; the running launcher applies every admission check and approval decision
 * exactly as it would for a direct HTTP client. A compromised relay therefore gains nothing
 * that a local process could not already attempt by talking to the port itself.
 * <p>
 * <b>stdout carries protocol only.</b> Every diagnostic goes to stderr — a stray log line on
 * stdout would corrupt the JSON-RPC stream and break the client, which is why the relay never
 * uses the launcher's normal logger.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class StdioProxy
{
    /** How long to wait for the launcher to answer one message. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds( 180 );

    /** Exit code used when no running launcher could be found. */
    public static final int EXIT_NO_LAUNCHER = 2;

    /**
     * Runs the relay until stdin closes.
     *
     * @param endpointPath where to read the running launcher's endpoint descriptor
     * @param in           the client's stdin
     * @param out          the client's stdout — protocol only, never diagnostics
     * @param err          where diagnostics go
     *
     * @return the process exit code
     *
     * @since 3.0
     */
    public static int run( Path endpointPath, InputStream in, PrintStream out, PrintStream err )
    {
        McpEndpointFile.Endpoint endpoint = McpEndpointFile.readLive( endpointPath );
        if ( endpoint == null ) {
            // readLive treats a file left by a dead process as absent, so this covers both
            // "never started" and "crashed without cleaning up".
            err.println( "No running Mica Minecraft Launcher with the MCP server enabled was "
                                 + "found. Start the launcher and turn on Settings → Security "
                                 + "→ Enable MCP server, then reconnect." );
            return EXIT_NO_LAUNCHER;
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout( Duration.ofSeconds( 10 ) )
                .build();
        URI target = URI.create( "http://127.0.0.1:" + endpoint.port() + LoopbackHttpTransport.PATH );
        String sessionId = null;

        try ( BufferedReader reader = new BufferedReader(
                new InputStreamReader( in, StandardCharsets.UTF_8 ) ) ) {
            String line;
            while ( ( line = reader.readLine() ) != null ) {
                if ( line.isBlank() ) {
                    continue;
                }
                Relayed relayed = relay( client, target, endpoint.token(), sessionId, line, err );
                if ( relayed.sessionId() != null ) {
                    sessionId = relayed.sessionId();
                }
                if ( relayed.body() != null ) {
                    // One message per line, flushed immediately: a client blocked waiting on a
                    // buffered reply would look like a hung server.
                    out.println( relayed.body() );
                    out.flush();
                }
            }
        }
        catch ( IOException e ) {
            err.println( "MCP relay stopped: " + e.getClass().getSimpleName() );
            return 1;
        }
        return 0;
    }

    /**
     * One relayed message's outcome.
     *
     * @param body      the response line to write to stdout, or {@code null} for a
     *                  notification, which must not be answered
     * @param sessionId the session id the launcher assigned, or {@code null} when unchanged
     *
     * @since 3.0
     */
    record Relayed( String body, String sessionId )
    {
    }

    /**
     * Forwards one message and returns what to write back.
     *
     * @param client    the HTTP client
     * @param target    the launcher's MCP endpoint
     * @param token     the bearer token from the endpoint file
     * @param sessionId the session id established so far, or {@code null}
     * @param line      the raw JSON-RPC message
     * @param err       where diagnostics go
     *
     * @return the outcome
     */
    private static Relayed relay( HttpClient client, URI target, String token, String sessionId,
                                  String line, PrintStream err )
    {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder( target )
                    .POST( HttpRequest.BodyPublishers.ofString( line, StandardCharsets.UTF_8 ) )
                    .header( "Authorization", "Bearer " + token )
                    .header( "Content-Type", "application/json" )
                    .timeout( REQUEST_TIMEOUT );
            if ( sessionId != null ) {
                request.header( LoopbackHttpTransport.SESSION_HEADER, sessionId );
            }

            HttpResponse< String > response = client.send( request.build(),
                                                           HttpResponse.BodyHandlers.ofString() );
            String assigned = response.headers()
                    .firstValue( LoopbackHttpTransport.SESSION_HEADER ).orElse( null );

            if ( response.statusCode() == 202 || response.body() == null || response.body().isBlank() ) {
                // Accepted with no content: the message was a notification.
                return new Relayed( null, assigned );
            }
            if ( response.statusCode() != 200 ) {
                // Turn a transport-level rejection into a JSON-RPC error the client can read,
                // rather than letting it see a silent gap where a reply should be.
                err.println( "MCP relay: launcher returned HTTP " + response.statusCode() );
                return new Relayed( JsonRpcCodec.encode( transportError(
                        line, "The launcher refused the request (HTTP " + response.statusCode()
                                + "). The MCP server may have been turned off." ) ), assigned );
            }
            return new Relayed( response.body(), assigned );
        }
        catch ( InterruptedException e ) {
            Thread.currentThread().interrupt();
            return new Relayed( JsonRpcCodec.encode( transportError( line, "Interrupted" ) ), null );
        }
        catch ( Exception e ) {
            err.println( "MCP relay: " + e.getClass().getSimpleName() );
            return new Relayed( JsonRpcCodec.encode( transportError(
                    line, "Could not reach the launcher. It may have been closed." ) ), null );
        }
    }

    /**
     * Builds a JSON-RPC error carrying the original request's id where one can be recovered.
     * <p>
     * The id is re-parsed from the outgoing line rather than tracked, because the relay is
     * deliberately stateless about message content — and a client waiting on a specific id
     * needs that id back or it will wait forever.
     *
     * @param line    the message that failed
     * @param message the human-readable reason
     *
     * @return the error response
     */
    private static JsonObject transportError( String line, String message )
    {
        JsonRpcCodec.Parse parse = JsonRpcCodec.parse( line );
        return JsonRpcCodec.error( parse.ok() ? parse.message().id() : null,
                                   McpErrors.INTERNAL_ERROR, message );
    }

    /**
     * Not instantiable.
     */
    private StdioProxy()
    {
        throw new AssertionError( "StdioProxy is a utility class and must not be instantiated" );
    }
}
