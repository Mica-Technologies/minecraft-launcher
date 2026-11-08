// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The single-instance lock and its IPC (`SingleInstanceLock`, `WindowsForeground`).
//!
//! The lock is a TCP listener on `127.0.0.1:<port>` (this identity's
//! [`single_instance_port`](mmcl_core::AppIdentity::single_instance_port): 47831, dev 47832,
//! so it never collides with an installed Java launcher). The OS frees the port on any exit,
//! so there are no stale lock files. On Windows the socket is bound with `SO_EXCLUSIVEADDRUSE`
//! like Java's `ServerSocket`, so no other process can co-bind with `SO_REUSEADDR`.
//!
//! A second launch forwards its request (`mmcl://open` for a plain launch, or a deep link) to
//! the holder: it sends `token\npayload\n`, where the token is 32 random bytes in lowercase hex
//! that the holder wrote to `<client config>/single-instance.token` (owner-only). The holder
//! answers `OK WINDOW`, `OK NOWINDOW` or `REJECTED`. Older launchers hang up without replying.
//!
//! **Guard (issue #92):** a second launch whose request was not delivered must tell the user
//! why ([`ForwardResult::user_message_key`]) instead of exiting silently.

use std::io::{self, BufRead, BufReader, Write};
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::JoinHandle;
use std::time::Duration;

use parking_lot::Mutex;
use rand::RngCore;
use socket2::{Domain, Protocol, Socket, Type};

use crate::host::{self, PlatformHost};

/// Read timeout on both ends of the IPC.
pub const IPC_TIMEOUT: Duration = Duration::from_secs(2);

/// Listen backlog of the lock socket.
pub const BACKLOG: i32 = 8;

/// The payload a plain second launch sends.
pub const OPEN_PAYLOAD: &str = "mmcl://open";

const REPLY_OK: &str = "OK";
const REPLY_WINDOW: &str = "WINDOW";
const REPLY_NO_WINDOW: &str = "NOWINDOW";
const REPLY_REJECTED: &str = "REJECTED";

/// How the accept loop polls for shutdown.
const ACCEPT_POLL: Duration = Duration::from_millis(50);

/// What happened to a forwarded request.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ForwardResult {
    /// The holder accepted it and has a window.
    Delivered,
    /// The holder accepted it but its window has not opened yet.
    DeliveredNoWindow,
    /// The holder hung up without replying (an older launcher) or replied something unknown.
    Unconfirmed,
    /// No token file: nothing to authenticate with.
    NoToken,
    /// Nothing answered on the port, or it stayed silent past the timeout.
    Unreachable,
    /// The holder refused the token (a stale token file).
    Rejected,
}

impl ForwardResult {
    /// `true` when the second launch may exit quietly: `Delivered` and `Unconfirmed`.
    pub fn delivered(self) -> bool {
        matches!(self, ForwardResult::Delivered | ForwardResult::Unconfirmed)
    }

    /// The localization key of the message a second launch shows for a result that was not
    /// delivered, or `None` when it was.
    pub fn user_message_key(self) -> Option<&'static str> {
        match self {
            ForwardResult::Delivered | ForwardResult::Unconfirmed => None,
            ForwardResult::DeliveredNoWindow => Some("startup.alreadyRunning.noWindow"),
            ForwardResult::NoToken | ForwardResult::Unreachable | ForwardResult::Rejected => {
                Some("startup.alreadyRunning.unreachable")
            }
        }
    }
}

/// Interprets the holder's one-line reply (`None` = hang-up without a reply).
pub fn parse_reply(reply: Option<&str>) -> ForwardResult {
    let Some(reply) = reply else {
        return ForwardResult::Unconfirmed;
    };
    let words: Vec<&str> = reply.trim().split(' ').collect();
    if words.len() == 1 && words[0] == REPLY_REJECTED {
        return ForwardResult::Rejected;
    }
    if words.len() == 2 && words[0] == REPLY_OK {
        return if words[1] == REPLY_NO_WINDOW {
            ForwardResult::DeliveredNoWindow
        } else {
            ForwardResult::Delivered
        };
    }
    ForwardResult::Unconfirmed
}

