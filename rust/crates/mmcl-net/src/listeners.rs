// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Download scopes and the scoped retry / progress listeners.
//!
//! Several launches run at once and each owns a [`DownloadScope`]. A download reports to the
//! listeners of its own scope (and to unscoped listeners); an unscoped download reports to
//! every listener. The listeners are a list, not a slot. **Guard:** with one slot a second launch
//! replaced the first's listener, and the first launch's cleanup then cleared the second's.
//!
//! The scope travels with the work: async code runs under [`with_scope`] (a task-local),
//! blocking code uses [`set_scope`] (a thread-local) and [`in_current_scope`] to carry it into a
//! pool thread. [`current_scope`] checks the task-local first.

use std::cell::Cell;
use std::future::Future;
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;

use parking_lot::RwLock;

use crate::tracker::DownloadTracker;

/// Progress lines per listener are throttled to one per this many milliseconds.
pub const DOWNLOAD_PROGRESS_MIN_INTERVAL_MS: u64 = 120;

/// One launch's identity for listener routing.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct DownloadScope(u64);

impl DownloadScope {
    /// A scope no other caller has.
    pub fn new() -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(1);
        DownloadScope(NEXT.fetch_add(1, Ordering::Relaxed))
    }

    pub fn id(self) -> u64 {
        self.0
    }
}

impl Default for DownloadScope {
    fn default() -> Self {
        Self::new()
    }
}

tokio::task_local! {
    static TASK_SCOPE: Option<DownloadScope>;
}

thread_local! {
    static THREAD_SCOPE: Cell<Option<DownloadScope>> = const { Cell::new(None) };
}

/// The scope of the running task, else of the running thread.
pub fn current_scope() -> Option<DownloadScope> {
    TASK_SCOPE
        .try_with(|s| *s)
        .unwrap_or_else(|_| THREAD_SCOPE.with(Cell::get))
}

/// Sets the calling thread's scope (blocking code).
pub fn set_scope(scope: Option<DownloadScope>) {
    THREAD_SCOPE.with(|c| c.set(scope));
}

/// Runs `fut` with `scope` as its scope.
pub fn with_scope<F: Future>(
    scope: Option<DownloadScope>,
    fut: F,
) -> impl Future<Output = F::Output> {
    TASK_SCOPE.scope(scope, fut)
}

/// Wraps `f` so it runs with the caller's current scope on whatever thread runs it, restoring
/// that thread's own scope afterwards.
pub fn in_current_scope<F, R>(f: F) -> impl FnOnce() -> R + Send
where
    F: FnOnce() -> R + Send,
{
    let captured = current_scope();
    move || {
        let previous = THREAD_SCOPE.with(|c| c.replace(captured));
        struct Restore(Option<DownloadScope>);
        impl Drop for Restore {
            fn drop(&mut self) {
                THREAD_SCOPE.with(|c| c.set(self.0));
            }
        }
        let _restore = Restore(previous);
        f()
    }
}

type Callback = Arc<dyn Fn(&str) + Send + Sync>;

struct Entry {
    id: u64,
    scope: Option<DownloadScope>,
    callback: Callback,
    last_fire_ms: AtomicU64,
}

impl Entry {
    fn hears(&self, download_scope: Option<DownloadScope>) -> bool {
        self.scope.is_none() || download_scope.is_none() || self.scope == download_scope
    }
}

#[derive(Clone, Copy)]
enum Kind {
    Retry,
    Progress,
}

static RETRY: RwLock<Vec<Arc<Entry>>> = RwLock::new(Vec::new());
static PROGRESS: RwLock<Vec<Arc<Entry>>> = RwLock::new(Vec::new());
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

fn list(kind: Kind) -> &'static RwLock<Vec<Arc<Entry>>> {
    match kind {
        Kind::Retry => &RETRY,
        Kind::Progress => &PROGRESS,
    }
}

/// Removes its listener when dropped (or on [`ListenerGuard::remove`]).
#[must_use = "dropping the guard removes the listener"]
pub struct ListenerGuard {
    kind: Kind,
    id: u64,
}

