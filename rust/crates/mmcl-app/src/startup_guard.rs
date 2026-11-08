// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The in-process startup guard (packaging-ci §3.7, replacing Java's native starters).
//!
//! - [`install`]: the panic hook (every panic goes to `logs/startup.log`, the session log and
//!   stderr) and the launch record line.
//! - [`attach_console`]: on Windows release builds the executable uses the GUI subsystem, so a
//!   terminal mode (`--cli`, `-s`, usage errors) re-attaches to the parent's console.
//! - [`show_native_dialog`]: an always-on-top native message box for failures that happen
//!   before (or without) the GPUI window: a second launch that cannot reach the first, a
//!   start-up error. Falls back to stderr when no dialog can be shown.

/// How a native dialog looks.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DialogLevel {
    Warning,
    Error,
}

/// Installs the panic hook and, unless `record_launch` is false (the `--mcp` relay), writes the
/// `Start ...` line to `startup.log`.
pub fn install(argc: usize, record_launch: bool) {
    mmcl_log::startup_diag::install_panic_hook();
    if record_launch {
        mmcl_log::startup_diag::record_launch(argc);
    }
}

/// Records an early failure in `startup.log`.
pub fn record(message: &str) {
    mmcl_log::startup_diag::record(message);
}

/// Windows: attaches to the parent process's console when this process has no usable standard
/// output (a GUI-subsystem release build started from a terminal). Pipes handed down by a parent
/// (the sign-in and RGB helpers, the MCP relay) are left alone. Elsewhere it does nothing.
pub fn attach_console() {
    #[cfg(windows)]
    win::attach_parent_console();
}

/// Shows `body` in a native always-on-top dialog titled `title`. Prints to stderr when
/// `headless` is true or no dialog backend is available.
pub fn show_native_dialog(level: DialogLevel, title: &str, body: &str, headless: bool) {
    if headless || !platform_dialog(level, title, body) {
        eprintln!("{body}");
    }
}

#[cfg(windows)]
fn platform_dialog(level: DialogLevel, title: &str, body: &str) -> bool {
    win::message_box(level, title, body)
}

#[cfg(target_os = "macos")]
fn platform_dialog(level: DialogLevel, title: &str, body: &str) -> bool {
    // `on run argv` passes the texts as arguments, so nothing is spliced into the script.
    let kind = match level {
        DialogLevel::Warning => "warning",
        DialogLevel::Error => "critical",
    };
    std::process::Command::new("/usr/bin/osascript")
        .args([
            "-e",
            "on run argv",
            "-e",
            &format!("display alert (item 1 of argv) message (item 2 of argv) as {kind}"),
            "-e",
            "end run",
            title,
            body,
        ])
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .is_ok_and(|s| s.success())
}

#[cfg(all(unix, not(target_os = "macos")))]
fn platform_dialog(level: DialogLevel, title: &str, body: &str) -> bool {
    use std::process::{Command, Stdio};
    if std::env::var_os("DISPLAY").is_none() && std::env::var_os("WAYLAND_DISPLAY").is_none() {
        return false;
    }
    let quiet = |c: &mut Command| {
        c.stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
    };
    let zenity_kind = match level {
        DialogLevel::Warning => "--warning",
        DialogLevel::Error => "--error",
    };
    let mut zenity = Command::new("zenity");
    zenity.args([zenity_kind, "--title", title, "--text", body, "--no-markup"]);
    quiet(&mut zenity);
    if zenity.status().is_ok_and(|s| s.success()) {
        return true;
    }
    let kdialog_kind = match level {
        DialogLevel::Warning => "--sorry",
        DialogLevel::Error => "--error",
    };
    let mut kdialog = Command::new("kdialog");
    kdialog.args(["--title", title, kdialog_kind, body]);
    quiet(&mut kdialog);
    kdialog.status().is_ok_and(|s| s.success())
}

#[cfg(not(any(windows, unix)))]
fn platform_dialog(_level: DialogLevel, _title: &str, _body: &str) -> bool {
    false
}

#[cfg(windows)]
mod win {
    use super::DialogLevel;
    use windows_sys::Win32::Foundation::{HANDLE, INVALID_HANDLE_VALUE};
    use windows_sys::Win32::System::Console::{
        ATTACH_PARENT_PROCESS, AttachConsole, GetStdHandle, STD_OUTPUT_HANDLE,
    };
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        MB_ICONERROR, MB_ICONWARNING, MB_OK, MB_SETFOREGROUND, MB_TOPMOST, MessageBoxW,
    };

    fn usable(h: HANDLE) -> bool {
        !h.is_null() && h != INVALID_HANDLE_VALUE
    }

    pub fn attach_parent_console() {
        // SAFETY: plain Win32 calls with constant arguments.
        unsafe {
            if usable(GetStdHandle(STD_OUTPUT_HANDLE)) {
                return;
            }
            // Fails harmlessly when the parent has no console (Explorer, a shortcut).
            AttachConsole(ATTACH_PARENT_PROCESS);
        }
    }

    fn wide(s: &str) -> Vec<u16> {
        s.encode_utf16().chain(std::iter::once(0)).collect()
    }

    pub fn message_box(level: DialogLevel, title: &str, body: &str) -> bool {
        let icon = match level {
            DialogLevel::Warning => MB_ICONWARNING,
            DialogLevel::Error => MB_ICONERROR,
        };
        let (title, body) = (wide(title), wide(body));
        // SAFETY: both buffers are NUL-terminated and outlive the call; no owner window.
        let r = unsafe {
            MessageBoxW(
                std::ptr::null_mut(),
                body.as_ptr(),
                title.as_ptr(),
                MB_OK | icon | MB_TOPMOST | MB_SETFOREGROUND,
            )
        };
        r != 0
    }
}
