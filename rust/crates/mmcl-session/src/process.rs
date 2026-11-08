// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The game process as a session sees it.
//!
//! [`ProcessHandle`] is the seam: [`ChildProcess`] wraps a real `tokio::process::Child`, and
//! tests use a fake. A graceful stop asks the game to close (Unix `SIGTERM`; Windows `WM_CLOSE`
//! to the game's top-level windows, so Minecraft saves the world, falling back to a kill when it
//! has no window); a forced stop kills it.

use std::process::ExitStatus;
use std::sync::Arc;

use parking_lot::Mutex;
use tokio::sync::mpsc;

use crate::error::SessionError;

/// A callback run once with the exit code.
pub type ExitCallback = Box<dyn FnOnce(i32) + Send + 'static>;

/// A running (or finished) game process.
pub trait ProcessHandle: Send + Sync {
    /// The OS process id, when known.
    fn pid(&self) -> Option<u32>;
    /// Whether the process has not exited yet.
    fn is_alive(&self) -> bool;
    /// Asks the process to exit (graceful stop).
    fn terminate(&self);
    /// Kills the process (forced stop).
    fn kill(&self);
    /// Runs `callback` with the exit code (-1 when unknown) once the process has exited, or
    /// right away (on the calling thread) when it already has.
    fn on_exit(&self, callback: ExitCallback);
}

/// The exit code of a finished process: its code, or on Unix `128 + signal` for a signal death
/// (what Java reports), else -1.
pub fn exit_code_of(status: ExitStatus) -> i32 {
    if let Some(code) = status.code() {
        return code;
    }
    #[cfg(unix)]
    {
        use std::os::unix::process::ExitStatusExt;
        if let Some(sig) = status.signal() {
            return 128 + sig;
        }
    }
    -1
}

#[derive(Default)]
struct ExitState {
    code: Option<i32>,
    callbacks: Vec<ExitCallback>,
}

/// A [`ProcessHandle`] over a `tokio::process::Child`: a task on the runtime waits for it and
/// performs forced kills (the child itself is owned by that task).
pub struct ChildProcess {
    pid: Option<u32>,
    state: Mutex<ExitState>,
    kill_tx: mpsc::UnboundedSender<()>,
}

impl ChildProcess {
    /// Watches `child` on the current tokio runtime. Take its stdout/stderr first (for example
    /// with [`GameLog::attach_child`](crate::GameLog::attach_child)) when they are piped.
    pub fn watch(child: tokio::process::Child) -> Result<Arc<Self>, SessionError> {
        let handle =
            tokio::runtime::Handle::try_current().map_err(|_| SessionError::NoAsyncRuntime)?;
        Ok(Self::watch_on(child, &handle))
    }

    /// Watches `child` on `runtime`.
    pub fn watch_on(
        mut child: tokio::process::Child,
        runtime: &tokio::runtime::Handle,
    ) -> Arc<Self> {
        let (kill_tx, mut kill_rx) = mpsc::unbounded_channel::<()>();
        let me = Arc::new(ChildProcess {
            pid: child.id(),
            state: Mutex::new(ExitState::default()),
            kill_tx,
        });
        let watcher = Arc::clone(&me);
        runtime.spawn(async move {
            let code = loop {
                tokio::select! {
                    status = child.wait() => break match status {
                        Ok(s) => exit_code_of(s),
                        Err(_) => -1,
                    },
                    Some(()) = kill_rx.recv() => {
                        let _ = child.start_kill();
                    }
                }
            };
            watcher.finish(code);
        });
        me
    }

    /// The exit code, once the process has exited.
    pub fn exit_code(&self) -> Option<i32> {
        self.state.lock().code
    }

    fn finish(&self, code: i32) {
        let callbacks = {
            let mut st = self.state.lock();
            if st.code.is_some() {
                return;
            }
            st.code = Some(code);
            std::mem::take(&mut st.callbacks)
        };
        for cb in callbacks {
            crate::listeners::run_isolated(|| cb(code));
        }
    }
}

impl ProcessHandle for ChildProcess {
    fn pid(&self) -> Option<u32> {
        self.pid
    }

    fn is_alive(&self) -> bool {
        self.state.lock().code.is_none()
    }

    fn terminate(&self) {
        if !self.is_alive() {
            return;
        }
        match self.pid {
            Some(pid) if graceful::request_close(pid) => {}
            _ => self.kill(),
        }
    }

    fn kill(&self) {
        if self.is_alive() {
            let _ = self.kill_tx.send(());
        }
    }

    fn on_exit(&self, callback: ExitCallback) {
        let code = {
            let mut st = self.state.lock();
            match st.code {
                Some(code) => code,
                None => {
                    st.callbacks.push(callback);
                    return;
                }
            }
        };
        callback(code);
    }
}

#[cfg(unix)]
mod graceful {
    /// Sends `SIGTERM`. `false` when the signal could not be sent.
    pub(super) fn request_close(pid: u32) -> bool {
        let Ok(pid) = i32::try_from(pid) else {
            return false;
        };
        // SAFETY: kill(2) with a positive pid and a valid signal number has no memory effects.
        unsafe { libc::kill(pid, libc::SIGTERM) == 0 }
    }
}

