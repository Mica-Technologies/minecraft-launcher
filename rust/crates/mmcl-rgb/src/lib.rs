// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! RGB peripheral lighting for the Mica Minecraft Launcher (Java `rgb/`).
//!
//! When RGB is on, the launcher lights keyboards, mice, fans and the like through whatever
//! vendor SDKs are present: OpenRGB (TCP), Razer Chroma (native DLL, plus the deprecated REST
//! API), Corsair iCUE (DLL), ASUS Aura (legacy DLL) and Windows Dynamic Lighting (WinRT). The
//! menus show an idle effect in the theme accent (or the focused pack's logo colours); a running
//! game shows its pack colour with the movement keys highlighted.
//!
//! - [`controller::RgbController`]: backends, frame queue (capacity 4, drop when full), the
//!   `mica-rgb` worker, a per-backend circuit breaker ([`health`]), and controller-level frame
//!   dedup (an identical frame is re-sent at most once per second).
//! - [`engine`]: the 33 ms effect ticker; [`effect`]: the pure effects.
//! - [`registry`]: which backends the settings ask for. Every `rgbBackend` value works,
//!   including `corsair` and `asus-aura` (a Java bug made those two unselectable).
//! - [`helper`]: the vendor-DLL backends run in a `--rgb-helper` child process, so a native
//!   crash in a vendor SDK cannot kill the launcher.
//! - [`integration`]: menu / in-game / bootstrap glue over an [`integration::RgbHost`].
//! - [`palette`]: the pack-logo dominant-colour sampler (pure, `image` crate, no GPUI);
//!   [`theme`]: theme accents and derived palettes.
//!
//! Every failure is contained and logged silently; the launcher keeps working if every backend
//! fails.

pub mod backend;
pub mod backends;
pub mod color;
pub mod controller;
pub mod effect;
mod engine;
pub mod error;
pub mod frame;
pub mod health;
pub mod helper;
pub mod integration;
pub mod key;
pub mod palette;
pub mod registry;
pub mod settings;
pub mod theme;

pub use backend::{BackendKind, RgbBackend};
pub use color::RgbColor;
pub use controller::{BackendStatus, Clock, MonotonicClock, RgbController, Status};
pub use effect::{CycleEffect, InGameEffect, PulseEffect, RainbowEffect, RgbEffect, SolidEffect};
pub use engine::TICK_INTERVAL_MS;
pub use error::{RgbError, RgbResult};
pub use frame::RgbFrame;
pub use health::{BackendHealth, HealthState};
pub use helper::{HELPER_ARG, helper_requested, run_helper};
pub use integration::{PackRef, RgbHost, RgbIntegration};
pub use key::KeyboardKey;
pub use palette::{PaletteCache, sample_palette, sample_palette_file};
pub use registry::{filter_candidates, resolve_backends};
pub use settings::{AutoToggles, BackendChoice, EffectStyle, RgbSettings};
pub use theme::{accent_for_theme, derive_palette};

#[cfg(test)]
mod tests;
