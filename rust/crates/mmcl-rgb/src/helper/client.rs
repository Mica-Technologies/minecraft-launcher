// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The launcher side: [`HelperBackend`] runs a vendor-DLL backend in a child process, so a
//! native fault inside a vendor SDK kills only the child. The controller sees an error (the
//! breaker counts it) and the backend restarts the child a bounded number of times.

use std::io::{self, BufRead, BufReader, Read, Write};
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::sync::mpsc::{self, Receiver, RecvTimeoutError};
use std::time::{Duration, Instant};

use mmcl_i18n::tf;

use super::HELPER_ARG;
use super::protocol::{self, Reply, Request};
use crate::backend::{BackendKind, RgbBackend};
use crate::error::{RgbError, RgbResult};
use crate::frame::RgbFrame;

/// Waits per request. `available` includes spawning the child and loading the DLL; `start`
/// covers iCUE's 8 s connect wait.
pub const SPAWN_TIMEOUT: Duration = Duration::from_millis(3000);
pub const AVAILABLE_TIMEOUT: Duration = Duration::from_millis(3000);
pub const START_TIMEOUT: Duration = Duration::from_millis(15_000);
/// Below the controller's 2 s worker-join bound.
pub const RENDER_TIMEOUT: Duration = Duration::from_millis(1500);
pub const SHUTDOWN_TIMEOUT: Duration = Duration::from_millis(3000);
/// How long a child may take to exit after `exit` before it is killed.
pub const EXIT_GRACE: Duration = Duration::from_millis(1000);
/// How many times a crashed child is restarted per backend instance.
pub const MAX_RESPAWNS: u32 = 3;

/// A running helper process (or a stand-in in tests).
pub trait HelperProcess: Send {
    /// Waits up to `timeout` for the process to exit; `true` if it did.
    fn wait_timeout(&mut self, timeout: Duration) -> bool;
    /// Kills it (by handle).
    fn kill(&mut self);
}

/// One connected helper: its stdin, a channel of its stdout replies (closed when it exits),
/// and its process.
pub struct HelperChild {
    pub stdin: Box<dyn Write + Send>,
    pub replies: Receiver<Reply>,
    pub process: Box<dyn HelperProcess>,
}

/// Starts helper processes.
pub trait HelperSpawner: Send + Sync {
    fn spawn(&self, kind: BackendKind) -> io::Result<HelperChild>;
}

/// Reads `stdout` lines on a thread: log lines are re-logged here, replies go to the channel.
pub fn reply_channel(kind: BackendKind, stdout: impl Read + Send + 'static) -> Receiver<Reply> {
    let (tx, rx) = mpsc::channel();
    let label = kind.label();
    let _ = std::thread::Builder::new()
        .name("mica-rgb-helper-out".into())
        .spawn(move || {
            for line in BufReader::new(stdout).lines() {
                let Ok(line) = line else { break };
                if line.trim().is_empty() {
                    continue;
                }
                match protocol::decode::<Reply>(&line) {
                    Ok(Reply::Log { level, msg }) => match level.as_str() {
                        "error" => tracing::error!("{msg}"),
                        "warn" => tracing::warn!("{msg}"),
                        "info" => tracing::info!("{msg}"),
                        _ => tracing::debug!("{msg}"),
                    },
                    Ok(reply) => {
                        if tx.send(reply).is_err() {
                            break;
                        }
                    }
                    Err(_) => {
                        tracing::debug!("{}", tf!("log.rgb.helper.stderr", label, line.as_str()))
                    }
                }
            }
        });
    rx
}

struct OsProcess(Child);

impl HelperProcess for OsProcess {
    fn wait_timeout(&mut self, timeout: Duration) -> bool {
        let deadline = Instant::now() + timeout;
        loop {
            match self.0.try_wait() {
                Ok(Some(_)) => return true,
                Ok(None) if Instant::now() < deadline => {
                    std::thread::sleep(Duration::from_millis(20))
                }
                _ => return false,
            }
        }
    }

