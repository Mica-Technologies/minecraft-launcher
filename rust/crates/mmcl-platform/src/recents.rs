// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Recent-modpack surfaces (`JumpListManager`): run at startup after registration and after
//! every successful launch.
//!
//! - Windows: the jump list's "Recent Modpacks" category ([`crate::win::jump_list`]).
//! - Linux: the `.desktop` file's `Actions=` ([`crate::register::refresh_recent_packs`]), the
//!   single writer of that file.
//! - macOS: nothing here; the app rebuilds the dock menu with `cx.set_dock_menu` from
//!   [`crate::menus::dock_menu`] (the dock has no on-show hook).
//!
//! Each entry runs `<exe> mmcl://play?name=<pack>` directly, so it works even though the Rust
//! identity does not register the `mmcl://` scheme.

/// How many recent packs the jump list and `.desktop` actions show.
pub const RECENT_MAX: usize = 5;

/// Refreshes the recent-modpack surfaces on a worker. Never blocks the caller.
pub fn refresh() {
    mmcl_util::tasks::spawn(|| crate::guard(refresh_now));
}

/// Refreshes the recent-modpack surfaces on the calling thread.
pub fn refresh_now() {
    #[cfg(windows)]
    {
        let identity = mmcl_core::AppIdentity::current();
        if identity.is_dev() {
            return;
        }
        let Some(exe) = crate::register::launcher_exe_path() else {
            tracing::debug!("{}", mmcl_i18n::t!("log.jumpList.noExePath"));
            return;
        };
        let packs = recent_packs();
        if let Err(e) = crate::win::jump_list::refresh(&exe, &packs) {
            tracing::warn!(
                "{}",
                mmcl_i18n::tf!(
                    "log.jumpListManager.refreshFailed",
                    "jumpList",
                    e.to_string()
                )
            );
        }
    }
    #[cfg(target_os = "linux")]
    crate::register::refresh_recent_packs();
}

/// The host's recent packs (at most [`RECENT_MAX`]) with a usable name.
#[cfg_attr(not(any(windows, target_os = "linux")), allow(dead_code))]
pub(crate) fn recent_packs() -> Vec<crate::host::RecentPack> {
    let mut packs = crate::host::host()
        .map(|h| h.recent_packs(RECENT_MAX))
        .unwrap_or_default();
    packs.retain(|p| !p.pack_name.trim().is_empty());
    packs.truncate(RECENT_MAX);
    packs
}
