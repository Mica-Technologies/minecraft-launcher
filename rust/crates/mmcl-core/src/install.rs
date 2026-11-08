// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! How this copy of the launcher was installed, and the executable to relaunch or register.
//!
//! Java used the `jpackage.app-path` property, which is unset for IDE runs. The Rust equivalent
//! is "the current executable, unless it is a cargo build output": an executable under a
//! `target` folder that sits next to a `Cargo.toml` is a dev run, which never registers itself
//! with the OS and restarts in-process instead of relaunching.

use std::path::{Path, PathBuf};

/// Set to any non-empty value to make relaunches fall back to an in-process restart.
pub const NO_RELAUNCH_ENV: &str = "MMCL_DEV_NO_RELAUNCH";

/// The kind of install the running executable belongs to.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub enum InstallKind {
    WindowsPerUser,
    WindowsPerMachine,
    MacAppBundle,
    LinuxSystemPackage,
    /// An AppImage, tarball, zip or other unpacked copy.
    Portable,
    /// A cargo build output (`cargo run`, tests).
    DevRun,
}

/// `true` when `exe` lives in a cargo `target` folder (a `target` ancestor whose parent holds a
/// `Cargo.toml`).
pub fn is_cargo_build_output(exe: &Path) -> bool {
    exe.ancestors().skip(1).any(|dir| {
        dir.file_name().is_some_and(|n| n == "target")
            && dir.parent().is_some_and(|p| p.join("Cargo.toml").is_file())
    })
}

/// The installed launcher executable, or `None` for a cargo build output.
pub fn launcher_executable() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    if is_cargo_build_output(&exe) {
        None
    } else {
        Some(exe)
    }
}

/// The executable to spawn for a relaunch, or `None` when the caller should restart in-process
/// (dev run, or [`NO_RELAUNCH_ENV`] set).
pub fn relaunch_executable() -> Option<PathBuf> {
    if std::env::var_os(NO_RELAUNCH_ENV).is_some_and(|v| !v.is_empty()) {
        return None;
    }
    launcher_executable()
}

/// Classifies an executable path. Pure over its inputs: `env` reads environment variables
/// (`ProgramFiles`, `ProgramFiles(x86)`, `LOCALAPPDATA`, `APPIMAGE`) and `os` is a
/// `std::env::consts::OS` value.
pub fn classify(exe: Option<&Path>, os: &str, env: &dyn Fn(&str) -> Option<String>) -> InstallKind {
    let Some(exe) = exe else {
        return InstallKind::DevRun;
    };
    if is_cargo_build_output(exe) {
        return InstallKind::DevRun;
    }
    let under = |var: &str| {
        env(var)
            .filter(|v| !v.is_empty())
            .is_some_and(|base| starts_with_ignore_case(exe, Path::new(&base)))
    };
    match os {
        "windows" => {
            if under("ProgramFiles") || under("ProgramFiles(x86)") || under("ProgramW6432") {
                InstallKind::WindowsPerMachine
            } else if under("LOCALAPPDATA") {
                InstallKind::WindowsPerUser
            } else {
                InstallKind::Portable
            }
        }
        "macos" => {
            let in_bundle = exe
                .ancestors()
                .any(|a| a.extension().is_some_and(|e| e.eq_ignore_ascii_case("app")));
            if in_bundle {
                InstallKind::MacAppBundle
            } else {
                InstallKind::Portable
            }
        }
        _ => {
            if env("APPIMAGE").is_some_and(|v| !v.is_empty()) {
                InstallKind::Portable
            } else if exe.starts_with("/usr") || exe.starts_with("/opt") {
                InstallKind::LinuxSystemPackage
            } else {
                InstallKind::Portable
            }
        }
    }
}

/// The install kind of the running executable.
pub fn install_kind() -> InstallKind {
    let exe = std::env::current_exe().ok();
    classify(exe.as_deref(), std::env::consts::OS, &|name| {
        std::env::var_os(name).map(|v| v.to_string_lossy().into_owned())
    })
}

fn starts_with_ignore_case(path: &Path, base: &Path) -> bool {
    let p = path.to_string_lossy().to_lowercase().replace('/', "\\");
    let b = base.to_string_lossy().to_lowercase().replace('/', "\\");
    let b = b.trim_end_matches('\\');
    p.starts_with(b) && p[b.len()..].starts_with('\\')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cargo_output_is_a_dev_run() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("Cargo.toml"), "").unwrap();
        let exe = dir.path().join("target").join("debug").join("app.exe");
        assert!(is_cargo_build_output(&exe));
        assert_eq!(
            classify(Some(&exe), "windows", &|_| None),
            InstallKind::DevRun
        );
        let other = dir.path().join("dist").join("app.exe");
        assert!(!is_cargo_build_output(&other));
        assert_eq!(classify(None, "linux", &|_| None), InstallKind::DevRun);
    }

    #[test]
    fn windows_scopes() {
        let env = |n: &str| match n {
            "ProgramFiles" => Some(r"C:\Program Files".to_string()),
            "LOCALAPPDATA" => Some(r"C:\Users\u\AppData\Local".to_string()),
            _ => None,
        };
        let machine = Path::new(r"C:\Program Files\Mica Minecraft Launcher\app.exe");
        let user = Path::new(r"C:\Users\u\AppData\Local\Programs\Mica\app.exe");
        let portable = Path::new(r"D:\Games\Mica\app.exe");
        let lookalike = Path::new(r"C:\Program Files Extra\app.exe");
        assert_eq!(
            classify(Some(machine), "windows", &env),
            InstallKind::WindowsPerMachine
        );
        assert_eq!(
            classify(Some(user), "windows", &env),
            InstallKind::WindowsPerUser
        );
        assert_eq!(
            classify(Some(portable), "windows", &env),
            InstallKind::Portable
        );
        assert_eq!(
            classify(Some(lookalike), "windows", &env),
            InstallKind::Portable
        );
    }

    #[test]
    fn unix_kinds() {
        let none = |_: &str| None;
        let app = Path::new("/Applications/Mica.app/Contents/MacOS/mica");
        assert_eq!(
            classify(Some(app), "macos", &none),
            InstallKind::MacAppBundle
        );
        let pkg = Path::new("/usr/bin/mica-minecraft-launcher-rs");
        assert_eq!(
            classify(Some(pkg), "linux", &none),
            InstallKind::LinuxSystemPackage
        );
        let appimage = |n: &str| (n == "APPIMAGE").then(|| "/home/u/Mica.AppImage".to_string());
        assert_eq!(
            classify(Some(pkg), "linux", &appimage),
            InstallKind::Portable
        );
        let home = Path::new("/home/u/mica/mica-minecraft-launcher");
        assert_eq!(classify(Some(home), "linux", &none), InstallKind::Portable);
    }
}
