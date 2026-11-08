// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Pack actions shared by Home, Browse and the detail modal: play (`startPlay`), the pack
//! context menu (`buildPackContextMenu`), uninstall with confirmation
//! (`confirmAndUninstallModpack`), installs, zip export and the official launcher export.

use std::path::{Path, PathBuf};
use std::rc::Rc;

use gpui::{App, AsyncApp, ClipboardItem, Window};
use gpui_component::menu::{PopupMenu, PopupMenuItem};
#[cfg(test)]
use mmcl_engine::PackId;
use mmcl_engine::{LoaderVersionView, PackSummary};
use mmcl_i18n::{t, tf};
use mmcl_ui_kit::notify;
use mmcl_ui_kit::{ScreenId, ScreenRequest};

use super::backend::{OfficialOutcome, PackExtras, VerifyOutcome, backend};
use super::home_logic::{display_name, total_play_time};
use super::hooks;
use crate::dialogs::{self, Answer, Question};

/// Reports a status line while an action runs (`None` hides it).
pub type StatusSink = Rc<dyn Fn(Option<String>, &mut App)>;
/// Runs after an action changed the library (rebuild).
pub type Done = Rc<dyn Fn(&mut App)>;

fn cancel() -> String {
    t!("dialog.button.cancel").into_owned()
}

// ---------------------------------------------------------------------------------------------
// Play

/// Plays a pack (`startPlay`): a running pack shows its session; otherwise records it as the
/// last pack and launches (behind the launch gate).
pub fn start_play(cx: &mut App, pack: &PackSummary) {
    let b = backend(cx);
    let h = hooks::read(cx);
    if let Some(session) = b.active_session_for_pack(&pack.id) {
        if let Some(f) = h.show_session {
            f(session, cx);
        }
        return;
    }
    if !crate::shell::may_leave_for_launch(cx) {
        return;
    }
    b.set_last_mod_pack(&pack.name);
    if let Some(f) = h.play {
        f(pack.id.clone(), cx);
        return;
    }
    let fut = b.launch(&pack.id);
    cx.spawn(async move |cx| {
        if let Err(message) = fut.await {
            dialogs::error(cx, &message, None).await;
        }
    })
    .detach();
}

// ---------------------------------------------------------------------------------------------
// Uninstall / install

/// Uninstalls a modpack after asking (`confirmAndUninstallModpack`): refused with a warning
/// while it runs; "delete files" / "keep files" / cancel.
pub fn confirm_and_uninstall_modpack(
    cx: &mut App,
    pack: &PackSummary,
    status: Option<StatusSink>,
    on_complete: Option<Done>,
) {
    let b = backend(cx);
    let name = display_name(pack);
    if b.active_session_for_pack(&pack.id).is_some() {
        notify::warn(
            &t!("notification.pack.running.title"),
            &tf!("notification.pack.running.uninstall", name.as_str()),
        );
        return;
    }
    let id = pack.id.clone();
    cx.spawn(async move |cx| {
        let q = Question::new(
            t!("dialog.uninstall.modpack.title"),
            tf!("dialog.uninstall.modpack.header", name.as_str()),
            t!("dialog.uninstall.modpack.body"),
            t!("dialog.uninstall.button.deleteFiles"),
            t!("dialog.uninstall.button.keepFiles"),
        );
        let delete = match dialogs::ask(cx, &q, None).await {
            Answer::Primary => true,
            Answer::Secondary => false,
            Answer::Closed => return,
        };
        set_status(
            cx,
            &status,
            Some(tf!("library.status.removing", name.as_str())),
        );
        let result = cx.update(|cx| backend(cx).uninstall(&id, delete)).await;
        set_status(cx, &status, None);
        match result {
            Ok(()) => {
                if let Some(done) = on_complete {
                    cx.update(|cx| done(cx));
                }
            }
            Err(message) => dialogs::error(cx, &message, None).await,
        }
    })
    .detach();
}

