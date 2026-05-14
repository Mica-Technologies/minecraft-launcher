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
 * Vendor-specific RGB backend (OpenRGB / Razer Chroma / future Logitech /
 * Corsair / etc.). Every backend implementation lives behind this
 * interface; {@link RgbController} never reaches past it.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Backends are instantiated lazily on first use — never during
 *       launcher startup. A backend that fails to construct still doesn't
 *       block anything.</li>
 *   <li>{@link #isAvailable()} is called once to decide whether the
 *       backend can run on this system. This must return quickly (under
 *       500ms) and must not throw — return false on any uncertainty.</li>
 *   <li>{@link #start()} runs only after {@code isAvailable()} returned
 *       true. May open sockets, load DLLs, register sessions, etc. It is
 *       allowed to throw — the controller catches and routes the backend
 *       to DEAD state.</li>
 *   <li>{@link #renderFrame(RgbFrame)} is called at the effect-engine
 *       cadence (~30fps when an effect is active, less when idle). It is
 *       allowed to throw — the controller's circuit breaker handles
 *       repeated failures by flipping to DEGRADED → DEAD.</li>
 *   <li>{@link #shutdown()} is called once on launcher exit. It must
 *       never throw; any cleanup failures must be swallowed and logged
 *       by the implementation itself.</li>
 * </ol>
 *
 * <h3>Threading</h3>
 *
 * <p>All method calls are dispatched on the dedicated RGB worker thread
 * ({@code mica-rgb}). Implementations don't need internal synchronization
 * for state mutated only via these methods.</p>
 *
 * @since 2026.5
 */
public interface RgbBackend
{
    /** Human-readable identifier used in logs and the Settings UI status
     *  chip (e.g. {@code "OpenRGB"}, {@code "Razer Chroma"}). */
    String name();

    /** Quick probe: does the backing software / device appear to be
     *  installed and reachable on this system? Must complete in &lt;500ms
     *  and never throw — return false on any error path. The controller
     *  uses this to decide whether to call {@link #start()}; a false
     *  return routes to the no-op fallback without surfacing an error to
     *  the user. */
    boolean isAvailable();

    /** One-time setup. Open sockets, discover devices, register
     *  sessions, etc. May throw — {@link RgbController} wraps the call
     *  and routes failures to DEAD. */
    void start() throws Exception;

    /** Apply {@code frame} to the connected device(s). Backends should
     *  render the frame's background color across the whole device,
     *  then overlay per-key overrides. May throw — the controller's
     *  circuit breaker handles repeated failures. */
    void renderFrame( RgbFrame frame ) throws Exception;

    /** Release resources, restore any saved device state, close
     *  connections. Must not throw. */
    void shutdown();
}
