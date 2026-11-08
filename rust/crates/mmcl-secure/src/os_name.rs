// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Java's `System.getProperty("os.name")`, reproduced exactly.
//!
//! The value is part of the cipher fingerprint, so the Rust port has to produce the very string a
//! JDK 26 on the same machine reports. On Windows the JDK derives it from the true version
//! (`major.minor.build`, not the manifest-shimmed one) and whether the product type is a
//! workstation (`java_props_md.c`); [`windows_os_name`] is that table. macOS is always
//! `"Mac OS X"`, Linux `"Linux"`, and other Unix systems report `uname -s`.
//!
//! An OS upgrade that changes the name (Windows 10 to 11) changes the key, so encrypted files
//! need a fresh sign-in afterwards. The Java launcher behaves the same.

use std::sync::OnceLock;

/// The `os.name` a JDK 26 reports on this machine. Computed once per process.
pub fn java_os_name() -> String {
    static NAME: OnceLock<String> = OnceLock::new();
    NAME.get_or_init(imp::detect).clone()
}

/// `os.name` for a Windows NT version, exactly as the JDK's `java_props_md.c` maps it.
///
/// `is_workstation` is `wProductType == VER_NT_WORKSTATION`; domain controllers and servers are
/// not workstations. `is_64bit` only matters for 5.2 (XP x64 versus Server 2003).
pub fn windows_os_name(
    major: u32,
    minor: u32,
    build: u32,
    is_workstation: bool,
    is_64bit: bool,
) -> &'static str {
    const UNKNOWN: &str = "Windows NT (unknown)";
    match major {
        0..=4 => "Windows NT",
        5 => match minor {
            0 => "Windows 2000",
            1 => "Windows XP",
            2 if is_workstation && is_64bit => "Windows XP",
            2 => "Windows 2003",
            _ => UNKNOWN,
        },
        6 if is_workstation => match minor {
            0 => "Windows Vista",
            1 => "Windows 7",
            2 => "Windows 8",
            3 => "Windows 8.1",
            _ => UNKNOWN,
        },
        6 => match minor {
            0 => "Windows Server 2008",
            1 => "Windows Server 2008 R2",
            2 => "Windows Server 2012",
            3 => "Windows Server 2012 R2",
            _ => UNKNOWN,
        },
        10 if minor != 0 => UNKNOWN,
        10 if is_workstation => {
            if build >= 22000 {
                "Windows 11"
            } else {
                "Windows 10"
            }
        }
        // The JDK compares against the previous server release's build number.
        10 => {
            if build > 20348 {
                "Windows Server 2025"
            } else if build > 17763 {
                "Windows Server 2022"
            } else if build > 14393 {
                "Windows Server 2019"
            } else {
                "Windows Server 2016"
            }
        }
        _ => UNKNOWN,
    }
}

/// `os.name` for a Unix `uname -s` value: Java reports `sysname` as is, except for Darwin.
pub fn unix_os_name(sysname: &str) -> String {
    if sysname == "Darwin" {
        "Mac OS X".to_string()
    } else {
        sysname.to_string()
    }
}

#[cfg(windows)]
mod imp {
    use windows::Wdk::System::SystemServices::RtlGetVersion;
    use windows::Win32::System::SystemInformation::OSVERSIONINFOEXW;
    use windows::Win32::System::SystemServices::VER_NT_WORKSTATION;

    pub fn detect() -> String {
        let mut info = OSVERSIONINFOEXW {
            dwOSVersionInfoSize: std::mem::size_of::<OSVERSIONINFOEXW>() as u32,
            ..Default::default()
        };
        // SAFETY: `info` is a properly sized OSVERSIONINFOEXW whose size field is set, which
        // RtlGetVersion accepts in place of the smaller OSVERSIONINFOW.
        let status = unsafe { RtlGetVersion(&mut info as *mut OSVERSIONINFOEXW as *mut _) };
        if status.is_err() {
            return "Windows NT (unknown)".to_string();
        }
        super::windows_os_name(
            info.dwMajorVersion,
            info.dwMinorVersion,
            info.dwBuildNumber,
            u32::from(info.wProductType) == VER_NT_WORKSTATION,
            cfg!(target_pointer_width = "64"),
        )
        .to_string()
    }
}

