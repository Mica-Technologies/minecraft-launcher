// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The host description that Mojang rules are evaluated against.
//!
//! Mojang's rule patterns were written against the values a JVM reports for
//! `os.arch` and `os.version`, not against Rust's `std::env::consts::ARCH`. So
//! [`HostInfo`] carries emulated Java values (spec section 4.3):
//!
//! | Host | `os.arch` | `os.version` |
//! |---|---|---|
//! | Windows x64 / ARM64 / x86 | `amd64` / `aarch64` / `x86` | `10.0` (Windows 10 and 11) |
//! | macOS Intel / Apple Silicon | `x86_64` / `aarch64` | product version, e.g. `14.5` |
//! | Linux x64 / ARM64 / x86 | `amd64` / `aarch64` / `i386` | kernel release |

use std::path::Path;
use std::sync::OnceLock;

/// `ModPackConstants.PLATFORM_WINDOWS`.
pub const PLATFORM_WINDOWS: &str = "windows";
/// `ModPackConstants.PLATFORM_MACOS`.
pub const PLATFORM_MACOS: &str = "osx";
/// `ModPackConstants.PLATFORM_UNIX`.
pub const PLATFORM_UNIX: &str = "linux";

/// The `os.version` a JDK reports on Windows 10 and 11 (major.minor only).
///
/// Kept for parity with the Java launcher (open question Q-1, bug B-18): a
/// `versionRange` rule with a build-number minimum never matches on Windows.
pub const WINDOWS_JAVA_OS_VERSION: &str = "10.0";

/// Host operating system family.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Hash)]
pub enum Os {
    Windows,
    MacOs,
    Linux,
    /// BSDs and anything else. Matches no Mojang `os.name` (the Java asymmetry).
    Other,
}

/// Host CPU architecture.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Hash)]
pub enum Arch {
    X86_64,
    X86,
    Aarch64,
    Other,
}

/// What Mojang rules see of the machine. Build one with [`HostInfo::detect`]
/// for the real host, or [`HostInfo::emulated`] / [`HostInfo::new`] in tests.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HostInfo {
    pub os: Os,
    pub arch: Arch,
    /// The value a JVM would report as `os.arch`.
    pub java_os_arch: String,
    /// The value a JVM would report as `os.version`.
    pub java_os_version: String,
}

impl HostInfo {
    /// A fully specified host.
    pub fn new(
        os: Os,
        arch: Arch,
        java_os_arch: impl Into<String>,
        java_os_version: impl Into<String>,
    ) -> Self {
        HostInfo {
            os,
            arch,
            java_os_arch: java_os_arch.into(),
            java_os_version: java_os_version.into(),
        }
    }

    /// A host with the Java `os.arch` a JDK would report for `(os, arch)` and
    /// the given `os.version`.
    pub fn emulated(os: Os, arch: Arch, java_os_version: impl Into<String>) -> Self {
        HostInfo::new(os, arch, java_os_arch_for(os, arch), java_os_version)
    }

    /// The real host, computed once per process.
    pub fn detect() -> &'static HostInfo {
        static HOST: OnceLock<HostInfo> = OnceLock::new();
        HOST.get_or_init(|| {
            let os = current_os();
            let arch = current_arch();
            HostInfo::emulated(os, arch, detect_java_os_version(os))
        })
    }

    /// `"windows"`, `"osx"` or `"linux"` (the last for every other host too).
    pub fn platform_name(&self) -> &'static str {
        match self.os {
            Os::Windows => PLATFORM_WINDOWS,
            Os::MacOs => PLATFORM_MACOS,
            Os::Linux | Os::Other => PLATFORM_UNIX,
        }
    }

    /// `true` when Java `os.arch` names a 64-bit architecture (contains `64`;
    /// `aarch64` counts). Used for the `${arch}` native classifier token.
    pub fn is_64_bit_java_arch(&self) -> bool {
        self.java_os_arch.contains("64")
    }

    /// `true` on ARM64 hosts (Java `os.arch` contains `aarch64` or `arm64`).
    pub fn is_arm64(&self) -> bool {
        self.java_os_arch.contains("aarch64") || self.java_os_arch.contains("arm64")
    }

    /// The platform key in Mojang's Java runtime index (`all.json`).
    ///
    /// Re-specified from Java's substring logic (spec 9.1). Windows x86 gets
    /// `windows-x86` (Java sent it to `windows-x64`, bug B-9). Linux ARM64 gets
    /// `None`: Mojang publishes no `linux-arm64` runtime, and the x64 one Java
    /// fell back to cannot run there, so the runtime manager must use another
    /// source (open question Q-2).
    pub fn mojang_runtime_key(&self) -> Option<&'static str> {
        match (self.os, self.arch) {
            (Os::Windows, Arch::X86_64) => Some("windows-x64"),
            (Os::Windows, Arch::Aarch64) => Some("windows-arm64"),
            (Os::Windows, Arch::X86) => Some("windows-x86"),
            (Os::MacOs, Arch::X86_64) => Some("mac-os"),
            (Os::MacOs, Arch::Aarch64) => Some("mac-os-arm64"),
            (Os::Linux, Arch::X86_64) => Some("linux"),
            (Os::Linux, Arch::X86) => Some("linux-i386"),
            _ => None,
        }
    }

    /// The Java executable path inside a Mojang runtime folder, as a string with
    /// the host's separators: `bin\java.exe`, `jre.bundle/Contents/Home/bin/java`
    /// or `bin/java`.
    pub fn java_exec_rel_path_str(&self) -> &'static str {
        match self.os {
            Os::Windows => "bin\\java.exe",
            Os::MacOs => "jre.bundle/Contents/Home/bin/java",
            Os::Linux | Os::Other => "bin/java",
        }
    }

    /// [`Self::java_exec_rel_path_str`] as a [`Path`].
    pub fn java_exec_rel_path(&self) -> &'static Path {
        Path::new(self.java_exec_rel_path_str())
    }

    /// The Java executable's file name: `java.exe` on Windows, `java` elsewhere.
    pub fn java_exec_name(&self) -> &'static str {
        if self.os == Os::Windows {
            "java.exe"
        } else {
            "java"
        }
    }

    /// The classpath separator for this host: `;` on Windows, `:` elsewhere.
    pub fn classpath_separator(&self) -> char {
        if self.os == Os::Windows { ';' } else { ':' }
    }
}

