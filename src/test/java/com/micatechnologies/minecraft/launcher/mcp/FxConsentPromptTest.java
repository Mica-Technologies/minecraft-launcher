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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FxConsentPrompt}'s argument summariser — the text that turns a consent
 * dialog from "a tool wants to run" into "a tool wants to install from example.com".
 *
 * <p>Why this is bounded rather than trusted: the arguments are chosen by the model on the
 * other end of the connection, and they are being rendered into the one dialog the user reads
 * before granting permission. An unbounded summary is a way to push the meaningful part of the
 * request off the bottom of the dialog, so length and count are capped and structured values
 * are reduced to their type.</p>
 *
 * <p>Only the summariser is covered here. Showing the dialog itself needs an FX toolkit, and
 * the surrounding decision logic — timeout denies, dismiss denies, which button maps to which
 * answer — is exercised through {@code LauncherMcpAuthorizerTest}'s stub prompt.</p>
 */
class FxConsentPromptTest
{
    @Test
    void anEmptyArgumentObjectSaysSoRatherThanRenderingNothing()
    {
        assertFalse( FxConsentPrompt.summarizeArguments( new JsonObject() ).isBlank() );
        assertFalse( FxConsentPrompt.summarizeArguments( null ).isBlank() );
    }

    @Test
    void argumentsAreRenderedAsKeyValuePairs()
    {
        JsonObject arguments = new JsonObject();
        arguments.addProperty( "friendlyName", "All the Mods 9" );
        assertEquals( "friendlyName=All the Mods 9", FxConsentPrompt.summarizeArguments( arguments ) );
    }

    @Test
    void severalArgumentsAreSeparated()
    {
        JsonObject arguments = new JsonObject();
        arguments.addProperty( "url", "https://example.test/pack.json" );
        arguments.addProperty( "confirm", true );
        String summary = FxConsentPrompt.summarizeArguments( arguments );
        assertTrue( summary.contains( "url=https://example.test/pack.json" ), summary );
        assertTrue( summary.contains( "confirm=true" ), summary );
    }

    /**
     * The bound that matters: a long value cannot push the rest of the dialog out of view.
     */
    @Test
    void aLongValueIsTruncated()
    {
        JsonObject arguments = new JsonObject();
        arguments.addProperty( "url", "x".repeat( 5_000 ) );
        String summary = FxConsentPrompt.summarizeArguments( arguments );
        assertTrue( summary.length() < 200, "summary was " + summary.length() + " characters" );
        assertTrue( summary.endsWith( "…" ), summary );
    }

    @Test
    void tooManyArgumentsAreElided()
    {
        JsonObject arguments = new JsonObject();
        for ( int i = 0; i < 40; i++ ) {
            arguments.addProperty( "key" + i, "value" + i );
        }
        String summary = FxConsentPrompt.summarizeArguments( arguments );
        assertTrue( summary.endsWith( "…" ), summary );
        assertTrue( summary.length() < 300, "summary was " + summary.length() + " characters" );
    }

    /**
     * Structured values are reduced to their type rather than expanded. A nested object is
     * where an unbounded renderer would blow up in size, and its contents are not what the
     * user needs in order to decide.
     */
    @Test
    void structuredValuesAreReducedToTheirType()
    {
        JsonObject nested = new JsonObject();
        nested.addProperty( "secret", "should not be rendered" );
        JsonArray list = new JsonArray();
        list.add( 1 );
        list.add( 2 );

        JsonObject arguments = new JsonObject();
        arguments.add( "options", nested );
        arguments.add( "ids", list );

        String summary = FxConsentPrompt.summarizeArguments( arguments );
        assertTrue( summary.contains( "options={object}" ), summary );
        assertTrue( summary.contains( "ids=[2 items]" ), summary );
        assertFalse( summary.contains( "should not be rendered" ), summary );
    }

    @Test
    void aJsonNullValueRendersAsNull()
    {
        JsonObject arguments = new JsonObject();
        arguments.add( "maybe", com.google.gson.JsonNull.INSTANCE );
        assertEquals( "maybe=null", FxConsentPrompt.summarizeArguments( arguments ) );
    }

    /**
     * The summary is a single line. A value carrying newlines must not be able to fabricate
     * extra lines of dialog text around the real request.
     */
    @Test
    void theSummaryStaysBoundedEvenWithNewlinesInAValue()
    {
        JsonObject arguments = new JsonObject();
        arguments.addProperty( "note", "line one\nAllow this? yes\nline three" );
        String summary = FxConsentPrompt.summarizeArguments( arguments );
        assertTrue( summary.length() < 200, summary );
    }
}