fn set_status(cx: &mut AsyncApp, status: &Option<StatusSink>, text: Option<String>) {
    if let Some(s) = status {
        let s = s.clone();
        cx.update(|cx| s(text, cx));
    }
}

/// Installs an available modpack.
pub fn install_available(cx: &mut App, pack: &PackSummary, status: StatusSink, done: Done) {
    let friendly = pack.friendly_name.clone();
    if friendly.trim().is_empty() {
        return;
    }
    let name = display_name(pack);
    status(Some(tf!("library.status.installing", name.as_str())), cx);
    let fut = backend(cx).install_available(&friendly);
    cx.spawn(async move |cx| {
        let r = fut.await;
        cx.update(|cx| {
            status(None, cx);
            done(cx);
        });
        match r {
            Ok(_) => notify::success(
                &t!("notification.install.modpackSuccess.title"),
                &tf!("notification.install.modpackSuccess.body", name.as_str()),
            ),
            Err(e) => install_failed(&name, &e),
        }
    })
    .detach();
}

fn install_failed(label: &str, message: &str) {
    notify::error(
        &t!("notification.install.failed.title"),
        &tf!("notification.install.failed.body", label, message),
    );
}

/// Adds a vanilla version.
pub fn install_vanilla(cx: &mut App, id: &str, status: StatusSink, done: Done) {
    if backend(cx).installed_vanilla_ids().iter().any(|v| v == id) {
        return;
    }
    let id = id.to_string();
    status(Some(tf!("library.status.installingMc", id.as_str())), cx);
    let fut = backend(cx).install_vanilla(&id);
    cx.spawn(async move |cx| {
        let r = fut.await;
        cx.update(|cx| {
            status(None, cx);
            done(cx);
        });
        match r {
            Ok(()) => notify::success(
                &t!("notification.install.vanillaSuccess.title"),
                &tf!("notification.install.vanillaSuccess.body", id.as_str()),
            ),
            Err(e) => install_failed(&tf!("library.entry.minecraftVersion", id.as_str()), &e),
        }
    })
    .detach();
}

/// Removes a vanilla version after asking.
pub fn uninstall_vanilla(cx: &mut App, id: &str, status: StatusSink, done: Done) {
    let id = id.to_string();
    cx.spawn(async move |cx| {
        let q = Question::new(
            t!("dialog.uninstall.vanilla.title"),
            tf!("dialog.uninstall.vanilla.header", id.as_str()),
            t!("dialog.uninstall.modpack.body"),
            t!("dialog.uninstall.button.deleteFiles"),
            t!("dialog.uninstall.button.keepFiles"),
        );
        let delete = match dialogs::ask(cx, &q, None).await {
            Answer::Primary => true,
            Answer::Secondary => false,
            Answer::Closed => return,
        };
        let s = status.clone();
        let fut = cx.update(|cx| {
            s(Some(tf!("library.status.removingMc", id.as_str())), cx);
            backend(cx).uninstall_vanilla(&id, delete)
        });
        let r = fut.await;
        cx.update(|cx| {
            status(None, cx);
            done(cx);
        });
        if let Err(e) = r {
            dialogs::error(cx, &e, None).await;
        }
    })
    .detach();
}

/// Installs a loader version as an empty pack.
pub fn install_loader(
    cx: &mut App,
    v: &LoaderVersionView,
    label: &str,
    status: StatusSink,
    done: Done,
) {
    if v.installed {
        return;
    }
    let label = label.to_string();
    status(
        Some(tf!("library.status.installingLabel", label.as_str())),
        cx,
    );
    let fut = backend(cx).install_loader(v);
    cx.spawn(async move |cx| {
        let r = fut.await;
        cx.update(|cx| {
            status(None, cx);
            done(cx);
        });
        match r {
            Ok(()) => notify::success(
                &t!("notification.install.loaderSuccess.title"),
                &tf!("notification.install.loaderSuccess.body", label.as_str()),
            ),
            Err(e) => install_failed(&label, &e),
        }
    })
    .detach();
}

