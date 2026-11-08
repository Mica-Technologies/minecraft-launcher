// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! View models: plain, cloneable snapshots the front ends render. None of them holds a token;
//! account UUIDs and paths appear only where a screen needs them (MCP narrows further).

use std::fmt;
use std::path::PathBuf;

use serde::Serialize;

use mmcl_modpack::GameModPack;

/// A pack's stable id: its settings key (the manifest URL, or `vanilla:<version>`), else
/// `name:<pack name>` for packs that have neither.
#[derive(Clone, Debug, Default, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize)]
pub struct PackId(pub String);

impl PackId {
    /// Wraps a raw id.
    pub fn new(id: impl Into<String>) -> PackId {
        PackId(id.into())
    }

    /// The id of `pack` (`GameSession.keyFor`).
    pub fn of(pack: &GameModPack) -> PackId {
        PackId(
            pack.settings_key()
                .unwrap_or_else(|| format!("name:{}", pack.name())),
        )
    }

    /// The vanilla pseudo-pack of a Minecraft version.
    pub fn vanilla(mc_version: &str) -> PackId {
        PackId(format!("vanilla:{mc_version}"))
    }

    /// The raw id.
    pub fn as_str(&self) -> &str {
        &self.0
    }

    /// The Minecraft version when this is a vanilla id.
    pub fn vanilla_version(&self) -> Option<&str> {
        self.0.strip_prefix("vanilla:")
    }
}

impl fmt::Display for PackId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

/// How a pack object was loaded.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
pub enum PackLoadState {
    /// Fully loaded manifest.
    #[default]
    Loaded,
    /// Card fields only (from the install index); a full load is pending.
    Stub,
    /// The manifest could not be loaded; see [`PackSummary::load_error`].
    Failed,
}

/// One pack card (library grid, browse grid, TUI list, MCP listing).
#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct PackSummary {
    pub id: PackId,
    /// `packName`.
    pub name: String,
    /// The display name (`name: version` style); falls back to `name`.
    pub friendly_name: String,
    /// The published version (`packVersion`).
    pub version: String,
    /// The version installed on disk, when known.
    pub installed_version: Option<String>,
    pub installed: bool,
    pub update_available: bool,
    pub is_vanilla: bool,
    /// `forge` / `neoforge` / `fabric`; `None` for vanilla.
    pub mod_loader: Option<String>,
    /// The Minecraft version from the manifest, when it states one.
    pub minecraft_version: Option<String>,
    pub unstable: bool,
    pub load_state: PackLoadState,
    pub load_error: Option<String>,
    pub manifest_url: Option<String>,
    pub logo_url: Option<String>,
    pub logo_count: usize,
    pub background_url: Option<String>,
    pub background_count: usize,
    pub min_ram_gb: f64,
    pub last_played_ms: i64,
    pub total_play_time_ms: i64,
    pub launch_count: i32,
    pub never_played: bool,
    pub unread_news: usize,
    /// A game of this pack is preparing or running.
    pub active: bool,
    /// The pack's website (`packURL`), when it declares one.
    pub website: Option<String>,
    /// The primary logo image in the image cache, when it is cached on disk (no network).
    /// See [`crate::Engine::pack_images`] for every image.
    pub logo_path: Option<PathBuf>,
    /// `packCustomDiscordRpc`: the pack drives its own Discord presence, so the launcher's
    /// game presence stands back.
    pub custom_discord_rpc: bool,
}

/// A pack's cached images ([`crate::Engine::pack_images`] / [`crate::Engine::cache_pack_images`]):
/// every logo and background file that exists on disk, primary first, for cycling.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct PackImagesView {
    pub logos: Vec<PathBuf>,
    pub backgrounds: Vec<PathBuf>,
    /// The last [`crate::Engine::cache_pack_images`] of this pack object succeeded (every
    /// declared image was resolved). `false` before the first cache run.
    pub cached: bool,
}

