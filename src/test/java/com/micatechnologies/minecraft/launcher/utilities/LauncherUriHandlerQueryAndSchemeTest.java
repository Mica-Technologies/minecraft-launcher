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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link LauncherUriHandler#isLauncherUri(String)},
 * {@link LauncherUriHandler#parseQuery(String)}, and
 * {@link LauncherUriHandler#hostOf(String)} — the scheme-sniff and
 * query-string decoder that sit in front of every {@code mmcl://} deep link,
 * whether it arrives via the OS URI-handler registration or is forwarded
 * over the single-instance IPC socket from a second launcher process.
 * {@link LauncherUriHandlerClassifyInstallUrlTest} already covers the
 * install-URL trust decision downstream of these; this class covers the
 * parsing step that decision depends on. {@code parseQuery} in particular
 * feeds directly into {@code handlePlay}/{@code handleJoin}'s pack-name and
 * URL arguments, so a decoding bug here (dropped parameter, wrong value)
 * would misroute or corrupt a deep-linked launch/join/install request.
 *
 * @since 3.0
 */
class LauncherUriHandlerQueryAndSchemeTest
{
    // =========================================================================================
    //  isLauncherUri
    // =========================================================================================

    @Test
    void recognizesLowercaseScheme()
    {
        assertTrue( LauncherUriHandler.isLauncherUri( "mmcl://play?name=x" ) );
    }

    @Test
    void schemeMatchIsCaseInsensitive()
    {
        assertTrue( LauncherUriHandler.isLauncherUri( "MMCL://play?name=x" ) );
        assertTrue( LauncherUriHandler.isLauncherUri( "Mmcl://Play" ) );
    }

    @Test
    void rejectsOtherSchemes()
    {
        assertFalse( LauncherUriHandler.isLauncherUri( "https://example.com" ) );
        assertFalse( LauncherUriHandler.isLauncherUri( "mmclx://play" ) );
    }

    @Test
    void rejectsNullAndBlank()
    {
        assertFalse( LauncherUriHandler.isLauncherUri( null ) );
        assertFalse( LauncherUriHandler.isLauncherUri( "" ) );
    }

    @Test
    void rejectsSchemeWithoutSlashSlash()
    {
        assertFalse( LauncherUriHandler.isLauncherUri( "mmcl:play" ) );
    }

    // =========================================================================================
    //  parseQuery
    // =========================================================================================

    @Test
    void parsesSingleKeyValuePair()
    {
        assertEquals( Map.of( "name", "MyPack" ), LauncherUriHandler.parseQuery( "name=MyPack" ) );
    }

    @Test
    void parsesMultiplePairsSeparatedByAmpersand()
    {
        assertEquals( Map.of( "name", "MyPack", "version", "2" ),
                      LauncherUriHandler.parseQuery( "name=MyPack&version=2" ) );
    }

    @Test
    void urlDecodesBothKeysAndValues()
    {
        // "%20" -> space, "+"-as-space is NOT applied by URLDecoder's default form
        // semantics here since query keys/values commonly percent-encode spaces.
        assertEquals( Map.of( "url", "https://example.com/a b" ),
                      LauncherUriHandler.parseQuery( "url=https%3A%2F%2Fexample.com%2Fa%20b" ) );
    }

    @Test
    void treatsKeyWithNoEqualsSignAsEmptyValue()
    {
        assertEquals( Map.of( "flag", "" ), LauncherUriHandler.parseQuery( "flag" ) );
    }

    @Test
    void emptyAndNullQueryReturnEmptyMap()
    {
        assertTrue( LauncherUriHandler.parseQuery( "" ).isEmpty() );
        assertTrue( LauncherUriHandler.parseQuery( null ).isEmpty() );
    }

    @Test
    void skipsEmptySegmentsFromDoubleAmpersand()
    {
        assertEquals( Map.of( "a", "1", "b", "2" ), LauncherUriHandler.parseQuery( "a=1&&b=2" ) );
    }

    @Test
    void laterDuplicateKeyOverwritesEarlierOne()
    {
        assertEquals( Map.of( "name", "second" ), LauncherUriHandler.parseQuery( "name=first&name=second" ) );
    }

    // =========================================================================================
    //  hostOf
    // =========================================================================================

    @Test
    void hostOfReturnsHostForWellFormedUrl()
    {
        assertEquals( "example.com", LauncherUriHandler.hostOf( "https://example.com/pack.json" ) );
    }

    @Test
    void hostOfFallsBackToRawUrlOnParseFailure()
    {
        String malformed = "https://exa mple.com/x with space";
        assertEquals( malformed, LauncherUriHandler.hostOf( malformed ) );
    }

    @Test
    void hostOfFallsBackToRawUrlWhenHostIsAbsent()
    {
        String noHost = "mailto:someone@example.com";
        assertEquals( noHost, LauncherUriHandler.hostOf( noHost ) );
    }
}
