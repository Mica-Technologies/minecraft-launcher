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
 * The simplest effect — a single solid color across the whole keyboard.
 *
 * <p>Used by the Settings "Test" button (flash the user's accent color
 * to confirm wiring) and as the "off" effect that paints black before
 * the engine stops. Constant-frame: {@link #frameAt(long)} returns the
 * same {@link RgbFrame} on every call so the controller's frame-
 * deduplication can collapse repeats and skip the per-frame socket
 * write.</p>
 *
 * @since 2026.5
 */
public final class SolidEffect implements RgbEffect
{
    private final String name;
    private final RgbFrame frame;

    public SolidEffect( String name, RgbColor color )
    {
        this.name = name;
        this.frame = RgbFrame.solid( color );
    }

    @Override public String name() { return name; }

    @Override public RgbFrame frameAt( long elapsedMs ) { return frame; }
}