/// A Modrinth modpack project, fetched without installing it ([`crate::Engine::preview_modrinth`]),
/// for the import-confirm dialog. Pass it back to [`crate::Engine::install_modrinth`].
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct ModrinthProjectView {
    pub project_id: Option<String>,
    /// The slug the URL named (or the project's own).
    pub slug: String,
    pub title: Option<String>,
    pub description: Option<String>,
    pub icon_url: Option<String>,
    /// The version that would be installed (the URL's version, else the newest).
    pub version_id: Option<String>,
    pub version_name: Option<String>,
    pub version_number: Option<String>,
    /// Minecraft versions of that version (else the project's).
    pub game_versions: Vec<String>,
    /// Loaders of that version (else the project's).
    pub loaders: Vec<String>,
    /// The `.mrpack` download; `None` when the version has none (install then fails).
    pub mrpack_url: Option<String>,
}

/// The official-launcher export pre-checks of a pack ([`crate::Engine::official_export_status`]).
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct OfficialExportStatus {
    /// The official launcher's data folder with its `launcher_profiles.json` exists.
    pub available: bool,
    /// Its `.minecraft` folder (shown when it is missing).
    pub dot_minecraft: PathBuf,
    /// The version id the profile uses (`1.20.1`, `1.20.1-forge-47.3.0`, `fabric-loader-...`).
    pub version_id: String,
    /// That version is installed in the official launcher (always `true` for vanilla).
    pub loader_installed: bool,
    /// The pack already has a profile there.
    pub has_export: bool,
    /// The profile's display name.
    pub profile_name: String,
    /// The pack is a vanilla version (no loader to install, no files to copy).
    pub vanilla: bool,
}

/// How an official-launcher export ended ([`crate::Engine::export_official`]).
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct OfficialExportOutcome {
    /// The profile's display name.
    pub profile_name: String,
    /// The version id the profile uses.
    pub version_id: String,
    /// `false` when the profile was written but the loader version is still missing there.
    pub loader_installed: bool,
}

/// The result of the manifest diagnostic ([`crate::Engine::manifest_diagnostic`]).
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct ManifestDiagnostic {
    /// The `[manifest-diag] ...` lines, in order (English, like Java's developer output).
    pub lines: Vec<String>,
    /// 0 = the library manifest was resolved once and memoized, 1 = the second resolve
    /// rebuilt it, 2 = the manifest could not be fetched or resolved.
    pub exit_code: i32,
}

/// A news item of a pack.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct PackNewsView {
    pub id: Option<String>,
    pub title: Option<String>,
    pub body: Option<String>,
    pub date: Option<String>,
    pub url: Option<String>,
}

/// One entry of the pack's update log (newest first).
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct UpdateLogView {
    pub version: String,
    pub when: String,
}

/// A saved server of a pack.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct ServerView {
    pub name: String,
    pub host: String,
    pub port: Option<i32>,
}

/// The detail modal / editor header of one pack.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct PackDetails {
    pub summary: PackSummary,
    /// The install folder.
    pub root: PathBuf,
    pub loader_name: Option<String>,
    pub loader_version: Option<String>,
    pub news: Vec<PackNewsView>,
    pub update_log: Vec<UpdateLogView>,
    pub servers: Vec<ServerView>,
    pub default_server: Option<ServerView>,
    /// The account UUID this pack launches as (its override), if set.
    pub account_override: Option<String>,
    pub always_verify_on_launch: bool,
    /// `EveryTime`, `Daily`, ... as the config stores it; `None` = the default.
    pub scan_frequency: Option<String>,
    /// Whether the newest crash report exists.
    pub has_crash_report: bool,
    /// How this pack can be shared (URL, manifest JSON, or a zip export).
    pub export_mode: ExportModeView,
}

/// How a pack can be shared ([`mmcl_modpack::export::ExportMode`]).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub enum ExportModeView {
    ShareUrl,
    ShareManifestJson,
    ExportZip,
}

/// Disk use of a pack (bounded walk).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub struct PackFootprint {
    pub size_bytes: u64,
    pub world_count: u32,
    /// The walk stopped early (time or entry budget, unreadable folder).
    pub approximate: bool,
}

/// One signed-in account.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct AccountView {
    pub uuid: String,
    /// `""` when unknown.
    pub name: String,
    pub status: AccountStatus,
    pub is_default: bool,
    pub remembered: bool,
    pub last_used_ms: i64,
}

/// An account's state ([`mmcl_auth::Status`]).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub enum AccountStatus {
    Ready,
    Refreshing,
    NeedsSignIn,
}

