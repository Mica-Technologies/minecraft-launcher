// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Cleanroom (a Forge 1.12.2 fork on Java 25 and LWJGL 3): the installer jar at
//! `bin/modpack.jar` (the pack's `packForgeURL`, one jar for both sides) and its two profiles.
//!
//! Cleanroom's `version.json` is standalone: no `inheritsFrom`, its own `javaVersion`
//! (`java-runtime-epsilon`, Java 25), `assetIndex`, client and server downloads, and every
//! library including LWJGL 3 and its natives. So unlike Forge it does not sit on top of the
//! vanilla client.json: [`ModLoader::standalone_manifest`] hands the launch pipeline that file in
//! place of Mojang's, which keeps vanilla LWJGL 2 (and every other vanilla library) off the
//! classpath and takes the runtime, assets and game jar from Cleanroom's own profile.
//!
//! The libraries the installer embeds (`downloads.artifact.url` blank: the universal jar under
//! `maven/`) are this loader's own classpath; everything else is verified by the Minecraft
//! branch from the standalone manifest. There are no install processors.
//!
//! A dedicated server is assembled the same way, mirroring the installer's `server` mode: the
//! same libraries, the server jar from `downloads.server`, `serverMainClass`, and
//! `--tweakClass` per `serverTweakers`.

use std::collections::HashSet;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use futures::future::BoxFuture;
use mmcl_i18n::{t, tf};
use mmcl_modpack::ManagedFile;
use mmcl_mojang::cleanroom_profile::{CleanroomProfile, cleanroom_minecraft_version};
use mmcl_mojang::forge_profile::ForgeVersionManifest;
use mmcl_mojang::host::Os;
use mmcl_mojang::{ClientJson, HostInfo};
use mmcl_progress::ProgressSink;
use parking_lot::Mutex;
use serde_json::{Map, Value};

use super::forge::{ForgeAsset, INSTALL_PROFILE_JSON, VERSION_JSON, plan_libraries};
use super::{LoaderKind, ModLoader};
use crate::batch::{BATCH_TIMEOUT, BatchKeys, blocking, file_name_of, join_rel, run_batch, spec};
use crate::error::{LaunchError, Result};
use crate::run::PackRun;

/// The Maven coordinate prefix of Cleanroom's own artifact.
pub const CLEANROOM_COORD_PREFIX: &str = "com.cleanroommc:cleanroom:";

/// What an installer holds, read once per installer file.
#[derive(Debug)]
pub struct CleanroomInstall {
    /// `version.json` as written.
    pub version_json: Map<String, Value>,
    /// `install_profile.json`, when the installer has a readable one.
    pub profile: Option<CleanroomProfile>,
}

/// Reads `version.json` (required) and `install_profile.json` (optional) out of an installer.
pub fn read_install(installer: &Path) -> Result<CleanroomInstall> {
    let unreadable = |_| LaunchError::Localized {
        key: "UNABLE_ACCESS_FORGE_JAR_TEXT",
        args: Vec::new(),
    };
    let vj = crate::jar::read_entry(installer, VERSION_JSON).map_err(unreadable)?;
    let ip = crate::jar::read_entry(installer, INSTALL_PROFILE_JSON).map_err(unreadable)?;
    parse_install(vj.as_deref(), ip.as_deref())
}

/// [`read_install`] over the two entries' bytes. `id` and `mainClass` are required, as for
/// Forge; an unreadable install profile only loses the server facts it carries.
pub fn parse_install(
    version_json: Option<&[u8]>,
    install_profile: Option<&[u8]>,
) -> Result<CleanroomInstall> {
    let Some(vj) = version_json else {
        return Err(mmcl_mojang::ManifestError::ForgeVersionFileMissing.into());
    };
    // Validates `id` and `mainClass`.
    let m = ForgeVersionManifest::select(Some(vj), None)?;
    let profile = match install_profile.map(CleanroomProfile::parse) {
        Some(Ok(p)) => Some(p),
        Some(Err(e)) => {
            mmcl_log::warn_silent(&tf!("log.cleanroomLoader.profileUnreadable", e.to_string()));
            None
        }
        None => None,
    };
    Ok(CleanroomInstall {
        version_json: m.raw().clone(),
        profile,
    })
}

