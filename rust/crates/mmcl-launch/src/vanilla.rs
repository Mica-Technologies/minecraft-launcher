// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Vanilla Minecraft files (the I/O half of Java `GameVersionManifest` and
//! `GameLibraryManifest`): the version manifest, client.json, libraries and natives, the client
//! (or server) jar, and the vanilla classpath.
//!
//! Fix over Java (B-3): client.json is managed with the version manifest's `sha1`, so a
//! republished client.json is picked up instead of being trusted forever once present.

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, LazyLock};

use mmcl_core::GameMode;
use mmcl_i18n::{t, tf};
use mmcl_modpack::{ManagedFile, VerifyMode};
use mmcl_mojang::client_json::{ClientJson, GameSide, ResolvedLibrary};
use mmcl_mojang::version_manifest::{
    VERSION_MANIFEST_URL, VersionManifest, client_json_cache_file_name,
};
use mmcl_progress::ProgressSink;
use parking_lot::Mutex;
use tokio::sync::OnceCell;

use crate::arm64;
use crate::assets::{self, AssetInfo};
use crate::batch::{BATCH_TIMEOUT, BatchKeys, blocking, file_name_of, join_rel, run_batch, spec};
use crate::env::LaunchEnv;
use crate::error::{LaunchError, Result};
use crate::lerr;
use crate::run::PackRun;

/// `bin/minecraft.jar.verified`: the client jar was verified (and stripped when needed).
pub const VERIFIED_MARKER: &str = "minecraft.jar.verified";

type Cell<T> = Arc<OnceCell<Arc<T>>>;

static VERSION_MANIFESTS: LazyLock<Mutex<HashMap<PathBuf, Cell<VersionManifest>>>> =
    LazyLock::new(Default::default);
static CLIENT_JSONS: LazyLock<Mutex<HashMap<PathBuf, Cell<ClientJson>>>> =
    LazyLock::new(Default::default);

fn cell<T>(map: &Mutex<HashMap<PathBuf, Cell<T>>>, key: PathBuf) -> Cell<T> {
    map.lock().entry(key).or_default().clone()
}

/// Mojang's version manifest, downloaded once per process (per data folder) to
/// `config/version.manifest`. Concurrent callers share one download; a failure is not cached.
/// Offline (or on a failed download) an existing copy is used.
pub async fn version_manifest(env: &LaunchEnv) -> Result<Arc<VersionManifest>> {
    let file = env.paths.mc_version_manifest_file();
    let c = cell(&VERSION_MANIFESTS, file.clone());
    c.get_or_try_init(|| async {
        mmcl_log::debug(&t!("log.versionManifest.notDownloadedGettingNow"));
        if let Some(p) = file.parent() {
            fs::create_dir_all(p)?;
        }
        let downloaded = if env.http.is_offline() {
            Err(())
        } else {
            env.http
                .download(VERSION_MANIFEST_URL, &file, None)
                .await
                .map_err(|e| mmcl_log::warn_silent(&e.to_string()))
        };
        if downloaded.is_err() && !file.is_file() {
            mmcl_log::warn_silent(&t!("log.versionManifest.downloadReadFailed"));
            return Err(lerr!("minecraft.error.versionManifestDownload"));
        }
        let f = file.clone();
        let bytes = blocking(move || fs::read(f)).await??;
        let m = VersionManifest::parse(&bytes)?;
        mmcl_log::debug(&t!("log.versionManifest.downloaded"));
        Ok::<_, LaunchError>(Arc::new(m))
    })
    .await
    .cloned()
}

