// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The pure parts of the pack detail modal: sizes, which tabs show, the chips, the stats rows,
//! the update log, the Advanced tab's options and the export choices.

use mmcl_engine::{
    AccountStatus, AccountView, ExportModeView, PackDetails, PackId, PackSummary, ScanFrequency,
    ServerView, UpdateLogView,
};
use mmcl_i18n::{t, tf};

/// The card's share of the window in each axis.
pub const CARD_FRACTION: f32 = 0.90;
/// The card's minimum size (px at 100 %).
pub const CARD_MIN: (f32, f32) = (600.0, 480.0);
/// The card's maximum size (px at 100 %).
pub const CARD_MAX: (f32, f32) = (1400.0, 1000.0);
/// The hero's share of the card height and its bounds.
pub const HERO_FRACTION: f32 = 0.36;
pub const HERO_MIN: f32 = 172.0;
pub const HERO_MAX: f32 = 320.0;
/// Update-log entries shown before "+N more".
pub const MAX_LOG_ENTRIES_SHOWN: usize = 12;
/// Screenshot tiles (px) and the gap between them.
pub const THUMB_SIZE: f32 = 100.0;
pub const THUMB_GAP: f32 = 8.0;

/// The card size for a window of `viewport` (both in px at 100 %).
pub fn card_size(viewport: (f32, f32)) -> (f32, f32) {
    let w = (viewport.0 * CARD_FRACTION).clamp(CARD_MIN.0, CARD_MAX.0);
    let h = (viewport.1 * CARD_FRACTION).clamp(CARD_MIN.1, CARD_MAX.1);
    (w, h)
}

/// The hero height for a card of height `card_h`.
pub fn hero_height(card_h: f32) -> f32 {
    (card_h * HERO_FRACTION).clamp(HERO_MIN, HERO_MAX)
}

/// One tab of the modal.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Tab {
    Overview,
    Problems,
    Content,
    Activity,
    Advanced,
}

impl Tab {
    /// The label key.
    pub fn label_key(self) -> &'static str {
        match self {
            Tab::Overview => "detailModal.tab.overview",
            Tab::Problems => "detailModal.section.problems",
            Tab::Content => "detailModal.tab.content",
            Tab::Activity => "detailModal.tab.activity",
            Tab::Advanced => "detailModal.tab.advanced",
        }
    }
}

/// The tabs a pack shows: Overview always; Problems with problems; Content and Activity with an
/// install folder; Advanced with a settings key.
pub fn tabs(problem_count: usize, has_root: bool, has_settings_key: bool) -> Vec<Tab> {
    let mut out = vec![Tab::Overview];
    if problem_count > 0 {
        out.push(Tab::Problems);
    }
    if has_root {
        out.push(Tab::Content);
        out.push(Tab::Activity);
    }
    if has_settings_key {
        out.push(Tab::Advanced);
    }
    out
}

/// Whether a pack id is a settings key (a manifest URL or `vanilla:`), not a `name:` fallback.
pub fn has_settings_key(id: &PackId) -> bool {
    !id.as_str().starts_with("name:")
}

/// A collapsible section of a tab.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Section {
    News,
    QuickActions,
    Stats,
    Worlds,
    Servers,
    Mods,
    Screenshots,
    ShaderPacks,
    ResourcePacks,
    UpdateLog,
    CrashHistory,
    Advanced,
    Problems,
}

impl Section {
    /// The heading key.
    pub fn heading_key(self) -> &'static str {
        match self {
            Section::News => "detailModal.section.news",
            Section::QuickActions => "detailModal.section.quickActions",
            Section::Stats => "detailModal.section.stats",
            Section::Worlds => "detailModal.section.worlds",
            Section::Servers => "detailModal.section.servers",
            Section::Mods => "detailModal.section.mods",
            Section::Screenshots => "detailModal.section.screenshots",
            Section::ShaderPacks => "detailModal.section.shaderPacks",
            Section::ResourcePacks => "detailModal.section.resourcePacks",
            Section::UpdateLog => "detailModal.section.updateLog",
            Section::CrashHistory => "detailModal.section.crashHistory",
            Section::Advanced => "detailModal.section.advanced",
            Section::Problems => "detailModal.section.problems",
        }
    }

    /// Whether it starts expanded (the mods list is built only when opened: it can be long).
    pub fn starts_expanded(self) -> bool {
        !matches!(self, Section::Mods)
    }

    /// A stable small number for element ids.
    pub fn ordinal(self) -> usize {
        self as usize
    }
}