impl ListenerGuard {
    /// Removes the listener now.
    pub fn remove(self) {}
}

impl Drop for ListenerGuard {
    fn drop(&mut self) {
        list(self.kind).write().retain(|e| e.id != self.id);
    }
}

fn add(kind: Kind, scope: Option<DownloadScope>, f: Callback) -> ListenerGuard {
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    let entry = Arc::new(Entry {
        id,
        scope,
        callback: f,
        last_fire_ms: AtomicU64::new(0),
    });
    list(kind).write().push(entry);
    ListenerGuard { kind, id }
}

/// Listens for retry notices (`"Retrying (2/3) jei.jar"`) of `scope` (`None`: every scope).
pub fn add_retry_listener(
    scope: Option<DownloadScope>,
    f: impl Fn(&str) + Send + Sync + 'static,
) -> ListenerGuard {
    add(Kind::Retry, scope, Arc::new(f))
}

/// Listens for live per-file progress lines of `scope` (`None`: every scope), at most one call
/// per [`DOWNLOAD_PROGRESS_MIN_INTERVAL_MS`].
pub fn add_progress_listener(
    scope: Option<DownloadScope>,
    f: impl Fn(&str) + Send + Sync + 'static,
) -> ListenerGuard {
    add(Kind::Progress, scope, Arc::new(f))
}

fn snapshot(kind: Kind) -> Vec<Arc<Entry>> {
    list(kind).read().clone()
}

fn call_quietly(cb: &Callback, line: &str) {
    // A listener fault must never poison the download path.
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| cb(line)));
}

/// Tells every listener that hears `scope` that `src_name` is being retried.
pub fn notify_retry(scope: Option<DownloadScope>, src_name: &str, attempt: u32, max: u32) {
    let entries = snapshot(Kind::Retry);
    if entries.is_empty() {
        return;
    }
    let notice = mmcl_i18n::tf!(
        "network.download.retrying",
        attempt.to_string(),
        max.to_string(),
        src_name
    );
    for e in entries.iter().filter(|e| e.hears(scope)) {
        call_quietly(&e.callback, &notice);
    }
}

fn monotonic_ms() -> u64 {
    static ANCHOR: std::sync::OnceLock<Instant> = std::sync::OnceLock::new();
    // Offset so the first reading is never inside the throttle interval of "never fired" (0).
    ANCHOR.get_or_init(Instant::now).elapsed().as_millis() as u64
        + DOWNLOAD_PROGRESS_MIN_INTERVAL_MS
        + 1
}

/// Sends a progress line to every listener that hears `scope` and whose interval has passed.
/// The line is built once, lazily, only if some listener fires.
pub fn notify_download_progress(
    scope: Option<DownloadScope>,
    dest: Option<&Path>,
    so_far: i64,
    content_length: i64,
    tracker: Option<&DownloadTracker>,
) {
    let entries = snapshot(Kind::Progress);
    if entries.is_empty() {
        return;
    }
    let now = monotonic_ms();
    let mut line: Option<String> = None;
    for e in entries.iter().filter(|e| e.hears(scope)) {
        let last = e.last_fire_ms.load(Ordering::SeqCst);
        if now.saturating_sub(last) < DOWNLOAD_PROGRESS_MIN_INTERVAL_MS
            || e.last_fire_ms
                .compare_exchange(last, now, Ordering::SeqCst, Ordering::SeqCst)
                .is_err()
        {
            continue;
        }
        let l = line.get_or_insert_with(|| {
            let speed = tracker
                .map(DownloadTracker::formatted_speed)
                .unwrap_or_default();
            format_download_progress(dest, so_far, content_length, &speed)
        });
        call_quietly(&e.callback, l);
    }
}

