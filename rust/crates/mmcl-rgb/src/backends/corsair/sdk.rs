// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The real iCUE SDK binding (Java `CorsairSdkLibrary`), via `libloading`.

use std::ffi::{c_char, c_void};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};

use libloading::Library;
use parking_lot::{Condvar, Mutex};

use super::{
    CDT_ALL, CorsairApi, DEVICE_ID_LEN, Device, LedColor, MAX_DEVICES, MAX_LEDS_PER_DEVICE,
    c_string, describe_error, describe_session_state,
};
use crate::error::{RgbError, RgbResult};

/// DLL names tried in order.
pub const DLL_CANDIDATES: [&str; 4] = [
    "iCUESDK.x64_2019.dll",
    "iCUESDK.x64_2017.dll",
    "iCUESDK_x64.dll",
    "iCUESDK.dll",
];

const CSS_CLOSED: i32 = 1;
const CSS_CONNECTED: i32 = 3;
const CSS_SUBSCRIBED: i32 = 4;
const CSS_CONNECTION_LOST: i32 = 5;
const CSS_CONNECTION_REFUSED: i32 = 6;
const CSS_CONNECTION_TIMEOUT: i32 = 7;

#[repr(C)]
struct DeviceFilter {
    device_type_mask: i32,
}

#[repr(C)]
#[derive(Copy, Clone)]
struct DeviceInfo {
    kind: i32,
    id: [u8; DEVICE_ID_LEN],
    serial: [u8; 128],
    model: [u8; 128],
    led_count: i32,
    channel_count: i32,
}

#[repr(C)]
#[derive(Copy, Clone, Default)]
struct LedPosition {
    id: i32,
    cx: f64,
    cy: f64,
}

type StateCallback = unsafe extern "system" fn(ctx: *mut c_void, event: *const i32);
type ConnectFn = unsafe extern "system" fn(StateCallback, *mut c_void) -> i32;
type DisconnectFn = unsafe extern "system" fn() -> i32;
type GetDevicesFn =
    unsafe extern "system" fn(*const DeviceFilter, i32, *mut DeviceInfo, *mut i32) -> i32;
type GetLedPositionsFn =
    unsafe extern "system" fn(*const c_char, i32, *mut LedPosition, *mut i32) -> i32;
type SetLedColorsFn = unsafe extern "system" fn(*const c_char, i32, *const LedColor) -> i32;

/// What the session-state callback reports.
#[derive(Default)]
struct Latch {
    state: Mutex<Option<i32>>,
    cv: Condvar,
}

/// The process-wide session context the SDK calls back into. It is never freed (the SDK thread
/// may call it at any time until the process exits), which also covers reconnects.
fn latch() -> &'static Latch {
    static L: OnceLock<Latch> = OnceLock::new();
    L.get_or_init(Latch::default)
}

unsafe extern "system" fn on_state_changed(_ctx: *mut c_void, event: *const i32) {
    if event.is_null() {
        return;
    }
    // SAFETY: the event struct starts with the i32 session state.
    let state = unsafe { *event };
    match state {
        CSS_CONNECTED
        | CSS_SUBSCRIBED
        | CSS_CONNECTION_REFUSED
        | CSS_CONNECTION_TIMEOUT
        | CSS_CONNECTION_LOST
        | CSS_CLOSED => {
            let l = latch();
            *l.state.lock() = Some(state);
            l.cv.notify_all();
        }
        _ => {}
    }
}

struct Symbols {
    connect: ConnectFn,
    disconnect: DisconnectFn,
    get_devices: GetDevicesFn,
    get_led_positions: GetLedPositionsFn,
    set_led_colors: SetLedColorsFn,
}

/// The loaded SDK.
pub struct DllCorsair {
    _lib: Library,
    sym: Symbols,
}

// Plain C entry points; the backend never calls them concurrently.
unsafe impl Send for DllCorsair {}
unsafe impl Sync for DllCorsair {}

impl DllCorsair {
    fn open() -> Option<DllCorsair> {
        for name in DLL_CANDIDATES {
            // SAFETY: runs the DLL's DllMain; only inside the isolated helper process.
            let Ok(lib) = (unsafe { Library::new(name) }) else {
                continue;
            };
            // SAFETY: signatures follow the iCUE SDK v4 header.
            let sym = unsafe {
                (|| {
                    Some(Symbols {
                        connect: *lib.get::<ConnectFn>(b"CorsairConnect\0").ok()?,
                        disconnect: *lib.get::<DisconnectFn>(b"CorsairDisconnect\0").ok()?,
                        get_devices: *lib.get::<GetDevicesFn>(b"CorsairGetDevices\0").ok()?,
                        get_led_positions: *lib
                            .get::<GetLedPositionsFn>(b"CorsairGetLedPositions\0")
                            .ok()?,
                        set_led_colors: *lib
                            .get::<SetLedColorsFn>(b"CorsairSetLedColors\0")
                            .ok()?,
                    })
                })()
            };
            if let Some(sym) = sym {
                return Some(DllCorsair { _lib: lib, sym });
            }
        }
        None
    }
}

