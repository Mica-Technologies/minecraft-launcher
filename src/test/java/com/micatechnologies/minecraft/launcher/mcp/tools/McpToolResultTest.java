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
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link McpToolResult}, and specifically for the plan's section 5.6 credential
 * invariant: <b>no tool may return a credential or credential-like value</b>.
 *
 * <p>Why this matters: the dangerous surface is not tool fields, it is logs and crash
 * reports. Those are large, attacker-influenced, and handled by several different tools
 * ({@code get_crash_report}, {@code get_game_log}, {@code diagnose_launch_failure}, and the
 * {@code mica://…/log} resources). A rule that every tool author must remember to apply would
 * eventually be forgotten by someone adding a tool a year from now who never read the plan.</p>
 *
 * <p>So redaction lives in {@link McpToolResult#toJson()} — the one place every result must
 * pass through on its way to a client. These tests exist to prove a tool <em>cannot</em>
 * return an unredacted token even when it tries to.</p>
 */
class McpToolResultTest
{
    // region the credential invariant

    @Test
    void anAccessTokenInToolOutputIsRedacted()
    {
        String leaked = "java -cp foo --accessToken eyJhbGciOiJIUzI1NiJ9.super.secret --username bob";
        String out = firstText( McpToolResult.text( leaked ).toJson() );
        assertFalse( out.contains( "eyJhbGciOiJIUzI1NiJ9.super.secret" ), out );
        assertTrue( out.contains( "[REDACTED]" ), out );
    }

    @Test
    void aClientTokenInToolOutputIsRedacted()
    {
        String out = firstText( McpToolResult.text( "--clientToken abc123def456 rest" ).toJson() );
        assertFalse( out.contains( "abc123def456" ), out );
    }

    /**
     * The account UUID is not a credential on its own, but it is identifying and it is half of
     * the legacy {@code token:<token>:<uuid>} session format. Section 5.6 forbids returning it,
     * which is why MCP output uses {@code redactStrict} rather than the console's
     * {@code redact}.
     */
    @Test
    void aBareAccountUuidIsRedacted()
    {
        String out = firstText(
                McpToolResult.text( "Player uuid 069a79f4-44e9-4726-a5be-fca90e38aaf5 joined" ).toJson() );
        assertFalse( out.contains( "069a79f4-44e9-4726-a5be-fca90e38aaf5" ), out );
    }

    @Test
    void anUndashedUuidIsAlsoRedacted()
    {
        String out = firstText( McpToolResult.text( "id=069a79f444e94726a5befca90e38aaf5" ).toJson() );
        assertFalse( out.contains( "069a79f444e94726a5befca90e38aaf5" ), out );
    }

    @Test
    void aLegacySessionStringIsRedacted()
    {
        String out = firstText(
                McpToolResult.text( "token:SUPERSECRETTOKEN:069a79f4-44e9-4726-a5be-fca90e38aaf5" ).toJson() );
        assertFalse( out.contains( "SUPERSECRETTOKEN" ), out );
        assertFalse( out.contains( "069a79f4-44e9-4726-a5be-fca90e38aaf5" ), out );
    }

    /** Redaction must apply to error text too — a failure message often quotes the command. */
    @Test
    void errorResultsAreRedactedAsWell()
    {
        JsonObject json = McpToolResult.error( "failed: --accessToken leakedvalue123" ).toJson();
        assertTrue( json.get( "isError" ).getAsBoolean() );
        assertFalse( firstText( json ).contains( "leakedvalue123" ) );
    }

    /** And to every block of a multi-block result, not just the first. */
    @Test
    void everyBlockOfAMultiBlockResultIsRedacted()
    {
        JsonObject json = McpToolResult.texts( Arrays.asList(
                "clean", "--accessToken leakedvalue123", "also clean" ) ).toJson();
        JsonArray content = json.getAsJsonArray( "content" );
        assertEquals( 3, content.size() );
        assertFalse( json.toString().contains( "leakedvalue123" ), json.toString() );
    }

    /** JSON payloads go out as text, so they are redacted on the same path. */
    @Test
    void jsonPayloadsAreRedactedToo()
    {
        JsonObject payload = new JsonObject();
        payload.addProperty( "uuid", "069a79f4-44e9-4726-a5be-fca90e38aaf5" );
        assertFalse( McpToolResult.json( payload ).toJson().toString()
                             .contains( "069a79f4-44e9-4726-a5be-fca90e38aaf5" ) );
    }

    /**
     * The raw accessor is the deliberate escape hatch for in-process callers. It must NOT be
     * redacted — otherwise callers would have no way to see real data — which is exactly why
     * it is named "raw" and documented as not for the MCP boundary.
     */
    @Test
    void theRawAccessorDeliberatelyDoesNotRedact()
    {
        assertEquals( "--accessToken leakedvalue123",
                      McpToolResult.text( "--accessToken leakedvalue123" ).rawTextBlocks().get( 0 ) );
    }

    /**
     * SHA-1 hashes must survive: asset and library checksums fill the logs, and redacting them
     * would gut the usefulness of {@code diagnose_launch_failure} for no security gain.
     */
    @Test
    void sha1HashesAreNotMistakenForCredentials()
    {
        String sha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
        assertTrue( firstText( McpToolResult.text( "sha1 " + sha1 ).toJson() ).contains( sha1 ) );
    }

    // endregion

    // region result shape

    @Test
    void aTextResultHasTheMcpContentShape()
    {
        JsonObject json = McpToolResult.text( "hello" ).toJson();
        JsonArray content = json.getAsJsonArray( "content" );
        assertEquals( 1, content.size() );
        assertEquals( "text", content.get( 0 ).getAsJsonObject().get( "type" ).getAsString() );
        assertEquals( "hello", content.get( 0 ).getAsJsonObject().get( "text" ).getAsString() );
        assertFalse( json.get( "isError" ).getAsBoolean() );
    }

    @Test
    void successAndErrorAreDistinguishable()
    {
        assertFalse( McpToolResult.text( "ok" ).isError() );
        assertTrue( McpToolResult.error( "bad" ).isError() );
    }

    @Test
    void nullTextBecomesAnEmptyBlockRatherThanNull()
    {
        assertEquals( "", firstText( McpToolResult.text( null ).toJson() ) );
        assertEquals( "", firstText( McpToolResult.error( null ).toJson() ) );
    }

    @Test
    void aNullJsonPayloadBecomesAnEmptyObject()
    {
        assertEquals( "{}", firstText( McpToolResult.json( null ).toJson() ) );
    }

    @Test
    void nullBlocksInAMultiBlockResultBecomeEmptyStrings()
    {
        JsonObject json = McpToolResult.texts( Arrays.asList( "a", null, "b" ) ).toJson();
        assertEquals( "", json.getAsJsonArray( "content" ).get( 1 ).getAsJsonObject()
                .get( "text" ).getAsString() );
    }

    @Test
    void aNullBlockListYieldsNoContent()
    {
        assertEquals( 0, McpToolResult.texts( null ).toJson().getAsJsonArray( "content" ).size() );
    }

    @Test
    void theRawBlockViewIsUnmodifiable()
    {
        assertThrows( UnsupportedOperationException.class,
                      () -> McpToolResult.text( "x" ).rawTextBlocks().add( "y" ) );
    }

    // endregion

    /**
     * Returns the text of a result's first content block.
     *
     * @param json the serialized result
     *
     * @return the first block's text
     */
    private static String firstText( JsonObject json )
    {
        return json.getAsJsonArray( "content" ).get( 0 ).getAsJsonObject().get( "text" ).getAsString();
    }
}
