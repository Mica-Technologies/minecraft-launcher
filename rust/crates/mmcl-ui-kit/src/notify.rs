// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! OS notifications (`NotificationManager`). The launcher's "toasts" are OS notifications, not
//! in-app snackbars, and warnings stay fire-and-forget.
//!
//! - Callable from any thread; never blocks the caller (the send runs on a background thread,
//!   serialised by a mutex like Java's `synchronized display`).
//! - Always logs `log.notification.fallback` as the paper trail.
//! - Windows: a toast under the launcher's AppUserModelID; macOS: Notification Center; Linux:
//!   D-Bus via `notify-rust`, then `notify-send` as a last resort (probed once, 2 s timeouts).
//! - Display failures log `log.notification.displayFailed` and use the fallback.
//! - The tray icon belongs to the platform slice; this facade never creates one. A platform
//!   backend can replace the default with [`set_backend`].

use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};

use mmcl_core::AppIdentity;
use mmcl_i18n::tf;
use parking_lot::{Mutex, RwLock};

/// A notification's kind.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Kind {
    /// Information.
    Info,
    /// Success (same visuals as info).
    Success,
    /// A warning.
    Warning,
    /// An error (critical urgency on Linux).
    Error,
}

/// Something that can show a notification.
pub trait NotificationBackend: Send + Sync {
    /// Shows it; `Err` with a message when it could not be shown.
    fn show(&self, kind: Kind, title: &str, body: &str) -> Result<(), String>;
}

/// The default backend: `notify-rust`, then `notify-send` on Linux.
#[derive(Debug, Default)]
pub struct SystemBackend;

impl NotificationBackend for SystemBackend {
    fn show(&self, kind: Kind, title: &str, body: &str) -> Result<(), String> {
        let identity = AppIdentity::current();
        let mut n = notify_rust::Notification::new();
        n.summary(title).appname(identity.display_name);
        if !body.trim().is_empty() {
            n.body(body);
        }
        #[cfg(target_os = "windows")]
        n.app_id(identity.win_aumid);
        #[cfg(all(unix, not(target_os = "macos")))]
        {
            n.icon(identity.linux_package_name);
            n.urgency(if kind == Kind::Error {
                notify_rust::Urgency::Critical
            } else {
                notify_rust::Urgency::Normal
            });
        }
        let _ = kind;
        match n.show() {
            Ok(_) => Ok(()),
            Err(e) => {
                #[cfg(all(unix, not(target_os = "macos")))]
                {
                    if notify_send(kind, title, body) {
                        return Ok(());
                    }
                }
                Err(e.to_string())
            }
        }
    }
}

/// The `notify-send` command line for a notification (pure, for tests).
pub fn notify_send_args(
    app_name: &str,
    icon: &str,
    kind: Kind,
    title: &str,
    body: &str,
) -> Vec<String> {
    let mut args = vec![
        format!("--app-name={app_name}"),
        format!("--icon={icon}"),
        format!(
            "--urgency={}",
            if kind == Kind::Error {
                "critical"
            } else {
                "normal"
            }
        ),
        title.to_string(),
    ];
    if !body.trim().is_empty() {
        args.push(body.to_string());
    }
    args
}

/// Runs a child process and waits at most `timeout`; `true` iff it exited successfully.
fn run_bounded(mut cmd: Command, timeout: Duration) -> bool {
    let Ok(mut child) = cmd
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    else {
        return false;
    };
    let deadline = Instant::now() + timeout;
    loop {
        match child.try_wait() {
            Ok(Some(status)) => return status.success(),
            Ok(None) if Instant::now() < deadline => std::thread::sleep(Duration::from_millis(25)),
            _ => {
                let _ = child.kill();
                let _ = child.wait();
                return false;
            }
        }
    }
}

/// Whether `notify-send` exists (probed once with `sh -c "command -v notify-send"`).
#[cfg_attr(not(all(unix, not(target_os = "macos"))), allow(dead_code))]
fn notify_send_available() -> bool {
    static AVAILABLE: OnceLock<bool> = OnceLock::new();
    *AVAILABLE.get_or_init(|| {
        let mut cmd = Command::new("sh");
        cmd.args(["-c", "command -v notify-send"]);
        run_bounded(cmd, Duration::from_secs(2))
    })
}

