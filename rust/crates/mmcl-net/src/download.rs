// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The file download core and the plain string fetch.
//!
//! Every file download (tracked or not, with or without `Accept`) goes through
//! [`download_file`]. **Guard:** Java's old untracked path had no stall watchdog, and a
//! trickling connection hung a launch forever.
//!
//! Per call: take the per-path lock; then up to three attempts, each into its own
//! `<dest>.<uuid>.tmp` sibling, streamed in 64 KiB writes with cancel checks, the stall
//! watchdog, live progress, the battery throttle and an optional digest check; publish with an
//! atomic rename. A failed attempt deletes its temp file and rolls its bytes back out of the
//! tracker; a cancel stops at once with no further retries.

use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, LazyLock};
use std::time::{Duration, Instant};

use dashmap::DashMap;
use mmcl_i18n::tf;
use tokio::io::AsyncWriteExt;

use crate::cancel::CancelToken;
use crate::client::clients;
use crate::error::DownloadError;
use crate::listeners::{DownloadScope, current_scope, notify_download_progress, notify_retry};
use crate::offline::note_network_success;
use crate::source::Source;
use crate::throttle::StreamThrottle;
use crate::tracker::DownloadTracker;
use crate::verify::{ExpectedHash, StreamHasher, algorithm_name};

/// Attempts per download.
pub const MAX_RETRIES: u32 = 3;
/// Back-off unit: attempt `n` waits `n` times this before the next one.
pub const RETRY_BASE_DELAY: Duration = Duration::from_millis(1000);
/// Write buffer of the download loop.
pub const DOWNLOAD_BUFFER_SIZE: usize = 64 * 1024;
/// Stall watchdog window.
pub const STALL_WINDOW: Duration = Duration::from_secs(60);
/// Fewer bytes than this in one window is a stall (~512 B/s).
pub const STALL_MIN_WINDOW_BYTES: u64 = 30_720;

/// Options of one download.
#[derive(Clone, Debug, Default)]
pub struct DownloadOpts {
    /// Byte and file progress for the launch.
    pub tracker: Option<Arc<DownloadTracker>>,
    /// `Accept` request header.
    pub accept: Option<String>,
    /// Cancels the transfer at the next chunk, and any back-off sleep.
    pub cancel: Option<CancelToken>,
    /// Digest the bytes must have before they are published.
    pub expected: Option<ExpectedHash>,
    /// Listener scope; `None` uses [`current_scope`].
    pub scope: Option<DownloadScope>,
}

impl DownloadOpts {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn tracker(mut self, t: Arc<DownloadTracker>) -> Self {
        self.tracker = Some(t);
        self
    }

    pub fn accept(mut self, a: impl Into<String>) -> Self {
        self.accept = Some(a.into());
        self
    }

    pub fn cancel(mut self, c: CancelToken) -> Self {
        self.cancel = Some(c);
        self
    }

    pub fn expected(mut self, h: ExpectedHash) -> Self {
        self.expected = Some(h);
        self
    }

    pub fn scope(mut self, s: DownloadScope) -> Self {
        self.scope = Some(s);
        self
    }
}

/// Retry and watchdog constants (shrunk in tests).
#[derive(Clone, Copy, Debug)]
pub(crate) struct Tuning {
    pub max_attempts: u32,
    pub retry_base: Duration,
    pub stall_window: Duration,
    pub stall_min_bytes: u64,
}

impl Tuning {
    pub(crate) const DEFAULT: Tuning = Tuning {
        max_attempts: MAX_RETRIES,
        retry_base: RETRY_BASE_DELAY,
        stall_window: STALL_WINDOW,
        stall_min_bytes: STALL_MIN_WINDOW_BYTES,
    };
}

static PATH_LOCKS: LazyLock<DashMap<String, Arc<tokio::sync::Mutex<()>>>> =
    LazyLock::new(DashMap::new);

/// The lock every download into `path` holds. Keyed like `mmcl_core::filelock` (absolute,
/// lexically normalised, lowercased on Windows) so every spelling of a path shares it; never
/// pruned.
pub fn path_lock(path: &Path) -> Arc<tokio::sync::Mutex<()>> {
    PATH_LOCKS
        .entry(mmcl_core::filelock::key_for(path))
        .or_insert_with(|| Arc::new(tokio::sync::Mutex::new(())))
        .clone()
}

