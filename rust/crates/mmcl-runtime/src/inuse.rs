// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Which runtimes running games use, so nothing replaces or deletes a runtime under a game.
//!
//! A record is added with an exit future (the session crate's process-exit signal) and drops
//! itself when that future resolves, so this crate needs no process type of its own.

use std::collections::HashMap;
use std::future::Future;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

use parking_lot::Mutex;

#[derive(Debug)]
struct Record {
    id: u64,
    pid: u32,
    alive: Arc<AtomicBool>,
}

/// Running games per runtime component.
#[derive(Debug, Default)]
pub struct InUseRegistry {
    map: Arc<Mutex<HashMap<String, Vec<Record>>>>,
    next: AtomicU64,
}

impl InUseRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    /// Records that the game `pid` runs on `component` until `exited` resolves. An empty
    /// component is ignored. Needs no particular executor: inside a tokio runtime the watcher
    /// is a task, outside one it is a small thread.
    pub fn mark(
        &self,
        component: &str,
        pid: u32,
        exited: impl Future<Output = ()> + Send + 'static,
    ) {
        if component.is_empty() {
            return;
        }
        let id = self.next.fetch_add(1, Ordering::Relaxed);
        let alive = Arc::new(AtomicBool::new(true));
        self.map
            .lock()
            .entry(component.to_string())
            .or_default()
            .push(Record {
                id,
                pid,
                alive: alive.clone(),
            });
        let map = Arc::downgrade(&self.map);
        let component = component.to_string();
        let watcher = async move {
            exited.await;
            alive.store(false, Ordering::SeqCst);
            if let Some(map) = map.upgrade() {
                let mut m = map.lock();
                if let Some(v) = m.get_mut(&component) {
                    v.retain(|r| r.id != id);
                    if v.is_empty() {
                        m.remove(&component);
                    }
                }
            }
        };
        match tokio::runtime::Handle::try_current() {
            Ok(h) => {
                h.spawn(watcher);
            }
            Err(_) => {
                std::thread::spawn(move || futures::executor::block_on(watcher));
            }
        }
    }

    /// `true` while any game recorded on `component` is still running.
    pub fn is_in_use(&self, component: &str) -> bool {
        let mut m = self.map.lock();
        let Some(v) = m.get_mut(component) else {
            return false;
        };
        v.retain(|r| r.alive.load(Ordering::SeqCst));
        let used = !v.is_empty();
        if !used {
            m.remove(component);
        }
        used
    }

    /// The process ids recorded on `component` (for diagnostics).
    pub fn pids(&self, component: &str) -> Vec<u32> {
        self.map
            .lock()
            .get(component)
            .map(|v| {
                v.iter()
                    .filter(|r| r.alive.load(Ordering::SeqCst))
                    .map(|r| r.pid)
                    .collect()
            })
            .unwrap_or_default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;
    use tokio::sync::oneshot;

    async fn settle(reg: &InUseRegistry, component: &str, want: bool) {
        for _ in 0..200 {
            if reg.is_in_use(component) == want {
                return;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        panic!("{component} never became in_use={want}");
    }

    #[tokio::test]
    async fn in_use_while_the_game_runs() {
        let reg = InUseRegistry::new();
        let (tx, rx) = oneshot::channel::<()>();
        reg.mark("rt", 1, async move {
            let _ = rx.await;
        });
        assert!(reg.is_in_use("rt"));
        assert_eq!(reg.pids("rt"), vec![1]);
        tx.send(()).unwrap();
        settle(&reg, "rt", false).await;
    }

    #[tokio::test]
    async fn two_games_keep_it_in_use_until_both_exit() {
        let reg = InUseRegistry::new();
        let (tx1, rx1) = oneshot::channel::<()>();
        let (tx2, rx2) = oneshot::channel::<()>();
        reg.mark("shared", 1, async move {
            let _ = rx1.await;
        });
        reg.mark("shared", 2, async move {
            let _ = rx2.await;
        });
        tx1.send(()).unwrap();
        tokio::time::sleep(Duration::from_millis(20)).await;
        assert!(reg.is_in_use("shared"));
        tx2.send(()).unwrap();
        settle(&reg, "shared", false).await;
    }

    #[test]
    fn works_without_a_tokio_runtime() {
        let reg = InUseRegistry::new();
        let (tx, rx) = oneshot::channel::<()>();
        reg.mark("plain", 7, async move {
            let _ = rx.await;
        });
        assert!(reg.is_in_use("plain"));
        tx.send(()).unwrap();
        for _ in 0..200 {
            if !reg.is_in_use("plain") {
                return;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        panic!("never released");
    }

    #[test]
    fn unknown_and_empty_components_are_not_in_use() {
        let reg = InUseRegistry::new();
        assert!(!reg.is_in_use("never-used"));
        reg.mark("", 1, std::future::pending());
        assert!(!reg.is_in_use(""));
    }
}
