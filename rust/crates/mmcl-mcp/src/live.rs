// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The engine-backed seams (Java `LiveMcpLauncherView`, `LiveMcpLauncherActions`): what the
//! app hands [`McpBootstrap`](crate::McpBootstrap) through [`EngineHost`].
//!
//! Only friendly names, versions, usernames and counts cross into tool code: never an account
//! UUID, token or path. Messages are model-facing and never carry error text; the detail goes
//! to the launcher log.
//!
//! The view methods run on the `mcp-dispatch` thread or an HTTP task; anything async is run on
//! the engine's runtime and awaited with a runtime-agnostic block, so no tokio runtime is ever
//! entered from inside another.

use std::path::PathBuf;
use std::sync::Arc;

use mmcl_engine::{Engine, EngineError, LaunchOptions, LaunchRefusal, PackId, SessionPhase};

use crate::approval::ConsentPrompt;
use crate::bootstrap::{McpConfig, McpHost};
use crate::tools::{
    CrashInfo, LauncherActions, LauncherStatus, LauncherView, Outcome, PackFootprint, PackSummary,
    RunningGame,
};
use crate::{BoxError, logging, now_ms};

/// Runs `fut` on the engine's runtime and waits for it from any thread.
fn run<F, T>(engine: &Engine, fut: F) -> Option<T>
where
    F: std::future::Future<Output = T> + Send + 'static,
    T: Send + 'static,
{
    futures::executor::block_on(engine.spawn(fut)).ok()
}

fn first_non_blank(a: Option<&str>, b: &str) -> String {
    match a {
        Some(s) if !s.trim().is_empty() => s.to_string(),
        _ => b.to_string(),
    }
}

fn summarize(p: &mmcl_engine::PackSummary) -> PackSummary {
    if p.installed {
        PackSummary {
            friendly_name: p.friendly_name.clone(),
            version: first_non_blank(p.installed_version.as_deref(), &p.version),
            latest_version: p.version.clone(),
            update_available: p.update_available,
            mod_loader: p.mod_loader.clone().unwrap_or_default(),
            installed: true,
            unstable: p.unstable,
        }
    } else {
        PackSummary {
            friendly_name: p.friendly_name.clone(),
            version: p.version.clone(),
            latest_version: p.version.clone(),
            update_available: false,
            mod_loader: p.mod_loader.clone().unwrap_or_default(),
            installed: false,
            unstable: p.unstable,
        }
    }
}

/// The model-facing pack list: installed first, then available; blank names are skipped and an
/// installed pack shadows an available one of the same friendly name.
fn merge_pack_lists(
    installed: &[mmcl_engine::PackSummary],
    available: &[mmcl_engine::PackSummary],
) -> Vec<PackSummary> {
    let mut out: Vec<PackSummary> = Vec::new();
    for p in installed.iter().chain(available.iter()) {
        if p.friendly_name.trim().is_empty() {
            continue;
        }
        if out.iter().any(|o| o.friendly_name == p.friendly_name) {
            continue; // installed shadows available
        }
        out.push(summarize(p));
    }
    out
}

/// [`LauncherView`] over the engine.
#[derive(Clone)]
pub struct EngineView {
    engine: Engine,
}

impl EngineView {
    pub fn new(engine: Engine) -> EngineView {
        EngineView { engine }
    }

    fn installed_id(&self, friendly_name: &str) -> Option<PackId> {
        find_by_friendly_name(&self.engine.installed_modpacks(), friendly_name).map(|p| p.id)
    }

    /// An installed modpack by friendly name, else an available one (Java `findPack`). Waits
    /// for the available list when it has not loaded yet.
    fn any_id(&self, friendly_name: &str) -> Option<PackId> {
        if let Some(id) = self.installed_id(friendly_name) {
            return Some(id);
        }
        let ready = self.engine.available_packs_if_ready();
        if let Some(p) = find_by_friendly_name(&ready, friendly_name) {
            return Some(p.id);
        }
        if !ready.is_empty() {
            return None;
        }
        let engine = self.engine.clone();
        let fetched = run(&self.engine, async move { engine.available_packs().await })?;
        find_by_friendly_name(&fetched, friendly_name).map(|p| p.id)
    }
}

/// The first pack whose friendly name is exactly `friendly_name` (blank never matches).
fn find_by_friendly_name(
    packs: &[mmcl_engine::PackSummary],
    friendly_name: &str,
) -> Option<mmcl_engine::PackSummary> {
    if friendly_name.trim().is_empty() {
        return None;
    }
    packs
        .iter()
        .find(|p| p.friendly_name == friendly_name)
        .cloned()
}

