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
 * A cosine-eased breathe between two colors. Used in two roles:
 *
 * <ul>
 *   <li><b>Idle pack effect</b> — slow (~4s period) breathe between
 *       a pack's two dominant colors so the keyboard subtly reflects
 *       the currently-selected modpack while the launcher sits idle.</li>
 *   <li><b>Launching effect</b> — fast (~1s period) pulse between the
 *       pack's primary color and white during the pre-launch progress
 *       screen so the user knows the launch is in flight.</li>
 * </ul>
 *
 * <p>The interpolation uses a half-cosine so the motion eases at both
 * extremes rather than ticking linearly across the value range —
 * matches what people perceive as "breathing" rather than the slightly
 * jarring linear lerp. Period configurable per-instance because the
 * two roles want different cadences.</p>
 *
 * @since 2026.5
 */
public final class PulseEffect implements RgbEffect
{
    /** Human-readable effect name surfaced via {@link #name()}. */
    private final String name;

    /** The color at the trough of the breathe ({@code t == 0}). */
    private final RgbColor colorA;

    /** The color at the peak of the breathe ({@code t == 1}). */
    private final RgbColor colorB;

    /** Duration in milliseconds of one full breathe (A → B → A). */
    private final long periodMs;

    /**
     * Creates a two-color breathe effect.
     *
     * @param name     human-readable effect name (returned by {@link #name()})
     * @param colorA   color shown at the breathe trough ({@code t == 0})
     * @param colorB   color shown at the breathe peak ({@code t == 1})
     * @param periodMs duration in milliseconds of one full breathe; must be
     *                 strictly positive
     *
     * @throws IllegalArgumentException if {@code periodMs} is not positive
     *
     * @since 2026.5
     */
    public PulseEffect( String name, RgbColor colorA, RgbColor colorB, long periodMs )
    {
        if ( periodMs <= 0 ) {
            throw new IllegalArgumentException( "periodMs must be positive: " + periodMs );
        }
        this.name = name;
        this.colorA = colorA;
        this.colorB = colorB;
        this.periodMs = periodMs;
    }

    /**
     * {@inheritDoc}
     *
     * @return the effect name supplied at construction
     *
     * @since 2026.5
     */
    @Override public String name() { return name; }

    /**
     * {@inheritDoc}
     *
     * <p>Maps the elapsed time onto a cosine-eased breathe coefficient and
     * returns a solid frame blended between {@code colorA} and {@code colorB}.
     * The coefficient is {@code 0} at the start of each period, {@code 1} at
     * the half-period, and back to {@code 0} at the period boundary.</p>
     *
     * @param elapsedMs milliseconds elapsed since the effect started; the
     *                  phase wraps every {@code periodMs}
     *
     * @return the blended solid frame for this instant
     *
     * @since 2026.5
     */
    @Override
    public RgbFrame frameAt( long elapsedMs )
    {
        // Phase in [0, 1) — what fraction of a full period have we
        // covered? Modulo handles the wrap.
        double phase = ( elapsedMs % periodMs ) / (double) periodMs;
        // Cosine-ease the phase into a 0..1 breathe coefficient:
        //   t=0 → 0   (full colorA)
        //   t=0.5 → 1 (full colorB)
        //   t=1 → 0   (back to colorA)
        // (1 - cos(2π * phase)) / 2 produces this curve.
        double t = ( 1.0 - Math.cos( phase * 2.0 * Math.PI ) ) / 2.0;
        return RgbFrame.solid( RgbColor.blend( colorA, colorB, t ) );
    }
}
