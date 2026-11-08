// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Crash isolation for the vendor-DLL backends (Razer native, Corsair, ASUS Aura).
//!
//! A native access violation inside a vendor SDK cannot be caught in Rust any more than in Java,
//! and it used to take the whole launcher down without a log line. So the launcher never loads
//! those DLLs itself: [`HelperBackend`] re-executes the current program as
//! `<exe> --rgb-helper <kind> --locale=<tag>`, and the child runs the real backend behind a
//! JSON-lines protocol on stdin/stdout ([`protocol`]). If the child dies, the launcher sees a
//! closed pipe, the circuit breaker counts the failure, and the next allowed frame restarts the
//! child (at most [`client::MAX_RESPAWNS`] times).
//!
//! `mmcl-app` wires this in: before anything else in `main()`, if [`helper_requested`] is true,
//! call [`run_helper`] with the remaining arguments and exit with its code. The helper process
//! must not install the console logger (stdout is protocol-only); [`run_helper`] installs its
//! own `tracing` layer that forwards log lines to the launcher.

pub mod client;
pub mod protocol;
pub mod server;

pub use client::{HelperBackend, HelperChild, HelperProcess, HelperSpawner, ProcessSpawner};

/// The helper-process flag; must be the first argument.
pub const HELPER_ARG: &str = "--rgb-helper";

/// `true` when argv (without the program name) asks for the RGB helper.
pub fn helper_requested(args: &[String]) -> bool {
    args.first().is_some_and(|a| a == HELPER_ARG)
}

/// Runs the helper. `args` is argv without the program name, starting with `--rgb-helper`
/// (or the arguments after it). Returns the process exit code.
pub fn run_helper(args: &[String]) -> i32 {
    let rest = if helper_requested(args) {
        &args[1..]
    } else {
        args
    };
    server::run(rest)
}

#[cfg(test)]
mod tests {
    use std::io::{self, BufReader, Write};
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
    use std::thread::JoinHandle;
    use std::time::{Duration, Instant};

    use parking_lot::Mutex;

    use super::client::reply_channel;
    use super::server::{SharedWriter, serve};
    use super::*;
    use crate::backend::{BackendKind, RgbBackend};
    use crate::color::RgbColor;
    use crate::error::{RgbError, RgbResult};
    use crate::frame::RgbFrame;

    /// A vendor backend stand-in. A frame with background 0xDEAD "crashes the process".
    struct Fake {
        available: bool,
        crash: Arc<AtomicBool>,
        frames: Arc<Mutex<Vec<RgbFrame>>>,
    }

    impl RgbBackend for Fake {
        fn name(&self) -> &str {
            "fake"
        }
        fn is_available(&mut self) -> bool {
            self.available
        }
        fn start(&mut self) -> RgbResult<()> {
            Ok(())
        }
        fn render_frame(&mut self, f: &RgbFrame) -> RgbResult<()> {
            if f.background().pack_rgb() == 0xDEAD {
                self.crash.store(true, Ordering::SeqCst);
                return Ok(());
            }
            if f.background() == RgbColor::WHITE {
                return Err(RgbError::backend("device said no"));
            }
            self.frames.lock().push(*f);
            Ok(())
        }
        fn shutdown(&mut self) {}
        fn kind(&self) -> BackendKind {
            BackendKind::Corsair
        }
    }

    /// Stops writing (closing the pipe, like a dead process) once `crash` is set.
    struct CrashableWriter {
        inner: Option<io::PipeWriter>,
        crash: Arc<AtomicBool>,
    }

