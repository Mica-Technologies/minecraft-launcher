// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Test fixtures: a scripted [`PackHttp`], a fetcher that never touches the network, and a
//! temp data folder with a ready [`LaunchEnv`].

use std::collections::HashMap;
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use futures::future::BoxFuture;
use mmcl_core::{DataPaths, GameMode};
use mmcl_manifest::PackManifest;
use mmcl_modpack::{GameModPack, PackHttp};
use mmcl_mojang::HostInfo;
use mmcl_net::{BoundedFetchResult, CancelToken, DownloadError, DownloadPool};
use mmcl_runtime::{Fetcher, RuntimeManager};
use parking_lot::Mutex;

use crate::env::{DefaultScanner, HeapOverride, LaunchEnv, StaticConfig};
use crate::run::PackRun;

/// Lowercase hex SHA-1.
pub fn sha1(b: &[u8]) -> String {
    mmcl_util::hash::sha1_hex(b)
}

/// URL → body, with a request log (`DL <url>`, `GET <url>`).
#[derive(Default)]
pub struct FakeHttp {
    offline: AtomicBool,
    bodies: Mutex<HashMap<String, Vec<u8>>>,
    log: Mutex<Vec<String>>,
}

impl FakeHttp {
    pub fn new() -> Arc<Self> {
        Arc::new(FakeHttp::default())
    }
    /// Serves `body` for `url` (replacing an earlier body).
    pub fn body(&self, url: &str, body: impl Into<Vec<u8>>) {
        self.bodies.lock().insert(url.to_string(), body.into());
    }
    pub fn set_offline(&self, v: bool) {
        self.offline.store(v, Ordering::SeqCst);
    }
    /// Log lines containing `needle`.
    pub fn count(&self, needle: &str) -> usize {
        self.log
            .lock()
            .iter()
            .filter(|l| l.contains(needle))
            .count()
    }
    fn get(&self, kind: &str, url: &str) -> Result<Vec<u8>, DownloadError> {
        self.log.lock().push(format!("{kind} {url}"));
        self.bodies
            .lock()
            .get(url)
            .cloned()
            .ok_or(DownloadError::Http {
                status: 404,
                url: url.to_string(),
            })
    }
}

impl PackHttp for FakeHttp {
    fn is_offline(&self) -> bool {
        self.offline.load(Ordering::SeqCst)
    }
    fn refresh_network_availability(&self) -> BoxFuture<'_, bool> {
        Box::pin(async move { !self.is_offline() })
    }
    fn get_conditional<'a>(
        &'a self,
        url: &'a str,
        _max: u64,
        _etag: Option<&'a str>,
        _lm: Option<&'a str>,
    ) -> BoxFuture<'a, Result<BoundedFetchResult, DownloadError>> {
        Box::pin(async move {
            let b = self.get("COND", url)?;
            Ok(BoundedFetchResult {
                body: Some(String::from_utf8_lossy(&b).into_owned()),
                etag: None,
                last_modified: None,
                not_modified: false,
            })
        })
    }
    fn get_bounded<'a>(
        &'a self,
        url: &'a str,
        _max: u64,
    ) -> BoxFuture<'a, Result<String, DownloadError>> {
        Box::pin(async move { Ok(String::from_utf8_lossy(&self.get("GET", url)?).into_owned()) })
    }
    fn get_string<'a>(&'a self, url: &'a str) -> BoxFuture<'a, Result<String, DownloadError>> {
        self.get_bounded(url, u64::MAX)
    }
    fn download<'a>(
        &'a self,
        url: &'a str,
        dest: &'a Path,
        _tracker: Option<Arc<mmcl_net::DownloadTracker>>,
    ) -> BoxFuture<'a, Result<(), DownloadError>> {
        Box::pin(async move {
            let b = self.get("DL", url)?;
            crate::jar::write_atomic(dest, &b)?;
            Ok(())
        })
    }
    fn download_bounded<'a>(
        &'a self,
        url: &'a str,
        dest: &'a Path,
        _max: u64,
    ) -> BoxFuture<'a, Result<(), DownloadError>> {
        self.download(url, dest, None)
    }
}

