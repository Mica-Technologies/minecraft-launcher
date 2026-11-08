// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The launch pipeline (Java `GameModPackLauncher`).
//!
//! [`prepare`]: guard (never prepare a pack whose manifest was not loaded), verify mode, then
//! for a modded pack three parallel branches (content sync, loader libraries, Minecraft files
//! then the Java runtime) with fail-fast cancellation, the install processors, the security
//! scan, and the loader-priority classpath merge. [`launch`]: the argv (JVM section, main
//! class, game args, placeholders, quick join, title and icon) and the process start.
//!
//! Fixes over Java: macOS dock flags sit in the JVM section (B-1); `${user_type}` is the
//! account's type (`msa`) and `${launcher_version}` the real launcher version (Q-7); server
//! heap sizes come from `--xms`/`--xmx` or `MMCL_XMS`/`MMCL_XMX` (Q-4); stdin is closed in
//! piped mode (Q-8).

use std::future::Future;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use futures::StreamExt;
use futures::stream::FuturesUnordered;
use indexmap::IndexMap;
use mmcl_core::GameMode;
use mmcl_i18n::{t, tf};
use mmcl_manifest::ServerFavorite;
use mmcl_modpack::sync::{FileSync, SyncEnv};
use mmcl_modpack::verify_state::{DEFAULT_TTL_MS, decide_mode, should_scan};
use mmcl_modpack::{GameModPack, PrepareContext, ScanFrequency, VerifyMode, VerifyState};
use mmcl_mojang::Os;
use mmcl_net::CancelToken;
use mmcl_progress::{ProgressSink, StepHandle, StepId, TrackerBridge};
use mmcl_runtime::SinkProgress;
use tokio::task::JoinHandle;

use crate::assets::{AssetInfo, game_assets_path};
use crate::command::{self, Log4jPlan, PATH_SEPARATOR, PlaceholderInputs};
use crate::env::{Account, AuthArguments, LaunchEnv};
use crate::error::{LaunchError, Result};
use crate::lerr;
use crate::loader::{ModLoader, loader_of};
use crate::run::PackRun;
use crate::spawn::{ChildIo, RuntimeLease, spawn_game};
use crate::vanilla::{self, LibraryManifest};

/// How long a failed launch waits for its other branches to stop.
pub const BRANCH_STOP_WAIT: Duration = Duration::from_secs(10);

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_millis() as i64)
}

// ---------------------------------------------------------------------------------------------
// Branches
// ---------------------------------------------------------------------------------------------

/// The parallel branches of one launch: a cancel token of their own (cancelled by the launch's
/// token, by the first failure, or when this is dropped), fail-fast joining, and a bounded
/// wait for the others to stop after a failure (so a relaunch never runs beside an orphan).
pub struct Branches<T> {
    token: CancelToken,
    launch: CancelToken,
    handles: Vec<JoinHandle<Result<T>>>,
    watcher: JoinHandle<()>,
}

impl<T: Send + 'static> Branches<T> {
    /// Branches of the launch whose token is `launch`.
    pub fn new(launch: &CancelToken) -> Branches<T> {
        let token = CancelToken::new();
        let (l, t) = (launch.clone(), token.clone());
        let watcher = tokio::spawn(async move {
            tokio::select! {
                _ = l.cancelled() => t.cancel(),
                _ = t.cancelled() => {}
            }
        });
        Branches {
            token,
            launch: launch.clone(),
            handles: Vec::new(),
            watcher,
        }
    }

    /// The branches' token: pass it to every branch.
    pub fn token(&self) -> CancelToken {
        self.token.clone()
    }

    /// Starts one branch.
    pub fn spawn(&mut self, fut: impl Future<Output = Result<T>> + Send + 'static) {
        self.handles.push(tokio::spawn(fut));
    }

    /// Waits for every branch. The first failure cancels the rest, waits up to
    /// [`BRANCH_STOP_WAIT`] for them, and is returned (`launch.error.preLaunchCancelled` when
    /// the launch itself was cancelled). Results are in spawn order.
    pub async fn join(mut self) -> Result<Vec<T>> {
        self.join_with_wait(BRANCH_STOP_WAIT).await
    }

    async fn join_with_wait(&mut self, wait: Duration) -> Result<Vec<T>> {
        let n = self.handles.len();
        let mut results: Vec<Option<T>> = (0..n).map(|_| None).collect();
        let handles = std::mem::take(&mut self.handles);
        let aborts: Vec<_> = handles.iter().map(JoinHandle::abort_handle).collect();
        let mut pending: FuturesUnordered<_> = handles
            .into_iter()
            .enumerate()
            .map(|(i, h)| async move { (i, h.await) })
            .collect();
        let mut first_err: Option<LaunchError> = None;
        while let Some((i, r)) = pending.next().await {
            let r = match r {
                Ok(r) => r,
                Err(e) if e.is_panic() => Err(lerr!("launch.error.stepFailed")),
                Err(_) => Err(LaunchError::Cancelled),
            };
            match r {
                Ok(v) => results[i] = Some(v),
                Err(e) => {
                    first_err = Some(e);
                    break;
                }
            }
        }
        if let Some(e) = first_err {
            self.token.cancel();
            let drained =
                tokio::time::timeout(wait, async { while pending.next().await.is_some() {} }).await;
            if drained.is_err() {
                mmcl_log::warn_silent(&t!("log.gameModPackLauncher.branchesStillRunning"));
                aborts.iter().for_each(|a| a.abort());
            }
            if self.launch.is_cancelled() {
                return Err(LaunchError::PreLaunchCancelled);
            }
            return Err(e);
        }
        if self.launch.is_cancelled() {
            return Err(LaunchError::PreLaunchCancelled);
        }
        Ok(results
            .into_iter()
            .map(|r| r.expect("every branch finished"))
            .collect())
    }
}

