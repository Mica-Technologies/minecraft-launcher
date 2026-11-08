// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Where Discord's IPC endpoint lives and how to open it.
//!
//! Windows: the named pipe `\\.\pipe\discord-ipc-{0..9}`, opened with tokio's overlapped
//! client so a pending read on the reader task never blocks a write. Elsewhere: the Unix
//! socket `{dir}/discord-ipc-{0..9}`, where `dir` is the first set of `$XDG_RUNTIME_DIR`,
//! `$TMPDIR`, `$TMP`, `$TEMP` (else `/tmp`), also under the Flatpak and Snap subfolders.

use std::future::Future;
use std::io;
use std::path::PathBuf;
use std::pin::Pin;

use tokio::io::{AsyncRead, AsyncWrite};

/// How many numbered endpoints Discord may use (one per running client instance).
pub const ENDPOINT_COUNT: u32 = 10;

/// Environment variables naming the Unix socket folder, in order.
pub const UNIX_DIR_VARS: [&str; 4] = ["XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"];

/// Subfolders of that folder where sandboxed Discord builds put the socket.
pub const UNIX_SUBDIRS: [&str; 3] = ["", "app/com.discordapp.Discord", "snap.discord"];

/// A duplex byte stream to Discord.
pub trait IpcStream: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> IpcStream for T {}

/// A boxed [`IpcStream`].
pub type BoxStream = Box<dyn IpcStream>;

/// The future a [`Connector`] returns.
pub type ConnectFuture<'a> =
    Pin<Box<dyn Future<Output = io::Result<Option<(BoxStream, String)>>> + Send + 'a>>;

/// Opens the IPC endpoint. The seam tests replace with an in-memory stream.
pub trait Connector: Send + Sync {
    /// Opens the first endpoint that answers. `Ok(None)` means Discord is not running (no
    /// endpoint exists); the `String` names the endpoint for the log. Runs on the presence
    /// worker's tokio runtime.
    fn connect(&self) -> ConnectFuture<'_>;
}

/// The real endpoint: named pipe on Windows, Unix socket elsewhere.
#[derive(Debug, Default, Clone, Copy)]
pub struct SystemConnector;

/// The Windows pipe names, in the order they are tried.
pub fn windows_pipe_candidates() -> Vec<String> {
    (0..ENDPOINT_COUNT)
        .map(|i| format!(r"\\.\pipe\discord-ipc-{i}"))
        .collect()
}

/// The Unix socket folder: the first non-empty variable of [`UNIX_DIR_VARS`], else `/tmp`.
pub fn unix_socket_dir(env: impl Fn(&str) -> Option<String>) -> PathBuf {
    UNIX_DIR_VARS
        .iter()
        .find_map(|var| env(var).filter(|v| !v.is_empty()))
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/tmp"))
}

/// The Unix socket paths, in the order they are tried: endpoint 0 in every subfolder first,
/// then endpoint 1, and so on.
pub fn unix_socket_candidates(env: impl Fn(&str) -> Option<String>) -> Vec<PathBuf> {
    let dir = unix_socket_dir(env);
    let mut out = Vec::with_capacity((ENDPOINT_COUNT as usize) * UNIX_SUBDIRS.len());
    for i in 0..ENDPOINT_COUNT {
        for sub in UNIX_SUBDIRS {
            let base = if sub.is_empty() {
                dir.clone()
            } else {
                dir.join(sub)
            };
            out.push(base.join(format!("discord-ipc-{i}")));
        }
    }
    out
}

impl Connector for SystemConnector {
    fn connect(&self) -> ConnectFuture<'_> {
        Box::pin(connect_system())
    }
}

#[cfg(windows)]
async fn connect_system() -> io::Result<Option<(BoxStream, String)>> {
    use tokio::net::windows::named_pipe::ClientOptions;
    for name in windows_pipe_candidates() {
        // tokio opens the pipe with FILE_FLAG_OVERLAPPED.
        match ClientOptions::new().open(&name) {
            Ok(pipe) => return Ok(Some((Box::new(pipe) as BoxStream, name))),
            Err(e) => {
                tracing::trace!("Discord pipe {name}: {e}");
            }
        }
    }
    Ok(None)
}

#[cfg(unix)]
async fn connect_system() -> io::Result<Option<(BoxStream, String)>> {
    use tokio::net::UnixStream;
    for path in unix_socket_candidates(|k| std::env::var(k).ok()) {
        match UnixStream::connect(&path).await {
            Ok(s) => return Ok(Some((Box::new(s) as BoxStream, path.display().to_string()))),
            Err(e) => {
                tracing::trace!("Discord socket {}: {e}", path.display());
            }
        }
    }
    Ok(None)
}

#[cfg(not(any(windows, unix)))]
async fn connect_system() -> io::Result<Option<(BoxStream, String)>> {
    Ok(None)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn env(pairs: &[(&str, &str)]) -> impl Fn(&str) -> Option<String> {
        let map: HashMap<String, String> = pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect();
        move |k| map.get(k).cloned()
    }

    #[test]
    fn pipe_names_cover_zero_to_nine() {
        let names = windows_pipe_candidates();
        assert_eq!(names.len(), 10);
        assert_eq!(names[0], r"\\.\pipe\discord-ipc-0");
        assert_eq!(names[9], r"\\.\pipe\discord-ipc-9");
    }

    #[test]
    fn socket_dir_takes_the_first_set_variable() {
        assert_eq!(
            unix_socket_dir(env(&[("TMP", "/t"), ("XDG_RUNTIME_DIR", "/run/user/1000")])),
            PathBuf::from("/run/user/1000")
        );
        assert_eq!(
            unix_socket_dir(env(&[("XDG_RUNTIME_DIR", ""), ("TMPDIR", "/var/tmp")])),
            PathBuf::from("/var/tmp")
        );
        assert_eq!(unix_socket_dir(env(&[("TEMP", "/e")])), PathBuf::from("/e"));
        assert_eq!(unix_socket_dir(env(&[])), PathBuf::from("/tmp"));
    }

    #[test]
    fn socket_candidates_include_flatpak_and_snap() {
        let c = unix_socket_candidates(env(&[("XDG_RUNTIME_DIR", "/run/user/1000")]));
        assert_eq!(c.len(), 30);
        let base = PathBuf::from("/run/user/1000");
        assert_eq!(c[0], base.join("discord-ipc-0"));
        assert_eq!(
            c[1],
            base.join("app/com.discordapp.Discord")
                .join("discord-ipc-0")
        );
        assert_eq!(c[2], base.join("snap.discord").join("discord-ipc-0"));
        assert_eq!(c[3], base.join("discord-ipc-1"));
        assert_eq!(c[29], base.join("snap.discord").join("discord-ipc-9"));
    }
}
