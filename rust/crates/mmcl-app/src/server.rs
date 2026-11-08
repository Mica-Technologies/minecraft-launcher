// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Server mode (`-s [name]`, core-config-i18n §6.4 server branch). The engine runs the server
//! and its crash-restart loop ([`Engine::run_server`]); this picks the pack.

use mmcl_engine::{Engine, PackSummary};

/// The pack a server start uses and whether the requested name was missing.
#[derive(Debug, Clone, PartialEq)]
pub struct Pick {
    pub pack: Option<PackSummary>,
    /// A name was given but is not installed (Java logs `PACK_NOT_INSTALLED_...` and falls
    /// back to the first pack).
    pub requested_missing: bool,
}

/// Picks the pack: the requested one (friendly name, then pack name, case-insensitive),
/// else the first installed pack that loaded.
pub fn pick(selection: &str, installed: &[PackSummary]) -> Pick {
    let usable = |p: &&PackSummary| p.load_error.is_none();
    let wanted = selection.trim();
    if !wanted.is_empty() {
        let found = installed
            .iter()
            .find(|p| p.friendly_name == wanted || p.name == wanted)
            .or_else(|| {
                installed.iter().find(|p| {
                    p.friendly_name.eq_ignore_ascii_case(wanted)
                        || p.name.eq_ignore_ascii_case(wanted)
                })
            });
        if let Some(p) = found {
            return Pick {
                pack: Some(p.clone()),
                requested_missing: false,
            };
        }
    }
    Pick {
        pack: installed.iter().find(usable).or(installed.first()).cloned(),
        requested_missing: !wanted.is_empty(),
    }
}

/// Runs the server for `selection` and returns the process exit code.
pub fn run(engine: &Engine, selection: &str) -> i32 {
    engine.block_on(engine.reload_installed_packs(true));
    let pick = pick(selection, &engine.installed_packs());
    if pick.requested_missing {
        mmcl_log::error_silent(&format!(
            "{} {}",
            selection.trim(),
            mmcl_i18n::t!("PACK_NOT_INSTALLED_WILL_DEFAULT_TO_FIRST_TEXT")
        ));
    }
    let Some(pack) = pick.pack else {
        mmcl_log::error_silent(&mmcl_i18n::t!(
            "NO_MOD_PACKS_INSTALLED_CANT_LAUNCH_SERVER_TEXT"
        ));
        return 1;
    };
    match engine.block_on(engine.run_server(&pack.id)) {
        Ok(code) => code,
        Err(e) => {
            mmcl_log::error_silent(&e.message());
            1
        }
    }
}

#[cfg(test)]
pub(crate) mod tests_support {
    use super::*;

    /// A pack card for tests.
    pub fn pack(name: &str, friendly: &str, broken: bool) -> PackSummary {
        PackSummary {
            id: mmcl_engine::PackId::new(format!("name:{name}")),
            name: name.to_string(),
            friendly_name: friendly.to_string(),
            version: "1".into(),
            installed_version: None,
            installed: true,
            update_available: false,
            is_vanilla: false,
            mod_loader: None,
            minecraft_version: None,
            unstable: false,
            load_state: if broken {
                mmcl_engine::PackLoadState::Failed
            } else {
                mmcl_engine::PackLoadState::Loaded
            },
            load_error: broken.then(|| "failed".to_string()),
            manifest_url: None,
            logo_url: None,
            logo_count: 0,
            background_url: None,
            background_count: 0,
            min_ram_gb: 0.0,
            last_played_ms: 0,
            total_play_time_ms: 0,
            launch_count: 0,
            never_played: true,
            unread_news: 0,
            active: false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::tests_support::pack;
    use super::*;

    #[test]
    fn the_requested_pack_wins() {
        let packs = [pack("a", "Alpha", false), pack("b", "Beta", false)];
        let p = pick("Beta", &packs);
        assert_eq!(p.pack.unwrap().name, "b");
        assert!(!p.requested_missing);
        assert_eq!(pick("BETA", &packs).pack.unwrap().name, "b");
        assert_eq!(pick("b", &packs).pack.unwrap().name, "b");
    }

    #[test]
    fn a_missing_name_falls_back_to_the_first_usable_pack() {
        let packs = [pack("a", "Alpha", true), pack("b", "Beta", false)];
        let p = pick("Gamma", &packs);
        assert!(p.requested_missing);
        assert_eq!(p.pack.unwrap().name, "b");
        let p = pick("", &packs);
        assert!(!p.requested_missing);
        assert_eq!(p.pack.unwrap().name, "b");
    }

    #[test]
    fn no_packs_means_no_server() {
        let p = pick("x", &[]);
        assert!(p.pack.is_none());
        assert!(p.requested_missing);
    }
}
