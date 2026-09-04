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

package com.micatechnologies.minecraft.launcher.mcp.approval;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpCallContext;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpTool;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LauncherMcpAuthorizer} — the production approval gate.
 *
 * <p>{@link McpApprovalEngine} decides the policy; this class is what happens around that
 * decision, and its failure modes are the dangerous ones. A settings read that throws, a
 * prompt that throws, and a prompt with no GUI behind it must all deny — because the
 * alternative, in each case, is running a tool the user never approved.</p>
 *
 * <p>The other property worth pinning is grant recording: "allow for this session" must
 * actually persist for the session and expire on schedule, while "allow once" must not leak
 * into the next call. Getting that backwards would either nag the user into clicking through
 * prompts or silently grant standing permission they only meant to give once.</p>
 */
class LauncherMcpAuthorizerTest
{
    private static final long NOW = 1_700_000_000_000L;
    private static final McpCallContext CALLER = new McpCallContext( "Claude Code", "session-1" );

    private StubSettings settings;
    private StubPrompt prompt;
    private McpGrantStore grants;
    private long now;

    /** Hand-rolled settings, per this repo's no-mocking-framework convention. */
    private static final class StubSettings implements LauncherMcpAuthorizer.Settings
    {
        boolean enabled = true;
        boolean autoApprove = true;
        McpApprovalPolicy policy;
        RuntimeException failure;

        @Override
        public boolean serverEnabled()
        {
            if ( failure != null ) {
                throw failure;
            }
            return enabled;
        }

        @Override
        public boolean autoApproveReadOnly() { return autoApprove; }

        @Override
        public McpApprovalPolicy policyFor( String toolName ) { return policy; }
    }

    /** Hand-rolled consent prompt. */
    private static final class StubPrompt implements LauncherMcpAuthorizer.ConsentPrompt
    {
        boolean available = true;
        LauncherMcpAuthorizer.Answer answer = LauncherMcpAuthorizer.Answer.DENY;
        RuntimeException failure;
        int askCount;

        @Override
        public boolean isAvailable() { return available; }

        @Override
        public LauncherMcpAuthorizer.Answer ask( McpTool tool, McpCallContext context,
                                                 JsonObject arguments )
        {
            askCount++;
            if ( failure != null ) {
                throw failure;
            }
            return answer;
        }
    }

    /** Minimal tool carrying a settable risk class. */
    private record StubTool( String name, McpRiskClass riskClass ) implements McpTool
    {
        @Override
        public String title() { return name; }

        @Override
        public String description() { return name; }

        @Override
        public JsonObject inputSchema() { return new JsonObject(); }

        @Override
        public McpToolResult invoke( McpCallContext context, JsonObject arguments )
        {
            return McpToolResult.text( "ran" );
        }
    }

    @BeforeEach
    void setUp()
    {
        settings = new StubSettings();
        prompt = new StubPrompt();
        grants = new McpGrantStore();
        now = NOW;
    }

    // region construction

    @Test
    void everyCollaboratorIsRequired()
    {
        assertThrows( IllegalArgumentException.class,
                      () -> new LauncherMcpAuthorizer( null, grants, prompt, () -> NOW ) );
        assertThrows( IllegalArgumentException.class,
                      () -> new LauncherMcpAuthorizer( settings, null, prompt, () -> NOW ) );
        assertThrows( IllegalArgumentException.class,
                      () -> new LauncherMcpAuthorizer( settings, grants, null, () -> NOW ) );
        assertThrows( IllegalArgumentException.class,
                      () -> new LauncherMcpAuthorizer( settings, grants, prompt, null ) );
    }

    @Test
    void aMissingToolOrContextIsDenied()
    {
        assertFalse( authorizer().authorize( null, CALLER, new JsonObject() ) );
        assertFalse( authorizer().authorize( readOnlyTool(), null, new JsonObject() ) );
    }

    // endregion

    // region the ordinary paths

    @Test
    void aReadOnlyToolRunsWithoutPrompting()
    {
        assertTrue( authorize( readOnlyTool() ) );
        assertEquals( 0, prompt.askCount, "read-only auto-approval must not prompt" );
    }

    @Test
    void nothingRunsWhileTheServerIsDisabled()
    {
        settings.enabled = false;
        assertFalse( authorize( readOnlyTool() ) );
        assertEquals( 0, prompt.askCount );
    }

    @Test
    void aMutatingToolPromptsAndRunsWhenAllowed()
    {
        prompt.answer = LauncherMcpAuthorizer.Answer.ALLOW_ONCE;
        assertTrue( authorize( mutatingTool() ) );
        assertEquals( 1, prompt.askCount );
    }

    @Test
    void aMutatingToolIsRefusedWhenTheUserDeclines()
    {
        prompt.answer = LauncherMcpAuthorizer.Answer.DENY;
        assertFalse( authorize( mutatingTool() ) );
    }

