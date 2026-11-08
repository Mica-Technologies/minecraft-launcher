// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Port of `McpServerLoopbackTest`: a real server on an ephemeral loopback port.

mod common;

use std::sync::Arc;
use std::time::Duration;

use common::*;
use mmcl_mcp::McpServer;
use mmcl_mcp::session::MAX_SESSIONS;
use serde_json::json;

struct Running {
    server: McpServer,
    port: u16,
    token: String,
    gate: Arc<Gate>,
    entered: std::sync::mpsc::Receiver<()>,
    release: std::sync::mpsc::Sender<()>,
}

fn start() -> Running {
    let (gate, entered, release) = Gate::new();
    let server = server_with(gate.clone());
    let port = server.start(0, None, "").unwrap();
    let token = server.token();
    Running {
        server,
        port,
        token,
        gate,
        entered,
        release,
    }
}

fn handshake(r: &Running) -> String {
    let reply = post(r.port, &r.token, None, INIT);
    assert_eq!(reply.status, 200);
    reply.session.expect("session header")
}

#[test]
fn the_endpoint_file_is_published_and_removed() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("config").join("mcp-endpoint.json");
    let (gate, _e, _r) = Gate::new();
    let server = server_with(gate);
    let port = server.start(0, Some(&path), "").unwrap();
    let e = mmcl_mcp::endpoint::read_live(&path).unwrap();
    assert_eq!(e.port, port);
    assert_eq!(e.token, server.token());
    assert_eq!(e.pid, i64::from(std::process::id()));
    assert_eq!(e.launcher_version, "1.0-test");
    server.stop();
    assert!(!path.exists());
}

#[test]
fn a_persisted_token_is_used() {
    let (gate, _e, _r) = Gate::new();
    let server = server_with(gate);
    let tok = "f".repeat(64);
    let port = server.start(0, None, &tok).unwrap();
    assert_eq!(
        post(
            port,
            &tok,
            None,
            r#"{"jsonrpc":"2.0","id":1,"method":"ping"}"#
        )
        .status,
        200
    );
}

#[test]
fn a_busy_port_falls_back_to_an_os_assigned_one() {
    let holder = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let busy = holder.local_addr().unwrap().port();
    let (gate, _e, _r) = Gate::new();
    let server = server_with(gate);
    let port = server.start(busy, None, "").unwrap();
    assert_ne!(port, busy);
    assert!(port > 0);
}

#[test]
fn missing_or_wrong_token_is_401_with_no_body() {
    let r = start();
    let body = r#"{"jsonrpc":"2.0","id":1,"method":"ping"}"#;
    let reply = http(
        r.port,
        "POST",
        "/mcp",
        &[("Content-Type", "application/json")],
        body.as_bytes(),
    );
    assert_eq!(reply.status, 401);
    assert!(reply.body.is_empty());
    assert_eq!(post(r.port, "wrong", None, body).status, 401);
}

#[test]
fn a_foreign_origin_is_403_and_a_loopback_origin_is_fine() {
    let r = start();
    let auth = format!("Bearer {}", r.token);
    let body = br#"{"jsonrpc":"2.0","id":1,"method":"ping"}"#;
    let reply = http(
        r.port,
        "POST",
        "/mcp",
        &[
            ("Authorization", &auth),
            ("Content-Type", "application/json"),
            ("Origin", "http://evil.example"),
        ],
        body,
    );
    assert_eq!(reply.status, 403);
    let reply = http(
        r.port,
        "POST",
        "/mcp",
        &[
            ("Authorization", &auth),
            ("Content-Type", "application/json"),
            ("Origin", "http://localhost:3000"),
        ],
        body,
    );
    assert_eq!(reply.status, 200);
}

