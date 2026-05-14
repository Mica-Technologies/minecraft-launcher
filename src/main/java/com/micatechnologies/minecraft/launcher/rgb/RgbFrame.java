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

import java.util.Map;
import java.util.Objects;

/**
 * A single rendered frame of RGB output. Carries one background color
 * (applied to every key not explicitly overridden) plus a sparse map of
 * per-key overrides (e.g. {@code W → accent} for the WASD highlight effect).
 *
 * <p>Frames are pushed from the effect engine into the controller's bounded
 * queue and consumed by the active backend on the dedicated RGB worker
 * thread. Keep instances immutable + cheap to construct — the effect engine
 * may produce ~30 of these per second when an effect is running.</p>
 *
 * @since 2026.5
 */
public final class RgbFrame
{
    private final RgbColor background;
    private final Map< KeyboardKey, RgbColor > keyOverrides;

    public RgbFrame( RgbColor background, Map< KeyboardKey, RgbColor > keyOverrides )
    {
        this.background = Objects.requireNonNull( background, "background must not be null" );
        this.keyOverrides = ( keyOverrides == null || keyOverrides.isEmpty() )
                ? Map.of()
                : Map.copyOf( keyOverrides );
    }

    /** A solid-color frame — every key painted the same. Used by simple
     *  effects (Test button flash, single-color idle) where no per-key
     *  highlighting is wanted. */
    public static RgbFrame solid( RgbColor color )
    {
        return new RgbFrame( color, Map.of() );
    }

    public RgbColor background() { return background; }

    /** Returns the color this frame wants for {@code key} — either the
     *  override entry if present, or the background color. Never returns
     *  null. */
    public RgbColor colorFor( KeyboardKey key )
    {
        RgbColor override = keyOverrides.get( key );
        return override != null ? override : background;
    }

    /** Unmodifiable view of the per-key override map — backends iterate it
     *  to set non-background keys, leaving the rest of the device on the
     *  background fill. */
    public Map< KeyboardKey, RgbColor > overrides()
    {
        return keyOverrides;
    }

    @Override
    public boolean equals( Object o )
    {
        if ( this == o ) return true;
        if ( !( o instanceof RgbFrame other ) ) return false;
        return background.equals( other.background )
                && keyOverrides.equals( other.keyOverrides );
    }

    @Override
    public int hashCode()
    {
        return background.hashCode() * 31 + keyOverrides.hashCode();
    }
}
