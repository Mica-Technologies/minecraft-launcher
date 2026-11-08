# mmcl-engine API

The one facade every front end (GUI, TUI, MCP, server mode) uses. `Engine` is `Clone` (an `Arc`
inside). Async methods run on the engine's tokio runtime; from GPUI, wrap a call in
`engine.spawn(async move { e.op(..).await })` and await the returned `EngineTask` (output
`Result<T, TaskAborted>`). Errors are `EngineError`; `err.message()` is the localized text,
`err.l10n_key()` / `err.l10n_args()` the key and arguments.

Status column: every operation is **real** (implemented over the lower crates).

Additive API since the first draft: `EngineOptions::{tui, follow_running_games}`,
`is_authored_pack`, `hardware_tuning_summary`, `official_launcher_available`,
`has_official_export`, `remove_official_export`, and the pure helpers
`engine::authoring::{resolve_authored_manifest, unsafe_local_path}` and
`engine::official_export::*`.

Added for the engine-side gaps (see "Engine-side gap APIs" below for details): `EngineOptions::{http,
net_hooks, offline_for_tests}`, `OfflineHttp`, `start_presence`, `http`, `installed_modpacks`,
`find_available_by_name`, `manifest_text`, `pack_images`, `cache_pack_images`,
`install_loader_version`, `preview_modrinth`, `install_modrinth`, `official_export_status`,
`install_official_loader`, `export_official`, the `*_with(.., OperationId)` progress variants,
`manifest_diagnostic` and `diag::run_manifest_diagnostic`; `PackSummary::{website, logo_path,
custom_discord_rpc}` and `Default` for `PackSummary` / `PackId` / `PackLoadState`.

## Lifecycle

| Method | Kind | Status | Notes |
|---|---|---|---|
| `Engine::new(EngineOptions) -> anyhow::Result<Engine>` | sync | real | Builds/uses the runtime, opens config, `AuthService`, `ModPackManager`, `RuntimeManager`, wires callbacks onto the bus |
| `EngineOptions::new(paths)` / `::for_mode(mode)` / `::offline_for_tests(paths, handle)` | sync | real | `runtime: RuntimeChoice::{Own{worker_threads}, Handle(h)}`, `event_capacity`, `web_view_supported`, `start_background_work`, `tui`, `follow_running_games`, `http: Option<Arc<dyn PackHttp>>` (None = real network), `net_hooks` (proxy provider + battery throttle, default on) |
| `start_presence()` | sync | real | Installs the RGB / Discord hosts and bootstraps RGB once; called by `start_background_work` and `run_server` |
| `http() -> Arc<dyn PackHttp>` | sync | real | The network the engine uses |
| `start_background_work()` | sync | real | Pack list load, pack-change forwarder, offline watcher (750 ms) |
| `spawn(fut) -> EngineTask<T>` | sync | real | Runtime-agnostic oneshot future |
| `block_on(fut)` | sync | real | TUI / server / tests; not from inside the runtime |
| `handle()` | sync | real | tokio handle |
| `subscribe() -> broadcast::Receiver<EngineEvent>` | sync | real | See events below |
| `emit(EngineEvent)` / `notify(Notification)` | sync | real | Publish on the bus |
| `next_operation_id()` | sync | real | Id for `Progress` events |
| `shutdown()` | async | real | Stops background work, RGB, Discord; flushes config |
| `is_shut_down()`, `paths()`, `mode()`, `config()`, `build_info()` | sync | real | `config()` is the escape hatch for shell state |
| `status() -> LauncherStatus` | sync | real | Version, signed-in username only, installed count, offline, server mode |

## Packs