#[cfg(target_os = "macos")]
mod imp {
    pub fn detect() -> String {
        "Mac OS X".to_string()
    }
}

#[cfg(target_os = "linux")]
mod imp {
    pub fn detect() -> String {
        "Linux".to_string()
    }
}

#[cfg(all(unix, not(any(target_os = "macos", target_os = "linux"))))]
mod imp {
    use std::ffi::CStr;

    pub fn detect() -> String {
        // SAFETY: `uts` is a zeroed utsname that uname fills; sysname is NUL-terminated.
        unsafe {
            let mut uts: libc::utsname = std::mem::zeroed();
            if libc::uname(&mut uts) == 0 {
                let name = CStr::from_ptr(uts.sysname.as_ptr()).to_string_lossy();
                return super::unix_os_name(&name);
            }
        }
        String::new()
    }
}

#[cfg(not(any(windows, unix)))]
mod imp {
    pub fn detect() -> String {
        String::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn windows_workstations() {
        assert_eq!(windows_os_name(10, 0, 19045, true, true), "Windows 10");
        assert_eq!(windows_os_name(10, 0, 21999, true, true), "Windows 10");
        assert_eq!(windows_os_name(10, 0, 22000, true, true), "Windows 11");
        assert_eq!(windows_os_name(10, 0, 26100, true, true), "Windows 11");
        assert_eq!(windows_os_name(6, 1, 7601, true, true), "Windows 7");
        assert_eq!(windows_os_name(6, 3, 9600, true, true), "Windows 8.1");
        assert_eq!(windows_os_name(5, 2, 3790, true, true), "Windows XP");
    }

    #[test]
    fn windows_servers() {
        assert_eq!(
            windows_os_name(10, 0, 14393, false, true),
            "Windows Server 2016"
        );
        assert_eq!(
            windows_os_name(10, 0, 17763, false, true),
            "Windows Server 2019"
        );
        assert_eq!(
            windows_os_name(10, 0, 20348, false, true),
            "Windows Server 2022"
        );
        assert_eq!(
            windows_os_name(10, 0, 26100, false, true),
            "Windows Server 2025"
        );
        assert_eq!(
            windows_os_name(6, 3, 9600, false, true),
            "Windows Server 2012 R2"
        );
        assert_eq!(windows_os_name(5, 2, 3790, false, true), "Windows 2003");
    }

    #[test]
    fn windows_unknowns() {
        assert_eq!(
            windows_os_name(10, 1, 0, true, true),
            "Windows NT (unknown)"
        );
        assert_eq!(
            windows_os_name(11, 0, 0, true, true),
            "Windows NT (unknown)"
        );
        assert_eq!(windows_os_name(4, 0, 1381, true, false), "Windows NT");
    }

    #[test]
    fn unix_names() {
        assert_eq!(unix_os_name("Darwin"), "Mac OS X");
        assert_eq!(unix_os_name("FreeBSD"), "FreeBSD");
        assert_eq!(unix_os_name("Linux"), "Linux");
    }

    #[test]
    fn host_name_is_a_known_java_value() {
        let name = java_os_name();
        if cfg!(windows) {
            assert!(name.starts_with("Windows"), "{name}");
        } else if cfg!(target_os = "macos") {
            assert_eq!(name, "Mac OS X");
        } else if cfg!(target_os = "linux") {
            assert_eq!(name, "Linux");
        }
    }

    /// Cross-checks against a real JDK: set `MMCL_PARITY_OS_NAME` to the
    /// `System.getProperty("os.name")` a JDK prints on this machine.
    #[test]
    fn matches_the_jdk_when_told_its_value() {
        if let Ok(expected) = std::env::var("MMCL_PARITY_OS_NAME") {
            assert_eq!(java_os_name(), expected);
        }
    }
}
