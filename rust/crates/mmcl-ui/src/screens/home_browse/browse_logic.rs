// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Browse's pure rules: the entry list (`collectEntries`), search, sort, the per-entry chips /
//! badge / actions, the empty state, and the mod-file-name search cache.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::LazyLock;
use std::time::SystemTime;

use mmcl_engine::{LoaderVersionView, PackId, PackSummary, VanillaVersionView};
use mmcl_i18n::{t, tf};
use mmcl_ui_kit::Icon;
use parking_lot::Mutex;

use super::home_logic::BadgeTone;

/// One day in ms (the synthetic loader release spacing).
const DAY_MS: i64 = 86_400_000;

/// What an entry is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum EntryKind {
    ModpackInstalled,
    ModpackAvailable,
    VanillaInstalled,
    VanillaAvailable,
    LoaderAvailable,
}

/// One Browse card (`LibraryEntry`).
#[derive(Debug, Clone, PartialEq)]
pub struct LibraryEntry {
    pub kind: EntryKind,
    pub display_name: String,
    pub chips: Vec<String>,
    pub status_text: String,
    pub pack: Option<PackSummary>,
    pub vanilla_id: Option<String>,
    pub vanilla: Option<VanillaVersionView>,
    pub loader: Option<LoaderVersionView>,
    /// Release time in ms (`i64::MIN` = none).
    pub release_time_ms: i64,
}

impl LibraryEntry {
    /// A stable key for card state.
    pub fn key(&self) -> String {
        match self.kind {
            EntryKind::ModpackInstalled | EntryKind::ModpackAvailable => format!(
                "pack:{}",
                self.pack
                    .as_ref()
                    .map(|p| p.id.as_str())
                    .unwrap_or_default()
            ),
            EntryKind::VanillaInstalled | EntryKind::VanillaAvailable => {
                format!("mc:{}", self.vanilla_id.as_deref().unwrap_or_default())
            }
            EntryKind::LoaderAvailable => {
                let l = self.loader.as_ref();
                format!(
                    "loader:{}:{}:{}",
                    l.map(|l| l.loader_type.as_str()).unwrap_or_default(),
                    l.map(|l| l.minecraft_version.as_str()).unwrap_or_default(),
                    l.map(|l| l.loader_version.as_str()).unwrap_or_default()
                )
            }
        }
    }

    /// The top badge (text and tone), if any.
    pub fn badge(&self) -> Option<(String, BadgeTone)> {
        match self.kind {
            EntryKind::ModpackInstalled => {
                let updated = self.pack.as_ref().is_some_and(|p| p.update_available);
                Some((
                    if updated {
                        t!("library.status.recentlyUpdated").into_owned()
                    } else {
                        t!("library.status.installed").into_owned()
                    },
                    BadgeTone::Success,
                ))
            }
            EntryKind::VanillaInstalled => Some((
                t!("library.status.installed").into_owned(),
                BadgeTone::Success,
            )),
            EntryKind::ModpackAvailable | EntryKind::LoaderAvailable => {
                Some((t!("library.status.available").into_owned(), BadgeTone::Info))
            }
            EntryKind::VanillaAvailable => None,
        }
    }

    /// The card's actions.
    pub fn actions(&self) -> &'static [EntryAction] {
        match self.kind {
            EntryKind::ModpackInstalled => &[EntryAction::Uninstall, EntryAction::Edit],
            EntryKind::VanillaInstalled => &[EntryAction::Uninstall],
            _ => &[EntryAction::Install],
        }
    }
}

/// A card button.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EntryAction {
    Install,
    Uninstall,
    Edit,
}

/// Everything `collect_entries` reads, gathered off the UI thread.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct BrowseSources {
    /// Installed modpacks (vanilla excluded).
    pub installed: Vec<PackSummary>,
    /// The available list, `None` while loading.
    pub available: Option<Vec<PackSummary>>,
    /// Installed vanilla version ids.
    pub vanilla_installed: Vec<String>,
    /// Version lists by kind (`release`, `snapshot`, `old_beta`, `old_alpha`); a missing kind
    /// is skipped.
    pub vanilla: HashMap<String, Vec<VanillaVersionView>>,
    /// Loader lists by type.
    pub loaders: HashMap<String, Vec<LoaderVersionView>>,
}