/// The SDK, loaded once; `None` when no candidate DLL loads.
pub fn load() -> Option<Arc<dyn CorsairApi>> {
    static LIB: OnceLock<Option<Arc<DllCorsair>>> = OnceLock::new();
    LIB.get_or_init(|| DllCorsair::open().map(Arc::new))
        .clone()
        .map(|l| l as Arc<dyn CorsairApi>)
}

impl CorsairApi for DllCorsair {
    fn connect(&self, timeout: Duration) -> RgbResult<()> {
        let l = latch();
        *l.state.lock() = None;
        // SAFETY: the callback is a 'static function and the context is unused (null).
        let rc = unsafe { (self.sym.connect)(on_state_changed, std::ptr::null_mut()) };
        if rc != 0 {
            return Err(RgbError::backend(format!(
                "Corsair iCUE: CorsairConnect returned error {}",
                describe_error(rc)
            )));
        }
        let deadline = Instant::now() + timeout;
        let mut st = l.state.lock();
        while st.is_none() {
            if l.cv.wait_until(&mut st, deadline).timed_out() {
                break;
            }
        }
        let state = *st;
        drop(st);
        match state {
            Some(CSS_CONNECTED | CSS_SUBSCRIBED) => Ok(()),
            None => {
                self.disconnect();
                Err(RgbError::backend(format!(
                    "Corsair iCUE: session never reached Connected within {}ms (iCUE service might not be running)",
                    timeout.as_millis()
                )))
            }
            Some(s) => {
                self.disconnect();
                Err(RgbError::backend(format!(
                    "Corsair iCUE: session ended in {}",
                    describe_session_state(s)
                )))
            }
        }
    }

    fn disconnect(&self) {
        // SAFETY: no arguments.
        unsafe {
            (self.sym.disconnect)();
        }
    }

    fn devices(&self) -> Result<Vec<Device>, i32> {
        let filter = DeviceFilter {
            device_type_mask: CDT_ALL,
        };
        let empty = DeviceInfo {
            kind: 0,
            id: [0; DEVICE_ID_LEN],
            serial: [0; 128],
            model: [0; 128],
            led_count: 0,
            channel_count: 0,
        };
        let mut infos = vec![empty; MAX_DEVICES];
        let mut n = 0i32;
        // SAFETY: infos holds MAX_DEVICES entries, as sizeMax says.
        let rc = unsafe {
            (self.sym.get_devices)(&filter, MAX_DEVICES as i32, infos.as_mut_ptr(), &mut n)
        };
        if rc != 0 {
            return Err(rc);
        }
        let mut out = Vec::new();
        for info in infos.iter().take((n.max(0) as usize).min(MAX_DEVICES)) {
            let mut leds = vec![LedPosition::default(); MAX_LEDS_PER_DEVICE];
            let mut m = 0i32;
            // SAFETY: the id is a NUL-terminated char[128]; leds holds MAX_LEDS_PER_DEVICE.
            let rc = unsafe {
                (self.sym.get_led_positions)(
                    info.id.as_ptr().cast(),
                    MAX_LEDS_PER_DEVICE as i32,
                    leds.as_mut_ptr(),
                    &mut m,
                )
            };
            if rc != 0 {
                tracing::warn!(
                    "{}",
                    mmcl_i18n::tf!("log.rgb.corsair.getLedPositionsError", describe_error(rc))
                );
                continue;
            }
            let led_ids: Vec<u32> = leds
                .iter()
                .take((m.max(0) as usize).min(MAX_LEDS_PER_DEVICE))
                .map(|p| p.id as u32)
                .collect();
            if led_ids.is_empty() {
                continue;
            }
            out.push(Device {
                id: info.id,
                model: c_string(&info.model),
                led_ids,
            });
        }
        Ok(out)
    }

    fn set_led_colors(&self, device_id: &[u8; DEVICE_ID_LEN], colors: &[LedColor]) -> i32 {
        // SAFETY: colors.len() entries, a NUL-terminated device id.
        unsafe {
            (self.sym.set_led_colors)(
                device_id.as_ptr().cast(),
                colors.len() as i32,
                colors.as_ptr(),
            )
        }
    }
}
