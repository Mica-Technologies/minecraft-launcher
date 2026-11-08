// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Razer Chroma over the legacy REST API (Java `ChromaRestBackend`). Deprecated: current
//! Synapse returns result 126 on every push, so Auto mode leaves it off by default.

use std::collections::HashSet;
use std::net::{TcpStream, ToSocketAddrs};
use std::sync::mpsc::{self, RecvTimeoutError, Sender};
use std::thread::JoinHandle;
use std::time::Duration;

use mmcl_i18n::{t, tf};

use super::http;
use super::layout::{self, Grid};
use crate::backend::{BackendKind, RgbBackend};
use crate::color::RgbColor;
use crate::error::{RgbError, RgbResult};
use crate::frame::RgbFrame;

pub const INIT_ENDPOINT: &str = "http://localhost:54235/razer/chromasdk";
pub const PROBE_TIMEOUT: Duration = Duration::from_millis(500);
pub const HTTP_TIMEOUT: Duration = Duration::from_millis(1000);
pub const HEARTBEAT_INTERVAL: Duration = Duration::from_millis(10_000);
/// The endpoints that get a static colour (the keyboard gets the custom grid).
pub const NON_KEYBOARD_ENDPOINTS: [&str; 5] =
    ["/mouse", "/mousepad", "/headset", "/keypad", "/chromalink"];

/// The `POST /razer/chromasdk` registration body.
pub fn init_body() -> String {
    serde_json::json!({
        "title": "Mica Minecraft Launcher",
        "description": "Modpack-aware RGB lighting for Minecraft sessions.",
        "author": {
            "name": "Mica Technologies",
            "contact": "https://github.com/Mica-Technologies/minecraft-launcher"
        },
        "device_supported": ["keyboard", "mouse", "mousepad", "headset", "keypad", "chromalink"],
        "category": "application"
    })
    .to_string()
}

/// `{"effect":"CHROMA_CUSTOM","param":[[c,...22],...6 rows]}` (decimal ints).
pub fn keyboard_body(grid: &Grid) -> String {
    let mut s = String::with_capacity(4096);
    s.push_str("{\"effect\":\"CHROMA_CUSTOM\",\"param\":[");
    for (r, row) in grid.iter().enumerate() {
        if r > 0 {
            s.push(',');
        }
        s.push('[');
        for (c, v) in row.iter().enumerate() {
            if c > 0 {
                s.push(',');
            }
            s.push_str(&v.to_string());
        }
        s.push(']');
    }
    s.push_str("]}");
    s
}

/// `{"effect":"CHROMA_STATIC","param":{"color":C}}`.
pub fn static_body(packed: u32) -> String {
    format!("{{\"effect\":\"CHROMA_STATIC\",\"param\":{{\"color\":{packed}}}}}")
}

/// The `result` field of a reply: blank body or no field = 0, unparseable = -1.
pub fn parse_result(body: &str) -> i64 {
    if body.trim().is_empty() {
        return 0;
    }
    match serde_json::from_str::<serde_json::Value>(body) {
        Ok(serde_json::Value::Object(o)) => match o.get("result") {
            None => 0,
            Some(v) => v.as_i64().unwrap_or(-1),
        },
        Ok(_) => 0,
        Err(_) => -1,
    }
}

/// Label for a REST `result` code.
pub fn describe_result(result: i64) -> &'static str {
    match result {
        0 => "SUCCESS",
        1 => "RZRESULT_FAILED",
        5 => "ACCESS_DENIED (check Chroma > Apps in Synapse and enable SDK access)",
        50 => "NOT_SUPPORTED (effect type not valid for this device)",
        87 => "INVALID_PARAMETER (malformed payload)",
        126 => "MOD_NOT_FOUND (Synapse cannot find the Chroma module for this device)",
        1168 => "NOT_FOUND (device not registered with Synapse)",
        1247 => "ALREADY_INITIALIZED",
        4309 => "RESOURCE_DISABLED",
        4319 => "DEVICE_NOT_AVAILABLE (no device of this type connected)",
        -1 => "generic SDK failure",
        -57 => "NOT_VALID_STATE (session dropped)",
        -1_073_741_275 => "invalid parameter",
        _ => "unknown Chroma result code (see Razer SDK docs)",
    }
}

