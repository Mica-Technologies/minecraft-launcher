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

/**
 * Decides whether one MCP tool call runs, prompts, or is refused.
 * <p>
 * This is a pure function of its inputs — no config reads, no clock, no UI — so the whole
 * decision table can be exercised by unit tests. That matters more here than almost anywhere
 * else in the launcher: this class stands between a model's chosen tool call and code that
 * launches games under the user's live Microsoft session, installs from model-chosen URLs, and
 * deletes modpacks.
 * <p>
 * <b>It fails closed.</b> Every path that cannot reach an affirmative answer resolves to
 * {@link McpApprovalDecision#DENY}, including the case where consent is required but no GUI
 * exists to ask — a headless launcher never silently self-approves.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class McpApprovalEngine
{
    /**
     * Default lifetime of a session grant, in milliseconds (8 hours).
     * <p>
     * Grants are keyed to the client name plus the tool rather than to the transport
     * connection, because MCP clients reconnect frequently and a connection-scoped grant would
     * re-prompt constantly — which trains the user to click through prompts without reading
     * them. The TTL is what keeps "allow for this session" from quietly becoming permanent.
     */
    public static final long DEFAULT_GRANT_TTL_MS = 8L * 60L * 60L * 1000L;

    /**
     * Everything the decision depends on, gathered by the caller.
     *
     * @param riskClass           the tool's declared risk class
     * @param explicitPolicy      the user's per-tool policy, or {@code null} when they have set
     *                            none and the risk-class default applies
     * @param serverEnabled       whether the MCP master switch is on
     * @param autoApproveReadOnly whether the "auto-approve read-only tools" master toggle is on
     * @param guiAvailable        whether a consent dialog can actually be shown
     * @param grantExpiresAtMs    when the session grant for this client and tool expires, or
     *                            {@code 0} when there is none
     * @param nowMs               the current time, supplied rather than read so the TTL is
     *                            testable
     *
     * @since 3.0
     */
    public record Request( McpRiskClass riskClass,
                           McpApprovalPolicy explicitPolicy,
                           boolean serverEnabled,
                           boolean autoApproveReadOnly,
                           boolean guiAvailable,
                           long grantExpiresAtMs,
                           long nowMs )
    {
    }

    /**
     * Resolves one tool call to a decision.
     * <p>
     * Order of resolution:
     * <ol>
     *   <li>Master switch off — deny. The server should not be reachable at all in this state;
     *       denying here is defence in depth.</li>
     *   <li>An explicit per-tool policy wins outright, in both directions: {@code DISABLED}
     *       denies, {@code ALWAYS_ALLOW} allows, and {@code ASK} prompts (or denies, with no
     *       GUI). <b>A session grant overrides none of them</b> — a user who turns a tool off,
     *       or sets it to "Always ask", has overruled any "allow for this session" consent they
     *       gave earlier.</li>
     *   <li>An unexpired session grant allows the call. Grants therefore only decide calls to
     *       tools with no explicit policy.</li>
     *   <li>Otherwise the risk-class default applies. {@code READ_ONLY} allows only while the
     *       auto-approve toggle is on, which is how a cautious user forces prompts on
     *       everything.</li>
     *   <li>Anything still requiring consent prompts — or denies, when no GUI is available to
     *       ask.</li>
     * </ol>
     * <p>
     * Note that an explicit {@code ALWAYS_ALLOW} on a read-only tool is <em>not</em> undone by
     * turning off the auto-approve toggle: the toggle gates the class default, and a per-tool
     * setting the user chose deliberately is the more specific instruction.
     *
     * @param request everything the decision depends on
     *
     * @return the decision; never {@code null}
     *
     * @throws IllegalArgumentException if {@code request} or its risk class is {@code null}
     * @since 3.0
     */
    public static McpApprovalDecision resolve( Request request )
    {
        if ( request == null || request.riskClass() == null ) {
            throw new IllegalArgumentException( "An approval request and its risk class are required" );
        }

        if ( !request.serverEnabled() ) {
            return McpApprovalDecision.DENY;
        }

        McpApprovalPolicy explicit = request.explicitPolicy();
        if ( explicit == McpApprovalPolicy.DISABLED ) {
            return McpApprovalDecision.DENY;
        }
        if ( explicit == McpApprovalPolicy.ALWAYS_ALLOW ) {
            return McpApprovalDecision.ALLOW;
        }
        if ( explicit == McpApprovalPolicy.ASK ) {
            // "Always ask" means every call. A grant from an earlier "Allow for this session"
            // must not quietly turn it into "ask once".
            return request.guiAvailable() ? McpApprovalDecision.PROMPT : McpApprovalDecision.DENY;
        }

        if ( hasUnexpiredGrant( request ) ) {
            return McpApprovalDecision.ALLOW;
        }

        if ( request.riskClass() == McpRiskClass.READ_ONLY
                && request.autoApproveReadOnly() ) {
            return McpApprovalDecision.ALLOW;
        }

        return request.guiAvailable() ? McpApprovalDecision.PROMPT : McpApprovalDecision.DENY;
    }

    /**
     * Reports whether the request carries a session grant that has not yet expired.
     * <p>
     * A grant expiring exactly now counts as expired, so a grant can never outlive its stated
     * deadline.
     *
     * @param request the request to inspect
     *
     * @return {@code true} when a live grant covers this call
     *
     * @since 3.0
     */
    public static boolean hasUnexpiredGrant( Request request )
    {
        return request.grantExpiresAtMs() > 0 && request.grantExpiresAtMs() > request.nowMs();
    }

    /**
     * Not instantiable.
     */
    private McpApprovalEngine()
    {
        throw new AssertionError( "McpApprovalEngine is a utility class and must not be instantiated" );
    }
}