/// The sections of a tab, in order. News appears only with news.
pub fn sections(tab: Tab, has_news: bool) -> Vec<Section> {
    match tab {
        Tab::Overview => {
            let mut v = Vec::new();
            if has_news {
                v.push(Section::News);
            }
            v.push(Section::QuickActions);
            v.push(Section::Stats);
            v
        }
        Tab::Problems => vec![Section::Problems],
        Tab::Content => vec![
            Section::Worlds,
            Section::Servers,
            Section::Mods,
            Section::Screenshots,
            Section::ShaderPacks,
            Section::ResourcePacks,
        ],
        Tab::Activity => vec![Section::UpdateLog, Section::CrashHistory],
        Tab::Advanced => vec![Section::Advanced],
    }
}

/// `%.0f` when whole, else `%.1f`; `None` when unset (0 or less).
pub fn ram_amount(gb: f64) -> Option<String> {
    if gb.is_nan() || gb <= 0.0 || !gb.is_finite() {
        return None;
    }
    Some(if gb.fract() == 0.0 {
        format!("{gb:.0}")
    } else {
        format!("{gb:.1}")
    })
}

fn non_blank(s: &str) -> Option<&str> {
    let s = s.trim();
    (!s.is_empty()).then_some(s)
}

/// Text after the last `-` of a loader version.
pub fn short_loader(version: &str) -> &str {
    version.rsplit('-').next().unwrap_or(version)
}

/// The header chips (`buildChipsRow`): plays as, Vanilla, Minecraft, loader, pack version (when
/// it differs from the Minecraft version), minimum RAM, mod count.
pub fn chips(
    summary: &PackSummary,
    details: Option<&PackDetails>,
    account_override: Option<&str>,
    accounts: &[AccountView],
    mod_count: usize,
) -> Vec<String> {
    let mut out = Vec::new();
    if let Some(uuid) = account_override {
        out.push(match accounts.iter().find(|a| a.uuid == uuid) {
            Some(a) => tf!(
                "modal.chip.playsAs",
                non_blank(&a.name).unwrap_or(a.uuid.as_str())
            ),
            None => t!("modal.chip.playsAsMissing").into_owned(),
        });
    }
    if summary.is_vanilla {
        out.push(t!("modal.chip.vanilla").into_owned());
    }
    let mc = summary.minecraft_version.as_deref().and_then(non_blank);
    if let Some(mc) = mc {
        out.push(tf!("modal.chip.minecraft", mc));
    }
    if let Some(d) = details
        && let (Some(name), Some(v)) = (
            d.loader_name.as_deref().and_then(non_blank),
            d.loader_version.as_deref().and_then(non_blank),
        )
    {
        out.push(format!("{name} {}", short_loader(v)));
    }
    if let Some(v) = non_blank(&summary.version)
        && Some(v) != mc
    {
        out.push(tf!("modal.chip.packVersion", v));
    }
    if let Some(ram) = ram_amount(summary.min_ram_gb) {
        out.push(tf!("modal.chip.minimumRam", ram));
    }
    if mod_count > 0 {
        out.push(tf!("modal.chip.modCount", mod_count as i64));
    }
    out
}

/// The hero badges: Beta, Recently updated.
pub fn badges(summary: &PackSummary) -> Vec<(String, bool)> {
    let mut out = Vec::new();
    if summary.unstable {
        out.push((t!("modal.badge.beta").into_owned(), true));
    }
    if summary.update_available {
        out.push((t!("modal.badge.recentlyUpdated").into_owned(), false));
    }
    out
}

/// The display name (`resolveDisplayName`): friendly name, else pack name, else "Unnamed".
pub fn display_name(summary: &PackSummary) -> String {
    non_blank(&summary.friendly_name)
        .or_else(|| non_blank(&summary.name))
        .map(str::to_string)
        .unwrap_or_else(|| t!("modal.unnamedPack").into_owned())
}

