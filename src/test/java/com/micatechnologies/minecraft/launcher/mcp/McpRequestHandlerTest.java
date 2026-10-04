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
import com.google.gson.JsonPrimitive;
import com.micatechnologies.minecraft.launcher.mcp.approval.LauncherMcpAuthorizer;
import com.micatechnologies.minecraft.launcher.mcp.approval.McpApprovalPolicy;
import com.micatechnologies.minecraft.launcher.mcp.approval.McpAuthorizer;
import com.micatechnologies.minecraft.launcher.mcp.approval.McpGrantStore;
import com.micatechnologies.minecraft.launcher.mcp.approval.McpRiskClass;
import com.micatechnologies.minecraft.launcher.mcp.protocol.JsonRpcCodec;
import com.micatechnologies.minecraft.launcher.mcp.protocol.JsonRpcMessage;
import com.micatechnologies.minecraft.launcher.mcp.protocol.McpErrors;
import com.micatechnologies.minecraft.launcher.mcp.resources.McpResource;
import com.micatechnologies.minecraft.launcher.mcp.resources.McpResourceRegistry;
import com.micatechnologies.minecraft.launcher.mcp.session.McpSession;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpCallContext;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpTool;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpToolRegistry;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link McpRequestHandler} — the whole MCP method surface.
 *
 * <p>The handler was written free of transport and JavaFX dependencies precisely so this
 * could exist: every dispatch path, including the ones that only occur when something goes
 * wrong, is reachable here without a socket or an FX toolkit.</p>
 *
 * <p>Three behaviours are security properties rather than protocol conformance, and are
 * tested as such. A tool call is refused before the handshake completes, because the client
 * name established by the handshake is half of every session-grant key. A denied call
 * produces an error and never reaches the tool. And when a tool throws, the exception's own
 * message is not returned to the client — exception text routinely carries absolute paths and
 * can carry command lines, so it belongs in the log, not on the wire.</p>
 */
class McpRequestHandlerTest
{
    private static final long NOW = 1_700_000_000_000L;

    private McpToolRegistry tools;
    private McpResourceRegistry resources;
    private McpSession session;

    /** Records whether the tool actually ran, so denial can be distinguished from failure. */
    private boolean toolRan;

    /** Hand-rolled tool whose behaviour each test sets up, per the no-mocking convention. */
    private class StubTool implements McpTool
    {
        private final String name;
        private final RuntimeException failure;
        private final boolean returnsNull;

        StubTool( String name, RuntimeException failure, boolean returnsNull )
        {
            this.name = name;
            this.failure = failure;
            this.returnsNull = returnsNull;
        }

        @Override
        public String name() { return name; }

        @Override
        public String title() { return name; }

