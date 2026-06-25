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

import com.jthemedetecor.OsThemeDetector;
import de.jangassen.jfa.foundation.Foundation;
import org.apache.commons.lang3.SystemUtils;

import java.util.Locale;

/**
 * Single, NULL-safe entry point for the "is the OS currently in dark mode?"
 * probe used across the GUI to pick light/dark token sheets and window chrome.
 *
 * <p>Previously every screen carried its own {@code try { OsThemeDetector
 * .getDetector().isDark() } catch ( Throwable ) { return true }} wrapper. On
 * macOS that path is buggy: {@code jSystemThemeDetector} 3.8 reads the
 * {@code AppleInterfaceStyle} user-default and feeds it straight into a regex
 * matcher, but that key is <em>absent</em> while macOS is in Light mode, so the
 * library hands {@code null} to {@code Pattern.matcher(...)} and throws an
 * {@link NullPointerException}. The library catches it internally, logs it at
 * <strong>ERROR</strong> with a full stack trace, and returns {@code false} —
 * so the result is correct but every theme application on a Light-mode Mac
 * spams the log. (See {@code com.jthemedetecor.MacOSThemeDetector#isDark}.)</p>
 *
 * <p>This helper sidesteps that on macOS by reading the same
 * {@code AppleInterfaceStyle} default directly through JFA (the very API the
 * library uses) and treating an absent value as Light — matching the library's
 * intended semantics without the noisy NPE. On Windows and Linux the library's
 * detector works fine, so we delegate to it.</p>
 *
 * @author Mica Technologies
 * @since 2026.2
 */
public class OsThemeUtilities
{
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private OsThemeUtilities() { /* static-only utility */ }

    /**
     * Determines if the operating system is currently in dark mode.
     *
     * @return {@code true} when the OS reports a dark appearance. On any
     *         detector failure the launcher's "when in doubt, assume dark"
     *         default is returned, except on macOS where an absent
     *         {@code AppleInterfaceStyle} unambiguously means Light.
     *
     * @since 2026.2
     */
    public static boolean isOsDark()
    {
        if ( SystemUtils.IS_OS_MAC ) {
            return isMacOsDark();
        }
        try {
            return OsThemeDetector.getDetector().isDark();
        }
        catch ( Throwable ignored ) {
            return true;
        }
    }

    /**
     * NULL-safe macOS dark-mode read. Mirrors {@code MacOSThemeDetector#isDark}
     * — {@code [NSUserDefaults standardUserDefaults] objectForKey:
     * "AppleInterfaceStyle"} matched against {@code .*dark.*} — but guards the
     * Light-mode case where the key (and therefore the returned string) is
     * {@code null}. Returns {@code false} (Light) for an absent value or any
     * JFA failure, which is exactly what the library returns on macOS today.
     */
    private static boolean isMacOsDark()
    {
        try {
            final Foundation.NSAutoreleasePool pool = new Foundation.NSAutoreleasePool();
            try {
                final var userDefaults = Foundation.invoke( "NSUserDefaults", "standardUserDefaults" );
                final String appleInterfaceStyle = Foundation.toStringViaUTF8(
                        Foundation.invoke( userDefaults, "objectForKey:",
                                           Foundation.nsString( "AppleInterfaceStyle" ) ) );
                return appleInterfaceStyle != null
                        && appleInterfaceStyle.toLowerCase( Locale.ROOT ).contains( "dark" );
            }
            finally {
                pool.drain();
            }
        }
        catch ( Throwable ignored ) {
            return false;
        }
    }
}
