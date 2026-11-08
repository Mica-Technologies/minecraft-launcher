// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Close, restart and relaunch (core-config-i18n §6.6 and §6.7).
//!
//! [`Lifecycle::begin`] guards every transition so a second close or restart request while one
//! runs is ignored. [`cleanup`] is the back-end half of `cleanupApp`: each step runs isolated,
//! and the steps that can hang (MCP, the engine's RGB/Discord shutdown) are bounded at
//! [`BOUNDED_STEP_TIMEOUT`]. The config is flushed before the single-instance lock is released,
//! so a quick relaunch never reads a half-written file. The GUI half (closing windows) runs on
//! the GPUI thread before this, in `gui`.

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, mpsc};
use std::time::Duration;

use mmcl_engine::Engine;
use mmcl_mcp::McpBootstrap;

/// How long a bounded cleanup step may take before it is left behind.
pub const BOUNDED_STEP_TIMEOUT: Duration = Duration::from_millis(3000);

/// The process-wide transition guard.
#[derive(Default)]
pub struct Lifecycle {
    transition: AtomicBool,
    cleaned: AtomicBool,
}

static LIFECYCLE: Lifecycle = Lifecycle::new();

impl Lifecycle {
    pub const fn new() -> Lifecycle {
        Lifecycle {
            transition: AtomicBool::new(false),
            cleaned: AtomicBool::new(false),
        }
    }

    /// The process-wide guard.
    pub fn global() -> &'static Lifecycle {
        &LIFECYCLE
    }

    /// Starts a transition; `false` (and a debug line) when one is already running.
    pub fn begin(&self) -> bool {
        let ok = self
            .transition
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .is_ok();
        if !ok {
            tracing::debug!(
                "{}",
                mmcl_i18n::t!("log.launcherCore.lifecycleTransitionIgnored")
            );
        }
        ok
    }

    /// Accepts transitions again (after an in-process GUI restart finished).
    pub fn end(&self) {
        self.transition.store(false, Ordering::SeqCst);
    }

    /// `true` the first time only: the back-end cleanup runs once per process.
    fn claim_cleanup(&self) -> bool {
        !self.cleaned.swap(true, Ordering::SeqCst)
    }
}

/// What a step ended with.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StepOutcome {
    Done,
    Failed(String),
    TimedOut,
}

/// Runs `f` isolated: a panic is caught and logged as `cleanupStepFailed`.
pub fn run_step(name: &str, f: impl FnOnce()) -> StepOutcome {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(()) => StepOutcome::Done,
        Err(payload) => {
            let msg = mmcl_util::startup_log::panic_message(payload.as_ref());
            tracing::warn!(
                "{}",
                mmcl_i18n::tf!("log.launcherCore.cleanupStepFailed", name, msg.as_str())
            );
            StepOutcome::Failed(msg)
        }
    }
}

/// Runs `f` on a fresh thread and waits at most `timeout`; on a timeout the thread is left
/// behind and `cleanupStepTimedOut` is logged.
pub fn run_bounded(
    name: &str,
    timeout: Duration,
    f: impl FnOnce() + Send + 'static,
) -> StepOutcome {
    let (tx, rx) = mpsc::channel();
    let thread_name = format!("cleanup-{name}");
    let spawned = std::thread::Builder::new()
        .name(thread_name)
        .spawn(move || {
            let outcome = catch_unwind(AssertUnwindSafe(f))
                .map_err(|p| mmcl_util::startup_log::panic_message(p.as_ref()));
            let _ = tx.send(outcome);
        });
    if let Err(e) = spawned {
        tracing::warn!(
            "{}",
            mmcl_i18n::tf!("log.launcherCore.cleanupStepFailed", name, e.to_string())
        );
        return StepOutcome::Failed(e.to_string());
    }
    match rx.recv_timeout(timeout) {
        Ok(Ok(())) => StepOutcome::Done,
        Ok(Err(msg)) => {
            tracing::warn!(
                "{}",
                mmcl_i18n::tf!("log.launcherCore.cleanupStepFailed", name, msg.as_str())
            );
            StepOutcome::Failed(msg)
        }
        Err(_) => {
            tracing::warn!(
                "{}",
                mmcl_i18n::tf!(
                    "log.launcherCore.cleanupStepTimedOut",
                    name,
                    timeout.as_millis().to_string()
                )
            );
            StepOutcome::TimedOut
        }
    }
}

/// The back-end cleanup, in the load-bearing order of §6.7 (GUI steps excluded). Runs once per
/// process; later calls return at once. Blocks; never call it on the GPUI thread.
pub fn cleanup(engine: Option<&Engine>, mcp: Option<&Arc<McpBootstrap>>) {
    if !Lifecycle::global().claim_cleanup() {
        return;
    }
    mmcl_log::std(&mmcl_i18n::t!("PERFORMING_APP_CLEANUP_TEXT"));
    if let Some(mcp) = mcp {
        // Deletes mcp-endpoint.json: a stale file would point clients at a port this process
        // no longer owns.
        let mcp = mcp.clone();
        run_bounded("mcp", BOUNDED_STEP_TIMEOUT, move || mcp.stop());
    }
    if let Some(engine) = engine {
        // RGB (a final black frame), Discord, the config flush.
        let e = engine.clone();
        let outcome = run_bounded("engine", BOUNDED_STEP_TIMEOUT, move || {
            e.block_on(e.shutdown());
        });
        if outcome != StepOutcome::Done {
            let config = engine.config().clone();
            run_step("config", move || config.flush());
        }
    }
    run_step("notifications", mmcl_ui_kit::notify::shutdown);
    run_step("tray", mmcl_platform::tray::shutdown);
    run_step("taskbarProgress", mmcl_platform::progress::shutdown);
    run_step("instanceLock", mmcl_platform::single_instance::release);
    mmcl_log::std(&mmcl_i18n::t!("FINISHED_APP_CLEANUP_TEXT"));
    run_step("logging", || {
        mmcl_log::flush();
        mmcl_log::shutdown();
    });
}

