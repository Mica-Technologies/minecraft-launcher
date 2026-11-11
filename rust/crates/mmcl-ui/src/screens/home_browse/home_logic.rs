// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Home's pure rules: the shown pack list (`buildFilteredSortedPacks`), display names, the
//! search haystack, card chips / badges / played line, the empty state, the announcement row
//! and the pagination labels.

use mmcl_engine::{AccountView, PackId, PackSummary};
use mmcl_i18n::{t, tf};
use mmcl_ui_kit::Icon;

use super::backend::PackExtras;
use super::model::{PageBounds, search_tokens};

/// One Home pack: the engine summary plus the async extras once loaded.
#[derive(Debug, Clone, PartialEq)]
pub struct HomeItem {
    /// The pack.
    pub summary: PackSummary,
    /// Folder / loader / update facts (`None` until read).
    pub extras: Option<PackExtras>,
}

impl HomeItem {
    /// An item with no extras yet.
    pub fn new(summary: PackSummary) -> Self {
        HomeItem {
            summary,
            extras: None,
        }
    }

    /// The pack id.
    pub fn id(&self) -> &PackId {
        &self.summary.id
    }

    fn loader_name(&self) -> Option<&str> {
        self.extras
            .as_ref()
            .and_then(|e| e.loader_name.as_deref())
            .filter(|s| !s.trim().is_empty())
    }

    fn loader_version(&self) -> Option<&str> {
        self.extras
            .as_ref()
            .and_then(|e| e.loader_version.as_deref())
            .filter(|s| !s.trim().is_empty())
    }
}

/// A synthetic vanilla pack for an installed version id (`GameModPack.createVanillaModPack`).
pub fn vanilla_summary(version: &str) -> PackSummary {
    PackSummary {
        id: PackId::vanilla(version),
        name: tf!("library.entry.minecraftVersion", version),
        friendly_name: tf!("library.entry.minecraftVersion", version),
        version: version.to_string(),
        installed_version: Some(version.to_string()),
        installed: true,
        update_available: false,
        is_vanilla: true,
        mod_loader: None,
        minecraft_version: Some(version.to_string()),
        unstable: false,
        load_state: mmcl_engine::PackLoadState::Loaded,
        load_error: None,
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
        ..Default::default()
    }
}

/// Installed packs plus one synthetic vanilla pack per installed version not already listed.
pub fn merge_vanilla(mut packs: Vec<PackSummary>, vanilla_ids: &[String]) -> Vec<PackSummary> {
    for id in vanilla_ids {
        let pid = PackId::vanilla(id);
        if !packs.iter().any(|p| p.id == pid) {
            packs.push(vanilla_summary(id));
        }
    }
    packs
}

/// Only `http(s)` pages are opened (the update release page, a pack website).
pub fn is_openable_update_url(url: &str) -> bool {
    let lower = url.trim().to_ascii_lowercase();
    lower.starts_with("https://") || lower.starts_with("http://")
}

fn non_blank(s: &str) -> Option<&str> {
    (!s.trim().is_empty()).then_some(s)
}

/// The card title: vanilla -> pack name; else friendly name, else pack name, else
/// `main.unnamedPack`.
pub fn display_name(p: &PackSummary) -> String {
    if p.is_vanilla
        && let Some(n) = non_blank(&p.name)
    {
        return n.to_string();
    }
    non_blank(&p.friendly_name)
        .or_else(|| non_blank(&p.name))
        .map(str::to_string)
        .unwrap_or_else(|| t!("main.unnamedPack").into_owned())
}

/// The lower-cased search haystack: name, version, Minecraft version, loader name and
/// version, then `vanilla` / `modpack`.
pub fn haystack(item: &HomeItem) -> String {
    let p = &item.summary;
    let mut parts: Vec<&str> = vec![];
    let name = display_name(p);
    parts.push(&name);
    if let Some(v) = non_blank(&p.version) {
        parts.push(v);
    }
    if let Some(mc) = p.minecraft_version.as_deref().and_then(non_blank) {
        parts.push(mc);
    }
    if let Some(l) = item.loader_name() {
        parts.push(l);
    }
    if let Some(l) = item.loader_version() {
        parts.push(l);
    }
    parts.push(if p.is_vanilla { "vanilla" } else { "modpack" });
    parts.join(" ").to_lowercase()
}

/// Every token is a substring of `hay`.
pub fn matches_all(hay: &str, tokens: &[String]) -> bool {
    tokens.iter().all(|t| hay.contains(t.as_str()))
}