/// A local date-time `yyyy-MM-dd HH:mm` (`DATE_FORMAT`).
pub fn format_time(ms: i64) -> String {
    use chrono::TimeZone as _;
    match chrono::Local.timestamp_millis_opt(ms) {
        chrono::LocalResult::Single(t) | chrono::LocalResult::Ambiguous(t, _) => {
            t.format("%Y-%m-%d %H:%M").to_string()
        }
        chrono::LocalResult::None => String::new(),
    }
}

/// The installed version, annotated with the update when one is available.
pub fn installed_display(summary: &PackSummary) -> Option<String> {
    let installed = summary.installed_version.as_deref().and_then(non_blank)?;
    let remote = non_blank(&summary.version);
    Some(match remote {
        Some(r) if summary.update_available && r != installed => {
            tf!(
                "detailModal.stats.installedDisplay.withUpdate",
                installed,
                r
            )
        }
        _ => installed.to_string(),
    })
}

/// The stats rows `(label, value)`: last played, total play time, launches, installed version,
/// manifest URL.
pub fn stats_rows(summary: &PackSummary, total_play_time: String) -> Vec<(String, String)> {
    let last = if summary.never_played || summary.last_played_ms <= 0 {
        t!("main.card.neverPlayed").into_owned()
    } else {
        format_time(summary.last_played_ms)
    };
    let mut rows = vec![
        (t!("detailModal.stats.lastPlayed").into_owned(), last),
        (
            t!("detailModal.stats.totalPlayTime").into_owned(),
            total_play_time,
        ),
        (
            t!("detailModal.stats.launches").into_owned(),
            summary.launch_count.to_string(),
        ),
    ];
    if let Some(v) = installed_display(summary) {
        rows.push((t!("detailModal.stats.installedVersion").into_owned(), v));
    }
    if let Some(url) = summary.manifest_url.as_deref().and_then(non_blank) {
        rows.push((
            t!("detailModal.stats.manifest").into_owned(),
            url.to_string(),
        ));
    }
    rows
}

/// What the update-log section shows.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UpdateLogDisplay {
    /// No entries: the empty line for vanilla or modded packs.
    Empty(String),
    /// The newest entries, and the "+N more" line when some are hidden.
    Entries(Vec<UpdateLogView>, Option<String>),
}

/// The update log (newest first), capped at [`MAX_LOG_ENTRIES_SHOWN`].
pub fn update_log(entries: &[UpdateLogView], vanilla: bool) -> UpdateLogDisplay {
    if entries.is_empty() {
        return UpdateLogDisplay::Empty(
            if vanilla {
                t!("modal.updateLog.empty.vanilla")
            } else {
                t!("modal.updateLog.empty.modded")
            }
            .into_owned(),
        );
    }
    let shown = entries
        .iter()
        .take(MAX_LOG_ENTRIES_SHOWN)
        .cloned()
        .collect();
    let more = (entries.len() > MAX_LOG_ENTRIES_SHOWN).then(|| {
        tf!(
            "modal.updateLog.more",
            (entries.len() - MAX_LOG_ENTRIES_SHOWN) as i64
        )
    });
    UpdateLogDisplay::Entries(shown, more)
}

/// The "Launch as" options `(id, label)`: `""` (the default account), every account, and the
/// stored override when it no longer exists.
pub fn account_options(
    accounts: &[AccountView],
    account_override: Option<&str>,
) -> Vec<(String, String)> {
    let default_name = accounts
        .iter()
        .find(|a| a.is_default)
        .map(|a| a.name.clone())
        .unwrap_or_default();
    let mut out = vec![(
        String::new(),
        tf!("modal.advanced.account.default", default_name),
    )];
    for a in accounts {
        let name = non_blank(&a.name).unwrap_or(a.uuid.as_str());
        let label = if a.status == AccountStatus::NeedsSignIn {
            tf!("account.switcher.needsSignIn", name)
        } else {
            name.to_string()
        };
        out.push((a.uuid.clone(), label));
    }
    if let Some(o) = account_override
        && !accounts.iter().any(|a| a.uuid == o)
    {
        out.push((
            o.to_string(),
            t!("modal.advanced.account.missing").into_owned(),
        ));
    }
    out
}