/// Constant-time comparison of two tokens over their UTF-8 bytes. `None` on either side is
/// never equal. Case-sensitive.
pub fn slow_equals(a: Option<&str>, b: Option<&str>) -> bool {
    let (Some(a), Some(b)) = (a, b) else {
        return false;
    };
    let (a, b) = (a.as_bytes(), b.as_bytes());
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b) {
        diff |= x ^ y;
    }
    diff == 0
}

/// A fresh IPC token: 32 random bytes as lowercase hex.
pub fn new_token() -> String {
    let mut bytes = [0u8; 32];
    rand::rng().fill_bytes(&mut bytes);
    hex::encode(bytes)
}

struct Held {
    port: u16,
    stop: Arc<AtomicBool>,
    accept: Option<JoinHandle<()>>,
    token: Arc<str>,
}

/// One single-instance lock: a port and a token file. The process-wide lock is
/// [`SingleInstance::global`]; tests make their own on an ephemeral port.
pub struct SingleInstance {
    port: u16,
    token_file: PathBuf,
    host_override: Option<Arc<dyn PlatformHost>>,
    held: Mutex<Option<Held>>,
    last_failure: Mutex<Option<io::Error>>,
}

impl SingleInstance {
    /// A lock on `port` (0 picks an ephemeral port, for tests) with its token at `token_file`.
    pub fn new(port: u16, token_file: impl Into<PathBuf>) -> Self {
        SingleInstance {
            port,
            token_file: token_file.into(),
            host_override: None,
            held: Mutex::new(None),
            last_failure: Mutex::new(None),
        }
    }

    /// Uses `host` for incoming requests instead of the global [`host::host`].
    pub fn with_host(mut self, host: Arc<dyn PlatformHost>) -> Self {
        self.host_override = Some(host);
        self
    }

    /// The process-wide lock for this identity: its port, and the token under the *client*
    /// config folder whatever the game mode (so a forwarder started from another working
    /// directory finds it).
    pub fn global() -> &'static SingleInstance {
        static GLOBAL: std::sync::OnceLock<SingleInstance> = std::sync::OnceLock::new();
        GLOBAL.get_or_init(|| {
            SingleInstance::new(
                mmcl_core::AppIdentity::current().single_instance_port,
                mmcl_core::DataPaths::single_instance_token_file(),
            )
        })
    }

    /// The token file.
    pub fn token_file(&self) -> &Path {
        &self.token_file
    }

    /// The port this lock listens on: the bound port while held (useful with port 0), else
    /// the configured one.
    pub fn port(&self) -> u16 {
        self.held.lock().as_ref().map_or(self.port, |h| h.port)
    }

    /// Whether this process holds the lock.
    pub fn is_held(&self) -> bool {
        self.held.lock().is_some()
    }

    /// Takes the lock. Idempotent: `true` when this process already holds it (the restart loop
    /// calls it again). On success writes the token and starts the accept thread. On failure
    /// keeps the error for [`last_acquire_failure`](Self::last_acquire_failure); on Windows an
    /// access error usually means a Hyper-V/WSL reserved port range.
    pub fn try_acquire(&self) -> bool {
        let mut held = self.held.lock();
        if held.is_some() {
            return true;
        }
        let listener = match bind_exclusive(self.port) {
            Ok(l) => l,
            Err(e) => {
                *self.last_failure.lock() = Some(e);
                return false;
            }
        };
        let port = listener.local_addr().map(|a| a.port()).unwrap_or(self.port);
        let token: Arc<str> = write_token(&self.token_file).into();
        let stop = Arc::new(AtomicBool::new(false));
        let accept = {
            let stop = stop.clone();
            let token = token.clone();
            let host_override = self.host_override.clone();
            std::thread::Builder::new()
                .name("single-instance-ipc".into())
                .spawn(move || accept_loop(listener, stop, token, host_override))
        };
        let accept = match accept {
            Ok(h) => Some(h),
            Err(e) => {
                *self.last_failure.lock() = Some(e);
                let _ = std::fs::remove_file(&self.token_file);
                return false;
            }
        };
        tracing::debug!(
            "{}",
            mmcl_i18n::tf!("log.singleInstance.lockAcquired", port.to_string())
        );
        *held = Some(Held {
            port,
            stop,
            accept,
            token,
        });
        true
    }

    /// The bind error of the last failed [`try_acquire`](Self::try_acquire), as text.
    pub fn last_acquire_failure(&self) -> Option<String> {
        self.last_failure.lock().as_ref().map(|e| e.to_string())
    }

    /// The raw OS error kind of the last failed bind.
    pub fn last_acquire_failure_kind(&self) -> Option<io::ErrorKind> {
        self.last_failure.lock().as_ref().map(io::Error::kind)
    }

    /// Releases the lock: closes the listener (the accept loop ends and the port is free when
    /// this returns), forgets the token and deletes the token file.
    pub fn release(&self) {
        let held = self.held.lock().take();
        if let Some(mut h) = held {
            h.stop.store(true, Ordering::SeqCst);
            if let Some(t) = h.accept.take() {
                let _ = t.join();
            }
            drop(h.token);
            let _ = std::fs::remove_file(&self.token_file);
        }
    }

    /// Forwards `payload` to the process holding this lock. See [`ForwardResult`].
    pub fn forward(&self, payload: Option<&str>) -> ForwardResult {
        forward_to(self.port, &self.token_file, payload)
    }
}

