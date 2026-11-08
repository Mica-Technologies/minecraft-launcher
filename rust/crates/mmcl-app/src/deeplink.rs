// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The `mmcl://` dispatcher (utilities-platform §6.4, Java `LauncherUriHandler`). Parsing and
//! the install-URL classifier live in `mmcl_util::deeplink`; this runs the actions on the GUI.
//!
//! Delivery paths: argv at cold start (queued until the main screen shows), the
//! single-instance IPC payload, and GPUI's `on_open_urls` (macOS).

use gpui::{App, AsyncApp};
use mmcl_engine::{Engine, PackId, PackSummary};
use mmcl_i18n::{t, tf};
use mmcl_ui::Question;
use mmcl_ui_kit::dialogs::Answer;
use mmcl_ui_kit::notify;
use mmcl_util::deeplink::{self, DeepLink, InstallUrlVerdict};

/// Handles one launcher URI (steps 1 to 6 of `handle`), then runs the action on the GUI.
pub fn handle(uri: &str, engine: &Engine, cx: &mut App) {
    if !deeplink::is_launcher_uri(Some(uri)) {
        return;
    }
    if !engine.settings().uri_handler_enabled {
        tracing::info!("{}", t!("log.uriHandler.handlerDisabled"));
        return;
    }
    let link = match deeplink::parse(uri) {
        Ok(link) => link,
        Err(e) => {
            if e.log_key().is_some() {
                tracing::warn!("{}", e.localized());
            }
            return;
        }
    };
    let params = deeplink::parse_raw(uri)
        .map(|r| {
            let mut keys: Vec<String> = r.params.into_keys().collect();
            keys.sort();
            keys.join(",")
        })
        .unwrap_or_default();
    tracing::info!(
        "{}",
        tf!("log.uriHandler.dispatch", link.action(), params.as_str())
    );
    let engine = engine.clone();
    cx.spawn(async move |cx| dispatch(link, engine, cx).await)
        .detach();
}

async fn dispatch(link: DeepLink, engine: Engine, cx: &mut AsyncApp) {
    match link {
        DeepLink::Open => cx.update(mmcl_ui::shell::request_focus),
        DeepLink::Add { url } => add(url, engine, cx).await,
        DeepLink::Play { name } => play(name, engine, cx).await,
        DeepLink::JoinUrl { url } => join_url(url, engine, cx).await,
        DeepLink::JoinVanilla { version } => join_vanilla(version, engine, cx).await,
    }
}

/// An installed pack by pack name, then by friendly name, ignoring case.
pub fn find_pack(name: &str, installed: &[PackSummary]) -> Option<PackSummary> {
    installed
        .iter()
        .find(|p| p.name == name)
        .or_else(|| installed.iter().find(|p| p.name.eq_ignore_ascii_case(name)))
        .or_else(|| {
            installed
                .iter()
                .find(|p| p.friendly_name.eq_ignore_ascii_case(name))
        })
        .cloned()
}

/// The installed pack whose manifest is at `url`.
pub fn find_by_url(url: &str, installed: &[PackSummary]) -> Option<PackSummary> {
    installed
        .iter()
        .find(|p| p.manifest_url.as_deref() == Some(url))
        .cloned()
}

fn display_name(p: &PackSummary) -> String {
    if p.friendly_name.trim().is_empty() {
        p.name.clone()
    } else {
        p.friendly_name.clone()
    }
}

async fn add(url: String, engine: Engine, cx: &mut AsyncApp) {
    if !confirm_install_url(&url, "dialog.uri.confirmInstall.headerAdd", cx).await {
        return;
    }
    let e = engine.clone();
    let u = url.clone();
    match engine
        .spawn(async move { e.install_pack_by_url(&u).await })
        .await
    {
        Ok(Ok(_)) => notify::success(
            &t!("notification.uri.modpackAdded.title"),
            &t!("notification.uri.modpackAdded.body"),
        ),
        Ok(Err(err)) => {
            tracing::warn!("{}", tf!("log.uriHandler.addInstallFailed", err.message()));
            notify::error(
                &t!("notification.uri.modpackAddFailed.title"),
                &t!("notification.uri.modpackAddFailed.body"),
            );
        }
        Err(_) => {}
    }
}

async fn play(name: String, engine: Engine, cx: &mut AsyncApp) {
    let Some(pack) = find_pack(&name, &engine.installed_packs()) else {
        notify::warn(
            &t!("notification.uri.modpackNotInstalled.title"),
            &tf!("notification.uri.modpackNotInstalled.body", name.as_str()),
        );
        return;
    };
    if !ready_to_launch(&engine, cx) {
        return;
    }
    if !confirm_launch(&display_name(&pack), cx).await {
        return;
    }
    cx.update(|cx| mmcl_ui::screens::home_browse::start_play(cx, &pack));
}

