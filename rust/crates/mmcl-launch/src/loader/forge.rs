// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Forge and NeoForge (Java `GameModLoaderForge` / `GameModLoaderNeoForge`): the installer
//! jar, its version manifest (modern `version.json` or legacy `versionInfo`), the loader
//! libraries (embedded Maven entries, universal jars, the legacy top-level universal jar) and
//! the classpath with the patched client. Install processors live in `processors`.
//!
//! Fix over Java: a NeoForge installer whose version manifest has no `inheritsFrom` maps its
//! version id (`neoforge-21.1.77`, `neoforge-26.1.0.5-beta`) to the Minecraft version
//! (`1.21.1`, `26.1`) instead of reporting the whole id.

use std::collections::HashSet;
use std::fs;
use std::path::{MAIN_SEPARATOR_STR, Path, PathBuf};
use std::sync::Arc;

use futures::future::BoxFuture;
use mmcl_i18n::{t, tf};
use mmcl_manifest::{ManifestFile, Side};
use mmcl_modpack::ManagedFile;
use mmcl_mojang::HostInfo;
use mmcl_mojang::forge_profile::{ForgeVersionManifest, InstallProfile};
use mmcl_progress::ProgressSink;
use parking_lot::Mutex;

use super::planner;
use super::{LoaderKind, ModLoader};
use crate::batch::{BATCH_TIMEOUT, BatchKeys, blocking, file_name_of, join_rel, run_batch, spec};
use crate::error::{LaunchError, Result};
use crate::run::PackRun;

/// The installer's launcher-profile manifest.
pub const VERSION_JSON: &str = "version.json";
/// The installer's install profile.
pub const INSTALL_PROFILE_JSON: &str = "install_profile.json";

/// One loader library to verify.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ForgeAsset {
    /// URL (`https:` or a contained `jar:file:` entry of the installer).
    pub url: String,
    /// Path under `libraries/` (platform separators).
    pub local: String,
    /// SHA-1, unless suppressed.
    pub sha1: Option<String>,
    pub client_req: bool,
    pub server_req: bool,
}

impl ForgeAsset {
    /// Whether this asset is used on `side`.
    pub fn required_for(&self, side: Side) -> bool {
        match side {
            Side::Client => self.client_req,
            Side::Server => self.server_req,
        }
    }

    fn spec(&self) -> ManifestFile {
        let mut s = spec(&self.url, &self.local, self.sha1.as_deref());
        s.client_req = self.client_req;
        s.server_req = self.server_req;
        s
    }
}

#[derive(Default)]
struct Caches {
    entries: Option<Arc<HashSet<String>>>,
    libraries: Option<Arc<Vec<ForgeAsset>>>,
}

/// A Forge or NeoForge loader.
pub struct ForgeLoader {
    kind: LoaderKind,
    installer: ManagedFile,
    host: HostInfo,
    manifest: Mutex<Arc<ForgeVersionManifest>>,
    caches: Mutex<Caches>,
    facts: Mutex<Facts>,
}

/// What the loader reports, read from the current version manifest (refreshed when the
/// installer is re-downloaded).
#[derive(Clone, Debug)]
struct Facts {
    id: String,
    minecraft_version: String,
    main_class: String,
    minecraft_arguments: String,
}

impl Facts {
    fn of(kind: LoaderKind, m: &ForgeVersionManifest, host: &HostInfo) -> Facts {
        Facts {
            id: m.id().to_string(),
            minecraft_version: minecraft_version_for(kind, m),
            main_class: m.main_class().to_string(),
            minecraft_arguments: m.minecraft_arguments(host),
        }
    }
}

impl std::fmt::Debug for ForgeLoader {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let facts = self.facts.lock().clone();
        f.debug_struct("ForgeLoader")
            .field("kind", &self.kind)
            .field("id", &facts.id)
            .field("minecraft_version", &facts.minecraft_version)
            .finish_non_exhaustive()
    }
}

