// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Manifest fetch with an offline cache and conditional GET (Java `GameModPackFetcher`).
//!
//! * `file:` manifests (imported, editor-created, loader catalogue) are read straight from disk:
//!   never cached, never revalidated.
//! * Online, a conditional GET revalidates the cached body; a 304 touches the cache file so an
//!   unchanged manifest never reads as stale.
//! * Offline, the cached body is used; a stale cache (over 7 days) warns once per URL and shows
//!   one non-modal toast per process (parallel loads once raised a barrage of modal dialogs).

use std::collections::HashSet;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, LazyLock};
use std::time::{Duration, SystemTime};

use mmcl_core::DataPaths;
use mmcl_i18n::{t, tf};
use mmcl_manifest::{PackManifest, Syntax};
use parking_lot::Mutex;
use serde::{Deserialize, Serialize};

use crate::error::{ModpackError, Result};
use crate::http::PackHttp;
use crate::index::InstallIndex;
use crate::pack::GameModPack;
use crate::verify_state::compute_manifest_sha256;

/// Largest manifest body accepted.
pub const MANIFEST_MAX_BYTES: u64 = mmcl_net::MANIFEST_MAX_BYTES;
/// An offline cache older than this warns.
pub const MANIFEST_STALE_THRESHOLD: Duration = Duration::from_secs(7 * 24 * 60 * 60);

/// Shows a non-modal notification: `(title, body)`.
pub type Notifier = Arc<dyn Fn(&str, &str) + Send + Sync>;

static STALE_WARNED: LazyLock<Mutex<HashSet<String>>> = LazyLock::new(Default::default);
static STALE_TOAST_SHOWN: AtomicBool = AtomicBool::new(false);

/// Java's `String.hashCode()` (over UTF-16 units) as `Integer.toHexString`: the legacy cache
/// file name.
pub fn java_string_hash_code_hex(s: &str) -> String {
    let h = s
        .encode_utf16()
        .fold(0i32, |h, u| h.wrapping_mul(31).wrapping_add(i32::from(u)));
    format!("{:x}", h as u32)
}

/// The path of a `file:` URL, if it is one.
pub fn file_url_to_path(url: &str) -> Option<PathBuf> {
    if !url.trim().to_ascii_lowercase().starts_with("file:") {
        return None;
    }
    match mmcl_net::Source::parse(url) {
        Ok(mmcl_net::Source::File(p)) => Some(p),
        _ => None,
    }
}

/// `true` for a `file:` URL.
pub fn is_file_url(url: &str) -> bool {
    url.trim().to_ascii_lowercase().starts_with("file:")
}

/// The `file:` URL of a local path.
pub fn path_to_file_url(path: &Path) -> String {
    let abs = std::path::absolute(path).unwrap_or_else(|_| path.to_path_buf());
    url::Url::from_file_path(&abs)
        .map(|u| u.to_string())
        .unwrap_or_else(|_| format!("file:{}", abs.display()))
}

/// The cache validators sidecar.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct CacheMeta {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub etag: Option<String>,
    #[serde(rename = "lastModified", skip_serializing_if = "Option::is_none")]
    pub last_modified: Option<String>,
}

/// Loads manifests into packs.
#[derive(Clone)]
pub struct ManifestFetcher {
    paths: Arc<DataPaths>,
    http: Arc<dyn PackHttp>,
    notifier: Notifier,
}

impl ManifestFetcher {
    /// A fetcher that notifies through the logger's toast hook.
    pub fn new(paths: Arc<DataPaths>, http: Arc<dyn PackHttp>) -> Self {
        ManifestFetcher {
            paths,
            http,
            notifier: Arc::new(|_title, body| mmcl_log::warn(body)),
        }
    }

    /// Replaces the stale-cache notifier.
    pub fn with_notifier(mut self, n: Notifier) -> Self {
        self.notifier = n;
        self
    }

    pub fn paths(&self) -> &Arc<DataPaths> {
        &self.paths
    }
    pub fn http(&self) -> &Arc<dyn PackHttp> {
        &self.http
    }

    /// `<root>/installs/manifest_cache/<sha256(url)>.json`.
    pub fn cache_file(&self, url: &str) -> PathBuf {
        self.paths.manifest_cache_dir().join(format!(
            "{}.json",
            mmcl_util::hash::sha256_hex(url.as_bytes())
        ))
    }

