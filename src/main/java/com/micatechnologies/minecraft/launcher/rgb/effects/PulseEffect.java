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
    private final String name;
    private final RgbColor colorA;
    private final RgbColor colorB;
    private final long periodMs;

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

    @Override public String name() { return name; }

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
