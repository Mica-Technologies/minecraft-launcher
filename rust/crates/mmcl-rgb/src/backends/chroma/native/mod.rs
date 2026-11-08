// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Razer Chroma through the native C++ SDK, `RzChromaSDK64.dll` (Java `ChromaNativeBackend`).
//! Windows only; in the launcher it runs inside the `--rgb-helper` child process.
//!
//! The SDK is reached through the [`ChromaApi`] trait so the per-family logic is testable on
//! every OS with a fake; [`sdk`] is the real binding.

pub mod app_info;
pub mod params;
#[cfg(windows)]
pub mod sdk;

use std::sync::Arc;

use mmcl_i18n::tf;

use crate::backend::{BackendKind, RgbBackend};
use crate::backends::chroma::layout;
use crate::backends::common::{FailureOutcome, Families, NEVER_SUCCEEDED_DROP_THRESHOLD};
use crate::color::RgbColor;
use crate::error::{RgbError, RgbResult};
use crate::frame::RgbFrame;

/// `RZEFFECTID`: a GUID, passed by value.
#[repr(C)]
#[derive(Copy, Clone, Debug, Default, PartialEq, Eq)]
pub struct Guid {
    pub data1: u32,
    pub data2: u16,
    pub data3: u16,
    pub data4: [u8; 8],
}

/// The six device families, in render order.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub enum Family {
    Keyboard,
    Mouse,
    Mousepad,
    Headset,
    Keypad,
    ChromaLink,
}

impl Family {
    pub const ALL: [Family; 6] = [
        Family::Keyboard,
        Family::Mouse,
        Family::Mousepad,
        Family::Headset,
        Family::Keypad,
        Family::ChromaLink,
    ];

    /// The log name.
    pub const fn name(self) -> &'static str {
        match self {
            Family::Keyboard => "keyboard",
            Family::Mouse => "mouse",
            Family::Mousepad => "mousepad",
            Family::Headset => "headset",
            Family::Keypad => "keypad",
            Family::ChromaLink => "chromalink",
        }
    }

    /// The effect type this backend uses for the family.
    pub const fn effect_type(self) -> i32 {
        match self {
            Family::Keyboard => params::KEYBOARD_CUSTOM,
            Family::Mouse => params::MOUSE_STATIC,
            Family::Mousepad => params::MOUSEPAD_CUSTOM2,
            Family::Headset => params::HEADSET_STATIC,
            Family::Keypad => params::KEYPAD_STATIC,
            Family::ChromaLink => params::CHROMALINK_STATIC,
        }
    }

    /// The minimum param size the SDK reads for [`Family::effect_type`].
    pub const fn param_len(self) -> usize {
        match self {
            Family::Keyboard => params::KEYBOARD_CUSTOM_PARAM_LEN,
            Family::Mouse => params::MOUSE_STATIC_PARAM_LEN,
            Family::Mousepad => params::MOUSEPAD_CUSTOM2_PARAM_LEN,
            Family::Headset | Family::Keypad | Family::ChromaLink => params::STATIC_PARAM_LEN,
        }
    }
}

/// The Chroma SDK calls the backend uses. All return `RZRESULT` (0 = success).
pub trait ChromaApi: Send + Sync {
    /// `InitSDK(&APPINFOTYPE)`. The caller keeps `app_info` alive for the whole session.
    fn init_sdk(&self, app_info: &[u8]) -> i32;
    fn uninit(&self) -> i32;
    /// `Create<Family>Effect(effect, param, &out_id)`. `param` is at least
    /// [`Family::param_len`] bytes.
    fn create_effect(&self, family: Family, effect: i32, param: &[u8], out_id: &mut Guid) -> i32;
    fn set_effect(&self, id: Guid) -> i32;
    fn delete_effect(&self, id: Guid) -> i32;
}

/// Label for a native `RZRESULT`.
pub fn describe_result(result: i32) -> &'static str {
    match result {
        0 => "SUCCESS",
        5 => "ACCESS_DENIED",
        50 => "NOT_SUPPORTED (effect type not valid for this device)",
        87 => "INVALID_PARAMETER (malformed param or wrong effect type for family)",
        126 => "MOD_NOT_FOUND (per-device Chroma module not loaded)",
        1062 => "SERVICE_NOT_ACTIVE (Razer Chroma service stopped)",
        1152 => "SINGLE_INSTANCE_APP (another Chroma app holding the device)",
        1167 => "DEVICE_NOT_CONNECTED",
        1168 => "NOT_FOUND",
        1247 => "ALREADY_INITIALIZED",
        4309 => "RESOURCE_DISABLED",
        4319 => "DEVICE_NOT_AVAILABLE",
        5023 => "NOT_VALID_STATE",
        _ => "unknown RZRESULT (see Razer Chroma SDK headers)",
    }
}