    impl Write for CrashableWriter {
        fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
            if self.crash.load(Ordering::SeqCst) {
                self.inner = None;
                return Err(io::Error::from(io::ErrorKind::BrokenPipe));
            }
            self.inner
                .as_mut()
                .ok_or(io::ErrorKind::BrokenPipe)?
                .write(buf)
        }
        fn flush(&mut self) -> io::Result<()> {
            match self.inner.as_mut() {
                Some(w) => w.flush(),
                None => Err(io::ErrorKind::BrokenPipe.into()),
            }
        }
    }

    struct ThreadProcess(Option<JoinHandle<()>>);

    impl HelperProcess for ThreadProcess {
        fn wait_timeout(&mut self, timeout: Duration) -> bool {
            let deadline = Instant::now() + timeout;
            while Instant::now() < deadline {
                if self.0.as_ref().is_none_or(|h| h.is_finished()) {
                    if let Some(h) = self.0.take() {
                        let _ = h.join();
                    }
                    return true;
                }
                std::thread::sleep(Duration::from_millis(5));
            }
            false
        }
        fn kill(&mut self) {}
    }

    struct InMemorySpawner {
        available: bool,
        spawns: AtomicUsize,
        frames: Arc<Mutex<Vec<RgbFrame>>>,
    }

    impl HelperSpawner for InMemorySpawner {
        fn spawn(&self, kind: BackendKind) -> io::Result<HelperChild> {
            self.spawns.fetch_add(1, Ordering::SeqCst);
            let (req_r, req_w) = io::pipe()?;
            let (rep_r, rep_w) = io::pipe()?;
            let crash = Arc::new(AtomicBool::new(false));
            let backend = Box::new(Fake {
                available: self.available,
                crash: crash.clone(),
                frames: self.frames.clone(),
            });
            let out: SharedWriter = Arc::new(Mutex::new(Box::new(CrashableWriter {
                inner: Some(rep_w),
                crash,
            })));
            let h = std::thread::spawn(move || serve(backend, BufReader::new(req_r), out));
            Ok(HelperChild {
                stdin: Box::new(req_w),
                replies: reply_channel(kind, rep_r),
                process: Box::new(ThreadProcess(Some(h))),
            })
        }
    }

    fn spawner(available: bool) -> Arc<InMemorySpawner> {
        Arc::new(InMemorySpawner {
            available,
            spawns: AtomicUsize::new(0),
            frames: Arc::new(Mutex::new(Vec::new())),
        })
    }

    #[test]
    fn frames_cross_the_pipe_and_errors_come_back() {
        let s = spawner(true);
        let mut b = HelperBackend::with_spawner(BackendKind::Corsair, s.clone());
        assert!(b.is_available());
        assert!(b.is_available(), "second probe reuses the child");
        assert_eq!(s.spawns.load(Ordering::SeqCst), 1);
        b.start().unwrap();
        let f = RgbFrame::new(
            RgbColor::new(1, 2, 3),
            [(crate::key::KeyboardKey::W, RgbColor::new(9, 9, 9))],
        );
        b.render_frame(&f).unwrap();
        assert_eq!(*s.frames.lock(), vec![f]);
        let e = b
            .render_frame(&RgbFrame::solid(RgbColor::WHITE))
            .unwrap_err();
        assert!(e.to_string().contains("device said no"));
        b.shutdown();
        assert!(!b.has_child());
        b.shutdown();
    }

    #[test]
    fn a_crashed_helper_is_an_error_then_restarted() {
        let s = spawner(true);
        let mut b = HelperBackend::with_spawner(BackendKind::ChromaNative, s.clone());
        assert!(b.is_available());
        b.start().unwrap();
        let e = b
            .render_frame(&RgbFrame::solid(RgbColor::from_packed(0xDEAD)))
            .unwrap_err();
        assert!(e.to_string().contains("exited"), "{e}");
        assert!(!b.has_child());
        b.render_frame(&RgbFrame::solid(RgbColor::new(5, 5, 5)))
            .unwrap();
        assert_eq!(s.spawns.load(Ordering::SeqCst), 2);
        // Bounded restarts.
        for _ in 0..client::MAX_RESPAWNS {
            let _ = b.render_frame(&RgbFrame::solid(RgbColor::from_packed(0xDEAD)));
            let _ = b.render_frame(&RgbFrame::solid(RgbColor::new(5, 5, 5)));
        }
        let e = b
            .render_frame(&RgbFrame::solid(RgbColor::new(6, 6, 6)))
            .unwrap_err();
        assert!(e.to_string().contains("too often"), "{e}");
    }

    #[test]
    fn unavailable_closes_the_child_and_is_cached() {
        let s = spawner(false);
        let mut b = HelperBackend::with_spawner(BackendKind::AsusAura, s.clone());
        assert!(!b.is_available());
        assert!(!b.has_child());
        assert!(!b.is_available());
        assert_eq!(s.spawns.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn helper_arg_detection() {
        assert!(helper_requested(&["--rgb-helper".into(), "corsair".into()]));
        assert!(!helper_requested(&["-c".into()]));
        assert!(!helper_requested(&[]));
        assert_eq!(run_helper(&["--rgb-helper".into(), "nope".into()]), 2);
    }
}
