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

package com.micatechnologies.minecraft.launcher.rgb.effects;

import com.micatechnologies.minecraft.launcher.rgb.RgbColor;
import com.micatechnologies.minecraft.launcher.rgb.RgbEffect;
import com.micatechnologies.minecraft.launcher.rgb.RgbFrame;

/**
 * Continuous hue sweep across the full visible spectrum. Saturation
 * and lightness are fixed so the colors stay vivid; only hue rotates.
 *
 * <p>Independent of pack or theme — the same loop regardless of what
 * the user is doing in the launcher. Useful as a "novelty" RGB style
 * when a user just wants the keyboard alive without it adopting any
 * particular brand identity.</p>
 *
 * <p>HSL math is hand-rolled rather than going through {@code java.awt}
 * or {@code javafx.scene.paint.Color} so the effect compiles without
 * dragging the JavaFX runtime into a unit test JVM and so the
 * conversion is exactly reproducible across platforms.</p>
 *
 * @since 2026.5
 */
public final class RainbowEffect implements RgbEffect
{
    /** Saturation used for every emitted color. 1.0 = pure / vivid;
     *  fixed because user-driven saturation isn't a useful knob — every
     *  setting below ~0.7 just looks washed out on RGB hardware. */
    private static final double SATURATION = 1.0;

    /** Lightness used for every emitted color. 0.5 puts the hue circle
     *  at maximum colorfulness in HSL — going higher washes toward
     *  white, going lower toward black. */
    private static final double LIGHTNESS  = 0.5;

    private final String name;
    private final long periodMs;

    public RainbowEffect( String name, long periodMs )
    {
        if ( periodMs <= 0 ) {
            throw new IllegalArgumentException( "periodMs must be positive: " + periodMs );
        }
        this.name = name;
        this.periodMs = periodMs;
    }

    @Override public String name() { return name; }

    @Override
    public RgbFrame frameAt( long elapsedMs )
    {
        double phase = ( elapsedMs % periodMs ) / (double) periodMs; // [0, 1)
        double hue   = phase * 360.0; // [0, 360)
        return RgbFrame.solid( hslToRgb( hue, SATURATION, LIGHTNESS ) );
    }

    /**
     * HSL → RGB conversion. Hue in degrees [0, 360); saturation and
     * lightness in [0, 1]. Implementation follows the standard piecewise
     * formula (see https://en.wikipedia.org/wiki/HSL_and_HSV). Outputs
     * are clamped to [0, 255] before construction in case of float drift.
     */
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