/// A sibling temp file unique to one attempt: `<absolute dest>.<uuid>.tmp`. **Guard:** shared
/// folders (assets) are downloaded into by two launches at once; a fixed `.tmp` let one launch
/// publish the other's half-written bytes.
pub fn unique_temp_file(dest: &Path) -> PathBuf {
    let abs = std::path::absolute(dest).unwrap_or_else(|_| dest.to_path_buf());
    let mut s = abs.into_os_string();
    s.push(format!(".{}.tmp", uuid::Uuid::new_v4()));
    PathBuf::from(s)
}

/// Downloads `src` to `dest` with retries, the stall watchdog, progress, an optional digest
/// check and an atomic publish.
pub async fn download_file(
    src: &Source,
    dest: &Path,
    opts: &DownloadOpts,
) -> Result<(), DownloadError> {
    let client = match src {
        Source::Http(_) => Some(clients()?.download.clone()),
        _ => None,
    };
    download_file_with(client.as_ref(), src, dest, opts, &Tuning::DEFAULT).await
}

/// [`download_file`] from a URL string.
pub async fn download_file_url(
    src: &str,
    dest: &Path,
    opts: &DownloadOpts,
) -> Result<(), DownloadError> {
    download_file(&Source::parse(src)?, dest, opts).await
}

/// One attempt's temp file and tracker bytes, cleaned up unless the attempt published.
struct AttemptGuard {
    tmp: PathBuf,
    tracker: Option<Arc<DownloadTracker>>,
    bytes: i64,
    armed: bool,
}

impl Drop for AttemptGuard {
    fn drop(&mut self) {
        if !self.armed {
            return;
        }
        let _ = std::fs::remove_file(&self.tmp);
        if self.bytes > 0
            && let Some(t) = &self.tracker
        {
            t.add_bytes(-self.bytes);
        }
    }
}

pub(crate) async fn download_file_with(
    client: Option<&reqwest::Client>,
    src: &Source,
    dest: &Path,
    opts: &DownloadOpts,
    tuning: &Tuning,
) -> Result<(), DownloadError> {
    let lock = path_lock(dest);
    let _guard = lock.lock().await;
    let scope = opts.scope.or_else(current_scope);
    let src_text = src.to_string();
    let mut registered = false;
    let mut last_err = None;
    for attempt in 1..=tuning.max_attempts {
        // The guard deletes the temp file and rolls the attempt's bytes back on failure, and
        // also when this future is dropped mid-transfer (an aborted pool task, a `select!`),
        // so a cancelled launch never leaves `<dest>.<uuid>.tmp` files in shared folders.
        let mut guard = AttemptGuard {
            tmp: unique_temp_file(dest),
            tracker: opts.tracker.clone(),
            bytes: 0,
            armed: true,
        };
        let result = attempt_once(
            client,
            src,
            dest,
            &guard.tmp,
            opts,
            tuning,
            scope,
            &mut registered,
            &mut guard.bytes,
        )
        .await;
        match result {
            Ok(()) => {
                guard.armed = false;
                if let Some(t) = &opts.tracker {
                    t.complete_download();
                }
                if src.is_network() {
                    note_network_success();
                }
                return Ok(());
            }
            Err(e) => {
                drop(guard);
                if e.is_cancelled() {
                    return Err(e);
                }
                if attempt < tuning.max_attempts {
                    let delay = tuning.retry_base * attempt;
                    mmcl_log::warn_silent(&tf!(
                        "log.network.downloadAttemptFailed",
                        attempt.to_string(),
                        src_text.as_str(),
                        delay.as_millis().to_string()
                    ));
                    mmcl_log::debug(&e.to_string());
                    notify_retry(scope, &src.file_name(), attempt + 1, tuning.max_attempts);
                    let cancelled = match &opts.cancel {
                        Some(c) => c.sleep(delay).await,
                        None => {
                            tokio::time::sleep(delay).await;
                            false
                        }
                    };
                    if cancelled {
                        return Err(DownloadError::Cancelled(src_text));
                    }
                }
                last_err = Some(e);
            }
        }
    }
    Err(last_err.unwrap_or(DownloadError::InvalidSource(src_text)))
}