    /// The validators sidecar of [`ManifestFetcher::cache_file`].
    pub fn meta_file(&self, url: &str) -> PathBuf {
        self.paths.manifest_cache_dir().join(format!(
            "{}.meta.json",
            mmcl_util::hash::sha256_hex(url.as_bytes())
        ))
    }

    /// The legacy (read-only) cache name.
    pub fn legacy_cache_file(&self, url: &str) -> PathBuf {
        self.paths
            .manifest_cache_dir()
            .join(format!("{}.json", java_string_hash_code_hex(url)))
    }

    fn read_local(&self, url: &str) -> Result<String> {
        let path = file_url_to_path(url)
            .filter(|p| p.is_file())
            .ok_or_else(|| {
                ModpackError::Manifest(tf!("modpack.error.localManifestNotFound", url))
            })?;
        let body = fs::read_to_string(&path).map_err(|e| {
            mmcl_log::warn_silent(&tf!(
                "log.gameModPackFetcher.couldNotReadLocalManifest",
                path.display().to_string(),
                e.to_string()
            ));
            ModpackError::Io(e)
        })?;
        mmcl_log::debug(&tf!(
            "log.gameModPackFetcher.loadedImportedManifest",
            path.display().to_string()
        ));
        Ok(body)
    }

    fn load_cached(&self, url: &str, warn_stale: bool) -> Option<String> {
        let primary = self.cache_file(url);
        let file = if primary.is_file() {
            primary
        } else {
            let legacy = self.legacy_cache_file(url);
            legacy.is_file().then_some(legacy)?
        };
        let body = match fs::read_to_string(&file) {
            Ok(b) => b,
            Err(e) => {
                mmcl_log::warn_silent(&tf!(
                    "log.gameModPackFetcher.cachedManifestUnreadable",
                    url,
                    e.to_string()
                ));
                return None;
            }
        };
        if warn_stale {
            self.warn_if_stale(url, &file);
        }
        Some(body)
    }

    fn warn_if_stale(&self, url: &str, file: &Path) {
        let age = fs::metadata(file)
            .and_then(|m| m.modified())
            .ok()
            .and_then(|m| SystemTime::now().duration_since(m).ok());
        let Some(age) = age.filter(|a| *a > MANIFEST_STALE_THRESHOLD) else {
            return;
        };
        let days = (age.as_secs() / 86_400).to_string();
        if STALE_WARNED.lock().insert(url.to_string()) {
            mmcl_log::warn_silent(&tf!(
                "log.gameModPackFetcher.usingStaleCachedManifest",
                url,
                &days
            ));
        }
        if !STALE_TOAST_SHOWN.swap(true, Ordering::SeqCst) {
            (self.notifier)(
                &t!("notification.manifest.stale.title"),
                &tf!("notification.manifest.stale.body", &days),
            );
        }
    }

    fn write_cache(&self, url: &str, body: &str) {
        let f = self.cache_file(url);
        // A unique temp file + rename: a parallel load (or a crash) must never read a
        // half-written body as the offline copy, and two writers of one URL (a revalidate and
        // an install) never share a staging file.
        let res = f.parent().map_or(Ok(()), fs::create_dir_all).and_then(|_| {
            let tmp = mmcl_net::unique_temp_file(&f);
            let r = fs::write(&tmp, body).and_then(|_| fs::rename(&tmp, &f));
            if r.is_err() {
                let _ = fs::remove_file(&tmp);
            }
            r
        });
        if res.is_err() {
            mmcl_log::warn_silent(&tf!("log.gameModPackFetcher.unableToCacheManifest", url));
        }
    }

    fn load_meta(&self, url: &str) -> Option<CacheMeta> {
        let t = fs::read_to_string(self.meta_file(url)).ok()?;
        serde_json::from_str(&t).ok()
    }

    fn save_meta(&self, url: &str, etag: Option<&str>, last_modified: Option<&str>) {
        let meta = CacheMeta {
            etag: etag.map(str::to_string),
            last_modified: last_modified.map(str::to_string),
        };
        let json = serde_json::to_value(&meta)
            .map(|v| mmcl_manifest::gson::to_compact_string(&v))
            .unwrap_or_default();
        let f = self.meta_file(url);
        let res = f
            .parent()
            .map_or(Ok(()), fs::create_dir_all)
            .and_then(|_| fs::write(&f, json));
        if res.is_err() {
            mmcl_log::warn_silent(&tf!("log.gameModPackFetcher.unableToSaveCacheMeta", url));
        }
    }