#[cfg(windows)]
mod graceful {
    use windows_sys::Win32::Foundation::{HWND, LPARAM};
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        EnumWindows, GetWindowThreadProcessId, IsWindowVisible, PostMessageW, WM_CLOSE,
    };

    struct Search {
        pid: u32,
        posted: bool,
    }

    unsafe extern "system" fn visit(hwnd: HWND, lparam: LPARAM) -> windows_sys::core::BOOL {
        // SAFETY: lparam is the `&mut Search` passed to EnumWindows below, alive for the call.
        let search = unsafe { &mut *(lparam as *mut Search) };
        let mut owner = 0u32;
        // SAFETY: hwnd comes from EnumWindows; owner is a valid out pointer.
        unsafe { GetWindowThreadProcessId(hwnd, &mut owner) };
        // SAFETY: plain Win32 calls on a window handle EnumWindows handed us.
        if owner == search.pid
            && unsafe { IsWindowVisible(hwnd) } != 0
            && unsafe { PostMessageW(hwnd, WM_CLOSE, 0, 0) } != 0
        {
            search.posted = true;
        }
        1
    }

    /// Posts `WM_CLOSE` to every visible top-level window of `pid` (Minecraft then shuts down
    /// cleanly and saves the world). `false` when the process has no such window.
    pub(super) fn request_close(pid: u32) -> bool {
        let mut search = Search { pid, posted: false };
        // SAFETY: the callback only dereferences lparam as the Search that outlives this call.
        unsafe { EnumWindows(Some(visit), &mut search as *mut Search as LPARAM) };
        search.posted
    }
}

#[cfg(not(any(unix, windows)))]
mod graceful {
    pub(super) fn request_close(_pid: u32) -> bool {
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    fn command(args: &[&str]) -> tokio::process::Command {
        let mut c = tokio::process::Command::new(args[0]);
        c.args(&args[1..]);
        c.stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null());
        c
    }

    async fn exit_of(p: &Arc<ChildProcess>) -> i32 {
        let (tx, rx) = tokio::sync::oneshot::channel();
        p.on_exit(Box::new(move |c| {
            let _ = tx.send(c);
        }));
        tokio::time::timeout(Duration::from_secs(20), rx)
            .await
            .expect("process exits")
            .unwrap()
    }

    #[tokio::test]
    async fn reports_the_exit_code() {
        let args: &[&str] = if cfg!(windows) {
            &["cmd", "/C", "exit 3"]
        } else {
            &["sh", "-c", "exit 3"]
        };
        let p = ChildProcess::watch(command(args).spawn().unwrap()).unwrap();
        assert!(p.pid().is_some());
        assert_eq!(exit_of(&p).await, 3);
        assert!(!p.is_alive());
        assert_eq!(p.exit_code(), Some(3));
        // A callback added after the exit runs right away.
        let (tx, rx) = std::sync::mpsc::channel();
        p.on_exit(Box::new(move |c| tx.send(c).unwrap()));
        assert_eq!(rx.try_recv().unwrap(), 3);
    }

    #[tokio::test]
    async fn kill_ends_a_long_running_process() {
        let args: &[&str] = if cfg!(windows) {
            &["ping", "-n", "60", "127.0.0.1"]
        } else {
            &["sleep", "60"]
        };
        let p = ChildProcess::watch(command(args).spawn().unwrap()).unwrap();
        assert!(p.is_alive());
        p.kill();
        let code = exit_of(&p).await;
        assert_ne!(code, 0);
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn terminate_sends_sigterm() {
        let p = ChildProcess::watch(command(&["sleep", "60"]).spawn().unwrap()).unwrap();
        p.terminate();
        assert_eq!(exit_of(&p).await, 128 + libc::SIGTERM);
    }

    #[cfg(windows)]
    #[tokio::test]
    async fn terminate_without_a_window_falls_back_to_kill() {
        let p = ChildProcess::watch(command(&["ping", "-n", "60", "127.0.0.1"]).spawn().unwrap())
            .unwrap();
        p.terminate();
        assert_ne!(exit_of(&p).await, 0);
    }

    #[test]
    fn watch_outside_a_runtime_is_an_error() {
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let args: &[&str] = if cfg!(windows) {
            &["cmd", "/C", "exit 0"]
        } else {
            &["true"]
        };
        let child = rt.block_on(async { command(args).spawn().unwrap() });
        // Outside the runtime context, `watch` refuses; `watch_on` works.
        let p = ChildProcess::watch_on(child, rt.handle());
        let code = rt.block_on(exit_of(&p));
        assert_eq!(code, 0);
        let child = rt.block_on(async { command(args).spawn().unwrap() });
        let err = match ChildProcess::watch(child) {
            Err(e) => e,
            Ok(_) => panic!("no runtime"),
        };
        assert_eq!(err.l10n_key(), "session.error.noAsyncRuntime");
    }
}
