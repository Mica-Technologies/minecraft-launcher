// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `DownloadPool` (Java `DownloadExecutor`): the one process-wide bound on concurrent
//! downloads, shared by every pack and launch.
//!
//! A `tokio` semaphore of `max(16, 2 * cores)` permits. [`DownloadPool::submit`] spawns a task
//! that waits for a permit, runs with the submitter's [`DownloadScope`] (so progress reaches the
//! right launch) and receives a [`CancelToken`]. **Tasks must never submit back into the pool
//! and wait on it** (that can starve it); the launch orchestration has its own concurrency.
//!
//! [`await_all`] waits for a set of handles against one deadline; on a timeout, a failure (the
//! first wins), a panic or the caller's cancel it cancels **every** handle before returning.

use std::future::Future;
use std::sync::{Arc, LazyLock};
use std::time::Duration;

use tokio::sync::{OwnedSemaphorePermit, Semaphore};
use tokio::task::JoinHandle;

use crate::cancel::CancelToken;
use crate::error::AwaitError;
use crate::listeners::{current_scope, with_scope};

/// `max(16, 2 * cores)`.
pub fn default_pool_size() -> usize {
    let cores = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4);
    (2 * cores).max(16)
}

/// A bounded set of concurrent download slots.
#[derive(Clone, Debug)]
pub struct DownloadPool {
    sem: Arc<Semaphore>,
    size: usize,
}

static GLOBAL: LazyLock<DownloadPool> =
    LazyLock::new(|| DownloadPool::with_size(default_pool_size()));

/// A runtime for callers that have none (the engine normally owns one). Created lazily.
static FALLBACK_RUNTIME: LazyLock<tokio::runtime::Runtime> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .thread_name("mmcl-download")
        .enable_all()
        .build()
        .unwrap_or_else(|e| {
            mmcl_log::error_silent(&mmcl_i18n::tf!("log.network.runtimeFailed", e.to_string()));
            tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .unwrap_or_else(|e| panic!("no tokio runtime can be created: {e}"))
        })
});

/// The current runtime, or the crate's fallback when called outside one.
pub fn runtime_handle() -> tokio::runtime::Handle {
    tokio::runtime::Handle::try_current().unwrap_or_else(|_| FALLBACK_RUNTIME.handle().clone())
}

impl DownloadPool {
    /// The process-wide pool.
    pub fn global() -> &'static DownloadPool {
        &GLOBAL
    }

    /// A separate pool of `size` slots (at least one).
    pub fn with_size(size: usize) -> DownloadPool {
        let size = size.max(1);
        DownloadPool {
            sem: Arc::new(Semaphore::new(size)),
            size,
        }
    }

    /// Number of slots.
    pub fn size(&self) -> usize {
        self.size
    }

    /// Free slots right now.
    pub fn available(&self) -> usize {
        self.sem.available_permits()
    }

    /// Waits for a slot, for callers that run their own tasks (`JoinSet` + permit).
    pub async fn acquire(&self) -> OwnedSemaphorePermit {
        // The semaphore is never closed, so acquiring cannot fail; keep a safe path anyway.
        loop {
            if let Ok(p) = self.sem.clone().acquire_owned().await {
                return p;
            }
            tokio::task::yield_now().await;
        }
    }

    /// Runs `f(token)` on a slot. The task inherits the submitter's download scope.
    pub fn submit<F, Fut, T, E>(&self, f: F) -> DownloadHandle<T, E>
    where
        F: FnOnce(CancelToken) -> Fut + Send + 'static,
        Fut: Future<Output = Result<T, E>> + Send + 'static,
        T: Send + 'static,
        E: Send + 'static,
    {
        let token = CancelToken::new();
        let task_token = token.clone();
        let scope = current_scope();
        let pool = self.clone();
        let join = runtime_handle().spawn(with_scope(scope, async move {
            let _permit = tokio::select! {
                p = pool.acquire() => p,
                _ = task_token.cancelled() => return TaskOutcome::Cancelled,
            };
            match f(task_token).await {
                Ok(v) => TaskOutcome::Done(v),
                Err(e) => TaskOutcome::Failed(e),
            }
        }));
        DownloadHandle {
            join: Some(join),
            cancel: token,
        }
    }
}

enum TaskOutcome<T, E> {
    Done(T),
    Failed(E),
    Cancelled,
}

/// One submitted task.
pub struct DownloadHandle<T, E> {
    join: Option<JoinHandle<TaskOutcome<T, E>>>,
    cancel: CancelToken,
}

impl<T, E> DownloadHandle<T, E> {
    /// The task's token.
    pub fn token(&self) -> &CancelToken {
        &self.cancel
    }