impl<T> Drop for Branches<T> {
    fn drop(&mut self) {
        self.token.cancel();
        self.watcher.abort();
    }
}

// ---------------------------------------------------------------------------------------------
// Steps
// ---------------------------------------------------------------------------------------------

/// One progress row: the tracker handle when a tracker drives the UI, else the top-level
/// sink's step callbacks.
#[derive(Clone)]
struct Step {
    id: StepId,
    handle: Option<Arc<StepHandle>>,
    sink: Arc<dyn ProgressSink>,
}

impl Step {
    fn new(id: StepId, bridge: Option<&Arc<TrackerBridge>>, top: &Arc<dyn ProgressSink>) -> Step {
        let handle = bridge.map(|b| Arc::new(b.handle_for(id)));
        let sink = match &handle {
            Some(h) => h.clone() as Arc<dyn ProgressSink>,
            None => top.clone(),
        };
        Step { id, handle, sink }
    }
    fn running(&self) {
        match &self.handle {
            Some(h) => h.mark_running(),
            None => self.sink.enter_step(self.id),
        }
    }
    fn done(&self) {
        match &self.handle {
            Some(h) => h.mark_done(),
            None => self.sink.complete_step(self.id),
        }
    }
    fn failed(&self, msg: &str) {
        match &self.handle {
            Some(h) => h.mark_failed(msg),
            None => self.sink.fail_step(self.id, msg),
        }
    }
    fn skipped(&self, reason: &str) {
        match &self.handle {
            Some(h) => h.mark_skipped(reason),
            None => self.sink.set_curr_text(reason),
        }
    }
    /// Runs `body` as this step: running, then done or failed (the error wrapped with `key`
    /// unless it already describes itself).
    async fn run<T>(&self, key: &'static str, body: impl Future<Output = Result<T>>) -> Result<T> {
        self.running();
        match body.await {
            Ok(v) => {
                self.done();
                Ok(v)
            }
            Err(e) => {
                self.failed(&e.to_string());
                Err(e.wrap_step(key))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// prepare
// ---------------------------------------------------------------------------------------------

/// What [`prepare`] produced.
#[derive(Clone)]
pub struct Prepared {
    /// The merged classpath.
    pub classpath: Vec<String>,
    /// The pack's client.json.
    pub library_manifest: Arc<LibraryManifest>,
    /// The loader (`None` for vanilla).
    pub loader: Option<Arc<dyn ModLoader>>,
    /// Asset index facts.
    pub assets: AssetInfo,
    /// The Minecraft version.
    pub mc_version: String,
    /// The verify mode the run used.
    pub verify_mode: VerifyMode,
}

impl std::fmt::Debug for Prepared {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Prepared")
            .field("mc_version", &self.mc_version)
            .field("classpath", &self.classpath.len())
            .field("verify_mode", &self.verify_mode)
            .finish_non_exhaustive()
    }
}

/// Refuses a pack whose manifest was not (fully) loaded, before anything touches its files:
/// a missing mod list would otherwise read as "no mods" and empty `mods/`.
pub fn guard_loaded(pack: &GameModPack) -> Result<()> {
    if pack.is_failed_load() {
        return Err(lerr!("launch.error.manifestNotLoaded", pack.name()));
    }
    if pack.is_stub() || !pack.is_launchable() {
        return Err(lerr!("launch.error.manifestNotFullyLoaded", pack.name()));
    }
    Ok(())
}

/// FULL or FAST_PATH for this launch: server mode is always FULL.
pub fn decide_launch_verify_mode(env: &LaunchEnv, pack: &GameModPack) -> VerifyMode {
    if env.mode() == GameMode::Server {
        return VerifyMode::Full;
    }
    let always = pack
        .manifest_url()
        .is_some_and(|u| env.config.always_verify_on_launch(u));
    decide_mode(
        pack.load_verify_state().as_ref(),
        pack.manifest_sha256(),
        DEFAULT_TTL_MS,
        always,
        false,
        now_ms(),
    )
}

/// Restores the pack's previous prepare context however the run ends.
struct ContextGuard {
    pack: Arc<GameModPack>,
    prev: Option<Arc<PrepareContext>>,
}

impl Drop for ContextGuard {
    fn drop(&mut self) {
        if let Some(p) = self.prev.take() {
            self.pack.set_prepare_context(p);
        }
    }
}

/// Prepares `pack` for launch: syncs content, verifies every library, asset and runtime,
/// runs the processors and the scan, and returns the classpath. Writes the verify state after
/// a successful FULL run.
pub async fn prepare(
    env: &LaunchEnv,
    pack: &Arc<GameModPack>,
    sink: Arc<dyn ProgressSink>,
    bridge: Option<Arc<TrackerBridge>>,
    cancel: &CancelToken,
) -> Result<Prepared> {
    guard_loaded(pack)?;
    sink.set_curr_text(&t!("gameModPackLauncher.progress.preparingModpack"));
    let mode = decide_launch_verify_mode(env, pack);
    mmcl_log::debug(&tf!(
        "log.gameModPackLauncher.verifyMode",
        pack.name(),
        mode.name()
    ));
    let ctx = PrepareContext::for_launch(
        mode,
        Some(pack.checked_pack_root()?),
        pack.launch_count().saturating_add(1),
    );
    let prepared = prepare_with_context(env, pack, Arc::new(ctx), sink, bridge, cancel).await?;
    if mode == VerifyMode::Full {
        write_verify_state(pack);
    }
    Ok(prepared)
}

/// "Verify all game files": the same pipeline forced FULL, without an audit target. Writes
/// the verify state.
pub async fn verify_all_files_now(
    env: &LaunchEnv,
    pack: &Arc<GameModPack>,
    sink: Arc<dyn ProgressSink>,
    bridge: Option<Arc<TrackerBridge>>,
    cancel: &CancelToken,
) -> Result<Prepared> {
    guard_loaded(pack)?;
    mmcl_log::debug(&tf!("log.gameModPackLauncher.forceFullVerify", pack.name()));
    let prepared = prepare_with_context(
        env,
        pack,
        Arc::new(PrepareContext::full_verify()),
        sink,
        bridge,
        cancel,
    )
    .await?;
    write_verify_state(pack);
    Ok(prepared)
}

fn write_verify_state(pack: &GameModPack) {
    if let Some(sha) = pack.manifest_sha256() {
        let fresh = VerifyState::successful_verify(pack.load_verify_state(), Some(sha));
        pack.save_verify_state(&fresh);
        mmcl_log::debug(&tf!(
            "log.gameModPackLauncher.wroteVerifyState",
            pack.name(),
            fresh.verified_at.to_string()
        ));
    }
}

enum BranchOut {
    Content,
    LoaderCp(Vec<String>),
    Minecraft(Arc<LibraryManifest>, vanilla::MinecraftFiles),
}

async fn prepare_with_context(
    env: &LaunchEnv,
    pack: &Arc<GameModPack>,
    ctx: Arc<PrepareContext>,
    sink: Arc<dyn ProgressSink>,
    bridge: Option<Arc<TrackerBridge>>,
    cancel: &CancelToken,
) -> Result<Prepared> {
    pack.prepare_environment()?;
    let _restore = ContextGuard {
        pack: pack.clone(),
        prev: Some(pack.set_prepare_context(ctx.clone())),
    };
    let step = |id| Step::new(id, bridge.as_ref(), &sink);
    let mut loader_cp: Vec<String> = Vec::new();
    let (lm, mc_files, loader) = if pack.is_vanilla() {
        let run = PackRun {
            env: env.clone(),
            pack: pack.clone(),
            ctx: ctx.clone(),
            cancel: cancel.clone(),
        };
        let mc_version = pack.vanilla_mc_version().unwrap_or_default().to_string();
        let (lm, files) = minecraft_then_jre(
            &run,
            &mc_version,
            step(StepId::McLibsAssets),
            step(StepId::JreInstall),
        )
        .await?;
        (lm, files, None)
    } else {
        let mut branches: Branches<BranchOut> = Branches::new(cancel);
        let run = PackRun {
            env: env.clone(),
            pack: pack.clone(),
            ctx: ctx.clone(),
            cancel: branches.token(),
        };
        {
            let (run, s) = (run.clone(), step(StepId::ModpackContent));
            branches
                .spawn(async move { content_branch(&run, s).await.map(|_| BranchOut::Content) });
        }
        {
            let (run, s) = (run.clone(), step(StepId::ForgeLibs));
            branches.spawn(async move { loader_branch(&run, s).await.map(BranchOut::LoaderCp) });
        }
        {
            let (run, mc, jre) = (
                run.clone(),
                step(StepId::McLibsAssets),
                step(StepId::JreInstall),
            );
            branches.spawn(async move {
                let l = loader_of(&run.env, &run.pack)
                    .await?
                    .ok_or(lerr!("launch.error.stepFailed"))?;
                let (lm, files) = minecraft_then_jre(&run, &l.minecraft_version(), mc, jre).await?;
                Ok(BranchOut::Minecraft(lm, files))
            });
        }
        let mut lm_files = None;
        for out in branches.join().await? {
            match out {
                BranchOut::Content => {}
                BranchOut::LoaderCp(cp) => loader_cp = cp,
                BranchOut::Minecraft(lm, files) => lm_files = Some((lm, files)),
            }
        }
        let (lm, files) = lm_files.ok_or(lerr!("launch.error.stepFailed"))?;
        let loader = loader_of(env, pack).await?;
        if pack.uses_post_install_steps()
            && let Some(l) = &loader
        {
            let run = PackRun {
                env: env.clone(),
                pack: pack.clone(),
                ctx: ctx.clone(),
                cancel: cancel.clone(),
            };
            run.check_cancelled()?;
            let s = step(StepId::ForgeProcessors);
            let component = lm.runtime_component();
            s.run("launch.error.processorsFailed", async {
                s.sink
                    .start_section(&t!("gameModPackLauncher.progress.patchingFiles"), 10.0);
                l.run_post_install(&run, &s.sink, &component).await?;
                s.sink
                    .end_section(&t!("gameModPackLauncher.progress.filesPatched"));
                Ok(())
            })
            .await?;
        }
        (lm, files, loader)
    };
    if cancel.is_cancelled() {
        return Err(LaunchError::Cancelled);
    }
    security_scan(env, pack, step(StepId::SecurityScan), cancel).await?;
    let classpath = command::merge_classpaths_loader_priority_vec(&loader_cp, &mc_files.classpath);
    Ok(Prepared {
        classpath,
        mc_version: lm.mc_version.clone(),
        library_manifest: lm,
        loader,
        assets: mc_files.assets,
        verify_mode: ctx.verify_mode,
    })
}

async fn content_branch(run: &PackRun, step: Step) -> Result<()> {
    step.run("launch.error.contentSyncFailed", async {
        run.check_cancelled()?;
        let sink = step.sink.clone();
        let mut senv = SyncEnv::new(run.env.side(), run.env.http.clone(), sink.clone());
        senv.pool = run.env.pool.clone();
        senv.ctx = Some(run.ctx.clone());
        senv.cancel = Some(run.cancel.clone());
        let sync = FileSync::new(run.pack.clone(), senv);
        sink.start_section(&t!("gameModPackLauncher.progress.downloadingMods"), 15.0);
        sync.fetch_latest_mods().await?;
        run.check_cancelled()?;
        let mc = run.pack.minecraft_version_hint().unwrap_or_default();
        if crate::arm64::is_needed(&run.env, &mc) {
            crate::arm64::disable_incompatible_mods(&run.pack.mods_dir());
        }
        sink.end_section(&t!("gameModPackLauncher.progress.modsReady"));
        sink.start_section(&t!("gameModPackLauncher.progress.downloadingConfigs"), 5.0);
        sync.fetch_latest_configs().await?;
        run.check_cancelled()?;
        sync.fetch_latest_resource_packs().await?;
        run.check_cancelled()?;
        sync.fetch_latest_shader_packs().await?;
        run.check_cancelled()?;
        sync.fetch_latest_initial_files().await?;
        run.check_cancelled()?;
        run.pack
            .environment()
            .cache_images(&run.pack, run.env.http.as_ref())
            .await;
        sink.end_section(&t!("gameModPackLauncher.progress.configsReady"));
        Ok(())
    })
    .await
}

async fn loader_branch(run: &PackRun, step: Step) -> Result<Vec<String>> {
    step.run("launch.error.loaderLibsFailed", async {
        run.check_cancelled()?;
        let loader = loader_of(&run.env, &run.pack)
            .await?
            .ok_or(lerr!("launch.error.stepFailed"))?;
        let name = loader.name();
        step.sink.start_section(
            &tf!("gameModPackLauncher.progress.downloadingLoaderLibs", name),
            15.0,
        );
        let cp = loader.build_classpath(run, &step.sink).await?;
        run.check_cancelled()?;
        step.sink
            .end_section(&tf!("gameModPackLauncher.progress.loaderLibsReady", name));
        Ok(cp)
    })
    .await
}

async fn minecraft_then_jre(
    run: &PackRun,
    mc_version: &str,
    mc: Step,
    jre: Step,
) -> Result<(Arc<LibraryManifest>, vanilla::MinecraftFiles)> {
    let weight = if run.pack.is_vanilla() {
        (40.0, 20.0)
    } else {
        (20.0, 15.0)
    };
    let (lm, files) = mc
        .run("launch.error.minecraftFilesFailed", async {
            run.check_cancelled()?;
            mc.sink.start_section(
                &t!("gameModPackLauncher.progress.downloadingMcLibs"),
                weight.0,
            );
            let lm = vanilla::library_manifest(run, mc_version).await?;
            run.check_cancelled()?;
            let files = vanilla::build_minecraft_classpath(run, &lm, &mc.sink).await?;
            run.check_cancelled()?;
            mc.sink
                .end_section(&t!("gameModPackLauncher.progress.mcLibsReady"));
            Ok((lm, files))
        })
        .await?;
    let component = lm.runtime_component();
    jre.run("launch.error.runtimeFailed", async {
        jre.sink.start_section(
            &tf!("gameModPackLauncher.progress.installingRuntime", &component),
            weight.1,
        );
        let progress = SinkProgress(jre.sink.as_ref());
        run.env
            .runtimes
            .verify(&component, Some(&progress), &run.cancel)
            .await?;
        let ready = t!("gameModPackLauncher.progress.runtimeReady");
        jre.sink.submit_progress(&ready, 100.0);
        jre.sink.end_section(&ready);
        Ok(())
    })
    .await?;
    Ok((lm, files))
}

fn scan_skip_reason(f: ScanFrequency) -> String {
    match f {
        ScanFrequency::Disabled => t!("gameModPackLauncher.scanSkip.disabled"),
        ScanFrequency::OnChangesOnly => t!("gameModPackLauncher.scanSkip.noChange"),
        ScanFrequency::Daily => t!("gameModPackLauncher.scanSkip.recent"),
        ScanFrequency::EveryTime => t!("gameModPackLauncher.scanSkip.notDue"),
    }
    .into_owned()
}

async fn security_scan(
    env: &LaunchEnv,
    pack: &Arc<GameModPack>,
    step: Step,
    cancel: &CancelToken,
) -> Result<()> {
    let freq = env.config.scan_frequency_for(pack.manifest_url());
    let state = pack.load_verify_state();
    let sha = pack.manifest_sha256().map(str::to_string);
    if !should_scan(freq, state.as_ref(), sha.as_deref(), now_ms()) {
        mmcl_log::debug(&tf!(
            "log.gameModPackLauncher.skippingScan",
            pack.name(),
            freq.name()
        ));
        step.skipped(&scan_skip_reason(freq));
        return Ok(());
    }
    step.running();
    step.sink
        .start_section(&t!("gameModPackLauncher.progress.scanningMalware"), 10.0);
    match env
        .scanner
        .scan(pack, step.sink.clone(), cancel.clone())
        .await
    {
        Ok(()) => {
            let fresh = VerifyState::successful_scan(pack.load_verify_state(), sha.as_deref());
            pack.save_verify_state(&fresh);
            step.sink
                .end_section(&t!("gameModPackLauncher.progress.scanComplete"));
            step.done();
            Ok(())
        }
        Err(e) => {
            let msg = e.to_string();
            step.failed(&msg);
            if cancel.is_cancelled() {
                return Err(lerr!("launch.error.scanInterrupted"));
            }
            Err(match e {
                mmcl_modpack::ModpackError::Io(_) | mmcl_modpack::ModpackError::Scan(_) => {
                    lerr!("launch.error.scanFailed", msg)
                }
                other => LaunchError::Modpack(other),
            })
        }
    }
}

// ---------------------------------------------------------------------------------------------
// launch
// ---------------------------------------------------------------------------------------------

/// One launch request.
pub struct LaunchRequest {
    pub pack: Arc<GameModPack>,
    /// Required in client mode.
    pub account: Option<Account>,
    /// The top-level progress sink (the legacy single bar).
    pub sink: Arc<dyn ProgressSink>,
    /// The multi-row tracker, when a UI shows one.
    pub bridge: Option<Arc<TrackerBridge>>,
    pub cancel: CancelToken,
}

/// A started game.
#[derive(Debug)]
pub struct GameProcess {
    /// The game process. Its stdout and stderr are piped in GUI/TUI mode and must be drained.
    pub child: tokio::process::Child,
    /// The Java runtime component it runs on.
    pub runtime_component: String,
    /// The command line, with tokens redacted.
    pub argv_redacted: String,
    /// Keeps the runtime "in use"; drop it when the process has exited.
    pub runtime_lease: RuntimeLease,
}

/// Everything [`build_argv`] needs.
#[derive(Clone, Debug, Default)]
pub struct ArgvInputs {
    pub client: bool,
    pub mac: bool,
    pub java: String,
    pub custom_jvm_args: String,
    pub min_ram_mb: i64,
    pub max_ram_mb: i64,
    /// `-Dlog4j.configurationFile` target, when the version needs one.
    pub log4j_config: Option<String>,
    pub natives: String,
    pub arm64_flags: bool,
    pub logo: String,
    pub title: String,
    pub manifest_jvm: Vec<String>,
    pub loader_jvm: Vec<String>,
    pub classpath: String,
    pub main_class: String,
    pub game_args: Vec<String>,
    pub placeholders: IndexMap<&'static str, String>,
    pub quick_join: Option<ServerFavorite>,
}

/// Assembles the argv (spec 7.4, with the dock flags in the JVM section).
pub fn build_argv(i: &ArgvInputs) -> Vec<String> {
    let mut argv = command::custom_jvm_tokens(&i.custom_jvm_args);
    argv.push(format!("-Xms{}m", i.min_ram_mb));
    argv.push(format!("-Xmx{}m", i.max_ram_mb));
    argv.push(command::LOG4J_NO_LOOKUPS_FLAG.to_string());
    if let Some(cfg) = &i.log4j_config {
        argv.push(format!("-Dlog4j.configurationFile={cfg}"));
    }
    if i.arm64_flags {
        argv.extend(crate::arm64::jvm_flags(&i.natives));
    }
    let title = command::sanitize_title(&i.title);
    // The dock flags carry the pack name and logo path, which must never go through
    // placeholder substitution (a pack named `${auth_access_token}` would put the token in
    // the dock): remember where they belong and insert them after substituting.
    let dock_at = argv.len();
    argv.extend(i.manifest_jvm.iter().cloned());
    if !i.loader_jvm.is_empty() {
        argv.extend(i.loader_jvm.iter().cloned());
    }
    if i.manifest_jvm.is_empty() {
        argv.push(format!("-Djava.library.path={}", i.natives));
        argv.push("-cp".into());
        argv.push(i.classpath.clone());
    }
    argv.push(i.main_class.clone());
    argv.extend(i.game_args.iter().cloned());
    command::substitute_placeholders(&mut argv, &i.placeholders);
    if i.client && i.mac {
        argv.splice(
            dock_at..dock_at,
            command::mac_dock_jvm_args(&i.logo, &title),
        );
    }
    if i.client
        && let Some(s) = &i.quick_join
    {
        argv.push("--server".into());
        argv.push(s.host.clone());
        if s.port != ServerFavorite::DEFAULT_PORT {
            argv.push("--port".into());
            argv.push(s.port.to_string());
        }
    }
    if i.client {
        argv.push("--title".into());
        argv.push(title);
        argv.push("--icon".into());
        argv.push(i.logo.clone());
    }
    argv.insert(0, i.java.clone());
    argv
}

/// The client and loader main class and game args (spec 7.3).
pub fn main_class_and_game_args(
    client: bool,
    lm: &LibraryManifest,
    loader: Option<&dyn ModLoader>,
    host: &mmcl_mojang::HostInfo,
) -> (String, Vec<String>) {
    let vanilla_args = || lm.client.game_arguments_vec(host);
    match loader {
        None => {
            let main = vanilla::vanilla_main_class(&lm.client);
            (main, if client { vanilla_args() } else { Vec::new() })
        }
        Some(l) => {
            let main = if client {
                l.main_class()
            } else {
                l.server_main_class().unwrap_or_else(|| l.main_class())
            };
            let args = if client {
                command::combine_game_args_vec(
                    vanilla_args(),
                    command::split_command_line(&l.minecraft_arguments()),
                )
            } else {
                Vec::new()
            };
            (main, args)
        }
    }
}

/// Makes `bin/natives` and its direct children readable, writable and executable by the
/// owner (Unix); a no-op elsewhere.
fn open_natives_permissions(natives: &std::path::Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let set = |p: &std::path::Path| {
            if let Ok(m) = std::fs::metadata(p) {
                let mut perm = m.permissions();
                perm.set_mode(perm.mode() | 0o700);
                let _ = std::fs::set_permissions(p, perm);
            }
        };
        if natives.exists() {
            set(natives);
            if let Ok(rd) = std::fs::read_dir(natives) {
                for e in rd.flatten() {
                    set(&e.path());
                }
            }
        }
    }
    #[cfg(not(unix))]
    let _ = natives;
}

/// Mojang's Log4Shell config for `mc_version` under `metadata/log4j-configs/<sha1>.xml`, or
/// `None` (flag only, or a download failure, which only warns).
async fn log4j_config(env: &LaunchEnv, mc_version: &str) -> Option<String> {
    match command::log4j_plan(mc_version) {
        Log4jPlan::FlagOnly => {
            mmcl_log::debug(&tf!("log.gameModPackLauncher.log4jBuiltin", mc_version));
            None
        }
        Log4jPlan::File { url, sha1, .. } => {
            let path = env
                .paths
                .metadata_dir()
                .join("log4j-configs")
                .join(format!("{sha1}.xml"));
            let mf = mmcl_modpack::ManagedFile::at_path(
                crate::batch::spec(url, "", Some(sha1)),
                &path,
                env.paths.root(),
            );
            match mf.update(&PrepareContext::NONE, env.http.as_ref()).await {
                Ok(_) => {
                    let p = path.to_string_lossy().into_owned();
                    mmcl_log::debug(&tf!("log.gameModPackLauncher.appliedLog4jConfig", &p));
                    Some(p)
                }
                Err(_) => {
                    mmcl_log::warn_silent(&tf!(
                        "log.gameModPackLauncher.log4jConfigDownloadFailed",
                        url
                    ));
                    None
                }
            }
        }
    }
}

/// The RAM settings, with the server-mode override.
fn ram_mb(env: &LaunchEnv) -> (i64, i64) {
    let (mut min, mut max) = (env.config.min_ram_mb(), env.config.max_ram_mb());
    if env.mode() == GameMode::Server {
        if let Some(v) = env.heap_override.xms_mb {
            mmcl_log::debug(&tf!(
                "log.gameModPackLauncher.configMinRam",
                format!("{v}m")
            ));
            min = v as i64;
        }
        if let Some(v) = env.heap_override.xmx_mb {
            mmcl_log::debug(&tf!(
                "log.gameModPackLauncher.configMaxRam",
                format!("{v}m")
            ));
            max = v as i64;
        }
    }
    (min, max)
}

/// Prepares and starts the game.
pub async fn launch(env: &LaunchEnv, req: LaunchRequest) -> Result<GameProcess> {
    let client = env.mode() == GameMode::Client;
    let auth = match (&req.account, client) {
        (_, false) => AuthArguments::none(),
        (Some(a), true) => AuthArguments::of(a),
        (None, true) => return Err(lerr!("gameModPackLauncher.error.noAccount")),
    };
    let pack = req.pack.clone();
    let prepared = prepare(
        env,
        &pack,
        req.sink.clone(),
        req.bridge.clone(),
        &req.cancel,
    )
    .await?;
    let lm = prepared.library_manifest.clone();
    let component = lm.runtime_component();
    mmcl_log::std(&tf!(
        "log.gameModPackLauncher.versionRequiresRuntime",
        &prepared.mc_version,
        &component,
        lm.java_major().to_string()
    ));
    let java = env
        .runtimes
        .verify(&component, None, &req.cancel)
        .await
        .map_err(|e| LaunchError::Runtime(e).wrap_step("launch.error.runtimeFailed"))?
        .java;
    req.sink
        .set_curr_text(&t!("gameModPackLauncher.progress.preparingLaunchCommand"));

    let root = pack.checked_pack_root()?;
    let natives = vanilla::natives_path(&root);
    open_natives_permissions(&natives);
    let (main_class, game_args) =
        main_class_and_game_args(client, &lm, prepared.loader.as_deref(), &env.host);
    let natives_s = natives.to_string_lossy().into_owned();
    let classpath = command::join_classpath(&prepared.classpath, PATH_SEPARATOR);
    let shared_assets = env.paths.shared_assets_dir();
    let version_name = match &prepared.loader {
        None => prepared.mc_version.clone(),
        Some(l) => l.loader_version(),
    };
    let placeholders = command::placeholder_map(&PlaceholderInputs {
        client,
        natives_directory: natives_s.clone(),
        classpath: classpath.clone(),
        launcher_version: mmcl_core::build::VERSION.to_string(),
        classpath_separator: PATH_SEPARATOR.to_string(),
        library_directory: root.join("libraries").to_string_lossy().into_owned(),
        auth_player_name: auth.player_name.clone(),
        version_name,
        game_directory: root.to_string_lossy().into_owned(),
        assets_root: shared_assets.to_string_lossy().into_owned(),
        assets_index_name: prepared.assets.index_id.clone(),
        auth_uuid: auth.uuid.clone(),
        auth_access_token: auth.access_token.clone(),
        user_type: auth.user_type.clone(),
        client_id: auth.client_id.clone(),
        auth_xuid: auth.xuid.clone(),
        auth_session: auth.session.clone(),
        game_assets: game_assets_path(&shared_assets, &root, Some(&prepared.assets))
            .to_string_lossy()
            .into_owned(),
    });
    let (min_ram_mb, max_ram_mb) = ram_mb(env);
    let quick_join = pack.consume_quick_join_server();
    let logo: PathBuf = pack.environment().logo_path_raw(&pack);
    let inputs = ArgvInputs {
        client,
        mac: env.host.os == Os::MacOs,
        java: java.to_string_lossy().into_owned(),
        custom_jvm_args: env.config.custom_jvm_args(),
        min_ram_mb,
        max_ram_mb,
        log4j_config: log4j_config(env, &prepared.mc_version).await,
        natives: natives_s,
        arm64_flags: crate::arm64::is_needed(env, &prepared.mc_version),
        logo: logo.to_string_lossy().into_owned(),
        title: pack.name(),
        manifest_jvm: lm.client.jvm_arguments_vec(&env.host),
        loader_jvm: match (&prepared.loader, client) {
            (Some(l), true) => l.jvm_arguments(),
            _ => Vec::new(),
        },
        classpath,
        main_class,
        game_args,
        placeholders,
        quick_join,
    };
    let argv = build_argv(&inputs);
    req.sink
        .signal_complete(&t!("gameModPackLauncher.progress.startingMinecraft"));
    start(env, &root, argv, component).await
}

async fn start(
    env: &LaunchEnv,
    root: &std::path::Path,
    argv: Vec<String>,
    component: String,
) -> Result<GameProcess> {
    let redacted = mmcl_log::redact(&argv.join(" ")).into_owned();
    mmcl_log::debug(&tf!("log.gameModPackLauncher.launchingGame", &redacted));
    if cfg!(windows)
        && command::command_line_utf16_len(&argv) > command::WINDOWS_COMMAND_LINE_WARN_UNITS
    {
        mmcl_log::error_silent(&tf!(
            "log.gameModPackLauncher.commandLineTooLong",
            command::command_line_utf16_len(&argv).to_string()
        ));
    }
    let io = if env.mode() == GameMode::Server {
        ChildIo::Inherit
    } else {
        ChildIo::Pipe
    };
    let child = spawn_game(&argv, root, io)
        .map_err(|e| lerr!("launch.error.startFailed", e.to_string()))?;
    let (lease, released) = RuntimeLease::new();
    if let Some(pid) = child.id() {
        env.runtimes.mark_in_use(&component, pid, released);
    }
    Ok(GameProcess {
        child,
        runtime_component: component,
        argv_redacted: redacted,
        runtime_lease: lease,
    })
}

#[cfg(test)]
mod tests;
