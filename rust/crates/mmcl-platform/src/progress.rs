// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Taskbar and dock progress (`TaskbarProgressManager`, `MacOsDockManager` progress).
//!
//! One process-wide owner, safe to call from any thread. Commands go to a dedicated worker
//! thread that owns the single native wrapper (**Guard:** several wrappers on one HWND
//! interleaved and left the bar stuck):
//!
//! - Windows: `ITaskbarList3` created lazily on the worker's STA after [`attach`] gives it the
//!   HWND; calls fail until the taskbar button exists, so a failed init is retried on the
//!   next call.
//! - macOS: the dock tile (progress view, badge, attention), hopped to the main queue.
//! - Linux: the `com.canonical.Unity.LauncherEntry` D-Bus signal (KDE Plasma, Dash-to-Dock).
//!
//! Several launches can report at once: [`set_launch_progress`] keeps one value per launch and
//! shows their combination ([`mmcl_util::progress::combine`]); **Guard:** each launch used to
//! overwrite the bar and the first to finish cleared it for all.

use std::collections::HashMap;
use std::sync::OnceLock;
use std::sync::mpsc::{Sender, channel};

use parking_lot::Mutex;
use raw_window_handle::HasWindowHandle;

use crate::handle::{NativeHandle, native_handle};
pub use mmcl_util::Progress;
pub use mmcl_util::progress::combine;

/// Identifies one launch's progress.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct LaunchId(pub u64);

/// What the native side shows.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Command {
    /// Bind to a window (Windows needs the HWND).
    Attach(NativeHandle),
    /// Show a value.
    Set(Progress),
    /// Hide the bar.
    Stop,
    /// The "update available" attention cue: full red bar (Windows); error, attention and a
    /// badge (macOS); urgent (Linux).
    FullError,
    /// Clear everything and release native objects.
    Shutdown,
}

/// A native progress surface. It is created on, and only used from, the progress worker
/// thread (COM objects are not `Send`).
pub trait ProgressBackend: 'static {
    /// Handles one command.
    fn apply(&mut self, cmd: Command);
}

/// Per-launch values and their combination. Pure; the global owner wraps one.
#[derive(Debug, Default)]
pub struct LaunchProgress {
    values: HashMap<LaunchId, Progress>,
    order: Vec<LaunchId>,
}

impl LaunchProgress {
    /// Records `owner`'s value and returns the combination to show.
    pub fn set(&mut self, owner: LaunchId, p: Progress) -> Progress {
        if self.values.insert(owner, p).is_none() {
            self.order.push(owner);
        }
        self.combined()
    }

    /// Forgets `owner`. Returns the combination to show, or `None` when no launch is left
    /// (stop the bar).
    pub fn end(&mut self, owner: LaunchId) -> Option<Progress> {
        self.values.remove(&owner);
        self.order.retain(|o| *o != owner);
        (!self.values.is_empty()).then(|| self.combined())
    }

    /// Number of launches reporting.
    pub fn len(&self) -> usize {
        self.values.len()
    }

    /// Whether no launch is reporting.
    pub fn is_empty(&self) -> bool {
        self.values.is_empty()
    }

    fn combined(&self) -> Progress {
        let vals: Vec<Progress> = self
            .order
            .iter()
            .filter_map(|o| self.values.get(o).copied())
            .collect();
        combine(&vals)
    }
}

struct Owner {
    tx: Mutex<Option<Sender<Command>>>,
    launches: Mutex<LaunchProgress>,
}

fn owner() -> &'static Owner {
    static OWNER: OnceLock<Owner> = OnceLock::new();
    OWNER.get_or_init(|| Owner {
        tx: Mutex::new(spawn_worker(platform_backend)),
        launches: Mutex::new(LaunchProgress::default()),
    })
}

/// Starts a worker thread that creates its backend with `make` and drains commands into it.
/// `None` if the thread could not be created (progress is then simply not shown).
pub fn spawn_worker(
    make: impl FnOnce() -> Box<dyn ProgressBackend> + Send + 'static,
) -> Option<Sender<Command>> {
    let (tx, rx) = channel::<Command>();
    std::thread::Builder::new()
        .name("mmcl-taskbar".into())
        .spawn(move || {
            #[cfg(windows)]
            let _apartment = crate::win::com::Apartment::sta();
            let mut backend = make();
            while let Ok(cmd) = rx.recv() {
                let last = cmd == Command::Shutdown;
                crate::guard(|| backend.apply(cmd));
                if last {
                    break;
                }
            }
        })
        .ok()
        .map(|_| tx)
}

fn send(cmd: Command) {
    let o = owner();
    let mut tx = o.tx.lock();
    if let Some(s) = tx.as_ref()
        && s.send(cmd).is_err()
    {
        *tx = None;
    }
}

