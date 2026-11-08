// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `CacheManager`: the content cache under `<root>/cache` (images, icons).
//!
//! File name: `sha256_hex(url)` plus the URL's extension (the text after the last `.` that
//! follows the last `/`, query removed, at most 6 characters with the dot). Entries live 24 h
//! by default; [`CacheManager::cleanup`] also trims the folder to 100 MiB, oldest first. Every
//! public method holds the cache's one lock (Java's `synchronized` class methods).

use std::path::{Path, PathBuf};
use std::sync::{Arc, LazyLock};
use std::time::{Duration, SystemTime};

use dashmap::DashMap;
use mmcl_i18n::tf;

use crate::download::{DownloadOpts, download_file};
use crate::error::DownloadError;
use crate::source::Source;

/// Default time to live.
pub const DEFAULT_TTL: Duration = Duration::from_secs(24 * 60 * 60);
/// Size the cache is trimmed to.
pub const MAX_CACHE_SIZE_BYTES: u64 = 100 * 1024 * 1024;

/// One cache folder.
pub struct CacheManager {
    dir: PathBuf,
    lock: tokio::sync::Mutex<()>,
}

static SHARED: LazyLock<DashMap<String, Arc<CacheManager>>> = LazyLock::new(DashMap::new);

/// The cache file name for `url`.
pub fn cache_file_name(url: &str) -> String {
    format!(
        "{}{}",
        mmcl_util::hash::sha256_hex_str(url),
        extension_of(url)
    )
}

/// The URL's extension (with the dot), or `""`.
pub fn extension_of(url: &str) -> &str {
    let path = url.split('?').next().unwrap_or(url);
    let dot = path.rfind('.');
    let slash = path.rfind('/');
    match dot {
        Some(d) if slash.is_none_or(|s| d > s) && d < path.len() - 1 => {
            let ext = &path[d..];
            if ext.chars().count() <= 6 { ext } else { "" }
        }
        _ => "",
    }
}

impl CacheManager {
    /// A manager for `dir` (created on demand).
    pub fn new(dir: impl Into<PathBuf>) -> Self {
        CacheManager {
            dir: dir.into(),
            lock: tokio::sync::Mutex::new(()),
        }
    }

    /// The one manager per folder, so every caller shares its lock.
    pub fn shared(dir: &Path) -> Arc<CacheManager> {
        SHARED
            .entry(mmcl_core::filelock::key_for(dir))
            .or_insert_with(|| Arc::new(CacheManager::new(dir)))
            .clone()
    }

