// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Corsair iCUE through the iCUE SDK (`CorsairConnect` generation) (Java `CorsairBackend`).
//! Windows only; in the launcher it runs inside the `--rgb-helper` child process. Paints the
//! background only.

#[cfg(windows)]
pub mod sdk;

use std::sync::Arc;
use std::time::Duration;

use mmcl_i18n::{t, tf};

use crate::backend::{BackendKind, RgbBackend};
use crate::backends::common::{BackgroundDedup, FailureOutcome, Families};
use crate::error::{RgbError, RgbResult};
use crate::frame::RgbFrame;

pub const DEVICE_ID_LEN: usize = 128;
pub const CDT_ALL: i32 = 0x7FFF_FFFF;
pub const MAX_DEVICES: usize = 64;
pub const MAX_LEDS_PER_DEVICE: usize = 512;
pub const CONNECT_TIMEOUT: Duration = Duration::from_millis(8000);

/// `CorsairError`.
pub fn describe_error(code: i32) -> String {
    match code {
        0 => "CE_Success".into(),
        1 => "CE_NotConnected".into(),
        2 => "CE_NoControl".into(),
        3 => "CE_IncompatibleProtocol".into(),
        4 => "CE_InvalidArguments".into(),
        5 => "CE_InvalidOperation".into(),
        6 => "CE_DeviceNotFound".into(),
        7 => "CE_NotAllowed".into(),
        n => format!("unknown ({n})"),
    }
}

/// `CorsairSessionState`.
pub fn describe_session_state(state: i32) -> String {
    match state {
        0 => "CSS_Invalid".into(),
        1 => "CSS_Closed".into(),
        2 => "CSS_Connecting".into(),
        3 => "CSS_Connected".into(),
        4 => "CSS_Subscribed".into(),
        5 => "CSS_ConnectionLost".into(),
        6 => "CSS_ConnectionRefused".into(),
        7 => "CSS_ConnectionTimeout".into(),
        n => format!("unknown ({n})"),
    }
}

/// `CorsairLedColor`.
#[repr(C)]
#[derive(Copy, Clone, Debug, Default, PartialEq, Eq)]
pub struct LedColor {
    pub id: u32,
    pub r: u8,
    pub g: u8,
    pub b: u8,
    pub a: u8,
}

/// One enumerated device.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Device {
    pub id: [u8; DEVICE_ID_LEN],
    pub model: String,
    pub led_ids: Vec<u32>,
}

/// The iCUE calls the backend uses.
pub trait CorsairApi: Send + Sync {
    /// Connects and waits (up to `timeout`) for Connected/Subscribed; any other terminal state
    /// or a timeout disconnects and is an error.
    fn connect(&self, timeout: Duration) -> RgbResult<()>;
    fn disconnect(&self);
    /// Every device with at least one LED (devices whose LED query fails are skipped and
    /// logged). Err is the `GetDevices` error code.
    fn devices(&self) -> Result<Vec<Device>, i32>;
    /// `CorsairSetLedColors`; returns the `CorsairError`.
    fn set_led_colors(&self, device_id: &[u8; DEVICE_ID_LEN], colors: &[LedColor]) -> i32;
}

/// Device model text: bytes up to the first NUL, UTF-8 (lossy).
pub fn c_string(bytes: &[u8]) -> String {
    let end = bytes.iter().position(|&b| b == 0).unwrap_or(bytes.len());
    String::from_utf8_lossy(&bytes[..end]).into_owned()
}

/// Every LED of `led_ids` set to the colour, alpha 255.
pub fn led_colors(led_ids: &[u32], packed_rgb: u32) -> Vec<LedColor> {
    led_ids
        .iter()
        .map(|&id| LedColor {
            id,
            r: (packed_rgb >> 16) as u8,
            g: (packed_rgb >> 8) as u8,
            b: packed_rgb as u8,
            a: 0xFF,
        })
        .collect()
}

type Loader = Box<dyn Fn() -> Option<Arc<dyn CorsairApi>> + Send>;

/// The Corsair backend.
pub struct CorsairBackend {
    loader: Loader,
    api: Option<Arc<dyn CorsairApi>>,
    connected: bool,
    devices: Vec<Device>,
    dedup: BackgroundDedup,
    families: Families,
}

impl CorsairBackend {
    pub fn new() -> CorsairBackend {
        CorsairBackend::with_loader(Box::new(default_loader))
    }

    pub fn with_loader(loader: Loader) -> CorsairBackend {
        CorsairBackend {
            loader,
            api: None,
            connected: false,
            devices: Vec::new(),
            dedup: BackgroundDedup::default(),
            families: Families::new(),
        }
    }

    fn api(&mut self) -> Option<Arc<dyn CorsairApi>> {
        if self.api.is_none() {
            self.api = (self.loader)();
        }
        self.api.clone()
    }
}

impl Default for CorsairBackend {
    fn default() -> Self {
        CorsairBackend::new()
    }
}

#[cfg(windows)]
fn default_loader() -> Option<Arc<dyn CorsairApi>> {
    sdk::load()
}

#[cfg(not(windows))]
fn default_loader() -> Option<Arc<dyn CorsairApi>> {
    None
}

impl RgbBackend for CorsairBackend {
    fn name(&self) -> &str {
        BackendKind::Corsair.label()
    }

    fn is_available(&mut self) -> bool {
        self.api().is_some()
    }