/// Reads the version manifest out of an installer jar.
pub fn read_version_manifest(installer: &Path) -> Result<ForgeVersionManifest> {
    let unreadable = |_| LaunchError::Localized {
        key: "UNABLE_ACCESS_FORGE_JAR_TEXT",
        args: Vec::new(),
    };
    let vj = crate::jar::read_entry(installer, VERSION_JSON).map_err(unreadable)?;
    let ip = match vj {
        Some(_) => None,
        None => crate::jar::read_entry(installer, INSTALL_PROFILE_JSON).map_err(unreadable)?,
    };
    Ok(ForgeVersionManifest::select(vj.as_deref(), ip.as_deref())?)
}

/// The Minecraft version a NeoForge version number stands for: `20.4.x` → `1.20.4`,
/// `21.0.x` → `1.21`, and from the year-based scheme on (`26.1.0.x` → `26.1`,
/// `26.1.1.x` → `26.1.1`). `None` when the number does not look like either.
pub fn mc_version_from_neoforge(version: &str) -> Option<String> {
    let num = |s: &str| -> Option<u32> {
        let d: String = s.chars().take_while(char::is_ascii_digit).collect();
        d.parse().ok()
    };
    let core = version.split(['-', '+']).next().unwrap_or(version);
    let parts: Vec<&str> = core.split('.').collect();
    let major = num(parts.first()?)?;
    let minor = parts.get(1).and_then(|p| num(p)).unwrap_or(0);
    let patch = parts.get(2).and_then(|p| num(p)).unwrap_or(0);
    if major >= 25 {
        // Year-based: the first three numbers are the Minecraft version.
        return Some(if patch > 0 && parts.len() > 3 {
            format!("{major}.{minor}.{patch}")
        } else {
            format!("{major}.{minor}")
        });
    }
    if major >= 20 {
        return Some(if minor > 0 {
            format!("1.{major}.{minor}")
        } else {
            format!("1.{major}")
        });
    }
    None
}

/// The Minecraft version of a loader manifest: `inheritsFrom`, else (NeoForge) the mapped
/// NeoForge number, else the id before `-forge`, else the id.
pub fn minecraft_version_for(kind: LoaderKind, m: &ForgeVersionManifest) -> String {
    if m.raw()
        .get("inheritsFrom")
        .and_then(|v| v.as_str())
        .is_some()
    {
        return m.minecraft_version();
    }
    if kind == LoaderKind::NeoForge {
        let id = m.id();
        let num = id
            .strip_prefix("neoforge-")
            .or_else(|| id.strip_prefix("neoforged-"));
        if let Some(mc) = num.and_then(mc_version_from_neoforge) {
            return mc;
        }
    }
    m.minecraft_version()
}

impl ForgeLoader {
    /// Downloads and verifies the installer (`bin/modpack.jar`) and reads its version manifest.
    pub async fn open(
        run: &PackRun,
        kind: LoaderKind,
        url: &str,
        sha1: Option<&str>,
    ) -> Result<ForgeLoader> {
        let path = run.root()?.join("bin").join("modpack.jar");
        let installer = run.managed_at(spec(url, "", sha1), &path);
        installer.update(&run.ctx, run.env.http.as_ref()).await?;
        let p = path.clone();
        let manifest = blocking(move || read_version_manifest(&p)).await??;
        Ok(ForgeLoader {
            kind,
            host: run.env.host.clone(),
            facts: Mutex::new(Facts::of(kind, &manifest, &run.env.host)),
            manifest: Mutex::new(Arc::new(manifest)),
            caches: Mutex::new(Caches::default()),
            installer,
        })
    }

