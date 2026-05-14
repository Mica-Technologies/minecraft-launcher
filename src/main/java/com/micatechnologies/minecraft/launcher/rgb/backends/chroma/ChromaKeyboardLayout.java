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

package com.micatechnologies.minecraft.launcher.rgb.backends.chroma;

import com.micatechnologies.minecraft.launcher.rgb.KeyboardKey;

import java.util.EnumMap;
import java.util.Map;

/**
 * Logical {@link KeyboardKey} → (row, column) lookup for the Razer
 * Chroma 6×22 keyboard grid.
 *
 * <p>Razer's Chroma SDK addresses every keyboard with a fixed 6-row by
 * 22-column grid. Keys that don't physically exist on a given model
 * (e.g. macro keys on a TKL) are still addressable — pushing a color to
 * them is a no-op the SDK swallows quietly, so the launcher can use a
 * single layout table across all Razer keyboards without per-model
 * forks.</p>
 *
 * <p>The row/column values here mirror the {@code RZKEY_*} constants
 * from the Razer Chroma SDK C++ headers (encoded as
 * {@code (row << 8) | col}). Only the keys present in
 * {@link KeyboardKey} are listed; the launcher's effect engine never
 * targets media keys, the Razer logo zone, or numpad keys, so they're
 * intentionally omitted. Adding entries here is a deliberate API choice
 * — not every Chroma key automatically becomes a launcher-controllable
 * one.</p>
 *
 * @since 2026.5
 */
final class ChromaKeyboardLayout
{
    /** Number of rows in the Chroma keyboard grid. */
    static final int ROWS = 6;
    /** Number of columns in the Chroma keyboard grid. */
    static final int COLS = 22;

    /** Per-key (row, col) lookup. Immutable, populated at class init. */
    private static final Map< KeyboardKey, int[] > KEY_TO_RC;

    static {
        Map< KeyboardKey, int[] > m = new EnumMap<>( KeyboardKey.class );
        // Row 0 — function row. RZKEY_ESC = 0x0001, RZKEY_F1 = 0x0003.
        m.put( KeyboardKey.ESCAPE, rc( 0, 1 ) );
        m.put( KeyboardKey.F1,  rc( 0,  3 ) );
        m.put( KeyboardKey.F2,  rc( 0,  4 ) );
        m.put( KeyboardKey.F3,  rc( 0,  5 ) );
        m.put( KeyboardKey.F4,  rc( 0,  6 ) );
        m.put( KeyboardKey.F5,  rc( 0,  7 ) );
        m.put( KeyboardKey.F6,  rc( 0,  8 ) );
        m.put( KeyboardKey.F7,  rc( 0,  9 ) );
        m.put( KeyboardKey.F8,  rc( 0, 10 ) );
        m.put( KeyboardKey.F9,  rc( 0, 11 ) );
        m.put( KeyboardKey.F10, rc( 0, 12 ) );
        m.put( KeyboardKey.F11, rc( 0, 13 ) );
        m.put( KeyboardKey.F12, rc( 0, 14 ) );

        // Row 1 — number row. RZKEY_1 = 0x0102 → row 1, col 2.
        m.put( KeyboardKey.NUM_1, rc( 1,  2 ) );
        m.put( KeyboardKey.NUM_2, rc( 1,  3 ) );
        m.put( KeyboardKey.NUM_3, rc( 1,  4 ) );
        m.put( KeyboardKey.NUM_4, rc( 1,  5 ) );
        m.put( KeyboardKey.NUM_5, rc( 1,  6 ) );
        m.put( KeyboardKey.NUM_6, rc( 1,  7 ) );
        m.put( KeyboardKey.NUM_7, rc( 1,  8 ) );
        m.put( KeyboardKey.NUM_8, rc( 1,  9 ) );
        m.put( KeyboardKey.NUM_9, rc( 1, 10 ) );

        // Row 2 — top letter row.
        m.put( KeyboardKey.TAB, rc( 2, 1 ) );
        m.put( KeyboardKey.Q,   rc( 2, 2 ) );
        m.put( KeyboardKey.W,   rc( 2, 3 ) );
        m.put( KeyboardKey.E,   rc( 2, 4 ) );
        m.put( KeyboardKey.T,   rc( 2, 6 ) );

        // Row 3 — home row.
        m.put( KeyboardKey.A,     rc( 3,  2 ) );
        m.put( KeyboardKey.S,     rc( 3,  3 ) );
        m.put( KeyboardKey.D,     rc( 3,  4 ) );
        m.put( KeyboardKey.F,     rc( 3,  5 ) );
        m.put( KeyboardKey.ENTER, rc( 3, 14 ) );

        // Row 4 — bottom letter row + shift.
        m.put( KeyboardKey.LEFT_SHIFT, rc( 4, 1 ) );

        // Row 5 — modifier row.
        m.put( KeyboardKey.LEFT_CTRL, rc( 5, 1 ) );
        m.put( KeyboardKey.SPACE,     rc( 5, 7 ) );

        KEY_TO_RC = Map.copyOf( m );
    }

    private static int[] rc( int row, int col ) { return new int[]{ row, col }; }

    /** Returns the (row, col) Chroma grid coordinate for {@code key},
     *  or {@code null} if the launcher doesn't map this key for the
     *  Chroma backend. The caller treats null as "leave at background
     *  color" — same graceful-degradation behavior as
     *  {@link com.micatechnologies.minecraft.launcher.rgb.backends.openrgb.OpenRgbBackend}'s
     *  unrecognized-name path. */
    static int[] coordOf( KeyboardKey key )
    {
        return KEY_TO_RC.get( key );
    }

    private ChromaKeyboardLayout() { /* static-only */ }
}