// ---------------------------------------------------------------------------------------------
// Context menu

/// The folders the context menu opens.
pub const PACK_FOLDERS: [(&str, Option<&str>); 6] = [
    ("main.contextMenu.openInstall", None),
    ("main.contextMenu.openScreenshots", Some("screenshots")),
    ("main.contextMenu.openResourcePacks", Some("resourcepacks")),
    ("main.contextMenu.openShaderPacks", Some("shaderpacks")),
    ("main.contextMenu.openMods", Some("mods")),
    ("main.contextMenu.openConfig", Some("config")),
];

/// Creates `dir` if missing, then opens it (failure: a silent warning).
pub fn open_pack_folder(cx: &App, dir: &Path) {
    if let Err(e) = std::fs::create_dir_all(dir) {
        tracing::warn!(
            "{}",
            tf!(
                "log.modpack.openFolderFailed",
                dir.display().to_string(),
                e.to_string()
            )
        );
        return;
    }
    mmcl_ui_kit::open_folder(cx, dir);
}

/// The invite link of a pack (`buildInviteLinkFromPack`).
pub fn invite_link(pack: &PackSummary) -> Option<String> {
    mmcl_discord::activity::build_invite_link_from_pack(
        pack.id.vanilla_version(),
        pack.manifest_url.as_deref(),
    )
}

struct ShortcutTarget {
    name: String,
    logo: Option<PathBuf>,
}

impl mmcl_platform::shortcut::ShortcutPack for ShortcutTarget {
    fn pack_name(&self) -> &str {
        &self.name
    }
    fn logo_path(&self) -> Option<PathBuf> {
        self.logo.clone()
    }
}

fn menu_item(label: impl Into<gpui::SharedString>) -> PopupMenuItem {
    PopupMenuItem::new(label)
}

/// The shortcut icon of a pack: its first cached logo, else the summary's cached primary logo.
pub fn shortcut_logo(cached: Vec<PathBuf>, pack: &PackSummary) -> Option<PathBuf> {
    cached
        .into_iter()
        .next()
        .or_else(|| pack.logo_path.clone().filter(|p| p.is_file()))
}