/// The vanilla kinds a type filter shows.
pub fn vanilla_kinds_for(kind: &str) -> &'static [&'static str] {
    match kind {
        "all" => &["release", "snapshot", "old_beta", "old_alpha"],
        "vanilla.release" => &["release"],
        "vanilla.snapshot" => &["snapshot"],
        "vanilla.beta" => &["old_beta"],
        "vanilla.alpha" => &["old_alpha"],
        _ => &[],
    }
}

/// The loader type a type filter shows (`forge`, `neoforge`, `fabric`).
pub fn loader_for(kind: &str) -> Option<&'static str> {
    match kind {
        "forge" => Some("forge"),
        "neoforge" => Some("neoforge"),
        "fabric" => Some("fabric"),
        _ => None,
    }
}

/// `Forge`, `NeoForge`, `Fabric`, or the raw id.
pub fn pretty_loader(id: &str) -> String {
    match id.to_ascii_lowercase().as_str() {
        "forge" => "Forge".into(),
        "neoforge" => "NeoForge".into(),
        "fabric" => "Fabric".into(),
        _ => id.to_string(),
    }
}

/// The localized vanilla type (Java hard-coded these four in English).
pub fn pretty_vanilla_type(kind: &str) -> String {
    match kind {
        "release" => t!("library.vanillaType.release").into_owned(),
        "snapshot" => t!("library.vanillaType.snapshot").into_owned(),
        "old_beta" => t!("library.vanillaType.oldBeta").into_owned(),
        "old_alpha" => t!("library.vanillaType.oldAlpha").into_owned(),
        other => other.to_string(),
    }
}

/// An ISO offset date-time in ms (`i64::MIN` when unparsable).
pub fn parse_release_time(s: &str) -> i64 {
    chrono::DateTime::parse_from_rfc3339(s.trim())
        .map(|d| d.timestamp_millis())
        .unwrap_or(i64::MIN)
}

fn pack_chips(p: &PackSummary) -> Vec<String> {
    let mut chips = vec![t!("library.chip.modpack").into_owned()];
    if let Some(mc) = p
        .minecraft_version
        .as_deref()
        .filter(|s| !s.trim().is_empty())
    {
        chips.push(tf!("library.chip.minecraft", mc));
    }
    chips
}

fn pack_name(p: &PackSummary) -> String {
    if !p.friendly_name.trim().is_empty() {
        p.friendly_name.clone()
    } else {
        p.name.clone()
    }
}

/// An installed modpack entry.
pub fn installed_modpack(p: &PackSummary) -> LibraryEntry {
    LibraryEntry {
        kind: EntryKind::ModpackInstalled,
        display_name: pack_name(p),
        chips: pack_chips(p),
        status_text: if p.update_available {
            t!("library.status.recentlyUpdated").into_owned()
        } else {
            t!("library.status.installed").into_owned()
        },
        pack: Some(p.clone()),
        vanilla_id: None,
        vanilla: None,
        loader: None,
        release_time_ms: i64::MIN,
    }
}

/// An available modpack entry.
pub fn available_modpack(p: &PackSummary) -> LibraryEntry {
    LibraryEntry {
        kind: EntryKind::ModpackAvailable,
        status_text: t!("library.status.fromManifest").into_owned(),
        ..installed_modpack(p)
    }
}

fn vanilla_entry(v: &VanillaVersionView, installed: bool) -> LibraryEntry {
    let status_text = if installed {
        t!("library.status.installed").into_owned()
    } else if v.release_time.chars().count() >= 10 {
        tf!(
            "library.status.released",
            v.release_time.chars().take(10).collect::<String>()
        )
    } else {
        t!("library.status.available").into_owned()
    };
    LibraryEntry {
        kind: if installed {
            EntryKind::VanillaInstalled
        } else {
            EntryKind::VanillaAvailable
        },
        display_name: tf!("library.entry.minecraftVersion", v.id.as_str()),
        chips: vec![
            t!("library.chip.vanilla").into_owned(),
            pretty_vanilla_type(&v.kind),
        ],
        status_text,
        pack: None,
        vanilla_id: Some(v.id.clone()),
        vanilla: Some(v.clone()),
        loader: None,
        release_time_ms: parse_release_time(&v.release_time),
    }
}

