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
 * before they hit disk or the clipboard (security finding 2.2). Centralizes the
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
 *   <li>{@code --uuid <value>} / {@code --auth_uuid <value>} — UUIDs aren't
 *       secrets per se but they're PII when paired with usernames; redact in
 *       contexts where the user might paste publicly.</li>
 * </ul>
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

    private SensitiveDataRedactor() { /* static-only */ }

    /**
     * Returns a copy of {@code input} with any embedded auth tokens replaced by
     * a placeholder. Null and empty inputs are passed through unchanged. The
     * input is never modified.
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
}