    /// The manager for `paths.cache_dir()`.
    pub fn for_paths(paths: &mmcl_core::DataPaths) -> Arc<CacheManager> {
        Self::shared(&paths.cache_dir())
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    fn ensure_dir(&self) {
        let _ = std::fs::create_dir_all(&self.dir);
    }

    fn path_for(&self, url: &str) -> PathBuf {
        self.dir.join(cache_file_name(url))
    }

    fn cached_unlocked(&self, url: &str, ttl: Duration) -> Option<PathBuf> {
        self.ensure_dir();
        let p = self.path_for(url);
        let meta = std::fs::metadata(&p).ok()?;
        if age(&meta) < ttl {
            return Some(p);
        }
        let _ = std::fs::remove_file(&p);
        None
    }

    /// The cached file for `url` if it is younger than `ttl`; an expired file is deleted.
    pub async fn get_cached(&self, url: &str, ttl: Duration) -> Option<PathBuf> {
        let _g = self.lock.lock().await;
        self.cached_unlocked(url, ttl)
    }

    /// The cached file, downloading it first when missing or expired. Only `http(s)` URLs are
    /// cached (a manifest-supplied URL must not copy local files into the cache). **Guard:** an
    /// empty download is deleted and fails, instead of being served for the whole TTL.
    pub async fn download_and_cache(
        &self,
        url: &str,
        ttl: Duration,
    ) -> Result<PathBuf, DownloadError> {
        let _g = self.lock.lock().await;
        if let Some(p) = self.cached_unlocked(url, ttl) {
            return Ok(p);
        }
        let lower = url.to_ascii_lowercase();
        if !lower.starts_with("https://") && !lower.starts_with("http://") {
            return Err(DownloadError::CacheRefused(url.to_string()));
        }
        let src = Source::parse(url)?;
        let dest = self.path_for(url);
        download_file(&src, &dest, &DownloadOpts::default()).await?;
        if std::fs::metadata(&dest).map(|m| m.len()).unwrap_or(0) == 0 {
            let _ = std::fs::remove_file(&dest);
            return Err(DownloadError::CacheEmpty(url.to_string()));
        }
        Ok(dest)
    }

    /// Deletes entries older than [`DEFAULT_TTL`], then the oldest until the rest fit in
    /// [`MAX_CACHE_SIZE_BYTES`]. Logs `log.cacheManager.cleanupComplete`.
    pub async fn cleanup(&self) {
        let _g = self.lock.lock().await;
        self.cleanup_unlocked(DEFAULT_TTL, MAX_CACHE_SIZE_BYTES);
    }

    fn cleanup_unlocked(&self, ttl: Duration, max_bytes: u64) -> u64 {
        self.ensure_dir();
        let Ok(rd) = std::fs::read_dir(&self.dir) else {
            return 0;
        };
        let mut live: Vec<(PathBuf, SystemTime, u64)> = Vec::new();
        let mut total: u64 = 0;
        let mut seen = 0usize;
        for entry in rd.flatten() {
            seen += 1;
            let Ok(meta) = entry.metadata() else { continue };
            if !meta.is_file() {
                continue;
            }
            if age(&meta) > ttl {
                let _ = std::fs::remove_file(entry.path());
            } else {
                total += meta.len();
                live.push((
                    entry.path(),
                    meta.modified().unwrap_or(SystemTime::UNIX_EPOCH),
                    meta.len(),
                ));
            }
        }
        if seen == 0 {
            return 0;
        }
        if total > max_bytes {
            live.sort_by_key(|(_, m, _)| *m);
            for (p, _, len) in live {
                if total <= max_bytes {
                    break;
                }
                if std::fs::remove_file(&p).is_ok() {
                    total -= len;
                }
            }
        }
        mmcl_log::debug(&tf!(
            "log.cacheManager.cleanupComplete",
            (total / 1024).to_string()
        ));
        total
    }
}

fn age(meta: &std::fs::Metadata) -> Duration {
    meta.modified()
        .ok()
        .and_then(|m| SystemTime::now().duration_since(m).ok())
        .unwrap_or(Duration::ZERO)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn set_age(p: &Path, d: Duration) {
        let f = fs::OpenOptions::new().write(true).open(p).unwrap();
        f.set_modified(SystemTime::now() - d).unwrap();
    }

    #[test]
    fn extension_rule_matches_java() {
        assert_eq!(extension_of("https://a.com/x/logo.png"), ".png");
        assert_eq!(extension_of("https://a.com/x/logo.png?sig=1.2"), ".png");
        assert_eq!(extension_of("https://a.com/x.dir/logo"), "");
        assert_eq!(extension_of("https://a.com/x/logo."), "");
        assert_eq!(extension_of("https://a.com/x/archive.toolong"), "");
        assert_eq!(extension_of("https://a.com/x/a.webp"), ".webp");
        let name = cache_file_name("https://a.com/x/logo.png");
        assert_eq!(name.len(), 64 + 4);
        assert!(name.ends_with(".png"));
    }

    #[tokio::test]
    async fn expired_entries_are_deleted_on_lookup() {
        let dir = tempfile::tempdir().unwrap();
        let c = CacheManager::new(dir.path());
        let url = "https://a.com/x/logo.png";
        let p = dir.path().join(cache_file_name(url));
        fs::write(&p, b"png").unwrap();
        assert_eq!(c.get_cached(url, DEFAULT_TTL).await, Some(p.clone()));
        set_age(&p, Duration::from_secs(25 * 3600));
        assert_eq!(c.get_cached(url, DEFAULT_TTL).await, None);
        assert!(!p.exists());
    }

    #[tokio::test]
    async fn non_http_urls_are_refused() {
        let dir = tempfile::tempdir().unwrap();
        let c = CacheManager::new(dir.path());
        for u in ["file:///etc/passwd", "jar:file:/a.jar!/x", "ftp://a/b"] {
            let e = c.download_and_cache(u, DEFAULT_TTL).await.unwrap_err();
            assert_eq!(
                e.to_string(),
                format!("Refusing to cache non-http(s) URL: {u}")
            );
        }
    }

    #[test]
    fn cleanup_removes_expired_then_oldest_over_the_cap() {
        let dir = tempfile::tempdir().unwrap();
        let c = CacheManager::new(dir.path());
        let old = dir.path().join("old.bin");
        let a = dir.path().join("a.bin");
        let b = dir.path().join("b.bin");
        fs::write(&old, vec![0u8; 10]).unwrap();
        fs::write(&a, vec![0u8; 600]).unwrap();
        fs::write(&b, vec![0u8; 600]).unwrap();
        set_age(&old, Duration::from_secs(48 * 3600));
        set_age(&a, Duration::from_secs(3600));
        set_age(&b, Duration::from_secs(60));
        let left = c.cleanup_unlocked(DEFAULT_TTL, 1000);
        assert!(!old.exists());
        assert!(!a.exists(), "the oldest live entry goes first");
        assert!(b.exists());
        assert_eq!(left, 600);
    }
}