/// The keys of the result-126 hint, logged once per session in this order (`""` = blank line).
pub const HINT_126_KEYS: [&str; 23] = [
    "log.rgb.chroma.hint126.rejecting",
    "log.rgb.chroma.hint126.synapseIssue",
    "",
    "log.rgb.chroma.hint126.background",
    "log.rgb.chroma.hint126.cppSdk",
    "log.rgb.chroma.hint126.restApi",
    "log.rgb.chroma.hint126.deprecated1",
    "log.rgb.chroma.hint126.deprecated2",
    "log.rgb.chroma.hint126.deprecated3",
    "log.rgb.chroma.hint126.deprecated4",
    "log.rgb.chroma.hint126.deprecated5",
    "",
    "log.rgb.chroma.hint126.worthChecking",
    "log.rgb.chroma.hint126.checkSdkAccess",
    "log.rgb.chroma.hint126.checkAppVisible",
    "log.rgb.chroma.hint126.checkRestart",
    "",
    "log.rgb.chroma.hint126.recommendedFix1",
    "log.rgb.chroma.hint126.recommendedFix2",
    "log.rgb.chroma.hint126.recommendedFix3",
    "log.rgb.chroma.hint126.recommendedFix4",
    "log.rgb.chroma.hint126.recommendedFix5",
    "log.rgb.chroma.hint126.recommendedFix6",
];

/// The Chroma REST backend.
pub struct ChromaRestBackend {
    init_url: String,
    session_uri: Option<String>,
    heartbeat: Option<(Sender<()>, JoinHandle<()>)>,
    succeeded_once: HashSet<&'static str>,
    hint_logged: bool,
}

impl ChromaRestBackend {
    pub fn new() -> ChromaRestBackend {
        ChromaRestBackend::with_init_url(INIT_ENDPOINT)
    }

    /// Another init endpoint (tests).
    pub fn with_init_url(url: &str) -> ChromaRestBackend {
        ChromaRestBackend {
            init_url: url.to_string(),
            session_uri: None,
            heartbeat: None,
            succeeded_once: HashSet::new(),
            hint_logged: false,
        }
    }

    fn send_effect(&mut self, endpoint: &'static str, body: &str) -> RgbResult<()> {
        let Some(uri) = self.session_uri.as_deref() else {
            return Ok(());
        };
        let resp = http::request("PUT", &format!("{uri}{endpoint}"), Some(body), HTTP_TIMEOUT)?;
        if resp.status != 200 {
            return Err(RgbError::backend(format!(
                "Chroma {endpoint} returned HTTP {} body={}",
                resp.status, resp.body
            )));
        }
        let result = parse_result(&resp.body);
        if result != 0 {
            if result == 126 && !self.hint_logged {
                self.hint_logged = true;
                for key in HINT_126_KEYS {
                    if key.is_empty() {
                        tracing::info!("");
                    } else {
                        tracing::info!("{}", t!(key));
                    }
                }
            }
            return Err(RgbError::backend(format!(
                "Chroma {endpoint} returned result={result} ({}) body={}",
                describe_result(result),
                resp.body
            )));
        }
        if self.succeeded_once.insert(endpoint) {
            tracing::info!(
                "{}",
                tf!("log.rgb.chroma.endpointFirstFrameSucceeded", endpoint)
            );
        }
        Ok(())
    }

    fn push(&mut self, frame: &RgbFrame) -> RgbResult<()> {
        if self.session_uri.is_none() {
            return Ok(());
        }
        let bg = frame.background().chroma_pack();
        let mut successes = 0;
        let mut last: Option<RgbError> = None;
        let attempts = 1 + NON_KEYBOARD_ENDPOINTS.len();
        let kb = keyboard_body(&layout::build_grid(frame));
        let st = static_body(bg);
        let jobs = std::iter::once(("/keyboard", kb.as_str()))
            .chain(NON_KEYBOARD_ENDPOINTS.iter().map(|e| (*e, st.as_str())));
        for (endpoint, body) in jobs {
            match self.send_effect(endpoint, body) {
                Ok(()) => successes += 1,
                Err(e) => {
                    tracing::warn!(
                        "{}",
                        tf!(
                            "log.rgb.chroma.endpointPushFailed",
                            endpoint,
                            "RgbError",
                            e.to_string()
                        )
                    );
                    last = Some(e);
                }
            }
        }
        if successes == 0 {
            return Err(RgbError::backend(format!(
                "Chroma renderFrame: all {attempts} endpoints failed: {}",
                last.map(|e| e.to_string()).unwrap_or_default()
            )));
        }
        Ok(())
    }
}

