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

## Lifecycle

| Method | Kind | Status | Notes |
|---|---|---|---|
| `Engine::new(EngineOptions) -> anyhow::Result<Engine>` | sync | real | Builds/uses the runtime, opens config, `AuthService`, `ModPackManager`, `RuntimeManager`, wires callbacks onto the bus |
| `EngineOptions::new(paths)` / `::for_mode(mode)` | sync | real | `runtime: RuntimeChoice::{Own{worker_threads}, Handle(h)}`, `event_capacity`, `web_view_supported`, `start_background_work` |
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
| `installed_packs() -> Vec<PackSummary>` | sync | real |
| `available_packs() -> Vec<PackSummary>` | async | real |
| `available_packs_if_ready()` | sync | real |
| `refresh_packs()` | async | real |
| `pack(&PackId) -> Option<PackSummary>` | sync | real |
| `find_installed_by_name(name)` | sync | real |
| `pack_details(&PackId) -> PackDetails` | async | real |
| `install_pack_by_url(url) -> PackId` | async | real |
| `install_available_pack(friendly_name) -> PackId` | async | real |
| `uninstall_pack(&PackId, delete_files)` | async | real (refused while a session is active) |
| `verify_pack(&PackId) -> OperationId` | async | real |
| `verify_all_packs()` | async | real |
| `update_pack(&PackId)` | async | real |
| `pack_manifest_text(&PackId) -> Option<String>` | sync | real |
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
| `install_runtime(component) -> OperationId` | async | real (emits `Progress`) |
| `remove_runtime(component)`, `remove_all_runtimes() -> Vec<failed>` | async | real |
| `backups(&PackId) -> Vec<BackupView>` | async | real |
| `backup_now(&PackId) -> Option<PathBuf>` | async | real |
| `restore_backup(&PackId, path)` | async | real |
| `export_pack(&PackId, ExportRequest)` | async | real (`Zip`, `ManifestJson`, `OfficialLauncher`) |
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
| `scan_folder(path) -> ScanSummary`, `cancel_scan()` | async / sync | real |

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