/// `true` for a library the installer carries itself: a `downloads.artifact` whose `url` is
/// blank (the universal jar, under the installer's `maven/`).
pub fn is_embedded_library(lib: &Value) -> bool {
    lib.get("downloads")
        .and_then(|d| d.get("artifact"))
        .and_then(Value::as_object)
        .is_some_and(|a| {
            a.get("url")
                .and_then(Value::as_str)
                .is_none_or(|u| u.trim().is_empty())
        })
}

/// Splits `version.json` into the standalone manifest (every library but the embedded ones,
/// which the Minecraft branch cannot download) and a Forge-shaped manifest of just the
/// embedded libraries (planned like Forge's `maven/` entries).
pub fn split_manifest(
    version_json: &Map<String, Value>,
) -> Result<(ClientJson, ForgeVersionManifest)> {
    let libs: &[Value] = version_json
        .get("libraries")
        .and_then(Value::as_array)
        .map_or(&[], Vec::as_slice);
    let (embedded, downloadable): (Vec<Value>, Vec<Value>) =
        libs.iter().cloned().partition(is_embedded_library);
    let mut standalone = version_json.clone();
    standalone.insert("libraries".into(), Value::Array(downloadable));
    let mut own = version_json.clone();
    own.insert("libraries".into(), Value::Array(embedded));
    Ok((
        ClientJson::from_value(Value::Object(standalone))?,
        ForgeVersionManifest::from_object(own)?,
    ))
}

/// The loader's facts and plans for one installer file.
#[derive(Debug)]
struct State {
    id: String,
    minecraft_version: String,
    main_class: String,
    minecraft_arguments: String,
    profile: Option<CleanroomProfile>,
    manifest: Arc<ClientJson>,
    own: ForgeVersionManifest,
    /// Planned once per installer.
    libraries: Mutex<Option<Arc<Vec<ForgeAsset>>>>,
}

impl State {
    fn of(install: CleanroomInstall) -> Result<State> {
        let (manifest, own) = split_manifest(&install.version_json)?;
        Ok(State {
            id: own.id().to_string(),
            minecraft_version: cleanroom_minecraft_version(
                install.profile.as_ref(),
                &install.version_json,
            ),
            main_class: own.main_class().to_string(),
            minecraft_arguments: install
                .version_json
                .get("minecraftArguments")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_string(),
            profile: install.profile,
            manifest: Arc::new(manifest),
            own,
            libraries: Mutex::new(None),
        })
    }
}

/// Answers Cleanroom's startup confirmation prompts on an unattended server.
pub const SERVER_AUTO_CONFIRM: &str = "-Dfml.queryResult=confirm";

/// A Cleanroom loader.
pub struct CleanroomLoader {
    installer: ManagedFile,
    host: HostInfo,
    state: Mutex<Arc<State>>,
}

impl std::fmt::Debug for CleanroomLoader {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = self.state.lock().clone();
        f.debug_struct("CleanroomLoader")
            .field("id", &s.id)
            .field("minecraft_version", &s.minecraft_version)
            .finish_non_exhaustive()
    }
}

impl CleanroomLoader {
    /// Downloads and verifies the installer (`bin/modpack.jar`) and reads its profiles.
    pub async fn open(run: &PackRun, url: &str, sha1: Option<&str>) -> Result<CleanroomLoader> {
        let path = run.root()?.join("bin").join("modpack.jar");
        let installer = run.managed_at(spec(url, "", sha1), &path);
        installer.update(&run.ctx, run.env.http.as_ref()).await?;
        let p = path.clone();
        let install = blocking(move || read_install(&p)).await??;
        Ok(CleanroomLoader {
            state: Mutex::new(Arc::new(State::of(install)?)),
            host: run.env.host.clone(),
            installer,
        })
    }

