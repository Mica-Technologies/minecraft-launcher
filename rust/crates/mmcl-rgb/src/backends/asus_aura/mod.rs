// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! ASUS Aura through the legacy `AURA_SDK.dll` (Java `AsusAuraBackend`). Windows only; in the
//! launcher it runs inside the `--rgb-helper` child process. Paints the background only.
//!
//! Caveat carried over from Java: this binding was never validated on ASUS hardware and the
//! published header declares `CreateXController` differently (it returns a controller count
//! into a caller array). Each symbol is resolved on its own and a missing one only disables
//! that family. OpenRGB covers the same hardware and is the recommended path.

#[cfg(windows)]
pub mod sdk;

use std::sync::Arc;

use mmcl_i18n::tf;

use crate::backend::{BackendKind, RgbBackend, guarded};
use crate::backends::common::{BackgroundDedup, FailureOutcome, Families};
use crate::error::{RgbError, RgbResult};
use crate::frame::RgbFrame;

/// We drive the LEDs.
pub const MODE_SOFTWARE: i32 = 1;
/// The firmware drives them again.
pub const MODE_DEFAULT: i32 = 0;

/// The four SDK families, in init order.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub enum AuraFamily {
    Motherboard,
    Gpu,
    Ram,
    ClaymoreKeyboard,
}

impl AuraFamily {
    pub const ALL: [AuraFamily; 4] = [
        AuraFamily::Motherboard,
        AuraFamily::Gpu,
        AuraFamily::Ram,
        AuraFamily::ClaymoreKeyboard,
    ];

    /// The log label.
    pub const fn label(self) -> &'static str {
        match self {
            AuraFamily::Motherboard => "Motherboard",
            AuraFamily::Gpu => "GPU",
            AuraFamily::Ram => "RAM",
            AuraFamily::ClaymoreKeyboard => "Claymore Keyboard",
        }
    }

    /// The `Mb` / `GPU` / `RAM` part of the symbol names, as Java logs it.
    pub const fn symbol_part(self) -> &'static str {
        match self {
            AuraFamily::Motherboard => "Mb",
            AuraFamily::Gpu => "GPU",
            AuraFamily::Ram => "RAM",
            AuraFamily::ClaymoreKeyboard => "ClaymoreKeyboard",
        }
    }
}

/// The SDK calls per family. `handle` is the controller pointer as an integer.
pub trait AuraApi: Send + Sync {
    /// Whether every symbol of the family resolved.
    fn has_family(&self, f: AuraFamily) -> bool;
    /// `CreateXController(&handle)` -> `(rc, handle)`.
    fn create(&self, f: AuraFamily) -> (i32, usize);
    fn led_count(&self, f: AuraFamily, handle: usize) -> i32;
    fn set_mode(&self, f: AuraFamily, handle: usize, mode: i32) -> i32;
    /// `SetXColor(handle, buf, buf.len())`.
    fn set_color(&self, f: AuraFamily, handle: usize, buf: &[u8]) -> i32;
    fn release(&self, f: AuraFamily, handle: usize) -> i32;
}

/// `R, G, B` repeated `led_count` times (RGB order per the ASUS docs; if a user reports swapped
/// colours, BGR is the first suspect).
pub fn color_buffer(led_count: usize, packed_rgb: u32) -> Vec<u8> {
    [
        (packed_rgb >> 16) as u8,
        (packed_rgb >> 8) as u8,
        packed_rgb as u8,
    ]
    .repeat(led_count)
}

struct Connected {
    family: AuraFamily,
    handle: usize,
    leds: usize,
}

type Loader = Box<dyn Fn() -> Option<Arc<dyn AuraApi>> + Send>;

/// The ASUS Aura backend.
pub struct AsusAuraBackend {
    loader: Loader,
    api: Option<Arc<dyn AuraApi>>,
    connected: Vec<Connected>,
    dedup: BackgroundDedup,
    families: Families,
}

