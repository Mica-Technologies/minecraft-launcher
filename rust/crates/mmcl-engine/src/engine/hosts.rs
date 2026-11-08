// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The engine's side of RGB and Discord: the [`mmcl_rgb::RgbHost`] and
//! [`mmcl_discord::PresenceHost`] implementations, and the running-game follower (Java
//! `RunningGameFollower` + `LauncherCore.ensureRunningGameFollower`) that keeps both on the
//! newest running game.

use std::sync::{Arc, Weak};

use mmcl_rgb::PackRef;

use super::{Engine, Inner};
use crate::session::SessionInfo;

/// What the hosts read the launcher through (weak, so the globals never keep an engine alive).
pub(crate) struct EngineHost {
    pub(crate) inner: Weak<Inner>,
}

impl EngineHost {
    fn engine(&self) -> Option<Engine> {
        self.inner.upgrade().map(|inner| Engine { inner })
    }
}

impl mmcl_rgb::RgbHost for EngineHost {
    fn settings(&self) -> mmcl_rgb::RgbSettings {
        match self.engine() {
            Some(e) => crate::settings::rgb_settings(&e.inner.config),
            None => mmcl_rgb::RgbSettings::default(),
        }
    }

    fn os_is_dark(&self) -> bool {
        mmcl_platform::theme::is_os_dark()
    }

    fn newest_running_pack(&self) -> Option<PackRef> {
        let e = self.engine()?;
        let s = e.newest_running_session()?;
        Some(e.pack_ref(&s))
    }
}

impl mmcl_discord::PresenceHost for EngineHost {
    fn rpc_enabled(&self) -> bool {
        self.engine().is_some_and(|e| e.inner.config.discord_rpc())
    }

    fn invites_enabled(&self) -> bool {
        self.engine()
            .is_some_and(|e| e.inner.config.discord_invites())
    }

    fn any_game_running(&self) -> bool {
        self.engine()
            .is_some_and(|e| e.newest_running_session().is_some())
    }

    fn handle_join(&self, uri: &str) {
        // The Rust build does not own `mmcl://` (the Java launcher keeps the scheme), so a
        // Discord join is logged rather than dispatched.
        mmcl_log::debug(&mmcl_i18n::tf!(
            "log.engine.discordJoinIgnored",
            mmcl_log::redact(uri).into_owned()
        ));
    }
}

impl Engine {
    /// Registers the RGB and Discord hosts (once per engine; a later engine replaces them).
    pub(super) fn install_hosts(&self) {
        let host = Arc::new(EngineHost {
            inner: Arc::downgrade(&self.inner),
        });
        mmcl_rgb::integration::install(host.clone());
        mmcl_discord::Presence::global().set_host(host);
    }

    /// The RGB view of a session's pack.
    pub(crate) fn pack_ref(&self, s: &SessionInfo) -> PackRef {
        let pack = self.installed_pack(&s.pack);
        let logo = pack
            .as_ref()
            .map(|p| p.environment().logo_path_raw(p))
            .filter(|p| p.is_file());
        PackRef {
            key: s.pack.as_str().to_string(),
            friendly_name: s.pack_name.clone(),
            logo_path: logo,
            vanilla: pack.as_ref().is_some_and(|p| p.is_vanilla()),
        }
    }

    /// Points RGB and Discord at the newest running game (or back at the menu). Only a change
    /// of the shown game does anything; updates apply in order and only the newest one runs.
    pub(crate) fn follow_running_games(&self) {
        if !self.inner.follow_games {
            return;
        }
        // The snapshot is taken, and the update submitted, inside the follower lock: two
        // changes on different threads could otherwise apply their snapshots out of order and
        // leave RGB / Discord on a game that already exited (spec bug-guard 14).
        let mut shown = self.inner.followed.lock();
        let next = self.newest_running_session();
        let next_id = next.as_ref().map(|s| s.id);
        // Unchanged (including "still no game") does nothing.
        if *shown == next_id {
            return;
        }
        *shown = next_id;
        match next {
            Some(s) => {
                let pack_ref = self.pack_ref(&s);
                let pack = self.installed_pack(&s.pack);
                let custom_rpc = pack
                    .as_ref()
                    .is_some_and(|p| p.manifest().pack_custom_discord_rpc);
                let url = pack
                    .as_ref()
                    .and_then(|p| p.manifest_url().map(str::to_string));
                let name = s.pack_name.clone();
                self.inner.follower.submit(move || {
                    mmcl_rgb::integration::on_play_started(&pack_ref);
                    mmcl_discord::Presence::global().set_game_presence(
                        &name,
                        custom_rpc,
                        url.as_deref(),
                    );
                });
            }
            None => {
                self.inner.follower.submit(|| {
                    mmcl_rgb::integration::on_play_ended();
                    mmcl_discord::Presence::global()
                        .set_menu_presence(&mmcl_i18n::t!("discordRpc.screen.selectingPack"));
                });
            }
        }
    }
}