/// The `os.arch` a JDK reports for this OS/architecture pair.
pub fn java_os_arch_for(os: Os, arch: Arch) -> String {
    match (os, arch) {
        (Os::MacOs, Arch::X86_64) => "x86_64".to_string(),
        (_, Arch::X86_64) => "amd64".to_string(),
        (_, Arch::Aarch64) => "aarch64".to_string(),
        (Os::Windows, Arch::X86) => "x86".to_string(),
        (_, Arch::X86) => "i386".to_string(),
        (_, Arch::Other) => std::env::consts::ARCH.to_string(),
    }
}

fn current_os() -> Os {
    if cfg!(target_os = "windows") {
        Os::Windows
    } else if cfg!(target_os = "macos") {
        Os::MacOs
    } else if cfg!(target_os = "linux") {
        Os::Linux
    } else {
        Os::Other
    }
}

fn current_arch() -> Arch {
    match std::env::consts::ARCH {
        "x86_64" => Arch::X86_64,
        "x86" => Arch::X86,
        "aarch64" => Arch::Aarch64,
        _ => Arch::Other,
    }
}

/// Emulate Java's `os.version` for the running host.
fn detect_java_os_version(os: Os) -> String {
    match os {
        Os::Windows => WINDOWS_JAVA_OS_VERSION.to_string(),
        Os::MacOs => std::fs::read_to_string("/System/Library/CoreServices/SystemVersion.plist")
            .ok()
            .and_then(|plist| plist_product_version(&plist))
            .unwrap_or_default(),
        Os::Linux | Os::Other => std::fs::read_to_string("/proc/sys/kernel/osrelease")
            .map(|s| s.trim().to_string())
            .unwrap_or_default(),
    }
}

/// Pull `ProductVersion` out of macOS's `SystemVersion.plist` (XML form).
pub(crate) fn plist_product_version(plist: &str) -> Option<String> {
    let key_at = plist.find("<key>ProductVersion</key>")?;
    let rest = &plist[key_at..];
    let open = rest.find("<string>")? + "<string>".len();
    let close = rest[open..].find("</string>")? + open;
    let v = rest[open..close].trim();
    (!v.is_empty()).then(|| v.to_string())
}

/// Hosts for tests, one per supported platform.
pub mod presets {
    use super::{Arch, HostInfo, Os};

    pub fn windows_x64() -> HostInfo {
        HostInfo::emulated(Os::Windows, Arch::X86_64, "10.0")
    }
    pub fn windows_arm64() -> HostInfo {
        HostInfo::emulated(Os::Windows, Arch::Aarch64, "10.0")
    }
    pub fn windows_x86() -> HostInfo {
        HostInfo::emulated(Os::Windows, Arch::X86, "10.0")
    }
    pub fn mac_intel() -> HostInfo {
        HostInfo::emulated(Os::MacOs, Arch::X86_64, "14.5")
    }
    pub fn mac_arm() -> HostInfo {
        HostInfo::emulated(Os::MacOs, Arch::Aarch64, "14.5")
    }
    pub fn linux_x64() -> HostInfo {
        HostInfo::emulated(Os::Linux, Arch::X86_64, "6.5.0-35-generic")
    }
    pub fn linux_arm64() -> HostInfo {
        HostInfo::emulated(Os::Linux, Arch::Aarch64, "6.5.0-1-raspi")
    }
    pub fn linux_x86() -> HostInfo {
        HostInfo::emulated(Os::Linux, Arch::X86, "5.10.0")
    }
    pub fn freebsd_x64() -> HostInfo {
        HostInfo::emulated(Os::Other, Arch::X86_64, "14.0-RELEASE")
    }