/// The scan-frequency options `(id, label)`: `""` uses the global setting, then each value by
/// its config name.
pub fn scan_options() -> Vec<(String, String)> {
    let mut out = vec![(
        String::new(),
        t!("modal.advanced.scanFreq.useGlobal").into_owned(),
    )];
    for f in ScanFrequency::ALL {
        out.push((f.name().to_string(), f.label()));
    }
    out
}

/// The stored scan-frequency override as an option id (`""` for none). Accepts the config name
/// (`EVERY_TIME`) or the enum spelling (`EveryTime`).
pub fn scan_selected(stored: Option<&str>) -> String {
    let Some(s) = stored.map(str::trim).filter(|s| !s.is_empty()) else {
        return String::new();
    };
    if let Some(f) = ScanFrequency::from_name(s) {
        return f.name().to_string();
    }
    ScanFrequency::ALL
        .into_iter()
        .find(|f| format!("{f:?}").eq_ignore_ascii_case(s))
        .map(|f| f.name().to_string())
        .unwrap_or_default()
}

/// One quick action.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum QuickAction {
    OpenFolder,
    Mods,
    Config,
    Screenshots,
    ResourcePacks,
    Shaders,
    DesktopShortcut,
    Export,
    CopyInvite,
}

impl QuickAction {
    /// The label key.
    pub fn label_key(self) -> &'static str {
        match self {
            QuickAction::OpenFolder => "modal.quickAction.openFolder",
            QuickAction::Mods => "modal.quickAction.mods",
            QuickAction::Config => "modal.quickAction.config",
            QuickAction::Screenshots => "modal.quickAction.screenshots",
            QuickAction::ResourcePacks => "modal.quickAction.resourcePacks",
            QuickAction::Shaders => "modal.quickAction.shaders",
            QuickAction::DesktopShortcut => "modal.quickAction.desktopShortcut",
            QuickAction::Export => "detailModal.exportPack.label",
            QuickAction::CopyInvite => "modal.quickAction.copyInvite",
        }
    }

    /// The pack sub-folder it opens (`Some("")` = the pack folder itself).
    pub fn folder(self) -> Option<&'static str> {
        match self {
            QuickAction::OpenFolder => Some(""),
            QuickAction::Mods => Some("mods"),
            QuickAction::Config => Some("config"),
            QuickAction::Screenshots => Some("screenshots"),
            QuickAction::ResourcePacks => Some("resourcepacks"),
            QuickAction::Shaders => Some("shaderpacks"),
            _ => None,
        }
    }
}

/// The quick actions, in order (Mods and Config only for modded packs).
pub fn quick_actions(is_vanilla: bool) -> Vec<QuickAction> {
    let mut v = vec![QuickAction::OpenFolder];
    if !is_vanilla {
        v.push(QuickAction::Mods);
        v.push(QuickAction::Config);
    }
    v.extend([
        QuickAction::Screenshots,
        QuickAction::ResourcePacks,
        QuickAction::Shaders,
        QuickAction::DesktopShortcut,
        QuickAction::Export,
        QuickAction::CopyInvite,
    ]);
    v
}

/// A choice of the smart export dialog.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExportChoice {
    ShareUrl,
    ShareManifest,
    Zip,
}

impl ExportChoice {
    /// The button label key.
    pub fn label_key(self) -> &'static str {
        match self {
            ExportChoice::ShareUrl => "modal.share.button.shareUrl",
            ExportChoice::ShareManifest => "modal.share.button.shareManifest",
            ExportChoice::Zip => "modal.share.button.exportZip",
        }
    }
}

/// The export buttons for a mode: Share URL (URL packs), Share manifest (URL or manifest
/// packs), Export ZIP (always). Cancel follows.
pub fn export_choices(mode: ExportModeView) -> Vec<ExportChoice> {
    let mut v = Vec::new();
    if mode == ExportModeView::ShareUrl {
        v.push(ExportChoice::ShareUrl);
    }
    if matches!(
        mode,
        ExportModeView::ShareUrl | ExportModeView::ShareManifestJson
    ) {
        v.push(ExportChoice::ShareManifest);
    }
    v.push(ExportChoice::Zip);
    v
}