    async fn fetch_online(&self, url: &str) -> Result<String> {
        let prev = self.load_meta(url).unwrap_or_default();
        let res = self
            .http
            .get_conditional(
                url,
                MANIFEST_MAX_BYTES,
                prev.etag.as_deref(),
                prev.last_modified.as_deref(),
            )
            .await
            .map_err(|e| ModpackError::Manifest(e.to_string()))?;
        if res.not_modified {
            let body = match self.load_cached(url, false) {
                Some(b) => {
                    // The server just confirmed the cache fresh.
                    if let Ok(f) = fs::File::options().write(true).open(self.cache_file(url)) {
                        let _ = f.set_modified(SystemTime::now());
                    }
                    b
                }
                None => {
                    let b = self
                        .http
                        .get_bounded(url, MANIFEST_MAX_BYTES)
                        .await
                        .map_err(|e| ModpackError::Manifest(e.to_string()))?;
                    self.write_cache(url, &b);
                    b
                }
            };
            self.save_meta(url, res.etag.as_deref(), res.last_modified.as_deref());
            return Ok(body);
        }
        let body = res.body.unwrap_or_default();
        self.write_cache(url, &body);
        self.save_meta(url, res.etag.as_deref(), res.last_modified.as_deref());
        Ok(body)
    }

    fn parse(&self, url: &str, body: &str) -> Result<Arc<GameModPack>> {
        let parsed = PackManifest::parse_with_syntax(body).map_err(|e| {
            ModpackError::Manifest(mmcl_i18n::format(
                e.key(),
                &e.args()
                    .iter()
                    .map(|a| a.as_str().into())
                    .collect::<Vec<_>>(),
            ))
        })?;
        if parsed.syntax == Syntax::Lenient {
            mmcl_log::warn_silent(&tf!(mmcl_manifest::LOG_KEY_LENIENT_SYNTAX, url));
        }
        Ok(GameModPack::from_manifest(
            self.paths.clone(),
            parsed.manifest,
            Some(url.to_string()),
            Some(compute_manifest_sha256(body)),
        ))
    }

    fn upsert_index(&self, url: &str, pack: &GameModPack) {
        InstallIndex::upsert_and_save(&self.paths.install_index_file(), url, pack);
    }

    /// Loads `url` into a pack. Never fails: an error gives a failed pack with the URL set.
    pub async fn get(&self, url: &str, create_environment: bool) -> Arc<GameModPack> {
        match self.try_get(url, create_environment).await {
            Ok(p) => p,
            Err(e) => {
                mmcl_log::error_silent(&tf!(
                    "log.gameModPackFetcher.installedModPackNotLoaded",
                    url
                ));
                mmcl_log::error_silent(&e.to_string());
                GameModPack::failed(self.paths.clone(), url, &e.to_string())
            }
        }
    }

    async fn try_get(&self, url: &str, create_environment: bool) -> Result<Arc<GameModPack>> {
        let body = if is_file_url(url) {
            self.read_local(url)?
        } else if self.http.is_offline() {
            let b = self.load_cached(url, true).ok_or_else(|| {
                ModpackError::Manifest(t!("modpack.error.noCachedManifestOffline").into_owned())
            })?;
            mmcl_log::debug(&tf!(
                "log.gameModPackFetcher.loadedCachedManifestOffline",
                url
            ));
            b
        } else {
            self.fetch_online(url).await?
        };
        let pack = self.parse(url, &body)?;
        if create_environment {
            pack.prepare_environment()?;
            if !self.http.is_offline()
                && !pack
                    .environment()
                    .cache_images(&pack, self.http.as_ref())
                    .await
            {
                mmcl_log::warn_silent(&tf!(
                    "log.gameModPackFetcher.imageCacheFailed",
                    url,
                    t!("modpack.error.imagesNotCached")
                ));
            }
            // Only installed loads (the ones that get an environment) belong in the index;
            // the available list's fetches would otherwise fill it with packs never installed.
            self.upsert_index(url, &pack);
        }
        Ok(pack)
    }

