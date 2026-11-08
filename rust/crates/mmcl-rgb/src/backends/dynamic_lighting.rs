// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Windows Dynamic Lighting (`Windows.Devices.Lights.LampArray`) (Java
//! `WindowsDynamicLightingBackend`). Uses the projected WinRT types from the `windows` crate, so
//! none of Java's vtable bridge is needed. Runs in-process: it loads no vendor DLL.
//!
//! Only HID Lighting & Illumination devices the user enabled under Settings > Personalization >
//! Dynamic Lighting take part. Paints the background only.

use crate::backend::{BackendKind, RgbBackend};
use crate::backends::common::BackgroundDedup;
use crate::error::{RgbError, RgbResult};
use crate::frame::RgbFrame;

/// How long one WinRT async operation may take.
pub const ASYNC_TIMEOUT_MS: u64 = 5000;
/// How often its status is polled.
pub const ASYNC_POLL_MS: u64 = 25;

/// The Dynamic Lighting backend.
pub struct DynamicLightingBackend {
    started: bool,
    dedup: BackgroundDedup,
    #[cfg(windows)]
    lamps: Vec<imp::Lamp>,
}

impl DynamicLightingBackend {
    pub fn new() -> DynamicLightingBackend {
        DynamicLightingBackend {
            started: false,
            dedup: BackgroundDedup::default(),
            #[cfg(windows)]
            lamps: Vec::new(),
        }
    }
}

impl Default for DynamicLightingBackend {
    fn default() -> Self {
        DynamicLightingBackend::new()
    }
}

impl RgbBackend for DynamicLightingBackend {
    fn name(&self) -> &str {
        BackendKind::WindowsDynamicLighting.label()
    }

    fn is_available(&mut self) -> bool {
        cfg!(windows)
    }

    #[cfg(windows)]
    fn start(&mut self) -> RgbResult<()> {
        self.shutdown();
        self.lamps = imp::open_all()?;
        self.started = true;
        Ok(())
    }

    #[cfg(not(windows))]
    fn start(&mut self) -> RgbResult<()> {
        Err(RgbError::Unavailable(
            "Windows Dynamic Lighting needs Windows".into(),
        ))
    }

    fn render_frame(&mut self, frame: &RgbFrame) -> RgbResult<()> {
        if !self.started {
            return Ok(());
        }
        let c = frame.background();
        let packed = c.pack_rgb();
        if self.dedup.is_unchanged(packed) {
            return Ok(());
        }
        #[cfg(windows)]
        {
            imp::ensure_thread_init()?;
            let mut ok = 0;
            let mut last = None;
            for lamp in &self.lamps {
                match lamp.set_color(c) {
                    Ok(()) => ok += 1,
                    Err(e) => {
                        tracing::warn!(
                            "{}: {e}",
                            mmcl_i18n::t!("log.rgb.dynamicLighting.setColorThrew")
                        );
                        last = Some(e);
                    }
                }
            }
            if ok == 0 && !self.lamps.is_empty() {
                return Err(RgbError::backend(format!(
                    "Windows Dynamic Lighting: SetColor failed on every device: {}",
                    last.map(|e| e.to_string()).unwrap_or_default()
                )));
            }
        }
        self.dedup.delivered(packed);
        Ok(())
    }

    fn shutdown(&mut self) {
        self.started = false;
        #[cfg(windows)]
        self.lamps.clear();
        self.dedup.reset();
    }

    fn kind(&self) -> BackendKind {
        BackendKind::WindowsDynamicLighting
    }
}

#[cfg(windows)]
mod imp {
    use std::cell::Cell;
    use std::time::{Duration, Instant};

    use mmcl_i18n::tf;
    use windows::Devices::Enumeration::DeviceInformation;
    use windows::Devices::Lights::LampArray;
    use windows::Win32::System::WinRT::{RO_INIT_MULTITHREADED, RoInitialize};
    use windows::core::{HSTRING, RuntimeType};
    use windows_future::{AsyncStatus, IAsyncOperation};

    use super::{ASYNC_POLL_MS, ASYNC_TIMEOUT_MS};
    use crate::color::RgbColor;
    use crate::error::{RgbError, RgbResult};

    /// `RPC_E_CHANGED_MODE`: the thread is already in another apartment.
    const RPC_E_CHANGED_MODE: i32 = 0x8001_0106_u32 as i32;

    thread_local! {
        static INIT: Cell<bool> = const { Cell::new(false) };
    }