impl LauncherView for EngineView {
    fn packs(&self) -> Vec<PackSummary> {
        // Modpacks only, as Java's MCP view: the installed vanilla versions are not modpacks.
        merge_pack_lists(
            &self.engine.installed_modpacks(),
            &self.engine.available_packs_if_ready(),
        )
    }

    fn manifest_of(&self, friendly_name: &str) -> Option<String> {
        let id = self.any_id(friendly_name)?;
        let engine = self.engine.clone();
        let text = run(&self.engine, async move { engine.manifest_text(&id).await }).flatten();
        if text.is_none() {
            logging::warn("log.mcpView.manifestLoadFailed", &[friendly_name]);
        }
        text
    }

    fn latest_crash_of(&self, friendly_name: &str) -> Option<CrashInfo> {
        let id = self.installed_id(friendly_name)?;
        let crash = self.engine.latest_crash(&id)?;
        let report = crash.report.clone().filter(|r| !r.trim().is_empty())?;
        Some(CrashInfo {
            report_text: report,
            title: crash.title,
            summary: crash.summary,
            category: crash.category,
            suggestions: crash.suggestions.into_iter().map(|s| s.label).collect(),
        })
    }

    fn footprint_of(&self, friendly_name: &str) -> Option<PackFootprint> {
        let id = self.installed_id(friendly_name)?;
        let engine = self.engine.clone();
        match run(
            &self.engine,
            async move { engine.pack_footprint(&id).await },
        ) {
            Some(Ok(f)) => Some(PackFootprint {
                size_bytes: f.size_bytes,
                world_count: f.world_count,
                approximate: f.approximate,
            }),
            Some(Err(e)) => {
                logging::warn_with("log.mcpView.footprintFailed", &[friendly_name], &e);
                None
            }
            None => None,
        }
    }

    fn status(&self) -> LauncherStatus {
        let s = self.engine.status();
        LauncherStatus {
            launcher_version: s.launcher_version,
            signed_in: s.signed_in,
            username: s.username.unwrap_or_default(),
            installed_pack_count: s.installed_pack_count,
        }
    }

    fn running_games(&self) -> Vec<RunningGame> {
        let installed = self.engine.installed_packs();
        let now = now_ms() as i64;
        self.engine
            .active_sessions()
            .into_iter()
            .map(|s| {
                let friendly = installed
                    .iter()
                    .find(|p| p.id == s.pack)
                    .map(|p| p.friendly_name.clone())
                    .unwrap_or_else(|| s.pack_name.clone());
                let running = s.phase == SessionPhase::Running;
                RunningGame {
                    friendly_name: friendly,
                    account: s.account_name.clone().unwrap_or_default(),
                    phase: if running { "running" } else { "preparing" }.to_string(),
                    uptime_seconds: if running && s.started_ms > 0 {
                        (s.uptime_ms(now).max(0) / 1000) as u64
                    } else {
                        0
                    },
                }
            })
            .collect()
    }
}

/// [`LauncherActions`] over the engine.
#[derive(Clone)]
pub struct EngineActions {
    engine: Engine,
}

const VALID_LOADERS: [&str; 3] = ["forge", "neoforge", "fabric"];

impl EngineActions {
    pub fn new(engine: Engine) -> EngineActions {
        EngineActions { engine }
    }

    fn installed(&self, friendly_name: &str) -> Option<mmcl_engine::PackSummary> {
        find_by_friendly_name(&self.engine.installed_modpacks(), friendly_name)
    }

    fn account_uuid(&self, username: &str) -> Option<String> {
        let wanted = username.trim().to_lowercase();
        self.engine
            .accounts()
            .into_iter()
            .find(|a| a.name.trim().to_lowercase() == wanted)
            .map(|a| a.uuid)
    }

    fn not_installed(name: &str) -> Outcome {
        Outcome::failed(format!("No modpack named \"{name}\" is installed."))
    }