/// An available loader entry; `index` is its position in the upstream catalogue.
pub fn available_loader(v: &LoaderVersionView, index: usize, now_ms: i64) -> LibraryEntry {
    let pretty = pretty_loader(&v.loader_type);
    LibraryEntry {
        kind: EntryKind::LoaderAvailable,
        display_name: format!("{pretty} {}", v.loader_version),
        chips: vec![
            pretty,
            tf!("library.chip.minecraft", v.minecraft_version.as_str()),
        ],
        status_text: t!("library.status.installsAsEmpty").into_owned(),
        pack: None,
        vanilla_id: None,
        vanilla: None,
        loader: Some(v.clone()),
        release_time_ms: now_ms.saturating_sub(i64::try_from(index).unwrap_or(0) * DAY_MS),
    }
}

/// The entry list for a type and status filter (`collectEntries`).
pub fn collect_entries(
    src: &BrowseSources,
    kind: &str,
    status: &str,
    now_ms: i64,
) -> Vec<LibraryEntry> {
    let want_installed = status != "available";
    let want_available = status != "installed";
    let want_modpacks = kind == "all" || kind == "modpacks";
    let mut out = Vec::new();
    if want_modpacks && want_installed {
        out.extend(
            src.installed
                .iter()
                .filter(|p| !p.is_vanilla)
                .map(installed_modpack),
        );
    }
    if want_modpacks
        && want_available
        && let Some(avail) = &src.available
    {
        for p in avail {
            let dup = src
                .installed
                .iter()
                .any(|i| !i.friendly_name.is_empty() && i.friendly_name == p.friendly_name);
            if !dup {
                out.push(available_modpack(p));
            }
        }
    }
    if let Some(loader) = loader_for(kind)
        && want_available
        && let Some(list) = src.loaders.get(loader)
    {
        for (index, v) in list.iter().enumerate() {
            if !v.installed {
                out.push(available_loader(v, index, now_ms));
            }
        }
    }
    for vk in vanilla_kinds_for(kind) {
        let Some(list) = src.vanilla.get(*vk) else {
            continue;
        };
        for v in list {
            let installed = v.installed || src.vanilla_installed.iter().any(|i| i == &v.id);
            if installed && want_installed {
                out.push(vanilla_entry(v, true));
            } else if !installed && want_available {
                out.push(vanilla_entry(v, false));
            }
        }
    }
    out
}

/// Keeps entries whose lower-cased name contains `search` (already lower-cased and trimmed),
/// or whose pack has a mod file name containing it.
pub fn filter_search(
    entries: Vec<LibraryEntry>,
    search: &str,
    mods_match: impl Fn(&PackSummary, &str) -> bool,
) -> Vec<LibraryEntry> {
    if search.is_empty() {
        return entries;
    }
    entries
        .into_iter()
        .filter(|e| {
            e.display_name.to_lowercase().contains(search)
                || e.pack.as_ref().is_some_and(|p| mods_match(p, search))
        })
        .collect()
}

/// Sorts entries (stable; descending orders newest / most first; missing values sink).
pub fn sort_entries(
    entries: &mut [LibraryEntry],
    sort: &str,
    update_ms: impl Fn(&PackId) -> Option<i64>,
) {
    use std::cmp::Reverse;
    let pack_val = |e: &LibraryEntry, f: &dyn Fn(&PackSummary) -> i64| {
        e.pack.as_ref().map(f).unwrap_or(i64::MIN)
    };
    match sort {
        "nameAz" => entries.sort_by_key(|e| e.display_name.to_lowercase()),
        "nameZa" => entries.sort_by_key(|e| Reverse(e.display_name.to_lowercase())),
        "releaseDate" => entries.sort_by_key(|e| {
            Reverse(if e.release_time_ms != i64::MIN {
                e.release_time_ms
            } else {
                e.pack
                    .as_ref()
                    .and_then(|p| update_ms(&p.id))
                    .unwrap_or(i64::MIN)
            })
        }),
        "lastPlayed" => entries.sort_by_key(|e| Reverse(pack_val(e, &|p| p.last_played_ms))),
        "mostPlayed" => entries.sort_by_key(|e| Reverse(pack_val(e, &|p| p.total_play_time_ms))),
        "recentUpdate" => entries.sort_by_key(|e| {
            Reverse(
                e.pack
                    .as_ref()
                    .and_then(|p| update_ms(&p.id))
                    .unwrap_or(i64::MIN),
            )
        }),
        _ => {}
    }
}