/// The Home filter state.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HomeFilter {
    /// `all`, `modpacks`, `vanilla`.
    pub kind: String,
    /// Only packs with an update.
    pub updates_only: bool,
    /// The raw search text.
    pub search: String,
    /// The sort id.
    pub sort: String,
    /// Config `lastModPack`.
    pub last_mod_pack: String,
}

impl HomeFilter {
    /// Whether any filter narrows the list.
    pub fn active(&self) -> bool {
        self.updates_only || self.kind != "all" || !self.search.trim().is_empty()
    }
}

/// Indices into `items` in display order (`buildFilteredSortedPacks`).
pub fn build_filtered_sorted(items: &[HomeItem], f: &HomeFilter) -> Vec<usize> {
    let tokens = search_tokens(&f.search);
    let mut out: Vec<usize> = (0..items.len())
        .filter(|&i| {
            let p = &items[i].summary;
            match f.kind.as_str() {
                "modpacks" if p.is_vanilla => return false,
                "vanilla" if !p.is_vanilla => return false,
                _ => {}
            }
            if f.updates_only && !p.update_available {
                return false;
            }
            tokens.is_empty() || matches_all(&haystack(&items[i]), &tokens)
        })
        .collect();
    let s = |i: &usize| &items[*i].summary;
    match f.sort.as_str() {
        "nameAz" => out.sort_by_key(|i| display_name(s(i)).to_lowercase()),
        "nameZa" => {
            out.sort_by_key(|i| std::cmp::Reverse(display_name(s(i)).to_lowercase()));
        }
        "recentUpdate" => out.sort_by_key(|i| {
            std::cmp::Reverse(
                items[*i]
                    .extras
                    .as_ref()
                    .and_then(|e| e.newest_update_ms)
                    .unwrap_or(i64::MIN),
            )
        }),
        "mostPlayed" => out.sort_by_key(|i| std::cmp::Reverse(s(i).total_play_time_ms)),
        _ => {
            out.sort_by_key(|i| std::cmp::Reverse(s(i).last_played_ms));
            let last = f.last_mod_pack.as_str();
            if !last.is_empty()
                && let Some(pos) = out
                    .iter()
                    .position(|i| s(i).name == last || s(i).friendly_name == last)
            {
                let pinned = out.remove(pos);
                out.insert(0, pinned);
            }
        }
    }
    out
}

/// Text after the last `-` of a loader version (`1.12.2-14.23.5.2860` -> `14.23.5.2860`).
pub fn short_loader(version: &str) -> &str {
    version.rsplit('-').next().unwrap_or(version)
}

/// The card chips, in order.
pub fn chips(item: &HomeItem) -> Vec<String> {
    let p = &item.summary;
    let mut out = Vec::new();
    if p.is_vanilla {
        out.push(t!("main.chip.vanilla").into_owned());
    }
    let mc = p
        .minecraft_version
        .as_deref()
        .and_then(non_blank)
        .or_else(|| {
            item.extras
                .as_ref()
                .and_then(|e| e.minecraft_version.as_deref())
                .and_then(non_blank)
        });
    if let Some(mc) = mc {
        out.push(tf!("main.chip.minecraft", mc));
    }
    if let (Some(n), Some(v)) = (item.loader_name(), item.loader_version()) {
        out.push(format!("{n} {}", short_loader(v)));
    }
    if let Some(v) = non_blank(&p.version)
        && Some(v) != mc
    {
        out.push(tf!("main.chip.packVersion", v));
    }
    out
}

/// A badge's tone.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BadgeTone {
    /// `stat-chip` (neutral).
    Neutral,
    /// `stat-chip-success`.
    Success,
    /// `stat-chip-warn`.
    Warn,
    /// `stat-chip-info`.
    Info,
}

/// The hero card badges (beta, recently updated, news).
pub fn badges(p: &PackSummary) -> Vec<(String, BadgeTone)> {
    let mut out = Vec::new();
    if p.unstable {
        out.push((t!("main.badge.beta").into_owned(), BadgeTone::Warn));
    }
    if p.update_available {
        out.push((
            t!("main.badge.recentlyUpdated").into_owned(),
            BadgeTone::Success,
        ));
    }
    if p.unread_news > 0 {
        out.push((t!("main.badge.news").into_owned(), BadgeTone::Neutral));
    }
    out
}