| Method | Kind | Status |
|---|---|---|
| `installed_packs() -> Vec<PackSummary>` | sync | real (modpacks, then installed vanilla versions) |
| `installed_modpacks() -> Vec<PackSummary>` | sync | real (no vanilla) |
| `find_available_by_name(name) -> Option<PackSummary>` | sync | real |
| `pack_images(&PackId) -> PackImagesView` | sync | real (disk only, installed packs) |
| `cache_pack_images(&PackId) -> PackImagesView` | async | real |
| `install_loader_version(&LoaderVersionView) -> PackId` | async | real |
| `preview_modrinth(url) -> Option<ModrinthProjectView>` | async | real |
| `install_modrinth(&ModrinthProjectView) -> ImportOutcome` | async | real |
| `manifest_text(&PackId) -> Option<String>` | async | real (installed or available) |
| `manifest_diagnostic(url) -> ManifestDiagnostic` | async | real |
| `available_packs() -> Vec<PackSummary>` | async | real |
| `available_packs_if_ready()` | sync | real |
| `refresh_packs()` | async | real |
| `pack(&PackId) -> Option<PackSummary>` | sync | real |
| `find_installed_by_name(name)` | sync | real |
| `pack_details(&PackId) -> PackDetails` | async | real |
| `install_pack_by_url(url) -> PackId` | async | real |
| `install_available_pack(friendly_name) -> PackId` | async | real |
| `uninstall_pack(&PackId, delete_files)` | async | real (refused while a session is active) |
| `verify_pack(&PackId) -> OperationId`, `verify_pack_with(&PackId, op)` | async | real |
| `verify_all_packs()`, `verify_all_packs_with(op)` | async | real |
| `update_pack(&PackId)`, `update_pack_with(&PackId, op)` | async | real |
| `pack_manifest_text(&PackId) -> Option<String>` | sync | real (installed, available, or a cached `https:` id; never a raw `file:` id) |
| `mark_news_seen(&PackId)` | sync | real |
| `set_pack_account_override(&PackId, Option<uuid>)` | sync | real |
| `set_pack_always_verify(&PackId, bool)` | sync | real |
| `set_pack_scan_frequency(&PackId, Option<ScanFrequency>)` | sync | real |
| `pack_folder(&PackId, PackFolder) -> PathBuf` | sync | real |
| `pack_footprint(&PackId) -> PackFootprint` | async | real (40k entries / 1.5 s, no symlinks) |
| `latest_crash(&PackId) -> Option<CrashView>` | sync | real |
| `diagnose_crash(text, Option<&PackId>, exit_code) -> CrashView` | sync | real |
| `apply_crash_action(&CrashAction, Option<&PackId>) -> CrashActionResult` | async | real |
| `vanilla_versions(kind) -> Vec<VanillaVersionView>` | async | real |
| `install_vanilla(mc_version) -> PackId` | async | real |
| `loader_versions(loader_type) -> Vec<LoaderVersionView>` | async | real |
| `create_pack(name, loader, loader_url) -> PackId` | async | real |
| `fork_pack(&PackId, new_name) -> PackId` | async | real |
| `save_manifest(&PackId, json)` | async | real |
| `add_mod(&PackId, name, remote_url, local_path)` | async | real |
| `remove_mod(&PackId, identifier)` | async | real |
| `search_modrinth(query, mc, loader, limit) -> Vec<ModSearchHit>` | async | real |
| `resolve_modrinth_file(project, mc, loader) -> Option<ModFileView>` | async | real |

## Launch and sessions

| Method | Kind | Status |
|---|---|---|
| `why_launch_blocked(&PackId, Option<account_uuid>) -> Option<EngineError>` | sync | real (gameplay-disabled, not installed, pack running, account resolution, account busy) |
| `launch(&PackId, LaunchOptions) -> SessionId` | async | real (admission, then the pipeline runs in the background) |
| `run_server(&PackId) -> i32` | async | real |
| `sessions()`, `active_sessions()`, `has_active_sessions()`, `session(id)` | sync | real |
| `active_session_for_pack(&PackId)`, `newest_running_session()` | sync | real |
| `cancel_launch(SessionId)` | sync | real |
| `stop_game(SessionId, force)` | async | real (graceful or forced; preparing ones cancel) |
| `dismiss_session(SessionId) -> bool` | sync | real |
| `session_log(SessionId, max_lines) -> LogSnapshot` | sync | real |
| `subscribe_log(SessionId, max_lines) -> LogSubscription` | sync | real (lossless: snapshot + `mpsc` of `LogEvent::{Lines, Closed}`) |
| `session_full_log(SessionId) -> String` | async | real |
| `session_crash(SessionId) -> CrashView` | async | real |
| `format_uptime(ms)` (free fn) | sync | real |

