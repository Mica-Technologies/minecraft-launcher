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
 * This is the two-button form: allow once, or deny. The three-button variant with
 * "always allow", and the destructive-action framing that names what would be lost, arrive with
 * the mutating tools in a later phase — nothing registered today can change anything.
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
                                                    tool.description() ),
                        LocalizationManager.get( "dialog.mcp.consent.button.allowOnce" ),
                        LocalizationManager.get( "dialog.button.cancel" ),
                        MCLauncherGuiController.getTopStageOrNull() ), runnable -> {
            Thread thread = new Thread( runnable, "mcp-consent" );
            thread.setDaemon( true );
            thread.start();
        } );

        try {
            // showQuestionMessage returns 1 for the first button, 2 for the second, 0 for
            // cancel or dismiss. Anything but 1 is a refusal.
            return answer.get( CONSENT_TIMEOUT_SECONDS, TimeUnit.SECONDS ) == 1
                   ? LauncherMcpAuthorizer.Answer.ALLOW_ONCE
                   : LauncherMcpAuthorizer.Answer.DENY;
        }
        catch ( TimeoutException e ) {
            Logger.logStd( "MCP consent prompt timed out for " + tool.name() + "; denying" );
            return LauncherMcpAuthorizer.Answer.DENY;
        }
        catch ( InterruptedException e ) {
            Thread.currentThread().interrupt();
            return LauncherMcpAuthorizer.Answer.DENY;
        }
        catch ( Exception e ) {
            Logger.logError( "MCP consent prompt failed for " + tool.name() + "; denying" );
            Logger.logThrowable( e );
            return LauncherMcpAuthorizer.Answer.DENY;
        }
    }
}