#[test]
fn get_is_405_and_text_plain_is_415_and_other_paths_404() {
    let r = start();
    let auth = format!("Bearer {}", r.token);
    assert_eq!(
        http(r.port, "GET", "/mcp", &[("Authorization", &auth)], b"").status,
        405
    );
    assert_eq!(
        http(
            r.port,
            "POST",
            "/mcp",
            &[("Authorization", &auth), ("Content-Type", "text/plain")],
            b"{}"
        )
        .status,
        415
    );
    assert_eq!(
        http(r.port, "POST", "/other", &[("Authorization", &auth)], b"").status,
        404
    );
}

#[test]
fn an_oversized_body_gets_413_intact() {
    let r = start();
    let big = vec![b' '; (mmcl_mcp::transport::guard::MAX_BODY_BYTES + 10) as usize];
    let reply = post(r.port, &r.token, None, std::str::from_utf8(&big).unwrap());
    assert_eq!(reply.status, 413);
}

#[test]
fn ping_is_answered_without_a_session_and_creates_none() {
    let r = start();
    let reply = post(
        r.port,
        &r.token,
        None,
        r#"{"jsonrpc":"2.0","id":9,"method":"ping"}"#,
    );
    assert_eq!(json(&reply), json!({"jsonrpc":"2.0","id":9,"result":{}}));
    assert!(reply.content_type.unwrap().starts_with("application/json"));
    assert!(r.server.sessions().is_empty());
}

#[test]
fn malformed_json_is_a_parse_error_and_a_notification_is_202() {
    let r = start();
    let reply = post(r.port, &r.token, None, "{oops");
    assert_eq!(reply.status, 200);
    assert_eq!(json(&reply)["error"]["code"], json!(-32700));
    let sid = handshake(&r);
    let reply = post(
        r.port,
        &r.token,
        Some(&sid),
        r#"{"jsonrpc":"2.0","method":"notifications/initialized"}"#,
    );
    assert_eq!(reply.status, 202);
    assert!(reply.body.is_empty());
    assert!(reply.session.is_none());
}

#[test]
fn handshake_then_tool_call() {
    let r = start();
    let sid = handshake(&r);
    assert_eq!(sid.len(), 32);
    let reply = post(
        r.port,
        &r.token,
        Some(&sid),
        r#"{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_modpacks"}}"#,
    );
    let v = json(&reply);
    assert!(
        v["result"]["content"][0]["text"]
            .as_str()
            .unwrap()
            .contains("Alto")
    );
    assert!(reply.session.is_none());
    let snap = r.server.sessions();
    assert_eq!(snap.len(), 1);
    assert_eq!(snap[0].client_name, "tester");
    assert_eq!(snap[0].call_count, 1);
}

#[test]
fn missing_and_unknown_sessions() {
    let r = start();
    let reply = post(
        r.port,
        &r.token,
        None,
        r#"{"jsonrpc":"2.0","id":2,"method":"tools/list"}"#,
    );
    assert_eq!(reply.status, 400);
    assert_eq!(json(&reply)["error"]["code"], json!(-32600));
    let reply = post(
        r.port,
        &r.token,
        Some("deadbeef"),
        r#"{"jsonrpc":"2.0","id":2,"method":"tools/list"}"#,
    );
    assert_eq!(reply.status, 404);
    assert_eq!(json(&reply)["error"]["code"], json!(-32001));
    assert!(
        r.server.sessions().is_empty(),
        "sessionless requests create no sessions"
    );
}

#[test]
fn handshakes_past_the_cap_evict_the_oldest() {
    let r = start();
    let first = handshake(&r);
    for _ in 0..MAX_SESSIONS {
        std::thread::sleep(Duration::from_millis(2));
        handshake(&r);
    }
    assert_eq!(r.server.sessions().len(), MAX_SESSIONS);
    let reply = post(
        r.port,
        &r.token,
        Some(&first),
        r#"{"jsonrpc":"2.0","id":2,"method":"tools/list"}"#,
    );
    assert_eq!(reply.status, 404);
}

