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
 * Logical keyboard-key identifiers used by RGB effects.
 *
 * <p>This enum is intentionally <em>not</em> a comprehensive list of every
 * physical key on every keyboard. It's the set of keys the launcher's
 * effects ever target — gameplay-relevant defaults (WASD / E / Space /
 * Shift / Ctrl), plus a small extra set so future effects (combat-key
 * highlighting, hotbar-1-through-9, etc.) don't need a schema bump.
 * Vendor backends translate from this logical key to their own per-LED
 * indices via a per-keyboard-model mapping table.</p>
 *
 * <p>Keys not declared here can't be addressed by effects — by design.
 * Adding new entries is a deliberate API-surface decision, not a default
 * upon every new launcher feature. Each entry's mapping must be added to
 * the {@code KeyboardLayout} table inside each backend before it does
 * anything visible.</p>
 *
 * @since 2026.5
 */
public enum KeyboardKey
{
    // Movement (the "minecraft default" set — what In-Game effect lights up first)
    W, A, S, D,

    // Common interaction keys
    E,          // open inventory / use
    Q,          // drop item
    F,          // swap hand
    SPACE,      // jump
    LEFT_SHIFT, // sneak
    LEFT_CTRL,  // sprint (toggle-sprint default)

    // Combat / hotbar quick-access — reserved for future effects
    NUM_1, NUM_2, NUM_3, NUM_4, NUM_5, NUM_6, NUM_7, NUM_8, NUM_9,

    // Menu / chat
    ESCAPE, ENTER, T, TAB,

    // Function row — F3 (debug) + F5 (perspective) are the headline ones,
    // include the rest as a contiguous block for future debug-highlight effects.
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12
}
