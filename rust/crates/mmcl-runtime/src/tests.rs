// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Install-flow tests over a fake fetcher (no network).

use std::collections::HashMap;
use std::fs;
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use futures::future::BoxFuture;
use mmcl_core::{DataPaths, GameMode};
use mmcl_mojang::HostInfo;
use mmcl_mojang::host::presets;
use mmcl_mojang::runtime_index::RUNTIME_INDEX_URL;
use mmcl_net::{CancelToken, DownloadError};
use parking_lot::Mutex;
use sha1::Digest as _;

use crate::liberica;
use crate::manager::{ADOPTIUM_API_FILE_NAME, LEGACY_API_FILE_NAME, SOURCE_FILE_NAME};
use crate::{Fetcher, RuntimeError, RuntimeManager};

type Hook = Box<dyn Fn(&str, &Path) + Send + Sync>;

#[derive(Default)]
struct Fake {
    /// URL → responses; the last one repeats.
    responses: Mutex<HashMap<String, Vec<Vec<u8>>>>,
    requests: Mutex<Vec<String>>,
    hook: Mutex<Option<Hook>>,
}

impl Fake {
    fn serve(&self, url: &str, body: impl Into<Vec<u8>>) {
        self.responses
            .lock()
            .entry(url.to_string())
            .or_default()
            .push(body.into());
    }
    fn unserve(&self, url: &str) {
        self.responses.lock().remove(url);
    }
    fn requests(&self) -> Vec<String> {
        self.requests.lock().clone()
    }
    fn count(&self, url: &str) -> usize {
        self.requests.lock().iter().filter(|u| *u == url).count()
    }
}

impl Fetcher for Fake {
    fn fetch<'a>(
        &'a self,
        url: &'a str,
        dest: &'a Path,
        _accept: Option<&'a str>,
        cancel: &'a CancelToken,
    ) -> BoxFuture<'a, Result<(), DownloadError>> {
        Box::pin(async move {
            self.requests.lock().push(url.to_string());
            if let Some(h) = self.hook.lock().as_ref() {
                h(url, dest);
            }
            if cancel.is_cancelled() {
                return Err(DownloadError::Cancelled(url.into()));
            }
            let body = {
                let mut m = self.responses.lock();
                match m.get_mut(url) {
                    Some(q) if q.len() > 1 => Some(q.remove(0)),
                    Some(q) => q.first().cloned(),
                    None => None,
                }
            };
            let Some(body) = body else {
                return Err(DownloadError::Http {
                    status: 404,
                    url: url.into(),
                });
            };
            if let Some(p) = dest.parent() {
                fs::create_dir_all(p)?;
            }
            fs::write(dest, body)?;
            Ok(())
        })
    }
}

fn sha1_hex(b: &[u8]) -> String {
    hex::encode(sha1::Sha1::digest(b))
}

fn sha256_hex(b: &[u8]) -> String {
    use sha2::Digest as _;
    hex::encode(sha2::Sha256::digest(b))
}

struct Env {
    _dir: tempfile::TempDir,
    paths: Arc<DataPaths>,
    fake: Arc<Fake>,
    mgr: Arc<RuntimeManager>,
}

fn env(host: HostInfo) -> Env {
    let dir = tempfile::tempdir().unwrap();
    let paths = Arc::new(DataPaths::at_root(dir.path(), GameMode::Client));
    let fake = Arc::new(Fake::default());
    let mgr = RuntimeManager::with_parts(paths.clone(), host, fake.clone());
    Env {
        _dir: dir,
        paths,
        fake,
        mgr,
    }
}

impl Env {
    fn second_manager(&self, host: HostInfo) -> Arc<RuntimeManager> {
        RuntimeManager::with_parts(self.paths.clone(), host, self.fake.clone())
    }
}

const MANIFEST_URL: &str = "https://piston-meta.test/gamma/manifest.json";
const JAVA_BYTES: &[u8] = b"#!/bin/sh\necho java\n";
const LIB_BYTES: &[u8] = b"modules-blob";