async fn join_url(url: String, engine: Engine, cx: &mut AsyncApp) {
    if !ready_to_launch(&engine, cx) {
        return;
    }
    if let Some(pack) = find_by_url(&url, &engine.installed_packs()) {
        if confirm_launch(&display_name(&pack), cx).await {
            cx.update(|cx| mmcl_ui::screens::home_browse::start_play(cx, &pack));
        }
        return;
    }
    if !confirm_install_url(&url, "dialog.uri.confirmInstall.headerJoin", cx).await {
        return;
    }
    let e = engine.clone();
    let u = url.clone();
    let id = match engine
        .spawn(async move { e.install_pack_by_url(&u).await })
        .await
    {
        Ok(Ok(id)) => id,
        Ok(Err(err)) => {
            tracing::warn!("{}", tf!("log.uriHandler.joinInstallFailed", err.message()));
            notify::error(
                &t!("notification.uri.discordJoinFailed.title"),
                &t!("notification.uri.discordJoinModpackFailed.body"),
            );
            return;
        }
        Err(_) => return,
    };
    notify::info(
        &t!("notification.uri.discordJoin.title"),
        &t!("notification.uri.discordJoinModpack.body"),
    );
    match engine
        .pack(&id)
        .or_else(|| find_by_url(&url, &engine.installed_packs()))
    {
        Some(pack) => cx.update(|cx| mmcl_ui::screens::home_browse::start_play(cx, &pack)),
        None => tracing::warn!("{}", t!("log.uriHandler.joinPackNotFound")),
    }
}

async fn join_vanilla(version: String, engine: Engine, cx: &mut AsyncApp) {
    if !ready_to_launch(&engine, cx) {
        return;
    }
    let label = tf!("dialog.uri.confirmLaunch.vanillaName", version.as_str());
    if !confirm_launch(&label, cx).await {
        return;
    }
    let id = PackId::vanilla(&version);
    let pack = match engine.pack(&id) {
        Some(p) => p,
        None => {
            let e = engine.clone();
            let v = version.clone();
            match engine
                .spawn(async move { e.install_vanilla(&v).await })
                .await
            {
                Ok(Ok(id)) => {
                    notify::info(
                        &t!("notification.uri.discordJoin.title"),
                        &tf!("notification.uri.discordJoinVanilla.body", version.as_str()),
                    );
                    match engine.pack(&id) {
                        Some(p) => p,
                        None => {
                            tracing::warn!("{}", t!("log.uriHandler.joinPackNotFound"));
                            return;
                        }
                    }
                }
                Ok(Err(err)) => {
                    tracing::warn!(
                        "{}",
                        tf!(
                            "log.uriHandler.joinVanillaFailed",
                            version.as_str(),
                            err.message()
                        )
                    );
                    notify::error(
                        &t!("notification.uri.discordJoinFailed.title"),
                        &tf!(
                            "notification.uri.discordJoinVanillaFailed.body",
                            version.as_str()
                        ),
                    );
                    return;
                }
                Err(_) => return,
            }
        }
    };
    cx.update(|cx| mmcl_ui::screens::home_browse::start_play(cx, &pack));
}

/// `readyToLaunchViaDeepLink`: refuses while a game runs, then the navigation gate.
fn ready_to_launch(engine: &Engine, cx: &mut AsyncApp) -> bool {
    if engine.has_active_sessions() {
        tracing::info!("{}", t!("log.uriHandler.refuseGameRunning"));
        notify::warn(
            &t!("notification.uri.gameRunning.title"),
            &t!("notification.uri.gameRunning.body"),
        );
        cx.update(mmcl_ui::shell::request_focus);
        return false;
    }
    let ok = cx.update(|cx| mmcl_ui::shell::may_leave_for_launch(cx));
    if !ok {
        tracing::info!("{}", t!("log.uriHandler.launchCancelledUnsaved"));
    }
    ok
}

/// Always asks: web pages and other local processes can send these links.
async fn confirm_launch(name: &str, cx: &mut AsyncApp) -> bool {
    let name = if name.trim().is_empty() {
        t!("dialog.uri.confirmLaunch.unknownPack").into_owned()
    } else {
        name.to_string()
    };
    cx.update(mmcl_ui::shell::request_focus);
    let q = Question::new(
        t!("dialog.uri.confirmLaunch.title"),
        tf!("dialog.uri.confirmLaunch.header", name.as_str()),
        t!("dialog.uri.confirmLaunch.body"),
        t!("dialog.uri.confirmLaunch.button.launch"),
        t!("dialog.button.cancel"),
    );
    let ok = mmcl_ui::dialogs::ask(cx, &q, None).await == Answer::Primary;
    if !ok {
        tracing::info!(
            "{}",
            tf!("log.uriHandler.userDeclinedLaunch", name.as_str())
        );
    }
    ok
}