## Accounts and sign-in

| Method | Kind | Status |
|---|---|---|
| `accounts() -> Vec<AccountView>`, `default_account()` | sync | real |
| `set_default_account(uuid)` | sync | real |
| `sign_out(uuid)` | async | real |
| `refresh_account(uuid) -> AccountView` | async | real |
| `sign_in_flows() -> Vec<SignInKind>` | sync | real (device code first in gamepad/handheld mode) |
| `sign_in(SignInRequest) -> AccountView` | async | real: web view helper, device code (emits `SignInEvent::DeviceCode`), WAM |
| `cancel_sign_in()` | sync | real |
| `sign_in_with_code(code, remember, make_default)` | async | real |

## Settings

| Method | Kind | Status |
|---|---|---|
| `settings() -> Settings` | sync | real (every user-facing key) |
| `set(Setting) -> SideEffects` | sync | real (validates, stores, runs proxy reload / RGB restart / Discord exit / oops warning) |
| `set_many(iter) -> SideEffects` | sync | real |
| `window_bounds()`, `set_window_bounds(b)` | sync | real |
| `has_curseforge_api_key()`, `set_curseforge_api_key(Option<&str>)` | sync | real (machine-bound encryption) |
| `export_settings(path)`, `import_settings(path)` | async | real |
| `reset_launcher()` | async | real (refuses while games run; caller relaunches the process) |
| `system_memory() -> SystemMemory` | async | real |
| `hardware_tuned_jvm_args(max_ram_gb)` | sync | real |

Pure helpers in `settings`: `crosses_compressed_oops_limit`, `canonical_theme`, `rgb_settings`,
and the option lists `THEMES`, `UI_SCALE_STEPS`, `CONSOLE_LOG_MAX_LINES_PRESETS`,
`IMAGE_CYCLE_INTERVALS`, `RGB_BACKENDS`, `RGB_EFFECT_STYLES`.

## Runtimes, backups, export, import

| Method | Kind | Status |
|---|---|---|
| `runtimes() -> Vec<RuntimeView>` | async | real |
| `install_runtime(component) -> OperationId`, `install_runtime_with(component, op)` | async | real (emits `Progress`) |
| `remove_runtime(component)`, `remove_all_runtimes() -> Vec<failed>` | async | real |
| `backups(&PackId) -> Vec<BackupView>` | async | real |
| `backup_now(&PackId) -> Option<PathBuf>` | async | real |
| `restore_backup(&PackId, path)` | async | real |
| `export_pack(&PackId, ExportRequest)`, `export_pack_with(&PackId, ExportRequest, op)` | async | real (`Zip`, `ManifestJson`, `OfficialLauncher` with notifications) |
| `official_export_status(&PackId) -> OfficialExportStatus` | async | real |
| `install_official_loader(&PackId) -> String` (version id) | async | real |
| `export_official(&PackId, install_loader, op) -> OfficialExportOutcome` | async | real (no notifications) |
| `official_launcher_available()`, `has_official_export(&PackId)`, `remove_official_export(&PackId)` | sync / sync / async | real |
| `classify_import_url(url) -> ImportSourceView` | sync | real |
| `import(ImportRequest) -> ImportOutcome` | async | real for Mica URL, Modrinth URL, `.mrpack`, zip, manifest file, Prism instance; CurseForge / Technic URLs return a localized "no direct import" error (Java opens the page instead) |

## Network, notifications, Discord, RGB, announcements