    /// The Maven coordinate prefix of the loader artifact.
    pub fn loader_coord_prefix(&self) -> &'static str {
        match self.kind {
            LoaderKind::NeoForge => "net.neoforged:neoforge:",
            _ => "net.minecraftforge:forge:",
        }
    }

    /// The installer path.
    pub fn installer_path(&self) -> PathBuf {
        self.installer.full_path()
    }

    /// Re-verifies the installer under the run's context, dropping every cache derived from
    /// it when it was re-downloaded.
    async fn refresh_installer(&self, run: &PackRun) -> Result<()> {
        let changed = self
            .installer
            .update(&run.ctx, run.env.http.as_ref())
            .await?;
        if changed {
            *self.caches.lock() = Caches::default();
            let p = self.installer_path();
            let m = blocking(move || read_version_manifest(&p)).await??;
            *self.facts.lock() = Facts::of(self.kind, &m, &self.host);
            *self.manifest.lock() = Arc::new(m);
        }
        Ok(())
    }

    /// Every entry name of the installer, read once. A failed read is not cached.
    pub(crate) async fn entry_names(&self) -> Arc<HashSet<String>> {
        if let Some(e) = self.caches.lock().entries.clone() {
            return e;
        }
        let p = self.installer_path();
        match blocking(move || crate::jar::entry_names(&p)).await {
            Ok(Ok(set)) => {
                let set = Arc::new(set);
                self.caches.lock().entries = Some(set.clone());
                set
            }
            _ => Arc::new(HashSet::new()),
        }
    }

    /// The `jar:file:` URL of an installer entry.
    pub(crate) fn installer_entry_url(&self, entry: &str) -> String {
        let abs =
            std::path::absolute(self.installer_path()).unwrap_or_else(|_| self.installer_path());
        let file = url::Url::from_file_path(&abs)
            .map(|u| u.to_string())
            .unwrap_or_else(|_| format!("file:{}", abs.display()));
        format!("jar:{file}!/{entry}")
    }

    /// The loader libraries, planned once per installer.
    pub async fn library_list(&self) -> Result<Arc<Vec<ForgeAsset>>> {
        if let Some(l) = self.caches.lock().libraries.clone() {
            return Ok(l);
        }
        let entries = self.entry_names().await;
        let manifest = self.manifest.lock().clone();
        let list = Arc::new(plan_libraries(
            &manifest,
            self.loader_coord_prefix(),
            &|p| entries.contains(&format!("maven/{p}")),
            &|n| entries.contains(n),
            &|e| self.installer_entry_url(e),
        )?);
        self.caches.lock().libraries = Some(list.clone());
        Ok(list)
    }

    /// Downloads and verifies the libraries (shared pool, 30 minutes) and builds the
    /// classpath, with the patched client and `MC_EXTRA` jars when they exist.
    pub async fn build_forge_classpath(
        &self,
        run: &PackRun,
        sink: &Arc<dyn ProgressSink>,
    ) -> Result<Vec<String>> {
        self.refresh_installer(run).await?;
        let assets = self.library_list().await?;
        sink.submit_progress(&t!("forgeLoader.gotAssetList"), 20.0);
        run.check_cancelled()?;
        let side = run.env.side();
        let libs = run.libraries_dir()?;
        let wanted: Vec<&ForgeAsset> = assets.iter().filter(|a| a.required_for(side)).collect();
        let n = assets.len().max(1) as f64;
        let jobs = wanted
            .iter()
            .map(|a| {
                let mf = run.managed(a.spec(), &libs, Some(sink.as_ref()));
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

        let mut cp: Vec<String> = Vec::with_capacity(wanted.len() + 2);
        let n = wanted.len().max(1) as f64;
        for a in &wanted {
            let p = libs.join(&a.local);
            sink.submit_progress(
                &tf!("forgeLoader.addedToClasspath", file_name_of(&p)),
                20.0 / n,
            );
            cp.push(p.to_string_lossy().into_owned());
        }
        let installer = self.installer_path();
        let side_name = run.env.side_name();
        let (l, s) = (libs.clone(), side_name.to_string());
        let extra = blocking(move || patched_and_extra(&installer, &l, &s)).await?;
        cp.extend(extra);
        Ok(crate::command::ordered_unique(cp))
    }
}

/// The patched client and `MC_EXTRA` jars of `side` that exist under `libs`.
fn patched_and_extra(installer: &Path, libs: &Path, side: &str) -> Vec<String> {
    let profile = match crate::jar::read_entry(installer, INSTALL_PROFILE_JSON) {
        Ok(Some(b)) => match InstallProfile::parse(&b) {
            Ok(p) => p,
            Err(e) => {
                mmcl_log::warn_silent(&tf!(
                    "log.forgeLoader.couldNotCheckPatchedClient",
                    e.to_string()
                ));
                return Vec::new();
            }
        },
        Ok(None) => return Vec::new(),
        Err(e) => {
            mmcl_log::warn_silent(&tf!(
                "log.forgeLoader.couldNotCheckPatchedClient",
                e.to_string()
            ));
            return Vec::new();
        }
    };
    let mut out = Vec::new();
    for (key, log_key) in [
        ("PATCHED", "log.forgeLoader.addedPatchedClient"),
        ("MC_EXTRA", "log.forgeLoader.addedMcExtra"),
    ] {
        let Some(coord) = profile.data_value(key, side) else {
            continue;
        };
        match mmcl_mojang::maven::to_relative_path_strict(&coord) {
            Ok(rel) => {
                let f = join_rel(libs, &rel);
                if f.is_file() {
                    mmcl_log::debug(&tf!(log_key, f.display().to_string()));
                    out.push(f.to_string_lossy().into_owned());
                }
            }
            Err(e) => mmcl_log::warn_silent(&tf!(
                "log.forgeLoader.couldNotCheckPatchedClient",
                e.to_string()
            )),
        }
    }
    out
}

/// Plans the loader libraries of a version manifest (pure apart from the probes).
pub fn plan_libraries(
    manifest: &ForgeVersionManifest,
    prefix: &str,
    has_maven: &dyn Fn(&str) -> bool,
    has_top: &dyn Fn(&str) -> bool,
    entry_url: &dyn Fn(&str) -> String,
) -> Result<Vec<ForgeAsset>> {
    let mut out = Vec::new();
    for lib in manifest.libraries() {
        // Java's getRequiredString / inferredRepoPath threw here: a loader library the
        // launcher cannot place must fail the step, not vanish from the classpath.
        let name = lib.name().ok_or_else(|| {
            LaunchError::Manifest(mmcl_mojang::ManifestError::MissingField("name".into()))
        })?;
        let inferred = planner::inferred_repo_path(&name)
            .ok_or_else(|| LaunchError::Coord(mmcl_mojang::CoordError::Malformed(name.clone())))?;
        let artifact = lib.artifact();
        let (repo_path, specified) = match artifact.as_ref().and_then(|a| a.path.clone()) {
            Some(p) if !p.trim().is_empty() => (p, true),
            _ => (inferred.clone(), false),
        };
        let sha1 = artifact.as_ref().and_then(|a| a.sha1.clone());
        let plan = planner::plan(
            &name, &repo_path, &inferred, specified, prefix, has_maven, has_top,
        );
        let artifact_url = artifact
            .as_ref()
            .and_then(|a| a.url.clone())
            .filter(|u| !u.trim().is_empty());
        let url = if let Some(u) = artifact_url {
            u.trim().to_string()
        } else if specified && has_maven(&plan.repo_path) {
            entry_url(&format!("maven/{}", plan.repo_path))
        } else if plan.legacy_top_level_universal {
            entry_url(plan.legacy_top_level_entry.as_deref().unwrap_or_default())
        } else {
            planner::maven_url(
                &name,
                lib.url().as_deref(),
                &plan.repo_path,
                specified,
                &inferred,
            )
        };
        let url = planner::apply_url_overrides(url);
        let local = planner::local_path(
            &name,
            &plan.repo_path,
            specified,
            &inferred,
            MAIN_SEPARATOR_STR,
            ".jar",
        );
        let (client_req, server_req) = (lib.client_required(), lib.server_required());
        out.push(ForgeAsset {
            url,
            local,
            sha1: if plan.suppress_sha1 { None } else { sha1 },
            client_req,
            server_req,
        });
        if plan.add_universal_as_extra
            && let Some(uni) = &plan.universal_repo_path
        {
            out.push(ForgeAsset {
                url: entry_url(&format!("maven/{uni}")),
                local: uni.replace('/', MAIN_SEPARATOR_STR),
                sha1: None,
                client_req,
                server_req,
            });
        }
    }
    Ok(out)
}

impl ModLoader for ForgeLoader {
    fn kind(&self) -> LoaderKind {
        self.kind
    }
    fn minecraft_version(&self) -> String {
        self.facts.lock().minecraft_version.clone()
    }
    fn loader_version(&self) -> String {
        self.facts.lock().id.clone()
    }
    fn minecraft_arguments(&self) -> String {
        self.facts.lock().minecraft_arguments.clone()
    }
    fn jvm_arguments(&self) -> Vec<String> {
        self.manifest.lock().jvm_arguments_vec(&self.host)
    }
    fn main_class(&self) -> String {
        self.facts.lock().main_class.clone()
    }
    fn server_main_class(&self) -> Option<String> {
        let main = self.facts.lock().main_class.clone();
        mmcl_mojang::forge_profile::server_main_class_for(&main).map(str::to_string)
    }
    fn build_classpath<'a>(
        &'a self,
        run: &'a PackRun,
        sink: &'a Arc<dyn ProgressSink>,
    ) -> BoxFuture<'a, Result<Vec<String>>> {
        Box::pin(self.build_forge_classpath(run, sink))
    }
    fn run_post_install<'a>(
        &'a self,
        run: &'a PackRun,
        sink: &'a Arc<dyn ProgressSink>,
        runtime_component: &'a str,
    ) -> BoxFuture<'a, Result<()>> {
        Box::pin(async move {
            let entries = self.entry_names().await;
            super::processors::run_processors(
                &super::processors::ProcessorEnv {
                    installer: self.installer_path(),
                    entries,
                    minecraft_version: self.minecraft_version(),
                },
                run,
                sink,
                runtime_component,
            )
            .await
        })
    }
    fn installer_file(&self) -> Option<&ManagedFile> {
        Some(&self.installer)
    }
}