    @Test
    void anExplicitlyDisabledToolIsRefusedWithoutPrompting()
    {
        settings.policy = McpApprovalPolicy.DISABLED;
        assertFalse( authorize( readOnlyTool() ) );
        assertEquals( 0, prompt.askCount );
    }

    // endregion

    // region fail-closed

    /**
     * The one that matters most. A launcher with no GUI — server mode, or the window not up
     * yet — cannot ask, and must therefore refuse rather than assume.
     */
    @Test
    void noGuiToAskMeansDeny()
    {
        prompt.available = false;
        assertFalse( authorize( mutatingTool() ) );
        assertEquals( 0, prompt.askCount, "it must not even try to ask" );
    }

    @Test
    void aSettingsReadThatThrowsDenies()
    {
        settings.failure = new IllegalStateException( "config unavailable" );
        assertFalse( authorize( readOnlyTool() ) );
    }

    @Test
    void aPromptThatThrowsDenies()
    {
        prompt.failure = new IllegalStateException( "toolkit gone" );
        assertFalse( authorize( mutatingTool() ) );
    }

    /** A prompt implementation returning nothing has not obtained consent. */
    @Test
    void aPromptReturningNoAnswerDenies()
    {
        prompt.answer = null;
        assertFalse( authorize( mutatingTool() ) );
    }

    // endregion

    // region grants

    @Test
    void allowOnceDoesNotCarryToTheNextCall()
    {
        prompt.answer = LauncherMcpAuthorizer.Answer.ALLOW_ONCE;
        assertTrue( authorize( mutatingTool() ) );
        assertTrue( authorize( mutatingTool() ) );
        assertEquals( 2, prompt.askCount, "allow-once must ask again" );
        assertEquals( 0, grants.size() );
    }

    @Test
    void allowForSessionSuppressesTheNextPrompt()
    {
        prompt.answer = LauncherMcpAuthorizer.Answer.ALLOW_FOR_SESSION;
        assertTrue( authorize( mutatingTool() ) );
        assertTrue( authorize( mutatingTool() ) );
        assertEquals( 1, prompt.askCount, "the grant must cover the second call" );
    }

    @Test
    void aSessionGrantLapsesAfterItsTtl()
    {
        prompt.answer = LauncherMcpAuthorizer.Answer.ALLOW_FOR_SESSION;
        assertTrue( authorize( mutatingTool() ) );

        now = NOW + McpApprovalEngine.DEFAULT_GRANT_TTL_MS + 1;
        prompt.answer = LauncherMcpAuthorizer.Answer.DENY;
        assertFalse( authorize( mutatingTool() ) );
        assertEquals( 2, prompt.askCount, "an expired grant must prompt again" );
    }

    /** A grant covers one tool, not every tool the client might call. */
    @Test
    void aGrantDoesNotSpreadToOtherTools()
    {
        prompt.answer = LauncherMcpAuthorizer.Answer.ALLOW_FOR_SESSION;
        assertTrue( authorize( new StubTool( "install_modpack", McpRiskClass.MUTATING ) ) );

        prompt.answer = LauncherMcpAuthorizer.Answer.DENY;
        assertFalse( authorize( new StubTool( "uninstall_modpack", McpRiskClass.DESTRUCTIVE ) ) );
    }

    /** Nor to another client, since the client name is half the grant key. */
    @Test
    void aGrantDoesNotSpreadToOtherClients()
    {
        prompt.answer = LauncherMcpAuthorizer.Answer.ALLOW_FOR_SESSION;
        assertTrue( authorizer().authorize( mutatingTool(), CALLER, new JsonObject() ) );

        prompt.answer = LauncherMcpAuthorizer.Answer.DENY;
        assertFalse( authorizer().authorize( mutatingTool(),
                                             new McpCallContext( "Some Other Client", "session-2" ),
                                             new JsonObject() ) );
    }

    /**
     * Turning a tool off overrules consent given earlier in the session — otherwise revoking
     * would not take effect until the grant aged out, up to eight hours later.
     */
    @Test
    void disablingTheToolRevokesAnExistingSessionGrant()
    {
        prompt.answer = LauncherMcpAuthorizer.Answer.ALLOW_FOR_SESSION;
        assertTrue( authorize( mutatingTool() ) );

        settings.policy = McpApprovalPolicy.DISABLED;
        assertFalse( authorize( mutatingTool() ) );
    }

    // endregion

    private LauncherMcpAuthorizer authorizer()
    {
        return new LauncherMcpAuthorizer( settings, grants, prompt, () -> now );
    }

    private boolean authorize( McpTool tool )
    {
        return authorizer().authorize( tool, CALLER, new JsonObject() );
    }

    private static McpTool readOnlyTool()
    {
        return new StubTool( "list_modpacks", McpRiskClass.READ_ONLY );
    }

    private static McpTool mutatingTool()
    {
        return new StubTool( "install_modpack", McpRiskClass.MUTATING );
    }
}