fn platform_backend() -> Box<dyn ProgressBackend> {
    #[cfg(windows)]
    {
        Box::new(crate::win::taskbar::TaskbarBackend::default())
    }
    #[cfg(target_os = "macos")]
    {
        Box::new(crate::mac::dock::DockBackend)
    }
    #[cfg(target_os = "linux")]
    {
        Box::new(crate::linux::launcher_entry::LauncherEntryBackend::default())
    }
    #[cfg(not(any(windows, target_os = "macos", target_os = "linux")))]
    {
        struct Nothing;
        impl ProgressBackend for Nothing {
            fn apply(&mut self, _cmd: Command) {}
        }
        Box::new(Nothing)
    }
}

/// Binds the progress owner to the main window. Call once the window is shown.
pub fn attach<W: HasWindowHandle + ?Sized>(window: &W) {
    send(Command::Attach(native_handle(window)));
}

/// Shows `p` on the taskbar button or dock tile.
pub fn set_progress(p: Progress) {
    send(Command::Set(p));
}

/// Hides the progress.
pub fn stop() {
    send(Command::Stop);
}

/// The "update available" attention cue.
pub fn show_full_error() {
    send(Command::FullError);
}

/// Records one launch's progress and shows the combination of all running launches.
pub fn set_launch_progress(owner_id: LaunchId, p: Progress) {
    let combined = owner().launches.lock().set(owner_id, p);
    set_progress(combined);
}

/// Ends one launch's progress: stops the bar when it was the last, else shows the rest.
pub fn end_launch_progress(owner_id: LaunchId) {
    let next = owner().launches.lock().end(owner_id);
    match next {
        Some(p) => set_progress(p),
        None => stop(),
    }
}

/// Clears everything and releases the native objects (idempotent).
pub fn shutdown() {
    let o = owner();
    let tx = o.tx.lock().take();
    if let Some(tx) = tx {
        let _ = tx.send(Command::Shutdown);
    }
}

/// Windows percent for a fraction: `round(f * 100)` clamped to 0..=100.
pub fn percent(f: f64) -> u64 {
    if f.is_nan() {
        return 0;
    }
    (f.clamp(0.0, 1.0) * 100.0).round() as u64
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::time::Duration;

    fn frac(p: Progress) -> f64 {
        match p {
            Progress::Fraction(f) => f,
            Progress::Indeterminate => -1.0,
        }
    }

    #[test]
    fn launches_combine_and_the_last_one_stops_the_bar() {
        let mut lp = LaunchProgress::default();
        assert_eq!(frac(lp.set(LaunchId(1), Progress::Fraction(0.5))), 0.5);
        assert_eq!(frac(lp.set(LaunchId(2), Progress::Fraction(1.0))), 0.75);
        assert_eq!(
            lp.set(LaunchId(3), Progress::Indeterminate),
            Progress::Indeterminate
        );
        assert_eq!(lp.end(LaunchId(3)).map(frac), Some(0.75));
        // The first to finish no longer clears the bar for the others.
        assert_eq!(lp.end(LaunchId(2)).map(frac), Some(0.5));
        assert_eq!(lp.end(LaunchId(1)), None);
        assert!(lp.is_empty());
    }

    #[test]
    fn updating_a_launch_replaces_its_value() {
        let mut lp = LaunchProgress::default();
        lp.set(LaunchId(1), Progress::Fraction(0.1));
        assert_eq!(frac(lp.set(LaunchId(1), Progress::Fraction(0.9))), 0.9);
        assert_eq!(lp.len(), 1);
    }

    #[test]
    fn percent_rounds_and_clamps() {
        assert_eq!(percent(0.0), 0);
        assert_eq!(percent(0.505), 51);
        assert_eq!(percent(2.0), 100);
        assert_eq!(percent(-1.0), 0);
        assert_eq!(percent(f64::NAN), 0);
    }

    struct Rec(Arc<Mutex<Vec<Command>>>);
    impl ProgressBackend for Rec {
        fn apply(&mut self, cmd: Command) {
            self.0.lock().push(cmd);
        }
    }

    #[test]
    fn the_worker_drains_commands_in_order_and_ends_on_shutdown() {
        let log = Arc::new(Mutex::new(Vec::new()));
        let rec = Rec(log.clone());
        let tx = spawn_worker(move || Box::new(rec)).unwrap();
        tx.send(Command::Set(Progress::Fraction(0.2))).unwrap();
        tx.send(Command::Stop).unwrap();
        tx.send(Command::Shutdown).unwrap();
        for _ in 0..100 {
            if log.lock().len() == 3 {
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(
            *log.lock(),
            vec![
                Command::Set(Progress::Fraction(0.2)),
                Command::Stop,
                Command::Shutdown
            ]
        );
        std::thread::sleep(Duration::from_millis(50));
        assert!(
            tx.send(Command::Stop).is_err(),
            "the worker exits after shutdown"
        );
    }
}
