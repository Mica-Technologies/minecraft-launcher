// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! COM apartment guard.

use windows::Win32::Foundation::RPC_E_CHANGED_MODE;
use windows::Win32::System::Com::{COINIT_APARTMENTTHREADED, CoInitializeEx, CoUninitialize};

/// A single-threaded apartment on the current thread for the guard's lifetime.
///
/// `S_OK` means we initialized and uninitialize on drop; `S_FALSE` (already initialized) and
/// `RPC_E_CHANGED_MODE` (an MTA is already there) are usable as they are.
pub struct Apartment {
    owned: bool,
    /// The failing HRESULT, when COM is not usable on this thread.
    pub failed: Option<i32>,
}

impl Apartment {
    /// Enters an STA.
    pub fn sta() -> Apartment {
        let hr = unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED) };
        if hr.is_ok() {
            // S_OK and S_FALSE both need a matching CoUninitialize.
            Apartment {
                owned: true,
                failed: None,
            }
        } else if hr == RPC_E_CHANGED_MODE {
            Apartment {
                owned: false,
                failed: None,
            }
        } else {
            Apartment {
                owned: false,
                failed: Some(hr.0),
            }
        }
    }

    /// Whether COM calls can be made on this thread.
    pub fn usable(&self) -> bool {
        self.failed.is_none()
    }
}

impl Drop for Apartment {
    fn drop(&mut self) {
        if self.owned {
            unsafe { CoUninitialize() };
        }
    }
}