/// Browse's empty state: badge, heading, sub text. Labels (not raw ids) go into the template.
pub fn empty_state(search: &str, type_label: &str, status_label: &str) -> (Icon, String, String) {
    if search.is_empty() {
        (
            Icon::Browse,
            t!("library.empty.heading").into_owned(),
            t!("library.empty.sub.noSearch").into_owned(),
        )
    } else {
        (
            Icon::Search,
            t!("library.empty.heading").into_owned(),
            tf!("library.empty.sub.search", type_label, status_label, search),
        )
    }
}

/// The Modrinth project preview (`buildModrinthPreview`): the title (or
/// `preview.modrinth.unnamedProject`) and the dialog body: title, description, source + slug,
/// version, Minecraft versions, loaders, URL.
pub fn modrinth_preview_text(p: &mmcl_engine::ModrinthProjectView, url: &str) -> (String, String) {
    let title = p
        .title
        .clone()
        .filter(|t| !t.trim().is_empty())
        .unwrap_or_else(|| t!("preview.modrinth.unnamedProject").into_owned());
    let mut lines = vec![title.clone()];
    if let Some(d) = p.description.as_ref().filter(|d| !d.trim().is_empty()) {
        lines.push(String::new());
        lines.push(d.clone());
    }
    lines.push(String::new());
    lines.push(format!(
        "{} ({})",
        t!("preview.modrinth.sourceLabel"),
        p.slug
    ));
    if let Some(v) = p
        .version_number
        .as_ref()
        .or(p.version_name.as_ref())
        .filter(|v| !v.trim().is_empty())
    {
        lines.push(tf!("preview.modrinth.versionLabel", v.as_str()));
    }
    if !p.game_versions.is_empty() {
        lines.push(tf!(
            "preview.modrinth.minecraftLabel",
            p.game_versions.join(", ")
        ));
    }
    if !p.loaders.is_empty() {
        lines.push(tf!("preview.modrinth.loaderLabel", p.loaders.join(", ")));
    }
    lines.push(tf!("preview.urlLabel", url));
    (title, lines.join("\n"))
}

/// The hosting manifest (`{ "available": [ urls ] }`), pretty-printed.
pub fn hosting_manifest(urls: &[String]) -> String {
    let mut map = serde_json::Map::new();
    map.insert(
        mmcl_core::consts::modpack::AVAILABLE_PACKS_MANIFEST_LIST_KEY.to_string(),
        serde_json::Value::Array(
            urls.iter()
                .cloned()
                .map(serde_json::Value::String)
                .collect(),
        ),
    );
    serde_json::to_string_pretty(&serde_json::Value::Object(map)).unwrap_or_default()
}

// ---------------------------------------------------------------------------------------------
// Mod file names (searched by Browse)

struct ModsEntry {
    mtime: Option<SystemTime>,
    names: Vec<String>,
}

static MOD_NAMES: LazyLock<Mutex<HashMap<PathBuf, ModsEntry>>> = LazyLock::new(Default::default);

fn list_mods(mods: &Path) -> Vec<String> {
    let Ok(rd) = std::fs::read_dir(mods) else {
        return Vec::new();
    };
    rd.filter_map(Result::ok)
        .filter_map(|e| e.file_name().into_string().ok())
        .map(|n| n.to_lowercase())
        .filter(|n| n.ends_with(".jar") || n.ends_with(".disabled"))
        .collect()
}

/// Whether any mod file in `<root>/mods` contains `needle` (lower-case). The listing is cached
/// per root and refreshed when the folder's mtime changes. Blocking: call off the UI thread.
pub fn pack_mods_match(root: &Path, needle: &str) -> bool {
    let mods = root.join("mods");
    let mtime = std::fs::metadata(&mods).and_then(|m| m.modified()).ok();
    let mut cache = MOD_NAMES.lock();
    let fresh = cache
        .get(root)
        .is_some_and(|e| e.mtime == mtime && mtime.is_some());
    if !fresh {
        cache.insert(
            root.to_path_buf(),
            ModsEntry {
                mtime,
                names: list_mods(&mods),
            },
        );
    }
    cache
        .get(root)
        .is_some_and(|e| e.names.iter().any(|n| n.contains(needle)))
}

