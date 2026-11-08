// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The device code QR code: [`QrMatrix`] (pure, from `qrcodegen`) and [`QrCodeView`], which
//! paints it as quads. Always dark modules on a white card with a quiet zone, whatever the
//! theme, so every phone camera reads it.

use gpui::{
    App, Bounds, IntoElement, ParentElement as _, Pixels, RenderOnce, SharedString, Styled, Window,
    canvas, div, fill, point, px, rgb, size,
};
use qrcodegen::{QrCode, QrCodeEcc};

use crate::scale::u;

/// Modules of light border around the code (the QR spec asks for 4).
pub const QUIET_ZONE: usize = 4;

/// A QR code's modules.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QrMatrix {
    size: usize,
    dark: Vec<bool>,
}

impl QrMatrix {
    /// Encodes `text` (medium error correction). `None` when it is too long for a QR code.
    pub fn encode(text: &str) -> Option<QrMatrix> {
        let code = QrCode::encode_text(text, QrCodeEcc::Medium).ok()?;
        let n = usize::try_from(code.size()).ok()?;
        let mut dark = Vec::with_capacity(n * n);
        for y in 0..n {
            for x in 0..n {
                dark.push(code.get_module(x as i32, y as i32));
            }
        }
        Some(QrMatrix { size: n, dark })
    }

    /// Modules per side (without the quiet zone).
    pub fn size(&self) -> usize {
        self.size
    }

    /// Whether the module at (`x`, `y`) is dark (outside the code: light).
    pub fn is_dark(&self, x: usize, y: usize) -> bool {
        x < self.size && y < self.size && self.dark[y * self.size + x]
    }

    /// Modules per side with the quiet zone on both sides.
    pub fn total_modules(&self) -> usize {
        self.size + 2 * QUIET_ZONE
    }

    /// Horizontal runs of dark modules per row: `(x, y, len)`, so painting needs far fewer quads.
    pub fn dark_runs(&self) -> Vec<(usize, usize, usize)> {
        let mut runs = Vec::new();
        for y in 0..self.size {
            let mut x = 0;
            while x < self.size {
                if self.is_dark(x, y) {
                    let start = x;
                    while x < self.size && self.is_dark(x, y) {
                        x += 1;
                    }
                    runs.push((start, y, x - start));
                } else {
                    x += 1;
                }
            }
        }
        runs
    }
}

/// The QR code as an element of `side` px (at 100 %), with an accessible label.
#[derive(IntoElement)]
pub struct QrCodeView {
    matrix: Option<QrMatrix>,
    side: f32,
    label: SharedString,
}

impl QrCodeView {
    /// A QR code for `text`, `side` px wide and high.
    pub fn new(text: &str, side: f32, label: impl Into<SharedString>) -> Self {
        QrCodeView {
            matrix: QrMatrix::encode(text),
            side,
            label: label.into(),
        }
    }
}

impl RenderOnce for QrCodeView {
    fn render(self, _window: &mut Window, _cx: &mut App) -> impl IntoElement {
        let Some(matrix) = self.matrix else {
            return div().into_any_element();
        };
        let runs = matrix.dark_runs();
        let total = matrix.total_modules() as f32;
        div()
            .size(u(self.side))
            .flex_none()
            .rounded(px(mmcl_theme::shape::SMALL))
            .bg(rgb(0xFFFFFF))
            .overflow_hidden()
            .child(
                canvas(
                    |_, _, _| {},
                    move |bounds: Bounds<Pixels>, _, window, _| {
                        let side = bounds.size.width.min(bounds.size.height);
                        // Whole device pixels per module keep the edges crisp.
                        let module = (f32::from(side) / total).floor().max(1.0);
                        let drawn = module * total;
                        let ox = f32::from(bounds.origin.x)
                            + (f32::from(bounds.size.width) - drawn) / 2.0
                            + module * QUIET_ZONE as f32;
                        let oy = f32::from(bounds.origin.y)
                            + (f32::from(bounds.size.height) - drawn) / 2.0
                            + module * QUIET_ZONE as f32;
                        for &(x, y, len) in &runs {
                            let quad = Bounds {
                                origin: point(
                                    px(ox + x as f32 * module),
                                    px(oy + y as f32 * module),
                                ),
                                size: size(px(module * len as f32), px(module)),
                            };
                            window.paint_quad(fill(quad, rgb(0x000000)));
                        }
                    },
                )
                .size_full(),
            )
            .child(
                div()
                    .absolute()
                    .size_0()
                    .overflow_hidden()
                    .child(self.label),
            )
            .into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encodes_the_link() {
        let m = QrMatrix::encode("https://www.microsoft.com/link?otc=ABCD1234").unwrap();
        // Version 3 (29 modules) or so for ~45 bytes at medium correction.
        assert!(m.size() >= 21 && m.size() <= 41, "{}", m.size());
        assert_eq!(m.total_modules(), m.size() + 8);
        // Finder pattern: the top-left corner module is dark, the module inside its ring light.
        assert!(m.is_dark(0, 0));
        assert!(!m.is_dark(1, 1));
        assert!(m.is_dark(2, 2));
        assert!(!m.is_dark(m.size(), 0), "outside is light");
    }

    #[test]
    fn runs_cover_exactly_the_dark_modules() {
        let m = QrMatrix::encode("ABCD").unwrap();
        let mut count = 0;
        for (x, y, len) in m.dark_runs() {
            for i in 0..len {
                assert!(m.is_dark(x + i, y));
            }
            assert!(!m.is_dark(x + len, y) || x + len >= m.size());
            count += len;
        }
        let dark = (0..m.size())
            .flat_map(|y| (0..m.size()).map(move |x| (x, y)))
            .filter(|&(x, y)| m.is_dark(x, y))
            .count();
        assert_eq!(count, dark);
    }

    #[test]
    fn too_long_text_has_no_code() {
        assert!(QrMatrix::encode(&"x".repeat(8_000)).is_none());
    }
}
