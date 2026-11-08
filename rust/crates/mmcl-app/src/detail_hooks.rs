// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Fills the pack detail modal's hooks (`dialogs::detail::hooks`) from the engine and the pack
//! runtime: the audit's problem list (`ModPackAuditLog.analyzeProblems`), the pack website
//! (`packURL`), the Modrinth mod-update check (`ModrinthModUpdateChecker`) and applying one
//! update (`ModpackContentBrowser.applyModUpdate`).

use std::path::{Path, PathBuf};

use gpui::App;
use mmcl_engine::Engine;
use mmcl_i18n::{t, tf};
use mmcl_modpack::audit::{self, Problem};
use mmcl_modpack::modrinth_updates;

use crate::dialogs::detail::hooks::{self, ModUpdate, ProblemView, UpdateStatus};

/// The audit problems as detail-modal rows.
pub fn problem_views(problems: &[Problem]) -> Vec<ProblemView> {
    problems
        .iter()
        .map(|p| {
            let detail_key = if p.content_unchanged {
                "detailModal.problems.unchanged"
            } else {
                "detailModal.problems.changed"
            };
            ProblemView {
                title: tf!(
                    "detailModal.problems.redownload",
                    p.file.as_str(),
                    p.consecutive_launches.to_string()
                ),
                detail: t!(detail_key).into_owned(),
            }
        })
        .collect()
}

/// The modal's view of the checker's map, sorted by jar name ignoring case.
pub fn update_views(
    found: std::collections::HashMap<String, modrinth_updates::ModUpdate>,
) -> Vec<ModUpdate> {
    let mut out: Vec<ModUpdate> = found
        .into_iter()
        .map(|(file_name, u)| ModUpdate {
            file_name,
            status: match u.status {
                modrinth_updates::Status::UpdateAvailable => UpdateStatus::UpdateAvailable,
                modrinth_updates::Status::UpToDate => UpdateStatus::UpToDate,
                modrinth_updates::Status::NotOnModrinth => UpdateStatus::NotOnModrinth,
            },
            current_version: u.current_version,
            latest_version: u.latest_version,
            latest_download_url: u.latest_download_url,
        })
        .collect();
    out.sort_by_key(|u| u.file_name.to_lowercase());
    out
}

/// The temp file a jar update downloads into (`.<name>.update.tmp`, as Java).
pub fn update_temp_file(mods_dir: &Path, new_name: &str) -> PathBuf {
    mods_dir.join(format!(".{new_name}.update.tmp"))
}

/// Downloads `url` over the old jar: into a temp file, moved over `<new name>`, then the old
/// jar removed when the version bump renamed it. The old jar stays on any failure.
async fn apply_update(mods_dir: PathBuf, old: String, url: String) -> Result<String, String> {
    if !url.trim().to_ascii_lowercase().starts_with("https://") {
        return Err(tf!("managedFile.error.nonHttps", url.as_str()));
    }
    let new_name = hooks::updated_jar_name(&old, &url);
    let tmp = update_temp_file(&mods_dir, &new_name);
    if let Err(e) = mmcl_net::download_file_url(&url, &tmp, &mmcl_net::DownloadOpts::new()).await {
        let _ = tokio::fs::remove_file(&tmp).await;
        return Err(e.to_string());
    }
    let dest = mods_dir.join(&new_name);
    if let Err(e) = tokio::fs::rename(&tmp, &dest).await {
        let _ = tokio::fs::remove_file(&tmp).await;
        return Err(e.to_string());
    }
    if new_name != old {
        let _ = tokio::fs::remove_file(mods_dir.join(&old)).await;
    }
    Ok(new_name)
}

/// Installs the hooks.
pub fn install(cx: &mut App, engine: &Engine) {
    let e = engine.clone();
    hooks::set_problem_source(cx, move |id, root| {
        let launches = e.pack(id).map(|p| p.launch_count).unwrap_or(0);
        problem_views(&audit::analyze_problems(
            root,
            launches,
            audit::DEFAULT_PROBLEM_THRESHOLD,
        ))
    });
    let e = engine.clone();
    hooks::set_website_source(cx, move |id| {
        e.pack(id)
            .and_then(|p| p.website)
            .and_then(|w| mmcl_ui_kit::sys::sanitized_http_url(&w))
    });
    let e = engine.clone();
    hooks::set_mod_update_checker(cx, move |mods_dir, cx| {
        let http = e.http();
        let task = e.spawn(async move { modrinth_updates::scan(&mods_dir, &*http).await });
        cx.spawn(async move |_| {
            task.await
                .map(update_views)
                .map_err(|_| t!("detailModal.mods.checkFailed").into_owned())
        })
    });
    let e = engine.clone();
    hooks::set_add_mod_handler(cx, move |id, mods_dir, window, cx| {
        crate::dialogs::add_mod::show_add_mod(
            e.clone(),
            id,
            mods_dir,
            crate::dialogs::Owner::of(window, cx),
            cx,
        );
    });
    let e = engine.clone();
    hooks::set_mod_updater(cx, move |mods_dir, old, url, cx| {
        let task = e.spawn(apply_update(mods_dir, old, url));
        cx.spawn(async move |_| {
            task.await
                .unwrap_or_else(|_| Err(t!("detailModal.mods.checkFailed").into_owned()))
        })
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn problems_read_like_java() {
        let v = problem_views(&[
            Problem {
                file: "mods/a.jar".into(),
                consecutive_launches: 3,
                content_unchanged: true,
            },
            Problem {
                file: "config/b.cfg".into(),
                consecutive_launches: 4,
                content_unchanged: false,
            },
        ]);
        assert!(
            v[0].title.contains("mods/a.jar") && v[0].title.contains('3'),
            "{}",
            v[0].title
        );
        assert_eq!(v[0].detail, t!("detailModal.problems.unchanged"));
        assert_eq!(v[1].detail, t!("detailModal.problems.changed"));
    }

    #[test]
    fn updates_are_sorted_and_mapped() {
        let mut m = std::collections::HashMap::new();
        m.insert(
            "b.jar".to_string(),
            modrinth_updates::ModUpdate::not_on_modrinth(),
        );
        let mut a = modrinth_updates::ModUpdate::not_on_modrinth();
        a.status = modrinth_updates::Status::UpdateAvailable;
        a.latest_version = Some("2".into());
        m.insert("A.jar".to_string(), a);
        let v = update_views(m);
        assert_eq!(v[0].file_name, "A.jar");
        assert_eq!(v[0].status, UpdateStatus::UpdateAvailable);
        assert_eq!(v[1].status, UpdateStatus::NotOnModrinth);
    }

    #[test]
    fn the_update_temp_file_sits_beside_the_jar() {
        let p = update_temp_file(Path::new("mods"), "x-2.jar");
        assert_eq!(p, Path::new("mods").join(".x-2.jar.update.tmp"));
    }

    #[test]
    fn keys_exist() {
        for k in [
            "detailModal.problems.redownload",
            "detailModal.problems.unchanged",
            "detailModal.problems.changed",
            "detailModal.mods.checkFailed",
            "managedFile.error.nonHttps",
        ] {
            assert!(mmcl_i18n::contains(k), "{k}");
        }
    }
}