/// `metadata/client-<version>.json`, fetched once per process per version (used for Java
/// version reads outside a pack, e.g. by the official-launcher export).
pub async fn cached_client_json(env: &LaunchEnv, mc_version: &str) -> Result<Arc<ClientJson>> {
    let file = env
        .paths
        .metadata_dir()
        .join(client_json_cache_file_name(mc_version));
    let c = cell(&CLIENT_JSONS, file.clone());
    c.get_or_try_init(|| async {
        let vm = version_manifest(env).await?;
        let entry = vm.find(mc_version)?;
        let mf = ManagedFile::at_path(spec(&entry.url, "", entry.sha1()), &file, env.paths.root());
        mf.update(&mmcl_modpack::PrepareContext::NONE, env.http.as_ref())
            .await
            .map_err(|e| {
                mmcl_log::warn_silent(&e.to_string());
                lerr!("minecraft.error.clientJsonDownload", mc_version)
            })?;
        let f = file.clone();
        let bytes = blocking(move || fs::read(f)).await??;
        Ok::<_, LaunchError>(Arc::new(ClientJson::parse(&bytes)?))
    })
    .await
    .cloned()
}

/// The Java runtime component a Minecraft version needs (`jre-legacy` by default).
pub async fn required_runtime_component(env: &LaunchEnv, mc_version: &str) -> Result<String> {
    Ok(cached_client_json(env, mc_version)
        .await?
        .runtime_component())
}

/// A pack's client.json (`bin/libraries.manifest`), parsed once per pack instance.
#[derive(Debug)]
pub struct LibraryManifest {
    /// The Minecraft version it describes.
    pub mc_version: String,
    /// The parsed client.json.
    pub client: Arc<ClientJson>,
    file: ManagedFile,
}

impl LibraryManifest {
    /// The runtime component (`javaVersion.component`, default `jre-legacy`).
    pub fn runtime_component(&self) -> String {
        self.client.runtime_component()
    }

    /// `javaVersion.majorVersion` (default 8).
    pub fn java_major(&self) -> i32 {
        self.client.java_major()
    }

    /// The managed file behind it.
    pub fn file(&self) -> &ManagedFile {
        &self.file
    }
}

/// The pack's library manifest for `mc_version`: verified under the run's context and parsed
/// once per pack (re-parsed only when the file was re-downloaded).
pub async fn library_manifest(run: &PackRun, mc_version: &str) -> Result<Arc<LibraryManifest>> {
    let slot = run.pack.library_manifest_slot();
    let memo: Option<Arc<LibraryManifest>> = slot
        .lock()
        .clone()
        .and_then(|a| a.downcast::<LibraryManifest>().ok())
        .filter(|m| m.mc_version == mc_version);
    if let Some(m) = memo {
        let changed = m.file.update(&run.ctx, run.env.http.as_ref()).await?;
        if !changed {
            return Ok(m);
        }
    }
    let vm = version_manifest(&run.env).await?;
    let entry = vm.find(mc_version)?;
    let path = run
        .root()?
        .join("bin")
        .join(mmcl_core::paths::MINECRAFT_LIBRARY_MANIFEST_FILE_NAME);
    seed_from_client_json_cache(&run.env, mc_version, &path).await;
    let file = run.managed_at(spec(&entry.url, "", entry.sha1()), &path);
    file.update(&run.ctx, run.env.http.as_ref()).await?;
    let p = path.clone();
    let bytes = blocking(move || fs::read(p)).await??;
    let client = Arc::new(ClientJson::parse(&bytes)?);
    let m = Arc::new(LibraryManifest {
        mc_version: mc_version.to_string(),
        client,
        file,
    });
    *slot.lock() = Some(m.clone());
    Ok(m)
}

/// Copies the shared `metadata/client-<version>.json` (warmed by [`cached_client_json`], e.g.
/// the engine's idle prefetch) to a pack's missing library manifest, so its first launch
/// verifies a local copy instead of downloading it. The managed-file check that follows still
/// verifies the hash and downloads over a wrong copy. Best effort.
pub async fn seed_from_client_json_cache(env: &LaunchEnv, mc_version: &str, target: &Path) {
    if target.exists() {
        return;
    }
    let cached = env
        .paths
        .metadata_dir()
        .join(client_json_cache_file_name(mc_version));
    if !cached.is_file() {
        return;
    }
    let dest = target.to_path_buf();
    let _ = blocking(move || -> std::io::Result<()> {
        if let Some(p) = dest.parent() {
            fs::create_dir_all(p)?;
        }
        fs::copy(&cached, &dest).map(|_| ())
    })
    .await;
}