type Loader = Box<dyn Fn() -> Option<Arc<dyn ChromaApi>> + Send>;

/// The native Chroma backend.
pub struct ChromaNativeBackend {
    loader: Loader,
    api: Option<Arc<dyn ChromaApi>>,
    initialized: bool,
    /// Kept alive for the session: Synapse may read it after `InitSDK` returns.
    app_info: Option<Box<[u8]>>,
    families: Families,
}

impl ChromaNativeBackend {
    /// The backend over the real DLL (never loadable off Windows).
    pub fn new() -> ChromaNativeBackend {
        ChromaNativeBackend::with_loader(Box::new(default_loader))
    }

    /// The backend over any [`ChromaApi`] (tests).
    pub fn with_loader(loader: Loader) -> ChromaNativeBackend {
        ChromaNativeBackend {
            loader,
            api: None,
            initialized: false,
            app_info: None,
            families: Families::new(),
        }
    }

    fn api(&mut self) -> Option<Arc<dyn ChromaApi>> {
        if self.api.is_none() {
            self.api = (self.loader)();
        }
        self.api.clone()
    }

    fn try_family(&mut self, api: &dyn ChromaApi, family: Family, param: &[u8]) -> Result<(), i32> {
        debug_assert!(param.len() >= family.param_len());
        let mut id = Guid::default();
        let rc = api.create_effect(family, family.effect_type(), param, &mut id);
        if rc != 0 {
            self.record_failure(family, "Create", rc);
            return Err(rc);
        }
        let set = api.set_effect(id);
        // Always delete, even if SetEffect failed, so effect ids do not leak.
        let _ = api.delete_effect(id);
        if set != 0 {
            self.record_failure(family, "SetEffect", set);
            return Err(set);
        }
        if self.families.success(family.name()) {
            tracing::info!(
                "{}",
                tf!("log.rgb.chroma.nativeFirstFrameSucceeded", family.name())
            );
        }
        Ok(())
    }

    fn record_failure(&mut self, family: Family, op: &str, rc: i32) {
        match self.families.failure(family.name()) {
            FailureOutcome::JustDropped { .. } => tracing::info!(
                "{}",
                tf!(
                    "log.rgb.chroma.nativeGivingUpOnFamily",
                    family.name(),
                    NEVER_SUCCEEDED_DROP_THRESHOLD.to_string()
                )
            ),
            FailureOutcome::AlreadyDropped => {}
            FailureOutcome::Counted => tracing::warn!(
                "{}",
                tf!(
                    "log.rgb.chroma.nativeOpReturnedResult",
                    op,
                    family.name(),
                    rc.to_string(),
                    describe_result(rc)
                )
            ),
        }
    }

    fn push(&mut self, frame: &RgbFrame) -> RgbResult<()> {
        if !self.initialized {
            return Ok(());
        }
        let Some(api) = self.api.clone() else {
            return Ok(());
        };
        let bg = frame.background().chroma_pack();
        let keyboard = params::keyboard_custom_param(&layout::build_grid(frame));
        let mouse = params::mouse_static_param(bg);
        let mousepad = params::mousepad_custom2_param(bg);
        let stat = params::static_param(bg);
        let (mut attempts, mut successes, mut last) = (0, 0, 0);
        for family in Family::ALL {
            if self.families.is_dropped(family.name()) {
                continue;
            }
            let param: &[u8] = match family {
                Family::Keyboard => &keyboard,
                Family::Mouse => &mouse,
                Family::Mousepad => &mousepad,
                _ => &stat,
            };
            attempts += 1;
            match self.try_family(&*api, family, param) {
                Ok(()) => successes += 1,
                Err(rc) => last = rc,
            }
        }
        if attempts > 0 && successes == 0 {
            return Err(RgbError::backend(format!(
                "Razer Chroma (Native) renderFrame: all {attempts} device families failed, last result={last} {}",
                describe_result(last)
            )));
        }
        Ok(())
    }
}

impl Default for ChromaNativeBackend {
    fn default() -> Self {
        ChromaNativeBackend::new()
    }
}

#[cfg(windows)]
fn default_loader() -> Option<Arc<dyn ChromaApi>> {
    sdk::load()
}

#[cfg(not(windows))]
fn default_loader() -> Option<Arc<dyn ChromaApi>> {
    None
}

impl RgbBackend for ChromaNativeBackend {
    fn name(&self) -> &str {
        BackendKind::ChromaNative.label()
    }

    fn is_available(&mut self) -> bool {
        self.api().is_some()
    }

