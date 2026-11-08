// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Where account refreshes run. Production uses one serial tokio task (the Java single-thread
//! `mmcl-auth-refresh` executor): refreshes of every account run one after another, spaced by the
//! rate limit.

use std::panic::AssertUnwindSafe;

use futures::FutureExt;
use futures::future::BoxFuture;
use tokio::sync::mpsc;

/// Runs fire-and-forget tasks.
pub trait Spawn: Send + Sync {
    fn spawn(&self, task: BoxFuture<'static, ()>);
}

/// Runs every task strictly in order on one tokio task. A task that panics is logged and the
/// queue keeps going.
#[derive(Clone, Debug)]
pub struct SerialSpawner {
    tx: mpsc::UnboundedSender<BoxFuture<'static, ()>>,
}

impl SerialSpawner {
    /// Starts the queue on `handle`.
    pub fn new(handle: &tokio::runtime::Handle) -> SerialSpawner {
        let (tx, mut rx) = mpsc::unbounded_channel::<BoxFuture<'static, ()>>();
        handle.spawn(async move {
            while let Some(task) = rx.recv().await {
                if AssertUnwindSafe(task).catch_unwind().await.is_err() {
                    mmcl_log::warn_silent(&mmcl_i18n::tf!(
                        "log.systemUtil.taskPanicked",
                        "mmcl-auth-refresh"
                    ));
                }
            }
        });
        SerialSpawner { tx }
    }
}

impl Spawn for SerialSpawner {
    fn spawn(&self, task: BoxFuture<'static, ()>) {
        // A closed queue means the runtime is shutting down; dropping the task is correct.
        let _ = self.tx.send(task);
    }
}

/// Runs each task to completion on the calling thread. For tests and tools whose renewers
/// complete without a runtime; never use it with the network renewer.
#[derive(Clone, Copy, Debug, Default)]
pub struct InlineSpawner;

impl Spawn for InlineSpawner {
    fn spawn(&self, task: BoxFuture<'static, ()>) {
        futures::executor::block_on(task);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::time::Duration;

    #[tokio::test]
    async fn the_serial_queue_runs_in_order_and_survives_a_panic() {
        let s = SerialSpawner::new(&tokio::runtime::Handle::current());
        let log = Arc::new(parking_lot::Mutex::new(Vec::new()));
        let (done_tx, done_rx) = tokio::sync::oneshot::channel();
        for i in 0..3 {
            let log = log.clone();
            s.spawn(
                async move {
                    tokio::time::sleep(Duration::from_millis(5 * (3 - i))).await;
                    log.lock().push(i);
                }
                .boxed(),
            );
        }
        s.spawn(async { panic!("boom") }.boxed());
        s.spawn(
            async move {
                let _ = done_tx.send(());
            }
            .boxed(),
        );
        done_rx.await.unwrap();
        assert_eq!(*log.lock(), vec![0, 1, 2]);
    }
}