#[test]
fn delete_ends_a_session() {
    let r = start();
    let sid = handshake(&r);
    let auth = format!("Bearer {}", r.token);
    assert_eq!(
        http(r.port, "DELETE", "/mcp", &[("Authorization", &auth)], b"").status,
        400
    );
    assert_eq!(
        http(r.port, "DELETE", "/mcp", &[("Mcp-Session-Id", &sid)], b"").status,
        401
    );
    assert_eq!(
        http(
            r.port,
            "DELETE",
            "/mcp",
            &[("Authorization", &auth), ("Mcp-Session-Id", &sid)],
            b""
        )
        .status,
        204
    );
    assert_eq!(
        http(
            r.port,
            "DELETE",
            "/mcp",
            &[("Authorization", &auth), ("Mcp-Session-Id", &sid)],
            b""
        )
        .status,
        404
    );
    assert!(r.server.sessions().is_empty());
}

#[test]
fn reinitialize_cannot_change_the_client_name() {
    let r = start();
    let sid = handshake(&r);
    let reply = post(
        r.port,
        &r.token,
        Some(&sid),
        r#"{"jsonrpc":"2.0","id":3,"method":"initialize","params":{"clientInfo":{"name":"evil"}}}"#,
    );
    assert_eq!(reply.status, 200);
    assert_eq!(reply.session.as_deref(), Some(sid.as_str()));
    assert_eq!(r.server.sessions()[0].client_name, "tester");
}

#[test]
fn a_denied_call_is_32003_and_never_runs() {
    let r = start();
    let sid = handshake(&r);
    let reply = post(
        r.port,
        &r.token,
        Some(&sid),
        r#"{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_crash_report","arguments":{"friendlyName":"Alto"}}}"#,
    );
    assert_eq!(json(&reply)["error"]["code"], json!(-32003));
    assert_eq!(r.gate.calls.lock().as_slice(), ["get_crash_report"]);
}

#[test]
fn a_pending_consent_does_not_block_unrelated_requests() {
    let r = start();
    let sid = handshake(&r);
    let (port, token, sid2) = (r.port, r.token.clone(), sid.clone());
    let slow = std::thread::spawn(move || {
        post(
            port,
            &token,
            Some(&sid2),
            r#"{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"get_modpack_manifest","arguments":{"friendlyName":"Alto"}}}"#,
        )
    });
    r.entered
        .recv_timeout(Duration::from_secs(10))
        .expect("call reached consent");
    let ping = post(
        r.port,
        &r.token,
        None,
        r#"{"jsonrpc":"2.0","id":6,"method":"ping"}"#,
    );
    assert_eq!(json(&ping)["result"], json!({}));
    let list = post(
        r.port,
        &r.token,
        Some(&sid),
        r#"{"jsonrpc":"2.0","id":7,"method":"tools/list"}"#,
    );
    assert!(json(&list)["result"]["tools"].is_array());
    let other = post(r.port, &r.token, None, INIT);
    assert!(other.session.is_some());
    r.release.send(()).unwrap();
    let slow = slow.join().unwrap();
    assert_eq!(json(&slow)["result"]["content"][0]["text"], json!("{}"));
}

#[test]
fn start_twice_refused_stop_twice_harmless_and_restart_works() {
    let r = start();
    assert!(r.server.start(0, None, "").is_err());
    r.server.stop();
    r.server.stop();
    assert!(!r.server.is_running());
    let port = r.server.start(0, None, "").unwrap();
    let token = r.server.token();
    assert_eq!(
        post(
            port,
            &token,
            None,
            r#"{"jsonrpc":"2.0","id":1,"method":"ping"}"#
        )
        .status,
        200
    );
}

#[test]
fn the_bearer_token_never_appears_in_output() {
    let r = start();
    let sid = handshake(&r);
    let body = format!(
        r#"{{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{{"name":"get_modpack_info","arguments":{{"friendlyName":"{}"}}}}}}"#,
        r.token
    );
    let reply = post(r.port, &r.token, Some(&sid), &body);
    assert!(!reply.body.contains(&r.token), "{}", reply.body);
    assert!(reply.body.contains("[REDACTED]"));
}