#[allow(clippy::too_many_arguments)]
async fn attempt_once(
    client: Option<&reqwest::Client>,
    src: &Source,
    dest: &Path,
    tmp: &Path,
    opts: &DownloadOpts,
    tuning: &Tuning,
    scope: Option<DownloadScope>,
    registered: &mut bool,
    attempt_bytes: &mut i64,
) -> Result<(), DownloadError> {
    let cancelled = || DownloadError::Cancelled(src.to_string());
    if opts.cancel.as_ref().is_some_and(CancelToken::is_cancelled) {
        return Err(cancelled());
    }
    if let Some(parent) = dest.parent().filter(|p| !p.as_os_str().is_empty()) {
        tokio::fs::create_dir_all(parent).await?;
    }
    let actual_hex = match src {
        Source::Http(url) => {
            let client = client.ok_or_else(|| DownloadError::Client("no HTTP client".into()))?;
            let mut req = client.get(url.clone());
            if let Some(a) = &opts.accept {
                req = req.header(reqwest::header::ACCEPT, a.as_str());
            }
            let send = req.send();
            let resp = match &opts.cancel {
                Some(c) => tokio::select! {
                    _ = c.cancelled() => return Err(cancelled()),
                    r = send => r,
                },
                None => send.await,
            }
            .map_err(|e| DownloadError::request(url.as_str(), e))?;
            let status = resp.status().as_u16();
            if status >= 400 {
                return Err(DownloadError::Http {
                    status,
                    url: url.to_string(),
                });
            }
            let content_length = resp.content_length().map(|n| n as i64).unwrap_or(-1);
            register(opts, registered, content_length);
            stream_http(
                resp,
                src,
                dest,
                tmp,
                opts,
                tuning,
                scope,
                content_length,
                attempt_bytes,
            )
            .await?
        }
        Source::File(_) | Source::JarEntry { .. } => {
            copy_local(src, dest, tmp, opts, scope, registered, attempt_bytes).await?
        }
    };
    if let (Some(want), Some(got)) = (&opts.expected, actual_hex)
        && !want.matches(&got)
    {
        return Err(DownloadError::HashMismatch {
            url: src.to_string(),
            algorithm: algorithm_name(want.algorithm),
            expected: want.hex.to_ascii_lowercase(),
            actual: got,
        });
    }
    publish(tmp, dest).await?;
    Ok(())
}

fn register(opts: &DownloadOpts, registered: &mut bool, content_length: i64) {
    // **Guard:** register once per download, not per attempt; re-registering double-counted
    // expected bytes and leaked active downloads, so the bar and ETA went backwards.
    if let Some(t) = &opts.tracker
        && !*registered
    {
        t.register_download(content_length);
        *registered = true;
    }
}

