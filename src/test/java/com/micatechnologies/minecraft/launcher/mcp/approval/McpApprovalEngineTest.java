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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link McpApprovalEngine} — the gate between a model's chosen tool call and code
 * that launches games under the user's live Microsoft session, installs from model-chosen
 * URLs, and deletes modpacks.
 *
 * <p>Why this matters: an approval bug here is not a wrong answer on screen, it is an
 * unattended destructive action. The engine was written as a pure function specifically so
 * the whole decision table could be enumerated in tests rather than reasoned about.</p>
 *
 * <p>The properties worth the most are the fail-closed ones, and they are asserted
 * deliberately rather than incidentally: consent required with no GUI to ask must deny and
 * never self-approve; a tool the user set to <b>disabled</b> must stay denied even when an
 * earlier session grant would otherwise cover it; and no combination of inputs may allow a
 * destructive or executing tool that the user has not explicitly permitted.</p>
 */
class McpApprovalEngineTest
{
    /** Arbitrary fixed "now" so no test reads the real clock. */
    private static final long NOW = 1_700_000_000_000L;

    /** A grant that is still live at {@link #NOW}. */
    private static final long LIVE_GRANT = NOW + 60_000L;

    /** A grant that lapsed before {@link #NOW}. */
    private static final long DEAD_GRANT = NOW - 1L;

    /** No grant recorded at all. */
    private static final long NO_GRANT = 0L;

    // region master switch

    @Test
    void nothingRunsWhileTheMasterSwitchIsOff()
    {
        for ( McpRiskClass risk : McpRiskClass.values() ) {
            for ( McpApprovalPolicy policy : McpApprovalPolicy.values() ) {
                assertEquals( McpApprovalDecision.DENY,
                              McpApprovalEngine.resolve( new McpApprovalEngine.Request(
                                      risk, policy, false, true, true, LIVE_GRANT, NOW ) ),
                              "disabled server allowed " + risk + " / " + policy );
            }
        }
    }

    // endregion

    // region explicit per-tool policy

    @Test
    void anExplicitAlwaysAllowRunsWithoutPrompting()
    {
        assertEquals( McpApprovalDecision.ALLOW, resolve( McpRiskClass.EXECUTE,
                                                          McpApprovalPolicy.ALWAYS_ALLOW,
                                                          true, true, NO_GRANT ) );
    }

    @Test
    void anExplicitDisabledIsRefused()
    {
        assertEquals( McpApprovalDecision.DENY, resolve( McpRiskClass.READ_ONLY,
                                                         McpApprovalPolicy.DISABLED,
                                                         true, true, NO_GRANT ) );
    }

    /**
     * The important precedence rule: turning a tool off overrules consent the user gave
     * earlier in the session. Without this, revoking a tool would not take effect until the
     * grant aged out — up to eight hours later.
     */
    @Test
    void aSessionGrantCannotOverrideAnExplicitDisabled()
    {
        assertEquals( McpApprovalDecision.DENY, resolve( McpRiskClass.DESTRUCTIVE,
                                                         McpApprovalPolicy.DISABLED,
                                                         true, true, LIVE_GRANT ) );
    }

    @Test
    void anExplicitAskStillPromptsForAReadOnlyToolEvenWithAutoApproveOn()
    {
        assertEquals( McpApprovalDecision.PROMPT, resolve( McpRiskClass.READ_ONLY,
                                                           McpApprovalPolicy.ASK,
                                                           true, true, NO_GRANT ) );
    }

    /**
     * Explicit policy beats grants in both directions: "Always ask" means every call, so an
     * "Allow for this session" given earlier must not silence it.
     */
    @Test
    void aSessionGrantCannotOverrideAnExplicitAsk()
    {
        for ( McpRiskClass risk : McpRiskClass.values() ) {
            assertEquals( McpApprovalDecision.PROMPT,
                          resolve( risk, McpApprovalPolicy.ASK, true, true, LIVE_GRANT ),
                          "a grant silenced always-ask for " + risk );
            assertEquals( McpApprovalDecision.DENY,
                          resolve( risk, McpApprovalPolicy.ASK, true, false, LIVE_GRANT ),
                          "a grant allowed always-ask headlessly for " + risk );
        }
    }

