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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import com.micatechnologies.minecraft.launcher.utilities.SensitiveDataRedactor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of one tool call, in MCP's content-block form.
 * <p>
 * <b>This class is where the plan's section 5.6 credential invariant is enforced.</b> Every
 * piece of text leaving a tool passes through
 * {@link SensitiveDataRedactor#redactStrict(String)} in {@link #toJson()} — access tokens,
 * client tokens, legacy {@code token:<token>:<uuid>} session strings, and bare UUIDs are
 * stripped on the way out.
 * <p>
 * Putting it here rather than in each tool is deliberate. The dangerous surface is logs and
 * crash reports, which are large, attacker-influenced, and handled by several different tools;
 * a rule that every tool author must remember would eventually be forgotten. A tool
 * <em>cannot</em> return unredacted text through this type, including one written later by
 * someone who has not read the plan.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class McpToolResult
{
    /** The text blocks making up this result, in order. */
    private final List< String > textBlocks;

    /** Whether the call failed in a way the model should see and can react to. */
    private final boolean error;

    /**
     * Constructs a result.
     *
     * @param textBlocks the text blocks, in order
     * @param error      whether this represents a tool-level failure
     */
    private McpToolResult( List< String > textBlocks, boolean error )
    {
        this.textBlocks = textBlocks;
        this.error = error;
    }

    /**
     * Builds a successful text result.
     *
     * @param text the text to return; {@code null} is treated as empty
     *
     * @return the result
     *
     * @since 3.0
     */
    public static McpToolResult text( String text )
    {
        return new McpToolResult( List.of( text == null ? "" : text ), false );
    }

    /**
     * Builds a successful result carrying JSON, serialized as a text block.
     * <p>
     * MCP transports structured tool output as text, so the payload is pretty-printed to stay
     * readable in a client's transcript.
     *
     * @param payload the JSON payload; {@code null} yields an empty object
     *
     * @return the result
     *
     * @since 3.0
     */
    public static McpToolResult json( JsonElement payload )
    {
        return text( JSONUtilities.getPrettyGson().toJson( payload == null ? new JsonObject() : payload ) );
    }

    /**
     * Builds a tool-level error result.
     * <p>
     * This is <em>not</em> a JSON-RPC error: the call itself succeeded, the tool just could not
     * do what was asked. MCP wants that reported as a result with {@code isError} set so the
     * model can read the reason and adjust, rather than as a protocol failure.
     *
     * @param message the reason, in terms the model can act on
     *
     * @return the result
     *
     * @since 3.0
     */
    public static McpToolResult error( String message )
    {
        return new McpToolResult( List.of( message == null ? "" : message ), true );
    }

    /**
     * Reports whether this result represents a tool-level failure.
     *
     * @return {@code true} when the tool could not do what was asked
     *
     * @since 3.0
     */
    public boolean isError()
    {
        return error;
    }

    /**
     * Returns the raw, <b>unredacted</b> text blocks.
     * <p>
     * Exposed for tests and for in-process callers that are not sending the result across the
     * MCP boundary. Anything crossing that boundary must go through {@link #toJson()}.
     *
     * @return an unmodifiable view of the text blocks
     *
     * @since 3.0
     */
    public List< String > rawTextBlocks()
    {
        return Collections.unmodifiableList( textBlocks );
    }

    /**
     * Serializes this result to MCP's {@code tools/call} result shape, redacting every text
     * block on the way out.
     *
     * @return the result object, safe to send to a client
     *
     * @since 3.0
     */
    public JsonObject toJson()
    {
        JsonArray content = new JsonArray();
        for ( String block : textBlocks ) {
            JsonObject entry = new JsonObject();
            entry.addProperty( "type", "text" );
            entry.addProperty( "text", SensitiveDataRedactor.redactStrict( block ) );
            content.add( entry );
        }

        JsonObject result = new JsonObject();
        result.add( "content", content );
        result.addProperty( "isError", error );
        return result;
    }

    /**
     * Builds a multi-block successful result.
     *
     * @param blocks the text blocks, in order; {@code null} entries become empty strings
     *
     * @return the result
     *
     * @since 3.0
     */
    public static McpToolResult texts( List< String > blocks )
    {
        List< String > copy = new ArrayList<>();
        if ( blocks != null ) {
            for ( String block : blocks ) {
                copy.add( block == null ? "" : block );
            }
        }
        return new McpToolResult( copy, false );
    }
}