/// `confirmInstallUrl`: trusted hosts pass, unsafe links are refused with a toast, anything
/// else asks.
async fn confirm_install_url(url: &str, header_key: &str, cx: &mut AsyncApp) -> bool {
    match deeplink::classify_install_url(Some(url)) {
        InstallUrlVerdict::AcceptTrusted => true,
        InstallUrlVerdict::Reject => {
            tracing::warn!("{}", tf!("log.uriHandler.refuseInstallUrl", url));
            notify::warn(
                &t!("notification.uri.unsafeLink.title"),
                &t!("notification.uri.unsafeLink.body"),
            );
            false
        }
        InstallUrlVerdict::RequireConfirmation => {
            cx.update(mmcl_ui::shell::request_focus);
            let q = Question::new(
                t!("dialog.uri.confirmInstall.title"),
                mmcl_i18n::get(header_key),
                tf!(
                    "dialog.uri.confirmInstall.body",
                    deeplink::host_of(url).as_str()
                ),
                t!("dialog.uri.confirmInstall.button.install"),
                t!("dialog.button.cancel"),
            );
            let ok = mmcl_ui::dialogs::ask(cx, &q, None).await == Answer::Primary;
            if !ok {
                tracing::info!("{}", tf!("log.uriHandler.userDeclinedInstall", url));
            }
            ok
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pack(name: &str, friendly: &str, url: Option<&str>) -> PackSummary {
        let mut p = crate::server::tests_support::pack(name, friendly, false);
        p.manifest_url = url.map(str::to_string);
        p
    }

    #[test]
    fn packs_are_found_by_name_then_friendly_name() {
        let packs = [pack("alpha", "The Alpha", None), pack("beta", "Beta", None)];
        assert_eq!(find_pack("alpha", &packs).unwrap().name, "alpha");
        assert_eq!(find_pack("ALPHA", &packs).unwrap().name, "alpha");
        assert_eq!(find_pack("the alpha", &packs).unwrap().name, "alpha");
        assert!(find_pack("gamma", &packs).is_none());
    }

    #[test]
    fn packs_are_found_by_manifest_url() {
        let packs = [
            pack("a", "A", Some("https://x/a.json")),
            pack("b", "B", None),
        ];
        assert_eq!(find_by_url("https://x/a.json", &packs).unwrap().name, "a");
        assert!(find_by_url("https://x/b.json", &packs).is_none());
    }

    #[test]
    fn every_key_used_here_exists() {
        for k in [
            "log.uriHandler.handlerDisabled",
            "log.uriHandler.dispatch",
            "log.uriHandler.addInstallFailed",
            "log.uriHandler.joinInstallFailed",
            "log.uriHandler.joinPackNotFound",
            "log.uriHandler.joinVanillaFailed",
            "log.uriHandler.refuseGameRunning",
            "log.uriHandler.launchCancelledUnsaved",
            "log.uriHandler.userDeclinedLaunch",
            "log.uriHandler.refuseInstallUrl",
            "log.uriHandler.userDeclinedInstall",
            "notification.uri.modpackAdded.title",
            "notification.uri.modpackAdded.body",
            "notification.uri.modpackAddFailed.title",
            "notification.uri.modpackAddFailed.body",
            "notification.uri.modpackNotInstalled.title",
            "notification.uri.modpackNotInstalled.body",
            "notification.uri.discordJoin.title",
            "notification.uri.discordJoinModpack.body",
            "notification.uri.discordJoinFailed.title",
            "notification.uri.discordJoinModpackFailed.body",
            "notification.uri.discordJoinVanilla.body",
            "notification.uri.discordJoinVanillaFailed.body",
            "notification.uri.gameRunning.title",
            "notification.uri.gameRunning.body",
            "notification.uri.unsafeLink.title",
            "notification.uri.unsafeLink.body",
            "dialog.uri.confirmLaunch.title",
            "dialog.uri.confirmLaunch.header",
            "dialog.uri.confirmLaunch.body",
            "dialog.uri.confirmLaunch.button.launch",
            "dialog.uri.confirmLaunch.unknownPack",
            "dialog.uri.confirmLaunch.vanillaName",
            "dialog.uri.confirmInstall.title",
            "dialog.uri.confirmInstall.headerAdd",
            "dialog.uri.confirmInstall.headerJoin",
            "dialog.uri.confirmInstall.body",
            "dialog.uri.confirmInstall.button.install",
            "dialog.button.cancel",
        ] {
            assert!(mmcl_i18n::contains(k), "{k}");
        }
    }
}