impl AccountStatus {
    /// The localization key of the status line.
    pub fn l10n_key(self) -> &'static str {
        match self {
            AccountStatus::Ready => "account.status.signedIn",
            AccountStatus::Refreshing => "account.status.refreshing",
            AccountStatus::NeedsSignIn => "account.status.needsSignIn",
        }
    }
}

impl From<&mmcl_auth::AccountInfo> for AccountView {
    fn from(a: &mmcl_auth::AccountInfo) -> Self {
        AccountView {
            uuid: a.uuid.clone(),
            name: a.display_name.clone(),
            status: match a.status {
                mmcl_auth::Status::Ready => AccountStatus::Ready,
                mmcl_auth::Status::Refreshing => AccountStatus::Refreshing,
                mmcl_auth::Status::NeedsSignIn => AccountStatus::NeedsSignIn,
            },
            is_default: a.is_default,
            remembered: a.remembered,
            last_used_ms: a.last_used_ms,
        }
    }
}

/// One installed Java runtime.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct RuntimeView {
    /// The component (`java-runtime-gamma`, `jre-legacy`, ...).
    pub component: String,
    /// The `.version` marker; `None` when unknown.
    pub version: Option<String>,
    pub path: PathBuf,
    pub size_mb: u64,
    /// A running game uses it (deleting fails).
    pub in_use: bool,
}

/// One backup archive of a pack.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct BackupView {
    pub path: PathBuf,
    pub file_name: String,
    pub size_bytes: u64,
    pub modified_ms: i64,
}

/// What [`crate::Engine::export_pack`] writes.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ExportRequest {
    /// A zip of the pack folder (minus caches), optionally with `saves`.
    Zip { dest: PathBuf, include_saves: bool },
    /// The manifest JSON only.
    ManifestJson { dest: PathBuf },
    /// A profile in the official Minecraft launcher.
    OfficialLauncher,
}

/// What [`crate::Engine::import`] reads.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ImportRequest {
    /// A pasted URL (Modrinth / CurseForge / Technic project page, or a Mica manifest URL).
    Url(String),
    /// A `.mrpack` file.
    MrpackFile(PathBuf),
    /// A zip (Mica export, Technic server zip, CurseForge zip).
    ZipFile(PathBuf),
    /// A `.mmcjson` / `.json` manifest file.
    ManifestFile(PathBuf),
    /// A Prism / MultiMC instance folder.
    PrismInstance(PathBuf),
}

/// Where a pasted URL points ([`mmcl_modpack::import::classifier::ImportSource`]).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub enum ImportSourceView {
    Modrinth,
    CurseForge,
    Technic,
    Mica,
    Unknown,
}

/// A finished import.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct ImportOutcome {
    /// The registered pack.
    pub pack: PackId,
    /// Mods brought in, when the importer counts them.
    pub mod_count: Option<usize>,
}

/// One Modrinth search hit (editor "add a mod", browse import).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct ModSearchHit {
    pub project_id: Option<String>,
    pub slug: Option<String>,
    pub title: Option<String>,
    pub description: Option<String>,
    pub icon_url: Option<String>,
    pub author: Option<String>,
    pub downloads: i64,
}

impl From<mmcl_modpack::import::modrinth::SearchHit> for ModSearchHit {
    fn from(h: mmcl_modpack::import::modrinth::SearchHit) -> Self {
        ModSearchHit {
            project_id: h.project_id,
            slug: h.slug,
            title: h.title,
            description: h.description,
            icon_url: h.icon_url,
            author: h.author,
            downloads: h.downloads,
        }
    }
}

/// A resolved downloadable mod file.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct ModFileView {
    pub filename: Option<String>,
    pub url: String,
    pub sha1: Option<String>,
    pub sha512: Option<String>,
    pub size: i64,
    pub version_number: Option<String>,
}

/// One loader version (editor picker, quick-start wizard).
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct LoaderVersionView {
    /// `forge`, `neoforge`, `fabric`.
    pub loader_type: String,
    pub minecraft_version: String,
    pub loader_version: String,
    pub display_name: String,
    pub installed: bool,
}

/// One vanilla Minecraft version.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct VanillaVersionView {
    pub id: String,
    /// `release`, `snapshot`, `old_beta`, `old_alpha`.
    pub kind: String,
    pub release_time: String,
    pub installed: bool,
}