| Method | Kind | Status |
|---|---|---|
| `is_offline()`, `check_network()` | sync / async | real |
| `set_discord_menu_presence(screen)` | sync | real |
| `rgb_status() -> RgbStatusView`, `rgb_test()`, `rgb_restart()` | sync | real |
| `announcements()`, `refresh_announcements()`, `dismiss_announcement(text)` | sync / async | real |
| `check_launcher_update() -> Option<UpdateInfo>` | async | real |
| `scan_folder(path) -> ScanSummary`, `scan_folder_with(path, op)`, `cancel_scan()` | async / async / sync | real (Progress under `op`, cancel stops both scanner stages) |

## Events (`EngineEvent`)

`PacksChanged`, `PackChanged(PackId)`, `AccountsChanged`, `SignIn(SignInEvent::{DeviceCode,
Status})`, `SessionStarted`, `SessionChanged`, `SessionRemoved`, `SessionOutput`,
`Progress(OperationProgress)`, `OperationFinished`, `OfflineChanged(bool)`,
`SettingChanged(key)`, `Notification`, `RgbStatusChanged`, `AnnouncementsChanged`,
`RestartRequested`, `ShuttingDown`. A lagging receiver should re-read state through the getters.

## View models

`PackId`, `PackSummary`, `PackDetails`, `PackFootprint`, `AccountView`/`AccountStatus`,
`RuntimeView`, `BackupView`, `ExportRequest`, `ImportRequest`, `ImportOutcome`,
`ImportSourceView`, `ModSearchHit`, `ModFileView`, `LoaderVersionView`, `VanillaVersionView`,
`CrashView`/`CrashSuggestionView`/`CrashSeverity`, `RgbStatusView`/`ChipTone`, `LauncherStatus`,
`AnnouncementsView`, `UpdateInfo`, `ScanProgress`, `ScanSummary`, `SystemMemory`, `SessionId`,
`SessionPhase`, `SessionInfo`, `LaunchStepView`, `LogSnapshot`, `LogSubscription`, `LogEvent`,
`Settings`, `Setting`, `SideEffects`, `Notification`, `NotificationKind`.

Added: `PackImagesView`, `ModrinthProjectView`, `OfficialExportStatus`, `OfficialExportOutcome`,
`ManifestDiagnostic`, `ModConflictView`.

## Engine-side gap APIs

### `PackSummary` additions

- `website: Option<String>`: `packURL`, trimmed, `None` when blank (vanilla: `https://minecraft.net`).
- `logo_path: Option<PathBuf>`: the primary logo in the image cache, only for installed packs and
  only when the file exists (no network).
- `custom_discord_rpc: bool`: `packCustomDiscordRpc`.
- `PackSummary`, `PackId` and `PackLoadState` now derive `Default`. Struct literals of
  `PackSummary` outside this crate need `..Default::default()`.

### Vanilla versions are packs

