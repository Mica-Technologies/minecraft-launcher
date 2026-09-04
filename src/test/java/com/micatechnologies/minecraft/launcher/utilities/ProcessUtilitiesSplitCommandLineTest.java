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

package com.micatechnologies.minecraft.launcher.utilities;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link ProcessUtilities#splitCommandLine(String)} — the hand-rolled
 * argv tokenizer behind the deprecated {@code executeStringCommand} path.
 * Every game-launch argument (including the pack's own display name and, on
 * older code paths, auth tokens) used to cross this parser before reaching
 * {@link ProcessBuilder}. A quoting bug here doesn't just mis-split
 * whitespace — the commit referenced in {@link ProcessUtilities}'s own
 * javadoc ({@code 32f58ca}) recounts a real pack-name injection that lived in
 * exactly this kind of string-rebuild step, which is why the modern
 * {@code launchCommand(List<String>, ...)} overload bypasses this parser
 * entirely for new call sites. This test pins the current tokenizer contract
 * so nobody has to rediscover its quirks (e.g. literal backslashes, empty
 * quoted tokens) by debugging a broken launch.
 *
 * @since 3.0
 */
class ProcessUtilitiesSplitCommandLineTest
{
    @Test
    void splitsOnPlainWhitespace()
    {
        assertEquals( List.of( "java", "-jar", "app.jar" ),
                      ProcessUtilities.splitCommandLine( "java -jar app.jar" ) );
    }

    @Test
    void collapsesRunsOfWhitespaceBetweenTokens()
    {
        assertEquals( List.of( "a", "b" ), ProcessUtilities.splitCommandLine( "a    b" ) );
    }

    @Test
    void keepsQuotedSegmentWithInternalSpacesAsOneToken()
    {
        assertEquals( List.of( "-Dname=My Pack", "-jar", "app.jar" ),
                      ProcessUtilities.splitCommandLine( "\"-Dname=My Pack\" -jar app.jar" ) );
    }

    @Test
    void preservesExplicitlyEmptyQuotedArgument()
    {
        // A length-only "is this token started" check would silently drop an
        // intentional "" argument; tokenStarted tracks quote-opening instead.
        assertEquals( List.of( "a", "", "b" ), ProcessUtilities.splitCommandLine( "a \"\" b" ) );
    }

    @Test
    void leavesBackslashesLiteralForWindowsPaths()
    {
        // Backslash is not an escape character in this parser — a Windows path
        // like "C:\dir\" must survive unmodified rather than being reinterpreted.
        List< String > result = ProcessUtilities.splitCommandLine( "\"C:\\dir\\\" -jar" );
        assertTrue( result.get( 0 ).contains( "C:\\dir\\" ) );
    }

    @Test
    void handlesEmptyString()
    {
        assertEquals( List.of(), ProcessUtilities.splitCommandLine( "" ) );
    }

    @Test
    void handlesWhitespaceOnlyString()
    {
        assertEquals( List.of(), ProcessUtilities.splitCommandLine( "   " ) );
    }

    @Test
    void trailingUnclosedQuoteStillEmitsFinalToken()
    {
        // No exception, no dropped final argument even when the quote never closes.
        assertEquals( List.of( "a", "b unclosed" ), ProcessUtilities.splitCommandLine( "a \"b unclosed" ) );
    }

    @Test
    void adjacentQuotedSegmentsJoinIntoOneToken()
    {
        // "foo""bar" (no space between the quoted segments) is one token per
        // the toggle-on-each-quote-char implementation.
        assertEquals( List.of( "foobar" ), ProcessUtilities.splitCommandLine( "\"foo\"\"bar\"" ) );
    }
}
