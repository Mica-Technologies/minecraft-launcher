// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The endpoint file (Java `McpEndpointFile`): `<client config>/mcp-endpoint.json`, written
//! owner-only while the server runs, so the `--mcp` relay (started by a client from any working
//! directory) can find the port and token.
//!
//! The path is always the game-mode-independent client config folder
//! ([`mmcl_core::DataPaths::mcp_endpoint_file`]): a mode-dependent path would resolve
//! differently in the relay process and never agree.

use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use serde_json::{Map, Value};

/// The file name.
pub const FILENAME: &str = mmcl_core::paths::MCP_ENDPOINT_FILE_NAME;

/// What the file holds.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Endpoint {
    pub port: u16,
    pub token: String,
    /// The launcher's process id; `-1` when the file had none.
    pub pid: i64,
    /// `""` when the file had none.
    pub launcher_version: String,
}

impl Endpoint {
    /// Compact JSON in the Java key order: `port`, `token`, `pid`, `launcherVersion`.
    pub fn to_json(&self) -> String {
        let mut m = Map::new();
        m.insert("port".into(), Value::from(self.port));
        m.insert("token".into(), Value::String(self.token.clone()));
        m.insert("pid".into(), Value::from(self.pid));
        m.insert(
            "launcherVersion".into(),
            Value::String(self.launcher_version.clone()),
        );
        serde_json::to_string(&Value::Object(m)).unwrap_or_default()
    }
}

/// The default location: `<client config>/mcp-endpoint.json`.
pub fn default_path() -> PathBuf {
    mmcl_core::DataPaths::mcp_endpoint_file()
}

/// Writes the file (parents created), owner-only.
pub fn write(path: &Path, endpoint: &Endpoint) -> io::Result<()> {
    if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty()) {
        fs::create_dir_all(parent)?;
    }
    let mut opts = fs::OpenOptions::new();
    opts.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        opts.mode(0o600);
    }
    let mut f = opts.open(path)?;
    f.write_all(endpoint.to_json().as_bytes())?;
    f.flush()?;
    drop(f);
    mmcl_core::fsx::owner_only(path, false)
}

/// Reads the file; `None` when it is absent, not a regular file, unparsable, missing `port` or
/// `token`, the port is out of range or the token blank (a half-written file is a normal race).
pub fn read(path: &Path) -> Option<Endpoint> {
    let meta = fs::metadata(path).ok()?;
    if !meta.is_file() {
        return None;
    }
    let text = fs::read_to_string(path).ok()?;
    let v: Value = serde_json::from_str(&text).ok()?;
    let obj = v.as_object()?;
    let port = match obj.get("port")? {
        Value::Number(n) => n.as_i64()?,
        Value::String(s) => s.trim().parse::<i64>().ok()?,
        _ => return None,
    };
    if !(1..=65535).contains(&port) {
        return None;
    }
    let token = match obj.get("token")? {
        Value::String(s) => s.clone(),
        _ => return None,
    };
    if token.trim().is_empty() {
        return None;
    }
    let pid = match obj.get("pid") {
        Some(Value::Number(n)) => n.as_i64().unwrap_or(-1),
        Some(Value::String(s)) => s.trim().parse().unwrap_or(-1),
        _ => -1,
    };
    let launcher_version = match obj.get("launcherVersion") {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => n.to_string(),
        _ => String::new(),
    };
    Some(Endpoint {
        port: port as u16,
        token,
        pid,
        launcher_version,
    })
}

/// [`read`], then `None` when the recorded process is gone (a stale file from a crashed
/// launcher would send a client to a port nobody owns). A file with no pid counts as live.
pub fn read_live(path: &Path) -> Option<Endpoint> {
    let e = read(path)?;
    if e.pid > 0 && !is_process_alive(e.pid) {
        return None;
    }
    Some(e)
}

/// Deletes the file if it exists; a failure is logged.
pub fn delete(path: &Path) {
    match fs::remove_file(path) {
        Ok(()) => {}
        Err(e) if e.kind() == io::ErrorKind::NotFound => {}
        Err(e) => crate::logging::warn("log.mcpEndpointFile.removeFailed", &[&e.to_string()]),
    }
}

/// Whether a process with this id exists. PID reuse is an accepted residual risk.
pub fn is_process_alive(pid: i64) -> bool {
    if pid <= 0 {
        return false;
    }
    imp::alive(pid)
}

