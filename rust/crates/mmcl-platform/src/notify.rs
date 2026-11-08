// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Desktop notifications (`NotificationManager`).
//!
//! Every notification is also written to the log (`log.notification.fallback`) as a paper
//! trail, and that line is all that happens when native toasts are unavailable.
//!
//! - Windows: a `Shell_NotifyIconW` balloon on the launcher's tray icon (Windows 10 and later
//!   show it as a toast; no Start-menu AUMID shortcut needed).
//! - macOS: Notification Center via `osascript` (works without an app bundle).
//! - Linux: `org.freedesktop.Notifications` over D-Bus, urgency Critical for errors so they do
//!   not auto-expire.
//!
//! The tray icon is created lazily on the first notification and kept for the session
//! (**Guard:** adding and removing it around each toast dropped balloons on Windows).

/// How a notification is presented.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    /// Information.
    Info,
    /// Success (looks like information).
    Success,
    /// A warning.
    Warn,
    /// An error.
    Error,
}

/// An informational notification.
pub fn info(title: &str, body: &str) {
    show(Kind::Info, title, body);
}

/// A success notification (shown like [`info`]).
pub fn success(title: &str, body: &str) {
    show(Kind::Success, title, body);
}

/// A warning notification.
pub fn warn(title: &str, body: &str) {
    show(Kind::Warn, title, body);
}

/// An error notification.
pub fn error(title: &str, body: &str) {
    show(Kind::Error, title, body);
}

/// Shows a notification of `kind`. Never blocks on the native side for long and never panics.
pub fn show(kind: Kind, title: &str, body: &str) {
    tracing::info!("{}", fallback_line(title, body));
    let (title, body) = (title.to_string(), body.to_string());
    let deliver = move || {
        crate::best_effort("log.notification.displayFailed", || {
            native_show(kind, &title, &body)
        });
    };
    // The D-Bus round trip and osascript may take a moment; keep them off the caller.
    if cfg!(any(target_os = "linux", target_os = "macos")) {
        mmcl_util::tasks::spawn(deliver);
    } else {
        deliver();
    }
}

/// Delivers a notification natively on the calling thread, without the log line (for a
/// front end's notification facade that already logs and runs on its own worker). Windows and
/// Linux create the tray icon first. `Err` when the OS refused it.
pub fn deliver(kind: Kind, title: &str, body: &str) -> crate::error::Result<()> {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        native_show(kind, title, body)
    })) {
        Ok(r) => r,
        Err(payload) => Err(crate::PlatformError::Other(
            mmcl_util::startup_log::panic_message(payload.as_ref()),
        )),
    }
}

/// Removes the tray icon (idempotent).
pub fn shutdown() {
    crate::tray::shutdown();
}

/// The log line every notification leaves: `[notify] title — body`.
pub fn fallback_line(title: &str, body: &str) -> String {
    mmcl_i18n::tf!("log.notification.fallback", title, fallback_suffix(body))
}

/// `" — body"` when there is a body, else empty.
pub fn fallback_suffix(body: &str) -> String {
    if body.trim().is_empty() {
        String::new()
    } else {
        format!(" \u{2014} {body}")
    }
}

#[cfg(windows)]
fn native_show(kind: Kind, title: &str, body: &str) -> crate::error::Result<()> {
    crate::tray::ensure();
    crate::win::tray::balloon(kind, title, body)
}

#[cfg(target_os = "macos")]
fn native_show(kind: Kind, title: &str, body: &str) -> crate::error::Result<()> {
    let _ = kind;
    crate::mac::notify::show(title, body)
}

#[cfg(target_os = "linux")]
fn native_show(kind: Kind, title: &str, body: &str) -> crate::error::Result<()> {
    crate::tray::ensure();
    crate::linux::notify::show(kind, title, body)
}

#[cfg(not(any(windows, target_os = "macos", target_os = "linux")))]
fn native_show(_kind: Kind, _title: &str, _body: &str) -> crate::error::Result<()> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_paper_trail_line_includes_the_body_only_when_present() {
        assert_eq!(fallback_suffix(""), "");
        assert_eq!(fallback_suffix("  "), "");
        assert_eq!(fallback_suffix("Done"), " \u{2014} Done");
        let line = fallback_line("Title", "Body");
        assert!(line.contains("Title") && line.contains("Body"), "{line}");
    }
}
