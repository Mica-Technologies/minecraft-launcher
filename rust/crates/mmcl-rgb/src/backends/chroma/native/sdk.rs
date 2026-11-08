// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The real `RzChromaSDK64.dll` binding (Java `RzChromaSdkLibrary`), via `libloading`. The
//! library is loaded once and kept for the life of the process.

use std::ffi::c_void;
use std::sync::{Arc, OnceLock};

use libloading::Library;

use super::{ChromaApi, Family, Guid};

#[cfg(target_pointer_width = "64")]
pub const DLL_NAME: &str = "RzChromaSDK64.dll";
#[cfg(not(target_pointer_width = "64"))]
pub const DLL_NAME: &str = "RzChromaSDK.dll";

type InitSdkFn = unsafe extern "system" fn(*const c_void) -> i32;
type UnInitFn = unsafe extern "system" fn() -> i32;
type CreateFn = unsafe extern "system" fn(i32, *const c_void, *mut Guid) -> i32;
/// `RZEFFECTID` is a GUID passed by value (on x64 the ABI passes it by hidden reference, which
/// is what Java's pointer declaration relied on; by-value is correct on every ABI).
type EffectIdFn = unsafe extern "system" fn(Guid) -> i32;

struct Symbols {
    init_sdk: InitSdkFn,
    uninit: UnInitFn,
    create: [CreateFn; 6],
    set_effect: EffectIdFn,
    delete_effect: EffectIdFn,
}

/// The loaded SDK.
pub struct DllChroma {
    _lib: Library,
    sym: Symbols,
}

// The function pointers are plain C entry points; the SDK serialises internally and the
// backend never calls it from two threads at once.
unsafe impl Send for DllChroma {}
unsafe impl Sync for DllChroma {}

fn create_symbol(f: Family) -> &'static [u8] {
    match f {
        Family::Keyboard => b"CreateKeyboardEffect\0",
        Family::Mouse => b"CreateMouseEffect\0",
        Family::Mousepad => b"CreateMousepadEffect\0",
        Family::Headset => b"CreateHeadsetEffect\0",
        Family::Keypad => b"CreateKeypadEffect\0",
        Family::ChromaLink => b"CreateChromaLinkEffect\0",
    }
}

impl DllChroma {
    fn open() -> Option<DllChroma> {
        // SAFETY: loading a vendor DLL runs its DllMain; this happens only inside the isolated
        // helper process (or with the debug in-process override).
        let lib = unsafe { Library::new(DLL_NAME) }.ok()?;
        // SAFETY: the signatures match the Razer Chroma SDK header (RzChromaSDKTypes.h).
        let sym = unsafe {
            let get_create = |f: Family| -> Option<CreateFn> {
                lib.get::<CreateFn>(create_symbol(f)).ok().map(|s| *s)
            };
            Symbols {
                init_sdk: *lib.get::<InitSdkFn>(b"InitSDK\0").ok()?,
                uninit: *lib.get::<UnInitFn>(b"UnInit\0").ok()?,
                create: [
                    get_create(Family::Keyboard)?,
                    get_create(Family::Mouse)?,
                    get_create(Family::Mousepad)?,
                    get_create(Family::Headset)?,
                    get_create(Family::Keypad)?,
                    get_create(Family::ChromaLink)?,
                ],
                set_effect: *lib.get::<EffectIdFn>(b"SetEffect\0").ok()?,
                delete_effect: *lib.get::<EffectIdFn>(b"DeleteEffect\0").ok()?,
            }
        };
        Some(DllChroma { _lib: lib, sym })
    }
}

/// The SDK, loaded on first use; `None` when the DLL or a symbol is missing.
pub fn load() -> Option<Arc<dyn ChromaApi>> {
    static LIB: OnceLock<Option<Arc<DllChroma>>> = OnceLock::new();
    LIB.get_or_init(|| DllChroma::open().map(Arc::new))
        .clone()
        .map(|l| l as Arc<dyn ChromaApi>)
}

impl ChromaApi for DllChroma {
    fn init_sdk(&self, app_info: &[u8]) -> i32 {
        // SAFETY: app_info is a 3592-byte APPINFOTYPE kept alive by the backend for the session.
        unsafe { (self.sym.init_sdk)(app_info.as_ptr().cast()) }
    }

    fn uninit(&self) -> i32 {
        // SAFETY: no arguments.
        unsafe { (self.sym.uninit)() }
    }

    fn create_effect(&self, family: Family, effect: i32, param: &[u8], out_id: &mut Guid) -> i32 {
        if param.len() < family.param_len() {
            // Never hand the SDK a buffer smaller than the struct it reads (see params.rs).
            return 87;
        }
        let f = self.sym.create[Family::ALL.iter().position(|x| *x == family).unwrap_or(0)];
        // SAFETY: param is at least the size of the effect struct; out_id is a valid GUID.
        unsafe { f(effect, param.as_ptr().cast(), out_id) }
    }

    fn set_effect(&self, id: Guid) -> i32 {
        // SAFETY: id came from a Create*Effect call.
        unsafe { (self.sym.set_effect)(id) }
    }

    fn delete_effect(&self, id: Guid) -> i32 {
        // SAFETY: as above.
        unsafe { (self.sym.delete_effect)(id) }
    }
}
