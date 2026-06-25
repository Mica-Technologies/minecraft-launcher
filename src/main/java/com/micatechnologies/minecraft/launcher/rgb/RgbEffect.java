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
 * A time-varying source of {@link RgbFrame}s. The effect engine ticks an
 * active effect at the engine's target FPS, asks it for a frame, and
 * forwards that frame to {@link RgbController#submitFrame}.
 *
 * <p>Effects are pure functions of elapsed time — they receive a
 * milliseconds-since-start value and return the frame they want at that
 * moment. This lets the engine support pause / resume / scrub-to-time
 * without effects needing internal mutable state for the animation
 * timeline. Effects MAY hold immutable configuration state (colors,
 * highlighted keys, etc.) set at construction time.</p>
 *
 * <p>Implementations must be safe to call from the engine's worker
 * thread. They should not block, should not allocate aggressively (a
 * 30fps effect engine can churn through {@code 60 * 60 * 30} frame
 * objects in an hour), and should not throw — exceptions surface in
 * the engine's tick loop and get routed to the silent log; effects
 * stay active but their frame for that tick is dropped.</p>
 *
 * @since 2026.5
 */
public interface RgbEffect
{
    /**
     * Human-readable name for log lines + the Settings status chip.
     *
     * @return the effect's display name; never {@code null}
     *
     * @since 2026.5
     */
    String name();

    /**
     * Return the frame this effect wants to display at the given time
     * since the effect was activated. May return {@code null} to signal
     * "no change this tick" — the engine then skips this frame entirely
     * (useful for effects that only animate occasionally, e.g. a slow
     * 5-second cross-fade).
     *
     * @param elapsedMs milliseconds elapsed since this effect was
     *                  activated by the engine; the animation timeline
     *                  is derived purely from this value, never internal
     *                  mutable clock state
     *
     * @return the frame to display at {@code elapsedMs}, or {@code null}
     *         to signal "no change this tick" (engine skips the frame)
     *
     * @since 2026.5
     */
    RgbFrame frameAt( long elapsedMs );
}
