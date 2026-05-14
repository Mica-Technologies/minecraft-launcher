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

package com.micatechnologies.minecraft.launcher.rgb.backends.chromanative;

import com.sun.jna.Memory;

/**
 * Effect-type enum values + parameter-struct layouts for each Razer
 * Chroma device family.
 *
 * <p>The native SDK groups effects under per-device namespaces
 * ({@code ChromaSDK::Keyboard::EFFECT_TYPE},
 * {@code ChromaSDK::Mouse::EFFECT_TYPE}, etc.), and the enum integer
 * values are <em>different</em> across families even when the effect's
 * name is the same. {@code CHROMA_STATIC} is {@code 4} on a keyboard
 * but {@code 2} on a ChromaLink controller, for instance. Mixing up
 * the integers produces {@code result=87} (INVALID_PARAMETER) which
 * looks like a generic SDK failure until you trace through the
 * headers.</p>
 *
 * <p>The constants below come from the Razer Chroma SDK headers
 * ({@code RzChromaSDKDefines.h}) verbatim. Values are immutable parts
 * of Razer's ABI — they're as stable as the SDK itself.</p>
 *
 * @since 2026.5
 */
final class ChromaEffectTypes
{
    private ChromaEffectTypes() { /* static-only */ }

    // ============================================================
    //  Keyboard effects — ChromaSDK::Keyboard::EFFECT_TYPE
    // ============================================================

    /** Static single-color fill across all keys. Param = COLORREF. */
    static final int KEYBOARD_STATIC = 4;

    /** Per-key 6×22 custom grid. Param = COLORREF[6][22] (528 bytes). */
    static final int KEYBOARD_CUSTOM = 2;

    // ============================================================
    //  Mouse effects — ChromaSDK::Mouse::EFFECT_TYPE
    //  (full enum: NONE=0, BLINKING=1, BREATHING=2, CUSTOM=3,
    //   REACTIVE=4, SPECTRUMCYCLING=5, STATIC=6, WAVE=7, CUSTOM2=8)
    // ============================================================

    /** Static single-color fill across all mouse zones.
     *  Param layout: { LEDId (uint32 = 0 for "all zones"), Color (COLORREF) }. */
    static final int MOUSE_STATIC = 6;

    // ============================================================
    //  Mousepad effects — ChromaSDK::Mousepad::EFFECT_TYPE
    //  (full enum: NONE=0, BREATHING=1, CUSTOM=2, SPECTRUMCYCLING=3,
    //   STATIC=4, WAVE=5, CUSTOM2=6)
    //
    //  We pick CUSTOM2 (6) here rather than STATIC (4): CUSTOM2 is the
    //  modern path Razer points new integrations at — the deprecated
    //  STATIC enum is "may still work but don't rely on it" in their
    //  current SDK headers. Param is still a bare COLORREF; CUSTOM2 just
    //  bypasses Synapse's legacy preset cache.
    // ============================================================

    /** Param = COLORREF. */
    static final int MOUSEPAD_STATIC = 6;

    // ============================================================
    //  Headset effects — ChromaSDK::Headset::EFFECT_TYPE
    //  (full enum: NONE=0, STATIC=1, BREATHING=2, SPECTRUMCYCLING=3,
    //   CUSTOM=4)
    // ============================================================

    /** Param = COLORREF. */
    static final int HEADSET_STATIC = 1;

    // ============================================================
    //  Keypad effects — ChromaSDK::Keypad::EFFECT_TYPE
    //  (full enum: NONE=0, BREATHING=1, CUSTOM=2, REACTIVE=3,
    //   STATIC=4, SPECTRUMCYCLING=5, WAVE=6)
    // ============================================================

    /** Param = COLORREF. */
    static final int KEYPAD_STATIC = 4;

    // ============================================================
    //  ChromaLink effects — ChromaSDK::ChromaLink::EFFECT_TYPE
    //  This is the family that drives third-party ARGB controllers
    //  (RGB fan hubs, light strips, etc.) — the user's setup.
    // ============================================================

    /** Single-color fill across every Chromalink-attached zone.
     *  Param = COLORREF. */
    static final int CHROMALINK_STATIC = 2;

    /** Per-LED 1×5 custom array (Chromalink exposes 5 logical zones).
     *  Param = COLORREF[5] (20 bytes). */
    static final int CHROMALINK_CUSTOM = 1;

    // ============================================================
    //  Param builders
    // ============================================================

    /** Allocates a 4-byte param block holding a single COLORREF.
     *  Used by every {@code _STATIC} effect that takes a bare color
     *  (keyboard, mousepad, headset, keypad, chromalink). Mouse is
     *  special — see {@link #buildMouseStaticParam}. */
    static Memory buildStaticParam( int packedColor )
    {
        Memory m = new Memory( 4 );
        m.setInt( 0, packedColor );
        return m;
    }

    /** Mouse static effect param: {@code { LEDId, Color }}. LEDId 0
     *  means "all zones" — covers the whole device with one color. */
    static Memory buildMouseStaticParam( int packedColor )
    {
        Memory m = new Memory( 8 );
        m.setInt( 0, 0 );             // LEDId = ALL
        m.setInt( 4, packedColor );   // Color
        return m;
    }

    /** Keyboard CHROMA_CUSTOM param: 6 rows × 22 cols × 4-byte COLORREF.
     *  {@code grid[row][col]} maps to byte offset
     *  {@code (row * 22 + col) * 4}. */
    static Memory buildKeyboardCustomParam( int[][] grid )
    {
        int rows = 6;
        int cols = 22;
        Memory m = new Memory( (long) rows * cols * 4L );
        for ( int r = 0; r < rows; r++ ) {
            for ( int c = 0; c < cols; c++ ) {
                m.setInt( ( (long) r * cols + c ) * 4L, grid[ r ][ c ] );
            }
        }
        return m;
    }
}