fn index_json(version: &str) -> String {
    format!(
        r#"{{"linux": {{"java-runtime-gamma": [{{"manifest": {{"sha1": "x", "size": 1, "url": "{MANIFEST_URL}"}},
            "version": {{"name": "{version}", "released": "2024"}}}}]}},
            "windows-x64": {{}}}}"#
    )
}

fn manifest_json() -> String {
    format!(
        r#"{{"files": {{
          "bin": {{"type": "directory"}},
          "bin/java": {{"type": "file", "executable": true,
                        "downloads": {{"raw": {{"sha1": "{js}", "size": 1, "url": "https://f.test/java"}}}}}},
          "lib": {{"type": "directory"}},
          "lib/modules": {{"type": "file", "downloads": {{"raw": {{"sha1": "{ls}", "size": 1, "url": "https://f.test/modules"}}}}}},
          "legal/java.base/LICENSE": {{"type": "file", "downloads": {{"raw": {{"sha1": "{ls}", "size": 1, "url": "https://f.test/modules"}}}}}},
          "legal/java.desktop/LICENSE": {{"type": "link", "target": "../java.base/LICENSE"}},
          "legal/evil": {{"type": "link", "target": "../../../../etc/passwd"}},
          "../escape": {{"type": "file", "downloads": {{"raw": {{"sha1": "{ls}", "size": 1, "url": "https://f.test/escape"}}}}}},
          "/abs": {{"type": "directory"}}
        }}}}"#,
        js = sha1_hex(JAVA_BYTES),
        ls = sha1_hex(LIB_BYTES)
    )
}

fn serve_mojang(fake: &Fake, version: &str) {
    fake.serve(RUNTIME_INDEX_URL, index_json(version));
    fake.serve(MANIFEST_URL, manifest_json());
    fake.serve("https://f.test/java", JAVA_BYTES);
    fake.serve("https://f.test/modules", LIB_BYTES);
}

#[tokio::test]
async fn mojang_fresh_install_writes_files_and_marker_last() {
    let e = env(presets::linux_x64());
    serve_mojang(&e.fake, "17.0.8");
    let dir = e.paths.runtime_component_dir("java-runtime-gamma");
    let marker = dir.join(".version");
    let marker_seen = Arc::new(AtomicBool::new(false));
    {
        let marker = marker.clone();
        let seen = marker_seen.clone();
        *e.fake.hook.lock() = Some(Box::new(move |url, _| {
            if url.starts_with("https://f.test/") && marker.exists() {
                seen.store(true, Ordering::SeqCst);
            }
        }));
    }
    // A stale marker of an older version: it must go before the first file is written.
    fs::create_dir_all(&dir).unwrap();
    fs::write(&marker, "17.0.1").unwrap();

    let r = e
        .mgr
        .verify("java-runtime-gamma", None, &CancelToken::new())
        .await
        .unwrap();
    assert!(
        !marker_seen.load(Ordering::SeqCst),
        "marker deleted before files"
    );
    assert_eq!(r.version, "17.0.8");
    assert_eq!(r.java, std::path::absolute(dir.join("bin/java")).unwrap());
    assert_eq!(fs::read(dir.join("bin/java")).unwrap(), JAVA_BYTES);
    assert_eq!(fs::read(dir.join("lib/modules")).unwrap(), LIB_BYTES);
    assert_eq!(fs::read_to_string(&marker).unwrap(), "17.0.8");
    assert!(dir.join("runtime-manifest.json").is_file());
    assert!(
        e.paths
            .runtime_dir()
            .join("mojang-runtime-index.json")
            .is_file()
    );
    assert!(
        !e.paths.runtime_dir().join("escape").exists(),
        "escape skipped"
    );
    assert_eq!(e.fake.count("https://f.test/escape"), 0);
    assert_eq!(
        e.mgr.java_version("java-runtime-gamma").as_deref(),
        Some("17.0.8")
    );

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let link = dir.join("legal/java.desktop/LICENSE");
        assert_eq!(
            fs::read_link(&link).unwrap(),
            Path::new("../java.base/LICENSE")
        );
        assert_eq!(fs::read(&link).unwrap(), LIB_BYTES);
        assert!(
            fs::symlink_metadata(dir.join("legal/evil")).is_err(),
            "escaping link skipped"
        );
        let mode = fs::metadata(dir.join("bin/java"))
            .unwrap()
            .permissions()
            .mode();
        assert!(mode & 0o100 != 0, "executable entry gets +x");
    }
    #[cfg(not(unix))]
    assert!(
        !dir.join("legal/java.desktop/LICENSE").exists(),
        "links skipped on Windows"
    );

    let listed = e.mgr.installed();
    assert_eq!(listed.len(), 1);
    assert_eq!(listed[0].component, "java-runtime-gamma");
    assert_eq!(listed[0].version, "17.0.8");
}