    /// The installer path.
    pub fn installer_path(&self) -> PathBuf {
        self.installer.full_path()
    }

    fn state(&self) -> Arc<State> {
        self.state.lock().clone()
    }

    /// Re-verifies the installer under the run's context, re-reading it when it changed.
    async fn refresh_installer(&self, run: &PackRun) -> Result<()> {
        let changed = self
            .installer
            .update(&run.ctx, run.env.http.as_ref())
            .await?;
        if changed {
            let p = self.installer_path();
            let install = blocking(move || read_install(&p)).await??;
            *self.state.lock() = Arc::new(State::of(install)?);
        }
        Ok(())
    }

    /// The installer-embedded libraries, planned once per installer.
    async fn library_list(&self) -> Result<Arc<Vec<ForgeAsset>>> {
        let state = self.state();
        if let Some(l) = state.libraries.lock().clone() {
            return Ok(l);
        }
        let p = self.installer_path();
        let entries: HashSet<String> = blocking(move || crate::jar::entry_names(&p))
            .await?
            .map_err(|_| LaunchError::Localized {
                key: "UNABLE_ACCESS_FORGE_JAR_TEXT",
                args: Vec::new(),
            })?;
        let installer = self.installer_path();
        let list = Arc::new(plan_embedded(&state.own, &entries, &installer)?);
        *state.libraries.lock() = Some(list.clone());
        Ok(list)
    }

    async fn build(&self, run: &PackRun, sink: &Arc<dyn ProgressSink>) -> Result<Vec<String>> {
        self.refresh_installer(run).await?;
        let assets = self.library_list().await?;
        sink.submit_progress(&t!("forgeLoader.gotAssetList"), 20.0);
        run.check_cancelled()?;
        let libs = run.libraries_dir()?;
        let n = assets.len().max(1) as f64;
        let jobs = assets
            .iter()
            .map(|a| {
                let mut s = spec(&a.url, &a.local, a.sha1.as_deref());
                s.client_req = a.client_req;
                s.server_req = a.server_req;
                let mf = run.managed(s, &libs, Some(sink.as_ref()));
                let (ctx, http, sink) = (run.ctx.clone(), run.env.http.clone(), sink.clone());
                let fut: BoxFuture<'static, Result<()>> = Box::pin(async move {
                    mf.update(&ctx, http.as_ref()).await?;
                    sink.submit_progress(
                        &tf!("forgeLoader.verifiedAsset", mf.file_name()),
                        60.0 / n,
                    );
                    Ok(())
                });
                fut
            })
            .collect();
        run_batch(
            &run.env.pool,
            jobs,
            BATCH_TIMEOUT,
            &run.cancel,
            BatchKeys {
                timeout: "forge.error.libsTimedOut",
                interrupted: "forge.error.libsInterrupted",
                failed: Some("forge.error.libDownloadFailed"),
                pass_modpack: true,
            },
        )
        .await?;
        let cp: Vec<String> = assets
            .iter()
            .map(|a| join_rel(&libs, &a.local))
            .inspect(|p| {
                sink.submit_progress(
                    &tf!("forgeLoader.addedToClasspath", file_name_of(p)),
                    20.0 / n,
                )
            })
            .map(|p| p.to_string_lossy().into_owned())
            .collect();
        Ok(crate::command::ordered_unique(cp))
    }
}