/// Fills the mod-name cache for `roots` (Browse prewarms it so the first keystroke is fast).
pub fn prewarm_mod_names(roots: &[PathBuf]) {
    for r in roots {
        let _ = pack_mods_match(r, "\u{0}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::screens::home_browse::home_logic::tests::pack;

    #[test]
    fn modrinth_preview_lists_the_project_facts() {
        let p = mmcl_engine::ModrinthProjectView {
            slug: "fo".into(),
            title: Some("Fabulously Optimized".into()),
            description: Some("Fast.".into()),
            version_number: Some("6.1".into()),
            game_versions: vec!["1.21".into(), "1.21.1".into()],
            loaders: vec!["fabric".into()],
            ..Default::default()
        };
        let (title, body) = modrinth_preview_text(&p, "https://modrinth.com/modpack/fo");
        assert_eq!(title, "Fabulously Optimized");
        let lines: Vec<&str> = body.lines().collect();
        assert_eq!(lines[0], "Fabulously Optimized");
        assert_eq!(lines[2], "Fast.");
        assert!(body.contains("(fo)"));
        assert!(body.contains(&*tf!("preview.modrinth.versionLabel", "6.1")));
        assert!(body.contains(&*tf!("preview.modrinth.minecraftLabel", "1.21, 1.21.1")));
        assert!(body.contains(&*tf!("preview.modrinth.loaderLabel", "fabric")));
        assert!(body.ends_with(&*tf!("preview.urlLabel", "https://modrinth.com/modpack/fo")));

        let bare = mmcl_engine::ModrinthProjectView {
            slug: "x".into(),
            title: Some(" ".into()),
            ..Default::default()
        };
        let (title, body) = modrinth_preview_text(&bare, "u");
        assert_eq!(title, t!("preview.modrinth.unnamedProject"));
        // Title, blank, source, URL: no description, version, Minecraft or loader lines.
        assert_eq!(body.lines().count(), 4);
    }

    fn vv(id: &str, kind: &str, installed: bool) -> VanillaVersionView {
        VanillaVersionView {
            id: id.into(),
            kind: kind.into(),
            release_time: "2023-06-07T09:36:16+00:00".into(),
            installed,
        }
    }

    fn lv(t: &str, v: &str, installed: bool) -> LoaderVersionView {
        LoaderVersionView {
            loader_type: t.into(),
            minecraft_version: "1.20.1".into(),
            loader_version: v.into(),
            display_name: format!("{t} {v}"),
            installed,
        }
    }

    fn sources() -> BrowseSources {
        let mut a = pack("Avail");
        a.friendly_name = "Avail".into();
        let dup = pack("Inst");
        let mut s = BrowseSources {
            installed: vec![pack("Inst")],
            available: Some(vec![a, dup]),
            vanilla_installed: vec!["1.20.1".into()],
            ..Default::default()
        };
        s.vanilla.insert(
            "release".into(),
            vec![
                vv("1.20.1", "release", false),
                vv("1.19.4", "release", false),
            ],
        );
        s.vanilla
            .insert("snapshot".into(), vec![vv("23w01a", "snapshot", false)]);
        s.loaders.insert(
            "forge".into(),
            vec![lv("forge", "47.2.0", true), lv("forge", "47.1.0", false)],
        );
        s
    }

    fn kinds(e: &[LibraryEntry]) -> Vec<EntryKind> {
        e.iter().map(|e| e.kind).collect()
    }

    #[test]
    fn status_and_type_matrix() {
        let s = sources();
        use EntryKind::*;
        // Default: installed only, all types.
        assert_eq!(
            kinds(&collect_entries(&s, "all", "installed", 0)),
            [ModpackInstalled, VanillaInstalled]
        );
        // Available modpacks drop the installed duplicate by friendly name.
        let avail = collect_entries(&s, "modpacks", "available", 0);
        assert_eq!(kinds(&avail), [ModpackAvailable]);
        assert_eq!(avail[0].display_name, "Avail");
        // All statuses, vanilla releases.
        assert_eq!(
            kinds(&collect_entries(&s, "vanilla.release", "all", 0)),
            [VanillaInstalled, VanillaAvailable]
        );
        // Snapshots, available.
        assert_eq!(
            kinds(&collect_entries(&s, "vanilla.snapshot", "available", 0)),
            [VanillaAvailable]
        );
        // A missing kind list is skipped.
        assert!(collect_entries(&s, "vanilla.alpha", "all", 0).is_empty());
        // Loaders: installed ones skipped, the index keeps counting.
        let l = collect_entries(&s, "forge", "all", 10 * DAY_MS);
        assert_eq!(kinds(&l), [LoaderAvailable]);
        assert_eq!(l[0].release_time_ms, 9 * DAY_MS);
        assert_eq!(l[0].display_name, "Forge 47.1.0");
        assert!(collect_entries(&s, "forge", "installed", 0).is_empty());
        // The available list still loading.
        let mut s2 = s.clone();
        s2.available = None;
        assert!(collect_entries(&s2, "modpacks", "available", 0).is_empty());
    }

    #[test]
    fn entry_badges_and_actions() {
        let s = sources();
        let e = collect_entries(&s, "all", "all", 0);
        let inst = e
            .iter()
            .find(|e| e.kind == EntryKind::ModpackInstalled)
            .unwrap();
        assert_eq!(inst.actions(), [EntryAction::Uninstall, EntryAction::Edit]);
        assert_eq!(inst.badge().unwrap().1, BadgeTone::Success);
        let av = e
            .iter()
            .find(|e| e.kind == EntryKind::VanillaAvailable)
            .unwrap();
        assert!(av.badge().is_none());
        assert_eq!(av.actions(), [EntryAction::Install]);
        assert!(av.status_text.contains("2023-06-07"));
        let keys: std::collections::HashSet<_> = e.iter().map(LibraryEntry::key).collect();
        assert_eq!(keys.len(), e.len());
    }

    #[test]
    fn search_and_sorts() {
        let s = sources();
        let e = collect_entries(&s, "all", "all", 0);
        let found = filter_search(e.clone(), "1.19", |_, _| false);
        assert_eq!(found.len(), 1);
        let by_mods = filter_search(e.clone(), "jei", |p, _| p.name == "Inst");
        assert_eq!(by_mods.len(), 1);
        let mut sorted = e.clone();
        sort_entries(&mut sorted, "nameAz", |_| None);
        assert_eq!(sorted[0].display_name, "Avail");
        let mut by_date = collect_entries(&s, "vanilla.release", "all", 0);
        by_date[1].release_time_ms = 5;
        by_date[0].release_time_ms = 1;
        sort_entries(&mut by_date, "releaseDate", |_| None);
        assert_eq!(by_date[0].release_time_ms, 5);
        let mut d = e.clone();
        sort_entries(&mut d, "default", |_| None);
        assert_eq!(d, e);
    }

    #[test]
    fn release_times_parse_or_sink() {
        assert_eq!(parse_release_time("1970-01-01T00:00:01+00:00"), 1000);
        assert_eq!(parse_release_time("garbage"), i64::MIN);
        assert_eq!(pretty_vanilla_type("weird"), "weird");
        assert_eq!(pretty_loader("NEOFORGE"), "NeoForge");
    }

    #[test]
    fn hosting_manifest_lists_urls() {
        let text = hosting_manifest(&["https://a/x.json".into()]);
        let v: serde_json::Value = serde_json::from_str(&text).unwrap();
        assert_eq!(v["available"][0], "https://a/x.json");
    }

    #[test]
    fn mod_names_are_cached_and_refreshed() {
        let dir = std::env::temp_dir().join(format!("mmcl-ui-mods-{}", std::process::id()));
        let mods = dir.join("mods");
        std::fs::create_dir_all(&mods).unwrap();
        std::fs::write(mods.join("JEI-1.20.jar"), b"").unwrap();
        std::fs::write(mods.join("notes.txt"), b"").unwrap();
        assert!(pack_mods_match(&dir, "jei"));
        assert!(!pack_mods_match(&dir, "notes"));
        assert!(!pack_mods_match(&dir.join("missing"), "jei"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn empty_state_uses_labels() {
        let (icon, _, sub) = empty_state("abc", "Modpacks", "Installed");
        assert_eq!(icon, Icon::Search);
        assert!(sub.contains("Modpacks") && sub.contains("abc"));
        assert_eq!(empty_state("", "", "").0, Icon::Browse);
    }
}