#[tokio::test]
async fn fast_path_makes_no_request_and_background_check_drops_a_stale_marker() {
    let e = env(presets::linux_x64());
    serve_mojang(&e.fake, "17.0.8");
    e.mgr
        .verify("java-runtime-gamma", None, &CancelToken::new())
        .await
        .unwrap();

    // A new process: nothing cached in memory.
    let mgr2 = e.second_manager(presets::linux_x64());
    let before = e.fake.requests().len();
    let r = mgr2
        .verify("java-runtime-gamma", None, &CancelToken::new())
        .await
        .unwrap();
    assert_eq!(r.version, "17.0.8");
    assert_eq!(
        e.fake.requests().len(),
        before,
        "fast path stays off the network"
    );

    // Mojang now publishes a newer build: the background check drops the marker.
    e.fake.unserve(RUNTIME_INDEX_URL);
    e.fake.serve(RUNTIME_INDEX_URL, index_json("17.0.9"));
    mgr2.join_background().await;
    let marker = e
        .paths
        .runtime_component_dir("java-runtime-gamma")
        .join(".version");
    assert!(!marker.exists(), "next launch reinstalls");

    // Deduped: a second fast verify in the same session spawns no second check.
    let mgr3 = e.second_manager(presets::linux_x64());
    fs::write(&marker, "17.0.9").unwrap();
    mgr3.verify("java-runtime-gamma", None, &CancelToken::new())
        .await
        .unwrap();
    mgr3.join_background().await;
    let n = e.fake.count(RUNTIME_INDEX_URL);
    mgr3.verify("java-runtime-gamma", None, &CancelToken::new())
        .await
        .unwrap();
    mgr3.join_background().await;
    assert_eq!(e.fake.count(RUNTIME_INDEX_URL), n);
}

#[tokio::test]
async fn background_check_keeps_a_marker_rewritten_while_it_was_on_the_network() {
    let e = env(presets::linux_x64());
    serve_mojang(&e.fake, "17.0.8");
    e.mgr
        .verify("java-runtime-gamma", None, &CancelToken::new())
        .await
        .unwrap();
    let marker = e
        .paths
        .runtime_component_dir("java-runtime-gamma")
        .join(".version");

    // A new session sees 17.0.8 on the fast path; Mojang now has 17.0.9. While the check is
    // fetching the index, another verify installs 17.0.9 (simulated by rewriting the marker).
    e.fake.unserve(RUNTIME_INDEX_URL);
    e.fake.serve(RUNTIME_INDEX_URL, index_json("17.0.9"));
    {
        let marker = marker.clone();
        *e.fake.hook.lock() = Some(Box::new(move |url, _| {
            if url == RUNTIME_INDEX_URL {
                fs::write(&marker, "17.0.9").unwrap();
            }
        }));
    }
    let mgr2 = e.second_manager(presets::linux_x64());
    mgr2.verify("java-runtime-gamma", None, &CancelToken::new())
        .await
        .unwrap();
    mgr2.join_background().await;
    assert_eq!(
        fs::read_to_string(&marker).unwrap(),
        "17.0.9",
        "a marker that no longer names the checked version is left alone"
    );
}