/// Takes the process-wide lock ([`SingleInstance::try_acquire`]).
pub fn try_acquire() -> bool {
    SingleInstance::global().try_acquire()
}

/// Forwards `payload` to the running instance ([`SingleInstance::forward`]).
pub fn forward(payload: &str) -> ForwardResult {
    SingleInstance::global().forward(Some(payload))
}

/// Releases the process-wide lock.
pub fn release() {
    SingleInstance::global().release()
}

fn bind_exclusive(port: u16) -> io::Result<TcpListener> {
    let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP))?;
    #[cfg(windows)]
    crate::win::set_exclusive_addr_use(&socket)?;
    let addr = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::LOCALHOST, port));
    socket.bind(&addr.into())?;
    socket.listen(BACKLOG)?;
    let listener: TcpListener = socket.into();
    listener.set_nonblocking(true)?;
    Ok(listener)
}

fn write_token(file: &Path) -> String {
    let token = new_token();
    let write = || -> io::Result<()> {
        if let Some(parent) = file.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(file, token.as_bytes())?;
        mmcl_core::fsx::owner_only(file, false)?;
        Ok(())
    };
    if let Err(e) = write() {
        tracing::warn!(
            "{}",
            mmcl_i18n::tf!(
                "log.singleInstance.tokenPersistFailed",
                format!("{:?}", e.kind())
            )
        );
    }
    token
}

fn read_token(file: &Path) -> Option<String> {
    if !file.is_file() {
        return None;
    }
    std::fs::read_to_string(file)
        .ok()
        .map(|s| s.trim().to_string())
}

fn accept_loop(
    listener: TcpListener,
    stop: Arc<AtomicBool>,
    token: Arc<str>,
    host_override: Option<Arc<dyn PlatformHost>>,
) {
    while !stop.load(Ordering::SeqCst) {
        match listener.accept() {
            Ok((stream, _)) => {
                let token = token.clone();
                let host = host_override.clone();
                mmcl_util::tasks::spawn(move || {
                    crate::guard(|| handle_incoming(stream, &token, host.or_else(host::host)))
                });
            }
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => std::thread::sleep(ACCEPT_POLL),
            Err(e) if e.kind() == io::ErrorKind::Interrupted => {}
            Err(_) => break,
        }
    }
    // Dropping the listener closes the port.
}