/// Plans the installer-embedded libraries of `own` (the embedded half of
/// [`split_manifest`]). Each must really be in the installer under `maven/`: one that is not
/// would otherwise be fetched from a made-up repository URL.
pub fn plan_embedded(
    own: &ForgeVersionManifest,
    entries: &HashSet<String>,
    installer: &Path,
) -> Result<Vec<ForgeAsset>> {
    let abs = std::path::absolute(installer).unwrap_or_else(|_| installer.to_path_buf());
    let file = url::Url::from_file_path(&abs)
        .map(|u| u.to_string())
        .unwrap_or_else(|_| format!("file:{}", abs.display()));
    let list = plan_libraries(
        own,
        CLEANROOM_COORD_PREFIX,
        &|p| entries.contains(&format!("maven/{p}")),
        &|n| entries.contains(n),
        &|e| format!("jar:{file}!/{e}"),
    )?;
    if let Some(missing) = list.iter().find(|a| !a.url.starts_with("jar:")) {
        return Err(LaunchError::Localized {
            key: "launch.error.cleanroomEmbeddedLibraryMissing",
            args: vec![file_name_of(Path::new(&missing.local))],
        });
    }
    Ok(list)
}

impl ModLoader for CleanroomLoader {
    fn kind(&self) -> LoaderKind {
        LoaderKind::Cleanroom
    }
    fn minecraft_version(&self) -> String {
        self.state().minecraft_version.clone()
    }
    fn loader_version(&self) -> String {
        self.state().id.clone()
    }
    fn minecraft_arguments(&self) -> String {
        self.state().minecraft_arguments.clone()
    }
    fn jvm_arguments(&self) -> Vec<String> {
        let profile = self
            .state()
            .profile
            .as_ref()
            .map(CleanroomProfile::jvm_args)
            .unwrap_or_default();
        client_jvm_args(profile, &self.host)
    }
    fn main_class(&self) -> String {
        self.state().main_class.clone()
    }
    fn server_main_class(&self) -> Option<String> {
        let s = self.state();
        Some(
            s.profile
                .as_ref()
                .and_then(CleanroomProfile::server_main_class)
                .unwrap_or_else(|| s.main_class.clone()),
        )
    }
    fn server_arguments(&self) -> Vec<String> {
        match &self.state().profile {
            Some(p) => p.server_game_args(),
            None => vec![
                "--tweakClass".to_string(),
                mmcl_mojang::cleanroom_profile::DEFAULT_SERVER_TWEAKER.to_string(),
            ],
        }
    }
    /// `-Dfml.queryResult=confirm`, which answers Cleanroom's "Fugue and Scalar are not
    /// installed" console prompt on a dedicated server.
    fn server_jvm_arguments(&self) -> Vec<String> {
        vec![SERVER_AUTO_CONFIRM.to_string()]
    }
    fn standalone_manifest(&self) -> Option<Arc<ClientJson>> {
        Some(self.state().manifest.clone())
    }
    fn build_classpath<'a>(
        &'a self,
        run: &'a PackRun,
        sink: &'a Arc<dyn ProgressSink>,
    ) -> BoxFuture<'a, Result<Vec<String>>> {
        Box::pin(self.build(run, sink))
    }
    fn installer_file(&self) -> Option<&ManagedFile> {
        Some(&self.installer)
    }
}

/// The client JVM arguments, as the installer writes them into a launcher profile: the
/// profile's `jvmArgs`, plus `-XstartOnFirstThread` on macOS (LWJGL 3 must own the main
/// thread there, and the standalone `version.json` has no `arguments.jvm` to say so).
pub fn client_jvm_args(mut profile_args: Vec<String>, host: &HostInfo) -> Vec<String> {
    const FIRST_THREAD: &str = "-XstartOnFirstThread";
    if host.os == Os::MacOs && !profile_args.iter().any(|a| a == FIRST_THREAD) {
        profile_args.push(FIRST_THREAD.to_string());
    }
    profile_args
}

/// Empties `bin/natives` (an earlier Forge launch of the same pack leaves LWJGL 2 natives
/// there, and LWJGL 3 would find its own `lwjgl.dll` name among them). `Ok` when it is absent.
pub fn clear_natives(natives: &Path) -> std::io::Result<()> {
    match fs::remove_dir_all(natives) {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e),
        _ => fs::create_dir_all(natives),
    }
}

#[cfg(test)]
mod tests;