#[tokio::test]
async fn legacy_refuses_an_api_bundle_type_that_is_not_a_plain_name() {
    let host = presets::linux_x64();
    let e = env(host.clone());
    let archive = tar_gz(&[("jre8u392/bin/java", JAVA_BYTES)]);
    let api = legacy_api(&archive).replace(r#""bundleType":"jre""#, r#""bundleType":"../../x""#);
    e.fake.serve(&legacy_url(&host), api);
    e.fake.serve(JRE8_URL, archive);
    // Something the traversal would have targeted (dir/../../x8u392).
    let victim = e
        .paths
        .runtime_component_dir("jre-legacy")
        .join("../../x8u392");
    fs::create_dir_all(&victim).unwrap();
    let err = e
        .mgr
        .verify("jre-legacy", None, &CancelToken::new())
        .await
        .unwrap_err();
    let RuntimeError::Jre8InstallFailed { cause } = &err else {
        panic!("{err:?}")
    };
    assert_eq!(cause.l10n_key(), "runtime.error.unsafeArchiveName");
    assert!(
        victim.is_dir(),
        "nothing outside the runtime folder deleted"
    );
    assert_eq!(e.fake.count(JRE8_URL), 0);
}

#[tokio::test]
async fn hash_retry_then_success() {
    let e = env(presets::linux_x64());
    serve_mojang(&e.fake, "17.0.8");
    e.fake.unserve("https://f.test/java");
    e.fake.serve("https://f.test/java", b"corrupt".to_vec());
    e.fake
        .serve("https://f.test/java", b"corrupt again".to_vec());
    e.fake.serve("https://f.test/java", JAVA_BYTES);
    let r = e
        .mgr
        .verify("java-runtime-gamma", None, &CancelToken::new())
        .await
        .unwrap();
    assert_eq!(fs::read(&r.java).unwrap(), JAVA_BYTES);
    assert_eq!(e.fake.count("https://f.test/java"), 3);
}

#[tokio::test]
async fn hash_failure_after_three_attempts_fails_without_a_marker() {
    let e = env(presets::linux_x64());
    serve_mojang(&e.fake, "17.0.8");
    e.fake.unserve("https://f.test/java");
    e.fake
        .serve("https://f.test/java", b"always wrong".to_vec());
    let err = e
        .mgr
        .verify("java-runtime-gamma", None, &CancelToken::new())
        .await
        .unwrap_err();
    match &err {
        RuntimeError::InstallFailed { component, cause } => {
            assert_eq!(component, "java-runtime-gamma");
            assert_eq!(cause.l10n_key(), "runtime.error.fileFailed");
            assert!(cause.to_string().contains("bin/java"), "{cause}");
        }
        other => panic!("{other:?}"),
    }
    assert_eq!(e.fake.count("https://f.test/java"), 3);
    let dir = e.paths.runtime_component_dir("java-runtime-gamma");
    assert!(!dir.join(".version").exists());
    assert!(!dir.join("bin/java").exists(), "bad bytes deleted");
}

#[tokio::test]
async fn offline_index_uses_the_cached_file() {
    let e = env(presets::linux_x64());
    serve_mojang(&e.fake, "17.0.8");
    fs::create_dir_all(e.paths.runtime_dir()).unwrap();
    fs::write(
        e.paths.runtime_dir().join("mojang-runtime-index.json"),
        index_json("17.0.8"),
    )
    .unwrap();
    e.fake.unserve(RUNTIME_INDEX_URL);
    let r = e
        .mgr
        .verify("java-runtime-gamma", None, &CancelToken::new())
        .await
        .unwrap();
    assert_eq!(r.version, "17.0.8");
}

#[tokio::test]
async fn unknown_component_and_platform_errors() {
    let e = env(presets::linux_x64());
    serve_mojang(&e.fake, "17.0.8");
    let err = e
        .mgr
        .verify("java-runtime-delta", None, &CancelToken::new())
        .await
        .unwrap_err();
    let RuntimeError::InstallFailed { cause, .. } = &err else {
        panic!("{err:?}")
    };
    assert_eq!(cause.l10n_key(), "runtime.error.componentNotFound");

    let e = env(presets::freebsd_x64());
    let err = e
        .mgr
        .verify("java-runtime-gamma", None, &CancelToken::new())
        .await
        .unwrap_err();
    let RuntimeError::InstallFailed { cause, .. } = &err else {
        panic!("{err:?}")
    };
    assert_eq!(cause.l10n_key(), "runtime.error.platformNotFound");
    assert!(e.fake.requests().is_empty());
}

#[tokio::test]
async fn cancel_before_install_is_reported_as_cancel() {
    let e = env(presets::linux_x64());
    serve_mojang(&e.fake, "17.0.8");
    let c = CancelToken::new();
    c.cancel();
    let err = e
        .mgr
        .verify("java-runtime-gamma", None, &c)
        .await
        .unwrap_err();
    assert!(matches!(err, RuntimeError::Cancelled), "{err:?}");
    assert!(err.is_cancelled());
}

#[tokio::test]
async fn in_use_defers_an_update_and_blocks_delete() {
    let e = env(presets::linux_x64());
    serve_mojang(&e.fake, "17.0.8");
    e.mgr
        .verify("java-runtime-gamma", None, &CancelToken::new())
        .await
        .unwrap();
    let dir = e.paths.runtime_component_dir("java-runtime-gamma");
    fs::remove_file(dir.join(".version")).unwrap(); // as the update check would

    let (tx, rx) = tokio::sync::oneshot::channel::<()>();
    e.mgr.mark_in_use("java-runtime-gamma", 42, async move {
        let _ = rx.await;
    });
    assert!(e.mgr.is_in_use("java-runtime-gamma"));
    let before = e.fake.requests().len();
    let r = e
        .mgr
        .verify("java-runtime-gamma", None, &CancelToken::new())
        .await
        .unwrap();
    assert_eq!(r.version, "");
    assert_eq!(
        e.fake.requests().len(),
        before,
        "no reinstall under a running game"
    );

    let err = e.mgr.clear("java-runtime-gamma").await.unwrap_err();
    assert_eq!(err.l10n_key(), "runtime.error.inUse");
    assert!(dir.exists());

    tx.send(()).unwrap();
    for _ in 0..200 {
        if !e.mgr.is_in_use("java-runtime-gamma") {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(5)).await;
    }
    e.mgr.clear("java-runtime-gamma").await.unwrap();
    assert!(!dir.exists());
    assert_eq!(e.mgr.java_version("java-runtime-gamma"), None);
    assert!(e.mgr.installed().is_empty());
}

// ---------------------------------------------------------------- Liberica JRE 8

fn tar_gz(entries: &[(&str, &[u8])]) -> Vec<u8> {
    let mut b = tar::Builder::new(flate2::write::GzEncoder::new(
        Vec::new(),
        flate2::Compression::fast(),
    ));
    for (name, data) in entries {
        let mut h = tar::Header::new_gnu();
        h.set_size(data.len() as u64);
        h.set_mode(0o644);
        h.set_entry_type(tar::EntryType::Regular);
        h.set_cksum();
        b.append_data(&mut h, name, *data).unwrap();
    }
    b.into_inner().unwrap().finish().unwrap()
}

const JRE8_URL: &str = "https://download.bell-sw.test/jre8.tar.gz";

fn legacy_api(archive: &[u8]) -> String {
    format!(
        r#"[{{"version":"8u392+9","bundleType":"jre","featureVersion":8,"updateVersion":392,
            "sha1":"{}","filename":"bellsoft-jre8u392+9-linux-amd64.tar.gz",
            "downloadUrl":"{JRE8_URL}","packageType":"tar.gz"}}]"#,
        sha1_hex(archive)
    )
}