    /**
     * The auto-approve toggle gates the risk-class <em>default</em>. A per-tool
     * {@code ALWAYS_ALLOW} the user chose deliberately is more specific and survives the
     * toggle being switched off.
     */
    @Test
    void turningOffAutoApproveDoesNotUndoAnExplicitAlwaysAllow()
    {
        assertEquals( McpApprovalDecision.ALLOW, resolve( McpRiskClass.READ_ONLY,
                                                          McpApprovalPolicy.ALWAYS_ALLOW,
                                                          false, true, NO_GRANT ) );
    }

    // endregion

    // region risk-class defaults

    @Test
    void readOnlyToolsRunFreelyByDefault()
    {
        assertEquals( McpApprovalDecision.ALLOW, resolve( McpRiskClass.READ_ONLY, null,
                                                          true, true, NO_GRANT ) );
    }

    /** The cautious-user path: one toggle forces prompts on everything. */
    @Test
    void turningOffAutoApproveMakesReadOnlyToolsPrompt()
    {
        assertEquals( McpApprovalDecision.PROMPT, resolve( McpRiskClass.READ_ONLY, null,
                                                           false, true, NO_GRANT ) );
    }

    @Test
    void everyOtherRiskClassPromptsByDefault()
    {
        for ( McpRiskClass risk : McpRiskClass.values() ) {
            if ( risk == McpRiskClass.READ_ONLY ) {
                continue;
            }
            assertEquals( McpApprovalDecision.PROMPT, resolve( risk, null, true, true, NO_GRANT ),
                          risk + " should prompt by default" );
        }
    }

    /** The auto-approve toggle must not reach past read-only tools. */
    @Test
    void autoApproveNeverAppliesToMutatingOrWorseTools()
    {
        for ( McpRiskClass risk : McpRiskClass.values() ) {
            if ( risk == McpRiskClass.READ_ONLY ) {
                continue;
            }
            assertEquals( McpApprovalDecision.PROMPT, resolve( risk, null, true, true, NO_GRANT ),
                          risk + " must not be auto-approved" );
        }
    }

    @Test
    void theDeclaredDefaultsMatchWhatTheEngineDoes()
    {
        assertEquals( McpApprovalPolicy.ALWAYS_ALLOW, McpRiskClass.READ_ONLY.getDefaultPolicy() );
        assertEquals( McpApprovalPolicy.ASK, McpRiskClass.MUTATING.getDefaultPolicy() );
        assertEquals( McpApprovalPolicy.ASK, McpRiskClass.DESTRUCTIVE.getDefaultPolicy() );
        assertEquals( McpApprovalPolicy.ASK, McpRiskClass.EXECUTE.getDefaultPolicy() );
    }

    @Test
    void onlyReadOnlyIsConsideredNonMutating()
    {
        assertFalse( McpRiskClass.READ_ONLY.mutatesState() );
        assertTrue( McpRiskClass.MUTATING.mutatesState() );
        assertTrue( McpRiskClass.DESTRUCTIVE.mutatesState() );
        assertTrue( McpRiskClass.EXECUTE.mutatesState() );
    }

    // endregion

    // region session grants

    @Test
    void aLiveGrantAllowsAToolThatWouldOtherwisePrompt()
    {
        assertEquals( McpApprovalDecision.ALLOW, resolve( McpRiskClass.MUTATING, null,
                                                          true, true, LIVE_GRANT ) );
    }

    @Test
    void anExpiredGrantPromptsAgain()
    {
        assertEquals( McpApprovalDecision.PROMPT, resolve( McpRiskClass.MUTATING, null,
                                                           true, true, DEAD_GRANT ) );
    }

    /** A grant expiring exactly now is spent, so it can never outlive its stated deadline. */
    @Test
    void aGrantExpiringExactlyNowHasAlreadyLapsed()
    {
        assertEquals( McpApprovalDecision.PROMPT, resolve( McpRiskClass.MUTATING, null,
                                                           true, true, NOW ) );
    }

    @Test
    void aZeroExpiryMeansNoGrantRatherThanAnAncientOne()
    {
        assertFalse( McpApprovalEngine.hasUnexpiredGrant( new McpApprovalEngine.Request(
                McpRiskClass.MUTATING, null, true, true, true, NO_GRANT, NOW ) ) );
    }

