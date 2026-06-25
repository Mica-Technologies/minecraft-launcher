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

/**
 * Immutable 24-bit RGB color value used by the RGB-integration subsystem.
 *
 * <p>Components are stored as ints in {@code [0, 255]}. Construction
 * validates inputs because color values flow in from extracted modpack-logo
 * dominant colors, theme palettes, and user-supplied hex strings — a single
 * out-of-range channel here would propagate into vendor SDK calls where it'd
 * surface as a more confusing failure mode.
 *
 * <p>Comparison + hashing are field-wise so two colors with the same RGB
 * triple compare equal regardless of how they were constructed, which keeps
 * frame-deduplication cheap in the effect engine.
 *
 * @since 2026.5
 */
public final class RgbColor
{
    /** Black; useful as a "no-light" sentinel and the default frame
     *  background. */
    public static final RgbColor BLACK = new RgbColor( 0, 0, 0 );

    /** White; useful as a default highlight color before pack-derived
     *  palettes are available. */
    public static final RgbColor WHITE = new RgbColor( 255, 255, 255 );

    /** Red channel, in {@code [0, 255]}. */
    private final int r;

    /** Green channel, in {@code [0, 255]}. */
    private final int g;

    /** Blue channel, in {@code [0, 255]}. */
    private final int b;

    /**
     * Constructs a color from individual 8-bit channel values.
     *
     * @param r red channel, must be in {@code [0, 255]}
     * @param g green channel, must be in {@code [0, 255]}
     * @param b blue channel, must be in {@code [0, 255]}
     *
     * @throws IllegalArgumentException if any channel falls outside {@code [0, 255]}
     * @since 2026.5
     */
    public RgbColor( int r, int g, int b )
    {
        if ( r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255 ) {
            throw new IllegalArgumentException(
                    "RGB components must be 0-255: r=" + r + " g=" + g + " b=" + b );
        }
        this.r = r;
        this.g = g;
        this.b = b;
    }

    /**
     * Returns the red channel.
     *
     * @return red channel value, in {@code [0, 255]}
     *
     * @since 2026.5
     */
    public int r() { return r; }

    /**
     * Returns the green channel.
     *
     * @return green channel value, in {@code [0, 255]}
     *
     * @since 2026.5
     */
    public int g() { return g; }

    /**
     * Returns the blue channel.
     *
     * @return blue channel value, in {@code [0, 255]}
     *
     * @since 2026.5
     */
    public int b() { return b; }

    /**
     * Parses a hex color string of the form {@code #RRGGBB} or {@code RRGGBB}.
     * Throws {@link IllegalArgumentException} for malformed input rather than
     * silently returning a fallback — callers passing constants want loud
     * failures during dev, and runtime callers wrap the parse in their own
     * defensive logic.
     *
     * @param hex a six-hex-digit color string, optionally prefixed with {@code #}
     *
     * @return the parsed {@link RgbColor}
     *
     * @throws IllegalArgumentException if {@code hex} is {@code null}, not exactly
     *                                  six hex digits (after any leading {@code #}),
     *                                  or contains non-hexadecimal characters
     * @since 2026.5
     */
    public static RgbColor fromHex( String hex )
    {
        if ( hex == null ) {
            throw new IllegalArgumentException( "Hex string cannot be null" );
        }
        String s = hex.startsWith( "#" ) ? hex.substring( 1 ) : hex;
        if ( s.length() != 6 ) {
            throw new IllegalArgumentException( "Hex string must be 6 chars: " + hex );
        }
        try {
            int r = Integer.parseInt( s.substring( 0, 2 ), 16 );
            int g = Integer.parseInt( s.substring( 2, 4 ), 16 );
            int b = Integer.parseInt( s.substring( 4, 6 ), 16 );
            return new RgbColor( r, g, b );
        }
        catch ( NumberFormatException e ) {
            throw new IllegalArgumentException( "Hex string is not valid hex: " + hex, e );
        }
    }

    /**
     * Linear interpolation between two colors. {@code t=0} returns {@code a},
     * {@code t=1} returns {@code b}, values outside {@code [0,1]} are clamped.
     *
     * @param a the start color (returned when {@code t <= 0})
     * @param b the end color (returned when {@code t >= 1})
     * @param t the interpolation fraction; clamped to {@code [0, 1]}
     *
     * @return the per-channel interpolated color
     *
     * @since 2026.5
     */
    public static RgbColor blend( RgbColor a, RgbColor b, double t )
    {
        double tc = Math.max( 0.0, Math.min( 1.0, t ) );
        int r = (int) Math.round( a.r + ( b.r - a.r ) * tc );
        int g = (int) Math.round( a.g + ( b.g - a.g ) * tc );
        int bl = (int) Math.round( a.b + ( b.b - a.b ) * tc );
        return new RgbColor( r, g, bl );
    }

    /**
     * Returns the HSL-complement (180-degree hue rotation) — used by
     * effects to produce a contrasting highlight color from a pack's
     * primary palette without needing a second hand-picked color.
     *
     * @return a new color with each channel inverted ({@code 255 - channel})
     *
     * @since 2026.5
     */
    public RgbColor complement()
    {
        return new RgbColor( 255 - r, 255 - g, 255 - b );
    }

    /**
     * Pack the three channels into a single int as {@code 0x00RRGGBB} —
     * the format Razer Chroma's REST API and OpenRGB's protocol both
     * accept directly.
     *
     * @return the channels packed into the low 24 bits of an int as
     *         {@code 0x00RRGGBB}
     *
     * @since 2026.5
     */
    public int packRgb()
    {
        return ( r << 16 ) | ( g << 8 ) | b;
    }

    /**
     * Compares two colors field-wise, so colors with the same RGB triple
     * compare equal regardless of how they were constructed.
     *
     * @param o the object to compare against
     *
     * @return {@code true} if {@code o} is an {@code RgbColor} with identical
     *         red, green, and blue channels
     *
     * @since 2026.5
     */
    @Override
    public boolean equals( Object o )
    {
        if ( this == o ) return true;
        if ( !( o instanceof RgbColor other ) ) return false;
        return r == other.r && g == other.g && b == other.b;
    }

    /**
     * Field-wise hash consistent with {@link #equals(Object)}.
     *
     * @return a hash derived from the three channel values
     *
     * @since 2026.5
     */
    @Override
    public int hashCode()
    {
        return ( r * 31 + g ) * 31 + b;
    }

    /**
     * Renders the color as an uppercase {@code #RRGGBB} hex string.
     *
     * @return the color formatted as {@code #RRGGBB}
     *
     * @since 2026.5
     */
    @Override
    public String toString()
    {
        return String.format( "#%02X%02X%02X", r, g, b );
    }
}
