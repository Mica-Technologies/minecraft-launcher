// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The real `AURA_SDK.dll` binding (Java `AsusAuraSdkLibrary`), resolving every symbol
//! individually so a missing one disables only its family.

use std::ffi::c_void;
use std::sync::{Arc, OnceLock};

use libloading::Library;

use super::{AuraApi, AuraFamily};

/// DLL names tried in order.
pub const DLL_CANDIDATES: [&str; 3] = ["AURA_SDK.dll", "AuraSDK.dll", "AURA_SDK.x64.dll"];

type CreateFn = unsafe extern "system" fn(*mut *mut c_void) -> i32;
type CountFn = unsafe extern "system" fn(*mut c_void) -> i32;
type ModeFn = unsafe extern "system" fn(*mut c_void, i32) -> i32;
type ColorFn = unsafe extern "system" fn(*mut c_void, *const u8, i32) -> i32;
type ReleaseFn = unsafe extern "system" fn(*mut *mut c_void) -> i32;

#[derive(Copy, Clone)]
struct FamilyFns {
    create: CreateFn,
    count: CountFn,
    mode: ModeFn,
    color: ColorFn,
    release: Option<ReleaseFn>,
}

fn names(f: AuraFamily) -> [&'static [u8]; 5] {
    match f {
        AuraFamily::Motherboard => [
            b"CreateMbController\0",
            b"GetMbLedCount\0",
            b"SetMbMode\0",
            b"SetMbColor\0",
            b"ReleaseMbController\0",
        ],
        AuraFamily::Gpu => [
            b"CreateGPUController\0",
            b"GetGPULedCount\0",
            b"SetGPUMode\0",
            b"SetGPUColor\0",
            b"ReleaseGPUController\0",
        ],
        AuraFamily::Ram => [
            b"CreateRAMController\0",
            b"GetRAMLedCount\0",
            b"SetRAMMode\0",
            b"SetRAMColor\0",
            b"ReleaseRAMController\0",
        ],
        AuraFamily::ClaymoreKeyboard => [
            b"CreateClaymoreKeyboard\0",
            b"GetClaymoreKeyboardLightCount\0",
            b"SetClaymoreKeyboardMode\0",
            b"SetClaymoreKeyboardColor\0",
            b"ReleaseClaymoreKeyboard\0",
        ],
    }
}

/// The loaded SDK.
pub struct DllAura {
    _lib: Library,
    fns: [Option<FamilyFns>; 4],
}

// Plain C entry points; the backend never calls them concurrently.
unsafe impl Send for DllAura {}
unsafe impl Sync for DllAura {}

impl DllAura {
    fn open() -> Option<DllAura> {
        for name in DLL_CANDIDATES {
            // SAFETY: runs the DLL's DllMain; only inside the isolated helper process.
            let Ok(lib) = (unsafe { Library::new(name) }) else {
                continue;
            };
            let fns = AuraFamily::ALL.map(|f| {
                let n = names(f);
                // SAFETY: the Java binding's signatures (see the module caveat).
                unsafe {
                    Some(FamilyFns {
                        create: *lib.get::<CreateFn>(n[0]).ok()?,
                        count: *lib.get::<CountFn>(n[1]).ok()?,
                        mode: *lib.get::<ModeFn>(n[2]).ok()?,
                        color: *lib.get::<ColorFn>(n[3]).ok()?,
                        release: lib.get::<ReleaseFn>(n[4]).ok().map(|s| *s),
                    })
                }
            });
            return Some(DllAura { _lib: lib, fns });
        }
        None
    }

    fn fns(&self, f: AuraFamily) -> Option<FamilyFns> {
        self.fns[AuraFamily::ALL.iter().position(|x| *x == f)?]
    }
}

/// The SDK, loaded once; `None` when no candidate DLL loads.
pub fn load() -> Option<Arc<dyn AuraApi>> {
    static LIB: OnceLock<Option<Arc<DllAura>>> = OnceLock::new();
    LIB.get_or_init(|| DllAura::open().map(Arc::new))
        .clone()
        .map(|l| l as Arc<dyn AuraApi>)
}

impl AuraApi for DllAura {
    fn has_family(&self, f: AuraFamily) -> bool {
        self.fns(f).is_some()
    }

    fn create(&self, f: AuraFamily) -> (i32, usize) {
        let Some(fns) = self.fns(f) else {
            return (-1, 0);
        };
        let mut handle: *mut c_void = std::ptr::null_mut();
        // SAFETY: an out pointer to a local.
        let rc = unsafe { (fns.create)(&mut handle) };
        (rc, handle as usize)
    }

    fn led_count(&self, f: AuraFamily, handle: usize) -> i32 {
        let Some(fns) = self.fns(f) else { return 0 };
        // SAFETY: handle came from create.
        unsafe { (fns.count)(handle as *mut c_void) }
    }

    fn set_mode(&self, f: AuraFamily, handle: usize, mode: i32) -> i32 {
        let Some(fns) = self.fns(f) else { return -1 };
        // SAFETY: handle came from create.
        unsafe { (fns.mode)(handle as *mut c_void, mode) }
    }

    fn set_color(&self, f: AuraFamily, handle: usize, buf: &[u8]) -> i32 {
        let Some(fns) = self.fns(f) else { return -1 };
        // SAFETY: buf.len() bytes, as the size argument says.
        unsafe { (fns.color)(handle as *mut c_void, buf.as_ptr(), buf.len() as i32) }
    }

    fn release(&self, f: AuraFamily, handle: usize) -> i32 {
        let Some(release) = self.fns(f).and_then(|x| x.release) else {
            return 0;
        };
        let mut h = handle as *mut c_void;
        // SAFETY: a pointer to the handle, as ReleaseXController(void**) expects.
        unsafe { release(&mut h) }
    }
}
