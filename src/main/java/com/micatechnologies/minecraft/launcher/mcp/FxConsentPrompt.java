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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.gui.GUIUtilities;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiController;
import com.micatechnologies.minecraft.launcher.mcp.approval.LauncherMcpAuthorizer;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpCallContext;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpTool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Asks the user to approve an MCP tool call, using the launcher's existing question dialog.
 * <p>
 * The dialog names the <b>client</b>, the <b>tool</b>, and the tool's own description, so the
 * user is answering "Claude Code wants to install a modpack" rather than a bare method name.
 * <p>
 * <b>Every wait is bounded.</b> {@code GUIUtilities.showQuestionMessage} blocks on a latch with
 * no deadline, so it is run on a separate thread and awaited with a timeout: a tool call must
 * never hang a client forever on a dialog nobody is looking at. On timeout the call is denied.
 * The dialog itself stays on screen — the underlying helper offers no way to dismiss it — but
 * the answer has already been decided, which is the fail-closed direction.
 * <p>
 * Three outcomes, per the plan's section 5.4: <b>Allow once</b>, <b>Allow for this session</b>,
 * and deny — which is what dismissing, cancelling, or pressing Escape produces, so the safe
 * answer is the one that requires no decision. The launcher's question helper already appends
 * its own Cancel button when the second label is not itself a cancel, so no new dialog API was
 * needed for the third choice.
 * <p>
 * "Always allow, permanently" is deliberately <em>not</em> a button here. A standing grant that
 * survives restarts is a considered decision, not one to make while a model is waiting; it lives
 * in Settings as a per-tool policy, where it sits next to the tool's risk class and can be
 * reviewed and revoked. The destructive-action framing that names what would be lost arrives
 * with the mutating tools — nothing registered today can change anything.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class FxConsentPrompt implements LauncherMcpAuthorizer.ConsentPrompt
{
    /**
     * How long to wait for an answer before denying.
     * <p>
     * Tool invocation is serialized through a single-threaded executor, so a call blocked here
     * stalls every other call. Two minutes is long enough for a user who stepped away briefly
     * and short enough that an unattended launcher recovers on its own.
     */
    public static final long CONSENT_TIMEOUT_SECONDS = 120L;

    /** Most arguments rendered in the consent dialog before the rest are elided. */
    private static final int MAX_SUMMARIZED_ARGUMENTS = 6;

    /** Longest argument value rendered in the consent dialog before truncation. */
    private static final int MAX_SUMMARIZED_VALUE_LENGTH = 120;

    @Override
    public boolean isAvailable()
    {
        try {
            return MCLauncherGuiController.getTopStageOrNull() != null;
        }
        catch ( Exception e ) {
            // No toolkit, no window, no dialog. Reporting unavailable makes the caller deny.
            return false;
        }
    }

    @Override
    public LauncherMcpAuthorizer.Answer ask( McpTool tool, McpCallContext context,
                                             JsonObject arguments )
    {
        return ask( tool, context, arguments, true );
    }

    /**
     * Shows the consent dialog. When {@code offerSessionGrant} is {@code false} — the tool is
     * set to "Always ask" — the second button is the dialog's own Cancel rather than "Allow for
     * this session", so the only ways out are allowing this one call or refusing it.
     *
     * @param tool              the tool being called
     * @param context           who is calling
     * @param arguments         the call arguments
     * @param offerSessionGrant whether "Allow for this session" may be offered
     *
     * @return the user's answer
     *
     * @since 2026.10
     */
    @Override
    public LauncherMcpAuthorizer.Answer ask( McpTool tool, McpCallContext context,
                                             JsonObject arguments, boolean offerSessionGrant )
    {
        if ( tool == null || context == null || !isAvailable() ) {
            return LauncherMcpAuthorizer.Answer.DENY;
        }

        CompletableFuture< Integer > answer = CompletableFuture.supplyAsync( () ->
                GUIUtilities.showQuestionMessage(
                        LocalizationManager.get( "dialog.mcp.consent.title" ),
                        LocalizationManager.format( "dialog.mcp.consent.header",
                                                    context.clientName(), tool.title() ),
                        LocalizationManager.format( "dialog.mcp.consent.body",
                                                    context.clientName(), tool.name(),
                                                    tool.description(),
                                                    detailedArguments( tool, arguments ) ),
                        LocalizationManager.get( "dialog.mcp.consent.button.allowOnce" ),
                        // The question helper folds a second label equal to its own Cancel
                        // into the dialog's cancel button, so this yields Allow once / Cancel.
                        LocalizationManager.get( offerSessionGrant
                                                 ? "dialog.mcp.consent.button.allowSession"
                                                 : "dialog.button.cancel" ),
                        MCLauncherGuiController.getTopStageOrNull() ), runnable -> {
            Thread thread = new Thread( runnable, "mcp-consent" );
            thread.setDaemon( true );
            thread.start();
        } );

        try {
            // showQuestionMessage returns 1 for the first button, 2 for the second, and 0 for
            // cancel or dismiss. Dismissing therefore denies, which is the point: the answer
            // that needs no decision is the safe one.
            int chosen = answer.get( CONSENT_TIMEOUT_SECONDS, TimeUnit.SECONDS );
            return switch ( chosen ) {
                case 1 -> LauncherMcpAuthorizer.Answer.ALLOW_ONCE;
                case 2 -> offerSessionGrant ? LauncherMcpAuthorizer.Answer.ALLOW_FOR_SESSION
                                            : LauncherMcpAuthorizer.Answer.DENY;
                default -> LauncherMcpAuthorizer.Answer.DENY;
            };
        }
        catch ( TimeoutException e ) {
            Logger.logStd( LocalizationManager.format( "log.mcpConsent.promptTimedOut", tool.name() ) );
            return LauncherMcpAuthorizer.Answer.DENY;
        }
        catch ( InterruptedException e ) {
            Thread.currentThread().interrupt();
            return LauncherMcpAuthorizer.Answer.DENY;
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.format( "log.mcpConsent.promptFailed", tool.name() ) );
            Logger.logThrowable( e );
            return LauncherMcpAuthorizer.Answer.DENY;
        }
    }

    /**
     * Renders a tool call's arguments for the dialog, so the user is answering a concrete
     * question — "install a modpack from example.com" — rather than a bare tool name.
     *
     * <p>Arguments are model-chosen, so the summary is bounded rather than trusted: only
     * primitive values are rendered, each is truncated, and the list is capped. Structured
     * values are shown by type alone. The dialog helper additionally sanitizes the assembled
     * text before display.</p>
     *
     * @param arguments the call arguments, possibly {@code null}
     *
     * @return a one-line human-readable summary, or a "no arguments" marker
     *
     * @since 3.0
     */
    static String summarizeArguments( JsonObject arguments )
    {
        if ( arguments == null || arguments.isEmpty() ) {
            return LocalizationManager.get( "dialog.mcp.consent.noArguments" );
        }

        StringBuilder summary = new StringBuilder();
        int shown = 0;
        for ( String key : arguments.keySet() ) {
            if ( shown >= MAX_SUMMARIZED_ARGUMENTS ) {
                summary.append( ", \u2026" );
                break;
            }
            if ( shown > 0 ) {
                summary.append( ", " );
            }
            summary.append( key ).append( "=" ).append( renderValue( arguments.get( key ) ) );
            shown++;
        }
        return summary.toString();
    }

    /**
     * Renders one argument value, truncating long strings and reducing structured values to
     * their type.
     *
     * @param value the value to render
     *
     * @return the rendered value
     */
    private static String renderValue( JsonElement value )
    {
        if ( value == null || value.isJsonNull() ) {
            return "null";
        }
        if ( value.isJsonArray() ) {
            return "[" + value.getAsJsonArray().size() + " items]";
        }
        if ( value.isJsonObject() ) {
            return "{object}";
        }
        String text = value.getAsString();
        return text.length() > MAX_SUMMARIZED_VALUE_LENGTH
               ? text.substring( 0, MAX_SUMMARIZED_VALUE_LENGTH ) + "\u2026"
               : text;
    }

    /**
     * Builds the argument line for the dialog, appending the tool's own consent detail when it
     * offers one.
     *
     * <p>For a destructive tool that detail is what the user is actually deciding about —
     * "4.2 GB, 3 worlds" rather than a pack name — so it goes after the arguments where it
     * reads as the consequence rather than as more parameters.</p>
     *
     * @param tool      the tool being called
     * @param arguments the call arguments
     *
     * @return the assembled line
     */
    private static String detailedArguments( McpTool tool, JsonObject arguments )
    {
        String summary = summarizeArguments( arguments );
        String detail;
        try {
            detail = tool.consentDetail( arguments );
        }
        catch ( Exception e ) {
            // A detail that cannot be computed must not stop the prompt: the user still needs
            // to be asked, just with less context.
            Logger.logWarningSilent( LocalizationManager.format( "log.mcpConsent.detailFailed", tool.name() ) );
            detail = null;
        }
        return detail == null || detail.isBlank() ? summary : summary + "\n\n" + detail;
    }

}
