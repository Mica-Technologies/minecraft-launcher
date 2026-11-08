// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The vendor contract [`RgbBackend`] and the "catch everything" call helpers.

use std::any::Any;
use std::panic::{AssertUnwindSafe, catch_unwind};

use crate::error::{RgbError, RgbResult};
use crate::frame::RgbFrame;

/// Stable backend identity (replaces Java's `getClass()` comparisons).
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub enum BackendKind {
    OpenRgb,
    ChromaNative,
    ChromaRest,
    WindowsDynamicLighting,
    Corsair,
    AsusAura,
    NoOp,
}

impl BackendKind {
    /// The product label used in logs, the status chip and the Settings dropdown (unlocalised
    /// product names; Java parity).
    pub const fn label(self) -> &'static str {
        match self {
            BackendKind::OpenRgb => "OpenRGB",
            BackendKind::ChromaNative => "Razer Chroma (Native)",
            BackendKind::ChromaRest => "Razer Chroma",
            BackendKind::WindowsDynamicLighting => "Windows Dynamic Lighting",
            BackendKind::Corsair => "Corsair iCUE",
            BackendKind::AsusAura => "ASUS Aura",
            BackendKind::NoOp => "None",
        }
    }

    /// `true` for the backends that load a vendor DLL into the process. These run in the
    /// isolated `--rgb-helper` child process (see [`crate::helper`]).
    pub const fn is_vendor_dll(self) -> bool {
        matches!(
            self,
            BackendKind::ChromaNative | BackendKind::Corsair | BackendKind::AsusAura
        )
    }

    /// The helper-process argument naming this kind (`None` for kinds that never run there).
    pub const fn helper_arg(self) -> Option<&'static str> {
        match self {
            BackendKind::ChromaNative => Some("chroma-native"),
            BackendKind::Corsair => Some("corsair"),
            BackendKind::AsusAura => Some("asus-aura"),
            _ => None,
        }
    }

    /// The kind named by a helper-process argument.
    pub fn from_helper_arg(arg: &str) -> Option<BackendKind> {
        [
            BackendKind::ChromaNative,
            BackendKind::Corsair,
            BackendKind::AsusAura,
        ]
        .into_iter()
        .find(|k| k.helper_arg() == Some(arg))
    }
}

/// One lighting vendor.
///
/// Calls never overlap: the controller probes and starts a backend before its worker exists,
/// renders only on the worker, and shuts down after the worker is joined, so `&mut self` is
/// enough. A backend may still be started on one thread and rendered on another.
pub trait RgbBackend: Send {
    /// Log / Settings label, e.g. `"OpenRGB"`.
    fn name(&self) -> &str;
    /// Must return quickly (< 500 ms), must not fail (false on any doubt) and must have no side
    /// effects. Called more than once (registry probe, then controller start).
    fn is_available(&mut self) -> bool;
    /// One-time setup. The controller drops the backend on error.
    fn start(&mut self) -> RgbResult<()>;
    /// Paints the background everywhere, then the per-key overrides where addressable.
    fn render_frame(&mut self, frame: &RgbFrame) -> RgbResult<()>;
    /// Releases everything and restores what it can. Must be idempotent and must never panic
    /// out (the controller also guards it).
    fn shutdown(&mut self);
    /// Stable identity for the controller's "same request" check.
    fn kind(&self) -> BackendKind;
}

/// A panic payload as text.
pub(crate) fn panic_message(p: &(dyn Any + Send)) -> String {
    if let Some(s) = p.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = p.downcast_ref::<String>() {
        s.clone()
    } else {
        "panic".to_string()
    }
}

/// Runs `f`, turning a panic into an error.
pub(crate) fn guarded<T>(f: impl FnOnce() -> RgbResult<T>) -> RgbResult<T> {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(r) => r,
        Err(p) => Err(RgbError::backend(format!(
            "panicked: {}",
            panic_message(&*p)
        ))),
    }
}

/// The backend's name, falling back to its kind's label if `name()` panics.
pub(crate) fn safe_name(b: &dyn RgbBackend) -> String {
    match catch_unwind(AssertUnwindSafe(|| b.name().to_string())) {
        Ok(n) => n,
        Err(_) => match catch_unwind(AssertUnwindSafe(|| b.kind())) {
            Ok(k) => format!("{k:?}"),
            Err(_) => "unknown".to_string(),
        },
    }
}

/// The backend's kind; a panicking `kind()` counts as `NoOp`.
pub(crate) fn safe_kind(b: &dyn RgbBackend) -> BackendKind {
    catch_unwind(AssertUnwindSafe(|| b.kind())).unwrap_or(BackendKind::NoOp)
}

/// `shutdown()` with panics contained and logged.
pub(crate) fn safe_shutdown(b: &mut dyn RgbBackend) {
    let name = safe_name(b);
    if catch_unwind(AssertUnwindSafe(|| b.shutdown())).is_err() {
        tracing::warn!(
            "{}",
            mmcl_i18n::tf!("log.rgb.controller.opThrew", format!("{name} shutdown()"))
        );
    }
}
