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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link UrlValidation} — the scheme gate every remote-manifest-supplied
 * URL (news links, pack website links, image URLs) is funneled through before
 * it can reach {@code Desktop.browse(...)} or an image loader. If this gate
 * ever widened to accept a non-http(s) scheme, a hostile modpack manifest
 * could smuggle a {@code file://}, {@code javascript:}, or custom-handler URL
 * into a "click this link" surface and have it opened as if it were an
 * ordinary web page.
 *
 * @since 3.0
 */
class UrlValidationTest
{
    @Test
    void acceptsHttpsUrl()
    {
        assertTrue( UrlValidation.isHttpUrl( "https://example.com/page" ) );
        assertEquals( "https://example.com/page", UrlValidation.sanitizedHttpUrl( "https://example.com/page" ) );
    }

    @Test
    void acceptsHttpUrl()
    {
        assertTrue( UrlValidation.isHttpUrl( "http://example.com" ) );
    }

    @Test
    void schemeMatchIsCaseInsensitive()
    {
        assertTrue( UrlValidation.isHttpUrl( "HTTPS://example.com" ) );
        assertTrue( UrlValidation.isHttpUrl( "HtTp://example.com" ) );
    }

    @Test
    void trimsSurroundingWhitespaceBeforeParsing()
    {
        assertEquals( "https://example.com", UrlValidation.sanitizedHttpUrl( "   https://example.com  " ) );
    }

    @Test
    void rejectsFileScheme()
    {
        assertFalse( UrlValidation.isHttpUrl( "file:///etc/passwd" ) );
        assertNull( UrlValidation.sanitizedHttpUrl( "file:///etc/passwd" ) );
    }

    @Test
    void rejectsJavascriptScheme()
    {
        assertFalse( UrlValidation.isHttpUrl( "javascript:alert(1)" ) );
    }

    @Test
    void rejectsCustomHandlerScheme()
    {
        // A malicious manifest could point a "website" field at a registered
        // OS URI handler (e.g. mmcl://, ms-settings:, steam://) hoping the
        // launcher's link-open code treats any non-blank string as safe.
        assertFalse( UrlValidation.isHttpUrl( "mmcl://play?name=x" ) );
    }

    @Test
    void rejectsSchemelessUrl()
    {
        assertFalse( UrlValidation.isHttpUrl( "example.com/page" ) );
        assertFalse( UrlValidation.isHttpUrl( "//example.com/page" ) );
    }

    @Test
    void rejectsNullAndBlank()
    {
        assertFalse( UrlValidation.isHttpUrl( null ) );
        assertFalse( UrlValidation.isHttpUrl( "" ) );
        assertFalse( UrlValidation.isHttpUrl( "   " ) );
        assertNull( UrlValidation.sanitizedHttpUrl( null ) );
        assertNull( UrlValidation.sanitizedHttpUrl( "" ) );
    }

    @Test
    void rejectsMalformedUri()
    {
        // An unescaped space inside the URI makes URI.create throw
        // IllegalArgumentException; the gate must swallow it and reject rather
        // than propagate.
        assertFalse( UrlValidation.isHttpUrl( "https://exa mple.com/page with space" ) );
    }
}