#[allow(clippy::too_many_arguments)]
async fn stream_http(
    mut resp: reqwest::Response,
    src: &Source,
    dest: &Path,
    tmp: &Path,
    opts: &DownloadOpts,
    tuning: &Tuning,
    scope: Option<DownloadScope>,
    content_length: i64,
    attempt_bytes: &mut i64,
) -> Result<Option<String>, DownloadError> {
    let url = src.to_string();
    let file = tokio::fs::File::create(tmp).await?;
    let mut out = tokio::io::BufWriter::with_capacity(DOWNLOAD_BUFFER_SIZE, file);
    let mut hasher = opts
        .expected
        .as_ref()
        .map(|h| StreamHasher::new(h.algorithm));
    let mut throttle = StreamThrottle::new();
    let mut window_start = Instant::now();
    let mut window_bytes: u64 = 0;
    loop {
        let next = resp.chunk();
        let chunk = match &opts.cancel {
            Some(c) => tokio::select! {
                _ = c.cancelled() => return Err(DownloadError::Cancelled(url)),
                r = next => r,
            },
            None => next.await,
        }
        .map_err(|e| DownloadError::request(&url, e))?;
        let Some(chunk) = chunk else { break };
        if opts.cancel.as_ref().is_some_and(CancelToken::is_cancelled) {
            return Err(DownloadError::Cancelled(url));
        }
        let n = chunk.len();
        out.write_all(&chunk).await?;
        if let Some(h) = hasher.as_mut() {
            h.update(&chunk);
        }
        *attempt_bytes += n as i64;
        window_bytes += n as u64;
        if let Some(t) = &opts.tracker {
            t.add_bytes(n as i64);
        }
        let elapsed = window_start.elapsed();
        if elapsed >= tuning.stall_window {
            if window_bytes < tuning.stall_min_bytes {
                let name = file_name_of(dest);
                mmcl_log::warn_silent(&tf!(
                    "log.network.downloadStalled",
                    name.as_str(),
                    window_bytes.to_string(),
                    elapsed.as_secs().to_string()
                ));
                return Err(DownloadError::Stalled {
                    bytes: window_bytes,
                    seconds: elapsed.as_secs(),
                    url,
                });
            }
            window_start = Instant::now();
            window_bytes = 0;
        }
        notify_download_progress(
            scope,
            Some(dest),
            *attempt_bytes,
            content_length,
            opts.tracker.as_deref(),
        );
        if let Some(d) = throttle.after_chunk(n) {
            let cut = match &opts.cancel {
                Some(c) => c.sleep(d).await,
                None => {
                    tokio::time::sleep(d).await;
                    false
                }
            };
            if cut {
                return Err(DownloadError::Cancelled(url));
            }
        }
    }
    out.flush().await?;
    drop(out);
    Ok(hasher.map(StreamHasher::finish_hex))
}

fn file_name_of(p: &Path) -> String {
    p.file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default()
}

/// Opens a local source: the reader and its length.
fn open_local(src: &Source) -> Result<(Box<dyn Read + Send>, i64), DownloadError> {
    match src {
        Source::File(p) => {
            let f = std::fs::File::open(p)?;
            let len = f.metadata().map(|m| m.len() as i64).unwrap_or(-1);
            Ok((Box::new(f), len))
        }
        Source::JarEntry { jar, entry } => {
            let bytes = read_jar_entry(jar, entry)?;
            let len = bytes.len() as i64;
            Ok((Box::new(io::Cursor::new(bytes)), len))
        }
        Source::Http(u) => Err(DownloadError::InvalidSource(u.to_string())),
    }
}

/// Reads one entry of a jar or zip.
pub fn read_jar_entry(jar: &Path, entry: &str) -> Result<Vec<u8>, DownloadError> {
    let f = std::fs::File::open(jar)?;
    let mut zip = zip::ZipArchive::new(io::BufReader::new(f)).map_err(|e| {
        DownloadError::Io(io::Error::new(io::ErrorKind::InvalidData, e.to_string()))
    })?;
    let mut file = match zip.by_name(entry) {
        Ok(f) => f,
        Err(zip::result::ZipError::FileNotFound) => {
            return Err(DownloadError::JarEntryMissing {
                jar: jar.display().to_string(),
                entry: entry.to_string(),
            });
        }
        Err(e) => {
            return Err(DownloadError::Io(io::Error::new(
                io::ErrorKind::InvalidData,
                e.to_string(),
            )));
        }
    };
    let mut out = Vec::with_capacity(file.size() as usize);
    file.read_to_end(&mut out)?;
    Ok(out)
}

async fn copy_local(
    src: &Source,
    dest: &Path,
    tmp: &Path,
    opts: &DownloadOpts,
    scope: Option<DownloadScope>,
    registered: &mut bool,
    attempt_bytes: &mut i64,
) -> Result<Option<String>, DownloadError> {
    let src_c = src.clone();
    let opened = tokio::task::spawn_blocking(move || open_local(&src_c))
        .await
        .map_err(|e| DownloadError::Io(io::Error::other(e.to_string())))??;
    let (reader, len) = opened;
    register(opts, registered, len);
    let tmp = tmp.to_path_buf();
    let dest = dest.to_path_buf();
    let tracker = opts.tracker.clone();
    let cancel = opts.cancel.clone();
    let algo = opts.expected.as_ref().map(|h| h.algorithm);
    let url = src.to_string();
    let (result, copied) = tokio::task::spawn_blocking(move || {
        let mut copied: i64 = 0;
        let r = copy_blocking(
            reader,
            &tmp,
            &dest,
            len,
            tracker.as_deref(),
            cancel.as_ref(),
            algo,
            scope,
            &url,
            &mut copied,
        );
        (r, copied)
    })
    .await
    .map_err(|e| DownloadError::Io(io::Error::other(e.to_string())))?;
    *attempt_bytes += copied;
    result
}