#[cfg(unix)]
mod imp {
    pub fn alive(pid: i64) -> bool {
        let Ok(pid) = libc::pid_t::try_from(pid) else {
            return false;
        };
        // SAFETY: signal 0 only checks for existence and permission.
        let rc = unsafe { libc::kill(pid, 0) };
        if rc == 0 {
            return true;
        }
        std::io::Error::last_os_error().raw_os_error() == Some(libc::EPERM)
    }
}

#[cfg(windows)]
mod imp {
    use windows_sys::Win32::Foundation::{
        CloseHandle, ERROR_ACCESS_DENIED, GetLastError, STILL_ACTIVE,
    };
    use windows_sys::Win32::System::Threading::{
        GetExitCodeProcess, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION,
    };

    pub fn alive(pid: i64) -> bool {
        let Ok(pid) = u32::try_from(pid) else {
            return false;
        };
        // SAFETY: plain Win32 calls; the handle is closed before returning.
        unsafe {
            let h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
            if h.is_null() {
                // A protected process exists but refuses even limited queries.
                return GetLastError() == ERROR_ACCESS_DENIED;
            }
            let mut code: u32 = 0;
            let ok = GetExitCodeProcess(h, &mut code) != 0;
            CloseHandle(h);
            ok && code == STILL_ACTIVE as u32
        }
    }
}

#[cfg(not(any(unix, windows)))]
mod imp {
    pub fn alive(_pid: i64) -> bool {
        true
    }
}

#[cfg(test)]
mod tests {
    //! Port of `McpEndpointFileTest`.
    use super::*;

    fn sample() -> Endpoint {
        Endpoint {
            port: 47833,
            token: "a".repeat(64),
            pid: i64::from(std::process::id()),
            launcher_version: "2026.11.1".into(),
        }
    }

    #[test]
    fn round_trips_and_creates_parents() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("config").join("deep").join(FILENAME);
        write(&p, &sample()).unwrap();
        assert_eq!(read(&p), Some(sample()));
        assert_eq!(read_live(&p), Some(sample()));
    }

    #[test]
    fn content_is_compact_in_java_key_order() {
        let s = sample().to_json();
        assert!(s.starts_with(r#"{"port":47833,"token":""#));
        assert!(s.contains(r#","pid":"#));
        assert!(s.ends_with(r#""launcherVersion":"2026.11.1"}"#));
    }

    #[cfg(unix)]
    #[test]
    fn the_file_is_owner_only() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join(FILENAME);
        write(&p, &sample()).unwrap();
        let mode = fs::metadata(&p).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600);
    }

    #[test]
    fn absent_dir_and_malformed_files_read_as_none() {
        let dir = tempfile::tempdir().unwrap();
        assert!(read(&dir.path().join("missing.json")).is_none());
        assert!(read(dir.path()).is_none());
        let p = dir.path().join("x.json");
        for bad in [
            "",
            "{",
            "[]",
            r#"{"token":"abc"}"#,
            r#"{"port":47833}"#,
            r#"{"port":0,"token":"abc"}"#,
            r#"{"port":70000,"token":"abc"}"#,
            r#"{"port":47833,"token":"  "}"#,
            r#"{"port":true,"token":"abc"}"#,
        ] {
            fs::write(&p, bad).unwrap();
            assert!(read(&p).is_none(), "{bad}");
        }
    }

    #[test]
    fn missing_pid_and_version_take_defaults_and_count_as_live() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("x.json");
        fs::write(&p, r#"{"port":5,"token":"abc"}"#).unwrap();
        let e = read_live(&p).unwrap();
        assert_eq!(e.pid, -1);
        assert_eq!(e.launcher_version, "");
    }

    #[test]
    fn a_dead_pid_reads_as_absent() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("x.json");
        // Far above any real pid on every OS this builds for.
        fs::write(&p, r#"{"port":5,"token":"abc","pid":2147483000}"#).unwrap();
        assert!(read(&p).is_some());
        assert!(read_live(&p).is_none());
    }

    #[test]
    fn the_current_process_is_alive_and_invalid_pids_are_not() {
        assert!(is_process_alive(i64::from(std::process::id())));
        assert!(!is_process_alive(0));
        assert!(!is_process_alive(-5));
        assert!(!is_process_alive(i64::MAX));
    }

    #[test]
    fn delete_is_idempotent() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join(FILENAME);
        write(&p, &sample()).unwrap();
        delete(&p);
        assert!(!p.exists());
        delete(&p);
    }
}