        @Override
        public String description() { return name; }

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
            if ( failure != null ) {
                throw failure;
            }
            return returnsNull ? null : McpToolResult.text( "ran " + name + " for " + context.clientName() );
        }
    }

    /** Hand-rolled resource whose read behaviour each test sets up. */
    private record StubResource( String template, String content, RuntimeException failure )
            implements McpResource
    {
        @Override
        public String uriTemplate() { return template; }

        @Override
        public String name() { return "stub"; }

        @Override
        public String description() { return "stub"; }

        @Override
        public String mimeType() { return "text/plain"; }

        @Override
        public String read( Map< String, String > params )
        {
            if ( failure != null ) {
                throw failure;
            }
            return content;
        }

        @Override
        public List< String > concreteUris()
        {
            return template.contains( "{" ) ? List.of() : List.of( template );
        }
    }

    /** A resource that names the tool whose policy governs reading it. */
    private record GovernedResource( String template, String content, String governingToolName )
            implements McpResource
    {
        @Override
        public String uriTemplate() { return template; }

        @Override
        public String name() { return "governed"; }

        @Override
        public String description() { return "governed"; }

        @Override
        public String mimeType() { return "text/plain"; }

        @Override
        public String read( Map< String, String > params ) { return content; }
    }

    @BeforeEach
    void setUp()
    {
        tools = new McpToolRegistry();
        resources = new McpResourceRegistry();
        session = new McpSession( "session-1", NOW );
        toolRan = false;
    }

    // region construction

    @Test
    void everyCollaboratorIsRequired()
    {
        assertThrows( IllegalArgumentException.class,
                      () -> new McpRequestHandler( null, resources, allow() ) );
        assertThrows( IllegalArgumentException.class,
                      () -> new McpRequestHandler( tools, null, allow() ) );
        assertThrows( IllegalArgumentException.class,
                      () -> new McpRequestHandler( tools, resources, null ) );
    }

    // endregion

    // region handshake

    @Test
    void initializeReportsTheProtocolVersionAndCapabilities()
    {
        JsonObject result = resultOf( handler( allow() ).handle(
                request( 1, "initialize", clientInfoParams( "Claude Code", "1.2.3" ) ), session, NOW ) );

        assertEquals( McpRequestHandler.PROTOCOL_VERSION, result.get( "protocolVersion" ).getAsString() );
        assertTrue( result.getAsJsonObject( "capabilities" ).has( "tools" ) );
        assertTrue( result.getAsJsonObject( "capabilities" ).has( "resources" ) );
        assertEquals( "Mica Minecraft Launcher",
                      result.getAsJsonObject( "serverInfo" ).get( "name" ).getAsString() );
    }

    @Test
    void initializeRecordsTheClientIdentityOnTheSession()
    {
        handler( allow() ).handle( request( 1, "initialize", clientInfoParams( "Claude Code", "1.2.3" ) ),
                                   session, NOW );
        assertTrue( session.isInitialized() );
        assertEquals( "Claude Code", session.getClientName() );
        assertEquals( "1.2.3", session.getClientVersion() );
    }

    @Test
    void initializeToleratesAMissingClientInfo()
    {
        assertNotNull( handler( allow() ).handle( request( 1, "initialize", new JsonObject() ), session, NOW ) );
        assertTrue( session.isInitialized() );
        assertEquals( "", session.getClientName() );
    }

    /**
     * The client name established here is half of every session-grant key, so a tool call
     * before the handshake has no identity to attribute consent to.
     */
    @Test
    void aToolCallBeforeTheHandshakeIsRefused()
    {
        tools.register( new StubTool( "list_modpacks", null, false ) );
        JsonObject response = handler( allow() ).handle(
                request( 1, "tools/call", toolCallParams( "list_modpacks" ) ), session, NOW );

        assertEquals( McpErrors.INVALID_REQUEST, errorCodeOf( response ) );
        assertFalse( toolRan, "the tool must not run before the handshake" );
    }

    @Test
    void everyNonPingMethodIsRefusedBeforeTheHandshake()
    {
        for ( String method : new String[]{ "tools/list", "resources/list", "resources/read",
                                            "resources/templates/list" } ) {
            assertEquals( McpErrors.INVALID_REQUEST,
                          errorCodeOf( handler( allow() ).handle( request( 1, method, new JsonObject() ),
                                                                  session, NOW ) ),
                          method );
        }
    }

    /** Liveness must work before the handshake, or a client cannot probe the server at all. */
    @Test
    void pingWorksBeforeTheHandshake()
    {
        JsonObject response = handler( allow() ).handle( request( 1, "ping", null ), session, NOW );
        assertTrue( response.has( "result" ) );
        assertEquals( 0, response.getAsJsonObject( "result" ).size() );
    }

    // endregion

    // region notifications

    @Test
    void aNotificationIsNeverAnswered()
    {
        assertNull( handler( allow() ).handle(
                new JsonRpcMessage( null, "notifications/initialized", null ), session, NOW ) );
    }

    /** An unknown notification is explicitly not an error, and still must not be answered. */
    @Test
    void anUnknownNotificationIsSilentlyIgnored()
    {
        assertNull( handler( allow() ).handle(
                new JsonRpcMessage( null, "notifications/somethingNew", null ), session, NOW ) );
    }

    // endregion

    // region method dispatch

    @Test
    void anUnknownMethodIsReported()
    {
        initialized();
        assertEquals( McpErrors.METHOD_NOT_FOUND,
                      errorCodeOf( handler( allow() ).handle( request( 1, "tools/destroy", null ),
                                                              session, NOW ) ) );
    }

    @Test
    void toolsListReturnsTheRegistryListing()
    {
        initialized();
        tools.register( new StubTool( "list_modpacks", null, false ) );
        JsonObject result = resultOf( handler( allow() ).handle( request( 1, "tools/list", null ), session, NOW ) );
        assertEquals( 1, result.getAsJsonArray( "tools" ).size() );
    }

    @Test
    void aNullMessageIsAnInternalErrorRatherThanACrash()
    {
        assertEquals( McpErrors.INTERNAL_ERROR,
                      errorCodeOf( handler( allow() ).handle( null, session, NOW ) ) );
        assertEquals( McpErrors.INTERNAL_ERROR,
                      errorCodeOf( handler( allow() ).handle( request( 1, "ping", null ), null, NOW ) ) );
    }

    // endregion

    // region tools/call

    @Test
    void anApprovedToolCallRunsAndReturnsItsContent()
    {
        initializedAs( "Claude Code" );
        tools.register( new StubTool( "list_modpacks", null, false ) );

        JsonObject result = resultOf( handler( allow() ).handle(
                request( 1, "tools/call", toolCallParams( "list_modpacks" ) ), session, NOW ) );

        assertTrue( toolRan );
        assertFalse( result.get( "isError" ).getAsBoolean() );
        assertEquals( "ran list_modpacks for Claude Code",
                      result.getAsJsonArray( "content" ).get( 0 ).getAsJsonObject()
                              .get( "text" ).getAsString() );
    }

    @Test
    void aMissingToolNameIsReported()
    {
        initialized();
        assertEquals( McpErrors.INVALID_PARAMS,
                      errorCodeOf( handler( allow() ).handle(
                              request( 1, "tools/call", new JsonObject() ), session, NOW ) ) );
    }

    @Test
    void anUnknownToolIsReported()
    {
        initialized();
        assertEquals( McpErrors.INVALID_PARAMS,
                      errorCodeOf( handler( allow() ).handle(
                              request( 1, "tools/call", toolCallParams( "no_such_tool" ) ), session, NOW ) ) );
    }

    /** A denied call must not reach the tool at all — not merely discard its result. */
    @Test
    void aDeniedCallNeverReachesTheTool()
    {
        initialized();
        tools.register( new StubTool( "list_modpacks", null, false ) );

        JsonObject response = handler( ( t, c, a ) -> false ).handle(
                request( 1, "tools/call", toolCallParams( "list_modpacks" ) ), session, NOW );

        assertEquals( McpErrors.REQUEST_DENIED, errorCodeOf( response ) );
        assertFalse( toolRan, "a denied tool must not run" );
    }

    /** An authorizer that throws has not granted anything, so the call fails closed. */
    @Test
    void anAuthorizerThatThrowsDeniesTheCall()
    {
        initialized();
        tools.register( new StubTool( "list_modpacks", null, false ) );

        JsonObject response = handler( ( t, c, a ) -> {
            throw new IllegalStateException( "approval subsystem is down" );
        } ).handle( request( 1, "tools/call", toolCallParams( "list_modpacks" ) ), session, NOW );

        assertEquals( McpErrors.REQUEST_DENIED, errorCodeOf( response ) );
        assertFalse( toolRan );
    }

    /**
     * Content gates run ahead of consent, and a refused call must not reach either the
     * authorizer or the tool.
     *
     * <p>The ordering is what matters. Prompting the user to approve something the launcher
     * already knows it will refuse teaches them that these dialogs are noise to click
     * through — which is exactly the habit that makes the consent model worthless when a real
     * request arrives.</p>
     */
    @Test
    void aContentGateRefusalHappensBeforeTheApprovalCheck()
    {
        initialized();
        tools.register( new StubTool( "list_modpacks", null, false ) {
            @Override
            public String validateBeforeApproval( JsonObject arguments )
            {
                return "That request is not allowed.";
            }
        } );

        boolean[] askedForApproval = { false };
        JsonObject response = handler( ( t, c, a ) -> {
            askedForApproval[ 0 ] = true;
            return true;
        } ).handle( request( 1, "tools/call", toolCallParams( "list_modpacks" ) ), session, NOW );

        assertEquals( McpErrors.INVALID_PARAMS, errorCodeOf( response ) );
        assertFalse( askedForApproval[ 0 ], "the user must not be asked about a refused call" );
        assertFalse( toolRan, "a refused call must not run" );
    }

    /** A content gate that throws refuses, rather than falling through to the tool. */
    @Test
    void aContentGateThatThrowsRefusesTheCall()
    {
        initialized();
        tools.register( new StubTool( "list_modpacks", null, false ) {
            @Override
            public String validateBeforeApproval( JsonObject arguments )
            {
                throw new IllegalStateException( "validation blew up" );
            }
        } );

        JsonObject response = handler( allow() ).handle(
                request( 1, "tools/call", toolCallParams( "list_modpacks" ) ), session, NOW );

        assertEquals( McpErrors.INVALID_PARAMS, errorCodeOf( response ) );
        assertFalse( toolRan );
    }

    /** With no gate declared, the default lets the call through to the approval stage. */
    @Test
    void aToolWithNoContentGateProceedsNormally()
    {
        initialized();
        tools.register( new StubTool( "list_modpacks", null, false ) );
        assertFalse( handler( allow() ).handle(
                request( 1, "tools/call", toolCallParams( "list_modpacks" ) ), session, NOW )
                             .has( "error" ) );
        assertTrue( toolRan );
    }

    /**
     * The leak this guards against: exception messages routinely carry absolute paths and can
     * carry command lines. The detail belongs in the launcher log, not in a response to a
     * model.
     */
    @Test
    void aThrowingToolDoesNotLeakItsExceptionMessage()
    {
        initialized();
        tools.register( new StubTool( "list_modpacks",
                                      new IllegalStateException(
                                              "/Users/someone/secret/path failed with --accessToken abc123" ),
                                      false ) );

        JsonObject response = handler( allow() ).handle(
                request( 1, "tools/call", toolCallParams( "list_modpacks" ) ), session, NOW );

        assertEquals( McpErrors.INTERNAL_ERROR, errorCodeOf( response ) );
        String wire = response.toString();
        assertFalse( wire.contains( "/Users/someone/secret/path" ), wire );
        assertFalse( wire.contains( "abc123" ), wire );
    }

    @Test
    void aToolReturningNoResultIsReportedAsAToolError()
    {
        initialized();
        tools.register( new StubTool( "list_modpacks", null, true ) );

        JsonObject result = resultOf( handler( allow() ).handle(
                request( 1, "tools/call", toolCallParams( "list_modpacks" ) ), session, NOW ) );
        assertTrue( result.get( "isError" ).getAsBoolean() );
    }

    @Test
    void toolCallsAreCountedOnTheSession()
    {
        initialized();
        tools.register( new StubTool( "list_modpacks", null, false ) );
        McpRequestHandler handler = handler( allow() );

        handler.handle( request( 1, "tools/list", null ), session, NOW + 10 );
        assertEquals( 0, session.getCallCount() );

        handler.handle( request( 2, "tools/call", toolCallParams( "list_modpacks" ) ), session, NOW + 20 );
        assertEquals( 1, session.getCallCount() );
        assertEquals( NOW + 20, session.getLastActivityMs() );
    }

    // endregion

    // region activity recording

    /**
     * Every outcome reaches the activity log, which is what makes an approval reviewable after
     * the moment it was given. A denial that left no trace would be the least useful kind:
     * the user would have no way to see that something was attempted.
     */
    @Test
    void everyOutcomeIsRecorded()
    {
        initializedAs( "Claude Code" );
        tools.register( new StubTool( "ok_tool", null, false ) );
        tools.register( new StubTool( "boom_tool", new IllegalStateException( "boom" ), false ) );
        tools.register( new StubTool( "gated_tool", null, false ) {
            @Override
            public String validateBeforeApproval( JsonObject arguments )
            {
                return "refused by a content gate";
            }
        } );

        McpActivityLog log = new McpActivityLog();
        McpRequestHandler allowing = new McpRequestHandler( tools, resources, allow(), log );
        allowing.handle( request( 1, "tools/call", toolCallParams( "ok_tool" ) ), session, NOW );
        allowing.handle( request( 2, "tools/call", toolCallParams( "boom_tool" ) ), session, NOW );
        allowing.handle( request( 3, "tools/call", toolCallParams( "gated_tool" ) ), session, NOW );
        new McpRequestHandler( tools, resources, ( t, c, a ) -> false, log )
                .handle( request( 4, "tools/call", toolCallParams( "ok_tool" ) ), session, NOW );

        List< McpActivityLog.Entry > entries = log.recent();
        assertEquals( 4, entries.size() );
        assertEquals( McpActivityLog.Decision.DENIED, entries.get( 0 ).decision() );
        assertEquals( McpActivityLog.Decision.REFUSED, entries.get( 1 ).decision() );
        assertEquals( McpActivityLog.Decision.FAILED, entries.get( 2 ).decision() );
        assertEquals( McpActivityLog.Decision.ALLOWED, entries.get( 3 ).decision() );
        assertEquals( "Claude Code", entries.get( 0 ).clientName() );
    }

    /**
     * A throwing tool's exception message is kept out of the log for the same reason it is
     * kept off the wire: the activity view is rendered in Settings, and exception text carries
     * absolute paths and can carry command lines.
     */
    @Test
    void aThrowingToolsExceptionMessageIsNotRecorded()
    {
        initialized();
        tools.register( new StubTool( "boom_tool",
                                      new IllegalStateException( "/Users/someone/secret failed" ),
                                      false ) );

        McpActivityLog log = new McpActivityLog();
        new McpRequestHandler( tools, resources, allow(), log )
                .handle( request( 1, "tools/call", toolCallParams( "boom_tool" ) ), session, NOW );

        assertFalse( log.recent().get( 0 ).detail().contains( "/Users/someone/secret" ),
                     log.recent().get( 0 ).detail() );
    }

    @Test
    void anActivityLogIsRequired()
    {
        assertThrows( IllegalArgumentException.class,
                      () -> new McpRequestHandler( tools, resources, allow(), null ) );
    }

    // endregion

    // region resources

    @Test
    void aResourceReadReturnsItsContent()
    {
        initialized();
        resources.register( new StubResource( "mica://packs", "the pack index", null ) );

        JsonObject result = resultOf( handler( allow() ).handle(
                request( 1, "resources/read", uriParams( "mica://packs" ) ), session, NOW ) );

        JsonObject contents = result.getAsJsonArray( "contents" ).get( 0 ).getAsJsonObject();
        assertEquals( "mica://packs", contents.get( "uri" ).getAsString() );
        assertEquals( "text/plain", contents.get( "mimeType" ).getAsString() );
        assertEquals( "the pack index", contents.get( "text" ).getAsString() );
    }

    /**
     * Resources go out through the same redaction as tool results. A crash report or game log
     * exposed as a resource is exactly the surface the credential invariant exists for.
     */
    @Test
    void resourceTextIsRedactedLikeToolOutput()
    {
        initialized();
        resources.register( new StubResource( "mica://launcher/log",
                                              "launching with --accessToken leakedvalue123", null ) );

        JsonObject response = handler( allow() ).handle(
                request( 1, "resources/read", uriParams( "mica://launcher/log" ) ), session, NOW );
        assertFalse( response.toString().contains( "leakedvalue123" ), response.toString() );
    }

    @Test
    void aMissingUriIsReported()
    {
        initialized();
        assertEquals( McpErrors.INVALID_PARAMS,
                      errorCodeOf( handler( allow() ).handle(
                              request( 1, "resources/read", new JsonObject() ), session, NOW ) ) );
    }

    @Test
    void anUnknownUriIsReportedAsResourceNotFound()
    {
        initialized();
        assertEquals( McpErrors.RESOURCE_NOT_FOUND,
                      errorCodeOf( handler( allow() ).handle(
                              request( 1, "resources/read", uriParams( "mica://nope" ) ), session, NOW ) ) );
    }

    /** A traversal attempt must look like a missing resource, not reach the implementation. */
    @Test
    void aTraversalUriIsReportedAsResourceNotFound()
    {
        initialized();
        resources.register( new StubResource( "mica://modpack/{friendlyName}/manifest", "content", null ) );
        assertEquals( McpErrors.RESOURCE_NOT_FOUND,
                      errorCodeOf( handler( allow() ).handle(
                              request( 1, "resources/read", uriParams( "mica://modpack/%2e%2e/manifest" ) ),
                              session, NOW ) ) );
    }

    @Test
    void aFailingResourceReadDoesNotLeakItsExceptionMessage()
    {
        initialized();
        resources.register( new StubResource( "mica://packs", null,
                                              new IllegalStateException( "/Users/someone/secret failed" ) ) );

        JsonObject response = handler( allow() ).handle(
                request( 1, "resources/read", uriParams( "mica://packs" ) ), session, NOW );

        assertEquals( McpErrors.INTERNAL_ERROR, errorCodeOf( response ) );
        assertFalse( response.toString().contains( "/Users/someone/secret" ) );
    }

    /**
     * A resource serves the same data as its tool, so the authorizer sees that tool — with the
     * resource's parameters as arguments — and a refusal never reaches the resource.
     */
    @Test
    void aResourceReadIsAuthorizedAsItsGoverningTool()
    {
        initializedAs( "Claude Code" );
        tools.register( new StubTool( "get_crash_report", null, false ) );
        resources.register( new GovernedResource( "mica://modpack/{friendlyName}/crash-report",
                                                  "crash text", "get_crash_report" ) );
        McpActivityLog log = new McpActivityLog();
        String[] seenTool = new String[ 1 ];
        JsonObject[] seenArguments = new JsonObject[ 1 ];

        JsonObject response = new McpRequestHandler( tools, resources, ( t, c, a ) -> {
            seenTool[ 0 ] = t.name();
            seenArguments[ 0 ] = a;
            return false;
        }, log ).handle( request( 1, "resources/read",
                                  uriParams( "mica://modpack/Pack/crash-report" ) ), session, NOW );

        assertEquals( McpErrors.REQUEST_DENIED, errorCodeOf( response ) );
        assertFalse( response.toString().contains( "crash text" ) );
        assertEquals( "get_crash_report", seenTool[ 0 ] );
        assertEquals( "Pack", seenArguments[ 0 ].get( "friendlyName" ).getAsString() );
        assertFalse( toolRan, "the governing tool is consulted, never invoked" );

        McpActivityLog.Entry entry = log.recent().get( 0 );
        assertEquals( McpActivityLog.Decision.DENIED, entry.decision() );
        assertEquals( "get_crash_report", entry.toolName() );
        assertEquals( "Claude Code", entry.clientName() );
    }

    @Test
    void anApprovedResourceReadIsRecorded()
    {
        initialized();
        resources.register( new StubResource( "mica://packs", "the pack index", null ) );
        McpActivityLog log = new McpActivityLog();

        resultOf( new McpRequestHandler( tools, resources, allow(), log ).handle(
                request( 1, "resources/read", uriParams( "mica://packs" ) ), session, NOW ) );

        assertEquals( 1, log.size() );
        assertEquals( McpActivityLog.Decision.ALLOWED, log.recent().get( 0 ).decision() );
    }

    /**
     * With no registered tool of that name, the read is gated as a read-only stand-in carrying
     * the name, so a policy stored under it still applies.
     */
    @Test
    void aResourceWithNoRegisteredToolIsGatedAsReadOnly()
    {
        initialized();
        resources.register( new GovernedResource( "mica://packs", "index", "list_modpacks" ) );
        McpTool[] seen = new McpTool[ 1 ];

        resultOf( handler( ( t, c, a ) -> {
            seen[ 0 ] = t;
            return true;
        } ).handle( request( 1, "resources/read", uriParams( "mica://packs" ) ), session, NOW ) );

        assertEquals( "list_modpacks", seen[ 0 ].name() );
        assertEquals( McpRiskClass.READ_ONLY, seen[ 0 ].riskClass() );
    }

    /**
     * The property the gate exists for, through the production authorizer: a tool the user
     * disabled cannot be read around through its resource, auto-approve notwithstanding.
     */
    @Test
    void aDisabledToolCannotBeReadThroughItsResource()
    {
        initialized();
        tools.register( new StubTool( "get_modpack_manifest", null, false ) );
        resources.register( new GovernedResource( "mica://modpack/{friendlyName}/manifest",
                                                  "MANIFEST-BODY", "get_modpack_manifest" ) );
        LauncherMcpAuthorizer authorizer = new LauncherMcpAuthorizer(
                new LauncherMcpAuthorizer.Settings()
                {
                    @Override
                    public boolean serverEnabled() { return true; }

                    @Override
                    public boolean autoApproveReadOnly() { return true; }

                    @Override
                    public McpApprovalPolicy policyFor( String toolName )
                    {
                        return "get_modpack_manifest".equals( toolName ) ? McpApprovalPolicy.DISABLED
                                                                         : null;
                    }
                },
                new McpGrantStore(),
                new LauncherMcpAuthorizer.ConsentPrompt()
                {
                    @Override
                    public boolean isAvailable() { return false; }

                    @Override
                    public LauncherMcpAuthorizer.Answer ask( McpTool tool, McpCallContext context,
                                                             JsonObject arguments )
                    {
                        return LauncherMcpAuthorizer.Answer.DENY;
                    }
                },
                () -> NOW );

        JsonObject response = handler( authorizer ).handle(
                request( 1, "resources/read", uriParams( "mica://modpack/Pack/manifest" ) ), session, NOW );

        assertEquals( McpErrors.REQUEST_DENIED, errorCodeOf( response ) );
        assertFalse( response.toString().contains( "MANIFEST-BODY" ) );
    }

    @Test
    void resourceListingsAreServed()
    {
        initialized();
        resources.register( new StubResource( "mica://packs", "x", null ) );
        resources.register( new StubResource( "mica://modpack/{friendlyName}/manifest", "y", null ) );
        McpRequestHandler handler = handler( allow() );

        assertEquals( 1, resultOf( handler.handle( request( 1, "resources/list", null ), session, NOW ) )
                .getAsJsonArray( "resources" ).size() );
        assertEquals( 1, resultOf( handler.handle( request( 2, "resources/templates/list", null ), session, NOW ) )
                .getAsJsonArray( "resourceTemplates" ).size() );
    }

    // endregion

    // region helpers

    private McpRequestHandler handler( McpAuthorizer auth )
    {
        return new McpRequestHandler( tools, resources, auth );
    }

    private static McpAuthorizer allow()
    {
        return ( tool, context, arguments ) -> true;
    }

    private void initialized()
    {
        initializedAs( "Test Client" );
    }

    private void initializedAs( String clientName )
    {
        session.initialize( clientName, "1.0" );
    }

    private static JsonRpcMessage request( int id, String method, JsonObject params )
    {
        return new JsonRpcMessage( new JsonPrimitive( id ), method, params );
    }

    private static JsonObject clientInfoParams( String name, String version )
    {
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty( "name", name );
        clientInfo.addProperty( "version", version );
        JsonObject params = new JsonObject();
        params.add( "clientInfo", clientInfo );
        return params;
    }

    private static JsonObject toolCallParams( String name )
    {
        JsonObject params = new JsonObject();
        params.addProperty( "name", name );
        params.add( "arguments", new JsonObject() );
        return params;
    }

    private static JsonObject uriParams( String uri )
    {
        JsonObject params = new JsonObject();
        params.addProperty( "uri", uri );
        return params;
    }

    private static JsonObject resultOf( JsonObject response )
    {
        assertNotNull( response );
        assertFalse( response.has( "error" ), "expected a result but got: " + response );
        assertEquals( JsonRpcCodec.JSONRPC_VERSION, response.get( "jsonrpc" ).getAsString() );
        return response.getAsJsonObject( "result" );
    }

    private static int errorCodeOf( JsonObject response )
    {
        assertNotNull( response );
        assertTrue( response.has( "error" ), "expected an error but got: " + response );
        return response.getAsJsonObject( "error" ).get( "code" ).getAsInt();
    }

    // endregion
}
