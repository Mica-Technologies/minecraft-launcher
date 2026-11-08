// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Theme data for the Mica Minecraft Launcher, with no UI-toolkit dependency.
//!
//! - [`Tokens`]: every `-color-*` and `-md-*` token of the seven token sheets as typed
//!   [`Rgba`] (`0xRRGGBBAA`) fields, generated at build time from the Java launcher's
//!   `src/main/resources/ui/ui-tokens-*.css`.
//! - [`ThemeId`]: the seven configurable themes with their exact config strings.
//! - [`resolve`]: `(ThemeId, os_dark, Platform) -> TokenSheet`, plus [`is_light_chrome`],
//!   [`solid_bg`], [`backdrop`] and [`window_fill`] for the window chrome.
//! - [`shape`], [`typography`], [`motion`]: the radius, type and motion scales.
//! - [`contrast`]: the WCAG contrast checker the token tests use.
//!
//! The GPUI bridge (`ActiveTheme` global, `Hsla` conversion) lives in `mmcl-ui-kit`; use
//! [`Rgba::to_f32`] / [`Rgba::to_hsla`] there.

mod color;
pub mod contrast;
#[allow(dead_code)] // shared with build.rs; parts are only used by tests
pub(crate) mod css;
pub mod motion;
pub mod shape;
mod theme;
mod tokens;
pub mod typography;

pub use color::Rgba;
pub use theme::{
    Backdrop, MICA_MIN_WINDOWS_BUILD, Platform, Theme, ThemeId, backdrop, chrome_changed,
    is_light_chrome, mica_supported, resolve, resolve_config, resolve_detected, window_fill,
};
pub use tokens::{
    NativeOverrides, TOKEN_COUNT, TOKEN_NAMES, TOKEN_REFERENCES, TokenSheet, Tokens, bg_hex,
    solid_bg, tokens,
};
