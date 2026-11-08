// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Managed files (Java `ManagedGameFile`, `GameMod`, `GameAsset`): verify, download, and the
//! security gates every downloadable file passes.
//!
//! Gates, each a fix for a past bug or attack:
//!
//! * **Containment:** a file with a folder prefix must resolve inside that folder (lexical
//!   normalisation, case-insensitive on Windows), on the read side and again on the write side.
//! * **Scheme:** only `https`, or a `jar:file:` URL whose jar lies inside the launcher data
//!   folder (embedded Maven artifacts of an already verified installer). `http`, `file`, `ftp`
//!   and `jar:http` are refused, so a manifest cannot make the launcher read arbitrary files.
//! * **Post-download integrity:** every download is hashed against the strongest declared hash
//!   before it is accepted, up to three attempts; a compromised mirror's bytes never reach the
//!   classpath.
//! * **Session flag:** a file verified earlier in the session skips the hash, never the
//!   existence check.
//! * **Offline:** never accept unverified bytes without a network to re-check them.

use std::ffi::OsString;
use std::fs;
use std::io;
use std::path::{MAIN_SEPARATOR, Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, LazyLock};
use std::time::UNIX_EPOCH;

use dashmap::{DashMap, DashSet};
use mmcl_core::paths::normalize_lexical;
use mmcl_i18n::tf;
use mmcl_manifest::{HashAlgo, ManifestFile, Side};
use mmcl_net::{DownloadTracker, Source};
use mmcl_util::hash::{HashAlgorithm, constant_time_hex_eq, try_file_hash};
use parking_lot::Mutex;
use serde_json::Value;

use crate::context::{PrepareContext, VerifyMode};
use crate::error::{ModpackError, Result};
use crate::http::PackHttp;

/// Download attempts before a hash mismatch is fatal.
pub const MAX_DOWNLOAD_ATTEMPTS: u32 = 3;

fn algorithm(a: HashAlgo) -> HashAlgorithm {
    match a {
        HashAlgo::Sha256 => HashAlgorithm::Sha256,
        HashAlgo::Sha1 => HashAlgorithm::Sha1,
        HashAlgo::Md5 => HashAlgorithm::Md5,
    }
}

/// Lowercase hex digest of a file.
pub fn hash_file(path: &Path, algo: HashAlgo) -> io::Result<String> {
    try_file_hash(path, algorithm(algo))
}

/// Runs blocking work (hashing, zip reads) off the async threads.
pub(crate) async fn blocking<T: Send + 'static>(
    f: impl FnOnce() -> T + Send + 'static,
) -> io::Result<T> {
    match tokio::task::spawn_blocking(f).await {
        Ok(v) => Ok(v),
        Err(e) if e.is_panic() => std::panic::resume_unwind(e.into_panic()),
        Err(_) => Err(io::Error::other("blocking task cancelled")),
    }
}

/// Absolute, lexically normalised (no symlink resolution).
pub(crate) fn abs_norm(p: &Path) -> PathBuf {
    normalize_lexical(&std::path::absolute(p).unwrap_or_else(|_| p.to_path_buf()))
}

/// `path` lies inside `root` (or is `root` itself when `allow_equal`), compared lexically and,
/// on Windows and macOS, ignoring case (their default file systems are case-insensitive).
pub fn path_within(root: &Path, path: &Path, allow_equal: bool) -> bool {
    let fold = |p: PathBuf| -> PathBuf {
        if cfg!(any(windows, target_os = "macos")) {
            PathBuf::from(p.to_string_lossy().to_lowercase())
        } else {
            p
        }
    };
    let (r, p) = (fold(abs_norm(root)), fold(abs_norm(path)));
    p.starts_with(&r) && (allow_equal || p != r)
}

