/*
 * Copyright (c) 2021 Mica Technologies
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
 * Utility class for version string comparison. Extracted from {@link SystemUtilities} to separate version-related
 * concerns from general system utilities.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
public class VersionUtilities
{
    /**
     * Compares the specified version numbers and returns: 0 if {@code version1} and {@code version2} are equal, -1 if
     * {@code version1} is less than {@code version2}, or (+)1 if {@code version1} is greater than {@code version2}.
     * Version strings are expected to be dot-delimited (e.g., "1.2.3"). Pre-release suffixes (everything after the
     * first {@code -}) and SemVer build metadata (everything after the first {@code +}) are stripped before parsing,
     * which matches SemVer 2.0 §11 precedence rules (build metadata is ignored, and pre-release numeric prefixes are
     * compared). The strip is also what keeps dev / dirty / git-describe-derived versions like
     * {@code "2026.1.0-15-gabc1234.dirty"} from NumberFormatException-ing the per-segment Integer.parseInt — without
     * it, every dev build would silently disable update detection by tripping the outer try/catch in
     * {@code UpdateCheckManager.checkAndConfigureUI}.
     *
     * @param version1 first version number
     * @param version2 second version number
     *
     * @return 0 if equal, -1 if {@code version1} lower, 1 if {@code version1} higher
     *
     * @since 1.0
     */
    public static int compareVersionNumbers( String version1, String version2 )
    {
        String[] arr1 = stripSuffixes( version1 ).split( "\\." );
        String[] arr2 = stripSuffixes( version2 ).split( "\\." );

        int i = 0;
        while ( i < arr1.length || i < arr2.length ) {
            if ( i < arr1.length && i < arr2.length ) {
                if ( Integer.parseInt( arr1[ i ] ) < Integer.parseInt( arr2[ i ] ) ) {
                    return -1;
                }
                else if ( Integer.parseInt( arr1[ i ] ) > Integer.parseInt( arr2[ i ] ) ) {
                    return 1;
                }
            }
            else if ( i < arr1.length ) {
                if ( Integer.parseInt( arr1[ i ] ) != 0 ) {
                    return 1;
                }
            }
            else {
                if ( Integer.parseInt( arr2[ i ] ) != 0 ) {
                    return -1;
                }
            }

            i++;
        }

        return 0;
    }

    private static String stripSuffixes( String version )
    {
        int dash = version.indexOf( '-' );
        if ( dash >= 0 ) {
            version = version.substring( 0, dash );
        }
        int plus = version.indexOf( '+' );
        if ( plus >= 0 ) {
            version = version.substring( 0, plus );
        }
        return version.isEmpty() ? "0" : version;
    }
}