    /// Loads `url` from disk only (a `file:` manifest or the cache). `None` on any error.
    pub fn get_from_cache(&self, url: &str, create_environment: bool) -> Option<Arc<GameModPack>> {
        let body = if is_file_url(url) {
            self.read_local(url).ok()?
        } else {
            self.load_cached(url, false)?
        };
        let pack = match self.parse(url, &body) {
            Ok(p) => p,
            Err(e) => {
                mmcl_log::warn_silent(&tf!(
                    "log.gameModPackFetcher.unableToLoadCachedManifest",
                    url
                ));
                mmcl_log::warn_silent(&e.to_string());
                return None;
            }
        };
        if create_environment && pack.prepare_environment().is_err() {
            mmcl_log::warn_silent(&tf!(
                "log.gameModPackFetcher.unableToLoadCachedManifest",
                url
            ));
            return None;
        }
        self.upsert_index(url, &pack);
        Some(pack)
    }

    /// The install-index stub for `url`.
    pub fn get_stub_from_index(&self, url: &str) -> Option<Arc<GameModPack>> {
        if url.trim().is_empty() {
            return None;
        }
        let idx = InstallIndex::load(&self.paths.install_index_file());
        idx.get(url)
            .map(|e| GameModPack::stub_from_index(self.paths.clone(), url, e))
    }