/// Adds the pack context menu's items to `menu` (`buildPackContextMenu`), rebuilt per open from
/// the pack's current state. `status` shows "Removing <pack>..." on the calling screen while an
/// uninstall runs; `done` runs after it.
pub fn build_pack_context_menu(
    mut menu: PopupMenu,
    pack: &PackSummary,
    extras: Option<&PackExtras>,
    status: Option<StatusSink>,
    done: Done,
    cx: &App,
) -> PopupMenu {
    let root = extras.and_then(|e| e.root.clone());
    menu = menu
        .item(
            menu_item(tf!(
                "main.contextMenu.playStats",
                total_play_time(pack.total_play_time_ms),
                pack.launch_count
            ))
            .disabled(true),
        )
        .separator();
    for (i, (key, sub)) in PACK_FOLDERS.iter().enumerate() {
        if i == 1 || i == 4 {
            menu = menu.separator();
        }
        let dir = root.as_ref().map(|r| match sub {
            Some(s) => r.join(s),
            None => r.clone(),
        });
        menu = menu.item(
            menu_item(t!(key).into_owned())
                .disabled(dir.is_none())
                .on_click(move |_, _, cx| {
                    if let Some(d) = &dir {
                        open_pack_folder(cx, d);
                    }
                }),
        );
    }
    menu = menu.separator();
    {
        let target_name = pack.name.clone();
        let logo = shortcut_logo(backend(cx).pack_images(&pack.id).logos, pack);
        menu = menu.item(
            menu_item(t!("main.contextMenu.createShortcut").into_owned()).on_click(
                move |_, _, cx| {
                    let target = ShortcutTarget {
                        name: target_name.clone(),
                        logo: logo.clone(),
                    };
                    match mmcl_platform::shortcut::create_shortcut(&target) {
                        Ok(_) => notify::success(
                            &t!("dialog.shortcut.created.title"),
                            &tf!("dialog.shortcut.created.body", target_name.as_str()),
                        ),
                        Err(e) => {
                            let body = tf!("dialog.shortcut.failed.body", e.localized());
                            cx.spawn(async move |cx| dialogs::error(cx, &body, None).await)
                                .detach();
                        }
                    }
                },
            ),
        );
    }
    let invite = invite_link(pack);
    menu = menu.item(
        menu_item(t!("main.contextMenu.copyInvite").into_owned())
            .disabled(invite.is_none())
            .on_click(move |_, _, cx| match &invite {
                Some(link) => {
                    cx.write_to_clipboard(ClipboardItem::new_string(link.clone()));
                    notify::success(
                        &t!("notification.invite.copied.title"),
                        &t!("notification.invite.copied.body"),
                    );
                }
                None => notify::warn(
                    &t!("detailModal.invite.unavailable.title"),
                    &t!("detailModal.invite.unavailable.body"),
                ),
            }),
    );
    menu = menu.separator();
    let id = pack.id.clone();
    menu = menu.item(
        menu_item(t!("main.contextMenu.editPack").into_owned()).on_click(move |_, _, cx| {
            crate::shell::navigate_gated(
                cx,
                ScreenRequest::with_payload(ScreenId::EDITOR, id.clone()),
            );
        }),
    );
    let p = pack.clone();
    menu = menu.item(
        menu_item(t!("main.contextMenu.exportZip").into_owned())
            .disabled(root.is_none())
            .on_click(move |_, window, cx| export_zip(window, cx, &p)),
    );
    let p = pack.clone();
    menu = menu.item(
        menu_item(t!("officialExport.menuItem").into_owned())
            .disabled(root.is_none())
            .on_click(move |_, _, cx| official_export(cx, &p)),
    );
    if backend(cx).official_has_export(&pack.id) {
        let p = pack.clone();
        let root = root.clone();
        menu = menu.item(
            menu_item(t!("officialExport.remove.menuItem").into_owned())
                .on_click(move |_, _, cx| official_remove(cx, &p, root.clone())),
        );
    }
    menu = menu.separator();
    let p = pack.clone();
    menu.item(
        menu_item(t!("main.contextMenu.uninstall").into_owned()).on_click(move |_, _, cx| {
            confirm_and_uninstall_modpack(cx, &p, status.clone(), Some(done.clone()));
        }),
    )
}

// ---------------------------------------------------------------------------------------------
// Export

/// A user's home folder (the initial folder of save dialogs).
pub fn home_dir() -> PathBuf {
    #[allow(deprecated)]
    std::env::home_dir().unwrap_or_else(|| PathBuf::from("."))
}

/// Adds `ext` when the chosen file has no extension (GPUI save dialogs take no filter).
pub fn with_extension(path: PathBuf, ext: &str) -> PathBuf {
    if path.extension().is_some() {
        path
    } else {
        path.with_extension(ext)
    }
}

/// The suggested export file name: `<packName or modpack>-<epochMillis>.zip`.
pub fn export_file_name(pack_name: &str, now_ms: i64) -> String {
    let base = if pack_name.trim().is_empty() {
        "modpack"
    } else {
        pack_name
    };
    format!("{base}-{now_ms}.zip")
}