/// Deletes a file, `Ok` when it is already gone.
pub(crate) fn remove_if_exists(p: &Path) -> std::io::Result<()> {
    match fs::remove_file(p) {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e),
        _ => Ok(()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn manifest(v: serde_json::Value) -> ForgeVersionManifest {
        ForgeVersionManifest::from_object(v.as_object().unwrap().clone()).unwrap()
    }

    #[test]
    fn neoforge_versions_map_to_minecraft() {
        assert_eq!(
            mc_version_from_neoforge("21.1.77").as_deref(),
            Some("1.21.1")
        );
        assert_eq!(
            mc_version_from_neoforge("20.4.237").as_deref(),
            Some("1.20.4")
        );
        assert_eq!(
            mc_version_from_neoforge("21.0.167").as_deref(),
            Some("1.21")
        );
        assert_eq!(
            mc_version_from_neoforge("26.1.0.5-beta").as_deref(),
            Some("26.1")
        );
        assert_eq!(
            mc_version_from_neoforge("26.1.1.3").as_deref(),
            Some("26.1.1")
        );
        // Three parts in the year scheme: the third is the build number.
        assert_eq!(mc_version_from_neoforge("26.2.14").as_deref(), Some("26.2"));
        assert_eq!(mc_version_from_neoforge("abc"), None);
        assert_eq!(mc_version_from_neoforge("10.2"), None);

        let neo = manifest(
            json!({"id": "neoforge-26.1.0.5-beta", "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher"}),
        );
        assert_eq!(minecraft_version_for(LoaderKind::NeoForge, &neo), "26.1");
        let neo =
            manifest(json!({"id": "neoforge-21.1.77", "inheritsFrom": "1.21.1", "mainClass": "x"}));
        assert_eq!(minecraft_version_for(LoaderKind::NeoForge, &neo), "1.21.1");
        // NeoForge's 1.20.1 builds still used the Forge id shape.
        let neo = manifest(json!({"id": "1.20.1-forge-47.1.106", "mainClass": "x"}));
        assert_eq!(minecraft_version_for(LoaderKind::NeoForge, &neo), "1.20.1");
        let forge = manifest(
            json!({"id": "1.12.2-forge-14.23.5.2860", "mainClass": "net.minecraft.launchwrapper.Launch"}),
        );
        assert_eq!(minecraft_version_for(LoaderKind::Forge, &forge), "1.12.2");
    }

    fn url_of(e: &str) -> String {
        format!("jar:file:///i.jar!/{e}")
    }

    #[test]
    fn modern_library_plan() {
        let m = manifest(json!({
        "id": "1.20.1-forge-47.2.0", "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
        "libraries": [
            {"name": "net.minecraftforge:forge:1.20.1-47.2.0",
             "downloads": {"artifact": {"path": "net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0.jar", "url": "", "sha1": "aa"}}},
            {"name": "org.ow2.asm:asm:9.5",
             "downloads": {"artifact": {"path": "org/ow2/asm/asm/9.5/asm-9.5.jar", "url": "https://maven.minecraftforge.net/org/ow2/asm/asm/9.5/asm-9.5.jar", "sha1": "bb"}}},
            {"name": "lzma:lzma:0.0.1"}
        ]}));
        let base = "maven/net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0.jar";
        let uni = "maven/net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-universal.jar";
        let entries: HashSet<&str> = [base, uni].into();
        let list = plan_libraries(
            &m,
            "net.minecraftforge:forge:",
            &|p| entries.contains(format!("maven/{p}").as_str()),
            &|_| false,
            &url_of,
        )
        .unwrap();
        assert_eq!(list.len(), 4);
        assert_eq!(list[0].url, url_of(base));
        assert_eq!(list[0].sha1.as_deref(), Some("aa"));
        assert_eq!(list[1].url, url_of(uni));
        assert!(list[1].sha1.is_none());
        assert!(list[1].local.ends_with("forge-1.20.1-47.2.0-universal.jar"));
        assert_eq!(
            list[2].url,
            "https://maven.minecraftforge.net/org/ow2/asm/asm/9.5/asm-9.5.jar"
        );
        assert_eq!(
            list[3].url,
            "https://repo.spongepowered.org/maven/lzma/lzma/0.0.1/lzma-0.0.1.jar"
        );
        assert_eq!(
            list[3].local,
            ["lzma", "lzma", "0.0.1", "lzma-0.0.1.jar"].join(MAIN_SEPARATOR_STR)
        );
    }

    #[test]
    fn a_library_without_a_usable_name_fails_the_plan() {
        let plan = |libs: serde_json::Value| {
            let m = manifest(json!({"id": "x", "mainClass": "m", "libraries": libs}));
            plan_libraries(
                &m,
                "net.minecraftforge:forge:",
                &|_| false,
                &|_| false,
                &url_of,
            )
        };
        let e = plan(json!([{"url": "https://nameless"}])).unwrap_err();
        assert_eq!(e.key(), "mojang.error.missingField");
        let e = plan(json!([{"name": "not-a-coordinate"}])).unwrap_err();
        assert!(matches!(
            e,
            LaunchError::Coord(mmcl_mojang::CoordError::Malformed(_))
        ));
        assert!(plan(json!([{"name": "a:b:1"}])).unwrap().len() == 1);
    }

    #[test]
    fn legacy_library_plan() {
        let m = manifest(json!({
        "id": "1.7.10-Forge10.13.4.1614-1.7.10", "mainClass": "net.minecraft.launchwrapper.Launch",
        "minecraftArguments": "--username ${auth_player_name} --tweakClass cpw.mods.fml.common.launcher.FMLTweaker",
        "libraries": [
            {"name": "net.minecraftforge:forge:1.7.10-10.13.4.1614-1.7.10", "url": "http://files.minecraftforge.net/maven/"},
            {"name": "net.minecraft:launchwrapper:1.12", "serverreq": true},
            {"name": "com.typesafe.akka:akka-actor_2.11:2.3.3", "url": "http://files.minecraftforge.net/maven/", "clientreq": false}
        ]}));
        let top = "forge-1.7.10-10.13.4.1614-1.7.10-universal.jar";
        let list = plan_libraries(
            &m,
            "net.minecraftforge:forge:",
            &|_| false,
            &|n| n == top,
            &url_of,
        )
        .unwrap();
        assert_eq!(list[0].url, url_of(top));
        assert!(list[0].sha1.is_none());
        assert!(
            list[0]
                .local
                .ends_with("forge-1.7.10-10.13.4.1614-1.7.10.jar")
        );
        assert_eq!(
            list[1].url,
            "https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"
        );
        // A legacy http repository URL stays as written; the managed-file gate refuses it.
        assert!(
            list[2]
                .url
                .starts_with("http://files.minecraftforge.net/maven/com/typesafe/akka/")
        );
        assert!(!list[2].required_for(Side::Client));
        assert!(list[2].required_for(Side::Server));
    }

    #[test]
    fn installer_entry_urls_are_contained_jar_urls() {
        let d = tempfile::tempdir().unwrap();
        let installer = d.path().join("installs/P/bin/modpack.jar");
        let url = {
            let abs = std::path::absolute(&installer).unwrap();
            format!(
                "jar:{}!/maven/a/b.jar",
                url::Url::from_file_path(&abs).unwrap()
            )
        };
        assert!(
            mmcl_modpack::is_jar_url_contained_in(d.path(), &url),
            "{url}"
        );
        assert!(!mmcl_modpack::is_jar_url_contained_in(
            &d.path().join("other"),
            &url
        ));
    }
}

#[cfg(test)]
mod flow_tests {
    use super::*;
    use crate::test_support::{Fixture, sha1};
    use mmcl_manifest::PackManifest;

    const BASE: &str = "net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0.jar";
    const UNI: &str = "net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-universal.jar";
    const INSTALLER_URL: &str = "https://maven.minecraftforge.net/forge-installer.jar";

    fn installer() -> Vec<u8> {
        let version = format!(
            r#"{{"id":"1.20.1-forge-47.2.0","inheritsFrom":"1.20.1",
               "mainClass":"cpw.mods.bootstraplauncher.BootstrapLauncher",
               "arguments":{{"game":["--launchTarget","forgeclient"],
                            "jvm":["-p","${{library_directory}}/a${{classpath_separator}}b","--add-opens","java.base/java.util.jar=cpw.mods.securejarhandler"]}},
               "libraries":[
                 {{"name":"net.minecraftforge:forge:1.20.1-47.2.0","downloads":{{"artifact":{{"path":"{BASE}","url":"","sha1":"{}"}}}}}},
                 {{"name":"cpw.mods:securejarhandler:2.1.10","downloads":{{"artifact":{{"path":"cpw/mods/securejarhandler/2.1.10/securejarhandler-2.1.10.jar","url":"https://maven.minecraftforge.net/sjh.jar","sha1":"{}"}}}}}}
               ]}}"#,
            sha1(b"base"),
            sha1(b"sjh")
        );
        let profile = r#"{"data":{"PATCHED":{"client":"[net.minecraftforge:forge:1.20.1-47.2.0:client]","server":"[net.minecraftforge:forge:1.20.1-47.2.0:server]"}},"processors":[],"libraries":[]}"#;
        crate::jar::build_jar(&[
            (VERSION_JSON, version.as_bytes()),
            (INSTALL_PROFILE_JSON, profile.as_bytes()),
            (&format!("maven/{BASE}"), b"base"),
            (&format!("maven/{UNI}"), b"universal"),
        ])
        .unwrap()
    }

    #[tokio::test]
    async fn modern_forge_classpath_from_embedded_maven_entries() {
        let fx = Fixture::new(mmcl_core::GameMode::Client);
        let inst = installer();
        fx.http.body(INSTALLER_URL, inst.clone());
        fx.http
            .body("https://maven.minecraftforge.net/sjh.jar", b"sjh".to_vec());
        let pack = fx.pack(PackManifest {
            pack_name: Some("Forge Pack".into()),
            pack_version: Some("1".into()),
            pack_mod_loader: Some("forge".into()),
            pack_mod_loader_url: Some(INSTALLER_URL.into()),
            pack_mod_loader_hash: Some(sha1(&inst)),
            pack_mods: Some(Vec::new()),
            ..Default::default()
        });
        let loader = crate::loader::loader_of(&fx.env, &pack)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(loader.name(), "Forge");
        assert_eq!(loader.minecraft_version(), "1.20.1");
        assert_eq!(loader.loader_version(), "1.20.1-forge-47.2.0");
        assert!(loader.server_main_class().is_none());
        assert_eq!(loader.jvm_arguments()[0], "-p");
        // Memoised on the pack.
        let again = crate::loader::loader_of(&fx.env, &pack)
            .await
            .unwrap()
            .unwrap();
        assert!(Arc::ptr_eq(&loader, &again));
        assert!(pack.cached_mod_loader().is_some());

        let run = fx.run(&pack);
        let sink: Arc<dyn ProgressSink> = Arc::new(mmcl_progress::NullSink);
        let libs = pack.pack_root().join("libraries");
        // The patched client exists after processors ran once.
        let patched = join_rel(
            &libs,
            "net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-client.jar",
        );
        fs::create_dir_all(patched.parent().unwrap()).unwrap();
        fs::write(&patched, b"patched").unwrap();
        let cp = loader.build_classpath(&run, &sink).await.unwrap();
        let norm: Vec<String> = cp.iter().map(|e| e.replace('\\', "/")).collect();
        assert_eq!(norm.len(), 4, "{norm:?}");
        assert!(norm[0].ends_with(BASE));
        assert!(norm[1].ends_with(UNI));
        assert!(norm[2].ends_with("securejarhandler-2.1.10.jar"));
        assert!(norm[3].ends_with("forge-1.20.1-47.2.0-client.jar"));
        assert_eq!(fs::read(join_rel(&libs, UNI)).unwrap(), b"universal");
        assert_eq!(fs::read(join_rel(&libs, BASE)).unwrap(), b"base");
        // No processors: post-install is a no-op (patched client present and unhashed).
        loader
            .run_post_install(&run, &sink, "java-runtime-gamma")
            .await
            .unwrap();
    }

    #[tokio::test]
    async fn a_corrupt_installer_is_refused() {
        let fx = Fixture::new(mmcl_core::GameMode::Client);
        fx.http.body(INSTALLER_URL, installer());
        let pack = fx.pack(PackManifest {
            pack_name: Some("Bad Hash".into()),
            pack_mod_loader: Some("forge".into()),
            pack_mod_loader_url: Some(INSTALLER_URL.into()),
            pack_mod_loader_hash: Some("0".repeat(40)),
            ..Default::default()
        });
        let e = crate::loader::loader_of(&fx.env, &pack).await.unwrap_err();
        assert!(
            e.to_string().contains("modpack.jar") || e.key().starts_with("managedFile"),
            "{e}"
        );
    }
}
