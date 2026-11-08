// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Starting the game (Java `ProcessUtilities.launchCommand`): working directory, scrubbed
//! environment (secrets never reach the game), `CREATE_NO_WINDOW` on Windows, and the stdio
//! mode. stdin is closed in piped mode (Q-8); server mode inherits all three so console
//! commands reach the server (systemd `StandardInput=socket`).
//!
//! **Guard:** the caller must drain both pipes of a piped child for the game's whole life,
//! or the game stalls on its next write.

use std::path::Path;
use std::process::Stdio;

use mmcl_util::process::{ChildIoMode, build_command};
use tokio::sync::oneshot;

/// How the child's stdio is wired.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ChildIo {
    /// stdout and stderr piped, stdin closed (GUI and TUI).
    Pipe,
    /// All three inherited (server mode).
    Inherit,
    /// stdout and stderr discarded, stdin closed.
    Discard,
}

/// Spawns `argv` in `cwd`.
pub fn spawn_game(
    argv: &[String],
    cwd: &Path,
    io: ChildIo,
) -> std::io::Result<tokio::process::Child> {
    let mode = match io {
        ChildIo::Pipe => ChildIoMode::Pipe,
        ChildIo::Inherit => ChildIoMode::Inherit,
        ChildIo::Discard => ChildIoMode::Discard,
    };
    let mut cmd = build_command(argv, cwd, mode)?;
    if io != ChildIo::Inherit {
        cmd.stdin(Stdio::null());
    }
    tokio::process::Command::from(cmd).spawn()
}

/// Keeps the game's Java runtime marked "in use" (so it is neither deleted nor replaced
/// under the game). Drop it once the game process has exited.
#[derive(Debug)]
pub struct RuntimeLease(#[allow(dead_code)] oneshot::Sender<()>);

impl RuntimeLease {
    /// A lease and the future that resolves when it is dropped.
    pub fn new() -> (
        RuntimeLease,
        impl std::future::Future<Output = ()> + Send + 'static,
    ) {
        let (tx, rx) = oneshot::channel::<()>();
        (RuntimeLease(tx), async move {
            let _ = rx.await;
        })
    }

    /// Releases the runtime now (same as dropping the lease).
    pub fn release(self) {}
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn lease_resolves_on_drop() {
        let (lease, fut) = RuntimeLease::new();
        let h = tokio::spawn(fut);
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        assert!(!h.is_finished());
        lease.release();
        tokio::time::timeout(std::time::Duration::from_secs(5), h)
            .await
            .unwrap()
            .unwrap();
    }

    #[tokio::test]
    async fn piped_child_has_closed_stdin() {
        let d = tempfile::tempdir().unwrap();
        let argv: Vec<String> = if cfg!(windows) {
            vec!["cmd".into(), "/C".into(), "echo hi".into()]
        } else {
            vec!["sh".into(), "-c".into(), "cat; echo hi".into()]
        };
        let child = spawn_game(&argv, d.path(), ChildIo::Pipe).unwrap();
        let out =
            tokio::time::timeout(std::time::Duration::from_secs(20), child.wait_with_output())
                .await
                .unwrap()
                .unwrap();
        assert!(String::from_utf8_lossy(&out.stdout).contains("hi"));
    }
}
