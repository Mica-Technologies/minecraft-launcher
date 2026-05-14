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

package com.micatechnologies.minecraft.launcher.rgb.backends;

import com.micatechnologies.minecraft.launcher.rgb.RgbBackend;
import com.micatechnologies.minecraft.launcher.rgb.RgbFrame;

/**
 * Always-available backend that discards every frame. Two roles:
 *
 * <ol>
 *   <li><b>Default state</b> when RGB is disabled in Settings. The
 *       controller routes to this so callers can submit frames
 *       unconditionally without checking whether RGB is on.</li>
 *   <li><b>Fallback</b> when the configured real backend (OpenRGB,
 *       Razer, etc.) reports unavailable or trips its DEAD breaker.
 *       Lets the rest of the launcher continue submitting effect frames
 *       harmlessly — no special "is rgb dead" guards scattered through
 *       the codebase.</li>
 * </ol>
 *
 * <p>The implementation is deliberately zero-cost: no fields, no state,
 * no allocation in {@link #renderFrame}. A misbehaving effect engine
 * pushing thousands of frames at this backend produces no GC pressure.</p>
 *
 * @since 2026.5
 */
public final class NoOpBackend implements RgbBackend
{
    @Override
    public String name() { return "None"; }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public void start() { /* nothing to start */ }

    @Override
    public void renderFrame( RgbFrame frame ) { /* discard */ }

    @Override
    public void shutdown() { /* nothing to release */ }
}