An installed vanilla version (`install_vanilla`, the config's `installedVanillaVersions`) is a
pack with id `vanilla:<version>` (`PackId::vanilla`). It appears at the end of
`installed_packs()` (`is_vanilla = true`), and `pack`, `pack_details`, `launch`,
`why_launch_blocked`, `verify_pack`, `pack_folder`, `pack_images`, `backups`,
`official_export_status` / `export_official` and `uninstall_pack` accept it.
`uninstall_pack(vanilla, delete_files)` removes the version from the config and, with
`delete_files`, deletes `installs/Minecraft<version>`. `installed_modpacks()` lists modpacks only;
`status().installed_pack_count` and `verify_all_packs` stay modpacks-only, as in Java.

### Images

`pack_images(&PackId) -> PackImagesView { logos, backgrounds, cached }` lists every cached image
file that exists, primary first, for cycling (disk only; empty for packs that are not installed).
`cache_pack_images(&PackId)` downloads every declared logo and background into the cache
(`PackEnvironment::cache_images`, skipped offline), emits `PackChanged`, and returns the new
`pack_images`. Available (not installed) packs have no cache; cards show their URLs.

### Loader versions and Modrinth

- `install_loader_version(&LoaderVersionView) -> PackId`: writes
  `config/imported-manifests/loader-<type>-<mc>-<version>.json` (if absent) and installs it. The
  installer URL is rebuilt from the type (`forge`, `neoforge`, `fabric`).
- `preview_modrinth(url) -> Option<ModrinthProjectView>`: fetches the project and the version the
  URL pins (else the newest) without installing. `Err` when `url` is not a Modrinth project URL
  (`engine.error.notModrinthUrl`); `Ok(None)` when Modrinth has nothing (or is unreachable).
- `install_modrinth(&ModrinthProjectView) -> ImportOutcome`: downloads `mrpack_url`, translates
  and installs it. `import(ImportRequest::Url(modrinth))` now runs these two steps.

### Official-launcher export

- `official_export_status(&PackId) -> OfficialExportStatus { available, dot_minecraft,
  version_id, loader_installed, has_export, profile_name, vanilla }`. Resolving a modded pack's
  version id builds its loader.
- `install_official_loader(&PackId) -> String`: installs the loader into the official launcher and
  returns the version id; a failure is `EngineError::Other` with the localized
  `officialExport.installLoader.failed.body`. Vanilla succeeds at once.
- `export_official(&PackId, install_loader: bool, op) -> OfficialExportOutcome { profile_name,
  version_id, loader_installed }`: verify (modded, progress under `op`), optional loader install,
  copy, profile upsert, `OperationFinished(op)`. Sends no notification (the caller shows its own
  dialog). `export_pack(OfficialLauncher)` keeps the old behaviour (loader install plus
  notifications).
- `has_official_export(&PackId)`, `remove_official_export(&PackId)` as before.

### Progress operations with a known id

Each progress operation has a `*_with` variant that takes the `OperationId` up front (get one from
`next_operation_id()`), so a caller can match `Progress` and `OperationFinished` while the work
runs: `verify_pack_with`, `verify_all_packs_with`, `update_pack_with`, `install_runtime_with`,
`export_pack_with`, `scan_folder_with`. Every variant emits `OperationFinished(op)` when it ends,
including when it is refused at the start. The old signatures still work (they pick an id).

### Manifest text

`pack_manifest_text(&PackId)` (sync, disk only) now also answers for available packs of the loaded
list and for an `https:` id with a cached copy; a raw `file:` id is never read.
`manifest_text(&PackId)` (async) loads the available list first when needed (MCP
`get_modpack_manifest`, `mica://modpack/{n}/manifest`). `find_available_by_name(name)` finds an
available pack by friendly name, then name.

### Network hooks and tests

`Engine::new` installs the `mmcl-net` proxy settings provider over this engine's config
(`proxyEnable`, `proxyHost`, `proxyPort`, `proxyType`; reloaded by `SideEffects::reload_proxy` and
settings import) and the battery throttle policy (`mmcl_platform::power::state()` plus
`batteryThrottleEnable`; 512 KiB/s per stream below 30 % on battery) unless
`EngineOptions::net_hooks` is `false`. The pure helpers are
`engine::net_hooks::{proxy_settings_of, throttle_bytes_per_second}`.

`EngineOptions::http` replaces the network for every engine operation (pack manager, catalogues,
importers, launch pipeline, connectivity probe). `OfflineHttp` never does I/O.
`EngineOptions::offline_for_tests(paths, handle)` is the quiet, network-free test setup.

### Server mode presence

`start_presence()` installs the RGB / Discord hosts and bootstraps RGB once. `run_server` calls
it, so a headless server gets RGB and Discord without the GUI's background work.

### `--diag-manifest`

`manifest_diagnostic(url) -> ManifestDiagnostic { lines, exit_code }` fetches the manifest, prints
the pack, Minecraft version, loader, mod count, min RAM, manifest hash and folder, then resolves
the library manifest twice and reports memoization (exit 0 memoized, 1 not, 2 failed; English
`[manifest-diag]` lines, like Java). `diag::run_manifest_diagnostic(url, &mut dyn Write) -> i32`
is the `mmcl-app` entry: it builds a quiet client-mode engine, writes the lines and returns the
exit code (call it outside any tokio runtime).