/// Export as ZIP: pick a file, verify modded packs first, then zip.
pub fn export_zip(_window: &mut Window, cx: &mut App, pack: &PackSummary) {
    let name = export_file_name(&pack.name, chrono::Utc::now().timestamp_millis());
    let rx = cx.prompt_for_new_path(&home_dir(), Some(&name));
    let pack = pack.clone();
    cx.spawn(async move |cx| {
        let Ok(Ok(Some(dest))) = rx.await else {
            return;
        };
        let dest = with_extension(dest, "zip");
        if !pack.is_vanilla {
            notify::info(
                &t!("detailModal.exportPack.label"),
                &t!("detailModal.exportPack.verifying"),
            );
            let v = cx.update(|cx| backend(cx).verify(&pack.id)).await;
            if let VerifyOutcome::Failed(message) = v {
                notify::error(
                    &t!("detailModal.exportPack.failed"),
                    &tf!("detailModal.exportPack.failedBody", message),
                );
                return;
            }
        }
        let shown = dest.display().to_string();
        let r = cx.update(|cx| backend(cx).export_zip(&pack.id, dest)).await;
        match r {
            Ok(()) => notify::success(
                &t!("detailModal.export.success.title"),
                &tf!("detailModal.export.success.body", shown),
            ),
            Err(e) => notify::error(
                &t!("detailModal.exportPack.failed"),
                &tf!("detailModal.exportPack.failedBody", e),
            ),
        }
    })
    .detach();
}

/// Add to the official Minecraft Launcher (the flow in gui-shell 3.5).
pub fn official_export(cx: &mut App, pack: &PackSummary) {
    let pack = pack.clone();
    cx.spawn(async move |cx| {
        let status = match cx.update(|cx| backend(cx).official_status(&pack.id)).await {
            Ok(s) => s,
            Err(e) => {
                notify::error(
                    &t!("officialExport.failed.title"),
                    &tf!("officialExport.failed.body", e),
                );
                return;
            }
        };
        if !status.available {
            dialogs::error(
                cx,
                &format!(
                    "{}\n{}",
                    t!("officialExport.notInstalled.title"),
                    tf!(
                        "officialExport.notInstalled.body",
                        status.dot_minecraft.display().to_string()
                    )
                ),
                None,
            )
            .await;
            return;
        }
        let name = pack.name.clone();
        let vid = status.version_id.clone();
        let loader = if pack.is_vanilla {
            "Minecraft".to_string()
        } else {
            pack.mod_loader
                .as_deref()
                .map(super::browse_logic::pretty_loader)
                .unwrap_or_else(|| t!("main.officialExport.loaderFallback").into_owned())
        };
        let blurb = if pack.is_vanilla {
            tf!(
                "officialExport.confirm.body.vanilla",
                name.as_str(),
                vid.as_str()
            )
        } else if status.loader_installed {
            t!("officialExport.confirm.body.modded.loaderInstalled").into_owned()
        } else {
            tf!(
                "officialExport.confirm.body.modded.loaderMissing",
                loader.as_str(),
                vid.as_str()
            )
        };
        let q = Question::new(
            t!("officialExport.confirm.title"),
            tf!("officialExport.confirm.header", name.as_str()),
            tf!(
                "officialExport.confirm.body",
                name.as_str(),
                vid.as_str(),
                blurb
            ),
            t!("officialExport.confirm.button.export"),
            cancel(),
        );
        if dialogs::ask_multiline(cx, &q, None).await != Answer::Primary {
            return;
        }
        let mut install_loader = false;
        if !pack.is_vanilla && !status.loader_installed {
            let q = Question::new(
                t!("officialExport.installLoader.title"),
                tf!("officialExport.installLoader.header", loader.as_str()),
                tf!(
                    "officialExport.installLoader.body",
                    loader.as_str(),
                    vid.as_str()
                ),
                t!("officialExport.installLoader.button.installAndAdd"),
                t!("officialExport.installLoader.button.addOnly"),
            );
            install_loader = match dialogs::ask(cx, &q, None).await {
                Answer::Primary => true,
                Answer::Secondary => false,
                Answer::Closed => return,
            };
        }
        // The engine runs Java's order in one call: verify (modded), install the loader when
        // asked, then write the profile.
        if !pack.is_vanilla {
            notify::info(&t!("officialExport.verifying"), &name);
        }
        if install_loader {
            notify::info(
                &tf!("officialExport.installLoader.inProgress", loader.as_str()),
                &name,
            );
        }
        notify::info(&t!("officialExport.inProgress"), &name);
        match cx
            .update(|cx| backend(cx).official_export(&pack.id, install_loader))
            .await
        {
            Ok(OfficialOutcome::Exported(profile)) => notify::success(
                &t!("officialExport.success.title"),
                &tf!("officialExport.success.body", profile),
            ),
            Ok(OfficialOutcome::LoaderMissing(version)) => {
                // A failed loader install lands here too (the engine logs its message).
                let shown = if version.trim().is_empty() {
                    vid.clone()
                } else {
                    version
                };
                notify::warn(
                    &t!("officialExport.successButLoaderMissing.title"),
                    &tf!(
                        "officialExport.successButLoaderMissing.body",
                        shown.as_str()
                    ),
                );
            }
            Err(e) => notify::error(
                &t!("officialExport.failed.title"),
                &tf!("officialExport.failed.body", e),
            ),
        }
    })
    .detach();
}

