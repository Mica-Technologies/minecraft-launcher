// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The pure parts of the pack detail modal (Java `DetailModalLayout` and friends): sizes, which
//! tabs and sections show, the Optional mods summary counts, the sticky tab bar's scroll
//! arithmetic, the chips, the stats rows, the update log, the Settings tab's options and the
//! export choices.

use mmcl_engine::{
    AccountStatus, AccountView, ExportModeView, PackDetails, PackId, PackSummary,
    PendingUpdateView, ScanFrequency, ServerView, UpdateLogView,
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

/// One tab of the modal, grouped by task (Java `DetailModalLayout`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Tab {
    /// What's new, the optional mods summary, news, links, at a glance, quick actions.
    Overview,
    /// Files the launcher re-downloads on every launch.
    Problems,
    /// Optional mods, then the installed mods.
    Mods,
    /// Worlds, servers, screenshots, shader packs, resource packs.
    Files,
    /// Update log and crash history.
    Activity,
    /// Launch as, game files, the official Minecraft Launcher.
    Settings,
}

impl Tab {
    /// The label key.
    pub fn label_key(self) -> &'static str {
        match self {
            Tab::Overview => "detailModal.tab.overview",
            Tab::Problems => "detailModal.section.problems",
            Tab::Mods => "detailModal.tab.mods",
            Tab::Files => "detailModal.tab.files",
            Tab::Activity => "detailModal.tab.activity",
            Tab::Settings => "detailModal.tab.settings",
        }
    }
}

/// The tabs a pack shows, in order: Overview always, Problems after it when there are problems;
/// Mods, Worlds & files and Activity need an install folder; Settings needs a settings key.
pub fn tabs(has_problems: bool, has_root: bool, has_settings_key: bool) -> Vec<Tab> {
    let mut out = vec![Tab::Overview];
    if has_problems {
        out.push(Tab::Problems);
    }
    if has_root {
        out.push(Tab::Mods);
        out.push(Tab::Files);
        out.push(Tab::Activity);
    }
    if has_settings_key {
        out.push(Tab::Settings);
    }
    out
}

/// The Optional mods summary's numbers: how many optional mods a player can switch here, and
/// how many of those are on.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct OptionalCounts {
    pub available: usize,
    pub on: usize,
}

/// Counts optional mods the way the Mods tab lists them: client-side ones only (a server-only
/// mod gets no switch), each identity key once, and "on" within that set. `mods` yields each
/// mod's identity key and whether it is client-side.
pub fn optional_counts(
    mods: impl IntoIterator<Item = (String, bool)>,
    is_on: impl Fn(&str) -> bool,
) -> OptionalCounts {
    let mut seen = std::collections::HashSet::new();
    let mut on = 0;
    for (key, client_side) in mods {
        if !client_side || !seen.insert(key.clone()) {
            continue;
        }
        if is_on(&key) {
            on += 1;
        }
    }
    OptionalCounts {
        available: seen.len(),
        on,
    }
}

/// How close (px) the scroll offset must be to the tab bar's place to count as pinned.
pub const PIN_TOLERANCE: f32 = 0.5;

/// Whether the tab bar is pinned at a scroll offset: the area has scrolled at least as far as
/// the bar's place in the content (`pin_at`). A bar at the very top (nothing laid out yet) is
/// not pinned.
pub fn is_pinned(offset: f32, pin_at: f32) -> bool {
    pin_at > 0.0 && offset >= pin_at - PIN_TOLERANCE
}

/// Where the scroll area goes after a tab switch: the pinned position when the bar was pinned
/// (so the new tab shows from its top), otherwise where it was, so a switch never jumps back
/// over the hero.
pub fn offset_after_tab_switch(offset: f32, pin_at: f32) -> f32 {
    if is_pinned(offset, pin_at) {
        pin_at
    } else {
        offset.max(0.0)
    }
}

/// Where the scroll area goes when the bar's place moves from `old_pin_at` to `new_pin_at` (the
/// hero follows the window's height; chips and the Problems banner arrive late): a pinned bar
/// stays pinned with the body under it where it was; otherwise the offset is kept.
pub fn offset_after_pin_move(offset: f32, old_pin_at: f32, new_pin_at: f32) -> f32 {
    if old_pin_at > 0.0 && new_pin_at > 0.0 && is_pinned(offset, old_pin_at) {
        (offset + new_pin_at - old_pin_at).max(0.0)
    } else {
        offset
    }
}

