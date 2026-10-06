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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpCallContext;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpTool;

import java.util.function.LongSupplier;

/**
 * The production {@link McpAuthorizer}: resolves policy through {@link McpApprovalEngine} and,
 * where consent is required, asks the user.
 * <p>
 * Every input arrives through an injected collaborator — settings, grants, the consent prompt,
 * and the clock. That is not ceremony: it is what allows the decision table, the grant
 * lifecycle, and the headless fail-closed path to be exercised in tests without a running
 * launcher or an FX toolkit, which is exactly where an approval bug would otherwise hide.
 * <p>
 * <b>It fails closed at every step.</b> A settings read that throws, a prompt that throws, a
 * prompt that times out, and a prompt with no GUI behind it all resolve to "no".
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class LauncherMcpAuthorizer implements McpAuthorizer
{
    /**
     * The launcher settings the decision depends on.
     *
     * @since 3.0
     */
    public interface Settings
    {
        /**
         * Reports whether the MCP master switch is on.
         *
         * @return {@code true} when the server is enabled
         *
         * @since 3.0
         */
        boolean serverEnabled();

        /**
         * Reports whether read-only tools skip the consent prompt.
         *
         * @return {@code true} when read-only auto-approval is on
         *
         * @since 3.0
         */
        boolean autoApproveReadOnly();

        /**
         * Returns the user's explicit policy for one tool.
         *
         * @param toolName the tool
         *
         * @return the policy, or {@code null} when the user has set none and the risk-class
         *         default applies
         *
         * @since 3.0
         */
        McpApprovalPolicy policyFor( String toolName );
    }

    /**
     * Asks the user to approve one call.
     *
     * @since 3.0
     */
    public interface ConsentPrompt
    {
        /**
         * Reports whether a prompt can actually be shown right now.
         * <p>
         * When this is {@code false} the call is denied rather than approved — a headless
         * launcher must never self-approve because it could not ask.
         *
         * @return {@code true} when a dialog can be shown
         *
         * @since 3.0
         */
        boolean isAvailable();

        /**
         * Shows the consent dialog and waits for an answer.
         *
         * @param tool      the tool being called
         * @param context   who is calling
         * @param arguments the call arguments, so the prompt can describe the call concretely
         *
         * @return the user's answer
         *
         * @since 3.0
         */
        Answer ask( McpTool tool, McpCallContext context, JsonObject arguments );

        /**
         * Shows the consent dialog, optionally without the "Allow for this session" choice,
         * and waits for an answer.
         * <p>
         * A tool the user set to "Always ask" must be asked about on every call, so offering a
         * session grant for it would be offering something the approval engine will not honour.
         * The default delegates to {@link #ask(McpTool, McpCallContext, JsonObject)}; the
         * authorizer ignores an {@link Answer#ALLOW_FOR_SESSION} it did not offer either way.
         *
         * @param tool              the tool being called
         * @param context           who is calling
         * @param arguments         the call arguments, so the prompt can describe the call
         * @param offerSessionGrant whether "Allow for this session" may be offered
         *
         * @return the user's answer
         *
         * @since 2026.10
         */
        default Answer ask( McpTool tool, McpCallContext context, JsonObject arguments,
                            boolean offerSessionGrant )
        {
            return ask( tool, context, arguments );
        }
    }

    /**
     * What the user chose.
     *
     * @since 3.0
     */
    public enum Answer
    {
        /** Refuse this call. Also the answer on dismiss, timeout, and error. */
        DENY,

        /** Run this call only. */
        ALLOW_ONCE,

        /** Run this call and remember the choice for the rest of the session. */
        ALLOW_FOR_SESSION
    }

    /** Launcher settings. */
    private final Settings settings;

    /** Consent granted so far this run. */
    private final McpGrantStore grants;

    /** Asks the user. */
    private final ConsentPrompt prompt;

    /** Supplies the current time, injected so grant expiry is testable. */
    private final LongSupplier clock;

    /**
     * Constructs an authorizer.
     *
     * @param settings launcher settings
     * @param grants   consent granted so far this run
     * @param prompt   asks the user when consent is required
     * @param clock    supplies the current time in epoch milliseconds
     *
     * @throws IllegalArgumentException if any argument is {@code null}
     * @since 3.0
     */
    public LauncherMcpAuthorizer( Settings settings, McpGrantStore grants, ConsentPrompt prompt,
                                  LongSupplier clock )
    {
        if ( settings == null || grants == null || prompt == null || clock == null ) {
            throw new IllegalArgumentException( "Settings, grants, a prompt and a clock are required" );
        }
        this.settings = settings;
        this.grants = grants;
        this.prompt = prompt;
        this.clock = clock;
    }

    @Override
    public boolean authorize( McpTool tool, McpCallContext context, JsonObject arguments )
    {
        if ( tool == null || context == null ) {
            return false;
        }

        long now;
        McpApprovalPolicy explicitPolicy;
        McpApprovalEngine.Request request;
        try {
            now = clock.getAsLong();
            explicitPolicy = settings.policyFor( tool.name() );
            request = new McpApprovalEngine.Request(
                    tool.riskClass(),
                    explicitPolicy,
                    settings.serverEnabled(),
                    settings.autoApproveReadOnly(),
                    prompt.isAvailable(),
                    grants.expiryFor( context.clientName(), tool.name() ),
                    now );
        }
        catch ( Exception e ) {
            // A settings or grant read that blew up has not granted anything.
            Logger.logError( LocalizationManager.format( "log.mcpAuthorizer.resolveFailed", tool.name() ) );
            Logger.logThrowable( e );
            return false;
        }

        McpApprovalDecision decision = McpApprovalEngine.resolve( request );
        if ( decision == McpApprovalDecision.ALLOW ) {
            return true;
        }
        if ( decision == McpApprovalDecision.DENY ) {
            Logger.logStd( LocalizationManager.format( "log.mcpAuthorizer.denied", context.clientName(), tool.name() ) );
            return false;
        }

        // "Always ask" is not satisfiable by a grant, so the dialog must not offer one.
        boolean offerSessionGrant = explicitPolicy != McpApprovalPolicy.ASK;
        Answer answer;
        try {
            answer = prompt.ask( tool, context, arguments, offerSessionGrant );
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.format( "log.mcpConsent.promptFailed", tool.name() ) );
            Logger.logThrowable( e );
            return false;
        }
        if ( answer == null ) {
            return false;
        }

        if ( answer == Answer.ALLOW_FOR_SESSION && offerSessionGrant ) {
            grants.grant( context.clientName(), tool.name(),
                          now + McpApprovalEngine.DEFAULT_GRANT_TTL_MS );
        }
        boolean allowed = answer != Answer.DENY;
        Logger.logStd( LocalizationManager.format( allowed ? "log.mcpAuthorizer.allowedAnswer" : "log.mcpAuthorizer.deniedAnswer", context.clientName(), tool.name(), answer ) );
        return allowed;
    }
}
