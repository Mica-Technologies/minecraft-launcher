// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! [`EngineTask`]: a runtime-agnostic handle to a future running on the engine's tokio runtime.
//! GPUI's executor (or any other) can await it; dropping it does not cancel the work.

use std::future::Future;
use std::pin::Pin;
use std::task::{Context, Poll};

use futures::channel::oneshot;

/// The task was dropped before finishing (it panicked or the runtime shut down).
#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
#[error("the engine task ended without a result")]
pub struct TaskAborted;

/// A future running on the engine's runtime, awaitable from any executor.
#[must_use = "an EngineTask does nothing unless awaited (the work runs regardless)"]
pub struct EngineTask<T> {
    rx: oneshot::Receiver<T>,
}

impl<T> std::fmt::Debug for EngineTask<T> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("EngineTask").finish_non_exhaustive()
    }
}

impl<T: Send + 'static> EngineTask<T> {
    /// Spawns `fut` on `handle`.
    pub(crate) fn spawn<F>(handle: &tokio::runtime::Handle, fut: F) -> EngineTask<T>
    where
        F: Future<Output = T> + Send + 'static,
    {
        let (tx, rx) = oneshot::channel();
        handle.spawn(async move {
            let _ = tx.send(fut.await);
        });
        EngineTask { rx }
    }

    /// An already finished task.
    pub fn ready(value: T) -> EngineTask<T> {
        let (tx, rx) = oneshot::channel();
        let _ = tx.send(value);
        EngineTask { rx }
    }
}

impl<T> Future for EngineTask<T> {
    type Output = Result<T, TaskAborted>;

    fn poll(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Self::Output> {
        Pin::new(&mut self.rx)
            .poll(cx)
            .map(|r| r.map_err(|_| TaskAborted))
    }
}