/// A crash diagnosis, already localized.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct CrashView {
    /// `OutOfMemory`, `JavaVersion`, ... (the enum name).
    pub category: String,
    pub severity: CrashSeverity,
    pub title: String,
    pub summary: String,
    pub suggestions: Vec<CrashSuggestionView>,
    /// The raw (redacted) report text the diagnosis read.
    pub report: Option<String>,
}

/// How serious a crash is.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub enum CrashSeverity {
    Critical,
    Warning,
    Info,
}

/// One suggested fix. `action` is `None` for a hint with no button.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct CrashSuggestionView {
    pub label: String,
    pub primary: bool,
    #[serde(skip)]
    pub action: Option<mmcl_crash::CrashAction>,
}

/// The RGB status chip on Settings → RGB.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct RgbStatusView {
    pub enabled: bool,
    pub running: bool,
    pub text: String,
    pub tone: ChipTone,
}

/// Status chip colour.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub enum ChipTone {
    Muted,
    Success,
    Warn,
    Danger,
}

/// The launcher-wide status line (MCP `get_launcher_status`, TUI header).
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct LauncherStatus {
    pub launcher_version: String,
    pub display_name: String,
    pub is_dev: bool,
    pub signed_in: bool,
    /// The default account's name only.
    pub username: Option<String>,
    pub installed_pack_count: usize,
    pub offline: bool,
    pub server_mode: bool,
}

/// The announcement banners.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct AnnouncementsView {
    pub home: String,
    pub login: String,
    pub settings: String,
    pub modpacks_edit: String,
    pub disable_gameplay: bool,
    pub disable_modpacks_edit: bool,
}

/// A newer launcher release.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct UpdateInfo {
    pub version: String,
    pub url: String,
    pub notes: Option<String>,
}

/// Progress of the malware scan (Settings → Security).
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct ScanProgress {
    /// 0..1, negative while the total is unknown.
    pub fraction: f64,
    /// The latest output line (ANSI codes stripped).
    pub line: String,
}

/// The result of a malware scan.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct ScanSummary {
    pub stage1: usize,
    pub stage2: usize,
    pub high: usize,
    pub medium: usize,
    pub cancelled: bool,
}

/// System memory (Settings → Game).
#[derive(Clone, Copy, Debug, PartialEq, Serialize)]
pub struct SystemMemory {
    pub total_gb: f64,
    pub used_gb: f64,
}

/// A known mod conflict in a pack's mods folder (Java `ModConflictDetector`), localized. The
/// front end asks before launching: disable one jar ([`crate::Engine::disable_mod_jar`]),
/// launch anyway, or cancel (dialog keys `launch.modConflict.*`).
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct ModConflictView {
    pub title: String,
    pub description: String,
    /// The jar file names (in `mods/`) of the two sides.
    pub first_jar: String,
    pub second_jar: String,
}

/// Names an import waiting for confirmation ([`crate::Engine::prepare_import`]); pass it to
/// [`crate::Engine::commit_import`] or [`crate::Engine::discard_import`].
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize)]
pub struct ImportToken(pub u64);

/// What a prepared import reads from.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub enum ImportPreviewSource {
    /// A Modrinth project page (its `.mrpack` was downloaded and read).
    ModrinthProject,
    /// A local `.mrpack` file.
    MrpackFile,
    /// A Mica manifest URL (installed as is on confirm; no file list).
    MicaUrl,
    /// A Mica export / Technic server zip (read on confirm; no file list).
    ZipFile,
    /// A `.mmcjson` / `.json` manifest file (copied on confirm; no file list).
    ManifestFile,
    /// A Prism / MultiMC instance folder (copied on confirm; no file list).
    PrismInstance,
}

/// The folder a `.mrpack` file goes to, by path prefix (Java's import-confirm totals).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize)]
pub enum ImportFileCategory {
    Mod,
    Config,
    ResourcePack,
    ShaderPack,
    Other,
}

