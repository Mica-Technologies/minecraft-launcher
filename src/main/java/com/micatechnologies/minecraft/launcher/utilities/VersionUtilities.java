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
            int a = i < arr1.length ? parseSegment( arr1[ i ] ) : 0;
            int b = i < arr2.length ? parseSegment( arr2[ i ] ) : 0;
            if      ( a < b ) return -1;
            else if ( a > b ) return  1;
            i++;
        }

        return 0;
    }

    /**
     * Parse one dot-segment to an int. Per-segment {@link Integer#parseInt}
     * was the original implementation but it threw on any non-numeric
     * content — Forge versions like {@code "2855_230729ER"} (the trailing
     * build-date suffix some modpack maintainers append) and prerelease
     * tags like {@code "rc3"} both surface as raw segments after
     * {@link #stripSuffixes} can't safely strip them (the build metadata
     * is wedged between numeric segments rather than after a recognised
     * separator). A thrown exception there propagated up to the FX
     * thread and broke main-menu card rebuilds.
     *
     * <p>Strategy: pull the leading digit run, parse that. {@code ""} or
     * pure-non-numeric falls back to {@code 0}. Loses fidelity vs a real
     * SemVer comparator on prerelease tags but never throws, and the
     * launcher's use case (is the installed pack older than the manifest
     * pack?) cares about ordinal precedence not lexicographic detail.</p>
     */
    private static int parseSegment( String s )
    {
        if ( s == null || s.isEmpty() ) return 0;
        int end = 0;
        while ( end < s.length() && Character.isDigit( s.charAt( end ) ) ) end++;
        if ( end == 0 ) return 0;
        try { return Integer.parseInt( s.substring( 0, end ) ); }
        catch ( NumberFormatException e ) { return 0; }
    }

    /**
     * Strips pre-release suffixes and build metadata from a version string.
     *
     * @param version the version string to strip
     *
     * @return the stripped version string
     */
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
        // Forge / modpack build-metadata convention: some maintainers
        // append _YYYYMMDDxx (build date + suffix tag) after a 4-part
        // version like 14.23.5.2855_230729ER. SemVer says metadata
        // hangs off `+`; Forge's tooling has used `_` for years
        // because Maven-coords don't allow `+`. Strip it the same way.
        int underscore = version.indexOf( '_' );
        if ( underscore >= 0 ) {
            version = version.substring( 0, underscore );
        }
        return version.isEmpty() ? "0" : version;
    }
}
