// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The OS-shell actions (`LauncherActions`, parts of `SystemMenuBarManager`): what the tray,
//! the dock menu, the Windows jump list and the macOS menu bar do, with the same toasts as
//! Java when there is nothing to act on.
//!
//! [`run`] is the one entry point for a [`MenuAction`]; the app crate's
//! `PlatformHost::on_menu_action` can forward to it (it runs on the GPUI thread), and the
//! dock-menu actions in [`crate::actions`] call it directly.

use std::path::{Path, PathBuf};

use gpui::App;
use mmcl_engine::{Engine, ImportRequest, PackFolder, PackSummary};
use mmcl_i18n::{t, tf};
use mmcl_platform::MenuAction;
use mmcl_ui_kit::{HelpTopic, ScreenId, ScreenRequest, notify};

use super::controller;
use super::hooks;
use crate::services;

/// The `.mmcjson` file extension (`ModPackConstants.MODPACK_FILE_EXTENSION`).
pub const MMCJSON_EXTENSION: &str = ".mmcjson";

/// The last selected pack (`lastPlayedModpack`): `lastModPackSelected` by pack name, then by
/// friendly name. `None` when nothing was played or it is no longer installed.
pub fn last_pack(engine: &Engine) -> Option<PackSummary> {
    let last = engine.settings().last_mod_pack;
    if last.trim().is_empty() {
        return None;
    }
    engine.find_installed_by_name(&last)
}

/// Runs a tray / dock / jump-list action on the GPUI thread.
pub fn run(action: MenuAction, cx: &mut App) {
    let engine = services::try_engine(cx);
    match action {
        MenuAction::ShowLauncher => controller::request_focus(cx),
        MenuAction::PlayLast => match engine.as_ref().and_then(last_pack) {
            Some(pack) => play(&pack, cx),
            None => notify::warn(
                &t!("notification.shell.noRecentModpackPlay.title"),
                &t!("notification.shell.noRecentModpackPlay.body"),
            ),
        },
        MenuAction::PlayModpack(name) => {
            match engine
                .as_ref()
                .and_then(|e| e.find_installed_by_name(&name))
            {
                Some(pack) => play(&pack, cx),
                None => notify::warn(
                    &t!("notification.uri.modpackNotInstalled.title"),
                    &tf!("notification.uri.modpackNotInstalled.body", name.as_str()),
                ),
            }
        }
        MenuAction::OpenLastModsFolder => {
            let Some(engine) = engine else { return };
            let Some(pack) = last_pack(&engine) else {
                notify::warn(
                    &t!("notification.shell.noRecentModpackMods.title"),
                    &t!("notification.shell.noRecentModpackMods.body"),
                );
                return;
            };
            match engine.pack_folder(&pack.id, PackFolder::Mods) {
                // `open_folder` creates a missing folder first (Java `mkdirs`).
                Ok(folder) => {
                    if let Err(e) = mmcl_platform::open::open_folder(&folder) {
                        tracing::warn!(
                            "{}",
                            tf!("log.launcherActions.openModsFolderFailed", e.to_string())
                        );
                    }
                }
                Err(e) => tracing::warn!(
                    "{}",
                    tf!("log.launcherActions.openModsFolderFailed", e.message())
                ),
            }
        }
        MenuAction::OpenBrowse => navigate(ScreenId::BROWSE, cx),
        MenuAction::OpenSettings => navigate(ScreenId::SETTINGS, cx),
        MenuAction::OpenHelp => hooks::show_help(cx, HelpTopic::GettingStarted),
        MenuAction::Quit => controller::request_quit(cx),
    }
}

/// `playModpack`: refused only on screens that lock navigation (`readyToLaunch`); the launch
/// itself is Home's Play flow (session tab, account choice, last-pack record).
fn play(pack: &PackSummary, cx: &mut App) {
    if !controller::may_leave_for_launch(cx) {
        return;
    }
    controller::request_focus(cx);
    crate::screens::home_browse::start_play(cx, pack);
}

fn navigate(id: ScreenId, cx: &mut App) {
    controller::request_focus(cx);
    controller::navigate_gated(cx, ScreenRequest::new(id));
}

/// Modpacks > Refresh from any screen (`refreshMain`): behind the navigation gate, re-check
/// the announcements and re-fetch the pack list, then go Home.
pub fn refresh_and_go_home(cx: &mut App) {
    let gate = controller::may_navigate_away(cx);
    let engine = services::try_engine(cx);
    cx.spawn(async move |cx| {
        if !gate.await {
            return;
        }
        if let Some(engine) = engine {
            let e = engine.clone();
            let _ = engine
                .spawn(async move {
                    e.refresh_announcements().await;
                    e.refresh_packs().await;
                })
                .await;
        }
        cx.update(|cx| controller::go_to(cx, ScreenRequest::new(ScreenId::HOME)));
    })
    .detach();
}