impl ImportFileCategory {
    /// The category of a pack-relative path (`mods/`, `config/`, `resourcepacks/`,
    /// `shaderpacks/`, ASCII case-insensitive, `\` read as `/`).
    pub fn of_path(path: &str) -> ImportFileCategory {
        let p = path.replace('\\', "/");
        let starts = |prefix: &str| {
            p.len() >= prefix.len()
                && p.is_char_boundary(prefix.len())
                && p[..prefix.len()].eq_ignore_ascii_case(prefix)
        };
        if starts("mods/") {
            ImportFileCategory::Mod
        } else if starts("config/") {
            ImportFileCategory::Config
        } else if starts("resourcepacks/") {
            ImportFileCategory::ResourcePack
        } else if starts("shaderpacks/") {
            ImportFileCategory::ShaderPack
        } else {
            ImportFileCategory::Other
        }
    }
}

/// One file of a prepared import (a `modrinth.index.json` entry).
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct ImportFileView {
    /// The pack-relative path (`mods/sodium.jar`).
    pub path: String,
    /// The file name (`sodium.jar`), what Java's dialog listed.
    pub file_name: String,
    /// Download size in bytes (0 when the index has none).
    pub size: u64,
    pub category: ImportFileCategory,
    /// `false` for files marked client-unsupported (server-only); the launcher skips them.
    pub client_supported: bool,
}

/// The totals line of the import-confirm dialog: counts per category and the summed size of
/// every file, as Java computed them (every entry with a path).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
pub struct ImportTotals {
    pub mods: usize,
    pub configs: usize,
    pub resource_packs: usize,
    pub shader_packs: usize,
    pub other: usize,
    pub total_bytes: u64,
}

impl ImportTotals {
    /// The totals of `files`.
    pub fn of(files: &[ImportFileView]) -> ImportTotals {
        let mut t = ImportTotals::default();
        for f in files {
            t.total_bytes = t.total_bytes.saturating_add(f.size);
            match f.category {
                ImportFileCategory::Mod => t.mods += 1,
                ImportFileCategory::Config => t.configs += 1,
                ImportFileCategory::ResourcePack => t.resource_packs += 1,
                ImportFileCategory::ShaderPack => t.shader_packs += 1,
                ImportFileCategory::Other => t.other += 1,
            }
        }
        t
    }

    /// Every file counted.
    pub fn file_count(&self) -> usize {
        self.mods + self.configs + self.resource_packs + self.shader_packs + self.other
    }
}

/// An import read and checked but not installed yet: what the import-confirm dialog shows
/// (Java `MCLauncherImportConfirmDialog`: name, version, Minecraft and loader versions, the
/// mod list with sizes and the totals). Confirm with [`crate::Engine::commit_import`], cancel
/// with [`crate::Engine::discard_import`].
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct ImportPreview {
    pub token: ImportToken,
    pub source: ImportPreviewSource,
    /// `true` when the fields below were read from the archive itself (Modrinth, `.mrpack`);
    /// `false` for sources read only on confirm (the dialog then shows the source alone).
    pub has_details: bool,
    /// The pack name (`None`: show `dialog.importConfirm.fallbackName`).
    pub pack_name: Option<String>,
    /// The pack version as the manifest will carry it.
    pub pack_version: Option<String>,
    pub minecraft_version: Option<String>,
    /// `forge`, `neoforge` or `fabric`.
    pub loader_type: Option<String>,
    pub loader_version: Option<String>,
    /// The index's summary line.
    pub summary: Option<String>,
    /// The Modrinth project icon.
    pub icon_url: Option<String>,
    /// Every file of the index, in index order (filter by `category` for the mod list).
    pub files: Vec<ImportFileView>,
    pub totals: ImportTotals,
    /// What the dialog names when there is no pack name: the file, folder or URL.
    pub source_label: String,
}

impl ImportPreview {
    /// The mod rows (Java listed only `mods/` entries).
    pub fn mods(&self) -> impl Iterator<Item = &ImportFileView> {
        self.files
            .iter()
            .filter(|f| f.category == ImportFileCategory::Mod)
    }
}

/// [`crate::Engine::export_official_report`]: the outcome plus the loader installer's error.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct OfficialExportReport {
    pub outcome: OfficialExportOutcome,
    /// The localized `officialExport.installLoader.failed.body` text when the loader install
    /// ran and failed (Java's `installLoader.failed` toast body).
    pub loader_install_error: Option<String>,
    /// The installer's own error text from that failure (`{0}` of the body).
    pub loader_installer_message: Option<String>,
}