    @Test
    void theDefaultGrantLifetimeIsEightHours()
    {
        assertEquals( 8L * 60L * 60L * 1000L, McpApprovalEngine.DEFAULT_GRANT_TTL_MS );
    }

    // endregion

    // region fail-closed behaviour

    /**
     * The single most important property in this class. If consent is required and there is no
     * GUI to ask — server mode, headless, tray unavailable, FX toolkit not up — the answer is
     * no. Silently self-approving because a dialog could not be shown would turn every
     * headless run into an unattended-execution path.
     */
    @Test
    void consentRequiredWithNoGuiDeniesRatherThanSelfApproving()
    {
        for ( McpRiskClass risk : McpRiskClass.values() ) {
            assertEquals( McpApprovalDecision.DENY,
                          resolve( risk, McpApprovalPolicy.ASK, true, false, NO_GRANT ),
                          risk + " should deny when it cannot ask" );
        }
    }

    @Test
    void aReadOnlyToolStillRunsHeadlesslyWhenAutoApproveIsOn()
    {
        assertEquals( McpApprovalDecision.ALLOW, resolve( McpRiskClass.READ_ONLY, null,
                                                          true, false, NO_GRANT ) );
    }

    @Test
    void aLiveGrantStillAllowsWithNoGuiPresent()
    {
        assertEquals( McpApprovalDecision.ALLOW, resolve( McpRiskClass.EXECUTE, null,
                                                          true, false, LIVE_GRANT ) );
    }

    /**
     * Exhaustive sweep: across every combination of inputs, a destructive or executing tool is
     * only ever allowed when the user explicitly permitted it or granted consent this session.
     * This is the property that would catch a future refactor quietly adding a bypass.
     */
    @Test
    void dangerousToolsAreNeverAllowedWithoutExplicitPermissionOrAGrant()
    {
        for ( McpRiskClass risk : new McpRiskClass[]{ McpRiskClass.DESTRUCTIVE, McpRiskClass.EXECUTE } ) {
            for ( McpApprovalPolicy policy : new McpApprovalPolicy[]{ null, McpApprovalPolicy.ASK,
                                                                      McpApprovalPolicy.DISABLED } ) {
                for ( boolean autoApprove : new boolean[]{ true, false } ) {
                    for ( boolean gui : new boolean[]{ true, false } ) {
                        for ( long grant : new long[]{ NO_GRANT, DEAD_GRANT } ) {
                            McpApprovalDecision decision = resolve( risk, policy, autoApprove, gui, grant );
                            assertTrue( decision != McpApprovalDecision.ALLOW,
                                        "allowed " + risk + " policy=" + policy + " autoApprove=" +
                                                autoApprove + " gui=" + gui + " grant=" + grant );
                        }
                    }
                }
            }
        }
    }

    // endregion

    // region argument validation

    @Test
    void aNullRequestIsRejected()
    {
        assertThrows( IllegalArgumentException.class, () -> McpApprovalEngine.resolve( null ) );
    }

    @Test
    void aRequestWithNoRiskClassIsRejected()
    {
        assertThrows( IllegalArgumentException.class,
                      () -> McpApprovalEngine.resolve( new McpApprovalEngine.Request(
                              null, null, true, true, true, NO_GRANT, NOW ) ) );
    }

    // endregion

    /**
     * Resolves a request with the server enabled, for the common case.
     *
     * @param risk        the tool's risk class
     * @param policy      the explicit per-tool policy, or {@code null} for the class default
     * @param autoApprove whether read-only auto-approval is on
     * @param gui         whether a consent dialog can be shown
     * @param grantExpiry when a session grant expires, or {@code 0} for none
     *
     * @return the resolved decision
     */
    private static McpApprovalDecision resolve( McpRiskClass risk, McpApprovalPolicy policy,
                                                boolean autoApprove, boolean gui, long grantExpiry )
    {
        return McpApprovalEngine.resolve( new McpApprovalEngine.Request(
                risk, policy, true, autoApprove, gui, grantExpiry, NOW ) );
    }
}