fn legacy_url(host: &HostInfo) -> String {
    let (os, arch) = liberica::os_arch(host);
    liberica::api_url(os, arch)
}

#[tokio::test]
async fn legacy_install_then_offline_fast_path() {
    let host = presets::linux_x64();
    let e = env(host.clone());
    let archive = tar_gz(&[
        ("jre8u392/bin/java", JAVA_BYTES),
        ("jre8u392/bin/keytool", b"k"),
        ("jre8u392/lib/rt.jar", b"rt"),
    ]);
    e.fake.serve(&legacy_url(&host), legacy_api(&archive));
    e.fake.serve(JRE8_URL, archive.clone());

    let r = e
        .mgr
        .verify("jre-legacy", None, &CancelToken::new())
        .await
        .unwrap();
    let dir = e.paths.runtime_component_dir("jre-legacy");
    assert_eq!(r.version, "8u392+9");
    assert_eq!(
        r.java,
        std::path::absolute(dir.join("jre8u392/bin/java")).unwrap()
    );
    assert_eq!(fs::read_to_string(dir.join(".version")).unwrap(), "8u392+9");
    assert!(dir.join(LEGACY_API_FILE_NAME).is_file());
    assert!(
        dir.join("bellsoft-jre8u392+9-linux-amd64.tar.gz").is_file(),
        "archive kept"
    );
    #[cfg(unix)]
    assert!(crate::fsutil::is_executable(
        &dir.join("jre8u392/bin/keytool")
    ));

    // Offline second process: no request at all on the launch path.
    let mgr2 = e.second_manager(host.clone());
    let before = e.fake.requests().len();
    let r2 = mgr2
        .verify("jre-legacy", None, &CancelToken::new())
        .await
        .unwrap();
    assert_eq!(r2, r);
    assert_eq!(e.fake.requests().len(), before);
    mgr2.join_background().await;
    assert!(dir.join(".version").exists(), "same version: marker kept");
}