/// `"Faithful-64x.zip — 45% · 12.3 MB / 27.1 MB · 2.4 MB/s"`; a plain byte count when the
/// length is unknown; the speed segment only when `speed` is non-empty.
pub fn format_download_progress(dest: Option<&Path>, so_far: i64, len: i64, speed: &str) -> String {
    let name = dest
        .and_then(Path::file_name)
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let mut s = String::new();
    if !name.is_empty() {
        s.push_str(&name);
        s.push_str(" \u{2014} ");
    }
    if len > 0 {
        let pct = ((so_far.max(0) as i128 * 100) / len as i128).min(100);
        s.push_str(&format!(
            "{pct}% \u{b7} {} / {}",
            format_bytes(so_far),
            format_bytes(len)
        ));
    } else {
        s.push_str(&format_bytes(so_far));
    }
    if !speed.is_empty() {
        s.push_str(" \u{b7} ");
        s.push_str(speed);
    }
    s
}

/// A localized byte size (`"27.1 MB"`).
pub fn format_bytes(n: i64) -> String {
    mmcl_util::units::bytes(n)
}

#[cfg(test)]
mod tests {
    use super::*;
    use parking_lot::Mutex;

    fn en() {
        mmcl_i18n::set_locale(mmcl_i18n::Locale::en_us());
    }

    // NetworkUtilitiesTest: formatBytes_*
    #[test]
    fn format_bytes_renders_each_unit() {
        en();
        assert_eq!(format_bytes(945), "945 B");
        assert_eq!(format_bytes(0), "0 B");
        assert_eq!(format_bytes(812 * 1024), "812 KB");
        assert_eq!(format_bytes((27.1 * 1024.0 * 1024.0) as i64), "27.1 MB");
        assert_eq!(
            format_bytes((1.5 * 1024.0 * 1024.0 * 1024.0) as i64),
            "1.50 GB"
        );
    }

    // NetworkUtilitiesTest: formatDownloadProgress_*
    #[test]
    fn progress_includes_file_name_percent_and_byte_counts() {
        en();
        let line = format_download_progress(
            Some(Path::new("Faithful-64x.zip")),
            12_000_000,
            27_000_000,
            "",
        );
        assert!(line.starts_with("Faithful-64x.zip \u{2014} "), "{line}");
        assert!(line.contains("44%"), "{line}");
        assert!(line.contains('/'), "{line}");
    }

    #[test]
    fn progress_omits_percent_when_length_unknown() {
        en();
        let line = format_download_progress(Some(Path::new("unknown-size.bin")), 4096, 0, "");
        assert!(line.contains("4 KB"), "{line}");
        assert!(!line.contains('%'), "{line}");
    }

    #[test]
    fn progress_clamps_percent_at_one_hundred() {
        en();
        let line = format_download_progress(Some(Path::new("f.bin")), 200, 100, "");
        assert!(line.contains("100%"), "{line}");
    }

    #[test]
    fn progress_omits_name_segment_without_destination() {
        en();
        let line = format_download_progress(None, 500, 0, "");
        assert!(!line.contains('\u{2014}'), "{line}");
        assert!(line.contains("500 B"), "{line}");
    }

    #[test]
    fn progress_appends_speed_when_present_and_omits_it_when_empty() {
        en();
        let with = format_download_progress(Some(Path::new("f.bin")), 10, 1000, "2.4 MB/s");
        assert!(with.contains(" \u{b7} 2.4 MB/s"), "{with}");
        let without = format_download_progress(Some(Path::new("f.bin")), 10, 0, "");
        assert!(!without.contains('\u{b7}'), "{without}");
    }

    // NetworkUtilitiesDownloadScopeTest. The listener lists are process-wide, so these tests
    // filter on the unique name they notify with.
    fn recorder(
        tag: &'static str,
    ) -> (
        Arc<Mutex<Vec<String>>>,
        impl Fn(&str) + Send + Sync + 'static,
    ) {
        let heard = Arc::new(Mutex::new(Vec::new()));
        let h = heard.clone();
        (heard, move |s: &str| {
            if s.contains(tag) {
                h.lock().push(s.to_string());
            }
        })
    }