    /// Runs a manifest edit on an authored pack. `refusal` turns an engine error the edit
    /// itself raised (path taken, no such mod) into its model-facing failure; any other error
    /// is logged and reported generically.
    fn edit<F>(
        &self,
        friendly_name: &str,
        refusal: impl FnOnce(&mmcl_engine::PackSummary, &EngineError) -> Option<Outcome>,
        op: F,
    ) -> Result<(), Outcome>
    where
        F: FnOnce(Engine, PackId) -> futures::future::BoxFuture<'static, Result<(), EngineError>>,
    {
        let Some(p) = self.installed(friendly_name) else {
            return Err(Self::not_installed(friendly_name));
        };
        let authored_dir = self.engine.paths().mcp_manifests_dir();
        let owned = p
            .manifest_url
            .as_deref()
            .and_then(|u| crate::tools::authored::local_manifest_path_of(u, &authored_dir))
            .is_some();
        if !owned {
            return Err(Outcome::failed(format!(
                "\"{friendly_name}\" was installed from a remote manifest, which this launcher does not own and cannot edit. Use fork_modpack to make an editable copy first."
            )));
        }
        let fut = op(self.engine.clone(), p.id.clone());
        match run(&self.engine, fut) {
            Some(Ok(())) => Ok(()),
            Some(Err(e)) => {
                if let Some(o) = refusal(&p, &e) {
                    return Err(o);
                }
                logging::warn_with("log.mcpActions.editManifestFailed", &[friendly_name], &e);
                Err(Outcome::failed("The manifest could not be updated."))
            }
            None => Err(Outcome::failed("The manifest could not be updated.")),
        }
    }
}

impl LauncherActions for EngineActions {
    fn install_from_url(&self, url: &str) -> Outcome {
        let engine = self.engine.clone();
        let u = url.to_string();
        match run(
            &self.engine,
            async move { engine.install_pack_by_url(&u).await },
        ) {
            Some(Ok(_)) => Outcome::ok(format!("Installed the modpack from {url}.")),
            other => {
                if let Some(Err(e)) = other {
                    logging::warn_with("log.mcpActions.installFailed", &[url], &e);
                }
                Outcome::failed(
                    "The install failed. The URL may not point at a valid modpack manifest, or the download may have been interrupted.",
                )
            }
        }
    }

    fn uninstall(&self, friendly_name: &str) -> Outcome {
        let Some(p) = self.installed(friendly_name) else {
            return Self::not_installed(friendly_name);
        };
        let engine = self.engine.clone();
        match run(&self.engine, async move {
            engine.uninstall_pack(&p.id, true).await
        }) {
            Some(Ok(())) => Outcome::ok(format!(
                "Uninstalled \"{friendly_name}\" and deleted its files."
            )),
            other => {
                if let Some(Err(e)) = other {
                    logging::warn_with("log.mcpActions.uninstallFailed", &[friendly_name], &e);
                }
                Outcome::failed("The uninstall failed. Some files may still be on disk.")
            }
        }
    }

    fn launch(&self, friendly_name: &str, account_username: Option<&str>) -> Outcome {
        let Some(p) = self.installed(friendly_name) else {
            return Self::not_installed(friendly_name);
        };
        let account_uuid = match account_username {
            Some(a) => match self.account_uuid(a) {
                Some(u) => Some(u),
                None => {
                    return Outcome::failed(format!("No signed-in account is named \"{a}\"."));
                }
            },
            None => None,
        };
        // The prepare pipeline runs for minutes: never on the single dispatch thread.
        let engine = self.engine.clone();
        let name = friendly_name.to_string();
        let _task = self.engine.spawn(async move {
            let options = LaunchOptions {
                account_uuid,
                ..LaunchOptions::default()
            };
            if let Err(e) = engine.launch(&p.id, options).await {
                logging::warn_with("log.mcpActions.launchFailed", &[&name], &e);
            }
        });
        Outcome::ok(format!(
            "Started launching \"{friendly_name}\". Its progress shows in the launcher's Running Games window; call list_running_games to follow it."
        ))
    }

    fn why_launch_blocked(&self, friendly_name: &str, account: Option<&str>) -> Option<String> {
        let p = self.installed(friendly_name)?;
        let uuid = match account {
            Some(a) => match self.account_uuid(a) {
                Some(u) => Some(u),
                None => {
                    return Some(format!(
                        "No signed-in account is named \"{a}\". get_launcher_status names the default account."
                    ));
                }
            },
            None => None,
        };
        let e = self.engine.why_launch_blocked(&p.id, uuid.as_deref())?;
        Some(match e {
            EngineError::LaunchRefused(LaunchRefusal::PackAlreadyRunning { .. }) => {
                format!("\"{friendly_name}\" is already running. Stop it with stop_game first.")
            }
            EngineError::LaunchRefused(LaunchRefusal::AccountBusy { pack_name, .. }) => format!(
                "That account is already playing \"{pack_name}\". An account can play one game at a time; pass a different account or stop that game."
            ),
            EngineError::Account(_) => account_problem_message(e.l10n_key()),
            EngineError::AccountNotFound(_) => format!(
                "No signed-in account is named \"{}\". get_launcher_status names the default account.",
                account.unwrap_or("")
            ),
            other => other.message(),
        })
    }