/// The "Last played ..." line (boolean check, never a localized string compare).
pub fn played_line(p: &PackSummary, now_ms: i64) -> String {
    if p.never_played || p.last_played_ms <= 0 {
        return t!("main.card.neverPlayed").into_owned();
    }
    let label = mmcl_manifest::play_time::last_played(p.last_played_ms, now_ms);
    let ago = mmcl_i18n::format(label.key, &[mmcl_i18n::FmtArg::Int(label.amount as i64)]);
    tf!("main.card.lastPlayed", ago.to_lowercase())
}

/// The total play time (`getTotalPlayTimeFormatted`).
pub fn total_play_time(ms: i64) -> String {
    let label = mmcl_manifest::play_time::total_play_time(ms);
    let arg = if label.is_integral() {
        mmcl_i18n::FmtArg::Int(label.amount as i64)
    } else {
        mmcl_i18n::FmtArg::Float((label.amount * 10.0).round() / 10.0)
    };
    mmcl_i18n::format(label.key, &[arg])
}

/// The Play button tooltip: who the pack plays as, when it has an override.
pub fn play_tooltip(account_override: Option<&str>, accounts: &[AccountView]) -> Option<String> {
    let uuid = account_override?;
    Some(match accounts.iter().find(|a| a.uuid == uuid) {
        Some(a) => tf!(
            "modal.chip.playsAs",
            if a.name.is_empty() {
                a.uuid.as_str()
            } else {
                a.name.as_str()
            }
        ),
        None => t!("modal.chip.playsAsMissing").into_owned(),
    })
}

/// The Play label: running or play.
pub fn play_label(active: bool) -> String {
    if active {
        t!("main.card.running").into_owned()
    } else {
        t!("common.button.play").into_owned()
    }
}

/// What the empty grid shows.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EmptyState {
    /// The badge glyph.
    pub icon: Icon,
    /// Heading key.
    pub heading: &'static str,
    /// Sub-text key.
    pub sub: &'static str,
    /// Show the primary Browse button.
    pub browse_button: bool,
}

/// Home's empty state.
pub fn empty_state(
    has_any_installed: bool,
    filters_active: bool,
    search_active: bool,
) -> EmptyState {
    if has_any_installed && filters_active {
        EmptyState {
            icon: Icon::Search,
            heading: "main.empty.filtered.heading",
            sub: if search_active {
                "main.empty.filtered.sub.search"
            } else {
                "main.empty.filtered.sub.noSearch"
            },
            browse_button: false,
        }
    } else {
        EmptyState {
            icon: Icon::Game,
            heading: "main.empty.none.heading",
            sub: "main.empty.none.sub",
            browse_button: true,
        }
    }
}

/// The announcement row text (`setAnnouncementRow`): `extra + "\n" + home`, or `None` when
/// empty or dismissed this session.
pub fn announcement_text(
    home: &str,
    extra: Option<&str>,
    dismissed: impl Fn(&str) -> bool,
) -> Option<String> {
    let mut text = home.to_string();
    if let Some(extra) = extra.filter(|e| !e.is_empty()) {
        text = if text.is_empty() {
            extra.to_string()
        } else {
            format!("{extra}\n{text}")
        };
    }
    if text.trim().is_empty() || dismissed(&text) {
        None
    } else {
        Some(text)
    }
}

/// The announcement row height: 60 with a line break, 40 otherwise, 0 when hidden.
pub fn announcement_height(text: Option<&str>) -> f32 {
    match text {
        None => 0.0,
        Some(t) if t.contains('\n') => 60.0,
        Some(_) => 40.0,
    }
}

/// The pagination labels `(range, page)`.
pub fn pagination_labels(b: &PageBounds, page: usize, empty_key: &str) -> (String, String) {
    if b.total_items == 0 {
        (
            t!(empty_key).into_owned(),
            tf!("main.pagination.pageOfPages", 1, 1),
        )
    } else {
        (
            tf!("main.pagination.range", b.start + 1, b.end, b.total_items),
            tf!("main.pagination.pageOfPages", page, b.total_pages),
        )
    }
}

