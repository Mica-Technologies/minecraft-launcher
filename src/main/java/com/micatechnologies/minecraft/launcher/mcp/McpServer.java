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
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.mcp.approval.McpAuthorizer;
import com.micatechnologies.minecraft.launcher.mcp.protocol.JsonRpcCodec;
import com.micatechnologies.minecraft.launcher.mcp.protocol.McpErrors;
import com.micatechnologies.minecraft.launcher.mcp.protocol.McpMethods;
import com.micatechnologies.minecraft.launcher.mcp.resources.McpResourceRegistry;
import com.micatechnologies.minecraft.launcher.mcp.session.McpSession;
import com.micatechnologies.minecraft.launcher.mcp.session.McpSessionRegistry;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpToolRegistry;
import com.micatechnologies.minecraft.launcher.mcp.transport.LoopbackHttpTransport;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The launcher's MCP server: lifecycle, session management, and request serialization.
 * <p>
 * <b>Requests are serialized.</b> Every message is dispatched through a single-threaded
 * executor, because {@code GameModPackManager} is {@code static synchronized} and several pack
 * operations are not safe to interleave. Serializing here makes concurrent calls from one
 * client well-defined instead of racy, at the cost of throughput this workload does not need.
 * <p>
 * Starting generates a fresh bearer token and publishes it, with the bound port, to the
 * owner-only endpoint file. The token is per-launch: a client that cached one from a previous
 * run has to re-read the file rather than reconnecting with a stale credential.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class McpServer
{
    /** Live sessions. */
    private final McpSessionRegistry sessions = new McpSessionRegistry();

    /** Dispatches validated messages. */
    private final McpRequestHandler handler;

    /** The HTTP listener, or {@code null} while stopped. */
    private LoopbackHttpTransport transport;

    /** Serializes message dispatch. {@code null} while stopped. */
    private ExecutorService dispatcher;

    /** The per-launch bearer token, or {@code null} while stopped. */
    private String token;

    /** The bound port, or {@code 0} while stopped. */
    private int port;

    /** Where the endpoint file was written, or {@code null} while stopped. */
    private Path endpointPath;

    /**
     * Constructs a server.
     *
     * @param tools      the tools to expose
     * @param resources  the resources to expose
     * @param authorizer the approval gate applied to every tool call
     *
     * @since 3.0
     */
    public McpServer( McpToolRegistry tools, McpResourceRegistry resources, McpAuthorizer authorizer )
    {
        this.handler = new McpRequestHandler( tools, resources, authorizer );
    }

    /**
     * Binds the listener, generates a bearer token, and publishes the endpoint file.
     *
     * @param requestedPort the loopback port to bind, or {@code 0} to let the OS choose
     * @param endpointFile  where to publish the endpoint descriptor, or {@code null} to skip
     *                      publishing — which is what tests that drive the server directly do
     *
     * @return the port actually bound
     *
     * @throws IOException           if the port cannot be bound or the endpoint file written
     * @throws IllegalStateException if the server is already running
     * @since 3.0
     */
    public synchronized int start( int requestedPort, Path endpointFile ) throws IOException
    {
        if ( transport != null ) {
            throw new IllegalStateException( "The MCP server is already running" );
        }

        token = generateToken();
        transport = new LoopbackHttpTransport( token, this::dispatch );
        dispatcher = Executors.newSingleThreadExecutor( runnable -> {
            Thread thread = new Thread( runnable, "mcp-dispatch" );
            thread.setDaemon( true );
            return thread;
        } );

        try {
            port = transport.start( requestedPort );
        }
        catch ( IOException e ) {
            // Leave no half-started server behind: a transport that failed to bind must not
            // leave a token and executor alive suggesting otherwise.
            shutdownInternals();
            throw e;
        }

        endpointPath = endpointFile;
        if ( endpointPath != null ) {
            try {
                McpEndpointFile.write( endpointPath, new McpEndpointFile.Endpoint(
                        port, token, ProcessHandle.current().pid(),
                        LauncherConstants.LAUNCHER_APPLICATION_VERSION ) );
            }
            catch ( IOException e ) {
                stop();
                throw e;
            }
        }

        Logger.logStd( "MCP server listening on loopback port " + port );
        return port;
    }

    /**
     * Stops the listener, ends every session, and removes the endpoint file.
     * <p>
     * Safe to call when already stopped, since shutdown paths run it unconditionally.
     *
     * @since 3.0
     */
    public synchronized void stop()
    {
        if ( endpointPath != null ) {
            McpEndpointFile.delete( endpointPath );
            endpointPath = null;
        }
        shutdownInternals();
        sessions.clear();
        port = 0;
        Logger.logStd( "MCP server stopped" );
    }

    /**
     * Reports whether the server is running.
     *
     * @return {@code true} while the listener is bound
     *
     * @since 3.0
     */
    public synchronized boolean isRunning()
    {
        return transport != null && transport.isRunning();
    }

    /**
     * Returns the bound loopback port.
     *
     * @return the port, or {@code 0} while stopped
     *
     * @since 3.0
     */
    public synchronized int getPort()
    {
        return port;
    }

    /**
     * Returns the per-launch bearer token.
     * <p>
     * <b>In-process callers only.</b> This is a credential: the plan's section 5.6 forbids it
     * ever reaching a tool result, a resource, an error message, or the activity log. It exists
     * so the endpoint file can be written and so tests can authenticate.
     *
     * @return the token, or {@code null} while stopped
     *
     * @since 3.0
     */
    public synchronized String getToken()
    {
        return token;
    }

    /**
     * Returns the live sessions, for the Settings page.
     *
     * @return a snapshot of the live sessions
     *
     * @since 3.0
     */
    public List< McpSession > getSessions()
    {
        return sessions.all();
    }

    /**
     * Handles one request body: parses it, resolves the session, and dispatches on the
     * single-threaded executor.
     *
     * @param body      the raw request body
     * @param sessionId the {@code Mcp-Session-Id} header, or {@code null}
     *
     * @return the response to send
     */
    private LoopbackHttpTransport.Response dispatch( String body, String sessionId )
    {
        JsonRpcCodec.Parse parse = JsonRpcCodec.parse( body );
        if ( !parse.ok() ) {
            return new LoopbackHttpTransport.Response( parse.error(), null );
        }

        long now = System.currentTimeMillis();
        McpSession session = sessions.find( sessionId );
        if ( session == null ) {
            session = sessions.create( now );
            if ( session == null ) {
                // At the session cap. Refusing is better than evicting someone else's session,
                // which would silently drop their handshake state mid-conversation.
                return new LoopbackHttpTransport.Response(
                        JsonRpcCodec.error( parse.message().id(), McpErrors.INTERNAL_ERROR,
                                            "Too many active MCP sessions" ), null );
            }
        }

        final McpSession target = session;
        JsonObject response;
        try {
            Future< JsonObject > future = dispatcher.submit(
                    () -> handler.handle( parse.message(), target, now ) );
            response = future.get();
        }
        catch ( InterruptedException e ) {
            Thread.currentThread().interrupt();
            response = JsonRpcCodec.error( parse.message().id(), McpErrors.INTERNAL_ERROR,
                                           "Request was interrupted" );
        }
        catch ( ExecutionException e ) {
            Logger.logError( "MCP dispatch failed" );
            Logger.logThrowable( e );
            response = JsonRpcCodec.error( parse.message().id(), McpErrors.INTERNAL_ERROR,
                                           "Request failed" );
        }

        // The session id is echoed on the handshake so the client can carry it afterwards.
        String echoed = McpMethods.INITIALIZE.equals( parse.message().method() ) ? target.getId() : null;
        return new LoopbackHttpTransport.Response( response, echoed );
    }

    /**
     * Tears down the transport, executor, and token without touching the endpoint file.
     */
    private void shutdownInternals()
    {
        if ( transport != null ) {
            transport.stop();
            transport = null;
        }
        if ( dispatcher != null ) {
            dispatcher.shutdownNow();
            dispatcher = null;
        }
        token = null;
    }

    /**
     * Generates a fresh 256-bit bearer token, matching the single-instance IPC token's shape.
     *
     * @return the hex-encoded token
     */
    private static String generateToken()
    {
        byte[] bytes = new byte[ 32 ];
        new SecureRandom().nextBytes( bytes );
        return HexFormat.of().formatHex( bytes );
    }
}
