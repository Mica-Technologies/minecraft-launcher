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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.mcp.protocol.JsonRpcCodec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The HTTP listener that carries MCP traffic, bound to loopback only.
 * <p>
 * The bind address is {@link InetAddress#getLoopbackAddress()} and never anything else — the
 * plan's non-goals are explicit that the listener must refuse to bind a non-loopback address,
 * so there is deliberately no way to configure one. Everything that arrives is then put through
 * {@link McpHttpGuard} before a byte of body is read, because binding loopback does not keep out
 * a web page the user already has open.
 * <p>
 * Request handling runs on a small daemon pool; the body is dispatched by a
 * {@link BodyHandler} the owner supplies, which is where request serialization happens.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class LoopbackHttpTransport
{
    /** The single path MCP traffic is accepted on. */
    public static final String PATH = "/mcp";

    /** Header carrying the session id, per MCP's Streamable HTTP transport. */
    public static final String SESSION_HEADER = "Mcp-Session-Id";

    /**
     * Threads serving HTTP requests. Tool calls are serialized downstream and one can sit on a
     * consent dialog for minutes, holding its thread; the headroom keeps pings and listings
     * answerable while a few calls queue behind it.
     */
    private static final int HTTP_THREADS = 8;

    /**
     * Turns one request body into one response body.
     *
     * @since 3.0
     */
    @FunctionalInterface
    public interface BodyHandler
    {
        /**
         * Handles one request.
         *
         * @param body      the raw request body
         * @param sessionId the value of the {@code Mcp-Session-Id} header, or {@code null}
         *
         * @return the response to send
         *
         * @since 3.0
         */
        Response handle( String body, String sessionId );
    }

    /**
     * Ends a session on the client's request ({@code DELETE /mcp}).
     *
     * @since 2026.10
     */
    @FunctionalInterface
    public interface SessionCloser
    {
        /**
         * Ends one session.
         *
         * @param sessionId the value of the {@code Mcp-Session-Id} header
         *
         * @return {@code true} when a live session was ended; {@code false} when none matched
         *
         * @since 2026.10
         */
        boolean close( String sessionId );
    }

    /**
     * One response to send back.
     *
     * @param json      the response body, or {@code null} to send no body — which is what a
     *                  JSON-RPC notification gets, since it must not be answered
     * @param sessionId the session id to echo in the response header, or {@code null}
     * @param status    the HTTP status; {@code 200} for an ordinary reply ({@code 202} is used
     *                  instead when there is no body), {@code 400} for a request that needs a
     *                  session but carried none, {@code 404} for an unknown session
     *
     * @since 3.0
     */
    public record Response( JsonObject json, String sessionId, int status )
    {
        /**
         * Constructs an ordinary {@code 200} response.
         *
         * @param json      the response body, or {@code null} for an accepted notification
         * @param sessionId the session id to echo, or {@code null}
         *
         * @since 3.0
         */
        public Response( JsonObject json, String sessionId )
        {
            this( json, sessionId, 200 );
        }
    }

    /** The bearer token callers must present. */
    private final String token;

    /** Dispatches request bodies. */
    private final BodyHandler bodyHandler;

    /** Ends sessions on {@code DELETE}. */
    private final SessionCloser sessionCloser;

    /** The running server, or {@code null} when stopped. */
    private HttpServer server;

    /**
     * Constructs a transport.
     *
     * @param token       the per-launch bearer token callers must present
     * @param bodyHandler dispatches request bodies
     *
     * @throws IllegalArgumentException if the token is blank or the handler is {@code null}
     * @since 3.0
     */
    public LoopbackHttpTransport( String token, BodyHandler bodyHandler )
    {
        this( token, bodyHandler, sessionId -> false );
    }

    /**
     * Constructs a transport that also lets clients end their session with {@code DELETE}.
     *
     * @param token         the per-launch bearer token callers must present
     * @param bodyHandler   dispatches request bodies
     * @param sessionCloser ends a session on {@code DELETE /mcp}
     *
     * @throws IllegalArgumentException if the token is blank or a handler is {@code null}
     * @since 2026.10
     */
    public LoopbackHttpTransport( String token, BodyHandler bodyHandler, SessionCloser sessionCloser )
    {
        if ( sessionCloser == null ) {
            throw new IllegalArgumentException( "A session closer is required" );
        }
        this.sessionCloser = sessionCloser;
        if ( token == null || token.isBlank() ) {
            throw new IllegalArgumentException( "A non-blank bearer token is required" );
        }
        if ( bodyHandler == null ) {
            throw new IllegalArgumentException( "A body handler is required" );
        }
        this.token = token;
        this.bodyHandler = bodyHandler;
    }

    /**
     * Binds and starts the listener.
     *
     * @param port the loopback port to bind, or {@code 0} to let the OS choose one
     *
     * @return the port actually bound
     *
     * @throws IOException           if the port cannot be bound
     * @throws IllegalStateException if the transport is already running
     * @since 3.0
     */
    public synchronized int start( int port ) throws IOException
    {
        if ( server != null ) {
            throw new IllegalStateException( "The MCP transport is already running" );
        }

        // Loopback only, always. There is no configuration path to a routable address.
        InetSocketAddress address = new InetSocketAddress( InetAddress.getLoopbackAddress(), port );
        HttpServer created = HttpServer.create( address, 0 );
        created.createContext( PATH, this::serve );
        created.setExecutor( Executors.newFixedThreadPool( HTTP_THREADS, daemonThreads() ) );
        created.start();
        server = created;
        return created.getAddress().getPort();
    }

    /**
     * Stops the listener, if it is running.
     *
     * @since 3.0
     */
    public synchronized void stop()
    {
        if ( server != null ) {
            server.stop( 0 );
            server = null;
        }
    }

    /**
     * Reports whether the listener is running.
     *
     * @return {@code true} while bound
     *
     * @since 3.0
     */
    public synchronized boolean isRunning()
    {
        return server != null;
    }

    /**
     * Serves one HTTP exchange.
     *
     * @param exchange the exchange to serve
     *
     * @throws IOException if writing the response fails
     */
    private void serve( HttpExchange exchange ) throws IOException
    {
        try {
            McpHttpGuard.Request request = new McpHttpGuard.Request(
                    exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst( "Authorization" ),
                    exchange.getRequestHeaders().getFirst( "Origin" ),
                    exchange.getRequestHeaders().getFirst( "Content-Type" ),
                    declaredLength( exchange ),
                    McpHttpGuard.isLoopback( exchange.getRemoteAddress() == null
                                             ? null
                                             : exchange.getRemoteAddress().getAddress() ) );

            McpHttpGuard.Verdict verdict = McpHttpGuard.admit( request, token );
            if ( verdict != McpHttpGuard.Verdict.ALLOW ) {
                if ( verdict == McpHttpGuard.Verdict.PAYLOAD_TOO_LARGE ) {
                    // Only reached by an authenticated caller: the size check runs after the
                    // token check, so nobody else can make us read their upload.
                    try ( InputStream in = exchange.getRequestBody() ) {
                        discardUpTo( in, DRAIN_LIMIT_BYTES );
                    }
                }
                // No detail in the body: a rejected caller learns the status and nothing more.
                sendEmpty( exchange, statusFor( verdict ) );
                return;
            }

            if ( "DELETE".equalsIgnoreCase( exchange.getRequestMethod() ) ) {
                // The client is ending its session. 400 without an id to end, 404 for one we
                // do not hold, per MCP's Streamable HTTP transport.
                String sessionId = exchange.getRequestHeaders().getFirst( SESSION_HEADER );
                if ( sessionId == null || sessionId.isBlank() ) {
                    sendEmpty( exchange, 400 );
                }
                else {
                    sendEmpty( exchange, sessionCloser.close( sessionId ) ? 204 : 404 );
                }
                return;
            }

            String body = readCappedBody( exchange );
            if ( body == null ) {
                sendEmpty( exchange, 413 );
                return;
            }

            Response response = bodyHandler.handle( body, exchange.getRequestHeaders()
                    .getFirst( SESSION_HEADER ) );
            if ( response == null || response.json() == null ) {
                // A notification. MCP expects an accepted-with-no-content answer -- unless it
                // was refused, in which case the refusal status still has to reach the client.
                sendEmpty( exchange, response == null || response.status() == 200 ? 202
                                                                                   : response.status() );
                return;
            }
            if ( response.sessionId() != null ) {
                exchange.getResponseHeaders().add( SESSION_HEADER, response.sessionId() );
            }
            sendJson( exchange, response.status(), JsonRpcCodec.encode( response.json() ) );
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.get( "log.mcpTransport.serveFailed" ) );
            Logger.logThrowable( e );
            sendEmpty( exchange, 500 );
        }
        finally {
            exchange.close();
        }
    }

    /**
     * Reads the request body, refusing anything past the cap.
     * <p>
     * The declared {@code Content-Length} is already checked by the guard, but a chunked body
     * declares no length at all, so the cap has to be enforced again while reading rather than
     * trusted from a header.
     *
     * @param exchange the exchange to read from
     *
     * @return the body, or {@code null} when it exceeds the cap
     *
     * @throws IOException if reading fails
     */
    private static String readCappedBody( HttpExchange exchange ) throws IOException
    {
        try ( InputStream in = exchange.getRequestBody() ) {
            byte[] bytes = in.readNBytes( (int) McpHttpGuard.MAX_BODY_BYTES + 1 );
            if ( bytes.length > McpHttpGuard.MAX_BODY_BYTES ) {
                discardUpTo( in, DRAIN_LIMIT_BYTES );
                return null;
            }
            return new String( bytes, StandardCharsets.UTF_8 );
        }
    }

    /**
     * How much of a refused, oversized body is read and thrown away before the 413 goes out.
     * <p>
     * The JDK server drains only a small amount of unread request body when an exchange closes.
     * Past that it closes the socket with the client's upload still arriving, which sends a TCP
     * reset, and the client often sees "connection reset" instead of the 413. Reading the rest
     * (up to this bound, cheap on loopback) lets the 413 arrive intact. A body past the bound
     * still gets the reset, which is acceptable for a client that far out of spec.
     */
    private static final long DRAIN_LIMIT_BYTES = 4L * McpHttpGuard.MAX_BODY_BYTES;

    /**
     * Reads and discards up to {@code limit} bytes, stopping early at end of stream. A read
     * failure ends the drain quietly: the caller is refusing the request either way.
     *
     * @param in    the stream to drain
     * @param limit the most bytes to discard
     */
    private static void discardUpTo( InputStream in, long limit )
    {
        byte[] buffer = new byte[ 8192 ];
        long remaining = limit;
        try {
            while ( remaining > 0 ) {
                int read = in.read( buffer, 0, (int) Math.min( buffer.length, remaining ) );
                if ( read < 0 ) {
                    return;
                }
                remaining -= read;
            }
        }
        catch ( IOException e ) {
            // Nothing to do: the request is being refused regardless.
        }
    }

    /**
     * Returns the declared body length, or {@code -1} when it is absent or unparseable.
     *
     * @param exchange the exchange to inspect
     *
     * @return the declared length
     */
    private static long declaredLength( HttpExchange exchange )
    {
        String header = exchange.getRequestHeaders().getFirst( "Content-Length" );
        if ( header == null ) {
            return -1L;
        }
        try {
            return Long.parseLong( header.trim() );
        }
        catch ( NumberFormatException e ) {
            return -1L;
        }
    }

    /**
     * Maps an admission verdict to its HTTP status.
     *
     * @param verdict the verdict
     *
     * @return the status code
     */
    private static int statusFor( McpHttpGuard.Verdict verdict )
    {
        return switch ( verdict ) {
            case UNAUTHORIZED -> 401;
            case FORBIDDEN -> 403;
            case METHOD_NOT_ALLOWED -> 405;
            case PAYLOAD_TOO_LARGE -> 413;
            case UNSUPPORTED_MEDIA_TYPE -> 415;
            case ALLOW -> 200;
        };
    }

    /**
     * Sends a JSON response.
     *
     * @param exchange the exchange to answer
     * @param status   the status code
     * @param json     the response body
     *
     * @throws IOException if writing fails
     */
    private static void sendJson( HttpExchange exchange, int status, String json ) throws IOException
    {
        byte[] bytes = json.getBytes( StandardCharsets.UTF_8 );
        exchange.getResponseHeaders().add( "Content-Type", "application/json; charset=utf-8" );
        exchange.sendResponseHeaders( status, bytes.length );
        try ( OutputStream out = exchange.getResponseBody() ) {
            out.write( bytes );
        }
    }

    /**
     * Sends a bodiless response.
     *
     * @param exchange the exchange to answer
     * @param status   the status code
     *
     * @throws IOException if writing fails
     */
    private static void sendEmpty( HttpExchange exchange, int status ) throws IOException
    {
        exchange.sendResponseHeaders( status, -1 );
    }

    /**
     * Returns a factory producing named daemon threads, so a stuck request never keeps the
     * launcher alive at shutdown.
     *
     * @return the thread factory
     */
    private static ThreadFactory daemonThreads()
    {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread( runnable, "mcp-http-" + counter.incrementAndGet() );
            thread.setDaemon( true );
            return thread;
        };
    }
}