/// The body's minimum height: the viewport less the tab bar, rounded up plus a pixel (the
/// viewport is snapped to whole pixels, the content is not), so any tab, however short, can
/// scroll to the pinned position.
pub fn body_min_height(viewport_h: f32, bar_h: f32) -> f32 {
    ((viewport_h - bar_h).ceil() + 1.0).max(0.0)
}

/// Whether a pack id is a settings key (a manifest URL or `vanilla:`), not a `name:` fallback.
pub fn has_settings_key(id: &PackId) -> bool {
    !id.as_str().starts_with("name:")
}

/// A collapsible section of a tab. Every section starts expanded.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Section {
    /// What a pending update changes; headed "What's new in vX".
    WhatsNew,
    /// How many optional mods there are and how many are on, with Choose.
    OptionalSummary,
    News,
    Links,
    Stats,
    QuickActions,
    /// The pack's optional mods, each with a switch.
    OptionalMods,
    Mods,
    Worlds,
    Servers,
    Screenshots,
    ShaderPacks,
    ResourcePacks,
    UpdateLog,
    CrashHistory,
    /// Which account the pack plays as.
    LaunchAs,
    /// Always verify, verify now, scan frequency, acknowledged scan findings.
    GameFiles,
    /// Add to or remove from the official Minecraft Launcher.
    OfficialLauncher,
    Problems,
}

impl Section {
    /// The heading key ([`Section::WhatsNew`] formats it with the pack's version).
    pub fn heading_key(self) -> &'static str {
        match self {
            Section::WhatsNew => "detailModal.whatsNew.header",
            Section::OptionalSummary => "detailModal.optionalSummary.title",
            Section::News => "detailModal.section.news",
            Section::Links => "detailModal.section.links",
            Section::Stats => "detailModal.section.stats",
            Section::QuickActions => "detailModal.section.quickActions",
            Section::OptionalMods => "detailModal.section.optionalMods",
            Section::Mods => "detailModal.section.mods",
            Section::Worlds => "detailModal.section.worlds",
            Section::Servers => "detailModal.section.servers",
            Section::Screenshots => "detailModal.section.screenshots",
            Section::ShaderPacks => "detailModal.section.shaderPacks",
            Section::ResourcePacks => "detailModal.section.resourcePacks",
            Section::UpdateLog => "detailModal.section.updateLog",
            Section::CrashHistory => "detailModal.section.crashHistory",
            Section::LaunchAs => "modal.advanced.account.label",
            Section::GameFiles => "detailModal.section.gameFiles",
            Section::OfficialLauncher => "detailModal.section.officialLauncher",
            Section::Problems => "detailModal.section.problems",
        }
    }

    /// A stable small number for element ids.
    pub fn ordinal(self) -> usize {
        self as usize
    }
}

/// What decides which sections a tab shows.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct SectionFacts {
    /// An update is pending and changes something.
    pub pending_update: bool,
    /// The Overview's Optional mods summary applies: a modded pack with a Mods tab and at
    /// least one optional mod a player can switch.
    pub optional_summary: bool,
    pub news: bool,
    pub links: bool,
    /// The pack has optional mods a player can switch.
    pub optional_mods: bool,
    /// The pack has a manifest URL (game files are verified against it).
    pub manifest: bool,
    /// The pack has an install folder.
    pub root: bool,
}

