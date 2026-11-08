// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Linux integration over the session D-Bus: freedesktop notifications, the StatusNotifierItem
//! tray, the Unity LauncherEntry progress signal and the xdg-desktop-portal colour scheme.
//!
//! zbus runs on a small private tokio runtime owned by this module, so callers need no runtime
//! of their own and GPUI's loop is not involved.

pub mod launcher_entry;
pub mod notify;
pub mod tray;
pub mod x11;

use std::future::Future;
use std::sync::LazyLock;
use std::time::Duration;

use parking_lot::Mutex;
use zbus::zvariant::Value;

/// Timeout for one D-Bus round trip.
pub(crate) const DBUS_TIMEOUT: Duration = Duration::from_secs(2);

static RUNTIME: LazyLock<Option<tokio::runtime::Runtime>> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(1)
        .thread_name("mmcl-dbus")
        .enable_all()
        .build()
        .ok()
});

static SESSION: Mutex<Option<zbus::Connection>> = Mutex::new(None);

/// Runs `f` to completion on the D-Bus runtime. Safe to call from inside another tokio runtime
/// (it then blocks a scoped helper thread instead).
pub(crate) fn block_on<F>(f: F) -> Option<F::Output>
where
    F: Future + Send,
    F::Output: Send,
{
    let rt = RUNTIME.as_ref()?;
    if tokio::runtime::Handle::try_current().is_ok() {
        std::thread::scope(|s| s.spawn(|| rt.block_on(f)).join().ok())
    } else {
        Some(rt.block_on(f))
    }
}

/// The shared session-bus connection, connecting on first use.
pub(crate) async fn session() -> Result<zbus::Connection, String> {
    if let Some(c) = SESSION.lock().clone() {
        return Ok(c);
    }
    let conn = tokio::time::timeout(DBUS_TIMEOUT, zbus::Connection::session())
        .await
        .map_err(|_| "session bus timeout".to_string())?
        .map_err(|e| e.to_string())?;
    *SESSION.lock() = Some(conn.clone());
    Ok(conn)
}

/// Drops the cached connection (after an error, so the next call reconnects).
pub(crate) fn reset_session() {
    SESSION.lock().take();
}

/// A `u32` inside possibly nested variants (`Settings.Read` wraps the value twice).
pub(crate) fn as_u32(v: &Value<'_>) -> Option<u32> {
    match v {
        Value::U32(n) => Some(*n),
        Value::Value(inner) => as_u32(inner),
        _ => None,
    }
}

/// `org.freedesktop.appearance color-scheme` from xdg-desktop-portal: 0 no preference,
/// 1 prefer dark, 2 prefer light. `None` when the portal is missing.
pub fn portal_color_scheme() -> Option<u32> {
    block_on(async {
        let conn = session().await.ok()?;
        let proxy = zbus::Proxy::new(
            &conn,
            "org.freedesktop.portal.Desktop",
            "/org/freedesktop/portal/desktop",
            "org.freedesktop.portal.Settings",
        )
        .await
        .ok()?;
        let read_one = tokio::time::timeout(
            DBUS_TIMEOUT,
            proxy.call::<_, _, zbus::zvariant::OwnedValue>(
                "ReadOne",
                &("org.freedesktop.appearance", "color-scheme"),
            ),
        )
        .await
        .ok()?;
        if let Ok(v) = read_one {
            return as_u32(&v);
        }
        // Older portals only have the deprecated `Read`.
        let read = tokio::time::timeout(
            DBUS_TIMEOUT,
            proxy.call::<_, _, zbus::zvariant::OwnedValue>(
                "Read",
                &("org.freedesktop.appearance", "color-scheme"),
            ),
        )
        .await
        .ok()?
        .ok()?;
        as_u32(&read)
    })
    .flatten()
}

/// Whether we run inside a Flatpak sandbox (no well-known bus name for the tray there).
pub(crate) fn is_flatpak() -> bool {
    std::path::Path::new("/.flatpak-info").exists()
}

/// The `.desktop` id without its extension (the `desktop-entry` hint and the app URI).
pub(crate) fn desktop_stem() -> &'static str {
    let id = mmcl_core::AppIdentity::current().linux_desktop_id;
    id.strip_suffix(".desktop").unwrap_or(id)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nested_variants_unwrap() {
        let v = Value::Value(Box::new(Value::Value(Box::new(Value::U32(1)))));
        assert_eq!(as_u32(&v), Some(1));
        assert_eq!(as_u32(&Value::from("x")), None);
    }

    #[test]
    fn desktop_stem_drops_the_extension() {
        assert!(!desktop_stem().ends_with(".desktop"));
    }
}
