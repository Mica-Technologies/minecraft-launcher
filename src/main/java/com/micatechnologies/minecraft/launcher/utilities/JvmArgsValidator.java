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

/**
 * Validation for the user-configurable custom JVM arguments string. Shared so
 * the write path (Settings → save) and the read path (launch) enforce the same
 * rules — the read path matters because the value can also reach
 * {@code configuration.json} out-of-band (a hand edit, a sync tool restoring a
 * tampered file, same-user malware), bypassing the setter.
 *
 * <p>Two rejection rules:</p>
 * <ul>
 *   <li><b>Control characters</b> (below {@code 0x20}, plus DEL {@code 0x7F}).
 *       The launch pipeline tokenises the args by whitespace, so a newline / tab
 *       in a multi-line paste could smuggle extra command-line arguments.</li>
 *   <li><b>{@code ${...}} placeholder syntax.</b> The launcher runs token
 *       templating <em>after</em> appending the custom args, so a literal
 *       {@code ${auth_access_token}} would expand into argv and leak the live
 *       Microsoft token (readable via process listings).</li>
 * </ul>
 *
 * @since 2026.6
 */
public final class JvmArgsValidator
{
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private JvmArgsValidator() { /* static-only */ }

    /** The {@code ${...}} placeholder opener the launch templating expands. */
    public static final String PLACEHOLDER_OPEN = "${";

    /**
     * Returns {@code jvmArgs} unchanged when it is clean, throwing
     * {@link IllegalArgumentException} otherwise. {@code null} normalises to the
     * empty string. Use on the write path so invalid input is rejected at the
     * source with a clear message.
     *
     * @param jvmArgs the JVM arguments string to validate
     * @return the original JVM arguments string if it is clean
     * @throws IllegalArgumentException if the JVM arguments contain control characters or placeholder syntax
     */
    public static String requireClean( String jvmArgs )
    {
        if ( jvmArgs == null ) {
            return "";
        }
        int badChar = firstControlCharIndex( jvmArgs );
        if ( badChar >= 0 ) {
            throw new IllegalArgumentException(
                    "Custom JVM args contain a control character at position " + badChar );
        }
        if ( jvmArgs.contains( PLACEHOLDER_OPEN ) ) {
            throw new IllegalArgumentException(
                    "Custom JVM args may not contain '${...}' placeholder syntax." );
        }
        return jvmArgs;
    }

    /**
     * Pure predicate form of {@link #requireClean} for the read path, which
     * wants to silently fall back to a default rather than throw. {@code null} /
     * empty is clean.
     *
     * @param jvmArgs the JVM arguments string to validate
     * @return true if the JVM arguments are clean, false otherwise
     */
    public static boolean isClean( String jvmArgs )
    {
        if ( jvmArgs == null || jvmArgs.isEmpty() ) {
            return true;
        }
        return firstControlCharIndex( jvmArgs ) < 0 && !jvmArgs.contains( PLACEHOLDER_OPEN );
    }

    /**
     * Index of the first control / DEL character, or {@code -1} if none.
     *
     * @param s the string to search for control characters
     * @return the index of the first control character, or -1 if no control characters are found
     */
    private static int firstControlCharIndex( String s )
    {
        for ( int i = 0; i < s.length(); i++ ) {
            char c = s.charAt( i );
            if ( c < 0x20 || c == 0x7F ) {
                return i;
            }
        }
        return -1;
    }
}