/// The sections of a tab, in order: Overview = What's new, Optional mods summary, News, Links,
/// At a glance, Quick actions; Mods = Optional mods, then Installed mods; Worlds & files = the
/// folder browsers; Activity = Update log, Crash history; Settings = Launch as, Game files
/// (with a manifest), Official Minecraft Launcher (with an install folder).
pub fn sections(tab: Tab, f: SectionFacts) -> Vec<Section> {
    let mut v = Vec::new();
    match tab {
        Tab::Overview => {
            if f.pending_update {
                v.push(Section::WhatsNew);
            }
            if f.optional_summary {
                v.push(Section::OptionalSummary);
            }
            if f.news {
                v.push(Section::News);
            }
            if f.links {
                v.push(Section::Links);
            }
            v.push(Section::Stats);
            v.push(Section::QuickActions);
        }
        Tab::Problems => v.push(Section::Problems),
        Tab::Mods => {
            if f.optional_mods {
                v.push(Section::OptionalMods);
            }
            if f.root {
                v.push(Section::Mods);
            }
        }
        Tab::Files => v.extend([
            Section::Worlds,
            Section::Servers,
            Section::Screenshots,
            Section::ShaderPacks,
            Section::ResourcePacks,
        ]),
        Tab::Activity => v.extend([Section::UpdateLog, Section::CrashHistory]),
        Tab::Settings => {
            v.push(Section::LaunchAs);
            if f.manifest {
                v.push(Section::GameFiles);
            }
            if f.root {
                v.push(Section::OfficialLauncher);
            }
        }
    }
    v
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

/// How many names a "What's new" line lists before "(+N more)".
pub const WHATS_NEW_NAME_CAP: usize = 6;

/// The text of the Overview's "What's new" section (Java `buildPendingChangesNode`), shown
/// while an update is pending; `header` is the section's heading.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WhatsNew {
    /// "What's new in v1.2".
    pub header: String,
    /// The counts: "3 added  ·  1 updated  ·  2 new optional".
    pub summary: String,
    /// The muted example-name lines: added, updated, removed, new optional mods.
    pub lines: Vec<String>,
    /// The muted footnote: an approximation, applied on the next launch.
    pub note: String,
}

/// The "What's new" card for a pack whose manifest is at `version`. Added and updated names
/// that are optional mods the player has on carry "(optional)"; removed names never do (an
/// optional jar is never reported removed), and new optional mods are listed apart.
pub fn whats_new(version: &str, d: &PendingUpdateView) -> WhatsNew {
    let version = if version.trim().is_empty() {
        "?"
    } else {
        version
    };
    let mut parts = Vec::new();
    for (key, list) in [
        ("detailModal.whatsNew.added", &d.added),
        ("detailModal.whatsNew.updated", &d.updated),
        ("detailModal.whatsNew.removed", &d.removed),
        ("detailModal.whatsNew.newOptional", &d.new_optional_names),
    ] {
        if !list.is_empty() {
            parts.push(tf!(key, list.len() as i64));
        }
    }
    let tag = |names: &[String]| -> Vec<String> {
        names
            .iter()
            .map(|n| {
                if d.optional_names.contains(n) {
                    tf!("detailModal.whatsNew.optionalTag", n.as_str())
                } else {
                    n.clone()
                }
            })
            .collect()
    };
    let lines = [
        ("detailModal.whatsNew.addedNames", tag(&d.added)),
        ("detailModal.whatsNew.updatedNames", tag(&d.updated)),
        ("detailModal.whatsNew.removedNames", d.removed.clone()),
        (
            "detailModal.whatsNew.newOptionalNames",
            d.new_optional_names.clone(),
        ),
    ]
    .into_iter()
    .filter(|(_, names)| !names.is_empty())
    .map(|(key, names)| tf!(key, example_names(&names).as_str()))
    .collect();
    WhatsNew {
        header: tf!("detailModal.whatsNew.header", version),
        summary: parts.join("  \u{b7}  "),
        lines,
        note: t!("detailModal.whatsNew.note").into_owned(),
    }
}