#[tokio::test]
async fn legacy_archive_hash_failure_deletes_the_archive() {
    let host = presets::linux_x64();
    let e = env(host.clone());
    let archive = tar_gz(&[("jre8u392/bin/java", JAVA_BYTES)]);
    e.fake.serve(&legacy_url(&host), legacy_api(&archive));
    e.fake.serve(JRE8_URL, b"tampered".to_vec());
    let err = e
        .mgr
        .verify("jre-legacy", None, &CancelToken::new())
        .await
        .unwrap_err();
    let RuntimeError::Jre8InstallFailed { cause } = &err else {
        panic!("{err:?}")
    };
    assert_eq!(cause.l10n_key(), "runtime.error.jre8HashFailed");
    let dir = e.paths.runtime_component_dir("jre-legacy");
    assert!(!dir.join("bellsoft-jre8u392+9-linux-amd64.tar.gz").exists());
}

#[tokio::test]
async fn legacy_falls_back_to_existing_only_with_a_marker_and_never_on_cancel() {
    let host = presets::linux_x64();
    let e = env(host.clone());
    let dir = e.paths.runtime_component_dir("jre-legacy");
    fs::create_dir_all(dir.join("jre8u392/bin")).unwrap();
    fs::write(dir.join("jre8u392/bin/java"), JAVA_BYTES).unwrap();

    // Offline, no cached API answer, no marker: fail.
    let err = e
        .mgr
        .verify("jre-legacy", None, &CancelToken::new())
        .await
        .unwrap_err();
    assert_eq!(err.l10n_key(), "log.runtimeManager.jre8InstallFailed");

    // With a marker the whole old install is used.
    fs::write(dir.join(".version"), "8u382+6\n").unwrap();
    let r = e
        .mgr
        .verify("jre-legacy", None, &CancelToken::new())
        .await
        .unwrap();
    assert_eq!(r.version, "8u382+6");
    assert_eq!(
        r.java,
        std::path::absolute(dir.join("jre8u392/bin/java")).unwrap()
    );

    // A cancel is never papered over.
    let c = CancelToken::new();
    c.cancel();
    let err = e.mgr.verify("jre-legacy", None, &c).await.unwrap_err();
    assert!(matches!(err, RuntimeError::Cancelled), "{err:?}");
}

#[tokio::test]
async fn legacy_uses_the_cached_api_answer_when_offline() {
    let host = presets::linux_x64();
    let e = env(host.clone());
    let archive = tar_gz(&[("jre8u392/bin/java", JAVA_BYTES)]);
    let dir = e.paths.runtime_component_dir("jre-legacy");
    fs::create_dir_all(&dir).unwrap();
    fs::write(dir.join(LEGACY_API_FILE_NAME), legacy_api(&archive)).unwrap();
    e.fake.serve(JRE8_URL, archive);
    let r = e
        .mgr
        .verify("jre-legacy", None, &CancelToken::new())
        .await
        .unwrap();
    assert_eq!(r.version, "8u392+9");
}