/// The export dialog body: the prompt, a blank line, the recommendation for the mode.
pub fn export_body(mode: ExportModeView) -> String {
    let rec = match mode {
        ExportModeView::ShareUrl => t!("modal.share.recommend.url"),
        ExportModeView::ShareManifestJson => t!("modal.share.recommend.manifest"),
        ExportModeView::ExportZip => t!("modal.share.recommend.zip"),
    };
    format!("{}\n\n{}", t!("modal.share.prompt"), rec)
}

/// The suggested manifest file name: `<pack name or modpack>-manifest.json`.
pub fn manifest_file_name(pack_name: &str) -> String {
    let base = non_blank(pack_name).unwrap_or("modpack");
    format!("{base}-manifest.json")
}

/// A server's `host[:port]` (the quick-join argument and the copied address).
pub fn server_address(s: &ServerView) -> String {
    match s.port {
        Some(p) if p > 0 && p != 25565 => format!("{}:{p}", s.host),
        _ => s.host.clone(),
    }
}

/// The servers to list: the saved favourites, then the pack default when it is not already one
/// of them. `true` marks the pack default.
pub fn server_rows(details: &PackDetails) -> Vec<(ServerView, bool)> {
    let mut rows: Vec<(ServerView, bool)> = details
        .servers
        .iter()
        .cloned()
        .map(|s| (s, false))
        .collect();
    if let Some(d) = &details.default_server {
        rows.retain(|(s, _)| server_address(s) != server_address(d));
        rows.insert(0, (d.clone(), true));
    }
    rows
}

fn gcd(a: u64, b: u64) -> u64 {
    if b == 0 { a } else { gcd(b, a % b) }
}

/// The cycle period of a logo list and a background list (their least common multiple, at
/// least 1), so one shared index walks both.
pub fn cycle_period(logos: usize, backgrounds: usize) -> u64 {
    let (a, b) = (logos.max(1) as u64, backgrounds.max(1) as u64);
    a / gcd(a, b) * b
}