    fn start(&mut self) -> RgbResult<()> {
        let api = self.api().ok_or_else(|| {
            RgbError::Unavailable("RzChromaSDK64.dll not loadable (Synapse not installed?)".into())
        })?;
        let info: Box<[u8]> = app_info::launcher().into_boxed_slice();
        let rc = api.init_sdk(&info);
        if rc != 0 {
            return Err(RgbError::backend(format!(
                "Razer Chroma SDK InitSDK returned result={rc} {}",
                describe_result(rc)
            )));
        }
        self.app_info = Some(info);
        self.initialized = true;
        tracing::info!(
            "{}",
            tf!("log.rgb.chroma.nativeSdkInitialized", app_info::APP_TITLE)
        );
        Ok(())
    }

    fn render_frame(&mut self, frame: &RgbFrame) -> RgbResult<()> {
        self.push(frame)
    }

    fn shutdown(&mut self) {
        if !self.initialized {
            return;
        }
        let _ = self.push(&RgbFrame::solid(RgbColor::BLACK));
        if let Some(api) = &self.api {
            let _ = api.uninit();
        }
        self.initialized = false;
        self.app_info = None;
        self.families.reset();
    }

    fn kind(&self) -> BackendKind {
        BackendKind::ChromaNative
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use parking_lot::Mutex;

    /// A fake SDK: families listed in `working` succeed, the rest return 4319.
    #[derive(Default)]
    struct FakeSdk {
        working: Vec<Family>,
        calls: Mutex<Vec<String>>,
        init_rc: i32,
    }

    impl ChromaApi for FakeSdk {
        fn init_sdk(&self, info: &[u8]) -> i32 {
            self.calls.lock().push(format!("init {}", info.len()));
            self.init_rc
        }
        fn uninit(&self) -> i32 {
            self.calls.lock().push("uninit".into());
            0
        }
        fn create_effect(&self, f: Family, effect: i32, param: &[u8], out: &mut Guid) -> i32 {
            assert!(param.len() >= f.param_len(), "{f:?} param too small");
            assert_eq!(effect, f.effect_type());
            self.calls.lock().push(format!("create {}", f.name()));
            if self.working.contains(&f) {
                out.data1 = f as u32 + 1;
                0
            } else {
                4319
            }
        }
        fn set_effect(&self, id: Guid) -> i32 {
            self.calls.lock().push(format!("set {}", id.data1));
            0
        }
        fn delete_effect(&self, id: Guid) -> i32 {
            self.calls.lock().push(format!("delete {}", id.data1));
            0
        }
    }

    fn backend(sdk: Arc<FakeSdk>) -> ChromaNativeBackend {
        ChromaNativeBackend::with_loader(Box::new(move || Some(sdk.clone() as Arc<dyn ChromaApi>)))
    }

    #[test]
    fn keyboard_only_rig_is_delivered_and_missing_families_drop_after_five() {
        let sdk = Arc::new(FakeSdk {
            working: vec![Family::Keyboard],
            ..Default::default()
        });
        let mut b = backend(sdk.clone());
        assert!(b.is_available());
        b.start().unwrap();
        assert_eq!(sdk.calls.lock()[0], "init 3592");
        for _ in 0..6 {
            b.render_frame(&RgbFrame::solid(RgbColor::WHITE)).unwrap();
        }
        assert!(b.families.is_dropped("mouse"));
        assert!(!b.families.is_dropped("keyboard"));
        sdk.calls.lock().clear();
        b.render_frame(&RgbFrame::solid(RgbColor::WHITE)).unwrap();
        assert_eq!(*sdk.calls.lock(), ["create keyboard", "set 1", "delete 1"]);
        b.shutdown();
        assert!(sdk.calls.lock().iter().any(|c| c == "uninit"));
        b.shutdown(); // idempotent
    }

    #[test]
    fn no_razer_hardware_errors_until_every_family_is_dropped() {
        let sdk = Arc::new(FakeSdk::default());
        let mut b = backend(sdk);
        b.start().unwrap();
        for _ in 0..5 {
            let e = b
                .render_frame(&RgbFrame::solid(RgbColor::WHITE))
                .unwrap_err();
            assert!(e.to_string().contains("4319"));
        }
        // All six families dropped: a clean no-op, not a breaker failure.
        b.render_frame(&RgbFrame::solid(RgbColor::WHITE)).unwrap();
    }

    #[test]
    fn init_failure_and_missing_dll() {
        let sdk = Arc::new(FakeSdk {
            init_rc: 1062,
            ..Default::default()
        });
        let mut b = backend(sdk);
        assert!(
            b.start()
                .unwrap_err()
                .to_string()
                .contains("SERVICE_NOT_ACTIVE")
        );
        assert!(b.app_info.is_none());
        let mut none = ChromaNativeBackend::with_loader(Box::new(|| None));
        assert!(!none.is_available());
        assert!(none.start().is_err());
    }
}