    fn kill(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

/// Spawns `current_exe() --rgb-helper <kind> --locale=<tag>`.
pub struct ProcessSpawner;

impl HelperSpawner for ProcessSpawner {
    fn spawn(&self, kind: BackendKind) -> io::Result<HelperChild> {
        let arg = kind
            .helper_arg()
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "kind has no helper"))?;
        let exe = std::env::current_exe()?;
        let mut cmd = Command::new(exe);
        cmd.arg(HELPER_ARG)
            .arg(arg)
            .arg(format!("--locale={}", mmcl_i18n::current_locale().to_tag()))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }
        let mut child = cmd.spawn()?;
        let stdin = child
            .stdin
            .take()
            .ok_or_else(|| io::Error::other("no stdin"))?;
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| io::Error::other("no stdout"))?;
        if let Some(stderr) = child.stderr.take() {
            let label = kind.label();
            let _ = std::thread::Builder::new()
                .name("mica-rgb-helper-err".into())
                .spawn(move || {
                    for line in BufReader::new(stderr).lines().map_while(Result::ok) {
                        if !line.trim().is_empty() {
                            tracing::warn!(
                                "{}",
                                tf!("log.rgb.helper.stderr", label, line.as_str())
                            );
                        }
                    }
                });
        }
        Ok(HelperChild {
            stdin: Box::new(stdin),
            replies: reply_channel(kind, stdout),
            process: Box::new(OsProcess(child)),
        })
    }
}

/// A vendor-DLL backend running in an isolated helper process.
pub struct HelperBackend {
    kind: BackendKind,
    spawner: Arc<dyn HelperSpawner>,
    /// Off Windows the vendor DLLs never exist, so skip spawning entirely.
    windows_only: bool,
    child: Option<HelperChild>,
    available: Option<bool>,
    started: bool,
    respawns: u32,
}

impl HelperBackend {
    /// `kind` in a re-executed copy of this program.
    pub fn new(kind: BackendKind) -> HelperBackend {
        HelperBackend {
            kind,
            spawner: Arc::new(ProcessSpawner),
            windows_only: true,
            child: None,
            available: None,
            started: false,
            respawns: 0,
        }
    }

    /// `kind` through a custom spawner, on every OS (tests).
    pub fn with_spawner(kind: BackendKind, spawner: Arc<dyn HelperSpawner>) -> HelperBackend {
        HelperBackend {
            kind,
            spawner,
            windows_only: false,
            child: None,
            available: None,
            started: false,
            respawns: 0,
        }
    }

    /// Whether a helper process is running.
    pub fn has_child(&self) -> bool {
        self.child.is_some()
    }

    fn ensure_child(&mut self) -> RgbResult<()> {
        if self.child.is_some() {
            return Ok(());
        }
        let mut child = self.spawner.spawn(self.kind).map_err(|e| {
            tracing::warn!(
                "{}: {e}",
                tf!("log.rgb.helper.spawnFailed", self.kind.label())
            );
            RgbError::Io(e)
        })?;
        match child.replies.recv_timeout(SPAWN_TIMEOUT) {
            Ok(Reply::Ready { .. }) => {
                self.child = Some(child);
                Ok(())
            }
            other => {
                child.process.kill();
                Err(RgbError::protocol(format!(
                    "rgb helper did not start: {other:?}"
                )))
            }
        }
    }