impl AsusAuraBackend {
    pub fn new() -> AsusAuraBackend {
        AsusAuraBackend::with_loader(Box::new(default_loader))
    }

    pub fn with_loader(loader: Loader) -> AsusAuraBackend {
        AsusAuraBackend {
            loader,
            api: None,
            connected: Vec::new(),
            dedup: BackgroundDedup::default(),
            families: Families::new(),
        }
    }

    fn api(&mut self) -> Option<Arc<dyn AuraApi>> {
        if self.api.is_none() {
            self.api = (self.loader)();
        }
        self.api.clone()
    }

    fn try_init(api: &dyn AuraApi, f: AuraFamily) -> Option<Connected> {
        if !api.has_family(f) {
            return None;
        }
        let (rc, handle) = match guarded(|| Ok(api.create(f))) {
            Ok(v) => v,
            Err(_) => {
                tracing::debug!(
                    "{}",
                    tf!("log.rgb.asusAura.createControllerThrew", f.symbol_part())
                );
                return None;
            }
        };
        if rc < 0 || handle == 0 {
            tracing::debug!(
                "{}",
                tf!("log.rgb.asusAura.noController", f.label(), rc.to_string())
            );
            return None;
        }
        let leds = guarded(|| Ok(api.led_count(f, handle))).unwrap_or(0);
        if leds <= 0 {
            tracing::debug!("{}", tf!("log.rgb.asusAura.zeroLeds", f.label()));
            let _ = guarded(|| Ok(api.release(f, handle)));
            return None;
        }
        let _ = guarded(|| Ok(api.set_mode(f, handle, MODE_SOFTWARE)));
        tracing::info!(
            "{}",
            tf!(
                "log.rgb.asusAura.familyConnected",
                f.label(),
                leds.to_string()
            )
        );
        Some(Connected {
            family: f,
            handle,
            leds: leds as usize,
        })
    }
}

impl Default for AsusAuraBackend {
    fn default() -> Self {
        AsusAuraBackend::new()
    }
}

#[cfg(windows)]
fn default_loader() -> Option<Arc<dyn AuraApi>> {
    sdk::load()
}

#[cfg(not(windows))]
fn default_loader() -> Option<Arc<dyn AuraApi>> {
    None
}

impl RgbBackend for AsusAuraBackend {
    fn name(&self) -> &str {
        BackendKind::AsusAura.label()
    }

    fn is_available(&mut self) -> bool {
        self.api().is_some()
    }

    fn start(&mut self) -> RgbResult<()> {
        self.shutdown();
        let api = self
            .api()
            .ok_or_else(|| RgbError::Unavailable("AURA_SDK.dll not loadable".into()))?;
        for f in AuraFamily::ALL {
            if let Some(c) = AsusAuraBackend::try_init(&*api, f) {
                self.connected.push(c);
            }
        }
        if self.connected.is_empty() {
            return Err(RgbError::backend(
                "ASUS Aura: no device family connected. Consider using OpenRGB, which also supports ASUS hardware.",
            ));
        }
        let summary: Vec<String> = self
            .connected
            .iter()
            .map(|c| format!("{} ({} LEDs)", c.family.label(), c.leds))
            .collect();
        tracing::info!(
            "{}",
            tf!(
                "log.rgb.asusAura.familiesConnected",
                self.connected.len().to_string(),
                summary.join(", ")
            )
        );
        Ok(())
    }

