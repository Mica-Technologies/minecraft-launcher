// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The vendor backends.
//!
//! On non-Windows builds the Windows backends compile to versions whose `is_available()` is
//! false, so the registry and the Settings dropdown are the same on every OS.

pub mod asus_aura;
pub mod chroma;
pub mod common;
pub mod corsair;
pub mod dynamic_lighting;
pub mod noop;
pub mod openrgb;

use crate::backend::{BackendKind, RgbBackend};

/// A fresh in-process backend of `kind` (no helper process). The vendor-DLL kinds should only
/// be made this way inside the `--rgb-helper` process; see [`crate::registry::make_backend`].
pub fn make_in_process(kind: BackendKind) -> Box<dyn RgbBackend> {
    match kind {
        BackendKind::OpenRgb => Box::new(openrgb::OpenRgbBackend::new()),
        BackendKind::ChromaNative => Box::new(chroma::native::ChromaNativeBackend::new()),
        BackendKind::ChromaRest => Box::new(chroma::rest::ChromaRestBackend::new()),
        BackendKind::WindowsDynamicLighting => {
            Box::new(dynamic_lighting::DynamicLightingBackend::new())
        }
        BackendKind::Corsair => Box::new(corsair::CorsairBackend::new()),
        BackendKind::AsusAura => Box::new(asus_aura::AsusAuraBackend::new()),
        BackendKind::NoOp => Box::new(noop::NoOpBackend),
    }
}