// ---------------------------------------------------------------- Linux ARM64

#[tokio::test]
async fn linux_arm64_installs_from_adoptium_when_liberica_fails() {
    let host = presets::linux_arm64();
    let e = env(host.clone());
    let archive = tar_gz(&[
        ("jdk-17.0.13+11-jre/bin/java", JAVA_BYTES),
        ("jdk-17.0.13+11-jre/lib/modules", LIB_BYTES),
        ("jdk-17.0.13+11-jre/release", b"JAVA_VERSION=17"),
    ]);
    let adoptium = liberica::adoptium_api_url(17, &host);
    e.fake.serve(
        &adoptium,
        format!(
            r#"[{{"binary":{{"package":{{"checksum":"{}","link":"https://github.test/jre17.tar.gz",
               "name":"OpenJDK17U-jre_aarch64_linux_hotspot_17.0.13_11.tar.gz"}}}},
               "release_name":"jdk-17.0.13+11","version":{{"major":17,"security":13}}}}]"#,
            sha256_hex(&archive)
        ),
    );
    e.fake.serve("https://github.test/jre17.tar.gz", archive);

    let mut seen = Vec::new();
    let rec = Mutex::new(Vec::<String>::new());
    let cb = |_: &str, t: &str, _: f64| rec.lock().push(t.to_string());
    let r = e
        .mgr
        .verify("java-runtime-gamma", Some(&cb), &CancelToken::new())
        .await
        .unwrap();
    seen.extend(rec.lock().iter().cloned());
    assert!(!seen.is_empty());
    let dir = e.paths.runtime_component_dir("java-runtime-gamma");
    assert_eq!(r.version, "jdk-17.0.13+11");
    assert_eq!(r.java, std::path::absolute(dir.join("bin/java")).unwrap());
    assert_eq!(fs::read(dir.join("lib/modules")).unwrap(), LIB_BYTES);
    assert_eq!(
        fs::read_to_string(dir.join(SOURCE_FILE_NAME)).unwrap(),
        "adoptium"
    );
    assert!(dir.join(ADOPTIUM_API_FILE_NAME).is_file());
    assert!(!dir.join(".extract").exists());
    let (os, arch) = liberica::os_arch(&host);
    assert_eq!(
        e.fake.count(&liberica::latest_api_url(17, os, arch)),
        1,
        "Liberica tried first"
    );
    assert_eq!(
        e.fake.count(RUNTIME_INDEX_URL),
        0,
        "Mojang has nothing for linux-arm64"
    );

    // Fast path afterwards, and the background check asks the recorded source.
    let mgr2 = e.second_manager(host.clone());
    let before = e.fake.requests().len();
    let r2 = mgr2
        .verify("java-runtime-gamma", None, &CancelToken::new())
        .await
        .unwrap();
    assert_eq!(r2.version, "jdk-17.0.13+11");
    assert_eq!(e.fake.requests().len(), before);
    mgr2.join_background().await;
    assert_eq!(e.fake.count(&adoptium), 2);
    assert!(dir.join(".version").exists());
}