    fn stop_game(&self, friendly_name: &str) -> Outcome {
        let wanted = friendly_name.to_lowercase();
        let installed = self.engine.installed_packs();
        let found = self.engine.active_sessions().into_iter().find(|s| {
            let friendly = installed
                .iter()
                .find(|p| p.id == s.pack)
                .map(|p| p.friendly_name.as_str())
                .unwrap_or(&s.pack_name);
            friendly.to_lowercase() == wanted || s.pack_name.to_lowercase() == wanted
        });
        let Some(s) = found else {
            return Outcome::failed(format!("\"{friendly_name}\" is not running."));
        };
        let result = if s.phase == SessionPhase::Preparing {
            self.engine.cancel_launch(s.id).map(|_| true)
        } else {
            let engine = self.engine.clone();
            let id = s.id;
            match run(
                &self.engine,
                async move { engine.stop_game(id, false).await },
            ) {
                Some(r) => r.map(|_| false),
                None => Err(EngineError::ShutDown),
            }
        };
        match result {
            Ok(true) => Outcome::ok(format!("Cancelled the launch of \"{friendly_name}\".")),
            Ok(false) => Outcome::ok(format!("Stopped \"{friendly_name}\".")),
            Err(e) => {
                logging::warn_with("log.mcpActions.stopGameFailed", &[], &e);
                Outcome::failed("The game could not be stopped.")
            }
        }
    }

    fn create_pack(
        &self,
        name: &str,
        mod_loader: Option<&str>,
        mod_loader_url: Option<&str>,
    ) -> Outcome {
        let loader = match mod_loader.map(|l| l.trim().to_lowercase()) {
            Some(l) if !VALID_LOADERS.contains(&l.as_str()) => {
                return Outcome::failed(format!(
                    "Unknown mod loader \"{}\". Use forge, neoforge, or fabric.",
                    mod_loader.unwrap_or("")
                ));
            }
            other => other,
        };
        let url = mod_loader_url.map(|u| u.trim().to_string());
        let engine = self.engine.clone();
        let n = name.trim().to_string();
        let result = run(&self.engine, async move {
            engine
                .create_pack(&n, loader.as_deref(), url.as_deref())
                .await
        });
        match result {
            Some(Ok(_)) => Outcome::ok(format!("Created \"{}\" and installed it.", name.trim())),
            other => {
                if let Some(Err(e)) = other {
                    logging::warn_with("log.mcpActions.createFailed", &[name], &e);
                }
                Outcome::failed("The modpack could not be created.")
            }
        }
    }

    fn fork_pack(&self, source_friendly_name: &str, new_name: &str) -> Outcome {
        let Some(p) = self.installed(source_friendly_name) else {
            return Outcome::failed(format!(
                "Could not read the manifest for \"{source_friendly_name}\", so it cannot be forked."
            ));
        };
        let engine = self.engine.clone();
        let n = new_name.trim().to_string();
        match run(
            &self.engine,
            async move { engine.fork_pack(&p.id, &n).await },
        ) {
            Some(Ok(_)) => Outcome::ok(format!(
                "Forked \"{source_friendly_name}\" into \"{}\" and installed it.",
                new_name.trim()
            )),
            other => {
                if let Some(Err(e)) = other {
                    logging::warn_with("log.mcpActions.forkFailed", &[source_friendly_name], &e);
                }
                Outcome::failed("The fork failed.")
            }
        }
    }

    fn add_mod(
        &self,
        friendly_name: &str,
        mod_name: &str,
        remote_url: &str,
        local_path: &str,
    ) -> Outcome {
        let (m, r, l) = (
            mod_name.to_string(),
            remote_url.to_string(),
            local_path.to_string(),
        );
        let refusal = |_: &mmcl_engine::PackSummary, e: &EngineError| {
            path_taken(e).map(|taken| {
                Outcome::failed(format!(
                    "\"{friendly_name}\" already installs something to {taken}."
                ))
            })
        };
        match self.edit(friendly_name, refusal, move |engine, id| {
            Box::pin(async move { engine.add_mod(&id, &m, &r, &l).await })
        }) {
            Ok(()) => Outcome::ok(format!(
                "Added \"{mod_name}\" to \"{friendly_name}\". It downloads on the next sync."
            )),
            Err(o) => o,
        }
    }