/// What the Minecraft branch produced.
#[derive(Clone, Debug)]
pub struct MinecraftFiles {
    /// The vanilla classpath (libraries, natives jars included, then `bin/minecraft.jar`).
    pub classpath: Vec<String>,
    /// The asset index facts the placeholders need.
    pub assets: AssetInfo,
}

/// Downloads and verifies libraries (extracting natives every launch), patches ARM64 natives
/// when needed, verifies the game jar and the assets, and builds the classpath.
pub async fn build_minecraft_classpath(
    run: &PackRun,
    lm: &LibraryManifest,
    sink: &Arc<dyn ProgressSink>,
) -> Result<MinecraftFiles> {
    let resolution = lm.client.resolve_libraries(&run.env.host)?;
    for s in &resolution.skipped {
        mmcl_log::warn_silent(&tf!("log.libraryManifest.skippedUnknownType", s));
    }
    let libs = resolution.libraries;
    sink.submit_progress(&t!("libraryManifest.gotLibraryList"), 5.0);
    run.check_cancelled()?;

    download_verify_libraries(run, &libs, sink).await?;
    run.check_cancelled()?;

    let libs_dir = run.libraries_dir()?;
    if arm64::is_needed(&run.env, &lm.mc_version) {
        arm64::patch_natives_for(
            run.env.http.as_ref(),
            run.env.host.os,
            &run.natives_dir()?,
            &libs_dir,
            &libs_dir.join(arm64::CACHE_DIR),
        )
        .await;
    }

    download_minecraft_app(run, lm, sink).await?;
    run.check_cancelled()?;

    let assets = assets::download_assets(run, &lm.client, sink).await?;
    run.check_cancelled()?;

    let mut entries: Vec<String> = Vec::with_capacity(libs.len() + 1);
    let n = libs.len().max(1) as f64;
    for lib in &libs {
        let p = join_rel(&libs_dir, &lib.path);
        entries.push(p.to_string_lossy().into_owned());
        sink.submit_progress(
            &tf!("libraryManifest.addedToClasspath", file_name_of(&p)),
            10.0 / n,
        );
    }
    let jar = run.root()?.join("bin").join("minecraft.jar");
    entries.push(jar.to_string_lossy().into_owned());
    Ok(MinecraftFiles {
        classpath: crate::command::ordered_unique(entries),
        assets,
    })
}