fn handle_incoming(stream: TcpStream, expected: &str, host: Option<Arc<dyn PlatformHost>>) {
    let _ = stream.set_nonblocking(false);
    let _ = stream.set_read_timeout(Some(IPC_TIMEOUT));
    let Ok(read_half) = stream.try_clone() else {
        return;
    };
    let mut reader = BufReader::new(read_half);
    let mut writer = stream;

    let token_line = read_line(&mut reader);
    let token_ok = token_line
        .as_deref()
        .is_some_and(|t| !expected.is_empty() && slow_equals(Some(expected), Some(t.trim())));
    if !token_ok {
        tracing::warn!("{}", mmcl_i18n::t!("log.singleInstance.badToken"));
        reply(&mut writer, REPLY_REJECTED);
        return;
    }

    let Some(line) = read_line(&mut reader) else {
        return;
    };
    let line = line.trim();
    if line.is_empty() {
        return;
    }

    let has_window = host.as_ref().is_some_and(|h| h.has_main_window());
    reply(
        &mut writer,
        &format!(
            "{REPLY_OK} {}",
            if has_window {
                REPLY_WINDOW
            } else {
                REPLY_NO_WINDOW
            }
        ),
    );

    if let Some(h) = &host {
        h.focus_window();
    }
    if mmcl_util::deeplink::is_launcher_uri(Some(line)) {
        tracing::debug!(
            "{}",
            mmcl_i18n::tf!("log.singleInstance.dispatchDeepLink", line)
        );
        if let Some(h) = &host {
            h.handle_deep_link(line);
        }
    } else {
        tracing::warn!(
            "{}",
            mmcl_i18n::tf!("log.singleInstance.unrecognizedPayload", line)
        );
    }
}

/// One line without its terminator; `None` at EOF or on an error.
fn read_line(reader: &mut impl BufRead) -> Option<String> {
    let mut s = String::new();
    match reader.read_line(&mut s) {
        Ok(0) | Err(_) => None,
        Ok(_) => Some(s.trim_end_matches(['\n', '\r']).to_string()),
    }
}

/// Writes one reply line. Errors are ignored: an older sender hangs up early, and its request
/// must still run.
fn reply(writer: &mut impl Write, line: &str) {
    let _ = writer.write_all(format!("{line}\n").as_bytes());
    let _ = writer.flush();
}

fn forward_to(port: u16, token_file: &Path, payload: Option<&str>) -> ForwardResult {
    let Some(payload) = payload else {
        return ForwardResult::Unreachable;
    };
    let token = match read_token(token_file) {
        Some(t) if !t.trim().is_empty() => t,
        _ => return ForwardResult::NoToken,
    };
    let safe_payload = payload.replace(['\n', '\r'], " ");

    // The holder runs in the background and could only flash its taskbar button; this process
    // was started by the user's click and may pass on the right to take the foreground.
    #[cfg(windows)]
    crate::win::allow_any_foreground();

    let addr = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::LOCALHOST, port));
    let Ok(mut stream) = TcpStream::connect_timeout(&addr, IPC_TIMEOUT) else {
        return ForwardResult::Unreachable;
    };
    let _ = stream.set_read_timeout(Some(IPC_TIMEOUT));
    if stream
        .write_all(format!("{token}\n{safe_payload}\n").as_bytes())
        .and_then(|_| stream.flush())
        .is_err()
    {
        return ForwardResult::Unreachable;
    }
    let mut reader = BufReader::new(stream);
    let mut line = String::new();
    let reply = match reader.read_line(&mut line) {
        Ok(0) => None,
        Ok(_) => Some(line),
        Err(e)
            if matches!(
                e.kind(),
                io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
            ) =>
        {
            // Something that stays silent is not a launcher.
            return ForwardResult::Unreachable;
        }
        Err(_) => None,
    };
    parse_reply(reply.as_deref())
}

#[cfg(test)]
mod tests {
    use super::ForwardResult::*;
    use super::*;

    // ---- SingleInstanceLockReplyTest ----

    #[test]
    fn a_holder_with_a_window_is_delivered() {
        assert_eq!(parse_reply(Some("OK WINDOW")), Delivered);
    }