    fn remove_mod(&self, friendly_name: &str, identifier: &str) -> Outcome {
        let i = identifier.to_string();
        let refusal = |_: &mmcl_engine::PackSummary, e: &EngineError| {
            mod_not_found(e).map(|missing| {
                Outcome::failed(format!(
                    "No mod matching \"{missing}\" is in \"{friendly_name}\"."
                ))
            })
        };
        match self.edit(friendly_name, refusal, move |engine, id| {
            Box::pin(async move { engine.remove_mod(&id, &i).await })
        }) {
            Ok(()) => Outcome::ok(format!(
                "Removed \"{identifier}\" from \"{friendly_name}\". The file goes on the next sync."
            )),
            Err(o) => o,
        }
    }
}

/// The local path of an [`EngineError::ModPathTaken`] refusal (`add_mod`: the pack already
/// installs a file there).
fn path_taken(e: &EngineError) -> Option<&str> {
    match e {
        EngineError::ModPathTaken { local_path, .. } => Some(local_path),
        _ => None,
    }
}

/// The identifier of an [`EngineError::ModNotFound`] refusal (`remove_mod`: nothing in the
/// pack matches it).
fn mod_not_found(e: &EngineError) -> Option<&str> {
    match e {
        EngineError::ModNotFound { identifier, .. } => Some(identifier),
        _ => None,
    }
}

/// The model-facing text of an account-resolution problem, by the engine's message key.
fn account_problem_message(key: &str) -> String {
    match key {
        "engine.error.account.overrideMissing" => "This modpack is set to launch as an account that is no longer signed in. Change it in the launcher, or pass an account.".to_string(),
        "engine.error.account.needsSignIn" => {
            "The account this modpack launches as has to sign in again in the launcher.".to_string()
        }
        _ => "No account is signed in. Sign in through the launcher first.".to_string(),
    }
}

/// [`McpConfig`] over the engine's config.
#[derive(Clone)]
pub struct EngineMcpConfig {
    engine: Engine,
}

impl EngineMcpConfig {
    pub fn new(engine: Engine) -> EngineMcpConfig {
        EngineMcpConfig { engine }
    }
}

impl McpConfig for EngineMcpConfig {
    fn server_enabled(&self) -> Result<bool, BoxError> {
        Ok(self.engine.config().mcp_server_enabled())
    }
    fn allow_state_changes(&self) -> bool {
        self.engine.config().mcp_allow_state_changes()
    }
    fn auto_approve_read_only(&self) -> bool {
        self.engine.config().mcp_auto_approve_read_only()
    }
    fn tool_policies(&self) -> String {
        self.engine.config().mcp_tool_policies()
    }
    fn set_tool_policies(&self, json: &str) {
        self.engine.config().set_mcp_tool_policies(json);
    }
    fn port(&self) -> u16 {
        let p = self.engine.config().mcp_port();
        u16::try_from(p)
            .ok()
            .filter(|p| *p > 0)
            .unwrap_or(mmcl_core::AppIdentity::current().mcp_port)
    }
    fn token(&self) -> String {
        self.engine.config().mcp_token()
    }
    fn set_token(&self, token: &str) {
        self.engine.config().set_mcp_token(token);
    }
}

/// The [`McpHost`] the app passes to [`McpBootstrap::new`](crate::McpBootstrap::new): the
/// engine adapters plus the GUI's consent prompt.
pub struct EngineHost {
    engine: Engine,
    consent: Arc<dyn ConsentPrompt>,
}

impl EngineHost {
    pub fn new(engine: Engine, consent: Arc<dyn ConsentPrompt>) -> EngineHost {
        EngineHost { engine, consent }
    }
}

