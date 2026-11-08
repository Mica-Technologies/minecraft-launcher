// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Bundled fonts (`BundledFonts`): Inter (regular, italic, semibold, bold) and JetBrains Mono,
//! both SIL OFL 1.1. Register once at start-up, before any text renders.
//!
//! GPUI resolves weights within one family, so the UI uses family [`UI_FAMILY`] with
//! `FontWeight::NORMAL / SEMIBOLD / BOLD` (JavaFX needed a separate "Inter SemiBold" family) and
//! [`MONO_FAMILY`] for logs.

use std::borrow::Cow;

use gpui::App;
use mmcl_i18n::tf;

/// The UI font family.
pub const UI_FAMILY: &str = mmcl_theme::typography::INTER;
/// The monospace family (logs, `heading-display`).
pub const MONO_FAMILY: &str = mmcl_theme::typography::JETBRAINS_MONO;

/// One bundled face.
#[derive(Debug, Clone, Copy)]
pub struct BundledFace {
    /// The face's file name, e.g. `Inter-SemiBold.ttf` (for logs).
    pub file: &'static str,
    /// The font bytes.
    pub bytes: &'static [u8],
}

macro_rules! face {
    ($dir:literal, $file:literal) => {
        BundledFace {
            file: $file,
            bytes: include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../../../src/main/resources/fonts/",
                $dir,
                "/",
                $file
            )),
        }
    };
}

/// Every bundled face, in registration order.
pub const FACES: [BundledFace; 5] = [
    face!("inter", "Inter-Regular.ttf"),
    face!("inter", "Inter-Italic.ttf"),
    face!("inter", "Inter-SemiBold.ttf"),
    face!("inter", "Inter-Bold.ttf"),
    face!("jetbrains-mono", "JetBrainsMono-Regular.ttf"),
];

/// Inter's licence text (ship it with the app).
pub const INTER_LICENSE: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../../../src/main/resources/fonts/inter/OFL.txt"
));
/// JetBrains Mono's licence text (ship it with the app).
pub const JETBRAINS_MONO_LICENSE: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../../../src/main/resources/fonts/jetbrains-mono/OFL.txt"
));

/// A face GPUI's text system refused.
#[derive(Debug, thiserror::Error)]
#[error("bundled font {face} not loaded: {message}")]
pub struct FontError {
    /// The face's file name.
    pub face: &'static str,
    /// The text system's message.
    pub message: String,
}

/// Whether `bytes` look like a TrueType/OpenType font (sfnt `0x00010000`, `true` or `OTTO`).
pub fn looks_like_font(bytes: &[u8]) -> bool {
    matches!(
        bytes.get(..4),
        Some([0, 1, 0, 0]) | Some(b"true") | Some(b"OTTO")
    )
}

/// Registers every bundled face with the app's text system.
///
/// Faces are added one at a time so a bad face is logged (`log.bundledFonts.notLoaded`) and
/// skipped instead of losing the rest. Returns the faces that failed (empty on success).
pub fn register_fonts(cx: &mut App) -> Vec<FontError> {
    let mut failed = Vec::new();
    for face in FACES {
        tracing::debug!("{}", tf!("log.bundledFonts.loading", face.file));
        let result = if looks_like_font(face.bytes) {
            cx.text_system()
                .add_fonts(vec![Cow::Borrowed(face.bytes)])
                .map_err(|e| e.to_string())
        } else {
            Err("not a TrueType/OpenType file".to_string())
        };
        if let Err(message) = result {
            tracing::warn!("{}", tf!("log.bundledFonts.notLoaded", face.file));
            failed.push(FontError {
                face: face.file,
                message,
            });
        }
    }
    failed
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_face_is_a_font() {
        for face in FACES {
            assert!(looks_like_font(face.bytes), "{} is not a font", face.file);
            assert!(
                face.bytes.len() > 10_000,
                "{} is suspiciously small",
                face.file
            );
        }
        assert!(!looks_like_font(b"PK\x03\x04"));
        assert!(!looks_like_font(b""));
    }

    #[test]
    fn licences_ship() {
        assert!(INTER_LICENSE.contains("SIL OPEN FONT LICENSE"));
        assert!(JETBRAINS_MONO_LICENSE.contains("SIL OPEN FONT LICENSE"));
    }

    /// Port of `TypeScaleTest`'s weight rule: only 400/600/700 are used, and those are bundled.
    #[test]
    fn bundled_weights_cover_the_type_scale() {
        use mmcl_theme::typography::{TypeRole, Weight};
        for role in TypeRole::ALL {
            let w = role.style().weight;
            assert!(
                matches!(w, Weight::Regular | Weight::SemiBold | Weight::Bold),
                "{role:?} uses {w:?}"
            );
        }
        let files: Vec<_> = FACES.iter().map(|f| f.file).collect();
        assert!(files.contains(&"Inter-Regular.ttf"));
        assert!(files.contains(&"Inter-SemiBold.ttf"));
        assert!(files.contains(&"Inter-Bold.ttf"));
    }
}
