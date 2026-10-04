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


package com.micatechnologies.minecraft.launcher.gui;

import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Colour from a modpack's art: Material You's dynamic colour, scoped to one pack. The pack's logo
 * gives a seed colour ({@link #seedFrom}); a small scheme of accent roles is built from its hue
 * ({@link #roles}) and set on the detail window as overrides of the theme's lookups ({@link #apply}),
 * so that window's tabs, Play button, switches and titles take on the pack's colour while
 * everything else keeps the theme's.
 *
 * <p>Colours are worked in Oklab, a perceptual space, so a given lightness looks equally light in
 * any hue. Every text/background pair in the scheme is checked against WCAG AA (4.5:1) and nudged
 * until it reads.
 *
 * @since 2026.10
 */
public final class PackColorScheme
{
    /** Hue buckets for finding the dominant vivid colour. */
    private static final int BUCKETS = 24;
    /** Chroma below this counts as grey and is ignored. */
    private static final double MIN_CHROMA = 0.045;

    private PackColorScheme()
    {
    }

    // ------------------------------------------------------------------ seed

    /**
     * The seed colour of an image: the average of its most prominent vivid hue, or empty when it
     * has none (a grey, black or white logo).
     *
     * @param image a loaded image
     *
     * @return the seed, as RGB
     */
    public static OptionalInt seedFrom( Image image )
    {
        PixelReader reader = image == null ? null : image.getPixelReader();
        if ( reader == null || image.getWidth() < 1 || image.getHeight() < 1 ) {
            return OptionalInt.empty();
        }
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        int stepX = Math.max( 1, w / 48 );
        int stepY = Math.max( 1, h / 48 );
        int[] pixels = new int[ ( ( w + stepX - 1 ) / stepX ) * ( ( h + stepY - 1 ) / stepY ) ];
        int n = 0;
        for ( int y = 0; y < h; y += stepY ) {
            for ( int x = 0; x < w; x += stepX ) {
                pixels[ n++ ] = reader.getArgb( x, y );
            }
        }
        return seedFrom( java.util.Arrays.copyOf( pixels, n ) );
    }

    /**
     * The seed colour of a set of ARGB pixels (see {@link #seedFrom(Image)}).
     *
     * @param argb the pixels
     *
     * @return the seed, as RGB
     */
    static OptionalInt seedFrom( int[] argb )
    {
        double[] weight = new double[ BUCKETS ];
        double[][] sum = new double[ BUCKETS ][ 3 ];
        for ( int px : argb ) {
            if ( ( px >>> 24 ) < 128 ) {
                continue;
            }
            double[] lab = oklab( px & 0xFFFFFF );
            double chroma = Math.hypot( lab[ 1 ], lab[ 2 ] );
            if ( chroma < MIN_CHROMA || lab[ 0 ] < 0.2 || lab[ 0 ] > 0.97 ) {
                continue;
            }
            double hue = Math.atan2( lab[ 2 ], lab[ 1 ] );
            int bucket = (int) Math.floor( ( hue + Math.PI ) / ( 2 * Math.PI ) * BUCKETS ) % BUCKETS;
            // Vivid pixels count for more than dull ones.
            weight[ bucket ] += chroma;
            sum[ bucket ][ 0 ] += lab[ 0 ] * chroma;
            sum[ bucket ][ 1 ] += lab[ 1 ] * chroma;
            sum[ bucket ][ 2 ] += lab[ 2 ] * chroma;
        }
        int best = -1;
        for ( int i = 0; i < BUCKETS; i++ ) {
            if ( weight[ i ] > 0 && ( best < 0 || weight[ i ] > weight[ best ] ) ) {
                best = i;
            }
        }
        if ( best < 0 ) {
            return OptionalInt.empty();
        }
        double wsum = weight[ best ];
        return OptionalInt.of( srgb( sum[ best ][ 0 ] / wsum, sum[ best ][ 1 ] / wsum, sum[ best ][ 2 ] / wsum ) );
    }

    // ------------------------------------------------------------------ scheme

    /**
     * The accent roles for a seed, as lookup name to CSS colour: the {@code -md-*} roles the
     * components use, plus the older {@code -color-primary*} names.
     *
     * @param seed    the seed, as RGB
     * @param dark    whether the theme is dark
     * @param surface the colour the accents sit on (the window's surface), as RGB
     *
     * @return the roles, in a stable order
     */
    public static Map< String, String > roles( int seed, boolean dark, int surface )
    {
        double[] lab = oklab( seed );
        double hue = Math.atan2( lab[ 2 ], lab[ 1 ] );
        double chroma = Math.max( 0.08, Math.min( 0.17, Math.hypot( lab[ 1 ], lab[ 2 ] ) ) );

        int primary = tone( dark ? 0.78 : 0.50, chroma, hue );
        int onPrimary = readable( dark ? 0.22 : 1.0, chroma * 0.3, hue, primary, !dark );
        int container = tone( dark ? 0.34 : 0.91, chroma * ( dark ? 0.7 : 0.35 ), hue );
        int onContainer = readable( dark ? 0.93 : 0.25, chroma * 0.5, hue, container, dark );
        int indicator = tone( dark ? 0.35 : 0.90, Math.min( chroma, 0.06 ), hue );
        int onIndicator = readable( dark ? 0.93 : 0.22, 0.03, hue, indicator, dark );
        int textPrimary = readable( dark ? 0.78 : 0.48, chroma, hue, surface, dark );

        Map< String, String > roles = new LinkedHashMap<>();
        roles.put( "-md-primary", hex( primary ) );
        roles.put( "-md-on-primary", hex( onPrimary ) );
        roles.put( "-md-primary-container", hex( container ) );
        roles.put( "-md-on-primary-container", hex( onContainer ) );
        roles.put( "-md-active-indicator", hex( indicator ) );
        roles.put( "-md-on-active-indicator", hex( onIndicator ) );
        roles.put( "-md-text-primary", hex( textPrimary ) );
        roles.put( "-md-state-hover-primary", rgba( primary, 0.08 ) );
        roles.put( "-md-state-pressed-primary", rgba( primary, 0.10 ) );
        roles.put( "-md-state-hover-on-primary", rgba( onPrimary, 0.08 ) );
        roles.put( "-md-state-pressed-on-primary", rgba( onPrimary, 0.10 ) );
        roles.put( "-color-primary", hex( primary ) );
        roles.put( "-color-primary-hover", hex( tone( dark ? 0.83 : 0.45, chroma, hue ) ) );
        roles.put( "-color-primary-pressed", hex( tone( dark ? 0.87 : 0.40, chroma, hue ) ) );
        roles.put( "-color-primary-soft", rgba( primary, 0.18 ) );
        roles.put( "-color-primary-soft-hov", rgba( primary, 0.28 ) );
        roles.put( "-color-text-on-primary", hex( onPrimary ) );
        return roles;
    }

    /**
     * Sets a scheme on a node as inline lookup overrides, so the node and everything inside it use
     * the pack's accents. Replaces the node's inline style.
     *
     * @param node  the node to colour (the detail window's card)
     * @param roles the roles from {@link #roles}, or {@code null} to go back to the theme's
     */
    public static void apply( Node node, Map< String, String > roles )
    {
        if ( roles == null || roles.isEmpty() ) {
            node.setStyle( "" );
            return;
        }
        StringBuilder style = new StringBuilder();
        roles.forEach( ( name, value ) -> style.append( name ).append( ": " ).append( value ).append( "; " ) );
        node.setStyle( style.toString().trim() );
    }

    /** A colour at lightness L, chroma C and hue h (Oklch). */
    static int tone( double l, double c, double h )
    {
        // Lower the chroma until the colour fits in sRGB, so it keeps its hue.
        for ( double cc = c; cc >= 0; cc -= 0.005 ) {
            double a = cc * Math.cos( h );
            double b = cc * Math.sin( h );
            if ( inGamut( l, a, b ) ) {
                return srgb( l, a, b );
            }
        }
        return srgb( l, 0, 0 );
    }

    /**
     * The colour nearest lightness {@code l} that reaches 4.5:1 against {@code against}, moving
     * lighter ({@code lighter}) or darker.
     */
    static int readable( double l, double c, double h, int against, boolean lighter )
    {
        double step = lighter ? 0.01 : -0.01;
        for ( double ll = l; ll >= 0 && ll <= 1; ll += step ) {
            int candidate = tone( ll, c, h );
            if ( contrast( candidate, against ) >= 4.5 ) {
                return candidate;
            }
        }
        return lighter ? 0xFFFFFF : 0x000000;
    }

    // ------------------------------------------------------------------ colour maths

    /** sRGB to Oklab: {L, a, b}. */
    static double[] oklab( int rgb )
    {
        double r = lin( ( rgb >> 16 ) & 0xFF );
        double g = lin( ( rgb >> 8 ) & 0xFF );
        double b = lin( rgb & 0xFF );
        double l = Math.cbrt( 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b );
        double m = Math.cbrt( 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b );
        double s = Math.cbrt( 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b );
        return new double[]{
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s };
    }

    private static double[] linearRgb( double L, double a, double b )
    {
        double l = Math.pow( L + 0.3963377774 * a + 0.2158037573 * b, 3 );
        double m = Math.pow( L - 0.1055613458 * a - 0.0638541728 * b, 3 );
        double s = Math.pow( L - 0.0894841775 * a - 1.2914855480 * b, 3 );
        return new double[]{
                4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s };
    }

    private static boolean inGamut( double L, double a, double b )
    {
        for ( double v : linearRgb( L, a, b ) ) {
            if ( v < -0.0005 || v > 1.0005 ) {
                return false;
            }
        }
        return true;
    }

    /** Oklab to sRGB, clamped. */
    static int srgb( double L, double a, double b )
    {
        double[] rgb = linearRgb( L, a, b );
        int out = 0;
        for ( double v : rgb ) {
            out = ( out << 8 ) | (int) Math.round( Math.max( 0, Math.min( 1, gamma( v ) ) ) * 255 );
        }
        return out;
    }

    private static double lin( int c )
    {
        double s = c / 255.0;
        return s <= 0.04045 ? s / 12.92 : Math.pow( ( s + 0.055 ) / 1.055, 2.4 );
    }

    private static double gamma( double v )
    {
        return v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow( v, 1 / 2.4 ) - 0.055;
    }

    /** WCAG contrast ratio of two RGB colours. */
    static double contrast( int x, int y )
    {
        double a = luminance( x );
        double b = luminance( y );
        return ( Math.max( a, b ) + 0.05 ) / ( Math.min( a, b ) + 0.05 );
    }

    private static double luminance( int rgb )
    {
        return 0.2126 * lin( ( rgb >> 16 ) & 0xFF ) + 0.7152 * lin( ( rgb >> 8 ) & 0xFF ) + 0.0722 * lin( rgb & 0xFF );
    }

    private static String hex( int rgb )
    {
        return String.format( Locale.ROOT, "#%06X", rgb & 0xFFFFFF );
    }

    private static String rgba( int rgb, double alpha )
    {
        return String.format( Locale.ROOT, "rgba(%d, %d, %d, %.2f)", ( rgb >> 16 ) & 0xFF, ( rgb >> 8 ) & 0xFF,
                              rgb & 0xFF, alpha );
    }
}