    fn start(&mut self) -> RgbResult<()> {
        let api = self.api().ok_or_else(|| {
            RgbError::Unavailable("Corsair iCUE SDK DLL not loadable (iCUE not installed?)".into())
        })?;
        api.connect(CONNECT_TIMEOUT)?;
        self.connected = true;
        tracing::info!("{}", t!("log.rgb.corsair.sessionConnected"));
        match api.devices() {
            Ok(d) => self.devices = d,
            Err(rc) => {
                tracing::warn!(
                    "{}",
                    tf!("log.rgb.corsair.getDevicesError", describe_error(rc))
                );
                self.devices.clear();
            }
        }
        for d in &self.devices {
            tracing::info!(
                "{}",
                tf!(
                    "log.rgb.corsair.deviceRegistered",
                    d.model.as_str(),
                    d.led_ids.len().to_string()
                )
            );
        }
        if self.devices.is_empty() {
            api.disconnect();
            self.connected = false;
            return Err(RgbError::backend(
                "Corsair iCUE: connected but no devices found. Check iCUE recognises your hardware.",
            ));
        }
        tracing::info!(
            "{}",
            tf!(
                "log.rgb.corsair.devicesReady",
                self.devices.len().to_string()
            )
        );
        Ok(())
    }

    fn render_frame(&mut self, frame: &RgbFrame) -> RgbResult<()> {
        if !self.connected || self.devices.is_empty() {
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
        for d in &self.devices {
            let key = c_string(&d.id);
            if self.families.is_dropped(&key) {
                continue;
            }
            attempts += 1;
            let colors = led_colors(&d.led_ids, packed);
            let rc = crate::backend::guarded(|| Ok(api.set_led_colors(&d.id, &colors)))
                .unwrap_or_else(|_| {
                    tracing::warn!(
                        "{}",
                        tf!("log.rgb.corsair.setLedColorsThrew", d.model.as_str())
                    );
                    5
                });
            if rc == 0 {
                self.families.success(&key);
                successes += 1;
            } else {
                last = rc;
                if let FailureOutcome::JustDropped { failures } = self.families.failure(&key) {
                    tracing::info!(
                        "{}",
                        tf!(
                            "log.rgb.corsair.givingUpOnDevice",
                            d.model.as_str(),
                            failures.to_string(),
                            describe_error(rc)
                        )
                    );
                }
            }
        }
        if attempts > 0 && successes == 0 {
            return Err(RgbError::backend(format!(
                "Corsair iCUE: SetLedColors failed on all {attempts} device(s), last error {}",
                describe_error(last)
            )));
        }
        self.dedup.delivered(packed);
        Ok(())
    }

    fn shutdown(&mut self) {
        if self.connected
            && let Some(api) = &self.api
        {
            api.disconnect();
        }
        self.connected = false;
        self.devices.clear();
        self.dedup.reset();
        self.families.reset();
    }

    fn kind(&self) -> BackendKind {
        BackendKind::Corsair
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::color::RgbColor;
    use parking_lot::Mutex;

    struct FakeIcue {
        devices: Vec<Device>,
        failing: Vec<u8>, // first id byte of devices that fail
        pushes: Mutex<Vec<(u8, usize, LedColor)>>,
        disconnects: Mutex<u32>,
    }

    impl CorsairApi for FakeIcue {
        fn connect(&self, _: Duration) -> RgbResult<()> {
            Ok(())
        }
        fn disconnect(&self) {
            *self.disconnects.lock() += 1;
        }
        fn devices(&self) -> Result<Vec<Device>, i32> {
            Ok(self.devices.clone())
        }
        fn set_led_colors(&self, id: &[u8; DEVICE_ID_LEN], colors: &[LedColor]) -> i32 {
            self.pushes.lock().push((id[0], colors.len(), colors[0]));
            if self.failing.contains(&id[0]) { 6 } else { 0 }
        }
    }

    fn device(tag: u8, leds: u32) -> Device {
        let mut id = [0u8; DEVICE_ID_LEN];
        id[0] = tag;
        Device {
            id,
            model: format!("dev{tag}"),
            led_ids: (1..=leds).collect(),
        }
    }

    #[test]
    fn dedup_partial_rig_and_drop() {
        let api = Arc::new(FakeIcue {
            devices: vec![device(b'a', 3), device(b'b', 2)],
            failing: vec![b'b'],
            pushes: Mutex::new(Vec::new()),
            disconnects: Mutex::new(0),
        });
        let a2 = api.clone();
        let mut b =
            CorsairBackend::with_loader(Box::new(move || Some(a2.clone() as Arc<dyn CorsairApi>)));
        b.start().unwrap();
        let red = RgbFrame::solid(RgbColor::new(255, 0, 0));
        b.render_frame(&red).unwrap();
        assert_eq!(
            api.pushes.lock()[0],
            (
                b'a',
                3,
                LedColor {
                    id: 1,
                    r: 255,
                    g: 0,
                    b: 0,
                    a: 255
                }
            )
        );
        b.render_frame(&red).unwrap();
        assert_eq!(
            api.pushes.lock().len(),
            2,
            "unchanged background is skipped"
        );
        for i in 0..6u8 {
            b.render_frame(&RgbFrame::solid(RgbColor::new(i, 0, 0)))
                .unwrap();
        }
        assert!(b.families.is_dropped("b"));
        b.shutdown();
        assert_eq!(*api.disconnects.lock(), 1);
        b.shutdown();
        assert_eq!(*api.disconnects.lock(), 1, "idempotent");
    }

    #[test]
    fn no_devices_is_a_start_error() {
        let api = Arc::new(FakeIcue {
            devices: vec![],
            failing: vec![],
            pushes: Mutex::new(Vec::new()),
            disconnects: Mutex::new(0),
        });
        let a2 = api.clone();
        let mut b =
            CorsairBackend::with_loader(Box::new(move || Some(a2.clone() as Arc<dyn CorsairApi>)));
        assert!(b.start().is_err());
        assert_eq!(*api.disconnects.lock(), 1);
        assert_eq!(c_string(b"K70\0junk"), "K70");
    }
}
