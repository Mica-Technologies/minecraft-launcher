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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/**
 * A single per-pack news entry, deserialized from the {@code packNews} array
 * in a mod pack's manifest JSON. The news system rides along on the manifest
 * the launcher already downloads and hash-verifies every session, so a pack
 * author publishes news simply by editing the manifest they already host — no
 * separate feed, API, or server infrastructure is involved.
 *
 * <h3>Manifest shape</h3>
 *
 * <pre>
 * "packNews": [
 *   {
 *     "id":      "2026-06-summer-event",
 *     "title":   "Summer Build Contest",
 *     "body":    "Submit your builds before July 1st for a chance to win...",
 *     "date":    "2026-06-20",
 *     "type":    "event",
 *     "url":     "https://example.com/news/summer-contest",
 *     "pinned":  false,
 *     "expires": "2026-07-02"
 *   }
 * ]
 * </pre>
 *
 * <h3>Untrusted content</h3>
 *
 * <p>News fields are author-supplied remote content, so every accessor is
 * defensive: text is returned verbatim but rendered as plain text only (never
 * interpreted as markup), lengths are clamped, the {@link #getUrl() link} is
 * only returned when it parses as an {@code http}/{@code https} URL, and the
 * {@code date}/{@code expires} fields fall back gracefully when malformed —
 * the same posture {@code GameModPackMetadata#getPackMinRAMGB} uses for the
 * raw RAM string.</p>
 *
 * @author Mica Technologies
 * @since 2026.6
 */
public final class NewsItem
{
    /** Upper bound on rendered title length — a runaway manifest title can't
     *  blow out the modal layout. */
    private static final int MAX_TITLE_LEN = 200;

    /** Upper bound on rendered body length. Generous enough for a paragraph or
     *  two; authors wanting more should link out via {@link #url}. */
    private static final int MAX_BODY_LEN = 2000;

    // region GSON-deserialized fields (must match JSON keys exactly)

    /** Stable identifier used for local read/unread tracking. Optional, but
     *  items without an id can't be tracked as "read" and so never contribute
     *  to the unread badge — authors who want the badge should set a stable,
     *  unique id per item. */
    @SuppressWarnings( "unused" )
    public String id;

    /** Headline shown in bold. Required for the item to render. */
    @SuppressWarnings( "unused" )
    public String title;

    /** Plain-text body. Optional — a title-only item is valid. */
    @SuppressWarnings( "unused" )
    public String body;

    /** Publication date, ISO-8601 (either a {@code yyyy-MM-dd} local date or a
     *  full offset date-time). Optional; drives newest-first sorting and the
     *  displayed date. */
    @SuppressWarnings( "unused" )
    public String date;

    /** One of {@code info} / {@code update} / {@code warning} / {@code event}
     *  (case-insensitive). Anything else — including absent — maps to
     *  {@link Type#INFO}. Drives the leading glyph and chip colour. */
    @SuppressWarnings( "unused" )
    public String type;

    /** Optional "Read more" link. Only honoured when it parses as an
     *  {@code http}/{@code https} URL. */
    @SuppressWarnings( "unused" )
    public String url;

    /** When true, the item floats above unpinned items regardless of date. */
    @SuppressWarnings( "unused" )
    public boolean pinned;

    /** Optional expiry date (same formats as {@link #date}). Once the current
     *  date is on or after this, the item is hidden from the UI. */
    @SuppressWarnings( "unused" )
    public String expires;

    // endregion

    /**
     * Category of a news item — selects the leading glyph and the CSS chip
     * style class used when rendering. Kept text-free so it needs no
     * localization: the glyph is a Unicode symbol and the colour comes from
     * the existing stat-chip styles.
     */
    public enum Type
    {
        INFO( "ℹ", "stat-chip" ),          // ℹ
        UPDATE( "⬆", "stat-chip-success" ), // ⬆
        WARNING( "⚠", "stat-chip-warn" ),   // ⚠
        EVENT( "★", "stat-chip" );          // ★

        private final String glyph;
        private final String chipStyleClass;

        Type( String glyph, String chipStyleClass )
        {
            this.glyph = glyph;
            this.chipStyleClass = chipStyleClass;
        }

        /** Leading symbol shown before the title. */
        public String glyph()
        {
            return glyph;
        }

        /** CSS style class for the type chip (reuses existing stat-chip styles). */
        public String chipStyleClass()
        {
            return chipStyleClass;
        }
    }

    /**
     * Returns the parsed {@link Type}, defaulting to {@link Type#INFO} for an
     * absent or unrecognized {@code type} string.
     */
    public Type getType()
    {
        if ( type == null || type.isBlank() ) {
            return Type.INFO;
        }
        try {
            return Type.valueOf( type.trim().toUpperCase( Locale.ROOT ) );
        }
        catch ( IllegalArgumentException e ) {
            return Type.INFO;
        }
    }

    /** Returns the trimmed, length-clamped title, or {@code null} when blank. */
    public String getTitle()
    {
        return clamp( title, MAX_TITLE_LEN );
    }

    /** Returns the trimmed, length-clamped body, or {@code null} when blank. */
    public String getBody()
    {
        return clamp( body, MAX_BODY_LEN );
    }

    /** Returns the trimmed id, or {@code null} when blank. */
    public String getId()
    {
        return ( id == null || id.isBlank() ) ? null : id.trim();
    }

    /** Returns whether this item is pinned. */
    public boolean isPinned()
    {
        return pinned;
    }

    /**
     * Returns the "Read more" URL only when it parses as an {@code http} or
     * {@code https} URL; {@code null} otherwise (including for {@code file:},
     * {@code javascript:}, or otherwise malformed values supplied by a remote
     * manifest).
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

    /**
     * Returns true when this item should be hidden because its {@code expires}
     * date has arrived. An absent or unparseable {@code expires} never expires.
     *
     * @param today the current local date (injected so callers avoid a hidden
     *              clock dependency)
     */
    public boolean isExpired( LocalDate today )
    {
        LocalDate exp = parseDate( expires );
        return exp != null && !today.isBefore( exp );
    }

    /**
     * Returns this item's date as an epoch-second value for sorting, or
     * {@link Long#MIN_VALUE} when no parseable date is present (undated items
     * sort to the bottom).
     */
    public long getSortEpoch()
    {
        LocalDate d = parseDate( date );
        return d == null ? Long.MIN_VALUE : d.toEpochDay() * 86_400L;
    }

    /**
     * Returns a locale-formatted medium date string for display (e.g.
     * "Jun 20, 2026"), or {@code null} when the date is absent / unparseable.
     */
    public String getDisplayDate()
    {
        LocalDate d = parseDate( date );
        if ( d == null ) {
            return null;
        }
        return d.format( DateTimeFormatter.ofLocalizedDate( FormatStyle.MEDIUM )
                                          .withLocale( Locale.getDefault() ) );
    }

    /** True when this item has enough to render (a non-blank title). */
    public boolean isRenderable()
    {
        return getTitle() != null;
    }

    /** Lenient ISO date parse: accepts {@code yyyy-MM-dd} or a full offset
     *  date-time, returning the date component. {@code null} for blank /
     *  malformed input. */
    private static LocalDate parseDate( String raw )
    {
        if ( raw == null || raw.isBlank() ) {
            return null;
        }
        String s = raw.trim();
        try {
            return LocalDate.parse( s );
        }
        catch ( Exception ignored ) {
            // Not a plain date — try a full offset date-time next.
        }
        try {
            return OffsetDateTime.parse( s ).withOffsetSameInstant( ZoneOffset.UTC ).toLocalDate();
        }
        catch ( Exception ignored ) {
            return null;
        }
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
