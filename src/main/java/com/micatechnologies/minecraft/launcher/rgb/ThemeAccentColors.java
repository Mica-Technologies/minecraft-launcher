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

    /** OS dark-mode probe. Delegates to the shared NULL-safe helper, which
     *  defaults to dark when the detector isn't usable on this platform —
     *  matching the launcher's "when in doubt, assume dark" default. */
    private static boolean osIsDark()
    {
        return com.micatechnologies.minecraft.launcher.utilities.OsThemeUtilities.isOsDark();
    }

    /**
     * Derive a {@code count}-entry palette around {@code primary} by
     * rotating its hue in equal steps. Used by the Cycle effect when
     * the user isn't viewing a specific modpack — instead of cycling
     * between sampled pack hues, we fan the theme accent into a small
     * analogous palette so the cycle is still based on the launcher's
     * brand.
     *
     * <p>Rotation step is {@code 30°} per slot — analogous rather than
     * triadic — so the cycle reads as a smooth hue drift through the
     * accent's neighbourhood rather than jarring jumps across the
     * color wheel. {@code count = 1} returns just the primary;
     * {@code count = 2} is the primary plus one neighbour; etc.</p>
     */
    public static java.util.List< RgbColor > derivePalette( RgbColor primary, int count )
    {
        if ( count < 1 ) count = 1;
        if ( count == 1 ) return java.util.List.of( primary );
        java.util.List< RgbColor > palette = new java.util.ArrayList<>( count );
        double[] hsl = rgbToHsl( primary );
        // Centre the rotation on the primary so it's the middle hue of
        // the analogous spread (rather than the first one) — easier on
        // the eye when count is small.
        double centreHue = hsl[ 0 ];
        double s = hsl[ 1 ];
        double l = hsl[ 2 ];
        double stepDeg = 30.0;
        double startOffset = -stepDeg * ( count - 1 ) / 2.0;
        for ( int i = 0; i < count; i++ ) {
            double hue = ( ( centreHue + startOffset + i * stepDeg ) % 360.0 + 360.0 ) % 360.0;
            palette.add( hslToRgb( hue, s, l ) );
        }
        return palette;
    }

    // =========================================================================
    //  HSL helpers
    // =========================================================================
    //  Kept package-private — these are general enough to belong on
    //  RgbColor itself, but the only current caller is derivePalette
    //  and we don't want to grow that class's API surface speculatively.
    //  Move them up if a second caller appears.

    /** Returns {@code { hue°, saturation, lightness }} in HSL space.
     *  Hue ∈ [0, 360); saturation, lightness ∈ [0, 1]. */
    static double[] rgbToHsl( RgbColor c )
    {
        double r = c.r() / 255.0;
        double g = c.g() / 255.0;
        double b = c.b() / 255.0;
        double max = Math.max( r, Math.max( g, b ) );
        double min = Math.min( r, Math.min( g, b ) );
        double l = ( max + min ) / 2.0;
        double h, s;
        if ( max == min ) {
            h = 0;
            s = 0;
        }
        else {
            double d = max - min;
            s = l > 0.5 ? d / ( 2.0 - max - min ) : d / ( max + min );
            if ( max == r )      h = ( g - b ) / d + ( g < b ? 6 : 0 );
            else if ( max == g ) h = ( b - r ) / d + 2;
            else                 h = ( r - g ) / d + 4;
            h *= 60.0;
        }
        return new double[]{ h, s, l };
    }

    /** HSL → RGB. Hue in degrees, saturation + lightness in [0, 1]. */
    static RgbColor hslToRgb( double hueDeg, double s, double l )
    {
        double h = ( ( hueDeg % 360.0 ) + 360.0 ) % 360.0;
        double c = ( 1.0 - Math.abs( 2.0 * l - 1.0 ) ) * s;
        double x = c * ( 1.0 - Math.abs( ( h / 60.0 ) % 2.0 - 1.0 ) );
        double m = l - c / 2.0;
        double r1, g1, b1;
        if      ( h <  60 ) { r1 = c; g1 = x; b1 = 0; }
        else if ( h < 120 ) { r1 = x; g1 = c; b1 = 0; }
        else if ( h < 180 ) { r1 = 0; g1 = c; b1 = x; }
        else if ( h < 240 ) { r1 = 0; g1 = x; b1 = c; }
        else if ( h < 300 ) { r1 = x; g1 = 0; b1 = c; }
        else                { r1 = c; g1 = 0; b1 = x; }
        int r = clamp255( (int) Math.round( ( r1 + m ) * 255.0 ) );
        int g = clamp255( (int) Math.round( ( g1 + m ) * 255.0 ) );
        int b = clamp255( (int) Math.round( ( b1 + m ) * 255.0 ) );
        return new RgbColor( r, g, b );
    }

    private static int clamp255( int v )
    {
        return v < 0 ? 0 : ( v > 255 ? 255 : v );
    }
}
