// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Compile-time build information: version, flavour, and the names derived from them.
//!
//! The version comes from `build.rs` (env `MMCL_VERSION`, else `git describe`, else the sentinel
//! `0.0.0-no-git-tag`). The flavour is `DEV` or `RELEASE` when `MMCL_ENVIRONMENT` says so at
//! build time; otherwise a debug build is dev (Java's "IDE run is dev" rule) and a release build
//! is release.

use crate::identity::AppIdentity;

include!("version_numeric.rs");

/// RELEASE or DEV.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub enum Flavor {
    Release,
    Dev,
}

impl Flavor {
    /// `"RELEASE"` or `"DEV"`.
    pub const fn as_str(self) -> &'static str {
        match self {
            Flavor::Release => "RELEASE",
            Flavor::Dev => "DEV",
        }
    }
}

/// The version string stamped at build time.
pub const VERSION: &str = env!("MMCL_VERSION");
/// [`VERSION`] cut at the first `-` then `+` (`0.0.0` when empty).
pub const VERSION_NUMERIC: &str = env!("MMCL_VERSION_NUMERIC");
/// The sentinel version of a build made without tags or `MMCL_VERSION`.
pub const SENTINEL_VERSION: &str = "0.0.0-no-git-tag";
const FLAVOR_STAMP: &str = env!("MMCL_FLAVOR");

/// The flavour of this build.
pub const fn flavor() -> Flavor {
    flavor_from_stamp(FLAVOR_STAMP, cfg!(debug_assertions))
}

/// Pure flavour rule: an explicit `DEV`/`RELEASE` stamp wins, otherwise debug builds are dev.
pub const fn flavor_from_stamp(stamp: &str, debug_assertions: bool) -> Flavor {
    if const_eq(stamp, "DEV") {
        Flavor::Dev
    } else if const_eq(stamp, "RELEASE") {
        Flavor::Release
    } else if debug_assertions {
        Flavor::Dev
    } else {
        Flavor::Release
    }
}

const fn const_eq(a: &str, b: &str) -> bool {
    let (a, b) = (a.as_bytes(), b.as_bytes());
    if a.len() != b.len() {
        return false;
    }
    let mut i = 0;
    while i < a.len() {
        if a[i] != b[i] {
            return false;
        }
        i += 1;
    }
    true
}

/// `true` for a dev build (`LAUNCHER_IS_DEV`).
pub const fn is_dev() -> bool {
    matches!(flavor(), Flavor::Dev)
}

/// Everything about this build in one value.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BuildInfo {
    pub is_dev: bool,
    pub flavor: Flavor,
    /// Java-compatible application name (`Mica Minecraft Launcher[ DEV]`).
    pub app_name: &'static str,
    /// [`BuildInfo::app_name`] without spaces.
    pub app_name_trimmed: &'static str,
    /// Product name shown to the user (the identity's display name).
    pub display_name: &'static str,
    pub version: &'static str,
    pub version_numeric: &'static str,
    /// The compiler that built this binary, for diagnostics (`rustc <ver>` when known).
    pub compiler: &'static str,
}

static INFO: std::sync::OnceLock<BuildInfo> = std::sync::OnceLock::new();

/// The running build's information.
pub fn info() -> &'static BuildInfo {
    INFO.get_or_init(|| {
        let id = AppIdentity::current();
        BuildInfo {
            is_dev: is_dev(),
            flavor: flavor(),
            app_name: id.app_name,
            app_name_trimmed: id.app_name_trimmed,
            display_name: id.display_name,
            version: VERSION,
            version_numeric: VERSION_NUMERIC,
            compiler: option_env!("MMCL_RUSTC_VERSION").unwrap_or("rustc"),
        }
    })
}

/// The numeric part of a version string (see `version_numeric`).
pub fn numeric(version: &str) -> String {
    version_numeric(version)
}

/// `true` for versions that must skip the update check: `0.0.0*`, `*no-git-tag*`, `*-dev*`, or
/// an empty string.
pub fn is_unstamped(version: &str) -> bool {
    let v = version.trim();
    v.is_empty() || v.starts_with("0.0.0") || v.contains("no-git-tag") || v.contains("-dev")
}

/// The line `StartupDiagnostics.recordLaunch` writes: `Start <version>[ (dev)] on <os> <arch>,
/// <compiler>, <n> argument(s)`. Hardcoded English on purpose (written before any locale).
pub fn launch_record_line(argument_count: usize) -> String {
    let info = info();
    format!(
        "Start {}{} on {} {}, {}, {} argument(s)",
        info.version,
        if info.is_dev { " (dev)" } else { "" },
        std::env::consts::OS,
        std::env::consts::ARCH,
        info.compiler,
        argument_count
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn numeric_cuts_describe_forms() {
        assert_eq!(numeric("2026.5.12"), "2026.5.12");
        assert_eq!(numeric("2026.5.12-15-gabc1234"), "2026.5.12");
        assert_eq!(numeric("2026.5.12-15-gabc1234.dirty"), "2026.5.12");
        assert_eq!(
            numeric("2026.05.12-pre.1432.UTC+0000+abc1234"),
            "2026.05.12"
        );
        assert_eq!(numeric("1.2.3+meta"), "1.2.3");
        assert_eq!(numeric("abc1234"), "abc1234");
        assert_eq!(numeric(""), "0.0.0");
        assert_eq!(numeric("-x"), "0.0.0");
        assert_eq!(numeric("0.0.0-no-git-tag"), "0.0.0");
    }

    #[test]
    fn unstamped_versions_skip_the_update_check() {
        assert!(is_unstamped(""));
        assert!(is_unstamped("0.0.0-no-git-tag"));
        assert!(is_unstamped("0.0.0-dev"));
        assert!(is_unstamped("2026.1.0-dev"));
        assert!(!is_unstamped("2026.05.12"));
        assert!(!is_unstamped("2026.05.12.1"));
        assert!(!is_unstamped("2026.5.12-15-gabc1234.dirty"));
    }

    #[test]
    fn flavour_rule() {
        assert_eq!(flavor_from_stamp("DEV", false), Flavor::Dev);
        assert_eq!(flavor_from_stamp("RELEASE", true), Flavor::Release);
        assert_eq!(flavor_from_stamp("", true), Flavor::Dev);
        assert_eq!(flavor_from_stamp("", false), Flavor::Release);
    }

    #[test]
    fn info_is_consistent() {
        let i = info();
        assert_eq!(i.is_dev, is_dev());
        assert_eq!(i.app_name_trimmed, i.app_name.replace(' ', ""));
        assert!(!i.version.is_empty());
        assert_eq!(i.version_numeric, VERSION_NUMERIC);
        assert!(launch_record_line(3).ends_with(", 3 argument(s)"));
    }
}
