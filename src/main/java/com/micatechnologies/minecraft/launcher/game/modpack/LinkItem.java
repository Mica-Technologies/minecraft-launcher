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

package com.micatechnologies.minecraft.launcher.game.modpack;

/**
 * A single per-pack link, deserialized from the {@code packLinks} array in a
 * mod pack's manifest JSON. Like {@link NewsItem}, links ride along on the
 * manifest the launcher already downloads and hash-verifies every session — a
 * pack author publishes a curated set of links (wiki, Discord, store page,
 * issue tracker, …) by editing the manifest they already host, with no extra
 * infrastructure.
 *
 * <h3>Manifest shape</h3>
 *
 * <pre>
 * "packLinks": [
 *   {
 *     "title":       "Wiki",
 *     "url":         "https://example.com/wiki",
 *     "description": "Guides, recipes, and progression help."
 *   }
 * ]
 * </pre>
 *
 * <h3>Untrusted content</h3>
 *
 * <p>Link fields are author-supplied remote content, so accessors are
 * defensive: text is returned verbatim but rendered as plain text only, and
 * {@link #getUrl()} returns a value only when it parses as an {@code http} /
 * {@code https} URL — the same gate {@link NewsItem} applies.</p>
 *
 * @author Mica Technologies
 * @since 2026.6
 */
public final class LinkItem
{
    /** Upper bound on rendered title length. */
    private static final int MAX_TITLE_LEN = 120;

    /** Upper bound on rendered description length. */
    private static final int MAX_DESC_LEN = 500;

    // region GSON-deserialized fields (must match JSON keys exactly)

    /** Display label for the link. Required for the item to render. */
    @SuppressWarnings( "unused" )
    public String title;

    /** Target URL. Required, and only honoured when it parses as an
     *  {@code http}/{@code https} URL. */
    @SuppressWarnings( "unused" )
    public String url;

    /** Optional one-line description shown under the title. */
    @SuppressWarnings( "unused" )
    public String description;

    // endregion

    /** Returns the trimmed, length-clamped title, or {@code null} when blank. */
    public String getTitle()
    {
        return clamp( title, MAX_TITLE_LEN );
    }

    /** Returns the trimmed, length-clamped description, or {@code null} when blank. */
    public String getDescription()
    {
        return clamp( description, MAX_DESC_LEN );
    }

    /**
     * Returns the target URL only when it parses as an {@code http} or
     * {@code https} URL; {@code null} otherwise (including {@code file:},
     * {@code javascript:}, or malformed values supplied by a remote manifest).
     */
    public String getUrl()
    {
        if ( url == null || url.isBlank() ) {
            return null;
        }
        try {
            java.net.URI parsed = java.net.URI.create( url.trim() );
            String scheme = parsed.getScheme();
            if ( scheme != null
                    && ( scheme.equalsIgnoreCase( "http" ) || scheme.equalsIgnoreCase( "https" ) ) ) {
                return parsed.toString();
            }
        }
        catch ( IllegalArgumentException ignored ) {
            // Fall through — malformed URI, treat as no link.
        }
        return null;
    }

    /** True when this link has both a non-blank title and a valid http(s) URL —
     *  a link without a usable target is dropped rather than rendered dead. */
    public boolean isRenderable()
    {
        return getTitle() != null && getUrl() != null;
    }

    /** Trim + length-clamp helper; returns {@code null} for blank input. */
    private static String clamp( String value, int max )
    {
        if ( value == null ) {
            return null;
        }
        String trimmed = value.trim();
        if ( trimmed.isEmpty() ) {
            return null;
        }
        return trimmed.length() > max ? trimmed.substring( 0, max ) : trimmed;
    }
}
