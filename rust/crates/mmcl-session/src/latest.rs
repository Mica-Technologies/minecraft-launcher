// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Runs updates in order on one executor and skips any update a newer one has already replaced
//! (Java `LatestUpdateRunner`).
//!
//! On an unordered pool a slow "show the game" (RGB samples the pack logo) once landed after a
//! quick "no game" and left the keyboard and Discord on a game that had already exited. Here
//! every update goes through one ordered executor and only the newest one runs.

use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

/// A unit of work handed to the executor.
pub type Task = Box<dyn FnOnce() + Send + 'static>;

/// Runs a task, in submission order.
pub type Executor = Arc<dyn Fn(Task) + Send + Sync>;

/// Latest-wins update runner.
pub struct LatestUpdateRunner {
    executor: Executor,
    generation: Arc<AtomicU64>,
}

impl LatestUpdateRunner {
    /// A runner on `executor`, which must run tasks one at a time in submission order.
    pub fn new(executor: Executor) -> Self {
        LatestUpdateRunner {
            executor,
            generation: Arc::new(AtomicU64::new(0)),
        }
    }

    /// A runner on its own thread named `thread_name` (the thread ends when the runner is
    /// dropped and its queue is drained).
    pub fn on_daemon_thread(thread_name: &str) -> Self {
        let (tx, rx) = std::sync::mpsc::channel::<Task>();
        let spawned = std::thread::Builder::new()
            .name(thread_name.to_string())
            .spawn(move || {
                while let Ok(task) = rx.recv() {
                    crate::listeners::run_isolated(task);
                }
            });
        if let Err(e) = spawned {
            tracing::warn!(
                "{}",
                mmcl_i18n::tf!("log.session.followerThreadFailed", e.to_string())
            );
        }
        let tx = parking_lot::Mutex::new(tx);
        Self::new(Arc::new(move |task: Task| {
            // A failed spawn leaves no receiver: the update is dropped, which only means the
            // surfaces do not follow the game.
            let _ = tx.lock().send(task);
        }))
    }

    /// Queues `update`; it runs only if no newer update was submitted before its turn.
    pub fn submit(&self, update: impl FnOnce() + Send + 'static) {
        let mine = self.generation.fetch_add(1, Ordering::SeqCst) + 1;
        let generation = Arc::clone(&self.generation);
        (self.executor)(Box::new(move || {
            if generation.load(Ordering::SeqCst) == mine {
                update();
            }
        }));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use parking_lot::Mutex;
    use std::collections::VecDeque;
    use std::sync::mpsc;
    use std::time::Duration;

    struct Manual {
        queue: Arc<Mutex<VecDeque<Task>>>,
        runner: LatestUpdateRunner,
        applied: Arc<Mutex<Vec<&'static str>>>,
    }

    impl Manual {
        fn new() -> Self {
            let queue: Arc<Mutex<VecDeque<Task>>> = Arc::default();
            let q = queue.clone();
            Manual {
                runner: LatestUpdateRunner::new(Arc::new(move |t| q.lock().push_back(t))),
                queue,
                applied: Arc::default(),
            }
        }

        fn submit(&self, what: &'static str) {
            let applied = self.applied.clone();
            self.runner.submit(move || applied.lock().push(what));
        }

        fn drain(&self) {
            loop {
                let next = self.queue.lock().pop_front();
                match next {
                    Some(t) => t(),
                    None => break,
                }
            }
        }
    }

    #[test]
    fn applies_updates_in_order_when_each_runs_before_the_next() {
        let m = Manual::new();
        m.submit("game");
        m.drain();
        m.submit("none");
        m.drain();
        assert_eq!(*m.applied.lock(), vec!["game", "none"]);
    }

    #[test]
    fn skips_an_update_already_replaced() {
        let m = Manual::new();
        m.submit("game");
        m.submit("none");
        m.drain();
        assert_eq!(*m.applied.lock(), vec!["none"]);
    }

    #[test]
    fn newest_update_lands_last_even_when_an_earlier_one_is_slow() {
        let real = LatestUpdateRunner::on_daemon_thread("test-follower");
        let seen: Arc<Mutex<Vec<&'static str>>> = Arc::default();
        let (started_tx, started_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel::<()>();
        let (done_tx, done_rx) = mpsc::channel();
        let s = seen.clone();
        real.submit(move || {
            started_tx.send(()).unwrap();
            let _ = release_rx.recv_timeout(Duration::from_secs(5));
            s.lock().push("game");
        });
        started_rx.recv_timeout(Duration::from_secs(5)).unwrap();
        let s = seen.clone();
        real.submit(move || {
            s.lock().push("none");
            done_tx.send(()).unwrap();
        });
        release_tx.send(()).unwrap();
        done_rx.recv_timeout(Duration::from_secs(5)).unwrap();
        assert_eq!(*seen.lock(), vec!["game", "none"]);
    }

    #[test]
    fn a_panicking_update_does_not_stop_the_thread() {
        let real = LatestUpdateRunner::on_daemon_thread("test-follower-panic");
        let (tx, rx) = mpsc::channel();
        real.submit(|| panic!("update failure"));
        // Let the panicking one run before the next replaces it.
        std::thread::sleep(Duration::from_millis(50));
        real.submit(move || tx.send(()).unwrap());
        rx.recv_timeout(Duration::from_secs(5)).unwrap();
    }
}