/// `true` for a `jar:file:...!/entry` URL whose jar lies inside `root` (the launcher data
/// folder). Any parse failure is `false`.
pub fn is_jar_url_contained_in(root: &Path, url: &str) -> bool {
    let trimmed = url.trim();
    let lower = trimmed.to_ascii_lowercase();
    if !lower.starts_with("jar:") {
        return false;
    }
    let rest = &lower[4..];
    let Some(bang) = rest.find("!/") else {
        return false;
    };
    if !rest[..bang].starts_with("file:") {
        return false;
    }
    match Source::parse(trimmed) {
        Ok(Source::JarEntry { jar, .. }) => path_within(root, &jar, false),
        _ => false,
    }
}

/// How a managed file's URL may be fetched.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RemoteKind {
    /// `https://`.
    Https,
    /// A contained `jar:file:` entry.
    JarEntry { jar: PathBuf, entry: String },
    /// Anything else: refused.
    Refused,
}

/// Classifies `remote` against the scheme gate.
pub fn classify_remote(remote: &str, data_root: &Path) -> RemoteKind {
    let r = remote.trim();
    let lower = r.to_ascii_lowercase();
    if lower.starts_with("https://") {
        return match Source::parse(r) {
            Ok(Source::Http(_)) => RemoteKind::Https,
            _ => RemoteKind::Refused,
        };
    }
    if is_jar_url_contained_in(data_root, r)
        && let Ok(Source::JarEntry { jar, entry }) = Source::parse(r)
    {
        return RemoteKind::JarEntry { jar, entry };
    }
    RemoteKind::Refused
}

// ---------------------------------------------------------------------------------------------
// Verify cache and session flags
// ---------------------------------------------------------------------------------------------

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
struct VerifyKey {
    path: String,
    mtime_ms: i128,
    size: u64,
    expected: String,
    algo: HashAlgo,
}

/// The process-wide verify cache: `(absolute path, mtime, size, expected, algo) -> matches`.
/// A rewritten or touched file gets a new key, so nothing is ever evicted (one entry per file
/// per manifest).
static VERIFY_CACHE: LazyLock<DashMap<VerifyKey, bool>> = LazyLock::new(DashMap::new);

/// Number of verify-cache entries (diagnostics, tests).
pub fn verify_cache_len() -> usize {
    VERIFY_CACHE.len()
}

fn mtime_ms(m: &fs::Metadata) -> i128 {
    m.modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map_or(0, |d| d.as_millis() as i128)
}

/// Files verified in this session, keyed by path and expected hash. One set per pack instance:
/// a re-fetched pack (perhaps with new hashes) starts empty and re-verifies.
#[derive(Debug, Default)]
pub struct SessionVerified(DashSet<String>);

impl SessionVerified {
    pub fn new() -> Arc<Self> {
        Arc::new(SessionVerified::default())
    }
    fn contains(&self, k: &str) -> bool {
        self.0.contains(k)
    }
    fn mark(&self, k: String) {
        self.0.insert(k);
    }
    fn clear(&self, k: &str) {
        self.0.remove(k);
    }
    /// Number of verified files.
    pub fn len(&self) -> usize {
        self.0.len()
    }
    /// `true` when nothing has been verified yet.
    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }
}

// ---------------------------------------------------------------------------------------------
// ManagedFile
// ---------------------------------------------------------------------------------------------

type JsonMemo = Arc<Mutex<Option<(i128, u64, Arc<Value>)>>>;

/// One downloadable file: its manifest entry plus where it lives.
#[derive(Clone, Debug)]
pub struct ManagedFile {
    spec: ManifestFile,
    prefix: Option<PathBuf>,
    data_root: PathBuf,
    tracker: Option<Arc<DownloadTracker>>,
    session: Arc<SessionVerified>,
    md5_warned: Arc<AtomicBool>,
    json_memo: JsonMemo,
}

impl ManagedFile {
    /// A file whose `local` path is relative to `prefix` (and must stay inside it). `data_root`
    /// is the launcher data folder, the only place a `jar:file:` source may point into.
    pub fn new(
        spec: ManifestFile,
        prefix: impl Into<PathBuf>,
        data_root: impl Into<PathBuf>,
    ) -> Self {
        ManagedFile {
            spec,
            prefix: Some(prefix.into()),
            data_root: data_root.into(),
            tracker: None,
            session: SessionVerified::new(),
            md5_warned: Arc::new(AtomicBool::new(false)),
            json_memo: Arc::default(),
        }
    }

