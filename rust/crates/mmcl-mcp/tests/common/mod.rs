// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Shared fixtures for the loopback and relay integration tests.

#![allow(dead_code)]

use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpStream;
use std::sync::Arc;
use std::sync::mpsc;
use std::time::Duration;

use mmcl_mcp::McpServer;
use mmcl_mcp::activity::ActivityLog;
use mmcl_mcp::approval::Authorizer;
use mmcl_mcp::resources::{ResourceRegistry, launcher};
use mmcl_mcp::tools::{
    Args, CallContext, CrashInfo, LauncherStatus, LauncherView, PackFootprint, PackSummary,
    RunningGame, Tool, ToolRegistry, read_only,
};
use parking_lot::Mutex;

pub struct View;

impl LauncherView for View {
    fn packs(&self) -> Vec<PackSummary> {
        vec![PackSummary {
            friendly_name: "Alto".into(),
            version: "1.0".into(),
            latest_version: "1.0".into(),
            installed: true,
            mod_loader: "forge".into(),
            ..PackSummary::default()
        }]
    }
    fn manifest_of(&self, _: &str) -> Option<String> {
        Some("{}".into())
    }
    fn latest_crash_of(&self, _: &str) -> Option<CrashInfo> {
        None
    }
    fn footprint_of(&self, _: &str) -> Option<PackFootprint> {
        None
    }
    fn status(&self) -> LauncherStatus {
        LauncherStatus::default()
    }
    fn running_games(&self) -> Vec<RunningGame> {
        Vec::new()
    }
}

/// Allows everything except `get_crash_report` (denied) and `get_modpack_manifest`, which
/// blocks until [`Gate::release`] is called, standing in for a pending consent dialog.
pub struct Gate {
    pub entered: Mutex<Option<mpsc::Sender<()>>>,
    pub release: Mutex<Option<mpsc::Receiver<()>>>,
    pub calls: Mutex<Vec<String>>,
}

impl Gate {
    pub fn new() -> (Arc<Gate>, mpsc::Receiver<()>, mpsc::Sender<()>) {
        let (etx, erx) = mpsc::channel();
        let (rtx, rrx) = mpsc::channel();
        (
            Arc::new(Gate {
                entered: Mutex::new(Some(etx)),
                release: Mutex::new(Some(rrx)),
                calls: Mutex::new(Vec::new()),
            }),
            erx,
            rtx,
        )
    }
}

impl Authorizer for Gate {
    fn authorize(&self, tool: &dyn Tool, _: &CallContext, _: &Args) -> bool {
        self.calls.lock().push(tool.name().to_string());
        match tool.name() {
            "get_crash_report" => false,
            "get_modpack_manifest" => {
                if let Some(tx) = self.entered.lock().take() {
                    let _ = tx.send(());
                }
                let rx = self.release.lock().take();
                if let Some(rx) = rx {
                    let _ = rx.recv_timeout(Duration::from_secs(20));
                }
                true
            }
            _ => true,
        }
    }
}

pub fn server_with(auth: Arc<dyn Authorizer>) -> McpServer {
    let view: Arc<dyn LauncherView> = Arc::new(View);
    let mut tools = ToolRegistry::new();
    read_only::register_all(&mut tools, view.clone()).unwrap();
    let mut res = ResourceRegistry::new();
    launcher::register_all(&mut res, view).unwrap();
    McpServer::new(
        Arc::new(tools),
        Arc::new(res),
        auth,
        Arc::new(ActivityLog::new()),
        "1.0-test",
    )
}

pub struct Reply {
    pub status: u16,
    pub session: Option<String>,
    pub content_type: Option<String>,
    pub body: String,
}

/// One raw HTTP/1.1 exchange.
pub fn http(port: u16, method: &str, path: &str, headers: &[(&str, &str)], body: &[u8]) -> Reply {
    let mut s = TcpStream::connect(("127.0.0.1", port)).unwrap();
    s.set_read_timeout(Some(Duration::from_secs(30))).unwrap();
    let mut req = format!(
        "{method} {path} HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\nContent-Length: {}\r\n",
        body.len()
    );
    for (k, v) in headers {
        req.push_str(&format!("{k}: {v}\r\n"));
    }
    req.push_str("\r\n");
    s.write_all(req.as_bytes()).unwrap();
    // A rejected oversized upload may close early; that is what the test checks.
    let _ = s.write_all(body);
    let mut r = BufReader::new(s);
    let mut line = String::new();
    r.read_line(&mut line).unwrap();
    let status = line.split_whitespace().nth(1).unwrap().parse().unwrap();
    let mut session = None;
    let mut content_type = None;
    let mut len = 0usize;
    loop {
        line.clear();
        r.read_line(&mut line).unwrap();
        let l = line.trim_end();
        if l.is_empty() {
            break;
        }
        let (k, v) = l.split_once(':').unwrap();
        let v = v.trim().to_string();
        match k.to_ascii_lowercase().as_str() {
            "mcp-session-id" => session = Some(v),
            "content-type" => content_type = Some(v),
            "content-length" => len = v.parse().unwrap(),
            _ => {}
        }
    }
    let mut b = vec![0u8; len];
    r.read_exact(&mut b).unwrap();
    Reply {
        status,
        session,
        content_type,
        body: String::from_utf8(b).unwrap(),
    }
}

pub fn post(port: u16, token: &str, session: Option<&str>, body: &str) -> Reply {
    let auth = format!("Bearer {token}");
    let mut h = vec![
        ("Authorization", auth.as_str()),
        ("Content-Type", "application/json"),
    ];
    if let Some(s) = session {
        h.push(("Mcp-Session-Id", s));
    }
    http(port, "POST", "/mcp", &h, body.as_bytes())
}

pub fn json(r: &Reply) -> serde_json::Value {
    serde_json::from_str(&r.body).unwrap()
}

pub const INIT: &str = r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"clientInfo":{"name":"tester","version":"1"}}}"#;