/// Remove from the official Minecraft Launcher.
/// The confirmation shows the export's game folder inside the Mica exports folder
/// (`computeExportGameDir`); `root` (the pack's own folder) is only the fallback.
pub fn official_remove(cx: &mut App, pack: &PackSummary, root: Option<PathBuf>) {
    let pack = pack.clone();
    let game_dir = backend(cx).official_export_dir(&pack).or(root);
    cx.spawn(async move |cx| {
        let name = pack.name.clone();
        let dir = game_dir
            .map(|r| r.display().to_string())
            .unwrap_or_default();
        let q = Question::new(
            t!("officialExport.remove.confirm.title"),
            tf!("officialExport.remove.confirm.header", name.as_str()),
            tf!("officialExport.remove.confirm.body", name.as_str(), dir),
            t!("officialExport.remove.confirm.button.remove"),
            cancel(),
        );
        if dialogs::ask_multiline(cx, &q, None).await != Answer::Primary {
            return;
        }
        match cx.update(|cx| backend(cx).official_remove(&pack.id)).await {
            Ok(profile) => notify::success(
                &t!("officialExport.remove.success.title"),
                &tf!("officialExport.remove.success.body", profile),
            ),
            Err(e) => notify::error(
                &t!("officialExport.remove.failed.title"),
                &tf!("officialExport.remove.failed.body", e),
            ),
        }
    })
    .detach();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn export_names_and_extensions() {
        assert_eq!(export_file_name("Pack", 5), "Pack-5.zip");
        assert_eq!(export_file_name(" ", 5), "modpack-5.zip");
        assert_eq!(
            with_extension(PathBuf::from("a"), "zip"),
            PathBuf::from("a.zip")
        );
        assert_eq!(
            with_extension(PathBuf::from("a.json"), "zip"),
            PathBuf::from("a.json")
        );
    }

    #[test]
    fn shortcut_logo_prefers_the_cached_list() {
        let mut p = super::super::home_logic::vanilla_summary("1.20.1");
        p.logo_path = Some(PathBuf::from("definitely/missing/logo.png"));
        assert_eq!(
            shortcut_logo(vec![PathBuf::from("a.png")], &p),
            Some(PathBuf::from("a.png"))
        );
        // A summary path that is not on disk is not used.
        assert_eq!(shortcut_logo(Vec::new(), &p), None);
        let file = std::env::temp_dir().join("mmcl-ui-shortcut-logo-test.png");
        std::fs::write(&file, b"x").unwrap();
        p.logo_path = Some(file.clone());
        assert_eq!(shortcut_logo(Vec::new(), &p), Some(file.clone()));
        let _ = std::fs::remove_file(file);
    }

    #[test]
    fn invite_links_prefer_vanilla() {
        let v = super::super::home_logic::vanilla_summary("1.20.1");
        assert!(invite_link(&v).unwrap().contains("vanilla=1.20.1"));
        let mut p = v.clone();
        p.id = PackId::new("https://x/p.json");
        p.manifest_url = None;
        assert!(invite_link(&p).is_none());
    }
}
