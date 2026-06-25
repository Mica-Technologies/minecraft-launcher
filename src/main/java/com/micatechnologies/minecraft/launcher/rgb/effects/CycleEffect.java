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

import java.util.List;

/**
 * Cycles smoothly through an ordered palette of colors. Each adjacent
 * pair is cross-faded with a half-cosine eased blend, and the cycle
 * wraps from the last entry back to the first so the loop is seamless.
 *
 * <p>Driven by a single {@code periodMs} that's the time for one full
 * loop through all colors. With {@code N} colors and a period of
 * {@code P}, each segment (color → next color) takes {@code P / N}
 * milliseconds. The cosine ease produces the same smooth motion as
 * {@link PulseEffect} — adjacent colors blur into each other rather
 * than snapping at segment boundaries.</p>
 *
 * <p>Used by the RGB subsystem's "Cycle" effect style — typical input
 * is 3-4 dominant colors sampled from the modpack's logo, giving each
 * modpack a recognisable per-pack signature animation. Falls back to
 * the launcher theme's accent palette when no pack is in context.</p>
 *
 * @since 2026.5
 */
public final class CycleEffect implements RgbEffect
{
    /** Human-readable effect name surfaced via {@link #name()}. */
    private final String name;

    /** Ordered palette cycled through, defensively copied at construction.
     *  The cycle wraps from the last entry back to the first. */
    private final RgbColor[] colors;

    /** Duration in milliseconds of one full loop through every color. */
    private final long periodMs;

    /**
     * Creates a color-cycle effect.
     *
     * @param name     human-readable effect name (returned by {@link #name()})
     * @param colors   ordered palette to cycle through; must contain at least
     *                 one color (copied defensively, so later mutation of the
     *                 caller's list has no effect)
     * @param periodMs duration in milliseconds of one full loop through every
     *                 color; must be strictly positive
     *
     * @throws IllegalArgumentException if {@code colors} is {@code null} or
     *                                  empty, or if {@code periodMs} is not
     *                                  positive
     *
     * @since 2026.5
     */
    public CycleEffect( String name, List< RgbColor > colors, long periodMs )
    {
        if ( colors == null || colors.isEmpty() ) {
            throw new IllegalArgumentException( "CycleEffect needs at least one color" );
        }
        if ( periodMs <= 0 ) {
            throw new IllegalArgumentException( "periodMs must be positive: " + periodMs );
        }
        this.name = name;
        this.colors = colors.toArray( new RgbColor[ 0 ] );
        this.periodMs = periodMs;
    }

    /**
     * {@inheritDoc}
     *
     * @return the effect name supplied at construction
     *
     * @since 2026.5
     */
    /**
     * {@inheritDoc}
     *
     * <p>Maps the elapsed time onto a {@code (segment, segmentPhase)} pair,
     * then returns a solid frame cross-faded between the segment's two
     * adjacent palette colors using a half-cosine ease. A single-color
     * palette degenerates to a constant solid frame.</p>
     *
     * @param elapsedMs milliseconds elapsed since the effect started; the
     *                  phase wraps every {@code periodMs}
     *
     * @return the blended solid frame for this instant
     *
     * @since 2026.5
     */
    @Override public String name() { return name; }
    @Override
    public RgbFrame frameAt( long elapsedMs )
    {
        // A single color degenerates to SolidEffect — short-circuit so
        // we don't divide by zero or blend a color into itself.
        if ( colors.length == 1 ) {
            return RgbFrame.solid( colors[ 0 ] );
        }

        // Phase in [0, 1) — what fraction through the full cycle are we?
        double phase = ( elapsedMs % periodMs ) / (double) periodMs;

        // Map the phase onto a (segmentIndex, segmentPhase) pair: which
        // pair of colors are we currently between, and how far through
        // that segment are we? N segments cover the full cycle, the last
        // segment wraps from colors[N-1] back to colors[0].
        double scaled = phase * colors.length;
        int segIndex = (int) Math.floor( scaled );
        if ( segIndex >= colors.length ) segIndex = colors.length - 1; // belt-and-suspenders
        double segPhase = scaled - segIndex;

        RgbColor from = colors[ segIndex ];
        RgbColor to   = colors[ ( segIndex + 1 ) % colors.length ];

        // Half-cosine ease: 0 → 0, 0.5 → 0.5 (linear midpoint), 1 → 1,
        // with eased acceleration at the ends. Smoother than a linear
        // lerp without picking up the dip-to-zero behaviour of the
        // PulseEffect curve (which is a full breathe back to colorA).
        double t = ( 1.0 - Math.cos( segPhase * Math.PI ) ) / 2.0;
        return RgbFrame.solid( RgbColor.blend( from, to, t ) );
    }

    /**
     * Number of colors in the cycle. Exposed for tests + introspection.
     *
     * @return the count of colors in the cycle palette
     *
     * @since 2026.5
     */
    public int colorCount() { return colors.length; }
}