    #[test]
    fn a_holder_without_a_window_is_reported() {
        assert_eq!(parse_reply(Some("OK NOWINDOW")), DeliveredNoWindow);
    }

    #[test]
    fn trailing_whitespace_is_tolerated() {
        assert_eq!(parse_reply(Some("OK NOWINDOW \r")), DeliveredNoWindow);
    }

    #[test]
    fn no_reply_means_an_older_launcher_and_counts_as_delivered() {
        assert_eq!(parse_reply(None), Unconfirmed);
        assert!(Unconfirmed.delivered());
    }

    #[test]
    fn a_rejected_token_is_reported_not_treated_as_delivered() {
        assert_eq!(parse_reply(Some("REJECTED")), Rejected);
        assert!(!Rejected.delivered());
    }

    #[test]
    fn an_unknown_reply_is_unconfirmed() {
        assert_eq!(parse_reply(Some("HELLO")), Unconfirmed);
        assert_eq!(parse_reply(Some("")), Unconfirmed);
    }

    #[test]
    fn only_delivered_results_let_the_second_launch_exit_quietly() {
        assert!(Delivered.delivered());
        assert!(!DeliveredNoWindow.delivered());
        assert!(!NoToken.delivered());
        assert!(!Unreachable.delivered());
        for r in [DeliveredNoWindow, NoToken, Unreachable, Rejected] {
            let key = r.user_message_key().unwrap();
            assert!(mmcl_i18n::contains(key), "{key}");
        }
        assert_eq!(Delivered.user_message_key(), None);
    }

    // ---- SingleInstanceLockSlowEqualsTest ----

    #[test]
    fn accepts_identical_tokens() {
        assert!(slow_equals(Some("abc123"), Some("abc123")));
    }

    #[test]
    fn rejects_different_tokens_of_same_length() {
        assert!(!slow_equals(Some("abc123"), Some("abc124")));
    }

    #[test]
    fn rejects_tokens_of_different_length() {
        assert!(!slow_equals(Some("abc123"), Some("abc1234")));
        assert!(!slow_equals(Some("abc1234"), Some("abc123")));
    }

    #[test]
    fn rejects_when_either_argument_is_null() {
        assert!(!slow_equals(None, Some("abc123")));
        assert!(!slow_equals(Some("abc123"), None));
        assert!(!slow_equals(None, None));
    }

    #[test]
    fn is_case_sensitive() {
        assert!(!slow_equals(Some("ABC123"), Some("abc123")));
    }

    #[test]
    fn empty_strings_are_equal_to_each_other() {
        assert!(slow_equals(Some(""), Some("")));
    }

    #[test]
    fn compares_full_utf8_byte_length_not_just_char_length() {
        assert!(slow_equals(Some("café"), Some("café")));
        assert!(!slow_equals(Some("café"), Some("cafe")));
    }

