// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Windows: the `UUID` of the first `Win32_ComputerSystemProduct` row, queried through WMI in
//! `ROOT\CIMV2` the way oshi's `WmiQueryHandler` does.
//!
//! The query runs on a thread of its own in the multithreaded COM apartment, so it never
//! disturbs the caller's COM state (a GUI thread is usually single-threaded).

use std::sync::mpsc;
use std::time::Duration;

use windows::Win32::System::Com::{
    CLSCTX_INPROC_SERVER, COINIT_MULTITHREADED, CoCreateInstance, CoInitializeEx,
    CoInitializeSecurity, CoSetProxyBlanket, CoUninitialize, EOAC_NONE, RPC_C_AUTHN_LEVEL_CALL,
    RPC_C_AUTHN_LEVEL_DEFAULT, RPC_C_IMP_LEVEL_IMPERSONATE,
};
use windows::Win32::System::Rpc::{RPC_C_AUTHN_WINNT, RPC_C_AUTHZ_NONE};
use windows::Win32::System::Variant::{VARIANT, VT_BSTR, VariantClear};
use windows::Win32::System::Wmi::{
    IWbemClassObject, IWbemLocator, WBEM_FLAG_FORWARD_ONLY, WBEM_FLAG_RETURN_IMMEDIATELY,
    WBEM_INFINITE, WbemLocator,
};
use windows::core::{BSTR, PCWSTR, w};

/// After this long a warning is logged, and the wait goes on. oshi waits without a limit, and so
/// must this: giving up would put `"unknown"` into the fingerprint for the rest of the process
/// (the value is computed once), so every file written on this machine would fail to decrypt,
/// and what the process wrote would fail on the next launch, all because WMI was slow once.
const WMI_SLOW_WARNING: Duration = Duration::from_secs(30);

/// The raw `UUID` value, or `None` when WMI fails, returns no row, or the value is not a string.
pub fn query_uuid() -> Option<String> {
    let (tx, rx) = mpsc::channel();
    let spawned = std::thread::Builder::new()
        .name("mmcl-wmi-uuid".into())
        .spawn(move || {
            let _ = tx.send(query_on_this_thread());
        });
    if spawned.is_err() {
        return None;
    }
    match rx.recv_timeout(WMI_SLOW_WARNING) {
        Ok(value) => value,
        Err(mpsc::RecvTimeoutError::Timeout) => {
            tracing::warn!(
                "{}",
                mmcl_i18n::tf!("log.secure.hardwareUuid.slow", WMI_SLOW_WARNING.as_secs())
            );
            // A disconnect means the query thread panicked, which reads as oshi's no-rows path.
            rx.recv().ok().flatten()
        }
        Err(mpsc::RecvTimeoutError::Disconnected) => None,
    }
}

fn query_on_this_thread() -> Option<String> {
    // SAFETY: COM is initialised for this thread before any COM call and uninitialised after the
    // last interface pointer (all dropped inside `run_query`) is released.
    unsafe {
        let init = CoInitializeEx(None, COINIT_MULTITHREADED);
        let result = run_query();
        if init.is_ok() {
            CoUninitialize();
        }
        result
    }
}

/// # Safety
/// COM must be initialised on the calling thread.
unsafe fn run_query() -> Option<String> {
    // SAFETY: guaranteed by the caller; arguments follow the documented WMI client recipe.
    unsafe {
        // Fails with RPC_E_TOO_LATE when the process already set its security; that is fine.
        let _ = CoInitializeSecurity(
            None,
            -1,
            None,
            None,
            RPC_C_AUTHN_LEVEL_DEFAULT,
            RPC_C_IMP_LEVEL_IMPERSONATE,
            None,
            EOAC_NONE,
            None,
        );
        let locator: IWbemLocator =
            CoCreateInstance(&WbemLocator, None, CLSCTX_INPROC_SERVER).ok()?;
        let services = locator
            .ConnectServer(
                &BSTR::from("ROOT\\CIMV2"),
                &BSTR::new(),
                &BSTR::new(),
                &BSTR::new(),
                0,
                &BSTR::new(),
                None,
            )
            .ok()?;
        CoSetProxyBlanket(
            &services,
            RPC_C_AUTHN_WINNT,
            RPC_C_AUTHZ_NONE,
            PCWSTR::null(),
            RPC_C_AUTHN_LEVEL_CALL,
            RPC_C_IMP_LEVEL_IMPERSONATE,
            None,
            EOAC_NONE,
        )
        .ok()?;
        let rows = services
            .ExecQuery(
                &BSTR::from("WQL"),
                &BSTR::from("SELECT IDENTIFYINGNUMBER,UUID FROM Win32_ComputerSystemProduct"),
                WBEM_FLAG_FORWARD_ONLY | WBEM_FLAG_RETURN_IMMEDIATELY,
                None,
            )
            .ok()?;
        let mut row: [Option<IWbemClassObject>; 1] = [None];
        let mut returned = 0u32;
        rows.Next(WBEM_INFINITE, &mut row, &mut returned)
            .ok()
            .ok()?;
        if returned == 0 {
            return None;
        }
        let object = row[0].take()?;
        let mut value = VARIANT::default();
        object.Get(w!("UUID"), 0, &mut value, None, None).ok()?;
        let text = variant_string(&value);
        let _ = VariantClear(&mut value);
        text
    }
}

/// oshi `WmiUtil.getStr`: the BSTR of a string variant, `None` for anything else (`VT_NULL`).
fn variant_string(value: &VARIANT) -> Option<String> {
    // SAFETY: the union member read matches the `vt` tag checked first.
    unsafe {
        let inner = &value.Anonymous.Anonymous;
        if inner.vt != VT_BSTR {
            return None;
        }
        Some(inner.Anonymous.bstrVal.to_string())
    }
}