impl Default for ChromaRestBackend {
    fn default() -> Self {
        ChromaRestBackend::new()
    }
}

fn heartbeat_loop(uri: String, stop: mpsc::Receiver<()>) {
    loop {
        match stop.recv_timeout(HEARTBEAT_INTERVAL) {
            Err(RecvTimeoutError::Timeout) => {}
            _ => return,
        }
        if let Err(e) = http::request("PUT", &format!("{uri}/heartbeat"), Some(""), HTTP_TIMEOUT) {
            tracing::warn!(
                "{}",
                tf!("log.rgb.chroma.heartbeatFailed", "RgbError", e.to_string())
            );
        }
    }
}

impl RgbBackend for ChromaRestBackend {
    fn name(&self) -> &str {
        BackendKind::ChromaRest.label()
    }

    fn is_available(&mut self) -> bool {
        let Ok(url) = http::HttpUrl::parse(&self.init_url) else {
            return false;
        };
        let Ok(addrs) = (url.host.as_str(), url.port).to_socket_addrs() else {
            return false;
        };
        addrs
            .into_iter()
            .any(|a| TcpStream::connect_timeout(&a, PROBE_TIMEOUT).is_ok())
    }

    fn start(&mut self) -> RgbResult<()> {
        self.shutdown();
        let resp = http::request("POST", &self.init_url, Some(&init_body()), HTTP_TIMEOUT)?;
        if resp.status != 200 {
            return Err(RgbError::backend(format!(
                "Chroma init returned HTTP {}: {}",
                resp.status, resp.body
            )));
        }
        let uri = serde_json::from_str::<serde_json::Value>(&resp.body)
            .ok()
            .and_then(|v| v.get("uri").and_then(|u| u.as_str()).map(str::to_string))
            .ok_or_else(|| {
                RgbError::backend(format!("Chroma init response missing 'uri': {}", resp.body))
            })?;
        tracing::info!("{}", tf!("log.rgb.chroma.sessionOpened", uri.as_str()));
        let (tx, rx) = mpsc::channel();
        let hb_uri = uri.clone();
        let handle = std::thread::Builder::new()
            .name("mica-rgb-chroma-heartbeat".into())
            .spawn(move || heartbeat_loop(hb_uri, rx))?;
        self.session_uri = Some(uri);
        self.heartbeat = Some((tx, handle));
        Ok(())
    }

    fn render_frame(&mut self, frame: &RgbFrame) -> RgbResult<()> {
        self.push(frame)
    }

    fn shutdown(&mut self) {
        if self.session_uri.is_some() {
            let _ = self.push(&RgbFrame::solid(RgbColor::BLACK));
        }
        if let Some((tx, handle)) = self.heartbeat.take() {
            drop(tx);
            let _ = handle.join();
        }
        if let Some(uri) = self.session_uri.take() {
            let _ = http::request("DELETE", &uri, None, HTTP_TIMEOUT);
        }
        self.succeeded_once.clear();
        self.hint_logged = false;
    }

    fn kind(&self) -> BackendKind {
        BackendKind::ChromaRest
    }
}