    /// The raw manifest text (for the editor and export: unknown keys survive). No network.
    pub fn load_manifest_text(&self, url: &str) -> Option<String> {
        if is_file_url(url) {
            return self.read_local(url).ok();
        }
        self.load_cached(url, false)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::http::fake::{FakeHttp, Reply};
    use mmcl_core::GameMode;

    const M: &str = r#"{"packName":"Alto","packVersion":"1","packMods":[]}"#;
    const URL: &str = "https://host.example/alto/manifest.mmcjson";

    fn fetcher(root: &Path, http: Arc<FakeHttp>) -> ManifestFetcher {
        ManifestFetcher::new(Arc::new(DataPaths::at_root(root, GameMode::Client)), http)
            .with_notifier(Arc::new(|_, _| {}))
    }

    #[test]
    fn java_hash_code() {
        assert_eq!(java_string_hash_code_hex(""), "0");
        assert_eq!(java_string_hash_code_hex("a"), "61");
        assert_eq!(java_string_hash_code_hex("hello"), "5e918d2");
        // Negative Java hash ("polygenelubricants".hashCode() == Integer.MIN_VALUE).
        assert_eq!(java_string_hash_code_hex("polygenelubricants"), "80000000");
        // Non-BMP: hashed over its two UTF-16 units (0xD83D, 0xDE00).
        let expect = (0xD83Di32.wrapping_mul(31).wrapping_add(0xDE00)) as u32;
        assert_eq!(
            java_string_hash_code_hex("\u{1F600}"),
            format!("{expect:x}")
        );
    }

    #[tokio::test]
    async fn ok_200_caches_body_and_validators() {
        let d = tempfile::tempdir().unwrap();
        let http = FakeHttp::new();
        http.on(URL, Reply::BodyWithEtag(M.into(), "\"v1\"".into()));
        let f = fetcher(d.path(), http.clone());
        let p = f.get(URL, true).await;
        assert!(!p.is_failed_load());
        assert_eq!(p.manifest_url(), Some(URL));
        assert_eq!(
            p.manifest_sha256(),
            Some(compute_manifest_sha256(M).as_str())
        );
        assert_eq!(fs::read_to_string(f.cache_file(URL)).unwrap(), M);
        assert_eq!(
            fs::read_to_string(f.meta_file(URL)).unwrap(),
            r#"{"etag":"\"v1\""}"#
        );
        // The install index has the card.
        assert!(f.get_stub_from_index(URL).unwrap().is_stub());
    }

    #[tokio::test]
    async fn not_modified_reuses_body_touches_mtime_and_sends_validators() {
        let d = tempfile::tempdir().unwrap();
        let http = FakeHttp::new();
        http.on(URL, Reply::BodyWithEtag(M.into(), "e1".into()));
        http.on(URL, Reply::NotModified);
        let f = fetcher(d.path(), http.clone());
        f.get(URL, false).await;
        let cf = f.cache_file(URL);
        let old = SystemTime::now() - Duration::from_secs(30 * 86_400);
        fs::File::options()
            .write(true)
            .open(&cf)
            .unwrap()
            .set_modified(old)
            .unwrap();
        let p = f.get(URL, false).await;
        assert!(!p.is_failed_load());
        assert!(http.count("etag=Some(\"e1\")") >= 1);
        let m = fs::metadata(&cf).unwrap().modified().unwrap();
        assert!(SystemTime::now().duration_since(m).unwrap() < Duration::from_secs(60));
    }

    #[tokio::test]
    async fn not_modified_with_missing_cache_refetches() {
        let d = tempfile::tempdir().unwrap();
        let http = FakeHttp::new();
        http.on(URL, Reply::NotModified);
        let f = fetcher(d.path(), http.clone());
        // The bounded GET path returns NotModified -> error; script a body for it instead.
        let http2 = FakeHttp::new();
        http2.on(URL, Reply::NotModified);
        http2.on(URL, Reply::Body(M.into()));
        let f2 = fetcher(d.path(), http2.clone());
        let _ = f;
        let p = f2.get(URL, false).await;
        assert!(!p.is_failed_load(), "{:?}", p.failed_error());
        assert_eq!(http2.count("GET"), 1);
        assert!(f2.cache_file(URL).is_file());
    }

    #[tokio::test]
    async fn offline_uses_cache_and_warns_only_when_stale() {
        let d = tempfile::tempdir().unwrap();
        let http = FakeHttp::new();
        http.set_offline(true);
        let shown = Arc::new(AtomicBool::new(false));
        let s2 = shown.clone();
        let f = fetcher(d.path(), http.clone())
            .with_notifier(Arc::new(move |_, _| s2.store(true, Ordering::SeqCst)));
        // No cache: failed pack with the URL set.
        let p = f.get(URL, false).await;
        assert!(p.is_failed_load());
        assert_eq!(p.manifest_url(), Some(URL));
        fs::create_dir_all(f.cache_file(URL).parent().unwrap()).unwrap();
        fs::write(f.cache_file(URL), M).unwrap();
        let p = f.get(URL, false).await;
        assert!(!p.is_failed_load());
        assert_eq!(http.log.lock().len(), 0, "no network while offline");
        // Stale: toast (once per process; another test may have shown it already).
        let old = SystemTime::now() - Duration::from_secs(10 * 86_400);
        fs::File::options()
            .write(true)
            .open(f.cache_file(URL))
            .unwrap()
            .set_modified(old)
            .unwrap();
        let before = STALE_TOAST_SHOWN.load(Ordering::SeqCst);
        f.get(URL, false).await;
        assert!(shown.load(Ordering::SeqCst) || before);
    }

    #[tokio::test]
    async fn legacy_cache_name_is_read() {
        let d = tempfile::tempdir().unwrap();
        let http = FakeHttp::new();
        http.set_offline(true);
        let f = fetcher(d.path(), http);
        let legacy = f.legacy_cache_file(URL);
        fs::create_dir_all(legacy.parent().unwrap()).unwrap();
        fs::write(&legacy, M).unwrap();
        assert!(!f.get(URL, false).await.is_failed_load());
        assert!(f.load_manifest_text(URL).is_some());
    }

    #[tokio::test]
    async fn file_urls_read_from_disk_only() {
        let d = tempfile::tempdir().unwrap();
        let http = FakeHttp::new();
        let f = fetcher(d.path(), http.clone());
        let mf = d.path().join("m.json");
        fs::write(&mf, M).unwrap();
        let u = path_to_file_url(&mf);
        let p = f.get(&u, true).await;
        assert!(!p.is_failed_load(), "{:?}", p.failed_error());
        assert!(p.pack_root().join("mods").is_dir());
        assert_eq!(http.count("COND"), 0);
        assert!(f.get_from_cache(&u, false).is_some());
        assert_eq!(f.load_manifest_text(&u).as_deref(), Some(M));
        let missing = path_to_file_url(&d.path().join("nope.json"));
        let p = f.get(&missing, false).await;
        assert!(p.is_failed_load());
        assert!(f.get_from_cache(&missing, false).is_none());
    }

    #[tokio::test]
    async fn network_failure_gives_failed_pack() {
        let d = tempfile::tempdir().unwrap();
        let http = FakeHttp::new();
        http.on(URL, Reply::Status(500));
        let f = fetcher(d.path(), http);
        let p = f.get(URL, false).await;
        assert!(p.is_failed_load());
        assert!(p.friendly_name().is_some());
        assert!(!p.is_launchable());
    }

    #[tokio::test]
    async fn lenient_manifest_parses() {
        let d = tempfile::tempdir().unwrap();
        let http = FakeHttp::new();
        http.on(
            URL,
            Reply::Body(b"{ // comment\n packName: 'Alto', packVersion: '1' }".to_vec()),
        );
        let f = fetcher(d.path(), http);
        let p = f.get(URL, false).await;
        assert_eq!(p.name(), "Alto");
    }
}