/// The About dialog's `(title, header, body)` (`showAboutDialog`).
pub fn about_text() -> (String, String, String) {
    let name = mmcl_core::AppIdentity::current().display_name;
    (
        tf!("dialog.about.title", name),
        name.to_string(),
        tf!("dialog.about.body", mmcl_core::build::VERSION),
    )
}

/// The application menu's About item: an information dialog on the launcher window.
pub fn show_about(cx: &mut App) {
    let owner = controller::main_window_handle(cx);
    let (title, header, body) = about_text();
    cx.spawn(async move |cx| {
        mmcl_ui_kit::dialogs::info(cx, &title, &header, &body, owner).await;
    })
    .detach();
}

/// Whether `path` names a `.mmcjson` file (case-insensitive), so a stray file dropped on the
/// dock icon never reaches the importer (`isMmcjsonFile`).
pub fn is_mmcjson_file(path: &Path) -> bool {
    path.file_name()
        .and_then(|n| n.to_str())
        .is_some_and(|n| n.to_lowercase().ends_with(MMCJSON_EXTENSION))
}

/// The local `.mmcjson` file an opened URL names: GPUI delivers files opened from Finder or
/// dropped on the dock icon as `file://` URLs through `on_open_urls`. `None` for anything
/// else (an `mmcl://` deep link, another file type, a remote URL).
pub fn mmcjson_path(url: &str) -> Option<PathBuf> {
    let parsed = url::Url::parse(url).ok()?;
    if parsed.scheme() != "file" {
        return None;
    }
    let path = parsed.to_file_path().ok()?;
    is_mmcjson_file(&path).then_some(path)
}

/// Imports an opened `.mmcjson` file (`dispatchMmcjsonImport`): a success or failure toast,
/// and Home is rebuilt when it is showing (never navigates away from another screen).
pub fn import_mmcjson(path: PathBuf, cx: &mut App) {
    let Some(engine) = services::try_engine(cx) else {
        return;
    };
    let name = path
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let e = engine.clone();
    let task = engine.spawn(async move { e.import(ImportRequest::ManifestFile(path)).await });
    cx.spawn(async move |cx| match task.await {
        Ok(Ok(_)) => {
            notify::success(
                &t!("notification.uri.modpackAdded.title"),
                &t!("notification.uri.modpackAdded.body"),
            );
            cx.update(|cx| {
                if controller::current_screen(cx).is_some_and(|s| s.id(cx) == ScreenId::HOME) {
                    controller::go_to(cx, ScreenRequest::new(ScreenId::HOME));
                }
            });
        }
        Ok(Err(err)) => {
            let message = err.message();
            tracing::error!(
                "{}",
                tf!(
                    "log.menu.mmcjsonImportFailed",
                    name.as_str(),
                    message.as_str()
                )
            );
            notify::error(&t!("notification.uri.modpackAddFailed.title"), &message);
        }
        Err(_) => {
            tracing::error!("{}", tf!("log.menu.mmcjsonImportUnexpected", name.as_str()));
            notify::error(
                &t!("notification.uri.modpackAddFailed.title"),
                &t!("notification.uri.modpackAddFailed.body"),
            );
        }
    })
    .detach();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_local_mmcjson_files_are_imported() {
        let file = if cfg!(windows) {
            "file:///C:/Users/me/Pack.MMCJSON"
        } else {
            "file:///home/me/Pack.MMCJSON"
        };
        let path = mmcjson_path(file).expect("a local .mmcjson file");
        assert!(is_mmcjson_file(&path));
        assert_eq!(mmcjson_path("mmcl://play?name=x"), None);
        assert_eq!(mmcjson_path("https://example.com/p.mmcjson"), None);
        let other = if cfg!(windows) {
            "file:///C:/notes.txt"
        } else {
            "file:///tmp/notes.txt"
        };
        assert_eq!(mmcjson_path(other), None);
        assert!(!is_mmcjson_file(Path::new("mmcjson")));
    }

    #[test]
    fn about_names_the_app_and_version() {
        let (title, header, body) = about_text();
        let name = mmcl_core::AppIdentity::current().display_name;
        assert!(title.contains(name));
        assert_eq!(header, name);
        assert!(body.contains(mmcl_core::build::VERSION));
    }

    #[test]
    fn shell_strings_exist() {
        for k in [
            "notification.shell.noRecentModpackPlay.title",
            "notification.shell.noRecentModpackPlay.body",
            "notification.shell.noRecentModpackMods.title",
            "notification.shell.noRecentModpackMods.body",
            "notification.uri.modpackNotInstalled.title",
            "notification.uri.modpackNotInstalled.body",
            "notification.uri.modpackAdded.title",
            "notification.uri.modpackAdded.body",
            "notification.uri.modpackAddFailed.title",
            "notification.uri.modpackAddFailed.body",
            "log.launcherActions.openModsFolderFailed",
            "log.menu.mmcjsonImportFailed",
            "log.menu.mmcjsonImportUnexpected",
        ] {
            assert!(mmcl_i18n::english(k).is_some(), "{k}");
        }
    }
}
