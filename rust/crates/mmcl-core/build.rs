// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.
//
// Stamps the build identity into the crate as compile-time environment variables:
//
// - `MMCL_VERSION`: env `MMCL_VERSION` (non-empty), else `git describe --tags --dirty=.dirty
//   --always` run in this crate's directory (5 s cap), else the sentinel `0.0.0-no-git-tag`.
// - `MMCL_VERSION_NUMERIC`: env `MMCL_VERSION_NUMERIC`, else the version cut at `-` then `+`.
// - `MMCL_FLAVOR`: `DEV` or `RELEASE` when `MMCL_ENVIRONMENT` (or the older `MMCL_ENV`) says so,
//   empty otherwise (the library then falls back to `debug_assertions`).
//
// The launcher never shells out to git at run time.

use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

include!("src/version_numeric.rs");

const SENTINEL_VERSION: &str = "0.0.0-no-git-tag";

fn main() {
    for var in [
        "MMCL_VERSION",
        "MMCL_VERSION_NUMERIC",
        "MMCL_ENVIRONMENT",
        "MMCL_ENV",
    ] {
        println!("cargo:rerun-if-env-changed={var}");
    }

    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap_or_default());
    watch_git_state(&manifest_dir);

    let version = non_empty_env("MMCL_VERSION")
        .or_else(|| git_describe(&manifest_dir))
        .unwrap_or_else(|| SENTINEL_VERSION.to_string());
    let numeric =
        non_empty_env("MMCL_VERSION_NUMERIC").unwrap_or_else(|| version_numeric(&version));
    let flavor = non_empty_env("MMCL_ENVIRONMENT")
        .or_else(|| non_empty_env("MMCL_ENV"))
        .map(|v| {
            if v.eq_ignore_ascii_case("DEV") {
                "DEV".to_string()
            } else if v.eq_ignore_ascii_case("RELEASE") {
                "RELEASE".to_string()
            } else {
                String::new()
            }
        })
        .unwrap_or_default();

    println!("cargo:rustc-env=MMCL_VERSION={version}");
    println!("cargo:rustc-env=MMCL_VERSION_NUMERIC={numeric}");
    println!("cargo:rustc-env=MMCL_FLAVOR={flavor}");
    if let Some(rustc) = rustc_version() {
        println!("cargo:rustc-env=MMCL_RUSTC_VERSION={rustc}");
    }
}

/// `rustc 1.xx.y` of the compiler building this crate, for the startup diagnostics line.
fn rustc_version() -> Option<String> {
    let rustc = std::env::var("RUSTC").unwrap_or_else(|_| "rustc".to_string());
    let output = Command::new(rustc).arg("--version").output().ok()?;
    let text = String::from_utf8_lossy(&output.stdout);
    let mut parts = text.split_whitespace();
    let name = parts.next()?;
    let version = parts.next()?;
    Some(format!("{name} {version}"))
}

fn non_empty_env(name: &str) -> Option<String> {
    std::env::var(name)
        .ok()
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

/// Runs `git describe` with a hard time cap, returning its first output line.
fn git_describe(dir: &Path) -> Option<String> {
    let mut child = Command::new("git")
        .args(["describe", "--tags", "--dirty=.dirty", "--always"])
        .current_dir(dir)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .ok()?;
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                if !status.success() {
                    return None;
                }
                break;
            }
            Ok(None) => {
                if Instant::now() >= deadline {
                    let _ = child.kill();
                    let _ = child.wait();
                    return None;
                }
                std::thread::sleep(Duration::from_millis(25));
            }
            Err(_) => return None,
        }
    }
    let output = child.wait_with_output().ok()?;
    let text = String::from_utf8_lossy(&output.stdout);
    let line = text.lines().next()?.trim();
    if line.is_empty() {
        None
    } else {
        Some(line.to_string())
    }
}

/// Re-runs this script when HEAD, the checked-out branch or the tags move. Handles worktrees,
/// where `.git` is a file pointing at the real git dir and refs live in the common dir.
fn watch_git_state(start: &Path) {
    let Some(git_dir) = find_git_dir(start) else {
        return;
    };
    let common_dir = std::fs::read_to_string(git_dir.join("commondir"))
        .ok()
        .map(|c| resolve_relative(&git_dir, c.trim()))
        .unwrap_or_else(|| git_dir.clone());

    let head = git_dir.join("HEAD");
    if head.exists() {
        println!("cargo:rerun-if-changed={}", head.display());
        if let Ok(text) = std::fs::read_to_string(&head)
            && let Some(reference) = text.trim().strip_prefix("ref:")
        {
            let reference = reference.trim();
            for base in [&git_dir, &common_dir] {
                let path = base.join(reference);
                if path.exists() {
                    println!("cargo:rerun-if-changed={}", path.display());
                }
            }
        }
    }
    for extra in ["refs/tags", "packed-refs"] {
        let path = common_dir.join(extra);
        if path.exists() {
            println!("cargo:rerun-if-changed={}", path.display());
        }
    }
}

fn find_git_dir(start: &Path) -> Option<PathBuf> {
    let mut current = Some(start);
    while let Some(dir) = current {
        let candidate = dir.join(".git");
        if candidate.is_dir() {
            return Some(candidate);
        }
        if candidate.is_file() {
            let text = std::fs::read_to_string(&candidate).ok()?;
            let target = text.trim().strip_prefix("gitdir:")?.trim().to_string();
            return Some(resolve_relative(dir, &target));
        }
        current = dir.parent();
    }
    None
}

fn resolve_relative(base: &Path, value: &str) -> PathBuf {
    let path = PathBuf::from(value);
    if path.is_absolute() {
        path
    } else {
        base.join(path)
    }
}
