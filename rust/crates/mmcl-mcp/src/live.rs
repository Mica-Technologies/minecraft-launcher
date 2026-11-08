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
        self.engine
            .installed_packs()
            .into_iter()
            .find(|p| p.friendly_name == friendly_name)
            .map(|p| p.id)
    }
}

impl LauncherView for EngineView {
    fn packs(&self) -> Vec<PackSummary> {
        let mut out: Vec<PackSummary> = Vec::new();
        let installed = self.engine.installed_packs();
        let available = self.engine.available_packs_if_ready();
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

    fn manifest_of(&self, friendly_name: &str) -> Option<String> {
        let id = self.installed_id(friendly_name)?;
        let text = self.engine.pack_manifest_text(&id);
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
        self.engine
            .installed_packs()
            .into_iter()
            .find(|p| p.friendly_name == friendly_name)
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

    fn edit<F>(&self, friendly_name: &str, op: F) -> Result<(), Outcome>
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
        let fut = op(self.engine.clone(), p.id);
        match run(&self.engine, fut) {
            Some(Ok(())) => Ok(()),
            Some(Err(e)) => {
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
        match self.edit(friendly_name, move |engine, id| {
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
        match self.edit(friendly_name, move |engine, id| {
            Box::pin(async move { engine.remove_mod(&id, &i).await })
        }) {
            Ok(()) => Outcome::ok(format!(
                "Removed \"{identifier}\" from \"{friendly_name}\". The file goes on the next sync."
            )),
            Err(o) => o,
        }
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
