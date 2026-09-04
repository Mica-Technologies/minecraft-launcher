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

import java.util.regex.Pattern;

/**
 * Scrubs Minecraft / launcher access tokens from log lines and command strings
 * before they hit disk or the clipboard. Centralizes the
 * patterns so every place that surfaces text to the user runs the same filter.
 *
 * <p>Targets:
 * <ul>
 *   <li>{@code --accessToken <value>} / {@code --accessToken=<value>} — Mojang's
 *       modern CLI form.</li>
 *   <li>{@code --clientToken <value>} / {@code --clientToken=<value>} — the
 *       launcher's per-install identifier; not as catastrophic as an access
 *       token but still account-linked.</li>
 *   <li>{@code token:<token>:<uuid>} — the legacy session format used pre-1.6.
 *       Carries the same access token in a different syntax.</li>
 * </ul>
 *
 * <p>UUIDs ({@code --uuid} / {@code --auth_uuid}, and the trailing field of the
 * legacy {@code token:} form) are intentionally NOT redacted: a UUID is not a
 * credential, and the legacy pattern deliberately preserves it so the scrubbed
 * line still reads as a recognizable session string. Don't add UUID redaction
 * without reconciling that decision.</p>
 *
 * <p>Performance: regexes are compiled once and held in static fields, so the
 * per-line cost is one regex pass per pattern. The game console processes a
 * handful of MB of stdout per session — well under any noticeable overhead.
 *
 * @since 2026.2
 */
public final class SensitiveDataRedactor
{
    /** Replacement placeholder used in scrubbed output. */
    private static final String REDACTED = "[REDACTED]";

    /** Matches {@code --accessToken VALUE} and {@code --accessToken=VALUE}. Captures
     *  the flag form and equals separator so the replacement can preserve them. The
     *  value is any run of non-whitespace chars. */
    private static final Pattern ACCESS_TOKEN_PATTERN = Pattern.compile(
            "(--accessToken)([=\\s]+)\\S+" );

    /** Mirrors {@link #ACCESS_TOKEN_PATTERN} for the per-install client identifier. */
    private static final Pattern CLIENT_TOKEN_PATTERN = Pattern.compile(
            "(--clientToken)([=\\s]+)\\S+" );

    /** Legacy session token form: {@code token:<access-token>:<uuid>}. Keep the
     *  trailing UUID intact (it's not a credential by itself in this context;
     *  preserving it makes the redacted output more recognizable as a session
     *  string). The access-token portion is anything between the two colons. */
    private static final Pattern LEGACY_SESSION_PATTERN = Pattern.compile(
            "token:[A-Za-z0-9._-]+:([0-9a-fA-F-]{32,36})" );

    /** Canonical dashed UUID form (8-4-4-4-12). Word-boundary anchored so it can't
     *  match a slice out of a longer hex run. Used only by {@link #redactStrict}. */
    private static final Pattern UUID_DASHED_PATTERN = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b" );

    /** Undashed 32-hex UUID form, which is what Mojang's APIs and launch arguments
     *  actually use. Word-boundary anchored, so a 40-char SHA-1 is NOT matched (no
     *  boundary exists 32 chars in). A bare MD5 hash is indistinguishable from an
     *  undashed UUID at this layer and WILL be redacted — an accepted false positive,
     *  since {@link #redactStrict} exists precisely for contexts where over-redaction
     *  is preferable to leaking an identifier. Used only by {@link #redactStrict}. */
    private static final Pattern UUID_UNDASHED_PATTERN = Pattern.compile(
            "\\b[0-9a-fA-F]{32}\\b" );

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private SensitiveDataRedactor() { /* static-only */ }

    /**
     * Returns a copy of {@code input} with any embedded auth tokens replaced by
     * a placeholder. Null and empty inputs are passed through unchanged. The
     * input is never modified.
     *
     * @param input the string to be redacted
     * @return the redacted string, or the original input if it is null or empty
     */
    public static String redact( String input )
    {
        if ( input == null || input.isEmpty() ) {
            return input;
        }
        String out = ACCESS_TOKEN_PATTERN.matcher( input ).replaceAll( "$1$2" + REDACTED );
        out = CLIENT_TOKEN_PATTERN.matcher( out ).replaceAll( "$1$2" + REDACTED );
        out = LEGACY_SESSION_PATTERN.matcher( out ).replaceAll( "token:" + REDACTED + ":$1" );
        return out;
    }

    /**
     * Strict variant of {@link #redact(String)} that additionally removes account
     * UUIDs in both the dashed (8-4-4-4-12) and undashed 32-hex forms.
     *
     * <p><b>Why this is separate from {@link #redact(String)}:</b> the base method
     * deliberately preserves the trailing UUID of a legacy
     * {@code token:<access-token>:<uuid>} string, because in the game console and the
     * launch-command log that UUID is not a credential on its own and keeping it makes
     * the redacted line recognizable as a session string. That is still the right
     * behaviour for those surfaces, so it is left untouched.</p>
     *
     * <p>Some consumers have a stricter contract: no credential <i>or</i> identifying
     * account value may escape, under any circumstances. Those callers use this method
     * instead. It composes on top of {@link #redact(String)}, so the base patterns run
     * first and the UUID pass then also catches the legacy string's preserved tail.</p>
     *
     * <p>Over-redaction is the intended failure mode here — see
     * {@link #UUID_UNDASHED_PATTERN} for the one known false positive (a bare MD5).
     * Callers that need a readable hash in their output should use
     * {@link #redact(String)}.</p>
     *
     * @param input the string to be redacted
     *
     * @return the redacted string, or the original input if it is null or empty
     *
     * @since 2026.9
     */
    public static String redactStrict( String input )
    {
        if ( input == null || input.isEmpty() ) {
            return input;
        }
        String out = redact( input );
        out = UUID_DASHED_PATTERN.matcher( out ).replaceAll( REDACTED );
        out = UUID_UNDASHED_PATTERN.matcher( out ).replaceAll( REDACTED );
        return out;
    }
}