    /// Cancels the task: sets its token and aborts it at its next await.
    pub fn cancel(&self) {
        self.cancel.cancel();
        if let Some(j) = &self.join {
            j.abort();
        }
    }

    /// `true` once the task has finished (or was never started).
    pub fn is_finished(&self) -> bool {
        self.join.as_ref().is_none_or(JoinHandle::is_finished)
    }

    /// Waits for the task's result.
    pub async fn join(mut self) -> Result<T, AwaitError<E>> {
        match self.join.take() {
            Some(j) => map_join(j.await),
            None => Err(AwaitError::Cancelled),
        }
    }
}

fn map_join<T, E>(
    r: Result<TaskOutcome<T, E>, tokio::task::JoinError>,
) -> Result<T, AwaitError<E>> {
    match r {
        Ok(TaskOutcome::Done(v)) => Ok(v),
        Ok(TaskOutcome::Failed(e)) => Err(AwaitError::Failed(e)),
        Ok(TaskOutcome::Cancelled) => Err(AwaitError::Cancelled),
        Err(e) if e.is_panic() => {
            let p = e.into_panic();
            let msg = p
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| p.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "panic".to_string());
            Err(AwaitError::Panicked(msg))
        }
        Err(_) => Err(AwaitError::Cancelled),
    }
}

/// Waits for every handle within `timeout` overall. On a timeout, a task failure (first error
/// wins), a panic or `caller` being cancelled, every handle is cancelled first.
pub async fn await_all<T, E>(
    mut handles: Vec<DownloadHandle<T, E>>,
    timeout: Duration,
    caller: Option<&CancelToken>,
) -> Result<Vec<T>, AwaitError<E>> {
    use futures::StreamExt;
    use futures::stream::FuturesUnordered;

    let deadline = tokio::time::Instant::now() + timeout;
    let n = handles.len();
    let mut results: Vec<Option<T>> = (0..n).map(|_| None).collect();
    // The tokens and abort handles stay here so every task can be cancelled whatever state the
    // wait is in.
    let tokens: Vec<CancelToken> = handles.iter().map(|h| h.cancel.clone()).collect();
    if handles.iter().any(|h| h.join.is_none()) {
        handles.iter().for_each(DownloadHandle::cancel);
        return Err(AwaitError::Cancelled);
    }
    let joins: Vec<JoinHandle<TaskOutcome<T, E>>> =
        handles.iter_mut().filter_map(|h| h.join.take()).collect();
    let aborts: Vec<tokio::task::AbortHandle> =
        joins.iter().map(JoinHandle::abort_handle).collect();
    // Results are collected as tasks finish (not in order), so a failure in a later handle
    // cancels its siblings at once instead of after every earlier download completes.
    let mut pending: FuturesUnordered<_> = joins
        .into_iter()
        .enumerate()
        .map(|(i, j)| async move { (i, j.await) })
        .collect();
    let fail = |e: AwaitError<E>| {
        cancel_tokens(&tokens, &aborts);
        Err(e)
    };
    if n > 0 && tokio::time::Instant::now() >= deadline {
        return fail(AwaitError::Timeout);
    }
    loop {
        let next = tokio::time::timeout_at(deadline, pending.next());
        let waited = match caller {
            Some(c) => tokio::select! {
                biased;
                _ = c.cancelled() => None,
                r = next => Some(r),
            },
            None => Some(next.await),
        };
        match waited {
            None => return fail(AwaitError::Cancelled),
            Some(Err(_elapsed)) => return fail(AwaitError::Timeout),
            Some(Ok(None)) => break,
            Some(Ok(Some((i, r)))) => match map_join(r) {
                Ok(v) => results[i] = Some(v),
                Err(e) => return fail(e),
            },
        }
    }
    Ok(results.into_iter().flatten().collect())
}