/// The executable to relaunch, or `None` for a dev run (the caller restarts in-process).
pub fn relaunch_executable() -> Option<std::path::PathBuf> {
    mmcl_core::install::relaunch_executable()
}

/// Starts `exe` as a new top-level process with its folder as the working directory.
///
/// Windows (core-config-i18n §6.7): `ShellExecuteW` first, so the shell starts it as if the
/// user had (no inherited handles, no job object, the normal window state), then
/// `cmd /c start "" "<exe>"`, then a detached spawn. Elsewhere: a detached spawn with no
/// inherited standard streams.
pub fn spawn_relaunch(exe: &Path) -> std::io::Result<()> {
    #[cfg(windows)]
    {
        if win::shell_execute(exe).is_ok() {
            return Ok(());
        }
        if win::cmd_start(exe).is_ok() {
            return Ok(());
        }
    }
    spawn_detached(exe)
}

#[cfg(windows)]
mod win {
    use std::os::windows::ffi::OsStrExt as _;
    use std::os::windows::process::CommandExt as _;
    use std::path::Path;

    fn wide(s: &std::ffi::OsStr) -> Vec<u16> {
        s.encode_wide().chain(std::iter::once(0)).collect()
    }

    /// `ShellExecuteW(NULL, "open", exe, NULL, dir, SW_SHOWNORMAL)`; values above 32 succeed.
    pub fn shell_execute(exe: &Path) -> std::io::Result<()> {
        use windows_sys::Win32::UI::Shell::ShellExecuteW;
        use windows_sys::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;
        let file = wide(exe.as_os_str());
        let verb = wide(std::ffi::OsStr::new("open"));
        let dir = exe.parent().map(|d| wide(d.as_os_str()));
        // SAFETY: every pointer is a NUL-terminated UTF-16 buffer that outlives the call.
        let result = unsafe {
            ShellExecuteW(
                std::ptr::null_mut(),
                verb.as_ptr(),
                file.as_ptr(),
                std::ptr::null(),
                dir.as_ref().map_or(std::ptr::null(), |d| d.as_ptr()),
                SW_SHOWNORMAL,
            )
        };
        if result as isize > 32 {
            Ok(())
        } else {
            Err(std::io::Error::other(format!(
                "ShellExecuteW returned {}",
                result as isize
            )))
        }
    }

    /// `cmd /c start "" "<exe>"` without a console window.
    pub fn cmd_start(exe: &Path) -> std::io::Result<()> {
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        let mut cmd = std::process::Command::new("cmd");
        cmd.args(["/c", "start", ""]).arg(exe);
        if let Some(dir) = exe.parent() {
            cmd.current_dir(dir);
        }
        cmd.stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .creation_flags(CREATE_NO_WINDOW);
        cmd.spawn().map(|_| ())
    }
}

/// A detached spawn of `exe` with no inherited standard streams.
fn spawn_detached(exe: &Path) -> std::io::Result<()> {
    use std::process::{Command, Stdio};
    let mut cmd = Command::new(exe);
    if let Some(dir) = exe.parent() {
        cmd.current_dir(dir);
    }
    cmd.stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const DETACHED_PROCESS: u32 = 0x0000_0008;
        const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;
        cmd.creation_flags(DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP);
    }
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        cmd.process_group(0);
    }
    cmd.spawn().map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_second_transition_is_ignored_until_the_first_ends() {
        let l = Lifecycle::new();
        assert!(l.begin());
        assert!(!l.begin());
        l.end();
        assert!(l.begin());
    }

    #[test]
    fn cleanup_is_claimed_once() {
        let l = Lifecycle::new();
        assert!(l.claim_cleanup());
        assert!(!l.claim_cleanup());
    }

    #[test]
    fn a_panicking_step_is_isolated() {
        assert_eq!(run_step("ok", || {}), StepOutcome::Done);
        match run_step("boom", || panic!("nope")) {
            StepOutcome::Failed(msg) => assert!(msg.contains("nope"), "{msg}"),
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn a_bounded_step_times_out_and_moves_on() {
        let started = std::time::Instant::now();
        let outcome = run_bounded("slow", Duration::from_millis(50), || {
            std::thread::sleep(Duration::from_millis(2000));
        });
        assert_eq!(outcome, StepOutcome::TimedOut);
        assert!(started.elapsed() < Duration::from_millis(1500));
        assert_eq!(
            run_bounded("fast", Duration::from_secs(5), || {}),
            StepOutcome::Done
        );
        assert!(matches!(
            run_bounded("panics", Duration::from_secs(5), || panic!("bad")),
            StepOutcome::Failed(_)
        ));
    }
}