#[tokio::test]
async fn linux_arm64_prefers_liberica_and_replaces_the_old_payload() {
    let host = presets::linux_arm64();
    let e = env(host.clone());
    let dir = e.paths.runtime_component_dir("java-runtime-delta");
    fs::create_dir_all(dir.join("old-stuff")).unwrap();
    fs::write(dir.join("old-archive.tar.gz"), b"x").unwrap();
    let archive = tar_gz(&[("jre-21.0.5/bin/java", JAVA_BYTES)]);
    let (os, arch) = liberica::os_arch(&host);
    e.fake.serve(
        &liberica::latest_api_url(21, os, arch),
        format!(
            r#"[{{"version":"21.0.5+11","bundleType":"jre","featureVersion":21,"updateVersion":5,
                "sha1":"{}","filename":"bellsoft-jre21.0.5+11-linux-aarch64.tar.gz",
                "downloadUrl":"https://download.bell-sw.test/jre21.tar.gz","packageType":"tar.gz"}}]"#,
            sha1_hex(&archive)
        ),
    );
    e.fake
        .serve("https://download.bell-sw.test/jre21.tar.gz", archive);
    let r = e
        .mgr
        .verify("java-runtime-delta", None, &CancelToken::new())
        .await
        .unwrap();
    assert_eq!(r.version, "21.0.5+11");
    assert_eq!(
        fs::read_to_string(dir.join(SOURCE_FILE_NAME)).unwrap(),
        "liberica"
    );
    assert!(!dir.join("old-stuff").exists());
    assert!(!dir.join("old-archive.tar.gz").exists());
    assert!(dir.join("bin/java").is_file());
    assert_eq!(e.fake.count(&liberica::adoptium_api_url(21, &host)), 0);
}

#[tokio::test]
async fn linux_arm64_without_any_build_fails_cleanly() {
    let e = env(presets::linux_arm64());
    let err = e
        .mgr
        .verify("java-runtime-epsilon", None, &CancelToken::new())
        .await
        .unwrap_err();
    let RuntimeError::InstallFailed { cause, .. } = &err else {
        panic!("{err:?}")
    };
    assert_eq!(cause.l10n_key(), "runtime.error.noAltBuild");

    let err = e
        .mgr
        .verify("minecraft-java-exe", None, &CancelToken::new())
        .await
        .unwrap_err();
    let RuntimeError::InstallFailed { cause, .. } = &err else {
        panic!("{err:?}")
    };
    assert_eq!(cause.l10n_key(), "runtime.error.unsupportedComponent");
}

#[test]
fn component_mapping_and_sources() {
    use crate::manager::{RuntimeSource, component_major, source_for};
    let cases = [
        (8, "jre-legacy"),
        (16, "java-runtime-alpha"),
        (17, "java-runtime-gamma"),
        (21, "java-runtime-delta"),
        (25, "java-runtime-epsilon"),
        (0, "jre-legacy"),
        (-1, "jre-legacy"),
        (11, "java-runtime-alpha"),
        (18, "java-runtime-delta"),
        (30, "java-runtime-epsilon"),
    ];
    for (major, c) in cases {
        assert_eq!(RuntimeManager::component_for_major(major), c, "{major}");
    }
    assert_eq!(
        RuntimeManager::component_for_major(8),
        mmcl_core::consts::runtime::DEFAULT_RUNTIME_COMPONENT
    );
    for c in [
        "java-runtime-alpha",
        "java-runtime-gamma",
        "java-runtime-delta",
        "java-runtime-epsilon",
    ] {
        assert_eq!(
            RuntimeManager::component_for_major(component_major(c).unwrap() as i32),
            c
        );
    }
    assert_eq!(
        source_for("jre-legacy", &presets::linux_arm64()),
        RuntimeSource::LegacyLiberica
    );
    assert_eq!(
        source_for("java-runtime-gamma", &presets::linux_arm64()),
        RuntimeSource::Alternative(17)
    );
    assert_eq!(
        source_for("java-runtime-gamma", &presets::mac_arm()),
        RuntimeSource::Mojang("mac-os-arm64")
    );
    assert_eq!(
        source_for("java-runtime-gamma", &presets::windows_x86()),
        RuntimeSource::Mojang("windows-x86")
    );
    assert_eq!(
        source_for("java-runtime-gamma", &presets::freebsd_x64()),
        RuntimeSource::Unavailable
    );
}

#[tokio::test]
async fn java_path_verifies_once_then_uses_the_cache() {
    let e = env(presets::linux_x64());
    serve_mojang(&e.fake, "17.0.8");
    let p = e
        .mgr
        .java_path("java-runtime-gamma", &CancelToken::new())
        .await
        .unwrap();
    let n = e.fake.requests().len();
    let p2 = e
        .mgr
        .java_path_for_major(17, &CancelToken::new())
        .await
        .unwrap();
    assert_eq!(p, p2);
    assert_eq!(e.fake.requests().len(), n);
}
