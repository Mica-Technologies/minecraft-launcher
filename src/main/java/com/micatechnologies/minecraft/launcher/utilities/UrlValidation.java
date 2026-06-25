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

import java.net.URI;

/**
 * Shared validation for user/manifest-supplied URLs. The launcher treats every
 * URL that arrives from a remote manifest (news links, pack links, website
 * URLs) as untrusted: only {@code http} and {@code https} are honoured, so a
 * hostile manifest can't smuggle {@code file:}, {@code javascript:}, or other
 * schemes into {@code Desktop.browse(...)} and open local resources.
 *
 * <p>This centralizes the {@code URI.create(...)} + scheme-gate idiom that was
 * previously copy-pasted into {@code NewsItem.getUrl}, {@code LinkItem.getUrl},
 * and several inline {@code startsWith("https")} checks across the GUI.</p>
 *
 * @author Mica Technologies
 * @since 2026.6
 */
public final class UrlValidation
{
    private UrlValidation() { /* static utility */ }

    /**
     * Parses and normalizes a candidate URL, returning it only when it is a
     * syntactically valid {@code http} or {@code https} URL.
     *
     * @param raw candidate URL (may be {@code null}, blank, or malformed)
     *
     * @return the normalized URL string when {@code raw} parses as an
     *         {@code http}/{@code https} URI; {@code null} otherwise
     *
     * @since 2026.6
     */
    public static String sanitizedHttpUrl( String raw )
    {
        if ( raw == null || raw.isBlank() ) {
            return null;
        }
        try {
            URI parsed = URI.create( raw.trim() );
            String scheme = parsed.getScheme();
            if ( scheme != null
                    && ( scheme.equalsIgnoreCase( "http" ) || scheme.equalsIgnoreCase( "https" ) ) ) {
                return parsed.toString();
            }
        }
        catch ( IllegalArgumentException ignored ) {
            // Malformed URI — treat as no usable URL.
        }
        return null;
    }

    /**
     * Returns {@code true} when {@code raw} is a syntactically valid
     * {@code http}/{@code https} URL.
     *
     * @param raw candidate URL (may be {@code null})
     *
     * @return whether {@code raw} is a usable http(s) URL
     *
     * @since 2026.6
     */
    public static boolean isHttpUrl( String raw )
    {
        return sanitizedHttpUrl( raw ) != null;
    }
}
