// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Razer 6x22 keyboard grid (Java `ChromaKeyboardLayout`), shared by the native and REST
//! paths. Coordinates mirror Razer's `RZKEY_*` values, `(row << 8) | col`.

use crate::frame::RgbFrame;
use crate::key::KeyboardKey;

pub const ROWS: usize = 6;
pub const COLS: usize = 22;

/// A grid of `COLORREF`s (`(b << 16) | (g << 8) | r`).
pub type Grid = [[u32; COLS]; ROWS];

/// The grid position of `key`.
pub fn coord_of(key: KeyboardKey) -> Option<(usize, usize)> {
    use KeyboardKey::*;
    Some(match key {
        Escape => (0, 1),
        Num1 | Num2 | Num3 | Num4 | Num5 | Num6 | Num7 | Num8 | Num9 => {
            (1, 2 + (key.index() - Num1.index()))
        }
        F1 | F2 | F3 | F4 | F5 | F6 | F7 | F8 | F9 | F10 | F11 | F12 => {
            (0, 3 + (key.index() - F1.index()))
        }
        Tab => (2, 1),
        Q => (2, 2),
        W => (2, 3),
        E => (2, 4),
        T => (2, 6),
        A => (3, 2),
        S => (3, 3),
        D => (3, 4),
        F => (3, 5),
        Enter => (3, 14),
        LeftShift => (4, 1),
        LeftCtrl => (5, 1),
        Space => (5, 7),
    })
}

/// The frame as a grid: the background everywhere, then each override at its key.
pub fn build_grid(frame: &RgbFrame) -> Grid {
    let mut grid = [[frame.background().chroma_pack(); COLS]; ROWS];
    for (key, color) in frame.overrides() {
        if let Some((r, c)) = coord_of(key) {
            grid[r][c] = color.chroma_pack();
        }
    }
    grid
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::color::RgbColor;

    #[test]
    fn placements() {
        assert_eq!(coord_of(KeyboardKey::Escape), Some((0, 1)));
        assert_eq!(coord_of(KeyboardKey::F1), Some((0, 3)));
        assert_eq!(coord_of(KeyboardKey::F12), Some((0, 14)));
        assert_eq!(coord_of(KeyboardKey::Num1), Some((1, 2)));
        assert_eq!(coord_of(KeyboardKey::Num9), Some((1, 10)));
        assert_eq!(coord_of(KeyboardKey::Space), Some((5, 7)));
        for k in KeyboardKey::ALL {
            let (r, c) = coord_of(k).unwrap();
            assert!(r < ROWS && c < COLS);
        }
    }

    #[test]
    fn grid_fill_and_overrides() {
        let bg = RgbColor::new(1, 2, 3);
        let hi = RgbColor::new(255, 0, 0);
        let g = build_grid(&RgbFrame::new(bg, [(KeyboardKey::W, hi)]));
        assert_eq!(g[2][3], 0x0000_00FF);
        assert_eq!(g[0][0], bg.chroma_pack());
        assert_eq!(g[5][21], bg.chroma_pack());
    }
}
