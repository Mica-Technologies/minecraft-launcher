// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The background task pool (`SystemUtilities.spawnNewTask`, `runBounded`,
//! `shutdownBackgroundExecutor`) and the UI hand-off helpers (`FxAsyncTask`).
//!
//! The pool is a cached thread pool: it grows without bound, reuses idle workers, reaps a
//! worker after 60 s idle, and names workers `mica-bg-N` with a process-wide counter so names
//! stay unique across pool generations. **It is re-creatable**: the in-process restart loop
//! shuts it down between sessions, and the next [`spawn`] builds a fresh one. (A pool that
//! stayed shut once made every later submission fail and silently disabled image caching,
//! auth renewal and Play.)
//!
//! Rust has no daemon threads: the app's exit path must call `std::process::exit` rather than
//! rely on `main` returning while workers are blocked.

use std::any::Any;
use std::cell::Cell;
use std::collections::VecDeque;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc;
use std::sync::{Arc, OnceLock};
use std::thread::{self, ThreadId};
use std::time::{Duration, Instant};

use mmcl_i18n::{t, tf};
use parking_lot::{Condvar, Mutex};

use crate::startup_log::panic_message;

/// How long an idle worker waits for work before it exits.
pub const IDLE_TIMEOUT: Duration = Duration::from_secs(60);

type Job = Box<dyn FnOnce() + Send + 'static>;

#[derive(Default)]
struct PoolState {
    queue: VecDeque<Job>,
    /// Waiting workers not yet claimed by a queued job (waiting workers - queue length).
    idle: usize,
    /// Worker threads alive.
    live: usize,
    shutdown: bool,
}

#[derive(Default)]
struct Pool {
    state: Mutex<PoolState>,
    work: Condvar,
    drained: Condvar,
}

static CURRENT: Mutex<Option<Arc<Pool>>> = Mutex::new(None);
static WORKER_SEQ: AtomicU64 = AtomicU64::new(0);

thread_local! {
    static IS_WORKER: Cell<bool> = const { Cell::new(false) };
}

fn current_pool() -> Arc<Pool> {
    CURRENT
        .lock()
        .get_or_insert_with(|| Arc::new(Pool::default()))
        .clone()
}

/// `true` on a thread of the [`spawn`] pool. Code that shuts the pool down must not run on
/// one of its workers.
pub fn is_background_worker() -> bool {
    IS_WORKER.with(Cell::get)
}

fn run_job(job: Job) {
    if let Err(payload) = catch_unwind(AssertUnwindSafe(job)) {
        tracing::error!(
            "{}",
            tf!(
                "log.systemUtil.taskPanicked",
                panic_message(payload.as_ref())
            )
        );
    }
}

fn worker_loop(pool: Arc<Pool>, first: Job) {
    IS_WORKER.with(|w| w.set(true));
    run_job(first);
    let mut st = pool.state.lock();
    st.idle += 1;
    loop {
        if let Some(job) = st.queue.pop_front() {
            // Whoever queued it already took one unit of `idle`.
            drop(st);
            run_job(job);
            st = pool.state.lock();
            st.idle += 1;
            continue;
        }
        if st.shutdown {
            break;
        }
        let timed_out = pool.work.wait_for(&mut st, IDLE_TIMEOUT).timed_out();
        if timed_out && st.queue.is_empty() {
            break;
        }
    }
    st.idle -= 1;
    st.live -= 1;
    if st.live == 0 {
        pool.drained.notify_all();
    }
}

/// Runs `f` on the background pool. If the pool cannot take it (a worker thread could not
/// be started), `f` runs inline on the caller's thread and that is logged.
pub fn spawn(f: impl FnOnce() + Send + 'static) {
    let pool = current_pool();
    let mut st = pool.state.lock();
    if st.shutdown {
        // Raced with shutdown: the next call builds a fresh pool, this one runs inline.
        drop(st);
        tracing::warn!("{}", t!("log.systemUtil.bgExecutorRejected"));
        run_job(Box::new(f));
        return;
    }
    if st.idle > 0 {
        st.idle -= 1;
        st.queue.push_back(Box::new(f));
        drop(st);
        pool.work.notify_one();
        return;
    }
    st.live += 1;
    drop(st);
    let n = WORKER_SEQ.fetch_add(1, Ordering::Relaxed) + 1;
    // The job travels through a slot so a failed spawn can hand it back.
    let slot: Arc<Mutex<Option<Job>>> = Arc::new(Mutex::new(Some(Box::new(f))));
    let worker_slot = slot.clone();
    let worker_pool = pool.clone();
    let spawned = thread::Builder::new()
        .name(format!("mica-bg-{n}"))
        .spawn(move || {
            if let Some(job) = worker_slot.lock().take() {
                worker_loop(worker_pool, job);
            }
        });
    if spawned.is_err() {
        {
            let mut st = pool.state.lock();
            st.live -= 1;
            if st.live == 0 {
                pool.drained.notify_all();
            }
        }
        tracing::warn!("{}", t!("log.systemUtil.bgExecutorRejected"));
        if let Some(job) = slot.lock().take() {
            run_job(job);
        }
    }
}