impl Drop for ChromaRestBackend {
    fn drop(&mut self) {
        if let Some((tx, _)) = self.heartbeat.take() {
            drop(tx);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::key::KeyboardKey;
    use std::io::{BufRead, BufReader, Read, Write};
    use std::net::TcpListener;

    #[test]
    fn bodies_are_byte_exact() {
        assert_eq!(
            static_body(255),
            "{\"effect\":\"CHROMA_STATIC\",\"param\":{\"color\":255}}"
        );
        let g = layout::build_grid(&RgbFrame::new(
            RgbColor::BLACK,
            [(KeyboardKey::Escape, RgbColor::new(1, 0, 0))],
        ));
        let kb = keyboard_body(&g);
        assert!(kb.starts_with("{\"effect\":\"CHROMA_CUSTOM\",\"param\":[[0,1,0,"));
        assert!(kb.ends_with(",0]]}"));
        assert_eq!(kb.matches('[').count(), 7);
        let v: serde_json::Value = serde_json::from_str(&kb).unwrap();
        assert_eq!(v["param"].as_array().unwrap().len(), 6);
        assert_eq!(v["param"][0].as_array().unwrap().len(), 22);
        assert_eq!(
            init_body(),
            "{\"title\":\"Mica Minecraft Launcher\",\"description\":\"Modpack-aware RGB lighting for Minecraft sessions.\",\"author\":{\"name\":\"Mica Technologies\",\"contact\":\"https://github.com/Mica-Technologies/minecraft-launcher\"},\"device_supported\":[\"keyboard\",\"mouse\",\"mousepad\",\"headset\",\"keypad\",\"chromalink\"],\"category\":\"application\"}"
        );
    }

    #[test]
    fn result_parsing() {
        assert_eq!(parse_result(""), 0);
        assert_eq!(parse_result("{}"), 0);
        assert_eq!(parse_result("{\"result\":126}"), 126);
        assert_eq!(parse_result("{\"result\":-57}"), -57);
        assert_eq!(parse_result("not json"), -1);
        assert_eq!(
            describe_result(126).split(' ').next(),
            Some("MOD_NOT_FOUND")
        );
    }

    /// A fake Synapse: answers init with a session URI on the same server, `result` per path.
    fn fake_synapse(
        results: fn(&str) -> i64,
        requests: usize,
    ) -> (String, std::thread::JoinHandle<Vec<String>>) {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = l.local_addr().unwrap().port();
        let h = std::thread::spawn(move || {
            let mut seen = Vec::new();
            for _ in 0..requests {
                let (s, _) = l.accept().unwrap();
                let mut r = BufReader::new(s.try_clone().unwrap());
                let mut line = String::new();
                r.read_line(&mut line).unwrap();
                let mut len = 0;
                loop {
                    let mut h = String::new();
                    r.read_line(&mut h).unwrap();
                    if h.trim().is_empty() {
                        break;
                    }
                    if let Some(v) = h.to_ascii_lowercase().strip_prefix("content-length:") {
                        len = v.trim().parse().unwrap();
                    }
                }
                let mut body = vec![0; len];
                r.read_exact(&mut body).unwrap();
                let mut parts = line.split_whitespace();
                let (method, path) = (
                    parts.next().unwrap().to_string(),
                    parts.next().unwrap().to_string(),
                );
                seen.push(format!("{method} {path}"));
                let reply = if method == "POST" {
                    format!("{{\"sessionid\":1,\"uri\":\"http://127.0.0.1:{port}/s1\"}}")
                } else {
                    format!("{{\"result\":{}}}", results(&path))
                };
                let mut s = s;
                write!(
                    s,
                    "HTTP/1.1 200 OK\r\nContent-Length: {}\r\n\r\n{reply}",
                    reply.len()
                )
                .unwrap();
            }
            seen
        });
        (format!("http://127.0.0.1:{port}/razer/chromasdk"), h)
    }

    #[test]
    fn partial_rig_is_delivered_and_all_failed_is_an_error() {
        // init + 6 PUTs (keyboard ok, rest 4319) + 6 PUTs (all 126) + black frame 6 + DELETE
        let (url, server) = fake_synapse(
            |p| match p {
                "/s1/keyboard" => 0,
                _ => 4319,
            },
            1 + 6 + 6 + 1,
        );
        let mut b = ChromaRestBackend::with_init_url(&url);
        b.start().unwrap();
        b.render_frame(&RgbFrame::solid(RgbColor::WHITE)).unwrap();
        b.shutdown();
        let seen = server.join().unwrap();
        assert_eq!(seen[0], "POST /razer/chromasdk");
        assert_eq!(seen[1], "PUT /s1/keyboard");
        assert_eq!(seen[6], "PUT /s1/chromalink");
        assert_eq!(seen.last().unwrap(), "DELETE /s1");

        let (url, server) = fake_synapse(|_| 126, 1 + 6 + 6 + 1);
        let mut b = ChromaRestBackend::with_init_url(&url);
        b.start().unwrap();
        let e = b
            .render_frame(&RgbFrame::solid(RgbColor::WHITE))
            .unwrap_err();
        assert!(e.to_string().contains("all 6 endpoints failed"), "{e}");
        assert!(b.hint_logged);
        b.shutdown();
        assert!(!b.hint_logged);
        server.join().unwrap();
    }
}