/// A runtime fetcher that refuses everything (tests never install a real JRE).
pub struct NoFetcher;

impl Fetcher for NoFetcher {
    fn fetch<'a>(
        &'a self,
        url: &'a str,
        _dest: &'a Path,
        _accept: Option<&'a str>,
        _cancel: &'a CancelToken,
    ) -> BoxFuture<'a, Result<(), DownloadError>> {
        Box::pin(async move { Err(DownloadError::InvalidSource(url.to_string())) })
    }
}

/// A temp data folder with a launch environment over a [`FakeHttp`].
pub struct Fixture {
    pub dir: tempfile::TempDir,
    pub paths: Arc<DataPaths>,
    pub http: Arc<FakeHttp>,
    pub env: LaunchEnv,
}

impl Fixture {
    pub fn new(mode: GameMode) -> Fixture {
        Self::with_host(mode, HostInfo::detect().clone())
    }

    pub fn with_host(mode: GameMode, host: HostInfo) -> Fixture {
        let dir = tempfile::tempdir().unwrap();
        let paths = Arc::new(DataPaths::at_root(dir.path(), mode));
        let http = FakeHttp::new();
        let runtimes = RuntimeManager::with_parts(paths.clone(), host.clone(), Arc::new(NoFetcher));
        let env = LaunchEnv {
            paths: paths.clone(),
            host,
            http: http.clone(),
            pool: DownloadPool::with_size(8),
            runtimes,
            config: Arc::new(StaticConfig::default()),
            tui: false,
            heap_override: HeapOverride::default(),
            scanner: Arc::new(DefaultScanner),
        };
        Fixture {
            dir,
            paths,
            http,
            env,
        }
    }

    pub fn vanilla_pack(&self, version: &str) -> Arc<GameModPack> {
        GameModPack::vanilla(self.paths.clone(), version)
    }

    pub fn pack(&self, manifest: PackManifest) -> Arc<GameModPack> {
        GameModPack::from_manifest(
            self.paths.clone(),
            manifest,
            Some("https://example.invalid/pack.json".into()),
            Some("ab".repeat(32)),
        )
    }

    pub fn run(&self, pack: &Arc<GameModPack>) -> PackRun {
        PackRun::new(self.env.clone(), pack.clone(), CancelToken::new())
    }
}