/// Stops the current pool: it takes no new work (the next [`spawn`] creates a new pool),
/// queued tasks still run, and this waits up to `wait` for the workers to finish. Workers
/// still busy after that are abandoned (logged); Rust cannot interrupt them.
pub fn shutdown(wait: Duration) {
    let Some(pool) = CURRENT.lock().take() else {
        return;
    };
    let deadline = Instant::now() + wait;
    let mut st = pool.state.lock();
    st.shutdown = true;
    pool.work.notify_all();
    while st.live > 0 {
        if pool.drained.wait_until(&mut st, deadline).timed_out() {
            break;
        }
    }
    if st.live > 0 {
        tracing::warn!(
            "{}",
            tf!(
                "log.systemUtil.bgExecutorNoDrain",
                wait.as_millis().to_string()
            )
        );
    }
}

/// Runs `step` on a fresh thread `mmcl-bounded-<name>` and waits at most `timeout` (at least
/// 1 ms) for it. Returns whether it finished. A panic counts as finished and its message is
/// passed to `on_failure`. For shutdown steps that can hang (RGB SDKs, Discord, MCP).
pub fn run_bounded(
    name: &str,
    step: impl FnOnce() + Send + 'static,
    timeout: Duration,
    on_failure: Option<Box<dyn FnOnce(String) + Send>>,
) -> bool {
    let (tx, rx) = mpsc::channel::<()>();
    let spawned = thread::Builder::new()
        .name(format!("mmcl-bounded-{name}"))
        .spawn(move || {
            if let Err(payload) = catch_unwind(AssertUnwindSafe(step))
                && let Some(cb) = on_failure
            {
                cb(panic_message(payload.as_ref()));
            }
            let _ = tx.send(());
        });
    if spawned.is_err() {
        return false;
    }
    rx.recv_timeout(timeout.max(Duration::from_millis(1)))
        .is_ok()
}

// ---- UI hand-off -------------------------------------------------------------------------

/// Posts closures to the UI thread. The app crate implements it for GPUI (`Cx` =
/// `gpui::App`) with a channel drained by a foreground task; this crate stays UI-agnostic.
pub trait UiDispatcher<Cx: ?Sized>: Send + Sync {
    /// Runs `f` on the UI thread, later.
    fn run(&self, f: Box<dyn FnOnce(&mut Cx) + Send>);
}

static UI_THREAD: OnceLock<ThreadId> = OnceLock::new();

/// Records the calling thread as the UI thread (call once from the UI's main thread).
pub fn mark_ui_thread() {
    let _ = UI_THREAD.set(thread::current().id());
}

/// `true` on the thread recorded by [`mark_ui_thread`].
pub fn is_ui_thread() -> bool {
    UI_THREAD.get() == Some(&thread::current().id())
}

/// Runs `f` on the UI thread and blocks the calling worker until it returns (for "ask the
/// user, then continue" flows). `None` if the UI dropped the request. **Never call it on the
/// UI thread**: it would deadlock (checked in debug builds).
pub fn run_on_ui_and_wait<Cx: ?Sized + 'static, R: Send + 'static>(
    ui: &dyn UiDispatcher<Cx>,
    f: impl FnOnce(&mut Cx) -> R + Send + 'static,
) -> Option<R> {
    debug_assert!(
        !is_ui_thread(),
        "run_on_ui_and_wait called on the UI thread"
    );
    let (tx, rx) = mpsc::sync_channel(1);
    ui.run(Box::new(move |cx| {
        let _ = tx.send(f(cx));
    }));
    rx.recv().ok()
}

fn describe_failure(payload: Box<dyn Any + Send>) -> String {
    panic_message(payload.as_ref())
}