    /// A file whose `local` is already a full path (loader installer, libraries, Mojang
    /// manifests). There is no containment check: the caller built the path itself.
    pub fn at_path(mut spec: ManifestFile, path: &Path, data_root: impl Into<PathBuf>) -> Self {
        spec.local = Some(path.to_string_lossy().into_owned());
        ManagedFile {
            spec,
            prefix: None,
            data_root: data_root.into(),
            tracker: None,
            session: SessionVerified::new(),
            md5_warned: Arc::new(AtomicBool::new(false)),
            json_memo: Arc::default(),
        }
    }

    /// Reports download bytes to `tracker`.
    pub fn with_tracker(mut self, tracker: Option<Arc<DownloadTracker>>) -> Self {
        self.tracker = tracker;
        self
    }

    /// Shares a session-verified set (the owning pack's).
    pub fn with_session(mut self, session: Arc<SessionVerified>) -> Self {
        self.session = session;
        self
    }

    /// The manifest entry.
    pub fn spec(&self) -> &ManifestFile {
        &self.spec
    }

    /// The folder `local` is resolved against, if any.
    pub fn prefix(&self) -> Option<&Path> {
        self.prefix.as_deref()
    }

    /// `prefix` + separator + `local`, or `local` when there is no prefix.
    pub fn full_path(&self) -> PathBuf {
        let local = self.spec.local.as_deref().unwrap_or("");
        match &self.prefix {
            None => PathBuf::from(local),
            Some(p) => {
                let mut s: OsString = p.as_os_str().to_owned();
                let ends_with_sep = s.to_string_lossy().ends_with(['/', '\\']);
                if !ends_with_sep {
                    s.push(MAIN_SEPARATOR.to_string());
                }
                let local = if cfg!(windows) {
                    local.replace('/', "\\")
                } else {
                    local.to_string()
                };
                s.push(local);
                PathBuf::from(s)
            }
        }
    }

    /// The last component of the path.
    pub fn file_name(&self) -> String {
        self.full_path()
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default()
    }

    /// The containment gate: with a prefix, the resolved path must lie strictly inside it.
    pub fn is_contained(&self) -> bool {
        let Some(prefix) = &self.prefix else {
            return true;
        };
        let local = self.spec.local.as_deref().unwrap_or("");
        if local.trim().is_empty() || local.contains('\0') {
            return false;
        }
        // On Windows a `:` names a drive or an alternate data stream; neither belongs in a
        // pack file name.
        if cfg!(windows) && local.contains(':') {
            return false;
        }
        path_within(prefix, &self.full_path(), false)
    }

    /// `true` when this file is wanted on `side` (`clientReq` / `serverReq`).
    pub fn is_required_for(&self, side: Side) -> bool {
        self.spec.is_required_for(side)
    }

    fn session_key(&self) -> String {
        let hash = self
            .spec
            .strongest_hash()
            .map(|(a, h)| format!("{}:{}", a.key(), h.to_ascii_lowercase()))
            .unwrap_or_default();
        format!("{}|{hash}", abs_norm(&self.full_path()).display())
    }

    /// `true` once this file passed a check in the current session.
    pub fn is_session_verified(&self) -> bool {
        self.session.contains(&self.session_key())
    }

    /// Checks the file on disk under `ctx` (blocking: may hash).
    pub fn verify(&self, ctx: &PrepareContext) -> bool {
        if !self.is_contained() {
            mmcl_log::warn_silent(&tf!(
                "log.managedGameFile.outsidePackFolder",
                self.full_path().display().to_string()
            ));
            return false;
        }
        let path = self.full_path();
        let Ok(meta) = fs::metadata(&path) else {
            return false;
        };
        if ctx.verify_mode == VerifyMode::FastPath {
            return meta.is_file() && meta.len() > 0;
        }
        match self.spec.strongest_hash() {
            Some((algo, expected)) => {
                if algo == HashAlgo::Md5 && !self.md5_warned.swap(true, Ordering::Relaxed) {
                    mmcl_log::debug(&tf!(
                        "log.managedGameFile.md5Only",
                        path.display().to_string()
                    ));
                }
                meta.is_file() && cached_hash_matches(&path, &meta, algo, expected)
            }
            None => meta.is_file(),
        }
    }

