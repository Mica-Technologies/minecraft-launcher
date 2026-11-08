// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Razer native effect types and param buffers (Java `ChromaEffectTypes`). Pure, so the sizes
//! are pinned by tests on every OS.
//!
//! CRASH RULE: every buffer must be at least the size of the struct the SDK reads for that
//! effect type. The mousepad CUSTOM2 param was once 4 bytes; the SDK copies 80, and reading
//! past the allocation was an access violation inside `RzChromaSDK64.dll` that killed the
//! launcher silently (2026-10).

use crate::backends::chroma::layout::{COLS, Grid, ROWS};

/// Effect type integers differ per family (mixing them up gives result 87 forever).
pub const KEYBOARD_STATIC: i32 = 4;
pub const KEYBOARD_CUSTOM: i32 = 2;
pub const MOUSE_STATIC: i32 = 6;
/// Mousepad `CHROMA_CUSTOM2` (Java's constant is named `MOUSEPAD_STATIC`).
pub const MOUSEPAD_CUSTOM2: i32 = 6;
pub const MOUSEPAD_CUSTOM2_LEDS: usize = 20;
pub const HEADSET_STATIC: i32 = 1;
pub const KEYPAD_STATIC: i32 = 4;
pub const CHROMALINK_STATIC: i32 = 2;
pub const CHROMALINK_CUSTOM: i32 = 1;

/// `KEYBOARD_CUSTOM`: `COLORREF[6][22]`, 528 bytes, entry at `(row * 22 + col) * 4`.
pub const KEYBOARD_CUSTOM_PARAM_LEN: usize = ROWS * COLS * 4;
/// Mouse `STATIC`: `{ u32 LEDId (0 = all), COLORREF }`.
pub const MOUSE_STATIC_PARAM_LEN: usize = 8;
/// Mousepad `CUSTOM2`: `COLORREF[20]`.
pub const MOUSEPAD_CUSTOM2_PARAM_LEN: usize = MOUSEPAD_CUSTOM2_LEDS * 4;
/// Headset / keypad / ChromaLink `STATIC`: one `COLORREF`.
pub const STATIC_PARAM_LEN: usize = 4;

/// One `COLORREF`.
pub fn static_param(packed: u32) -> Vec<u8> {
    packed.to_le_bytes().to_vec()
}

/// `{0, colour}`.
pub fn mouse_static_param(packed: u32) -> Vec<u8> {
    let mut v = Vec::with_capacity(MOUSE_STATIC_PARAM_LEN);
    v.extend_from_slice(&0u32.to_le_bytes());
    v.extend_from_slice(&packed.to_le_bytes());
    v
}

/// Every one of the 20 LEDs set to `packed`.
pub fn mousepad_custom2_param(packed: u32) -> Vec<u8> {
    packed.to_le_bytes().repeat(MOUSEPAD_CUSTOM2_LEDS)
}

/// The 6x22 grid, row-major.
pub fn keyboard_custom_param(grid: &Grid) -> Vec<u8> {
    let mut v = Vec::with_capacity(KEYBOARD_CUSTOM_PARAM_LEN);
    for row in grid {
        for c in row {
            v.extend_from_slice(&c.to_le_bytes());
        }
    }
    v
}

#[cfg(test)]
mod tests {
    use super::*;

    fn u32_at(b: &[u8], off: usize) -> u32 {
        u32::from_le_bytes(b[off..off + 4].try_into().unwrap())
    }

    #[test]
    fn mousepad_custom2_is_type_6_and_80_bytes_of_colour() {
        assert_eq!(MOUSEPAD_CUSTOM2, 6);
        let p = mousepad_custom2_param(0x00AB_CDEF);
        assert_eq!(p.len(), 80);
        for i in 0..20 {
            assert_eq!(u32_at(&p, i * 4), 0x00AB_CDEF);
        }
    }

    #[test]
    fn keyboard_param_layout() {
        let mut grid = [[0u32; COLS]; ROWS];
        grid[5][21] = 0x0012_3456;
        let p = keyboard_custom_param(&grid);
        assert_eq!(p.len(), 528);
        assert_eq!(u32_at(&p, (5 * 22 + 21) * 4), 0x0012_3456);
        assert_eq!(u32_at(&p, 0), 0);
    }

    #[test]
    fn mouse_param_is_all_leds_then_colour() {
        let p = mouse_static_param(0x00FF_0000);
        assert_eq!(p.len(), 8);
        assert_eq!(u32_at(&p, 0), 0);
        assert_eq!(u32_at(&p, 4), 0x00FF_0000);
    }

    #[test]
    fn static_param_is_one_colorref() {
        let p = static_param(0x0000_00FF);
        assert_eq!(p.len(), 4);
        assert_eq!(u32_at(&p, 0), 0xFF);
    }
}