impl McpHost for EngineHost {
    fn config(&self) -> Arc<dyn McpConfig> {
        Arc::new(EngineMcpConfig::new(self.engine.clone()))
    }
    fn endpoint_file(&self) -> PathBuf {
        crate::endpoint::default_path()
    }
    fn launcher_version(&self) -> String {
        self.engine.build_info().version.to_string()
    }
    fn view(&self) -> Arc<dyn LauncherView> {
        Arc::new(EngineView::new(self.engine.clone()))
    }
    fn actions(&self) -> Arc<dyn LauncherActions> {
        Arc::new(EngineActions::new(self.engine.clone()))
    }
    fn consent(&self) -> Arc<dyn ConsentPrompt> {
        self.consent.clone()
    }
    fn extra_secrets(&self) -> Vec<String> {
        std::fs::read_to_string(mmcl_core::DataPaths::single_instance_token_file())
            .map(|t| vec![t.trim().to_string()])
            .unwrap_or_default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_core::{DataPaths, GameMode};
    use mmcl_engine::EngineOptions;

    fn summary(friendly: &str, installed: bool) -> mmcl_engine::PackSummary {
        mmcl_engine::PackSummary {
            id: PackId::new(format!("https://example.com/{friendly}.json")),
            name: friendly.to_string(),
            friendly_name: friendly.to_string(),
            version: "2.0".into(),
            installed_version: installed.then(|| "1.0".to_string()),
            installed,
            mod_loader: Some("forge".into()),
            ..Default::default()
        }
    }

    #[test]
    fn installed_shadows_available_and_blank_names_are_skipped() {
        let installed = vec![summary("Alto", true), summary(" ", true)];
        let available = vec![summary("Alto", false), summary("Beta", false)];
        let list = merge_pack_lists(&installed, &available);
        let names: Vec<_> = list.iter().map(|p| p.friendly_name.as_str()).collect();
        assert_eq!(names, ["Alto", "Beta"]);
        assert!(list[0].installed);
        assert_eq!(list[0].version, "1.0");
        assert_eq!(list[0].latest_version, "2.0");
        assert!(!list[1].installed);
        assert_eq!(list[1].version, "2.0");
    }

    #[test]
    fn friendly_name_lookup_is_exact() {
        let packs = vec![summary("Alto", true), summary("Beta", false)];
        assert_eq!(
            find_by_friendly_name(&packs, "Beta").map(|p| p.friendly_name),
            Some("Beta".to_string())
        );
        assert!(find_by_friendly_name(&packs, "alto").is_none());
        assert!(find_by_friendly_name(&packs, "").is_none());
        assert!(find_by_friendly_name(&packs, "  ").is_none());
    }

    #[test]
    fn engine_refusals_are_recognised_by_variant() {
        let taken = EngineError::ModPathTaken {
            pack_name: "Alto".into(),
            local_path: "mods/jei.jar".into(),
        };
        assert_eq!(path_taken(&taken), Some("mods/jei.jar"));
        assert_eq!(mod_not_found(&taken), None);
        let missing = EngineError::ModNotFound {
            identifier: "jei".into(),
            pack_name: "Alto".into(),
        };
        assert_eq!(mod_not_found(&missing), Some("jei"));
        assert_eq!(path_taken(&missing), None);
        // The same text as a generic error is not a refusal: only the variant counts.
        let text = EngineError::Other(mmcl_i18n::tf!("engine.error.modNotFound", "jei", "Alto"));
        assert_eq!(mod_not_found(&text), None);
        assert_eq!(path_taken(&EngineError::ShutDown), None);
    }

    struct Offline {
        engine: Engine,
        _rt: tokio::runtime::Runtime,
        _dir: tempfile::TempDir,
    }

    fn offline_engine() -> Offline {
        let dir = tempfile::tempdir().unwrap();
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .unwrap();
        let o = EngineOptions::offline_for_tests(
            DataPaths::at_root(dir.path().join("data"), GameMode::Client),
            rt.handle().clone(),
        );
        let engine = Engine::new(o).unwrap();
        Offline {
            engine,
            _rt: rt,
            _dir: dir,
        }
    }

    #[test]
    fn an_unknown_pack_has_no_manifest_even_after_loading_the_available_list() {
        let h = offline_engine();
        let view = EngineView::new(h.engine.clone());
        assert!(view.packs().is_empty());
        assert_eq!(view.manifest_of("Nope"), None);
        assert_eq!(view.manifest_of(""), None);
    }

    #[test]
    fn edits_of_a_pack_that_is_not_installed_say_so() {
        let h = offline_engine();
        let actions = EngineActions::new(h.engine.clone());
        let add = actions.add_mod("Nope", "JEI", "https://example.com/jei.jar", "mods/jei.jar");
        assert!(!add.ok);
        assert_eq!(add.message, "No modpack named \"Nope\" is installed.");
        let remove = actions.remove_mod("Nope", "jei");
        assert!(!remove.ok);
        assert_eq!(remove.message, "No modpack named \"Nope\" is installed.");
    }
}