    /// Hashes the file with the strongest declared algorithm, always for real (no fast path,
    /// no cache). `true` when nothing is declared.
    pub fn matches_declared_hash(&self, file: &Path) -> io::Result<bool> {
        match self.spec.strongest_hash() {
            None => Ok(true),
            Some((algo, expected)) => {
                let actual = hash_file(file, algo)?;
                Ok(constant_time_hex_eq(&actual, expected.trim()))
            }
        }
    }

    /// Makes the file present and verified, downloading it when needed. Returns whether it was
    /// downloaded.
    pub async fn update(&self, ctx: &PrepareContext, http: &dyn PackHttp) -> Result<bool> {
        let path = self.full_path();
        let key = self.session_key();
        // The session flag skips the hash, never the existence check.
        if self.session.contains(&key) {
            if fs::metadata(&path).is_ok_and(|m| m.is_file() && m.len() > 0) {
                return Ok(false);
            }
            self.session.clear(&key);
        }
        if self.spec.is_local_only() {
            if self.is_contained() && path.is_file() {
                self.session.mark(key);
                return Ok(false);
            }
            return Err(ModpackError::MissingNoUrl(path));
        }
        let me = self.clone();
        let c = ctx.clone();
        if blocking(move || me.verify(&c)).await? {
            self.session.mark(key);
            return Ok(false);
        }
        if http.is_offline() {
            return Err(if path.exists() {
                ModpackError::OfflineHashMismatch(path)
            } else {
                ModpackError::OfflineMissing(path)
            });
        }
        mmcl_log::warn_silent(&tf!(
            "log.managedGameFile.verifyFailedRedownload",
            path.display().to_string()
        ));
        let audit = ctx.is_auditing() && path.is_file();
        let declared = self.spec.strongest_hash().map(|(a, h)| (a, h.to_string()));
        let algo_only = declared.as_ref().map(|(a, _)| *a);
        let old = if audit {
            hash_with(path.clone(), algo_only).await
        } else {
            None
        };
        self.download(http).await?;
        if audit {
            let new = hash_with(path.clone(), algo_only).await;
            let (algo, expected) = match &declared {
                Some((a, h)) => (Some(*a), Some(h.as_str())),
                None => (None, None),
            };
            ctx.record_redownload(&path, old.as_deref(), new.as_deref(), expected, algo);
        }
        self.session.mark(key);
        Ok(true)
    }

    /// For callers about to execute the file: forget any earlier check, then
    /// [`ManagedFile::update`].
    pub async fn reverify(&self, ctx: &PrepareContext, http: &dyn PackHttp) -> Result<bool> {
        self.session.clear(&self.session_key());
        self.update(ctx, http).await
    }

    /// Side-aware update (`GameMod` / `GameAsset`): reports success without doing anything when
    /// the file is not wanted on `side`.
    pub async fn update_for(
        &self,
        side: Side,
        ctx: &PrepareContext,
        http: &dyn PackHttp,
    ) -> Result<bool> {
        if !self.is_required_for(side) {
            return Ok(true);
        }
        self.update(ctx, http).await
    }