/// Verifies every library on the shared pool (30-minute bound) and extracts each native jar
/// into `bin/natives` on every launch (an earlier "only when re-downloaded" gate left the
/// folder empty after a wipe, and LWJGL then failed to load).
pub async fn download_verify_libraries(
    run: &PackRun,
    libs: &[ResolvedLibrary],
    sink: &Arc<dyn ProgressSink>,
) -> Result<()> {
    let libs_dir = run.libraries_dir()?;
    let natives = run.natives_dir()?;
    fs::create_dir_all(&natives)?;
    let n = libs.len().max(1) as f64;
    let jobs = libs
        .iter()
        .map(|lib| {
            let mf = run.managed(
                spec(&lib.url, &lib.path, Some(&lib.sha1)),
                &libs_dir,
                Some(sink.as_ref()),
            );
            let (ctx, http, sink) = (run.ctx.clone(), run.env.http.clone(), sink.clone());
            let (native, natives) = (lib.native, natives.clone());
            let fut: futures::future::BoxFuture<'static, Result<()>> = Box::pin(async move {
                mf.update(&ctx, http.as_ref()).await?;
                if native {
                    let (jar, dest) = (mf.full_path(), natives);
                    blocking(move || mmcl_archive::extract_jar(&jar, &dest))
                        .await?
                        .map_err(|e| {
                            mmcl_log::warn_silent(&e.to_string());
                            lerr!("minecraft.error.nativeExtractFailed")
                        })?;
                }
                sink.submit_progress(
                    &tf!("libraryManifest.verifiedLibrary", mf.file_name()),
                    25.0 / n,
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
            timeout: "minecraft.error.libsTimedOut",
            interrupted: "minecraft.error.libsInterrupted",
            failed: Some("minecraft.error.libsFailed"),
            pass_modpack: false,
        },
    )
    .await?;
    Ok(())
}

/// The game jar (`downloads.client`, or `downloads.server` in server mode) at
/// `bin/minecraft.jar`.
///
/// With the `.verified` marker present the jar is trusted as Java does (B-7, kept), except
/// that a FULL run re-checks the hash of a 1.6+ jar (those are never stripped, so the hash
/// still applies). Without the marker the jar is verified, stripped of its signature before
/// 1.6, and the marker written.
pub async fn download_minecraft_app(
    run: &PackRun,
    lm: &LibraryManifest,
    sink: &Arc<dyn ProgressSink>,
) -> Result<()> {
    let side = match run.env.mode() {
        GameMode::Client => GameSide::Client,
        GameMode::Server => GameSide::Server,
    };
    let dl = lm.client.game_download(side)?;
    let bin = run.root()?.join("bin");
    let jar = bin.join("minecraft.jar");
    let marker = bin.join(VERIFIED_MARKER);
    let strip = crate::jar::is_strip_required_for(Some(&lm.mc_version));
    let mf = run.managed_at(spec(&dl.url, "", Some(&dl.sha1)), &jar);
    if marker.is_file() && jar.is_file() {
        if run.ctx.verify_mode == VerifyMode::Full && !strip {
            mf.update(&run.ctx, run.env.http.as_ref()).await?;
        }
    } else {
        mf.update(&run.ctx, run.env.http.as_ref()).await?;
        if strip {
            let j = jar.clone();
            match blocking(move || crate::jar::strip_signing(&j)).await? {
                Ok(true) => mmcl_log::std(&t!("log.libraryManifest.strippedSigning")),
                Ok(false) => {}
                Err(e) => mmcl_log::warn_silent(&tf!(
                    "log.libraryManifest.stripSigningFailed",
                    e.to_string()
                )),
            }
        }
        if let Err(e) = fs::write(&marker, b"") {
            mmcl_log::warn_silent(&tf!(
                "log.libraryManifest.verifiedMarkerCreateFailedDetail",
                e.to_string()
            ));
        }
    }
    sink.submit_progress(&t!("libraryManifest.verifiedMinecraftApp"), 10.0);
    Ok(())
}

/// The vanilla main class (client.json `mainClass`, else `net.minecraft.client.main.Main`).
pub fn vanilla_main_class(client: &ClientJson) -> String {
    client.main_class_or_default()
}

/// `<pack>/bin/natives` as a string.
pub fn natives_path(pack_root: &Path) -> PathBuf {
    pack_root.join("bin").join("natives")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::*;

    const VERSION_MANIFEST: &str = r#"{"latest":{"release":"1.21.5","snapshot":"x"},"versions":[
        {"id":"1.21.5","type":"release","url":"https://piston-meta.mojang.com/v1/packages/abc/1.21.5.json","time":"t","releaseTime":"t","sha1":"SHA1PLACEHOLDER"}]}"#;

    fn client_json() -> String {
        format!(
            r#"{{"id":"1.21.5","mainClass":"net.minecraft.client.main.Main",
            "javaVersion":{{"component":"java-runtime-delta","majorVersion":21}},
            "assetIndex":{{"id":"24","url":"https://piston-meta.mojang.com/v1/packages/i/24.json","sha1":"{}"}},
            "downloads":{{"client":{{"url":"https://piston-data.mojang.com/client.jar","sha1":"{}"}},
                         "server":{{"url":"https://piston-data.mojang.com/server.jar","sha1":"{}"}}}},
            "libraries":[
              {{"name":"a:b:1","downloads":{{"artifact":{{"path":"a/b/1/b-1.jar","sha1":"{}","url":"https://libraries.minecraft.net/a/b/1/b-1.jar"}},
                "classifiers":{{"natives-windows":{{"path":"a/b/1/b-1-natives-windows.jar","sha1":"{}","url":"https://libraries.minecraft.net/n.jar"}},
                                "natives-linux":{{"path":"a/b/1/b-1-natives-linux.jar","sha1":"{}","url":"https://libraries.minecraft.net/n.jar"}},
                                "natives-osx":{{"path":"a/b/1/b-1-natives-osx.jar","sha1":"{}","url":"https://libraries.minecraft.net/n.jar"}}}}}}}},
              {{"name":"old:style:1","url":"https://x"}}
            ]}}"#,
            sha1(ASSET_INDEX.as_bytes()),
            sha1(b"client-jar"),
            sha1(b"server-jar"),
            sha1(b"lib-b"),
            sha1(&natives_jar()),
            sha1(&natives_jar()),
            sha1(&natives_jar()),
        )
    }

    const ASSET_INDEX: &str = r#"{"objects":{"minecraft/sounds/a.ogg":{"hash":"HASH","size":3}}}"#;

    fn natives_jar() -> Vec<u8> {
        crate::jar::build_jar(&[("lwjgl.dll", b"dll"), ("META-INF/MANIFEST.MF", b"x")]).unwrap()
    }

    fn setup(fx: &Fixture) {
        let cj = client_json();
        let vm = VERSION_MANIFEST.replace("SHA1PLACEHOLDER", &sha1(cj.as_bytes()));
        fx.http.body(VERSION_MANIFEST_URL, vm.into_bytes());
        fx.http.body(
            "https://piston-meta.mojang.com/v1/packages/abc/1.21.5.json",
            cj.into_bytes(),
        );
        fx.http.body(
            "https://piston-meta.mojang.com/v1/packages/i/24.json",
            ASSET_INDEX.as_bytes().to_vec(),
        );
        fx.http.body(
            "https://piston-data.mojang.com/client.jar",
            b"client-jar".to_vec(),
        );
        fx.http.body(
            "https://piston-data.mojang.com/server.jar",
            b"server-jar".to_vec(),
        );
        fx.http.body(
            "https://libraries.minecraft.net/a/b/1/b-1.jar",
            b"lib-b".to_vec(),
        );
        fx.http
            .body("https://libraries.minecraft.net/n.jar", natives_jar());
    }

    #[tokio::test]
    async fn vanilla_files_and_classpath() {
        let fx = Fixture::new(mmcl_core::GameMode::Client);
        setup(&fx);
        // The asset index object hash must be real hex: patch the served index.
        let obj = b"ogg";
        let idx = ASSET_INDEX.replace("HASH", &sha1(obj));
        fx.http.body(
            "https://piston-meta.mojang.com/v1/packages/i/24.json",
            idx.clone().into_bytes(),
        );
        let cj = client_json().replace(&sha1(ASSET_INDEX.as_bytes()), &sha1(idx.as_bytes()));
        let vm = VERSION_MANIFEST.replace("SHA1PLACEHOLDER", &sha1(cj.as_bytes()));
        fx.http.body(VERSION_MANIFEST_URL, vm.into_bytes());
        fx.http.body(
            "https://piston-meta.mojang.com/v1/packages/abc/1.21.5.json",
            cj.into_bytes(),
        );
        fx.http.body(
            &mmcl_mojang::asset_index::object_url(&sha1(obj)),
            obj.to_vec(),
        );
        let pack = fx.vanilla_pack("1.21.5");
        let run = fx.run(&pack);
        let lm = library_manifest(&run, "1.21.5").await.unwrap();
        assert_eq!(lm.runtime_component(), "java-runtime-delta");
        let sink: Arc<dyn ProgressSink> = Arc::new(mmcl_progress::NullSink);
        let files = build_minecraft_classpath(&run, &lm, &sink).await.unwrap();
        let root = pack.pack_root();
        assert!(root.join("bin/minecraft.jar").is_file());
        assert!(root.join("bin").join(VERIFIED_MARKER).is_file());
        assert!(
            root.join("bin/natives/lwjgl.dll").is_file(),
            "natives extracted"
        );
        assert!(!root.join("bin/natives/META-INF").exists());
        // Native first, then its artifact, then the game jar.
        assert_eq!(files.classpath.len(), 3);
        assert!(files.classpath[0].contains("natives"));
        assert!(files.classpath[1].ends_with("b-1.jar"));
        assert!(files.classpath[2].ends_with("minecraft.jar"));
        assert_eq!(files.assets.index_id, "24");
        assert!(
            fx.paths
                .shared_assets_dir()
                .join("objects")
                .join(&sha1(obj)[..2])
                .join(sha1(obj))
                .is_file()
        );

        // A wiped natives folder is refilled on the next launch even though nothing changed.
        fs::remove_dir_all(root.join("bin/natives")).unwrap();
        build_minecraft_classpath(&run, &lm, &sink).await.unwrap();
        assert!(root.join("bin/natives/lwjgl.dll").is_file());

        // The memoised manifest is reused.
        let again = library_manifest(&run, "1.21.5").await.unwrap();
        assert!(Arc::ptr_eq(&lm, &again));
        // The version manifest was fetched once per process for this data folder.
        assert_eq!(fx.http.count(&format!("DL {VERSION_MANIFEST_URL}")), 1);
    }

    #[tokio::test]
    async fn server_mode_downloads_the_server_jar() {
        let fx = Fixture::new(mmcl_core::GameMode::Server);
        setup(&fx);
        let pack = fx.vanilla_pack("1.21.5");
        let run = fx.run(&pack);
        let lm = library_manifest(&run, "1.21.5").await.unwrap();
        let sink: Arc<dyn ProgressSink> = Arc::new(mmcl_progress::NullSink);
        download_minecraft_app(&run, &lm, &sink).await.unwrap();
        assert_eq!(
            fs::read(pack.pack_root().join("bin/minecraft.jar")).unwrap(),
            b"server-jar"
        );
    }

    #[tokio::test]
    async fn a_cached_client_json_seeds_a_missing_library_manifest() {
        let fx = Fixture::new(mmcl_core::GameMode::Client);
        let target = fx.dir.path().join("pack/bin/libraries.manifest");
        // Nothing cached: nothing written.
        seed_from_client_json_cache(&fx.env, "1.21.5", &target).await;
        assert!(!target.exists());
        let meta = fx.paths.metadata_dir();
        fs::create_dir_all(&meta).unwrap();
        fs::write(meta.join(client_json_cache_file_name("1.21.5")), b"{}").unwrap();
        seed_from_client_json_cache(&fx.env, "1.21.5", &target).await;
        assert_eq!(fs::read(&target).unwrap(), b"{}");
        // An existing manifest is never replaced.
        fs::write(&target, b"mine").unwrap();
        seed_from_client_json_cache(&fx.env, "1.21.5", &target).await;
        assert_eq!(fs::read(&target).unwrap(), b"mine");
    }

    #[tokio::test]
    async fn unknown_version_is_version_not_found() {
        let fx = Fixture::new(mmcl_core::GameMode::Client);
        setup(&fx);
        let pack = fx.vanilla_pack("9.9");
        let run = fx.run(&pack);
        let e = library_manifest(&run, "9.9").await.unwrap_err();
        assert_eq!(e.key(), "minecraft.error.versionNotFound");
    }
}

#[cfg(test)]
mod offline_tests {
    use super::*;
    use crate::test_support::Fixture;

    #[tokio::test]
    async fn offline_without_a_cached_manifest_is_a_download_error() {
        let fx = Fixture::new(mmcl_core::GameMode::Client);
        fx.http.set_offline(true);
        let e = version_manifest(&fx.env).await.unwrap_err();
        assert_eq!(e.key(), "minecraft.error.versionManifestDownload");
        // A cached copy is used offline, and a failure was not cached.
        fs::create_dir_all(fx.paths.config_dir()).unwrap();
        fs::write(
            fx.paths.mc_version_manifest_file(),
            br#"{"latest":{"release":"1","snapshot":"1"},"versions":[{"id":"1","type":"release","url":"https://x/1.json","time":"t","releaseTime":"t"}]}"#,
        )
        .unwrap();
        let m = version_manifest(&fx.env).await.unwrap();
        assert_eq!(m.versions.len(), 1);
        assert_eq!(fx.http.count("DL"), 0);
    }
}