fn cancel_tokens(tokens: &[CancelToken], aborts: &[tokio::task::AbortHandle]) {
    tokens.iter().for_each(CancelToken::cancel);
    aborts.iter().for_each(tokio::task::AbortHandle::abort);
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, Ordering};

    // DownloadExecutorTest
    #[tokio::test]
    async fn await_all_returns_when_every_task_completes() {
        let pool = DownloadPool::with_size(4);
        let hs: Vec<DownloadHandle<i32, String>> = (1..=3)
            .map(|n| pool.submit(move |_| async move { Ok(n) }))
            .collect();
        assert_eq!(
            await_all(hs, Duration::from_secs(5), None).await.unwrap(),
            vec![1, 2, 3]
        );
    }

    #[tokio::test]
    async fn await_all_propagates_failure_and_cancels_siblings() {
        let pool = DownloadPool::with_size(4);
        let sibling_cancelled = Arc::new(AtomicBool::new(false));
        let flag = sibling_cancelled.clone();
        let blocking: DownloadHandle<i32, String> = pool.submit(move |tok| async move {
            struct OnDrop(Arc<AtomicBool>);
            impl Drop for OnDrop {
                fn drop(&mut self) {
                    self.0.store(true, Ordering::SeqCst);
                }
            }
            let _d = OnDrop(flag);
            tok.sleep(Duration::from_secs(60)).await;
            Ok(0)
        });
        let failing: DownloadHandle<i32, String> =
            pool.submit(|_| async { Err::<i32, _>("boom".to_string()) });
        let token = blocking.token().clone();
        let r = await_all(vec![failing, blocking], Duration::from_secs(5), None).await;
        assert!(matches!(r, Err(AwaitError::Failed(ref e)) if e == "boom"));
        assert!(token.is_cancelled());
        tokio::time::timeout(Duration::from_secs(5), async {
            while !sibling_cancelled.load(Ordering::SeqCst) {
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        })
        .await
        .expect("the pending sibling must be cancelled");
    }

    #[tokio::test]
    async fn await_all_times_out_and_cancels_pending() {
        let pool = DownloadPool::with_size(2);
        let slow: DownloadHandle<i32, String> = pool.submit(|tok| async move {
            tok.sleep(Duration::from_secs(60)).await;
            Ok(0)
        });
        let token = slow.token().clone();
        let r = await_all(vec![slow], Duration::from_millis(100), None).await;
        assert!(matches!(r, Err(AwaitError::Timeout)));
        assert!(token.is_cancelled());
    }

    #[tokio::test]
    async fn the_pool_bounds_concurrency_and_carries_the_scope() {
        let pool = DownloadPool::with_size(2);
        let scope = crate::listeners::DownloadScope::new();
        let live = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let peak = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let hs: Vec<DownloadHandle<Option<crate::listeners::DownloadScope>, String>> =
            with_scope(Some(scope), async {
                (0..6)
                    .map(|_| {
                        let (live, peak) = (live.clone(), peak.clone());
                        pool.submit(move |_| async move {
                            let n = live.fetch_add(1, Ordering::SeqCst) + 1;
                            peak.fetch_max(n, Ordering::SeqCst);
                            tokio::time::sleep(Duration::from_millis(20)).await;
                            live.fetch_sub(1, Ordering::SeqCst);
                            Ok(current_scope())
                        })
                    })
                    .collect()
            })
            .await;
        let seen = await_all(hs, Duration::from_secs(10), None).await.unwrap();
        assert!(seen.iter().all(|s| *s == Some(scope)));
        assert!(peak.load(Ordering::SeqCst) <= 2);
        assert!(default_pool_size() >= 16);
    }

    #[tokio::test]
    async fn a_late_failure_cancels_an_earlier_slow_sibling_at_once() {
        let pool = DownloadPool::with_size(4);
        let slow: DownloadHandle<i32, String> = pool.submit(|tok| async move {
            tok.sleep(Duration::from_secs(60)).await;
            Ok(0)
        });
        let token = slow.token().clone();
        let failing: DownloadHandle<i32, String> =
            pool.submit(|_| async { Err::<i32, _>("late boom".to_string()) });
        let started = std::time::Instant::now();
        let r = await_all(vec![slow, failing], Duration::from_secs(30), None).await;
        assert!(matches!(r, Err(AwaitError::Failed(ref e)) if e == "late boom"));
        assert!(started.elapsed() < Duration::from_secs(10));
        assert!(token.is_cancelled());
    }

    #[tokio::test]
    async fn results_keep_submission_order_when_tasks_finish_out_of_order() {
        let pool = DownloadPool::with_size(4);
        let hs: Vec<DownloadHandle<u64, String>> = [60u64, 5, 30]
            .into_iter()
            .map(|ms| {
                pool.submit(move |_| async move {
                    tokio::time::sleep(Duration::from_millis(ms)).await;
                    Ok(ms)
                })
            })
            .collect();
        assert_eq!(
            await_all(hs, Duration::from_secs(10), None).await.unwrap(),
            vec![60, 5, 30]
        );
    }

    #[tokio::test]
    async fn caller_cancel_cancels_everything() {
        let pool = DownloadPool::with_size(2);
        let slow: DownloadHandle<i32, String> = pool.submit(|tok| async move {
            tok.sleep(Duration::from_secs(60)).await;
            Ok(0)
        });
        let token = slow.token().clone();
        let caller = CancelToken::new();
        let c2 = caller.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(20)).await;
            c2.cancel();
        });
        let r = await_all(vec![slow], Duration::from_secs(30), Some(&caller)).await;
        assert!(matches!(r, Err(AwaitError::Cancelled)));
        assert!(token.is_cancelled());
    }
}
