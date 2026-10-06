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

package com.micatechnologies.minecraft.launcher.mcp.tools;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.mcp.approval.McpRiskClass;

/**
 * One capability the launcher exposes to MCP clients.
 * <p>
 * Implementations must be safe to call off the FX thread and must not assume a GUI exists.
 * Tool invocation is serialized through a single-threaded executor, so an implementation does
 * not need to guard against concurrent calls to itself — but it must not block indefinitely,
 * because doing so stalls every other tool call.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public interface McpTool
{
    /**
     * Returns the tool's wire name, as used in {@code tools/call}.
     * <p>
     * Must match {@link McpToolRegistry#VALID_TOOL_NAME} — lower-case letters, digits,
     * underscores and hyphens.
     *
     * @return the tool name
     *
     * @since 3.0
     */
    String name();

    /**
     * Returns a short English title, sent to MCP clients as the tool's protocol title.
     * <p>
     * What the user sees in the consent dialog and the Settings list is
     * {@link #displayTitle()}, in the launcher's UI language.
     *
     * @return the protocol title
     *
     * @since 3.0
     */
    String title();

    /**
     * Returns the tool's title in the launcher's UI language, for the consent dialog and the
     * Settings per-tool permission list.
     * <p>
     * Looks up {@code mcp.tool.<name>.title}, falling back to {@link #title()} for a tool with
     * no translation.
     *
     * @return the localized title
     *
     * @since 2026.10
     */
    default String displayTitle()
    {
        return LocalizationManager.getOr( "mcp.tool." + name() + ".title", title() );
    }

    /**
     * Returns what the tool does, in the launcher's UI language, for the consent dialog.
     * <p>
     * Looks up {@code mcp.tool.<name>.description}, falling back to {@link #description()}. The
     * protocol description stays English: it is written for the model, which reads it.
     *
     * @return the localized description
     *
     * @since 2026.10
     */
    default String displayDescription()
    {
        return LocalizationManager.getOr( "mcp.tool." + name() + ".description", description() );
    }

    /**
     * Returns the description shown to the model. This is what the model reasons over when
     * deciding whether to call the tool, so it should say what the tool does and what it
     * changes.
     *
     * @return the tool description
     *
     * @since 3.0
     */
    String description();

    /**
     * Returns the JSON Schema describing this tool's arguments object.
     *
     * @return the input schema
     *
     * @since 3.0
     */
    JsonObject inputSchema();

    /**
     * Returns how much damage this tool can do, which decides what consent is required.
     *
     * @return the risk class; never {@code null}
     *
     * @since 3.0
     */
    McpRiskClass riskClass();

    /**
     * Runs the tool.
     * <p>
     * A tool that cannot do what was asked should return {@link McpToolResult#error} rather
     * than throwing — that reports the reason to the model, which can then adjust, instead of
     * surfacing a protocol-level failure. Throwing is reserved for genuinely unexpected
     * conditions.
     *
     * @param context who is calling
     * @param arguments the arguments object, already validated as an object but not against
     *                  {@link #inputSchema()}
     *
     * @return the result
     *
     * @throws Exception if the tool fails unexpectedly; the dispatcher converts this into an
     *                   internal error without leaking the exception's detail to the client
     * @since 3.0
     */
    McpToolResult invoke( McpCallContext context, JsonObject arguments ) throws Exception;

    /**
     * Rejects a call on its content, before any approval decision is made.
     *
     * <p>This runs <b>ahead of the consent prompt</b>, and that ordering is the point. When
     * the launcher already knows a request is unsafe — an install URL that fails
     * {@code LauncherUriHandler.classifyInstallUrl}, say — asking the user to approve it
     * anyway teaches them that prompts are noise to click through. A request the launcher
     * refuses on its own should never reach them as a question.</p>
     *
     * <p>It is also the cheaper order: no dialog is raised for a call that was never going to
     * run.</p>
     *
     * @param arguments the call arguments
     *
     * @return a human-readable reason to refuse, or {@code null} to continue to the approval
     *         gate
     *
     * @since 3.0
     */
    default String validateBeforeApproval( JsonObject arguments )
    {
        return null;
    }

    /**
     * Extra context for the consent dialog, naming concretely what this call would do.
     *
     * <p>The plan's section 5.4 requires a destructive prompt to say what would be
     * <em>lost</em> — worlds, size on disk — rather than only what would be run. "Delete All
     * the Mods 9?" and "Delete All the Mods 9: 4.2 GB, 3 worlds?" are different questions, and
     * only the second one can be answered responsibly.</p>
     *
     * <p>Computed at prompt time, so it may be expensive; implementations should bound the
     * work and return {@code null} rather than block.</p>
     *
     * @param arguments the call arguments
     *
     * @return a short line to add to the dialog, or {@code null} for none
     *
     * @since 3.0
     */
    default String consentDetail( JsonObject arguments )
    {
        return null;
    }

    /**
     * Builds this tool's entry in a {@code tools/list} response.
     *
     * @return the descriptor object
     *
     * @since 3.0
     */
    default JsonObject describe()
    {
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty( "name", name() );
        descriptor.addProperty( "title", title() );
        descriptor.addProperty( "description", description() );
        descriptor.add( "inputSchema", inputSchema() );
        return descriptor;
    }
}