/// Up to [`WHATS_NEW_NAME_CAP`] names joined with ", ", then " (+N more)" when longer.
fn example_names(names: &[String]) -> String {
    let mut joined = names
        .iter()
        .take(WHATS_NEW_NAME_CAP)
        .map(String::as_str)
        .collect::<Vec<_>>()
        .join(", ");
    if names.len() > WHATS_NEW_NAME_CAP {
        joined.push_str(&tf!(
            "detailModal.whatsNew.more",
            (names.len() - WHATS_NEW_NAME_CAP) as i64
        ));
    }
    joined
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
            links: vec![],
            scan_acknowledgements: vec![],
            update_log: vec![],
            pending_update: None,
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

    // Ports of Java `DetailModalLayoutTest`.

    #[test]
    fn installed_pack_gets_every_task_tab_in_order() {
        assert_eq!(
            tabs(false, true, true),
            vec![
                Tab::Overview,
                Tab::Mods,
                Tab::Files,
                Tab::Activity,
                Tab::Settings
            ]
        );
    }

    #[test]
    fn problems_follow_overview() {
        assert_eq!(
            tabs(true, true, true),
            vec![
                Tab::Overview,
                Tab::Problems,
                Tab::Mods,
                Tab::Files,
                Tab::Activity,
                Tab::Settings
            ]
        );
    }

    #[test]
    fn pack_without_folder_gets_overview_and_settings() {
        assert_eq!(tabs(false, false, true), vec![Tab::Overview, Tab::Settings]);
    }

    #[test]
    fn pack_with_neither_folder_nor_settings_key_is_overview_only() {
        assert_eq!(tabs(false, false, false), vec![Tab::Overview]);
        assert_eq!(tabs(true, false, false), vec![Tab::Overview, Tab::Problems]);
    }

    #[test]
    fn pack_with_folder_but_no_settings_key_has_no_settings_tab() {
        assert_eq!(
            tabs(false, true, false),
            vec![Tab::Overview, Tab::Mods, Tab::Files, Tab::Activity]
        );
        assert!(has_settings_key(&PackId::vanilla("1.20.1")));
        assert!(!has_settings_key(&PackId::new("name:Pack")));
    }

    fn opt(key: &str, client: bool) -> (String, bool) {
        (key.to_string(), client)
    }

    #[test]
    fn counts_only_client_side_mods_once_each() {
        let mods = [
            opt("a.jar", true),
            opt("b.jar", true),
            opt("server-only.jar", false),
            opt("a.jar", true),
        ];
        let on = ["a.jar", "server-only.jar"];
        let counts = optional_counts(mods, |k| on.contains(&k));
        assert_eq!(
            counts,
            OptionalCounts {
                available: 2,
                on: 1
            }
        );
    }

    #[test]
    fn counts_tolerate_missing_inputs() {
        assert_eq!(
            optional_counts(Vec::new(), |_| true),
            OptionalCounts::default()
        );
        assert_eq!(
            optional_counts([opt("a.jar", true)], |_| false),
            OptionalCounts {
                available: 1,
                on: 0
            }
        );
    }

    #[test]
    fn tab_bar_pins_once_scrolled_to_it() {
        assert!(!is_pinned(0.0, 300.0));
        assert!(!is_pinned(299.0, 300.0));
        assert!(is_pinned(299.6, 300.0));
        assert!(is_pinned(300.0, 300.0));
        assert!(is_pinned(900.0, 300.0));
        // A bar at the very top of the content (no hero laid out yet) is not "pinned".
        assert!(!is_pinned(0.0, 0.0));
    }

    #[test]
    fn tab_switch_keeps_a_pinned_bar_pinned_at_the_new_tabs_top() {
        assert_eq!(offset_after_tab_switch(1200.0, 300.0), 300.0);
        assert_eq!(offset_after_tab_switch(300.0, 300.0), 300.0);
    }

    #[test]
    fn tab_switch_leaves_an_unpinned_position_alone() {
        assert_eq!(offset_after_tab_switch(0.0, 300.0), 0.0);
        assert_eq!(offset_after_tab_switch(180.0, 300.0), 180.0);
        assert_eq!(offset_after_tab_switch(-5.0, 300.0), 0.0);
    }

    #[test]
    fn a_moved_tab_bar_keeps_a_pinned_body_in_place() {
        // The hero grew by 40 px while pinned 200 px past the bar: still 200 px past it.
        assert_eq!(offset_after_pin_move(500.0, 300.0, 340.0), 540.0);
        // Not pinned: the offset is kept.
        assert_eq!(offset_after_pin_move(100.0, 300.0, 340.0), 100.0);
        // Nothing measured yet: kept.
        assert_eq!(offset_after_pin_move(500.0, 0.0, 340.0), 500.0);
    }

    #[test]
    fn the_body_is_tall_enough_to_reach_the_pinned_position() {
        assert_eq!(body_min_height(600.0, 58.0), 543.0);
        assert_eq!(body_min_height(600.4, 58.0), 544.0);
        assert_eq!(body_min_height(30.0, 58.0), 0.0);
    }

    #[test]
    fn sections_are_grouped_by_task() {
        let none = SectionFacts::default();
        assert_eq!(
            sections(Tab::Overview, none),
            vec![Section::Stats, Section::QuickActions]
        );
        let all = SectionFacts {
            pending_update: true,
            optional_summary: true,
            news: true,
            links: true,
            optional_mods: true,
            manifest: true,
            root: true,
        };
        assert_eq!(
            sections(Tab::Overview, all),
            vec![
                Section::WhatsNew,
                Section::OptionalSummary,
                Section::News,
                Section::Links,
                Section::Stats,
                Section::QuickActions
            ]
        );
        assert_eq!(
            sections(Tab::Mods, all),
            vec![Section::OptionalMods, Section::Mods]
        );
        assert_eq!(
            sections(Tab::Mods, SectionFacts { root: true, ..none }),
            vec![Section::Mods]
        );
        assert_eq!(sections(Tab::Files, none).len(), 5);
        assert!(!sections(Tab::Files, none).contains(&Section::Mods));
        assert_eq!(
            sections(Tab::Activity, none),
            vec![Section::UpdateLog, Section::CrashHistory]
        );
        assert_eq!(
            sections(Tab::Settings, all),
            vec![
                Section::LaunchAs,
                Section::GameFiles,
                Section::OfficialLauncher
            ]
        );
        // A vanilla install has no manifest: the account choice, and the official launcher
        // once installed.
        assert_eq!(sections(Tab::Settings, none), vec![Section::LaunchAs]);
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

    fn names(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn whats_new_counts_each_kind_and_tags_optional_mods() {
        let d = PendingUpdateView {
            added: names(&["newmod", "lib"]),
            updated: names(&["jei"]),
            removed: names(&["old"]),
            optional_names: names(&["lib"]),
            new_optional_names: names(&["AUSM"]),
        };
        let w = whats_new("1.2", &d);
        assert_eq!(w.header, tf!("detailModal.whatsNew.header", "1.2"));
        assert_eq!(
            w.summary,
            [
                tf!("detailModal.whatsNew.added", 2i64),
                tf!("detailModal.whatsNew.updated", 1i64),
                tf!("detailModal.whatsNew.removed", 1i64),
                tf!("detailModal.whatsNew.newOptional", 1i64),
            ]
            .join("  \u{b7}  ")
        );
        let lib = tf!("detailModal.whatsNew.optionalTag", "lib");
        assert_eq!(
            w.lines,
            [
                tf!(
                    "detailModal.whatsNew.addedNames",
                    format!("newmod, {lib}").as_str()
                ),
                tf!("detailModal.whatsNew.updatedNames", "jei"),
                tf!("detailModal.whatsNew.removedNames", "old"),
                tf!("detailModal.whatsNew.newOptionalNames", "AUSM"),
            ]
        );
        assert_eq!(w.note, t!("detailModal.whatsNew.note"));
    }

    #[test]
    fn whats_new_leaves_out_empty_kinds_and_names_a_missing_version() {
        let d = PendingUpdateView {
            new_optional_names: names(&["AUSM"]),
            ..Default::default()
        };
        let w = whats_new(" ", &d);
        assert_eq!(w.header, tf!("detailModal.whatsNew.header", "?"));
        assert_eq!(w.summary, tf!("detailModal.whatsNew.newOptional", 1i64));
        assert_eq!(
            w.lines,
            [tf!("detailModal.whatsNew.newOptionalNames", "AUSM")]
        );
    }

    #[test]
    fn whats_new_caps_each_line_at_six_names() {
        let many = names(&["a", "b", "c", "d", "e", "f", "g", "h"]);
        let d = PendingUpdateView {
            removed: many,
            ..Default::default()
        };
        let more = tf!("detailModal.whatsNew.more", 2i64);
        assert_eq!(
            whats_new("2", &d).lines,
            [tf!(
                "detailModal.whatsNew.removedNames",
                format!("a, b, c, d, e, f{more}").as_str()
            )]
        );
        // The English "more" keeps its escaped leading space.
        assert_eq!(
            mmcl_i18n::english("detailModal.whatsNew.more"),
            Some(" (+{0} more)")
        );
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