/// The index of the pack named `name` (friendly or pack name) in `order`.
pub fn find_by_name(items: &[HomeItem], order: &[usize], name: &str) -> Option<usize> {
    order.iter().position(|&i| {
        let p = &items[i].summary;
        p.friendly_name == name || p.name == name
    })
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    #[test]
    fn only_http_pages_open() {
        assert!(is_openable_update_url(
            "https://github.com/x/releases/tag/v1"
        ));
        assert!(is_openable_update_url(" HTTP://example.com "));
        assert!(!is_openable_update_url("file:///etc/passwd"));
        assert!(!is_openable_update_url("javascript:alert(1)"));
        assert!(!is_openable_update_url(""));
    }

    #[test]
    fn website_button_uses_the_pack_url() {
        use super::super::home::pack_website;
        let mut p = pack("A");
        assert_eq!(pack_website(&p), None);
        p.website = Some(" https://example.com/pack ".into());
        assert_eq!(pack_website(&p), Some("https://example.com/pack".into()));
        p.website = Some("ftp://example.com".into());
        assert_eq!(pack_website(&p), None);
    }

    pub(crate) fn pack(name: &str) -> PackSummary {
        let mut p = vanilla_summary("x");
        p.id = PackId::new(format!("https://example.com/{name}.json"));
        p.name = name.to_string();
        p.friendly_name = name.to_string();
        p.version = "1.0.0".into();
        p.is_vanilla = false;
        p.mod_loader = Some("forge".into());
        p.minecraft_version = Some("1.12.2".into());
        p.never_played = true;
        p
    }

    fn items(packs: Vec<PackSummary>) -> Vec<HomeItem> {
        packs.into_iter().map(HomeItem::new).collect()
    }

    fn filter() -> HomeFilter {
        HomeFilter {
            kind: "all".into(),
            updates_only: false,
            search: String::new(),
            sort: "lastPlayed".into(),
            last_mod_pack: String::new(),
        }
    }

    fn names(items: &[HomeItem], order: &[usize]) -> Vec<String> {
        order
            .iter()
            .map(|&i| items[i].summary.name.clone())
            .collect()
    }

    #[test]
    fn haystack_is_multi_token_and() {
        let mut it = HomeItem::new(pack("Alpha Pack"));
        it.extras = Some(PackExtras {
            loader_name: Some("Forge".into()),
            loader_version: Some("1.12.2-14.23.5.2860".into()),
            ..Default::default()
        });
        let hay = haystack(&it);
        assert!(matches_all(&hay, &search_tokens("alpha 14.23")));
        assert!(matches_all(&hay, &search_tokens("FORGE modpack 1.12")));
        assert!(!matches_all(&hay, &search_tokens("alpha vanilla")));
        assert!(matches_all(
            &haystack(&HomeItem::new(vanilla_summary("1.20.1"))),
            &search_tokens("vanilla 1.20")
        ));
    }

    #[test]
    fn last_played_sort_pins_the_last_pack() {
        let mut a = pack("A");
        a.last_played_ms = 300;
        let mut b = pack("B");
        b.last_played_ms = 200;
        let mut c = pack("C");
        c.last_played_ms = 100;
        let items = items(vec![c, a, b]);
        let mut f = filter();
        assert_eq!(
            names(&items, &build_filtered_sorted(&items, &f)),
            ["A", "B", "C"]
        );
        f.last_mod_pack = "C".into();
        assert_eq!(
            names(&items, &build_filtered_sorted(&items, &f)),
            ["C", "A", "B"]
        );
    }

    #[test]
    fn type_updates_and_name_sorts() {
        let mut a = pack("beta");
        a.update_available = true;
        let b = pack("Alpha");
        let v = vanilla_summary("1.20.1");
        let items = items(vec![a, b, v]);
        let mut f = filter();
        f.sort = "nameAz".into();
        assert_eq!(
            names(&items, &build_filtered_sorted(&items, &f)),
            ["Alpha", "beta", "Minecraft 1.20.1"]
        );
        f.sort = "nameZa".into();
        assert_eq!(
            names(&items, &build_filtered_sorted(&items, &f))[0],
            "Minecraft 1.20.1"
        );
        f.kind = "vanilla".into();
        assert_eq!(build_filtered_sorted(&items, &f).len(), 1);
        f.kind = "modpacks".into();
        assert_eq!(build_filtered_sorted(&items, &f).len(), 2);
        f.updates_only = true;
        assert_eq!(names(&items, &build_filtered_sorted(&items, &f)), ["beta"]);
        assert!(f.active());
        assert!(!filter().active());
    }

    #[test]
    fn recent_update_sinks_unknown() {
        let mut items = items(vec![pack("A"), pack("B"), pack("C")]);
        items[1].extras = Some(PackExtras {
            newest_update_ms: Some(10),
            ..Default::default()
        });
        items[2].extras = Some(PackExtras {
            newest_update_ms: Some(20),
            ..Default::default()
        });
        let mut f = filter();
        f.sort = "recentUpdate".into();
        assert_eq!(
            names(&items, &build_filtered_sorted(&items, &f)),
            ["C", "B", "A"]
        );
    }

    #[test]
    fn display_names_fall_back() {
        let mut p = pack("");
        p.friendly_name = String::new();
        assert_eq!(display_name(&p), t!("main.unnamedPack"));
        p.name = "Name".into();
        assert_eq!(display_name(&p), "Name");
        p.friendly_name = "Friendly".into();
        assert_eq!(display_name(&p), "Friendly");
    }

    #[test]
    fn chips_follow_the_card_rules() {
        let mut it = HomeItem::new(pack("A"));
        it.extras = Some(PackExtras {
            loader_name: Some("Forge".into()),
            loader_version: Some("1.12.2-14.23.5.2860".into()),
            ..Default::default()
        });
        let c = chips(&it);
        assert_eq!(c.len(), 3);
        assert_eq!(c[1], "Forge 14.23.5.2860");
        let v = chips(&HomeItem::new(vanilla_summary("1.20.1")));
        // Vanilla: the vanilla chip and Minecraft, no pack version (it equals the MC version).
        assert_eq!(v.len(), 2);
        assert_eq!(short_loader("0.15.11"), "0.15.11");
    }

    /// After a restart the list summary has no Minecraft version until the loader is built;
    /// the chip then comes from the extras the card loads.
    #[test]
    fn minecraft_chip_falls_back_to_the_extras() {
        let mut p = pack("A");
        p.minecraft_version = None;
        let mut it = HomeItem::new(p);
        assert!(!chips(&it).iter().any(|c| c.contains("1.12.2")));
        it.extras = Some(PackExtras {
            minecraft_version: Some("1.12.2".into()),
            ..Default::default()
        });
        assert_eq!(chips(&it)[0], tf!("main.chip.minecraft", "1.12.2"));
    }

    #[test]
    fn empty_states_and_banner() {
        assert_eq!(
            empty_state(true, true, true).sub,
            "main.empty.filtered.sub.search"
        );
        assert_eq!(
            empty_state(true, true, false).sub,
            "main.empty.filtered.sub.noSearch"
        );
        assert!(empty_state(false, true, true).browse_button);
        assert!(empty_state(true, false, false).browse_button);
        assert_eq!(announcement_text("", None, |_| false), None);
        assert_eq!(
            announcement_text("Hi", Some("DEV"), |_| false).as_deref(),
            Some("DEV\nHi")
        );
        assert_eq!(announcement_text("Hi", None, |t| t == "Hi"), None);
        assert_eq!(announcement_height(Some("a\nb")), 60.0);
        assert_eq!(announcement_height(Some("a")), 40.0);
        assert_eq!(announcement_height(None), 0.0);
    }

    #[test]
    fn played_line_uses_the_boolean() {
        let p = pack("A");
        assert_eq!(played_line(&p, 0), t!("main.card.neverPlayed"));
        let mut q = pack("B");
        q.never_played = false;
        q.last_played_ms = 1_000;
        assert!(played_line(&q, 1_000 + 3 * 3_600_000).contains('3'));
    }

    #[test]
    fn play_tooltips() {
        let acct = AccountView {
            uuid: "u1".into(),
            name: "Steve".into(),
            status: mmcl_engine::AccountStatus::Ready,
            is_default: true,
            remembered: true,
            last_used_ms: 0,
        };
        assert_eq!(play_tooltip(None, &[]), None);
        assert!(play_tooltip(Some("u1"), &[acct]).unwrap().contains("Steve"));
        assert_eq!(
            play_tooltip(Some("u2"), &[]).unwrap(),
            t!("modal.chip.playsAsMissing")
        );
    }

    #[test]
    fn vanilla_merge_skips_listed_versions() {
        let merged = merge_vanilla(
            vec![vanilla_summary("1.20.1")],
            &["1.20.1".into(), "1.8.9".into()],
        );
        assert_eq!(merged.len(), 2);
    }

    #[test]
    fn pagination_label_texts() {
        let b = PageBounds {
            total_items: 0,
            total_pages: 1,
            start: 0,
            end: 0,
        };
        assert_eq!(
            pagination_labels(&b, 1, "main.pagination.empty").0,
            t!("main.pagination.empty")
        );
        let b = PageBounds {
            total_items: 30,
            total_pages: 3,
            start: 10,
            end: 20,
        };
        let (r, p) = pagination_labels(&b, 2, "main.pagination.empty");
        assert!(r.contains("11") && r.contains("20") && r.contains("30"));
        assert!(p.contains('2') && p.contains('3'));
    }
}
