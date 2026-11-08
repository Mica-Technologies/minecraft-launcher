// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! [`RgbFrame`]: one immutable lighting state (Java `RgbFrame`).

use crate::color::RgbColor;
use crate::key::KeyboardKey;

/// A background colour plus sparse per-key overrides. `Copy` and small (the effect engine
/// makes about 30 a second).
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub struct RgbFrame {
    background: RgbColor,
    overrides: [Option<RgbColor>; KeyboardKey::COUNT],
}

impl RgbFrame {
    /// A frame that is `color` everywhere.
    pub const fn solid(color: RgbColor) -> RgbFrame {
        RgbFrame {
            background: color,
            overrides: [None; KeyboardKey::COUNT],
        }
    }

    /// A frame with per-key overrides (a later duplicate key wins).
    pub fn new(
        background: RgbColor,
        overrides: impl IntoIterator<Item = (KeyboardKey, RgbColor)>,
    ) -> RgbFrame {
        let mut f = RgbFrame::solid(background);
        for (k, c) in overrides {
            f.overrides[k.index()] = Some(c);
        }
        f
    }

    /// The background colour.
    pub const fn background(&self) -> RgbColor {
        self.background
    }

    /// The colour of `key`: its override, else the background.
    pub fn color_for(&self, key: KeyboardKey) -> RgbColor {
        self.overrides[key.index()].unwrap_or(self.background)
    }

    /// The override of `key`, if any.
    pub fn override_for(&self, key: KeyboardKey) -> Option<RgbColor> {
        self.overrides[key.index()]
    }

    /// The overrides in key order.
    pub fn overrides(&self) -> impl Iterator<Item = (KeyboardKey, RgbColor)> + '_ {
        KeyboardKey::ALL
            .into_iter()
            .filter_map(|k| self.overrides[k.index()].map(|c| (k, c)))
    }

    /// `true` when the frame has no overrides.
    pub fn is_solid(&self) -> bool {
        self.overrides.iter().all(Option::is_none)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn overrides_and_equality() {
        let red = RgbColor::new(255, 0, 0);
        let f = RgbFrame::new(RgbColor::BLACK, [(KeyboardKey::W, red)]);
        assert_eq!(f.color_for(KeyboardKey::W), red);
        assert_eq!(f.color_for(KeyboardKey::A), RgbColor::BLACK);
        assert_eq!(
            f.overrides().collect::<Vec<_>>(),
            vec![(KeyboardKey::W, red)]
        );
        assert!(!f.is_solid());
        assert!(RgbFrame::solid(red).is_solid());
        assert_eq!(f, RgbFrame::new(RgbColor::BLACK, [(KeyboardKey::W, red)]));
        assert_ne!(f, RgbFrame::solid(RgbColor::BLACK));
    }
}