/// The image index the hero shows: the shared clock `tick` moved by the ◀ ▶ `offset`, wrapped
/// into `0..period` in both directions.
pub fn hero_tick(tick: u64, offset: i64, period: u64) -> u64 {
    let period = i128::from(period.max(1));
    (i128::from(tick) + i128::from(offset)).rem_euclid(period) as u64
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use mmcl_engine::PackLoadState;

    pub(crate) fn summary() -> PackSummary {
        PackSummary {
            id: PackId::new("https://example.invalid/p.json"),
            name: "Pack".into(),
            friendly_name: "Pack: 1.0".into(),
            version: "1.0".into(),
            installed_version: Some("0.9".into()),
            installed: true,
            update_available: true,
            is_vanilla: false,
            mod_loader: Some("forge".into()),
            minecraft_version: Some("1.20.1".into()),
            unstable: false,
            load_state: PackLoadState::Loaded,
            load_error: None,
            manifest_url: Some("https://example.invalid/p.json".into()),
            logo_url: None,
            logo_count: 0,
            background_url: None,
            background_count: 0,
            min_ram_gb: 4.0,
            last_played_ms: 0,
            total_play_time_ms: 0,
            launch_count: 3,
            never_played: true,
            unread_news: 0,
            active: false,
            website: None,
            logo_path: None,
            custom_discord_rpc: false,
        }
    }

    pub(crate) fn details() -> PackDetails {
        PackDetails {
            summary: summary(),
            root: std::path::PathBuf::from("root"),
            loader_name: Some("Forge".into()),
            loader_version: Some("1.20.1-47.2.0".into()),
            news: vec![],
            update_log: vec![],
            servers: vec![],
            default_server: None,
            account_override: None,
            always_verify_on_launch: false,
            scan_frequency: None,
            has_crash_report: false,
            export_mode: ExportModeView::ShareUrl,
        }
    }

    fn account(uuid: &str, name: &str, default: bool, status: AccountStatus) -> AccountView {
        AccountView {
            uuid: uuid.into(),
            name: name.into(),
            status,
            is_default: default,
            remembered: true,
            last_used_ms: 0,
        }
    }

    #[test]
    fn card_is_ninety_percent_clamped() {
        assert_eq!(card_size((1000.0, 800.0)), (900.0, 720.0));
        assert_eq!(card_size((300.0, 300.0)), CARD_MIN);
        assert_eq!(card_size((4000.0, 3000.0)), CARD_MAX);
        assert_eq!(hero_height(300.0), HERO_MIN);
        assert_eq!(hero_height(2000.0), HERO_MAX);
        assert!((hero_height(720.0) - 259.2).abs() < 0.01);
    }

    #[test]
    fn tabs_follow_the_pack() {
        assert_eq!(tabs(0, false, false), vec![Tab::Overview]);
        assert_eq!(
            tabs(2, true, true),
            vec![
                Tab::Overview,
                Tab::Problems,
                Tab::Content,
                Tab::Activity,
                Tab::Advanced
            ]
        );
        assert_eq!(tabs(0, false, true), vec![Tab::Overview, Tab::Advanced]);
        assert!(has_settings_key(&PackId::vanilla("1.20.1")));
        assert!(!has_settings_key(&PackId::new("name:Pack")));
    }

    #[test]
    fn overview_shows_news_only_with_news() {
        assert_eq!(
            sections(Tab::Overview, false),
            vec![Section::QuickActions, Section::Stats]
        );
        assert_eq!(sections(Tab::Overview, true)[0], Section::News);
        assert_eq!(sections(Tab::Content, false).len(), 6);
        assert!(!Section::Mods.starts_expanded());
        assert!(Section::Worlds.starts_expanded());
    }

    #[test]
    fn ram_amounts() {
        assert_eq!(ram_amount(4.0).as_deref(), Some("4"));
        assert_eq!(ram_amount(2.5).as_deref(), Some("2.5"));
        assert_eq!(ram_amount(0.0), None);
        assert_eq!(ram_amount(-1.0), None);
        assert_eq!(ram_amount(f64::NAN), None);
    }

    #[test]
    fn chips_in_java_order() {
        let s = summary();
        let d = details();
        let accounts = [account("u", "Alex", true, AccountStatus::Ready)];
        let c = chips(&s, Some(&d), Some("u"), &accounts, 12);
        assert_eq!(
            c,
            vec![
                tf!("modal.chip.playsAs", "Alex"),
                tf!("modal.chip.minecraft", "1.20.1"),
                "Forge 47.2.0".to_string(),
                tf!("modal.chip.packVersion", "1.0"),
                tf!("modal.chip.minimumRam", "4"),
                tf!("modal.chip.modCount", 12i64),
            ]
        );
        let missing = chips(&s, None, Some("gone"), &accounts, 0);
        assert_eq!(missing[0], t!("modal.chip.playsAsMissing"));
        let mut same = summary();
        same.version = "1.20.1".into();
        assert!(
            !chips(&same, None, None, &[], 0)
                .iter()
                .any(|c| c == &tf!("modal.chip.packVersion", "1.20.1"))
        );
    }

    #[test]
    fn installed_version_mentions_the_update() {
        let s = summary();
        assert_eq!(
            installed_display(&s).unwrap(),
            tf!(
                "detailModal.stats.installedDisplay.withUpdate",
                "0.9",
                "1.0"
            )
        );
        let mut no_update = summary();
        no_update.update_available = false;
        assert_eq!(installed_display(&no_update).unwrap(), "0.9");
        no_update.installed_version = None;
        assert!(installed_display(&no_update).is_none());
    }

    #[test]
    fn stats_rows_include_manifest_and_never_played() {
        let rows = stats_rows(&summary(), "1 hour".into());
        assert_eq!(rows.len(), 5);
        assert_eq!(rows[0].1, t!("main.card.neverPlayed"));
        assert_eq!(rows[2].1, "3");
        assert_eq!(rows[4].1, "https://example.invalid/p.json");
    }

    #[test]
    fn update_log_caps_at_twelve() {
        let e = |i: usize| UpdateLogView {
            version: format!("{i}"),
            when: String::new(),
        };
        let entries: Vec<_> = (0..15).map(e).collect();
        match update_log(&entries, false) {
            UpdateLogDisplay::Entries(shown, more) => {
                assert_eq!(shown.len(), MAX_LOG_ENTRIES_SHOWN);
                assert_eq!(more, Some(tf!("modal.updateLog.more", 3i64)));
            }
            other => panic!("{other:?}"),
        }
        assert_eq!(
            update_log(&[], true),
            UpdateLogDisplay::Empty(t!("modal.updateLog.empty.vanilla").into_owned())
        );
        match update_log(&entries[..2], false) {
            UpdateLogDisplay::Entries(shown, more) => {
                assert_eq!(shown.len(), 2);
                assert!(more.is_none());
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn account_options_include_a_missing_override() {
        let accounts = [
            account("a", "Alex", true, AccountStatus::Ready),
            account("b", "Steve", false, AccountStatus::NeedsSignIn),
        ];
        let opts = account_options(&accounts, Some("gone"));
        assert_eq!(opts.len(), 4);
        assert_eq!(opts[0].0, "");
        assert_eq!(opts[0].1, tf!("modal.advanced.account.default", "Alex"));
        assert_eq!(opts[2].1, tf!("account.switcher.needsSignIn", "Steve"));
        assert_eq!(
            opts[3],
            (
                "gone".into(),
                t!("modal.advanced.account.missing").into_owned()
            )
        );
        assert_eq!(account_options(&accounts, Some("a")).len(), 3);
    }

    #[test]
    fn scan_options_and_selection() {
        let opts = scan_options();
        assert_eq!(opts.len(), 5);
        assert_eq!(opts[0].0, "");
        assert_eq!(scan_selected(None), "");
        assert_eq!(scan_selected(Some("DAILY")), "DAILY");
        assert_eq!(scan_selected(Some("EveryTime")), "EVERY_TIME");
        assert_eq!(scan_selected(Some("nonsense")), "");
    }

    #[test]
    fn quick_actions_skip_mods_for_vanilla() {
        assert!(!quick_actions(true).contains(&QuickAction::Mods));
        assert_eq!(quick_actions(false).len(), 9);
        assert_eq!(QuickAction::OpenFolder.folder(), Some(""));
        assert_eq!(QuickAction::Export.folder(), None);
    }

    #[test]
    fn export_choices_by_mode() {
        assert_eq!(
            export_choices(ExportModeView::ShareUrl),
            vec![
                ExportChoice::ShareUrl,
                ExportChoice::ShareManifest,
                ExportChoice::Zip
            ]
        );
        assert_eq!(
            export_choices(ExportModeView::ShareManifestJson),
            vec![ExportChoice::ShareManifest, ExportChoice::Zip]
        );
        assert_eq!(
            export_choices(ExportModeView::ExportZip),
            vec![ExportChoice::Zip]
        );
        assert!(export_body(ExportModeView::ExportZip).contains("\n\n"));
        assert_eq!(manifest_file_name(""), "modpack-manifest.json");
        assert_eq!(manifest_file_name("P"), "P-manifest.json");
    }

    #[test]
    fn servers_put_the_pack_default_first_without_duplicates() {
        let s = |n: &str, h: &str, p: Option<i32>| ServerView {
            name: n.into(),
            host: h.into(),
            port: p,
        };
        let mut d = details();
        d.servers = vec![s("A", "a.example", None), s("B", "b.example", Some(25570))];
        d.default_server = Some(s("Default", "a.example", Some(25565)));
        let rows = server_rows(&d);
        assert_eq!(rows.len(), 2);
        assert!(rows[0].1);
        assert_eq!(server_address(&rows[1].0), "b.example:25570");
    }

    #[test]
    fn hero_steps_wrap() {
        assert_eq!(cycle_period(3, 2), 6);
        assert_eq!(cycle_period(0, 0), 1);
        assert_eq!(cycle_period(4, 0), 4);
        assert_eq!(hero_tick(5, 1, 6), 0);
        assert_eq!(hero_tick(0, -1, 3), 2);
        assert_eq!(hero_tick(10, -1, 3), 0);
        // Index 2 of a 3-list stays 2 of 3 whatever the clock did before.
        assert_eq!(hero_tick(u64::MAX, 0, 3) % 3, u64::MAX % 3);
    }

    #[test]
    fn display_name_falls_back() {
        let mut s = summary();
        assert_eq!(display_name(&s), "Pack: 1.0");
        s.friendly_name = " ".into();
        assert_eq!(display_name(&s), "Pack");
        s.name = String::new();
        assert_eq!(display_name(&s), t!("modal.unnamedPack"));
    }
}
