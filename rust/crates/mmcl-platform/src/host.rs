// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The callbacks the app crate provides to this crate.
//!
//! Native integration points (the IPC accept thread, tray clicks, jump-list refreshes, the
//! download throttle) need to reach the GUI, the pack list and the config. Those live in higher
//! crates, so the app installs one [`PlatformHost`] at startup with [`set_host`]. Every method
//! has a harmless default, and nothing here requires a host: without one the integration
//! simply has nothing to show.
//!
//! Methods may be called from any thread. Implementations post GUI work to the UI thread
//! themselves and must not block on it from the UI thread.

use std::sync::Arc;

use parking_lot::RwLock;

use crate::menus::MenuAction;

/// One recently played modpack, as the shell surfaces show it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RecentPack {
    /// The pack's unique name (what `mmcl://play?name=` and `-c` take).
    pub pack_name: String,
    /// The name to show (the friendly name, else the pack name).
    pub display_name: String,
    /// When it was last played, in epoch milliseconds (0 = never).
    pub last_played_ms: i64,
}

impl RecentPack {
    /// A recent pack.
    pub fn new(
        pack_name: impl Into<String>,
        display_name: impl Into<String>,
        last_played_ms: i64,
    ) -> Self {
        RecentPack {
            pack_name: pack_name.into(),
            display_name: display_name.into(),
            last_played_ms,
        }
    }

    /// The display name with control characters removed (for menus, jump lists and
    /// `.desktop` action names), falling back to the pack name.
    pub fn safe_display_name(&self) -> String {
        let clean: String = self
            .display_name
            .chars()
            .filter(|c| *c >= ' ' && *c != '\u{7f}')
            .collect();
        if clean.trim().is_empty() {
            self.pack_name
                .chars()
                .filter(|c| *c >= ' ' && *c != '\u{7f}')
                .collect()
        } else {
            clean
        }
    }
}

/// What the app crate does for this crate. All methods have no-op defaults.
pub trait PlatformHost: Send + Sync {
    /// Whether the main window exists yet (the IPC reply says `OK WINDOW` or `OK NOWINDOW`).
    fn has_main_window(&self) -> bool {
        false
    }

    /// Brings the main window forward (`window.activate_window()`; on Windows the app can add
    /// [`crate::chrome::bring_to_front`]).
    fn focus_window(&self) {}

    /// Handles an `mmcl://` deep link (from IPC or another source). The dispatcher confirms
    /// with the user where the spec requires it.
    fn handle_deep_link(&self, uri: &str) {
        let _ = uri;
    }

    /// Runs a tray or dock menu action.
    fn on_menu_action(&self, action: MenuAction) {
        let _ = action;
    }

    /// Up to `max` recently played packs, newest first (`RecentPacks.getRecent`).
    fn recent_packs(&self, max: usize) -> Vec<RecentPack> {
        let _ = max;
        Vec::new()
    }

    /// The `batteryThrottleEnable` setting, or `None` while the config is not ready (which
    /// counts as enabled).
    fn battery_throttle_enabled(&self) -> Option<bool> {
        None
    }
}

static HOST: RwLock<Option<Arc<dyn PlatformHost>>> = RwLock::new(None);

/// Installs the app's host. Replaces any earlier one.
pub fn set_host(host: Arc<dyn PlatformHost>) {
    *HOST.write() = Some(host);
}

/// Removes the host (shutdown, tests).
pub fn clear_host() {
    *HOST.write() = None;
}

/// The installed host, if any.
pub fn host() -> Option<Arc<dyn PlatformHost>> {
    HOST.read().clone()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn safe_display_name_strips_controls_and_falls_back() {
        assert_eq!(
            RecentPack::new("p", "A\r\nB\u{7f}", 1).safe_display_name(),
            "AB"
        );
        assert_eq!(
            RecentPack::new("pack\n", " \t", 1).safe_display_name(),
            "pack"
        );
    }
}