    #[test]
    fn tokens_are_64_lowercase_hex_chars() {
        let t = new_token();
        assert_eq!(t.len(), 64);
        assert!(
            t.chars()
                .all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase())
        );
        assert_ne!(t, new_token());
    }

    // ---- loopback round trips ----

    #[derive(Default)]
    struct Recorder {
        window: bool,
        links: Mutex<Vec<String>>,
        focused: AtomicBool,
    }

    impl PlatformHost for Recorder {
        fn has_main_window(&self) -> bool {
            self.window
        }
        fn focus_window(&self) {
            self.focused.store(true, Ordering::SeqCst);
        }
        fn handle_deep_link(&self, uri: &str) {
            self.links.lock().push(uri.to_string());
        }
    }

    fn wait_for(cond: impl Fn() -> bool) -> bool {
        for _ in 0..100 {
            if cond() {
                return true;
            }
            std::thread::sleep(Duration::from_millis(20));
        }
        false
    }

    #[test]
    fn forwarding_reaches_the_holder_and_dispatches_the_link() {
        let dir = tempfile::tempdir().unwrap();
        let rec = Arc::new(Recorder {
            window: true,
            ..Default::default()
        });
        let lock = SingleInstance::new(0, dir.path().join("config").join("single-instance.token"))
            .with_host(rec.clone());
        assert!(lock.try_acquire());
        assert!(lock.try_acquire(), "idempotent");
        assert!(lock.token_file().is_file());

        let forwarder = SingleInstance::new(lock.port(), lock.token_file().to_path_buf());
        let result = forwarder.forward(Some("mmcl://play?name=Pack\nX"));
        assert_eq!(result, Delivered);
        assert!(wait_for(|| !rec.links.lock().is_empty()));
        assert_eq!(rec.links.lock()[0], "mmcl://play?name=Pack X");
        assert!(rec.focused.load(Ordering::SeqCst));

        lock.release();
        assert!(!lock.token_file().exists());
        assert!(!lock.is_held());
    }

    #[test]
    fn a_holder_without_a_window_says_so() {
        let dir = tempfile::tempdir().unwrap();
        let rec = Arc::new(Recorder::default());
        let lock = SingleInstance::new(0, dir.path().join("t.token")).with_host(rec.clone());
        assert!(lock.try_acquire());
        let fwd = SingleInstance::new(lock.port(), lock.token_file().to_path_buf());
        assert_eq!(fwd.forward(Some(OPEN_PAYLOAD)), DeliveredNoWindow);
        lock.release();
    }

    #[test]
    fn a_wrong_token_is_rejected_and_nothing_runs() {
        let dir = tempfile::tempdir().unwrap();
        let rec = Arc::new(Recorder::default());
        let lock = SingleInstance::new(0, dir.path().join("t.token")).with_host(rec.clone());
        assert!(lock.try_acquire());
        let stale = dir.path().join("stale.token");
        std::fs::write(&stale, "0000").unwrap();
        let fwd = SingleInstance::new(lock.port(), stale);
        assert_eq!(fwd.forward(Some("mmcl://open")), Rejected);
        std::thread::sleep(Duration::from_millis(100));
        assert!(rec.links.lock().is_empty());
        lock.release();
    }

    #[test]
    fn missing_tokens_and_payloads_and_dead_ports_are_reported() {
        let dir = tempfile::tempdir().unwrap();
        let fwd = SingleInstance::new(1, dir.path().join("none.token"));
        assert_eq!(fwd.forward(Some("mmcl://open")), NoToken);
        assert_eq!(fwd.forward(None), Unreachable);

        // A port that was just freed: nothing listens there.
        let port = {
            let l = TcpListener::bind("127.0.0.1:0").unwrap();
            l.local_addr().unwrap().port()
        };
        let tok = dir.path().join("t.token");
        std::fs::write(&tok, "abc").unwrap();
        assert_eq!(
            SingleInstance::new(port, tok).forward(Some("mmcl://open")),
            Unreachable
        );
    }

    #[test]
    fn a_second_lock_on_the_same_port_fails_and_release_frees_it() {
        let dir = tempfile::tempdir().unwrap();
        let first = SingleInstance::new(0, dir.path().join("a.token"));
        assert!(first.try_acquire());
        let second = SingleInstance::new(first.port(), dir.path().join("b.token"));
        assert!(!second.try_acquire());
        assert!(second.last_acquire_failure().is_some());
        let port = first.port();
        first.release();
        let third = SingleInstance::new(port, dir.path().join("c.token"));
        assert!(third.try_acquire(), "the port is free once release returns");
        third.release();
    }

    #[test]
    fn a_silent_listener_is_unreachable() {
        let dir = tempfile::tempdir().unwrap();
        let silent = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = silent.local_addr().unwrap().port();
        let keep = std::thread::spawn(move || {
            let (s, _) = silent.accept().unwrap();
            std::thread::sleep(IPC_TIMEOUT + Duration::from_millis(500));
            drop(s);
        });
        let tok = dir.path().join("t.token");
        std::fs::write(&tok, "abc").unwrap();
        assert_eq!(
            SingleInstance::new(port, tok).forward(Some("mmcl://open")),
            Unreachable
        );
        keep.join().unwrap();
    }
}