#[allow(clippy::too_many_arguments)]
fn copy_blocking(
    mut reader: Box<dyn Read + Send>,
    tmp: &Path,
    dest: &Path,
    len: i64,
    tracker: Option<&DownloadTracker>,
    cancel: Option<&CancelToken>,
    algo: Option<mmcl_util::hash::HashAlgorithm>,
    scope: Option<DownloadScope>,
    url: &str,
    copied: &mut i64,
) -> Result<Option<String>, DownloadError> {
    let mut out = io::BufWriter::with_capacity(DOWNLOAD_BUFFER_SIZE, std::fs::File::create(tmp)?);
    let mut hasher = algo.map(StreamHasher::new);
    let mut buf = vec![0u8; DOWNLOAD_BUFFER_SIZE];
    loop {
        if cancel.is_some_and(CancelToken::is_cancelled) {
            return Err(DownloadError::Cancelled(url.to_string()));
        }
        let n = match reader.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => n,
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e.into()),
        };
        out.write_all(&buf[..n])?;
        if let Some(h) = hasher.as_mut() {
            h.update(&buf[..n]);
        }
        *copied += n as i64;
        if let Some(t) = tracker {
            t.add_bytes(n as i64);
        }
        notify_download_progress(scope, Some(dest), *copied, len, tracker);
    }
    out.flush()?;
    Ok(hasher.map(StreamHasher::finish_hex))
}

/// Moves a finished temp file over `dest` atomically (POSIX `rename`, Windows
/// `MoveFileExW(MOVEFILE_REPLACE_EXISTING)`); across devices, copy then remove.
pub(crate) async fn publish(tmp: &Path, dest: &Path) -> io::Result<()> {
    match tokio::fs::rename(tmp, dest).await {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == io::ErrorKind::CrossesDevices => {
            tokio::fs::copy(tmp, dest).await?;
            let _ = tokio::fs::remove_file(tmp).await;
            Ok(())
        }
        Err(e) => Err(e),
    }
}

/// Fetches `src` into a string: one attempt, no retry, UTF-8 (lossy). Follows redirects like
/// the download client. Local sources are read from disk.
pub async fn download_string(src: &Source, accept: Option<&str>) -> Result<String, DownloadError> {
    match src {
        Source::Http(_) => download_string_with(&clients()?.download, src, accept).await,
        _ => {
            let src_c = src.clone();
            let bytes = tokio::task::spawn_blocking(move || -> Result<Vec<u8>, DownloadError> {
                let (mut r, _) = open_local(&src_c)?;
                let mut v = Vec::new();
                r.read_to_end(&mut v)?;
                Ok(v)
            })
            .await
            .map_err(|e| DownloadError::Io(io::Error::other(e.to_string())))??;
            Ok(String::from_utf8_lossy(&bytes).into_owned())
        }
    }
}

/// [`download_string`] from a URL string.
pub async fn download_string_url(src: &str, accept: Option<&str>) -> Result<String, DownloadError> {
    download_string(&Source::parse(src)?, accept).await
}

pub(crate) async fn download_string_with(
    client: &reqwest::Client,
    src: &Source,
    accept: Option<&str>,
) -> Result<String, DownloadError> {
    let Source::Http(url) = src else {
        return Err(DownloadError::InvalidSource(src.to_string()));
    };
    let mut req = client.get(url.clone());
    if let Some(a) = accept {
        req = req.header(reqwest::header::ACCEPT, a);
    }
    let resp = req
        .send()
        .await
        .map_err(|e| DownloadError::request(url.as_str(), e))?;
    let status = resp.status().as_u16();
    if status >= 400 {
        return Err(DownloadError::Http {
            status,
            url: url.to_string(),
        });
    }
    let bytes = resp
        .bytes()
        .await
        .map_err(|e| DownloadError::request(url.as_str(), e))?;
    note_network_success();
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}
