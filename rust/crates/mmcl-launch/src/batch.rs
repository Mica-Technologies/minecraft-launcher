// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Batches of managed-file jobs on the shared bounded download pool, with Java's
//! `DownloadExecutor.awaitAll(futures, 30 min)` semantics: the first failure, a timeout or the
//! caller's cancel stops the rest.

use std::io;
use std::path::Path;
use std::time::Duration;

use futures::future::BoxFuture;
use mmcl_manifest::ManifestFile;
use mmcl_net::{AwaitError, CancelToken, DownloadPool, await_all};

use crate::error::LaunchError;
use crate::lerr;

/// Library, asset and loader batches wait at most this long.
pub(crate) const BATCH_TIMEOUT: Duration = Duration::from_secs(30 * 60);

/// The keys a batch failure maps to.
#[derive(Clone, Copy, Debug)]
pub(crate) struct BatchKeys {
    pub timeout: &'static str,
    pub interrupted: &'static str,
    /// Wraps a failure's message; `None` passes the job's error through unchanged.
    pub failed: Option<&'static str>,
    /// Pass modpack-layer errors (managed-file failures) through unwrapped (Java re-raised a
    /// `ModpackException` cause as is).
    pub pass_modpack: bool,
}

/// Runs every job on `pool` and returns the results in submission order.
pub(crate) async fn run_batch<T: Send + 'static>(
    pool: &DownloadPool,
    jobs: Vec<BoxFuture<'static, Result<T, LaunchError>>>,
    timeout: Duration,
    cancel: &CancelToken,
    keys: BatchKeys,
) -> Result<Vec<T>, LaunchError> {
    if cancel.is_cancelled() {
        return Err(LaunchError::Cancelled);
    }
    let handles = jobs
        .into_iter()
        .map(|job| pool.submit(move |_token| job))
        .collect();
    await_all(handles, timeout, Some(cancel))
        .await
        .map_err(|e| map_await(e, keys, cancel))
}

fn map_await(e: AwaitError<LaunchError>, keys: BatchKeys, cancel: &CancelToken) -> LaunchError {
    match e {
        AwaitError::Timeout => LaunchError::Localized {
            key: keys.timeout,
            args: Vec::new(),
        },
        AwaitError::Cancelled if cancel.is_cancelled() => LaunchError::Cancelled,
        AwaitError::Cancelled => LaunchError::Localized {
            key: keys.interrupted,
            args: Vec::new(),
        },
        AwaitError::Failed(e) if e.is_cancelled() => e,
        AwaitError::Failed(e @ LaunchError::Modpack(_)) if keys.pass_modpack => e,
        AwaitError::Failed(e) => match keys.failed {
            None => e,
            Some(key) => LaunchError::Step {
                key,
                message: e.to_string(),
                source: Some(Box::new(e)),
            },
        },
        AwaitError::Panicked(msg) => match keys.failed {
            Some(key) => LaunchError::Localized {
                key,
                args: vec![msg],
            },
            None => lerr!("launch.error.stepFailed"),
        },
    }
}

/// A manifest file entry for a launcher-built download: `remote`, `local`, optional SHA-1,
/// wanted on both sides.
pub(crate) fn spec(remote: &str, local: &str, sha1: Option<&str>) -> ManifestFile {
    ManifestFile {
        remote: Some(remote.to_string()),
        local: Some(local.to_string()),
        sha1: sha1
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_string),
        client_req: true,
        server_req: true,
        ..Default::default()
    }
}

/// Runs blocking work (hashing, zip, copies) off the async threads.
pub(crate) async fn blocking<T: Send + 'static>(
    f: impl FnOnce() -> T + Send + 'static,
) -> io::Result<T> {
    match tokio::task::spawn_blocking(f).await {
        Ok(v) => Ok(v),
        Err(e) if e.is_panic() => std::panic::resume_unwind(e.into_panic()),
        Err(_) => Err(io::Error::other("blocking task cancelled")),
    }
}

/// The last path component as text.
pub(crate) fn file_name_of(p: &Path) -> String {
    p.file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default()
}

/// `base` + separator + `rel` with `/` turned into the platform separator (Java's
/// `buildFilePath` over a manifest path).
pub(crate) fn join_rel(base: &Path, rel: &str) -> std::path::PathBuf {
    let rel = if cfg!(windows) {
        rel.replace('/', "\\")
    } else {
        rel.to_string()
    };
    base.join(rel)
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEYS: BatchKeys = BatchKeys {
        timeout: "minecraft.error.libsTimedOut",
        interrupted: "minecraft.error.libsInterrupted",
        failed: Some("minecraft.error.libsFailed"),
        pass_modpack: false,
    };

    fn job(
        r: Result<u32, LaunchError>,
        delay_ms: u64,
    ) -> BoxFuture<'static, Result<u32, LaunchError>> {
        Box::pin(async move {
            tokio::time::sleep(Duration::from_millis(delay_ms)).await;
            r
        })
    }

    #[tokio::test]
    async fn results_keep_submission_order() {
        let pool = DownloadPool::with_size(4);
        let c = CancelToken::new();
        let out = run_batch(
            &pool,
            vec![job(Ok(1), 30), job(Ok(2), 0)],
            BATCH_TIMEOUT,
            &c,
            KEYS,
        )
        .await
        .unwrap();
        assert_eq!(out, vec![1, 2]);
    }

    #[tokio::test]
    async fn failure_is_wrapped_and_timeout_mapped() {
        let pool = DownloadPool::with_size(4);
        let c = CancelToken::new();
        let e = run_batch(
            &pool,
            vec![
                job(Err(LaunchError::Io(io::Error::other("boom"))), 0),
                job(Ok(2), 5_000),
            ],
            BATCH_TIMEOUT,
            &c,
            KEYS,
        )
        .await
        .unwrap_err();
        assert_eq!(e.key(), "minecraft.error.libsFailed");
        assert!(e.to_string().contains("boom"));
        let e = run_batch(
            &pool,
            vec![job(Ok(1), 5_000)],
            Duration::from_millis(20),
            &c,
            KEYS,
        )
        .await
        .unwrap_err();
        assert_eq!(e.key(), "minecraft.error.libsTimedOut");
        let pass = BatchKeys {
            failed: None,
            ..KEYS
        };
        let e = run_batch(
            &pool,
            vec![job(Err(lerr!("forge.error.libsTimedOut")), 0)],
            BATCH_TIMEOUT,
            &c,
            pass,
        )
        .await
        .unwrap_err();
        assert_eq!(e.key(), "forge.error.libsTimedOut");
    }

    #[tokio::test]
    async fn caller_cancel_is_cancelled() {
        let pool = DownloadPool::with_size(4);
        let c = CancelToken::new();
        let c2 = c.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(20)).await;
            c2.cancel();
        });
        let e = run_batch(&pool, vec![job(Ok(1), 5_000)], BATCH_TIMEOUT, &c, KEYS)
            .await
            .unwrap_err();
        assert!(e.is_cancelled());
    }
}