/// Serves a complete small Minecraft `version` (version manifest, client.json, one library
/// with natives, the client and server jars, an asset index with one object).
pub fn serve_vanilla(fx: &Fixture, version: &str) {
    use mmcl_mojang::version_manifest::VERSION_MANIFEST_URL;
    let natives = crate::jar::build_jar(&[("lwjgl.dll", b"dll"), ("liblwjgl.so", b"so")]).unwrap();
    let obj = b"ogg-bytes".to_vec();
    let index = format!(
        r#"{{"objects":{{"minecraft/sounds/a.ogg":{{"hash":"{}","size":9}}}}}}"#,
        sha1(&obj)
    );
    let cj = format!(
        r#"{{"id":"{version}","mainClass":"net.minecraft.client.main.Main",
        "javaVersion":{{"component":"java-runtime-delta","majorVersion":21}},
        "assetIndex":{{"id":"24","url":"https://piston-meta.mojang.com/i/24.json","sha1":"{isha}"}},
        "downloads":{{"client":{{"url":"https://piston-data.mojang.com/{version}/client.jar","sha1":"{csha}"}},
                     "server":{{"url":"https://piston-data.mojang.com/{version}/server.jar","sha1":"{ssha}"}}}},
        "arguments":{{"game":["--username","${{auth_player_name}}","--version","${{version_name}}",
                             "--gameDir","${{game_directory}}","--assetsDir","${{assets_root}}",
                             "--assetIndex","${{assets_index_name}}","--uuid","${{auth_uuid}}",
                             "--accessToken","${{auth_access_token}}","--userType","${{user_type}}",
                             "--versionType","${{version_type}}"],
                     "jvm":["-Djava.library.path=${{natives_directory}}","-Dminecraft.launcher.brand=${{launcher_name}}",
                            "-Dminecraft.launcher.version=${{launcher_version}}","-cp","${{classpath}}"]}},
        "libraries":[
          {{"name":"org.ow2.asm:asm:9.6","downloads":{{"artifact":{{"path":"org/ow2/asm/asm/9.6/asm-9.6.jar","sha1":"{asha}","url":"https://libraries.minecraft.net/org/ow2/asm/asm/9.6/asm-9.6.jar"}}}}}},
          {{"name":"org.lwjgl:lwjgl:3.3.3","downloads":{{"artifact":{{"path":"org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar","sha1":"{lsha}","url":"https://libraries.minecraft.net/lwjgl.jar"}},
            "classifiers":{{"natives-windows":{{"path":"org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar","sha1":"{nsha}","url":"https://libraries.minecraft.net/natives.jar"}},
                            "natives-linux":{{"path":"org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar","sha1":"{nsha}","url":"https://libraries.minecraft.net/natives.jar"}},
                            "natives-osx":{{"path":"org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-osx.jar","sha1":"{nsha}","url":"https://libraries.minecraft.net/natives.jar"}}}}}}}}
        ]}}"#,
        isha = sha1(index.as_bytes()),
        csha = sha1(b"client-jar"),
        ssha = sha1(b"server-jar"),
        asha = sha1(b"asm-9.6"),
        lsha = sha1(b"lwjgl"),
        nsha = sha1(&natives),
    );
    let vm = format!(
        r#"{{"latest":{{"release":"{version}","snapshot":"x"}},"versions":[{{"id":"{version}","type":"release","url":"https://piston-meta.mojang.com/v/{version}.json","time":"t","releaseTime":"t","sha1":"{}"}}]}}"#,
        sha1(cj.as_bytes())
    );
    fx.http.body(VERSION_MANIFEST_URL, vm);
    fx.http.body(
        &format!("https://piston-meta.mojang.com/v/{version}.json"),
        cj,
    );
    fx.http
        .body("https://piston-meta.mojang.com/i/24.json", index);
    fx.http
        .body(&mmcl_mojang::asset_index::object_url(&sha1(&obj)), obj);
    fx.http.body(
        &format!("https://piston-data.mojang.com/{version}/client.jar"),
        b"client-jar".to_vec(),
    );
    fx.http.body(
        &format!("https://piston-data.mojang.com/{version}/server.jar"),
        b"server-jar".to_vec(),
    );
    fx.http.body(
        "https://libraries.minecraft.net/org/ow2/asm/asm/9.6/asm-9.6.jar",
        b"asm-9.6".to_vec(),
    );
    fx.http.body(
        "https://libraries.minecraft.net/lwjgl.jar",
        b"lwjgl".to_vec(),
    );
    fx.http
        .body("https://libraries.minecraft.net/natives.jar", natives);
}

/// Installs a fake, already-verified runtime `component` (marker and executable), so a verify
/// takes the network-free fast path. Returns the executable.
pub fn fake_runtime(fx: &Fixture, component: &str) -> std::path::PathBuf {
    let dir = fx.paths.runtime_component_dir(component);
    let exe = dir.join(fx.env.host.java_exec_rel_path());
    std::fs::create_dir_all(exe.parent().unwrap()).unwrap();
    std::fs::write(&exe, b"#!/bin/sh\n").unwrap();
    std::fs::write(dir.join(".version"), "21.0.3").unwrap();
    exe
}
