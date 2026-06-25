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
    /** Move forward (the "minecraft default" movement set). */
    W,
    /** Strafe left (the "minecraft default" movement set). */
    A,
    /** Move backward (the "minecraft default" movement set). */
    S,
    /** Strafe right (the "minecraft default" movement set). */
    D,

    // Common interaction keys
    /** Open inventory / use. */
    E,
    /** Drop item. */
    Q,
    /** Swap item to off-hand. */
    F,
    /** Jump. */
    SPACE,
    /** Sneak. */
    LEFT_SHIFT,
    /** Sprint (toggle-sprint default). */
    LEFT_CTRL,

    // Combat / hotbar quick-access — reserved for future effects
    /** Hotbar slot 1 (reserved for future combat / hotbar effects). */
    NUM_1,
    /** Hotbar slot 2 (reserved for future combat / hotbar effects). */
    NUM_2,
    /** Hotbar slot 3 (reserved for future combat / hotbar effects). */
    NUM_3,
    /** Hotbar slot 4 (reserved for future combat / hotbar effects). */
    NUM_4,
    /** Hotbar slot 5 (reserved for future combat / hotbar effects). */
    NUM_5,
    /** Hotbar slot 6 (reserved for future combat / hotbar effects). */
    NUM_6,
    /** Hotbar slot 7 (reserved for future combat / hotbar effects). */
    NUM_7,
    /** Hotbar slot 8 (reserved for future combat / hotbar effects). */
    NUM_8,
    /** Hotbar slot 9 (reserved for future combat / hotbar effects). */
    NUM_9,

    // Menu / chat
    /** Close menu / pause. */
    ESCAPE,
    /** Confirm / send chat. */
    ENTER,
    /** Open chat. */
    T,
    /** Player list / tab navigation. */
    TAB,

    // Function row — F3 (debug) + F5 (perspective) are the headline ones,
    // include the rest as a contiguous block for future debug-highlight effects.
    /** Function key F1. */
    F1,
    /** Function key F2. */
    F2,
    /** Function key F3 (debug overlay) — a headline function-row key. */
    F3,
    /** Function key F4. */
    F4,
    /** Function key F5 (perspective toggle) — a headline function-row key. */
    F5,
    /** Function key F6. */
    F6,
    /** Function key F7. */
    F7,
    /** Function key F8. */
    F8,
    /** Function key F9. */
    F9,
    /** Function key F10. */
    F10,
    /** Function key F11. */
    F11,
    /** Function key F12. */
    F12
}