    fn render_frame(&mut self, frame: &RgbFrame) -> RgbResult<()> {
        if self.connected.is_empty() {
            return Ok(());
        }
        let Some(api) = self.api.clone() else {
            return Ok(());
        };
        let packed = frame.background().pack_rgb();
        if self.dedup.is_unchanged(packed) {
            return Ok(());
        }
        let (mut attempts, mut successes, mut last) = (0, 0, 0);
        for c in &self.connected {
            let label = c.family.label();
            if self.families.is_dropped(label) {
                continue;
            }
            attempts += 1;
            let buf = color_buffer(c.leds, packed);
            let rc = guarded(|| Ok(api.set_color(c.family, c.handle, &buf))).unwrap_or_else(|_| {
                tracing::warn!("{}", tf!("log.rgb.asusAura.setColorThrew", label));
                -1
            });
            if rc >= 0 {
                self.families.success(label);
                successes += 1;
            } else {
                last = rc;
                if let FailureOutcome::JustDropped { failures } = self.families.failure(label) {
                    tracing::info!(
                        "{}",
                        tf!(
                            "log.rgb.asusAura.givingUpOnFamily",
                            label,
                            failures.to_string(),
                            rc.to_string()
                        )
                    );
                }
            }
        }
        if attempts > 0 && successes == 0 {
            return Err(RgbError::backend(format!(
                "ASUS Aura: SetColor failed on all {attempts} family/families (rc={last})"
            )));
        }
        self.dedup.delivered(packed);
        Ok(())
    }

    fn shutdown(&mut self) {
        if let Some(api) = self.api.clone() {
            for c in self.connected.drain(..) {
                let r = guarded(|| {
                    api.set_mode(c.family, c.handle, MODE_DEFAULT);
                    api.release(c.family, c.handle);
                    Ok(())
                });
                if r.is_err() {
                    tracing::warn!("{}", tf!("log.rgb.asusAura.releaseThrew", c.family.label()));
                }
            }
        }
        self.connected.clear();
        self.dedup.reset();
        self.families.reset();
    }

    fn kind(&self) -> BackendKind {
        BackendKind::AsusAura
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::color::RgbColor;
    use parking_lot::Mutex;

    #[derive(Default)]
    struct FakeAura {
        log: Mutex<Vec<String>>,
    }

    impl AuraApi for FakeAura {
        fn has_family(&self, f: AuraFamily) -> bool {
            f != AuraFamily::ClaymoreKeyboard
        }
        fn create(&self, f: AuraFamily) -> (i32, usize) {
            match f {
                AuraFamily::Motherboard => (1, 0x10),
                AuraFamily::Gpu => (-1, 0),
                _ => (0, 0x30),
            }
        }
        fn led_count(&self, f: AuraFamily, _: usize) -> i32 {
            if f == AuraFamily::Ram { 0 } else { 2 }
        }
        fn set_mode(&self, f: AuraFamily, _: usize, mode: i32) -> i32 {
            self.log.lock().push(format!("mode {} {mode}", f.label()));
            0
        }
        fn set_color(&self, f: AuraFamily, _: usize, buf: &[u8]) -> i32 {
            self.log.lock().push(format!("color {} {buf:?}", f.label()));
            2
        }
        fn release(&self, f: AuraFamily, _: usize) -> i32 {
            self.log.lock().push(format!("release {}", f.label()));
            0
        }
    }

    #[test]
    fn families_init_render_and_release() {
        let api = Arc::new(FakeAura::default());
        let a2 = api.clone();
        let mut b =
            AsusAuraBackend::with_loader(Box::new(move || Some(a2.clone() as Arc<dyn AuraApi>)));
        b.start().unwrap();
        assert_eq!(
            b.connected.len(),
            1,
            "only the motherboard has LEDs and a controller"
        );
        b.render_frame(&RgbFrame::solid(RgbColor::new(1, 2, 3)))
            .unwrap();
        b.render_frame(&RgbFrame::solid(RgbColor::new(1, 2, 3)))
            .unwrap();
        b.shutdown();
        let log = api.log.lock().clone();
        assert_eq!(
            log,
            [
                "mode Motherboard 1",
                "release RAM",
                "color Motherboard [1, 2, 3, 1, 2, 3]",
                "mode Motherboard 0",
                "release Motherboard",
            ]
        );
        assert_eq!(color_buffer(1, 0x00AA_BBCC), vec![0xAA, 0xBB, 0xCC]);
    }
}
