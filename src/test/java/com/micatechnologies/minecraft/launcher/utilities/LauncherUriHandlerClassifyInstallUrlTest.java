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

import static com.micatechnologies.minecraft.launcher.utilities.LauncherUriHandler.InstallUrlVerdict.ACCEPT_TRUSTED;
import static com.micatechnologies.minecraft.launcher.utilities.LauncherUriHandler.InstallUrlVerdict.REJECT;
import static com.micatechnologies.minecraft.launcher.utilities.LauncherUriHandler.InstallUrlVerdict.REQUIRE_CONFIRMATION;
import static com.micatechnologies.minecraft.launcher.utilities.LauncherUriHandler.classifyInstallUrl;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link LauncherUriHandler#classifyInstallUrl(String)} — the gate that
 * decides whether an {@code mmcl://add} / {@code mmcl://join} URL coming from an
 * external source (a website link, a Discord "Join Game" invite, or an IPC
 * forward from a second launcher process) installs silently, prompts the user
 * first, or is refused outright.
 *
 * <p>This is the launcher's primary defense against a phishing link shaped like
 * {@code mmcl://add?url=http://attacker/x.json} silently installing arbitrary
 * mod code, and against header/CRLF-smuggling tricks riding along in the URL
 * string. A regression here could either annoy every legitimate user with an
 * unnecessary confirmation prompt (host classification bug) or, far worse,
 * let a malicious URL sail through as {@code ACCEPT_TRUSTED} / skip the
 * REJECT gate entirely (a real security hole). Testing the classifier
 * directly — rather than only through {@code confirmInstallUrl}, which pops a
 * JavaFX dialog — lets us pin down every branch without a display.</p>
 */
class LauncherUriHandlerClassifyInstallUrlTest
{
    // ===== REJECT: missing / blank input =====

    @Test
    void nullUrlIsRejected()
    {
        assertEquals( REJECT, classifyInstallUrl( null ) );
    }

    @Test
    void emptyUrlIsRejected()
    {
        assertEquals( REJECT, classifyInstallUrl( "" ) );
    }

    @Test
    void blankUrlIsRejected()
    {
        assertEquals( REJECT, classifyInstallUrl( "   " ) );
    }

    // ===== REJECT: control characters embedded in the URL =====

    @Test
    void embeddedControlCharacterBelowSpaceIsRejected()
    {
        // A raw newline is the classic header/response-smuggling payload.
        assertEquals( REJECT, classifyInstallUrl( "https://micauseaststorage.blob.core.windows.net/x\n.json" ) );
    }

    @Test
    void embeddedCarriageReturnIsRejected()
    {
        assertEquals( REJECT, classifyInstallUrl( "https://example.com/x\r.json" ) );
    }

    @Test
    void embeddedNulByteIsRejected()
    {
        assertEquals( REJECT, classifyInstallUrl( "https://example.com/x\u0000.json" ) );
    }

    @Test
    void embeddedDelCharacterIsRejected()
    {
        // 0x7F (DEL) is explicitly checked alongside the < 0x20 control range.
        assertEquals( REJECT, classifyInstallUrl( "https://example.com/x\u007F.json" ) );
    }

    // ===== REJECT: malformed URI =====

    @Test
    void malformedUriIsRejected()
    {
        // An unescaped space is not a legal URI and URI.create throws.
        assertEquals( REJECT, classifyInstallUrl( "https://example.com/not a valid uri" ) );
    }

    // ===== REJECT: wrong or missing scheme =====

    @Test
    void httpSchemeIsRejected()
    {
        // Only https is trusted for a manifest install — http allows a
        // network-position attacker to tamper with the payload in transit.
        assertEquals( REJECT, classifyInstallUrl( "http://example.com/manifest.json" ) );
    }

    @Test
    void schemelessUrlIsRejected()
    {
        assertEquals( REJECT, classifyInstallUrl( "example.com/manifest.json" ) );
    }

    @Test
    void nonHttpSchemeIsRejected()
    {
        assertEquals( REJECT, classifyInstallUrl( "ftp://example.com/manifest.json" ) );
    }

    @Test
    void schemeCaseIsIgnoredForAcceptance()
    {
        // equalsIgnoreCase means HTTPS / Https etc. all pass the scheme check.
        assertEquals( REQUIRE_CONFIRMATION, classifyInstallUrl( "HTTPS://example.com/manifest.json" ) );
    }

    // ===== REJECT: missing host =====

    @Test
    void missingHostIsRejected()
    {
        // "https:///path" parses with a null host.
        assertEquals( REJECT, classifyInstallUrl( "https:///manifest.json" ) );
    }

    // ===== ACCEPT_TRUSTED: allowlisted host =====

    @Test
    void trustedHostIsAcceptedSilently()
    {
        assertEquals( ACCEPT_TRUSTED,
                classifyInstallUrl( "https://micauseaststorage.blob.core.windows.net/packs/manifest.json" ) );
    }

    @Test
    void trustedHostMatchIsCaseInsensitive()
    {
        assertEquals( ACCEPT_TRUSTED,
                classifyInstallUrl( "https://MICAUSEASTSTORAGE.BLOB.CORE.WINDOWS.NET/packs/manifest.json" ) );
    }

    // ===== REQUIRE_CONFIRMATION: well-formed https, unknown host =====

    @Test
    void ordinaryUnknownHttpsHostRequiresConfirmation()
    {
        assertEquals( REQUIRE_CONFIRMATION, classifyInstallUrl( "https://example.com/manifest.json" ) );
    }

    @Test
    void similarButNotExactHostRequiresConfirmation()
    {
        // A near-miss host (typosquat / subdomain trick) must NOT be treated as
        // trusted — only an exact host-string match on the allowlist qualifies.
        assertEquals( REQUIRE_CONFIRMATION,
                classifyInstallUrl( "https://evil.micauseaststorage.blob.core.windows.net.attacker.com/x.json" ) );
    }
}
