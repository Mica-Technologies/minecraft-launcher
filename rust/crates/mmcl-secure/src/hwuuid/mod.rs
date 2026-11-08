// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The hardware UUID exactly as oshi 6.6.5 (`ComputerSystem.getHardwareUUID()`) reports it.
//!
//! The value is part of the cipher fingerprint, so it has to be the same string oshi returns:
//!
//! - **Windows**: WMI `SELECT IdentifyingNumber,UUID FROM Win32_ComputerSystemProduct` in
//!   `ROOT\CIMV2`, the first row's `UUID` as WMI returns it (normally upper-case and dashed). An
//!   empty value, no row, or a failed WMI query gives `"unknown"`.
//! - **macOS**: the `IOPlatformUUID` string property of the `IOPlatformExpertDevice` service.
//!   Missing or empty gives `"unknown"`.
//! - **Linux**: the first of `/sys/devices/virtual/dmi/id/product_uuid` (first line, accepted
//!   when non-empty and not `"None"`; root-only on most systems), `dmidecode -t system` (only when
//!   running as root), `lshal`, `lshw -C system` (only when running as root), else `"unknown"`.
//!   See [`linux`].
//!
//! oshi's `"unknown"` sentinel is not blank, so it goes into the fingerprint as is.
//! [`hardware_uuid`] then applies `MachineSecretCipher`'s own rule on top: a blank value, or an
//! exception out of oshi, becomes `""`.

use std::sync::OnceLock;

pub mod linux;

#[cfg(target_os = "macos")]
mod macos;
#[cfg(windows)]
mod windows;

/// oshi's `Constants.UNKNOWN`.
pub const UNKNOWN: &str = "unknown";

/// oshi threw instead of returning a value (only reachable through the Linux command parsers).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct OshiFailure;

/// What oshi's `getHardwareUUID()` returns on this machine (`Err` where it would throw).
/// Computed once per process, like oshi's memoized supplier.
pub fn oshi_hardware_uuid() -> Result<String, OshiFailure> {
    static VALUE: OnceLock<Result<String, OshiFailure>> = OnceLock::new();
    VALUE.get_or_init(query).clone()
}

/// The hardware UUID as `MachineSecretCipher.resolveHardwareUuid` puts it into the fingerprint:
/// oshi's value, or `""` when oshi throws or the value is blank.
pub fn hardware_uuid() -> String {
    for_fingerprint(oshi_hardware_uuid())
}

/// `MachineSecretCipher.resolveHardwareUuid` applied to an oshi result.
pub fn for_fingerprint(oshi: Result<String, OshiFailure>) -> String {
    match oshi {
        Ok(value) if !crate::javastr::is_blank(&value) => value,
        _ => String::new(),
    }
}

/// oshi's `Util.isBlank(x) ? UNKNOWN : x` (null or empty only, not whitespace).
#[cfg_attr(not(any(windows, target_os = "macos")), allow(dead_code))]
pub(crate) fn or_unknown(value: Option<String>) -> String {
    match value {
        Some(v) if !v.is_empty() => v,
        _ => UNKNOWN.to_string(),
    }
}

#[cfg(windows)]
fn query() -> Result<String, OshiFailure> {
    Ok(or_unknown(windows::query_uuid()))
}

#[cfg(target_os = "macos")]
fn query() -> Result<String, OshiFailure> {
    Ok(or_unknown(macos::query_uuid()))
}

#[cfg(target_os = "linux")]
fn query() -> Result<String, OshiFailure> {
    linux::query_uuid(&linux::HostProbe)
}

#[cfg(not(any(windows, target_os = "macos", target_os = "linux")))]
fn query() -> Result<String, OshiFailure> {
    // oshi has no implementation here; SystemInfo throws UnsupportedOperationException, which
    // MachineSecretCipher turns into "".
    Err(OshiFailure)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn blank_or_failed_values_become_empty() {
        assert_eq!(for_fingerprint(Ok("ABC-1".into())), "ABC-1");
        assert_eq!(for_fingerprint(Ok(UNKNOWN.into())), "unknown");
        assert_eq!(for_fingerprint(Ok("   ".into())), "");
        assert_eq!(for_fingerprint(Ok(String::new())), "");
        assert_eq!(for_fingerprint(Err(OshiFailure)), "");
    }

    #[test]
    fn oshi_maps_only_null_or_empty_to_unknown() {
        assert_eq!(or_unknown(None), "unknown");
        assert_eq!(or_unknown(Some(String::new())), "unknown");
        assert_eq!(or_unknown(Some(" ".into())), " ");
        assert_eq!(or_unknown(Some("X".into())), "X");
    }

    #[test]
    fn host_query_does_not_panic_and_is_stable() {
        let first = hardware_uuid();
        assert_eq!(first, hardware_uuid());
    }

    /// Cross-checks against oshi itself: set `MMCL_PARITY_HWUUID` to what oshi 6.6.5's
    /// `getHardwareUUID()` prints on this machine.
    #[test]
    fn matches_oshi_when_told_its_value() {
        if let Ok(expected) = std::env::var("MMCL_PARITY_HWUUID") {
            assert_eq!(oshi_hardware_uuid(), Ok(expected));
        }
    }
}