#[cfg_attr(not(all(unix, not(target_os = "macos"))), allow(dead_code))]
fn notify_send(kind: Kind, title: &str, body: &str) -> bool {
    if !notify_send_available() {
        return false;
    }
    let mut cmd = Command::new("notify-send");
    cmd.args(notify_send_args(
        AppIdentity::current().display_name,
        AppIdentity::current().linux_package_name,
        kind,
        title,
        body,
    ));
    run_bounded(cmd, Duration::from_secs(2))
}

static BACKEND: RwLock<Option<Arc<dyn NotificationBackend>>> = RwLock::new(None);
static SERIAL: Mutex<()> = Mutex::new(());
static SHUT_DOWN: AtomicBool = AtomicBool::new(false);

/// Replaces the backend (the platform slice's tray / toast implementation).
pub fn set_backend(backend: Arc<dyn NotificationBackend>) {
    *BACKEND.write() = Some(backend);
}

/// The paper-trail log line text (`log.notification.fallback`).
pub fn fallback_line(title: &str, body: &str) -> String {
    let suffix = if body.trim().is_empty() {
        String::new()
    } else {
        format!(" — {body}")
    };
    tf!("log.notification.fallback", title, suffix)
}

fn display(kind: Kind, title: &str, body: &str) {
    tracing::info!("{}", fallback_line(title, body));
    if SHUT_DOWN.load(Ordering::Relaxed) {
        return;
    }
    let backend = BACKEND
        .read()
        .clone()
        .unwrap_or_else(|| Arc::new(SystemBackend));
    let (title, body) = (title.to_string(), body.to_string());
    let spawned = std::thread::Builder::new()
        .name("mmcl-notify".into())
        .spawn(move || {
            let _serial = SERIAL.lock();
            if let Err(msg) = backend.show(kind, &title, &body) {
                tracing::warn!("{}", tf!("log.notification.displayFailed", msg));
            }
        });
    if let Err(e) = spawned {
        tracing::warn!("{}", tf!("log.notification.displayFailed", e.to_string()));
    }
}

/// An information notification.
pub fn info(title: &str, body: &str) {
    display(Kind::Info, title, body);
}

/// A success notification (same visuals as info).
pub fn success(title: &str, body: &str) {
    display(Kind::Success, title, body);
}

/// A warning notification.
pub fn warn(title: &str, body: &str) {
    display(Kind::Warning, title, body);
}

/// An error notification.
pub fn error(title: &str, body: &str) {
    display(Kind::Error, title, body);
}

/// Stops showing notifications (once at exit; idempotent). Later calls still log.
pub fn shutdown() {
    SHUT_DOWN.store(true, Ordering::Relaxed);
}

/// Whether [`shutdown`] has run.
pub fn is_shut_down() -> bool {
    SHUT_DOWN.load(Ordering::Relaxed)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn notify_send_command_line() {
        assert_eq!(
            notify_send_args(
                "Mica",
                "mica-minecraft-launcher",
                Kind::Error,
                "Oops",
                "it broke"
            ),
            vec![
                "--app-name=Mica",
                "--icon=mica-minecraft-launcher",
                "--urgency=critical",
                "Oops",
                "it broke"
            ]
        );
        let no_body = notify_send_args("Mica", "icon", Kind::Info, "Hi", "  ");
        assert_eq!(no_body.len(), 4);
        assert_eq!(no_body[2], "--urgency=normal");
    }

    #[test]
    fn fallback_line_omits_a_blank_body() {
        let with = fallback_line("Title", "Body");
        assert!(with.contains("Title") && with.contains(" — Body"), "{with}");
        let without = fallback_line("Title", " ");
        assert!(!without.contains('—'), "{without}");
    }

    struct Recorder(Mutex<Vec<(Kind, String, String)>>);
    impl NotificationBackend for Recorder {
        fn show(&self, kind: Kind, title: &str, body: &str) -> Result<(), String> {
            self.0.lock().push((kind, title.into(), body.into()));
            Ok(())
        }
    }

    #[test]
    fn backend_receives_notifications_until_shutdown() {
        let rec = Arc::new(Recorder(Mutex::new(Vec::new())));
        set_backend(rec.clone());
        warn("W", "body");
        let deadline = Instant::now() + Duration::from_secs(5);
        while rec.0.lock().is_empty() && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(rec.0.lock()[0], (Kind::Warning, "W".into(), "body".into()));
        shutdown();
        shutdown();
        assert!(is_shut_down());
        error("E", "");
        std::thread::sleep(Duration::from_millis(50));
        assert_eq!(rec.0.lock().len(), 1);
    }
}