    #[test]
    fn a_scoped_download_reaches_only_its_launch() {
        en();
        let (a, b) = (DownloadScope::new(), DownloadScope::new());
        let (heard_a, fa) = recorder("scoped-only.jar");
        let (heard_b, fb) = recorder("scoped-only.jar");
        let _ga = add_retry_listener(Some(a), fa);
        let _gb = add_retry_listener(Some(b), fb);
        set_scope(Some(a));
        notify_retry(current_scope(), "scoped-only.jar", 1, 3);
        set_scope(None);
        assert_eq!(heard_a.lock().len(), 1);
        assert_eq!(heard_b.lock().len(), 0);
        assert_eq!(heard_a.lock()[0], "Retrying (1/3) scoped-only.jar");
    }

    #[test]
    fn an_unscoped_download_reaches_every_launch() {
        let (a, b) = (DownloadScope::new(), DownloadScope::new());
        let (heard_a, fa) = recorder("unscoped.jar");
        let (heard_b, fb) = recorder("unscoped.jar");
        let _ga = add_retry_listener(Some(a), fa);
        let _gb = add_retry_listener(Some(b), fb);
        notify_retry(None, "unscoped.jar", 1, 3);
        assert_eq!(heard_a.lock().len(), 1);
        assert_eq!(heard_b.lock().len(), 1);
    }

    #[test]
    fn the_scope_travels_with_work_handed_to_another_thread() {
        let (a, b) = (DownloadScope::new(), DownloadScope::new());
        let (heard_a, fa) = recorder("travels.jar");
        let (heard_b, fb) = recorder("travels.jar");
        let _ga = add_retry_listener(Some(a), fa);
        let _gb = add_retry_listener(Some(b), fb);
        set_scope(Some(b));
        let work = in_current_scope(|| {
            notify_retry(current_scope(), "travels.jar", 2, 3);
            current_scope()
        });
        set_scope(None);
        let (tx, rx) = std::sync::mpsc::channel::<Box<dyn FnOnce() + Send>>();
        let (seen_tx, seen_rx) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            for job in rx {
                job();
            }
        });
        let s1 = seen_tx.clone();
        tx.send(Box::new(move || s1.send(work()).unwrap())).unwrap();
        tx.send(Box::new(move || seen_tx.send(current_scope()).unwrap()))
            .unwrap();
        drop(tx);
        assert_eq!(seen_rx.recv().unwrap(), Some(b));
        // The worker thread's own scope is restored afterwards.
        assert_eq!(seen_rx.recv().unwrap(), None);
        worker.join().unwrap();
        assert_eq!(heard_a.lock().len(), 0);
        assert_eq!(heard_b.lock().len(), 1);
    }

    #[tokio::test]
    async fn task_scope_wins_over_thread_scope_and_guards_remove_listeners() {
        let s = DownloadScope::new();
        assert_eq!(
            with_scope(Some(s), async { current_scope() }).await,
            Some(s)
        );
        let (heard, f) = recorder("guarded.jar");
        let g = add_retry_listener(None, f);
        g.remove();
        notify_retry(None, "guarded.jar", 1, 3);
        assert!(heard.lock().is_empty());
    }

    #[test]
    fn progress_is_throttled_per_listener_and_listener_panics_are_contained() {
        en();
        let (heard, f) = recorder("throttled.bin");
        let scope = Some(DownloadScope::new());
        let _g = add_progress_listener(scope, f);
        let _boom = add_progress_listener(scope, |s: &str| {
            if s.contains("throttled.bin") {
                panic!("listener fault");
            }
        });
        let dest = Path::new("throttled.bin");
        notify_download_progress(scope, Some(dest), 1, 10, None);
        notify_download_progress(scope, Some(dest), 2, 10, None);
        assert_eq!(heard.lock().len(), 1);
        std::thread::sleep(std::time::Duration::from_millis(
            DOWNLOAD_PROGRESS_MIN_INTERVAL_MS + 30,
        ));
        notify_download_progress(scope, Some(dest), 3, 10, None);
        assert_eq!(heard.lock().len(), 2);
    }
}
