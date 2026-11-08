// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `org.freedesktop.Notifications.Notify` (replaces Java's `notify-send` fallback, the common
//! path on GNOME/Wayland).

use std::collections::HashMap;

use zbus::zvariant::Value;

use super::{DBUS_TIMEOUT, block_on, desktop_stem, reset_session, session};
use crate::error::{PlatformError, Result};
use crate::notify::Kind;

/// freedesktop urgency: 1 normal, 2 critical (errors do not auto-expire).
pub fn urgency(kind: Kind) -> u8 {
    match kind {
        Kind::Error => 2,
        _ => 1,
    }
}

/// Sends one notification.
pub fn show(kind: Kind, title: &str, body: &str) -> Result<()> {
    let identity = mmcl_core::AppIdentity::current();
    let result = block_on(async {
        let conn = session().await?;
        let proxy = zbus::Proxy::new(
            &conn,
            "org.freedesktop.Notifications",
            "/org/freedesktop/Notifications",
            "org.freedesktop.Notifications",
        )
        .await
        .map_err(|e| e.to_string())?;
        let mut hints: HashMap<&str, Value<'_>> = HashMap::new();
        hints.insert("urgency", Value::U8(urgency(kind)));
        hints.insert("desktop-entry", Value::from(desktop_stem()));
        let actions: Vec<&str> = Vec::new();
        let args = (
            identity.display_name,
            0u32,
            identity.linux_package_name,
            title,
            body,
            actions,
            hints,
            -1i32,
        );
        let call = proxy.call::<_, _, u32>("Notify", &args);
        tokio::time::timeout(DBUS_TIMEOUT, call)
            .await
            .map_err(|_| "Notify timed out".to_string())?
            .map_err(|e| e.to_string())
    });
    match result {
        Some(Ok(_)) => Ok(()),
        Some(Err(e)) => {
            reset_session();
            Err(PlatformError::DBus(e))
        }
        None => Err(PlatformError::Unsupported),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn errors_are_critical() {
        assert_eq!(urgency(Kind::Error), 2);
        assert_eq!(urgency(Kind::Warn), 1);
        assert_eq!(urgency(Kind::Info), 1);
    }
}
