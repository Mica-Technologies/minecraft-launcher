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

package com.micatechnologies.minecraft.launcher.consts.localization;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test helper that checks a message came from a given localization key, in whatever language
 * the test JVM's bundle resolves to.
 *
 * <p>Error messages are localized when they are built, so a test that matched English text
 * would fail on a machine whose default locale isn't English. Matching against the active
 * template instead keeps such tests locale-independent: each {@code {n}} placeholder in the
 * template matches any text, and everything else must appear literally.</p>
 *
 * @since 2026.10
 */
public final class LocalizedMessages
{
    private static final Pattern PLACEHOLDER = Pattern.compile( "\\{\\d+[^}]*}" );

    private LocalizedMessages() { }

    /**
     * Returns whether {@code actual} is the template for {@code key} with some arguments filled in.
     *
     * @param key    the localization key
     * @param actual the message to check
     *
     * @return {@code true} when the message matches the key's template in the active locale
     *
     * @since 2026.10
     */
    public static boolean matches( String key, String actual )
    {
        if ( actual == null ) return false;
        String[] literals = PLACEHOLDER.split( LocalizationManager.get( key ), -1 );
        StringBuilder regex = new StringBuilder( "(?s)" );
        for ( int i = 0; i < literals.length; i++ ) {
            if ( i > 0 ) regex.append( ".*" );
            regex.append( Pattern.quote( literals[ i ] ) );
        }
        return Pattern.matches( regex.toString(), actual );
    }

    /**
     * Asserts that {@code actual} is the template for {@code key} with some arguments filled in.
     *
     * @param key    the localization key
     * @param actual the message to check
     *
     * @since 2026.10
     */
    public static void assertFromKey( String key, String actual )
    {
        assertTrue( matches( key, actual ),
                    () -> "Expected a message built from \"" + key + "\" but got: " + actual );
    }
}
