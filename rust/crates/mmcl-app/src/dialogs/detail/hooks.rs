// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! What the detail modal needs that the engine does not offer yet, as hooks the app can fill:
//! the audit's problem list, the pack website, the Modrinth mod-update check and update, and
//! the "Add mod" window. Without a hook the matching control is shown disabled with a tooltip
//! saying it is not available yet; nothing is dropped.

use std::path::{Path, PathBuf};
use std::rc::Rc;

use gpui::{App, Global, Task, Window};
use mmcl_engine::PackId;

/// One problem the pack audit found (already localized).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProblemView {
    pub title: String,
    pub detail: String,
}

/// A mod's Modrinth status (`ModrinthModUpdateChecker.Status`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UpdateStatus {
    UpdateAvailable,
    UpToDate,
    NotOnModrinth,
}

/// One checked mod (`ModrinthModUpdateChecker.ModUpdate`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ModUpdate {
    /// The jar's file name in `mods/`.
    pub file_name: String,
    pub status: UpdateStatus,
    pub current_version: Option<String>,
    pub latest_version: Option<String>,
    pub latest_download_url: Option<String>,
}

type ProblemsFn = Rc<dyn Fn(&PackId, &Path) -> Vec<ProblemView>>;
type WebsiteFn = Rc<dyn Fn(&PackId) -> Option<String>>;
type CheckFn = Rc<dyn Fn(PathBuf, &mut App) -> Task<Result<Vec<ModUpdate>, String>>>;
type ApplyFn = Rc<dyn Fn(PathBuf, String, String, &mut App) -> Task<Result<String, String>>>;
type AddModFn = Rc<dyn Fn(PackId, PathBuf, &mut Window, &mut App)>;

/// The hook table.
#[derive(Default, Clone)]
pub struct DetailHooks {
    pub(crate) problems: Option<ProblemsFn>,
    pub(crate) website: Option<WebsiteFn>,
    pub(crate) check_updates: Option<CheckFn>,
    pub(crate) apply_update: Option<ApplyFn>,
    pub(crate) add_mod: Option<AddModFn>,
}

impl Global for DetailHooks {}

pub(crate) fn read(cx: &App) -> DetailHooks {
    cx.try_global::<DetailHooks>().cloned().unwrap_or_default()
}

/// The audit's problems of a pack (`ModPackAuditLog.analyzeProblems(root, launches)`).
pub fn set_problem_source(cx: &mut App, f: impl Fn(&PackId, &Path) -> Vec<ProblemView> + 'static) {
    cx.default_global::<DetailHooks>().problems = Some(Rc::new(f));
}

/// The pack's website (`packURL`) for the Website button.
pub fn set_website_source(cx: &mut App, f: impl Fn(&PackId) -> Option<String> + 'static) {
    cx.default_global::<DetailHooks>().website = Some(Rc::new(f));
}

/// The Modrinth update check of a `mods/` folder.
pub fn set_mod_update_checker(
    cx: &mut App,
    f: impl Fn(PathBuf, &mut App) -> Task<Result<Vec<ModUpdate>, String>> + 'static,
) {
    cx.default_global::<DetailHooks>().check_updates = Some(Rc::new(f));
}

/// Applies one update: `(mods_dir, old_jar_name, download_url)` -> the new jar name.
pub fn set_mod_updater(
    cx: &mut App,
    f: impl Fn(PathBuf, String, String, &mut App) -> Task<Result<String, String>> + 'static,
) {
    cx.default_global::<DetailHooks>().apply_update = Some(Rc::new(f));
}

/// Opens the "Add a mod from Modrinth" window for a pack's `mods/` folder
/// (`crate::detail_hooks` installs `dialogs::add_mod`). Without one the modal shows Add mod
/// disabled with its tooltip.
pub fn set_add_mod_handler(
    cx: &mut App,
    f: impl Fn(PackId, PathBuf, &mut Window, &mut App) + 'static,
) {
    cx.default_global::<DetailHooks>().add_mod = Some(Rc::new(f));
}

/// The name an updated jar gets: the URL-decoded last path segment of the download URL, or the
/// old name when that is blank or not a `.jar`.
pub fn updated_jar_name(old: &str, download_url: &str) -> String {
    let path = download_url.split(['?', '#']).next().unwrap_or("");
    let last = path.rsplit('/').next().unwrap_or("");
    let decoded = percent_decode(last);
    if decoded.trim().is_empty()
        || !decoded.to_lowercase().ends_with(".jar")
        || decoded.contains(['/', '\\'])
    {
        old.to_string()
    } else {
        decoded
    }
}

fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%'
            && i + 2 < bytes.len()
            && let (Some(h), Some(l)) = (hex(bytes[i + 1]), hex(bytes[i + 2]))
        {
            out.push(h * 16 + l);
            i += 3;
            continue;
        }
        // A path segment keeps `+` literally (only query strings use it for a space).
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn hex(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn updated_names_come_from_the_url() {
        assert_eq!(
            updated_jar_name(
                "old.jar",
                "https://cdn.example/data/x/versions/y/new%20mod-1.2.jar"
            ),
            "new mod-1.2.jar"
        );
        assert_eq!(
            updated_jar_name("old.jar", "https://cdn.example/file"),
            "old.jar"
        );
        assert_eq!(
            updated_jar_name("old.jar", "https://cdn.example/"),
            "old.jar"
        );
        assert_eq!(
            updated_jar_name("old.jar", "https://cdn.example/a.jar?x=1"),
            "a.jar"
        );
        assert_eq!(
            updated_jar_name("old.jar", "https://cdn.example/..%2Fevil.jar"),
            "old.jar",
            "a decoded slash never escapes mods/"
        );
    }
}