    /// Joins this thread to the MTA once (never uninitialised: tearing the MTA down could break
    /// a thread that still holds objects).
    pub(super) fn ensure_thread_init() -> RgbResult<()> {
        if INIT.with(Cell::get) {
            return Ok(());
        }
        // SAFETY: plain apartment initialisation of the calling thread.
        match unsafe { RoInitialize(RO_INIT_MULTITHREADED) } {
            Ok(()) => {}
            Err(e) if e.code().0 == RPC_E_CHANGED_MODE => {
                return Err(RgbError::backend(format!(
                    "Windows Dynamic Lighting: WinRT apartment conflict ({e})"
                )));
            }
            // S_FALSE (already initialised) maps to Ok; anything else is fatal.
            Err(e) => return Err(RgbError::backend(format!("RoInitialize failed: {e}"))),
        }
        INIT.with(|c| c.set(true));
        Ok(())
    }

    /// Polls `op` every 25 ms for up to 5 s (the blocking `get()` has no timeout).
    fn wait<T: RuntimeType + 'static>(op: &IAsyncOperation<T>, what: &str) -> RgbResult<T> {
        let deadline = Instant::now() + Duration::from_millis(ASYNC_TIMEOUT_MS);
        loop {
            let status = op
                .Status()
                .map_err(|e| RgbError::backend(format!("{what}: {e}")))?;
            if status == AsyncStatus::Completed {
                return op
                    .GetResults()
                    .map_err(|e| RgbError::backend(format!("{what}: {e}")));
            }
            if status == AsyncStatus::Canceled {
                return Err(RgbError::backend(format!("{what} was cancelled")));
            }
            if status == AsyncStatus::Error {
                return Err(RgbError::backend(format!("{what} reported an error")));
            }
            if Instant::now() >= deadline {
                let _ = op.Cancel();
                return Err(RgbError::backend(format!("{what} timed out")));
            }
            std::thread::sleep(Duration::from_millis(ASYNC_POLL_MS));
        }
    }

    /// One opened LampArray.
    pub(super) struct Lamp {
        lamp: LampArray,
    }

    // MTA objects may be used from any MTA thread; every thread that touches a lamp joins the
    // MTA first (ensure_thread_init).
    unsafe impl Send for Lamp {}

    impl Lamp {
        pub(super) fn set_color(&self, c: RgbColor) -> windows::core::Result<()> {
            self.lamp.SetColor(windows::UI::Color {
                A: 255,
                R: c.r,
                G: c.g,
                B: c.b,
            })
        }
    }

    pub(super) fn open_all() -> RgbResult<Vec<Lamp>> {
        ensure_thread_init()?;
        let selector: HSTRING = LampArray::GetDeviceSelector()
            .map_err(|e| RgbError::backend(format!("GetDeviceSelector: {e}")))?;
        tracing::debug!(
            "{}",
            tf!(
                "log.rgb.dynamicLighting.deviceSelector",
                selector.to_string()
            )
        );
        let op = DeviceInformation::FindAllAsyncAqsFilter(&selector)
            .map_err(|e| RgbError::backend(format!("FindAllAsyncAqsFilter: {e}")))?;
        let infos = wait(&op, "Windows Dynamic Lighting device enumeration")?;
        let n = infos.Size().unwrap_or(0);
        tracing::debug!(
            "{}",
            tf!("log.rgb.dynamicLighting.devicesReported", n.to_string())
        );
        let mut lamps = Vec::new();
        for i in 0..n {
            let id = match infos.GetAt(i).and_then(|d| d.Id()) {
                Ok(id) => id,
                Err(e) => {
                    tracing::warn!(
                        "{}: {e}",
                        tf!("log.rgb.dynamicLighting.openLampArrayFailed", i.to_string())
                    );
                    continue;
                }
            };
            let opened = LampArray::FromIdAsync(&id)
                .map_err(|e| RgbError::backend(e.to_string()))
                .and_then(|op| wait(&op, "FromIdAsync"));
            match opened {
                Ok(lamp) => {
                    tracing::debug!(
                        "{}",
                        tf!("log.rgb.dynamicLighting.openedLampArray", id.to_string())
                    );
                    lamps.push(Lamp { lamp });
                }
                Err(e) => tracing::warn!(
                    "{}",
                    tf!(
                        "log.rgb.dynamicLighting.fromIdAsyncFailed",
                        e.to_string(),
                        id.to_string()
                    )
                ),
            }
        }
        if lamps.is_empty() {
            return Err(RgbError::backend(format!(
                "Windows Dynamic Lighting: enumeration reported {n} device(s) but none opened; check that 'Use dynamic lighting on my devices' is on"
            )));
        }
        tracing::info!(
            "{}",
            tf!(
                "log.rgb.dynamicLighting.devicesReady",
                lamps.len().to_string()
            )
        );
        Ok(lamps)
    }
}
