// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Port of `StdioProxyTest`, plus the relay fixes (401 mid-run answers, silent failing
//! notifications).

mod common;

use std::io::{BufReader, Write};
use std::path::Path;
use std::sync::mpsc;
use std::time::Duration;

use common::*;
use mmcl_mcp::McpServer;
use mmcl_mcp::endpoint::{self, Endpoint};
use mmcl_mcp::relay;
use parking_lot::Mutex;
use serde_json::{Value, json};

/// Collects complete stdout lines into a channel.
struct Lines {
    buf: Vec<u8>,
    tx: mpsc::Sender<String>,
}

impl Write for Lines {
    fn write(&mut self, b: &[u8]) -> std::io::Result<usize> {
        self.buf.extend_from_slice(b);
        while let Some(i) = self.buf.iter().position(|&c| c == b'\n') {
            let line: Vec<u8> = self.buf.drain(..=i).collect();
            let _ = self
                .tx
                .send(String::from_utf8_lossy(&line[..line.len() - 1]).into_owned());
        }
        Ok(b.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

struct Proxy {
    stdin: Option<std::io::PipeWriter>,
    out: mpsc::Receiver<String>,
    handle: Option<std::thread::JoinHandle<(i32, String)>>,
}

impl Proxy {
    fn spawn(endpoint_path: &Path) -> Proxy {
        let (reader, writer) = std::io::pipe().unwrap();
        let (tx, rx) = mpsc::channel();
        let path = endpoint_path.to_path_buf();
        let handle = std::thread::spawn(move || {
            let mut err = Vec::new();
            let code = relay::run(
                &path,
                BufReader::new(reader),
                Lines {
                    buf: Vec::new(),
                    tx,
                },
                &mut err,
            );
            (code, String::from_utf8_lossy(&err).into_owned())
        });
        Proxy {
            stdin: Some(writer),
            out: rx,
            handle: Some(handle),
        }
    }

    fn send(&mut self, line: &str) {
        let w = self.stdin.as_mut().unwrap();
        w.write_all(line.as_bytes()).unwrap();
        w.write_all(b"\n").unwrap();
        w.flush().unwrap();
    }

    fn recv(&self) -> Value {
        let l = self
            .out
            .recv_timeout(Duration::from_secs(20))
            .expect("a reply line");
        serde_json::from_str(&l).unwrap()
    }

    fn nothing_for(&self, ms: u64) -> bool {
        self.out.recv_timeout(Duration::from_millis(ms)).is_err()
    }

    fn close(mut self) -> (i32, String) {
        drop(self.stdin.take());
        self.handle.take().unwrap().join().unwrap()
    }
}

fn running() -> (McpServer, tempfile::TempDir, std::path::PathBuf) {
    let (gate, _e, _r) = Gate::new();
    // The gate's channels are dropped: get_modpack_manifest would just pass.
    let server = server_with(gate);
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("mcp-endpoint.json");
    server.start(0, Some(&path), "").unwrap();
    (server, dir, path)
}

#[test]
fn an_absent_or_stale_endpoint_exits_2_with_nothing_on_stdout() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("mcp-endpoint.json");
    let p = Proxy::spawn(&path);
    let (code, err) = p.close();
    assert_eq!(code, relay::EXIT_NO_LAUNCHER);
    assert!(err.contains("Enable MCP server"));
    endpoint::write(
        &path,
        &Endpoint {
            port: 1,
            token: "x".into(),
            pid: 2_147_483_000,
            launcher_version: String::new(),
        },
    )
    .unwrap();
    let p = Proxy::spawn(&path);
    assert!(p.nothing_for(100));
    assert_eq!(p.close().0, relay::EXIT_NO_LAUNCHER);
}

#[test]
fn ping_is_relayed_and_blank_lines_skipped() {
    let (_s, _d, path) = running();
    let mut p = Proxy::spawn(&path);
    p.send("");
    p.send("   ");
    p.send(r#"{"jsonrpc":"2.0","id":1,"method":"ping"}"#);
    assert_eq!(p.recv(), json!({"jsonrpc":"2.0","id":1,"result":{}}));
    assert_eq!(p.close().0, 0);
}

#[test]
fn the_session_is_carried_and_closed_on_eof() {
    let (server, _d, path) = running();
    let mut p = Proxy::spawn(&path);
    p.send(INIT);
    assert_eq!(p.recv()["result"]["protocolVersion"], json!("2025-06-18"));
    p.send(r#"{"jsonrpc":"2.0","method":"notifications/initialized"}"#);
    assert!(p.nothing_for(200), "a notification produces no output");
    p.send(r#"{"jsonrpc":"2.0","id":2,"method":"tools/list"}"#);
    assert!(p.recv()["result"]["tools"].is_array());
    assert_eq!(server.sessions().len(), 1);
    let (code, err) = p.close();
    assert_eq!(code, 0);
    assert!(server.sessions().is_empty(), "stdin close sends DELETE");
    assert!(!err.contains("jsonrpc"));
}

#[test]
fn a_dropped_session_is_transparently_re_established() {
    let (server, _d, path) = running();
    let mut p = Proxy::spawn(&path);
    p.send(INIT);
    p.recv();
    let sid = server.sessions()[0].id.clone();
    assert!(server.close_session(&sid));
    p.send(r#"{"jsonrpc":"2.0","id":3,"method":"tools/list"}"#);
    let v = p.recv();
    assert_eq!(v["id"], json!(3));
    assert!(v["result"]["tools"].is_array());
    let snap = server.sessions();
    assert_eq!(snap.len(), 1);
    assert_ne!(snap[0].id, sid);
    let (_, err) = p.close();
    assert!(err.contains("re-initialized"));
}

#[test]
fn malformed_json_is_answered_by_the_launcher() {
    let (_s, _d, path) = running();
    let mut p = Proxy::spawn(&path);
    p.send("{not json");
    let v = p.recv();
    assert_eq!(v["error"]["code"], json!(-32700));
    assert!(v["id"].is_null());
    p.close();
}

#[test]
fn launcher_gone_yields_an_error_with_the_id_and_notifications_stay_silent() {
    let (server, _d, path) = running();
    let mut p = Proxy::spawn(&path);
    p.send(r#"{"jsonrpc":"2.0","id":1,"method":"ping"}"#);
    p.recv();
    // Stop the listener but leave the endpoint file so the relay cannot re-read a new one.
    let saved = std::fs::read(&path).unwrap();
    server.stop();
    std::fs::write(&path, saved).unwrap();
    p.send(r#"{"jsonrpc":"2.0","id":"abc","method":"ping"}"#);
    let v = p.recv();
    assert_eq!(v["id"], json!("abc"));
    assert_eq!(v["error"]["code"], json!(-32603));
    assert_eq!(
        v["error"]["message"],
        json!("Could not reach the launcher. It may have been closed.")
    );
    p.send(r#"{"jsonrpc":"2.0","method":"notifications/initialized"}"#);
    assert!(p.nothing_for(300));
    let (_, err) = p.close();
    assert!(err.contains("MCP relay:"));
}

#[test]
fn a_401_mid_run_yields_an_error_line() {
    let (server, _d, path) = running();
    // The token changed (regenerated) while the relay still holds the old one.
    endpoint::write(
        &path,
        &Endpoint {
            port: server.port(),
            token: "0".repeat(64),
            pid: i64::from(std::process::id()),
            launcher_version: String::new(),
        },
    )
    .unwrap();
    let mut p = Proxy::spawn(&path);
    p.send(r#"{"jsonrpc":"2.0","method":"notifications/initialized"}"#);
    assert!(p.nothing_for(200), "a refused notification stays silent");
    p.send(r#"{"jsonrpc":"2.0","id":7,"method":"ping"}"#);
    let v = p.recv();
    assert_eq!(v["id"], json!(7));
    assert_eq!(v["error"]["code"], json!(-32603));
    assert!(v["error"]["message"].as_str().unwrap().contains("HTTP 401"));
    p.close();
}

#[test]
fn a_restarted_launcher_is_found_by_rereading_the_endpoint() {
    let (server, _d, path) = running();
    let mut p = Proxy::spawn(&path);
    p.send(r#"{"jsonrpc":"2.0","id":1,"method":"ping"}"#);
    p.recv();
    server.stop();
    server.start(0, Some(&path), "").unwrap();
    p.send(r#"{"jsonrpc":"2.0","id":2,"method":"ping"}"#);
    assert_eq!(p.recv(), json!({"jsonrpc":"2.0","id":2,"result":{}}));
    p.close();
}

#[test]
fn diagnostics_never_reach_stdout() {
    let lines = Mutex::new(Vec::<String>::new());
    let (_s, _d, path) = running();
    let mut p = Proxy::spawn(&path);
    p.send(r#"{"jsonrpc":"2.0","id":1,"method":"ping"}"#);
    lines.lock().push(serde_json::to_string(&p.recv()).unwrap());
    p.close();
    for l in lines.lock().iter() {
        assert!(serde_json::from_str::<Value>(l).is_ok());
    }
}
