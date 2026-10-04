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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.mcp.approval.McpAuthorizer;
import com.micatechnologies.minecraft.launcher.mcp.approval.McpRiskClass;
import com.micatechnologies.minecraft.launcher.mcp.protocol.JsonRpcCodec;
import com.micatechnologies.minecraft.launcher.mcp.protocol.JsonRpcMessage;
import com.micatechnologies.minecraft.launcher.mcp.protocol.McpErrors;
import com.micatechnologies.minecraft.launcher.mcp.protocol.McpMethods;
import com.micatechnologies.minecraft.launcher.mcp.resources.McpResource;
import com.micatechnologies.minecraft.launcher.mcp.resources.McpResourceRegistry;
import com.micatechnologies.minecraft.launcher.mcp.session.McpSession;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpCallContext;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpTool;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpToolRegistry;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpToolResult;

/**
 * Dispatches one validated JSON-RPC message to the right handler and builds the response.
 * <p>
 * This is the whole MCP method surface in one place, deliberately kept free of any transport
 * or JavaFX dependency: it takes a parsed message and a session, and returns a response object
 * or {@code null}. That is what lets the complete protocol surface — handshake ordering,
 * unknown methods, denied tools, throwing tools — be exercised headlessly in tests, without a
 * socket or a toolkit.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class McpRequestHandler
{
    /** The MCP protocol revision this server implements. */
    public static final String PROTOCOL_VERSION = "2025-06-18";

    /** The tools this server exposes. */
    private final McpToolRegistry tools;

    /** The resources this server exposes. */
    private final McpResourceRegistry resources;

    /** Decides whether a tool call may proceed, prompting the user where required. */
    private final McpAuthorizer authorizer;

    /** Records what each call did, for the Settings activity view. Never {@code null}. */
    private final McpActivityLog activityLog;

    /**
     * Constructs a handler.
     *
     * @param tools      the tool registry
     * @param resources  the resource registry
     * @param authorizer the approval gate applied to every tool call
     *
     * @throws IllegalArgumentException if any argument is {@code null}
     * @since 3.0
     */
    public McpRequestHandler( McpToolRegistry tools, McpResourceRegistry resources, McpAuthorizer authorizer )
    {
        this( tools, resources, authorizer, new McpActivityLog() );
    }

    /**
     * Constructs a handler that records into a caller-supplied activity log.
     *
     * @param tools       the tool registry
     * @param resources   the resource registry
     * @param authorizer  the approval gate applied to every tool call
     * @param activityLog where each call's outcome is recorded
     *
     * @throws IllegalArgumentException if any argument is {@code null}
     * @since 3.0
     */
    public McpRequestHandler( McpToolRegistry tools, McpResourceRegistry resources,
                              McpAuthorizer authorizer, McpActivityLog activityLog )
    {
        if ( tools == null || resources == null || authorizer == null || activityLog == null ) {
            throw new IllegalArgumentException(
                    "A tool registry, resource registry, authorizer and activity log are required" );
        }
        this.tools = tools;
        this.resources = resources;
        this.authorizer = authorizer;
        this.activityLog = activityLog;
    }

    /**
     * Returns the activity log this handler records into.
     *
     * @return the activity log
     *
     * @since 3.0
     */
    public McpActivityLog getActivityLog()
    {
        return activityLog;
    }

    /**
     * Handles one message.
     *
     * @param message the validated inbound message
     * @param session the session it arrived on
     * @param nowMs   the current time, in epoch milliseconds, for the session's activity
     *                counters
     *
     * @return the response to send, or {@code null} when the message was a notification and
     *         must not be answered
     *
     * @since 3.0
     */
    public JsonObject handle( JsonRpcMessage message, McpSession session, long nowMs )
    {
        if ( message == null || session == null ) {
            return JsonRpcCodec.error( null, McpErrors.INTERNAL_ERROR, "No message to handle" );
        }

        String method = message.method();
        session.recordActivity( nowMs, McpMethods.TOOLS_CALL.equals( method ) );

        // Notifications are never answered, including unknown ones -- replying to one is a
        // protocol violation, and an unknown notification is explicitly not an error.
        if ( message.isNotification() ) {
            if ( McpMethods.NOTIFICATIONS_INITIALIZED.equals( method ) ) {
                Logger.logDebug( "MCP client initialized: " + session.getClientName() );
            }
            return null;
        }

        if ( McpMethods.INITIALIZE.equals( method ) ) {
            return handleInitialize( message, session );
        }

        // Everything except the handshake and a liveness check requires an initialized
        // session. Without this, a client could call tools before declaring who it is -- and
        // the client name is half of every session-grant key.
        if ( !session.isInitialized() && !McpMethods.PING.equals( method ) ) {
            return JsonRpcCodec.error( message.id(), McpErrors.INVALID_REQUEST,
                                       "Session is not initialized" );
        }

        return switch ( method ) {
            case McpMethods.PING -> JsonRpcCodec.result( message.id(), null );
            case McpMethods.TOOLS_LIST -> JsonRpcCodec.result( message.id(), tools.listResult() );
            case McpMethods.TOOLS_CALL -> handleToolsCall( message, session );
            case McpMethods.RESOURCES_LIST -> JsonRpcCodec.result( message.id(), resources.listResult() );
            case McpMethods.RESOURCES_TEMPLATES_LIST ->
                    JsonRpcCodec.result( message.id(), resources.templatesListResult() );
            case McpMethods.RESOURCES_READ -> handleResourcesRead( message, session );
            default -> JsonRpcCodec.error( message.id(), McpErrors.METHOD_NOT_FOUND,
                                           "Unknown method: " + method );
        };
    }

    /**
     * Handles the {@code initialize} handshake.
     *
     * @param message the request
     * @param session the session to record the client identity on
     *
     * @return the handshake response
     */
    private JsonObject handleInitialize( JsonRpcMessage message, McpSession session )
    {
        JsonObject params = message.paramsObject();
        JsonObject clientInfo = params.has( "clientInfo" ) && params.get( "clientInfo" ).isJsonObject()
                                ? params.getAsJsonObject( "clientInfo" )
                                : new JsonObject();
        session.initialize( readString( clientInfo, "name" ), readString( clientInfo, "version" ) );

        JsonObject toolsCapability = new JsonObject();
        toolsCapability.addProperty( "listChanged", false );
        JsonObject resourcesCapability = new JsonObject();
        resourcesCapability.addProperty( "subscribe", false );
        resourcesCapability.addProperty( "listChanged", false );

        JsonObject capabilities = new JsonObject();
        capabilities.add( "tools", toolsCapability );
        capabilities.add( "resources", resourcesCapability );

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty( "name", "Mica Minecraft Launcher" );
        serverInfo.addProperty( "version", LauncherConstants.LAUNCHER_APPLICATION_VERSION );

        JsonObject result = new JsonObject();
        result.addProperty( "protocolVersion", PROTOCOL_VERSION );
        result.add( "capabilities", capabilities );
        result.add( "serverInfo", serverInfo );
        return JsonRpcCodec.result( message.id(), result );
    }

    /**
     * Handles {@code tools/call}: resolves the tool, applies the approval gate, and invokes it.
     *
     * @param message the request
     * @param session the calling session
     *
     * @return the response
     */
    private JsonObject handleToolsCall( JsonRpcMessage message, McpSession session )
    {
        JsonObject params = message.paramsObject();
        String name = readString( params, "name" );
        if ( name.isEmpty() ) {
            return JsonRpcCodec.error( message.id(), McpErrors.INVALID_PARAMS, "Missing tool name" );
        }

        McpTool tool = tools.find( name );
        if ( tool == null ) {
            return JsonRpcCodec.error( message.id(), McpErrors.INVALID_PARAMS, "Unknown tool: " + name );
        }

        JsonObject arguments = params.has( "arguments" ) && params.get( "arguments" ).isJsonObject()
                               ? params.getAsJsonObject( "arguments" )
                               : new JsonObject();
        McpCallContext context = session.toCallContext();

        // Content gates run before consent. A request the launcher already refuses must not
        // reach the user as a question -- prompting for something that was never going to run
        // is how a consent dialog becomes noise to click through.
        String rejection;
        try {
            rejection = tool.validateBeforeApproval( arguments );
        }
        catch ( Exception e ) {
            Logger.logError( "MCP pre-approval validation failed for tool " + name + "; refusing" );
            Logger.logThrowable( e );
            rejection = "The request could not be validated.";
        }
        if ( rejection != null ) {
            Logger.logStd( "MCP refused " + name + " before approval: " + rejection );
            activityLog.record( System.currentTimeMillis(), context.clientName(), name,
                                McpActivityLog.Decision.REFUSED, rejection );
            return JsonRpcCodec.error( message.id(), McpErrors.INVALID_PARAMS, rejection );
        }

        boolean allowed;
        try {
            allowed = authorizer.authorize( tool, context, arguments );
        }
        catch ( Exception e ) {
            // Fail closed: an authorizer that blew up has not granted anything.
            Logger.logError( "MCP approval check failed for tool " + name + "; denying" );
            Logger.logThrowable( e );
            allowed = false;
        }
        if ( !allowed ) {
            activityLog.record( System.currentTimeMillis(), context.clientName(), name,
                                McpActivityLog.Decision.DENIED, "not approved" );
            return JsonRpcCodec.error( message.id(), McpErrors.REQUEST_DENIED,
                                       "Tool call was not approved: " + name );
        }

        try {
            McpToolResult result = tool.invoke( context, arguments );
            boolean failed = result == null || result.isError();
            activityLog.record( System.currentTimeMillis(), context.clientName(), name,
                                failed ? McpActivityLog.Decision.FAILED
                                       : McpActivityLog.Decision.ALLOWED,
                                failed && result != null && !result.rawTextBlocks().isEmpty()
                                ? result.rawTextBlocks().get( 0 ) : "" );
            return JsonRpcCodec.result( message.id(),
                                        result == null ? McpToolResult.error( "Tool returned no result" ).toJson()
                                                       : result.toJson() );
        }
        catch ( Exception e ) {
            // The exception's own message is deliberately not returned. It routinely carries
            // absolute paths, and could carry command lines; the log is where the detail
            // belongs, not the wire.
            Logger.logError( "MCP tool " + name + " failed" );
            Logger.logThrowable( e );
            // The exception's message is not recorded either: the activity view is shown in
            // Settings, and exception text carries paths and can carry command lines.
            activityLog.record( System.currentTimeMillis(), context.clientName(), name,
                                McpActivityLog.Decision.FAILED, "the tool threw" );
            return JsonRpcCodec.error( message.id(), McpErrors.INTERNAL_ERROR,
                                       "Tool failed: " + name );
        }
    }

    /**
     * Handles {@code resources/read}.
     * <p>
     * A resource serves the same data as a tool, so it goes through the same gate: the
     * authorizer decides with the governing tool's policy, and the outcome is recorded in the
     * activity log. A tool the user disabled therefore cannot be read around through its
     * resource, and reads show up in Settings alongside calls.
     *
     * @param message the request
     * @param session the calling session
     *
     * @return the response
     */
    private JsonObject handleResourcesRead( JsonRpcMessage message, McpSession session )
    {
        String uri = readString( message.paramsObject(), "uri" );
        if ( uri.isEmpty() ) {
            return JsonRpcCodec.error( message.id(), McpErrors.INVALID_PARAMS, "Missing resource uri" );
        }

        McpResourceRegistry.Match match = resources.resolve( uri );
        if ( match == null ) {
            return JsonRpcCodec.error( message.id(), McpErrors.RESOURCE_NOT_FOUND,
                                       "No such resource: " + uri );
        }

        McpTool gate = gateFor( match.resource() );
        McpCallContext context = session.toCallContext();
        String detail = "resource " + uri;
        JsonObject arguments = new JsonObject();
        match.params().forEach( arguments::addProperty );
        arguments.addProperty( "uri", uri );

        boolean allowed;
        try {
            allowed = authorizer.authorize( gate, context, arguments );
        }
        catch ( Exception e ) {
            // Fail closed, exactly as for a tool call.
            Logger.logError( "MCP approval check failed for resource " + uri + "; denying" );
            Logger.logThrowable( e );
            allowed = false;
        }
        if ( !allowed ) {
            activityLog.record( System.currentTimeMillis(), context.clientName(), gate.name(),
                                McpActivityLog.Decision.DENIED, detail );
            return JsonRpcCodec.error( message.id(), McpErrors.REQUEST_DENIED,
                                       "Resource read was not approved: " + uri );
        }

        String text;
        try {
            text = match.resource().read( match.params() );
        }
        catch ( Exception e ) {
            Logger.logError( "MCP resource read failed: " + uri );
            Logger.logThrowable( e );
            activityLog.record( System.currentTimeMillis(), context.clientName(), gate.name(),
                                McpActivityLog.Decision.FAILED, detail );
            return JsonRpcCodec.error( message.id(), McpErrors.INTERNAL_ERROR,
                                       "Could not read resource: " + uri );
        }
        activityLog.record( System.currentTimeMillis(), context.clientName(), gate.name(),
                            McpActivityLog.Decision.ALLOWED, detail );

        // Resource text goes out through the same redaction path as tool output, so a log or
        // crash report exposed as a resource is subject to the same credential invariant.
        JsonObject contents = new JsonObject();
        contents.addProperty( "uri", uri );
        contents.addProperty( "mimeType", match.resource().mimeType() );
        contents.addProperty( "text", redactedTextOf( text ) );

        JsonArray array = new JsonArray();
        array.add( contents );
        JsonObject result = new JsonObject();
        result.add( "contents", array );
        return JsonRpcCodec.result( message.id(), result );
    }

    /**
     * Returns the tool whose approval policy governs reading a resource: the registered tool
     * the resource names, or a read-only stand-in carrying that name when none is registered.
     *
     * @param resource the resource being read
     *
     * @return the gating tool; never {@code null}
     */
    private McpTool gateFor( McpResource resource )
    {
        String name = resource.governingToolName();
        if ( name == null || name.isBlank() ) {
            name = "read_resource";
        }
        McpTool tool = tools.find( name );
        return tool != null ? tool : new ResourceReadGate( name, resource );
    }

    /**
     * The approval stand-in for a resource with no registered tool equivalent: read-only, and
     * never invoked — it exists only to be shown to the authorizer.
     *
     * @param name     the tool name the policy is looked up under
     * @param resource the resource being read
     */
    private record ResourceReadGate( String name, McpResource resource ) implements McpTool
    {
        @Override
        public String title() { return resource.name(); }

        @Override
        public String description() { return resource.description(); }

        @Override
        public JsonObject inputSchema() { return new JsonObject(); }

        @Override
        public McpRiskClass riskClass() { return McpRiskClass.READ_ONLY; }

        @Override
        public McpToolResult invoke( McpCallContext context, JsonObject arguments )
        {
            throw new UnsupportedOperationException( "A resource read gate is never invoked" );
        }
    }

    /**
     * Runs resource text through the same redaction the tool-result path uses, so both
     * boundaries enforce the credential invariant identically rather than by convention.
     *
     * @param text the raw resource text
     *
     * @return the redacted text
     */
    private static String redactedTextOf( String text )
    {
        return McpToolResult.text( text ).toJson()
                .getAsJsonArray( "content" ).get( 0 ).getAsJsonObject()
                .get( "text" ).getAsString();
    }

    /**
     * Reads a string field, treating absent, null, and non-primitive values as empty.
     *
     * @param object the object to read
     * @param key    the field name
     *
     * @return the value, or {@code ""}
     */
    private static String readString( JsonObject object, String key )
    {
        if ( object == null || !object.has( key ) || !object.get( key ).isJsonPrimitive() ) {
            return "";
        }
        return object.get( key ).getAsString();
    }
}