    /// Every preset, for table tests.
    pub fn all() -> Vec<HostInfo> {
        vec![
            windows_x64(),
            windows_arm64(),
            windows_x86(),
            mac_intel(),
            mac_arm(),
            linux_x64(),
            linux_arm64(),
            linux_x86(),
            freebsd_x64(),
        ]
    }
}

#[cfg(test)]
mod tests {
    use super::presets::*;
    use super::*;

    #[test]
    fn java_arch_table_matches_the_jdk() {
        assert_eq!(windows_x64().java_os_arch, "amd64");
        assert_eq!(windows_arm64().java_os_arch, "aarch64");
        assert_eq!(windows_x86().java_os_arch, "x86");
        assert_eq!(mac_intel().java_os_arch, "x86_64");
        assert_eq!(mac_arm().java_os_arch, "aarch64");
        assert_eq!(linux_x64().java_os_arch, "amd64");
        assert_eq!(linux_arm64().java_os_arch, "aarch64");
        assert_eq!(linux_x86().java_os_arch, "i386");
    }

    #[test]
    fn platform_names() {
        assert_eq!(windows_x64().platform_name(), "windows");
        assert_eq!(mac_arm().platform_name(), "osx");
        assert_eq!(linux_x64().platform_name(), "linux");
        assert_eq!(
            freebsd_x64().platform_name(),
            "linux",
            "non-Windows/non-mac falls back to linux"
        );
    }

    #[test]
    fn mojang_runtime_keys() {
        assert_eq!(windows_x64().mojang_runtime_key(), Some("windows-x64"));
        assert_eq!(windows_arm64().mojang_runtime_key(), Some("windows-arm64"));
        assert_eq!(
            windows_x86().mojang_runtime_key(),
            Some("windows-x86"),
            "B-9 fixed"
        );
        assert_eq!(mac_intel().mojang_runtime_key(), Some("mac-os"));
        assert_eq!(mac_arm().mojang_runtime_key(), Some("mac-os-arm64"));
        assert_eq!(
            linux_x64().mojang_runtime_key(),
            Some("linux"),
            "never linux-i386 for x64"
        );
        assert_eq!(linux_x86().mojang_runtime_key(), Some("linux-i386"));
        assert_eq!(linux_arm64().mojang_runtime_key(), None);
        assert_eq!(freebsd_x64().mojang_runtime_key(), None);
    }

    #[test]
    fn java_exec_paths() {
        assert_eq!(windows_x64().java_exec_rel_path_str(), "bin\\java.exe");
        assert_eq!(
            mac_arm().java_exec_rel_path_str(),
            "jre.bundle/Contents/Home/bin/java"
        );
        assert_eq!(linux_x64().java_exec_rel_path_str(), "bin/java");
        assert_eq!(windows_x64().java_exec_name(), "java.exe");
        assert_eq!(linux_x64().java_exec_name(), "java");
    }

    #[test]
    fn arch_bitness_and_arm() {
        assert!(windows_x64().is_64_bit_java_arch());
        assert!(mac_arm().is_64_bit_java_arch(), "aarch64 counts as 64");
        assert!(!windows_x86().is_64_bit_java_arch());
        assert!(!linux_x86().is_64_bit_java_arch());
        assert!(mac_arm().is_arm64());
        assert!(!mac_intel().is_arm64());
    }

    #[test]
    fn classpath_separators() {
        assert_eq!(windows_x64().classpath_separator(), ';');
        assert_eq!(mac_arm().classpath_separator(), ':');
    }

    #[test]
    fn detect_is_consistent_with_the_build_target() {
        let h = HostInfo::detect();
        assert!(std::ptr::eq(h, HostInfo::detect()), "computed once");
        if cfg!(windows) {
            assert_eq!(h.os, Os::Windows);
            assert_eq!(h.java_os_version, "10.0");
        }
        if cfg!(all(windows, target_arch = "x86_64")) {
            assert_eq!(h.java_os_arch, "amd64");
        }
    }

    #[test]
    fn parses_mac_system_version_plist() {
        let plist = "<plist><dict><key>ProductBuildVersion</key><string>23F79</string>\n\
                     <key>ProductName</key><string>macOS</string>\n\
                     <key>ProductVersion</key>\n\t<string>14.5</string></dict></plist>";
        assert_eq!(plist_product_version(plist).as_deref(), Some("14.5"));
        assert_eq!(plist_product_version("<plist/>"), None);
    }
}
