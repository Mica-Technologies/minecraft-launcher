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

/**
 * Decides whether one tool call may proceed, including showing a consent dialog when the
 * policy calls for one.
 * <p>
 * This exists so the request dispatcher does not depend on JavaFX or on launcher config. The
 * production implementation reads the user's per-tool policy, consults
 * {@link McpApprovalEngine}, and blocks on an FX dialog when the answer is
 * {@link McpApprovalDecision#PROMPT}. Tests supply a stub, which is what lets every dispatch
 * path be exercised headlessly.
 * <p>
 * Implementations must <b>fail closed</b>: any error, timeout, or unavailable dialog answers
 * {@code false}. They must also never block indefinitely — a call left waiting on a dialog
 * nobody is looking at stalls every other tool call, since invocation is serialized.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
@FunctionalInterface
public interface McpAuthorizer
{
    /**
     * Decides whether a tool call may proceed.
     *
     * @param tool      the tool being called
     * @param context   who is calling
     * @param arguments the call arguments, so a consent prompt can describe the call
     *                  concretely rather than naming a bare tool
     *
     * @return {@code true} to run the tool; {@code false} to refuse
     *
     * @since 3.0
     */
    boolean authorize( McpTool tool, McpCallContext context, JsonObject arguments );
}