fn log_task_failure(error: &str) {
    let backtrace = std::backtrace::Backtrace::capture();
    tracing::error!(
        "{}\n{}",
        tf!("log.fxAsyncTask.taskFailed", error),
        backtrace
    );
}

/// `FxAsyncTask.run`: runs `background` on the pool, then exactly one of `on_success` (with
/// its value) or `on_error` (with the error text) on the UI thread. An `Err` or a panic is
/// logged (`log.fxAsyncTask.taskFailed`) before `on_error` runs.
pub fn run_async<Cx, T, E>(
    ui: Arc<dyn UiDispatcher<Cx>>,
    background: impl FnOnce() -> Result<T, E> + Send + 'static,
    on_success: impl FnOnce(&mut Cx, T) + Send + 'static,
    on_error: impl FnOnce(&mut Cx, String) + Send + 'static,
) where
    Cx: ?Sized + 'static,
    T: Send + 'static,
    E: std::fmt::Display + 'static,
{
    spawn(move || {
        let outcome = match catch_unwind(AssertUnwindSafe(background)) {
            Ok(Ok(v)) => Ok(v),
            Ok(Err(e)) => Err(e.to_string()),
            Err(p) => Err(describe_failure(p)),
        };
        match outcome {
            Ok(v) => ui.run(Box::new(move |cx| on_success(cx, v))),
            Err(msg) => {
                log_task_failure(&msg);
                ui.run(Box::new(move |cx| on_error(cx, msg)));
            }
        }
    });
}

