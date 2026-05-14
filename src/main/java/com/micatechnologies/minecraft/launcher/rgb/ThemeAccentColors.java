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

package com.micatechnologies.minecraft.launcher.rgb;

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;

/**
 * Maps the launcher's user-facing theme name (as returned by
 * {@link ConfigManager#getTheme()}) to the same primary accent color
 * the matching {@code ui-tokens-*.css} file defines via
 * {@code -color-primary}.
 *
 * <p>The launcher's CSS architecture is the source of truth for what
 * the user sees on screen; this class mirrors those values so the
 * RGB subsystem can paint the user's hardware in the same accent
 * while they sit in the launcher menus. Keep this in sync with the
 * CSS token files if a theme's primary color ever changes — a CI
 * check could enforce that, but the colors are stable enough that
 * a comment + hand maintenance is acceptable for now.</p>
 *
 * <p>For the {@code "Automatic"} and {@code "Native (Mica)"} themes
 * (which switch tokens based on OS dark/light mode), this consults
 * {@code OsThemeDetector} to pick the right variant.</p>
 *
 * @since 2026.5
 */
public final class ThemeAccentColors
{
    private ThemeAccentColors() { /* static-only */ }

    // Values mirror the -color-primary entries in each
    // src/main/resources/ui/ui-tokens-*.css file. Hex codes copied
    // verbatim and converted to decimal channels here so the linkage
    // is grep-able both ways.
    private static final RgbColor DARK_PRIMARY         = new RgbColor( 0x6F, 0xCF, 0x3D );
    private static final RgbColor LIGHT_PRIMARY        = new RgbColor( 0x3C, 0x85, 0x27 );
    private static final RgbColor BLUEGRAY_PRIMARY     = new RgbColor( 0x06, 0x68, 0xE1 );
    private static final RgbColor CREEPER_PRIMARY      = new RgbColor( 0x43, 0xD2, 0x2D );
    private static final RgbColor ORANGEPURPLE_PRIMARY = new RgbColor( 0xD2, 0x57, 0xDB );

    /** Used as the safe fallback when the configured theme name doesn't
     *  match any known token file — picking the brand wordmark green
     *  rather than (say) plain white so the keyboard still feels on-
     *  brand even if a stale config picks an unknown theme. */
    private static final RgbColor FALLBACK_PRIMARY = DARK_PRIMARY;

    /**
     * Returns the accent {@link RgbColor} for whatever theme is currently
     * selected in {@link ConfigManager}. Theme name lookups are
     * case-sensitive (the constants in {@link ConfigConstants} are the
     * authoritative values).
     */
    public static RgbColor accentForCurrentTheme()
    {
        String theme = ConfigManager.getTheme();
        return accentForTheme( theme );
    }

    /** Theme-by-name lookup, exposed so callers (notably a test or a
     *  preview-of-a-different-theme path) can resolve without going
     *  through {@link ConfigManager}. */
    public static RgbColor accentForTheme( String theme )
    {
        if ( theme == null ) return FALLBACK_PRIMARY;
        return switch ( theme ) {
            case ConfigConstants.THEME_DARK          -> DARK_PRIMARY;
            case ConfigConstants.THEME_LIGHT         -> LIGHT_PRIMARY;
            case ConfigConstants.THEME_BLUE_GRAY     -> BLUEGRAY_PRIMARY;
            case ConfigConstants.THEME_CREEPER       -> CREEPER_PRIMARY;
            case ConfigConstants.THEME_ORANGE_PURPLE -> ORANGEPURPLE_PRIMARY;
            // Automatic + Native both pick dark vs light based on the
            // OS appearance — same brand green either way, but the
            // light-mode tokens use a slightly darker shade for AA
            // contrast on a white background.
            case ConfigConstants.THEME_NATIVE, ConfigConstants.THEME_AUTOMATIC ->
                    osIsDark() ? DARK_PRIMARY : LIGHT_PRIMARY;
            default -> FALLBACK_PRIMARY;
        };
    }

    /** OS dark-mode probe via {@code jthemedetecor}. Defaults to dark
     *  when the detector isn't usable on this platform — matches the
     *  rest of the launcher's "when in doubt, assume dark" default. */
    private static boolean osIsDark()
    {
        try {
            return com.jthemedetecor.OsThemeDetector.getDetector().isDark();
        }
        catch ( Throwable t ) {
            return true;
        }
    }
}