    /// Sends `req` and waits for its reply. A timeout or a dead child kills the child.
    fn call(&mut self, req: &Request, timeout: Duration) -> RgbResult<Option<bool>> {
        self.ensure_child()?;
        let label = self.kind.label();
        let child = self
            .child
            .as_mut()
            .ok_or_else(|| RgbError::protocol("no helper"))?;
        let line = protocol::encode(req);
        let sent = writeln!(child.stdin, "{line}").and_then(|_| child.stdin.flush());
        let reply = match sent {
            Ok(()) => child.replies.recv_timeout(timeout),
            Err(_) => Err(RecvTimeoutError::Disconnected),
        };
        match reply {
            Ok(Reply::Ok { value }) => Ok(value),
            Ok(Reply::Err { msg }) => Err(RgbError::Backend(msg)),
            Ok(other) => Err(RgbError::protocol(format!(
                "unexpected helper reply {other:?}"
            ))),
            Err(RecvTimeoutError::Timeout) => {
                tracing::warn!(
                    "{}",
                    tf!(
                        "log.rgb.helper.timeout",
                        label,
                        req.op(),
                        timeout.as_millis().to_string()
                    )
                );
                self.kill_child();
                Err(RgbError::backend(format!(
                    "{label}: helper timed out on {}",
                    req.op()
                )))
            }
            Err(RecvTimeoutError::Disconnected) => {
                tracing::warn!("{}", tf!("log.rgb.helper.exited", label));
                self.kill_child();
                Err(RgbError::backend(format!("{label}: helper process exited")))
            }
        }
    }

    fn kill_child(&mut self) {
        if let Some(mut c) = self.child.take() {
            c.process.kill();
        }
    }

    /// Asks the child to exit, killing it if it does not within the grace period.
    fn close_child(&mut self) {
        if let Some(mut c) = self.child.take() {
            let _ = writeln!(c.stdin, "{}", protocol::encode(&Request::Exit))
                .and_then(|_| c.stdin.flush());
            drop(c.stdin);
            if !c.process.wait_timeout(EXIT_GRACE) {
                c.process.kill();
            }
        }
    }

    /// Restarts a crashed child and its session (bounded by [`MAX_RESPAWNS`]).
    fn revive(&mut self) -> RgbResult<()> {
        if self.respawns >= MAX_RESPAWNS {
            return Err(RgbError::backend(format!(
                "{}: helper crashed too often; not restarting it this session",
                self.kind.label()
            )));
        }
        self.respawns += 1;
        tracing::warn!(
            "{}",
            tf!(
                "log.rgb.helper.respawning",
                self.kind.label(),
                self.respawns.to_string(),
                MAX_RESPAWNS.to_string()
            )
        );
        if self.call(&Request::Available, AVAILABLE_TIMEOUT)? != Some(true) {
            return Err(RgbError::Unavailable(self.kind.label().into()));
        }
        self.call(&Request::Start, START_TIMEOUT).map(|_| ())
    }
}

impl RgbBackend for HelperBackend {
    fn name(&self) -> &str {
        self.kind.label()
    }

    fn is_available(&mut self) -> bool {
        if self.windows_only && !cfg!(windows) {
            return false;
        }
        if let Some(a) = self.available
            && (!a || self.child.is_some())
        {
            return a;
        }
        let a = matches!(
            self.call(&Request::Available, AVAILABLE_TIMEOUT),
            Ok(Some(true))
        );
        self.available = Some(a);
        if !a {
            self.close_child();
        }
        a
    }

    fn start(&mut self) -> RgbResult<()> {
        self.call(&Request::Start, START_TIMEOUT)?;
        self.started = true;
        Ok(())
    }

    fn render_frame(&mut self, frame: &RgbFrame) -> RgbResult<()> {
        if !self.started {
            return Ok(());
        }
        if self.child.is_none() {
            self.revive()?;
        }
        self.call(&Request::render(frame), RENDER_TIMEOUT)
            .map(|_| ())
    }

    fn shutdown(&mut self) {
        if self.started && self.child.is_some() {
            let _ = self.call(&Request::Shutdown, SHUTDOWN_TIMEOUT);
        }
        self.started = false;
        self.close_child();
    }

    fn kind(&self) -> BackendKind {
        self.kind
    }
}

impl Drop for HelperBackend {
    fn drop(&mut self) {
        self.close_child();
    }
}