/// `FxAsyncTask.runWithFinally`: runs `background` on the pool (logging an `Err` or panic),
/// then `finally_ui` on the UI thread whatever happened.
pub fn run_with_finally<Cx, E>(
    ui: Arc<dyn UiDispatcher<Cx>>,
    background: impl FnOnce() -> Result<(), E> + Send + 'static,
    finally_ui: impl FnOnce(&mut Cx) + Send + 'static,
) where
    Cx: ?Sized + 'static,
    E: std::fmt::Display + 'static,
{
    spawn(move || {
        match catch_unwind(AssertUnwindSafe(background)) {
            Ok(Ok(())) => {}
            Ok(Err(e)) => log_task_failure(&e.to_string()),
            Err(p) => log_task_failure(&describe_failure(p)),
        }
        ui.run(Box::new(finally_ui));
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, AtomicUsize};

    /// The pool is process-global; tests that shut it down must not overlap the others.
    static POOL_TESTS: Mutex<()> = Mutex::new(());

    // ---- SystemUtilitiesShutdownHelpersTest ----

    #[test]
    fn run_bounded_reports_a_step_that_finishes() {
        let ran = Arc::new(AtomicBool::new(false));
        let r = ran.clone();
        assert!(run_bounded(
            "quick",
            move || r.store(true, Ordering::SeqCst),
            Duration::from_secs(2),
            None
        ));
        assert!(ran.load(Ordering::SeqCst));
    }

    #[test]
    fn run_bounded_gives_up_on_a_hung_step_within_the_bound() {
        let (release, wait) = mpsc::channel::<()>();
        let start = Instant::now();
        let finished = run_bounded(
            "hung",
            move || {
                let _ = wait.recv();
            },
            Duration::from_millis(200),
            None,
        );
        assert!(!finished);
        assert!(start.elapsed() < Duration::from_secs(2));
        drop(release);
    }

    #[test]
    fn run_bounded_passes_a_panicking_steps_failure_on_and_counts_it_as_finished() {
        let seen = Arc::new(Mutex::new(None));
        let s = seen.clone();
        let finished = run_bounded(
            "throws",
            || panic!("boom"),
            Duration::from_secs(2),
            Some(Box::new(move |m| *s.lock() = Some(m))),
        );
        assert!(finished);
        assert_eq!(seen.lock().as_deref(), Some("boom"));
    }

    #[test]
    fn only_pool_workers_count_as_background_workers() {
        let _g = POOL_TESTS.lock();
        assert!(!is_background_worker());
        let (tx, rx) = mpsc::channel();
        spawn(move || {
            let _ = tx.send((
                is_background_worker(),
                thread::current().name().map(str::to_string),
            ));
        });
        let (on_worker, name) = rx.recv_timeout(Duration::from_secs(5)).unwrap();
        assert!(on_worker);
        assert!(name.unwrap().starts_with("mica-bg-"));
    }

    // ---- pool behaviour ----

    #[test]
    fn many_tasks_all_run_and_workers_are_reused() {
        let _g = POOL_TESTS.lock();
        let done = Arc::new(AtomicUsize::new(0));
        let (tx, rx) = mpsc::channel();
        for _ in 0..50 {
            let d = done.clone();
            let tx = tx.clone();
            spawn(move || {
                d.fetch_add(1, Ordering::SeqCst);
                let _ = tx.send(());
            });
        }
        for _ in 0..50 {
            rx.recv_timeout(Duration::from_secs(5)).unwrap();
        }
        assert_eq!(done.load(Ordering::SeqCst), 50);
    }

    #[test]
    fn a_panicking_task_does_not_kill_the_pool() {
        let _g = POOL_TESTS.lock();
        spawn(|| panic!("task failure"));
        let (tx, rx) = mpsc::channel();
        spawn(move || {
            let _ = tx.send(());
        });
        rx.recv_timeout(Duration::from_secs(5)).unwrap();
    }

    #[test]
    fn pool_is_recreated_after_shutdown() {
        let _g = POOL_TESTS.lock();
        let (tx, rx) = mpsc::channel();
        let t1 = tx.clone();
        spawn(move || {
            let _ = t1.send(thread::current().name().unwrap().to_string());
        });
        let first = rx.recv_timeout(Duration::from_secs(5)).unwrap();
        shutdown(Duration::from_secs(5));
        spawn(move || {
            let _ = tx.send(thread::current().name().unwrap().to_string());
        });
        let second = rx.recv_timeout(Duration::from_secs(5)).unwrap();
        assert!(first.starts_with("mica-bg-") && second.starts_with("mica-bg-"));
        assert_ne!(first, second, "a new generation gets new worker names");
    }

    #[test]
    fn shutdown_lets_queued_work_finish_and_gives_up_on_hung_workers() {
        let _g = POOL_TESTS.lock();
        let (release, hold) = mpsc::channel::<()>();
        spawn(move || {
            let _ = hold.recv();
        });
        let start = Instant::now();
        shutdown(Duration::from_millis(150));
        assert!(start.elapsed() < Duration::from_secs(2));
        drop(release);
        // Shutting down again with no pool is a no-op.
        shutdown(Duration::from_millis(1));
    }

    // ---- UI hand-off ----

    struct FakeUi {
        cx: Mutex<Vec<String>>,
    }

    impl UiDispatcher<Vec<String>> for FakeUi {
        fn run(&self, f: Box<dyn FnOnce(&mut Vec<String>) + Send>) {
            f(&mut self.cx.lock());
        }
    }

    fn fake_ui() -> Arc<FakeUi> {
        Arc::new(FakeUi {
            cx: Mutex::new(Vec::new()),
        })
    }

    fn wait_for(ui: &FakeUi, n: usize) -> Vec<String> {
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let v = ui.cx.lock().clone();
            if v.len() >= n || Instant::now() > deadline {
                return v;
            }
            thread::sleep(Duration::from_millis(5));
        }
    }

    #[test]
    fn run_async_dispatches_exactly_one_outcome() {
        let _g = POOL_TESTS.lock();
        let ui = fake_ui();
        let dyn_ui: Arc<dyn UiDispatcher<Vec<String>>> = ui.clone();
        run_async(
            dyn_ui.clone(),
            || Ok::<_, String>(7),
            |cx, v| cx.push(format!("ok {v}")),
            |cx, e| cx.push(format!("err {e}")),
        );
        run_async(
            dyn_ui.clone(),
            || Err::<i32, _>("bad".to_string()),
            |cx, v| cx.push(format!("ok {v}")),
            |cx, e| cx.push(format!("err {e}")),
        );
        run_async(
            dyn_ui.clone(),
            || -> Result<i32, String> { panic!("exploded") },
            |cx, v| cx.push(format!("ok {v}")),
            |cx, e| cx.push(format!("err {e}")),
        );
        run_with_finally(dyn_ui, || Err::<(), _>("x"), |cx| cx.push("finally".into()));
        let mut got = wait_for(&ui, 4);
        got.sort();
        assert_eq!(got, ["err bad", "err exploded", "finally", "ok 7"]);
    }

    #[test]
    fn run_on_ui_and_wait_returns_the_value() {
        let ui = fake_ui();
        assert_eq!(
            run_on_ui_and_wait(ui.as_ref(), |cx: &mut Vec<String>| cx.len() + 1),
            Some(1)
        );
    }
}
