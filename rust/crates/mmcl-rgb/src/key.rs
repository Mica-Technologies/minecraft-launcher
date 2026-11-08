// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! [`KeyboardKey`]: the closed set of keys an effect may address (Java `KeyboardKey`).

/// The keys effects can light individually. Deliberately not every key: only those the in-game
/// effect and the vendor layouts need. [`KeyboardKey::canonical_name`] is load-bearing: OpenRGB
/// LED-name matching compares against it.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
#[repr(u8)]
pub enum KeyboardKey {
    W,
    A,
    S,
    D,
    E,
    Q,
    F,
    Space,
    LeftShift,
    LeftCtrl,
    Num1,
    Num2,
    Num3,
    Num4,
    Num5,
    Num6,
    Num7,
    Num8,
    Num9,
    Escape,
    Enter,
    T,
    Tab,
    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12,
}

impl KeyboardKey {
    /// Number of keys.
    pub const COUNT: usize = 35;

    /// Every key, in declaration order (`ALL[k.index()] == k`).
    pub const ALL: [KeyboardKey; KeyboardKey::COUNT] = {
        use KeyboardKey::*;
        [
            W, A, S, D, E, Q, F, Space, LeftShift, LeftCtrl, Num1, Num2, Num3, Num4, Num5, Num6,
            Num7, Num8, Num9, Escape, Enter, T, Tab, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11,
            F12,
        ]
    };

    /// The position of this key in [`KeyboardKey::ALL`].
    pub const fn index(self) -> usize {
        self as usize
    }

    /// The Java enum constant name (`"LEFT_SHIFT"`, `"NUM_1"`, ...).
    pub const fn canonical_name(self) -> &'static str {
        use KeyboardKey::*;
        match self {
            W => "W",
            A => "A",
            S => "S",
            D => "D",
            E => "E",
            Q => "Q",
            F => "F",
            Space => "SPACE",
            LeftShift => "LEFT_SHIFT",
            LeftCtrl => "LEFT_CTRL",
            Num1 => "NUM_1",
            Num2 => "NUM_2",
            Num3 => "NUM_3",
            Num4 => "NUM_4",
            Num5 => "NUM_5",
            Num6 => "NUM_6",
            Num7 => "NUM_7",
            Num8 => "NUM_8",
            Num9 => "NUM_9",
            Escape => "ESCAPE",
            Enter => "ENTER",
            T => "T",
            Tab => "TAB",
            F1 => "F1",
            F2 => "F2",
            F3 => "F3",
            F4 => "F4",
            F5 => "F5",
            F6 => "F6",
            F7 => "F7",
            F8 => "F8",
            F9 => "F9",
            F10 => "F10",
            F11 => "F11",
            F12 => "F12",
        }
    }

    /// The key whose canonical name is `name` (exact match).
    pub fn from_canonical_name(name: &str) -> Option<KeyboardKey> {
        KeyboardKey::ALL
            .into_iter()
            .find(|k| k.canonical_name() == name)
    }

    /// `NUM_1..NUM_9` for `1..=9`.
    pub const fn num(n: u8) -> Option<KeyboardKey> {
        use KeyboardKey::*;
        Some(match n {
            1 => Num1,
            2 => Num2,
            3 => Num3,
            4 => Num4,
            5 => Num5,
            6 => Num6,
            7 => Num7,
            8 => Num8,
            9 => Num9,
            _ => return None,
        })
    }

    /// `F1..F12` for `1..=12`.
    pub const fn function(n: u8) -> Option<KeyboardKey> {
        use KeyboardKey::*;
        Some(match n {
            1 => F1,
            2 => F2,
            3 => F3,
            4 => F4,
            5 => F5,
            6 => F6,
            7 => F7,
            8 => F8,
            9 => F9,
            10 => F10,
            11 => F11,
            12 => F12,
            _ => return None,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn all_is_indexed_in_order_and_names_round_trip() {
        for (i, k) in KeyboardKey::ALL.into_iter().enumerate() {
            assert_eq!(k.index(), i);
            assert_eq!(
                KeyboardKey::from_canonical_name(k.canonical_name()),
                Some(k)
            );
        }
        assert_eq!(KeyboardKey::LeftShift.canonical_name(), "LEFT_SHIFT");
        assert_eq!(KeyboardKey::num(1), Some(KeyboardKey::Num1));
        assert_eq!(KeyboardKey::function(12), Some(KeyboardKey::F12));
        assert_eq!(KeyboardKey::num(0), None);
    }
}