    /// Downloads the file through both gates and the post-download hash check.
    pub async fn download(&self, http: &dyn PackHttp) -> Result<()> {
        let path = self.full_path();
        if !self.is_contained() {
            return Err(ModpackError::OutsidePack(path));
        }
        let remote = self.spec.remote.as_deref().unwrap_or("").trim().to_string();
        let kind = classify_remote(&remote, &self.data_root);
        if kind == RemoteKind::Refused {
            return Err(ModpackError::NonHttps(remote));
        }
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        for attempt in 1..=MAX_DOWNLOAD_ATTEMPTS {
            match &kind {
                RemoteKind::Https => http
                    .download(&remote, &path, self.tracker.clone())
                    .await
                    .map_err(|source| ModpackError::Download {
                        path: path.clone(),
                        source,
                    })?,
                RemoteKind::JarEntry { jar, entry } => {
                    let (jar, entry, dest) = (jar.clone(), entry.clone(), path.clone());
                    blocking(move || copy_jar_entry(&jar, &entry, &dest))
                        .await?
                        .map_err(|source| ModpackError::Download {
                            path: path.clone(),
                            source,
                        })?;
                }
                RemoteKind::Refused => return Err(ModpackError::NonHttps(remote)),
            }
            let me = self.clone();
            let p = path.clone();
            if blocking(move || me.matches_declared_hash(&p)).await?? {
                *self.json_memo.lock() = None;
                return Ok(());
            }
            let _ = fs::remove_file(&path);
            mmcl_log::warn_silent(&tf!(
                "log.managedGameFile.hashRetry",
                attempt.to_string(),
                MAX_DOWNLOAD_ATTEMPTS.to_string(),
                path.display().to_string()
            ));
        }
        Err(ModpackError::HashFailed {
            attempts: MAX_DOWNLOAD_ATTEMPTS,
            path,
            url: remote,
        })
    }

    /// [`ManagedFile::update`], then the file parsed as JSON. The parse is memoised per
    /// `(mtime, size)`.
    pub async fn read_json(&self, ctx: &PrepareContext, http: &dyn PackHttp) -> Result<Arc<Value>> {
        self.update(ctx, http).await?;
        let path = self.full_path();
        let meta = fs::metadata(&path)?;
        let (m, len) = (mtime_ms(&meta), meta.len());
        if let Some((mm, ll, v)) = self.json_memo.lock().as_ref()
            && *mm == m
            && *ll == len
        {
            return Ok(v.clone());
        }
        let bytes = fs::read(&path)?;
        let value: Value = serde_json::from_slice(&bytes)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e.to_string()))?;
        let value = Arc::new(value);
        *self.json_memo.lock() = Some((m, len, value.clone()));
        Ok(value)
    }
}

/// The file's digest with `algo` (off the async threads), `None` without an algorithm or on
/// an error.
async fn hash_with(p: PathBuf, algo: Option<HashAlgo>) -> Option<String> {
    let algo = algo?;
    blocking(move || hash_file(&p, algo).ok())
        .await
        .ok()
        .flatten()
}

fn cached_hash_matches(path: &Path, meta: &fs::Metadata, algo: HashAlgo, expected: &str) -> bool {
    let key = VerifyKey {
        path: abs_norm(path).to_string_lossy().into_owned(),
        mtime_ms: mtime_ms(meta),
        size: meta.len(),
        expected: expected.trim().to_ascii_lowercase(),
        algo,
    };
    if let Some(hit) = VERIFY_CACHE.get(&key) {
        return *hit;
    }
    let ok = hash_file(path, algo).is_ok_and(|actual| constant_time_hex_eq(&actual, &key.expected));
    VERIFY_CACHE.insert(key, ok);
    ok
}

/// Copies one jar entry to `dest` through a unique temp file and an atomic rename.
fn copy_jar_entry(
    jar: &Path,
    entry: &str,
    dest: &Path,
) -> std::result::Result<(), mmcl_net::DownloadError> {
    let bytes = mmcl_net::read_jar_entry(jar, entry)?;
    let tmp = mmcl_net::unique_temp_file(dest);
    let res = fs::write(&tmp, &bytes).and_then(|_| fs::rename(&tmp, dest));
    if res.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    Ok(res?)
}

/// Maps the core game mode to the manifest side.
pub fn side_of(mode: mmcl_core::GameMode) -> Side {
    match mode {
        mmcl_core::GameMode::Client => Side::Client,
        mmcl_core::GameMode::Server => Side::Server,
    }
}

#[cfg(test)]
mod tests;
