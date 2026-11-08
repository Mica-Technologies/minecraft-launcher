// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The `--mcp` stdio relay (Java `StdioProxy`): one JSON-RPC message per stdin line, POSTed
//! to the running launcher; each reply written to stdout as one line.
//!
//! stdout carries protocol only; diagnostics go to stderr; the launcher logger is never used
//! here. The relay talks HTTP/1.1 to `127.0.0.1` itself over a plain socket, so no proxy
//! setting can capture the loopback traffic.
//!
//! Fixes over Java: a rejected *request* (401 after a token change, 403, 405, 413, 415, 500)
//! now yields an error line carrying its id instead of silence; a failing *notification*
//! yields nothing (Java wrote an error with `id: null`); on a connection failure or a 401 the
//! endpoint file is re-read once, so a launcher restart (new port or token) is picked up; and
//! lines end in `\n` only.

use std::io::{self, BufRead, BufReader, Write};
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, TcpStream};
use std::path::Path;
use std::time::Duration;

use serde_json::Value;

use crate::endpoint::{self, Endpoint};
use crate::protocol::{codec, codes, methods};
use crate::transport::http::{PATH, SESSION_HEADER};

/// No running launcher with the server enabled was found.
pub const EXIT_NO_LAUNCHER: i32 = 2;
/// stdin failed.
pub const EXIT_IO_ERROR: i32 = 1;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
/// Must exceed the 120 s consent deadline.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(180);
const DELETE_TIMEOUT: Duration = Duration::from_secs(5);

const NO_LAUNCHER: &str = "No running Mica Minecraft Launcher with the MCP server enabled was found. Start the launcher and turn on Settings \u{2192} Security \u{2192} Enable MCP server, then reconnect.";

/// One HTTP reply.
#[derive(Debug, Default)]
struct HttpReply {
    status: u16,
    session_id: Option<String>,
    body: String,
}

/// What one relayed line produced.
#[derive(Debug, Default)]
struct Relayed {
    body: Option<String>,
    session_id: Option<String>,
    session_unknown: bool,
}

/// Runs the relay until stdin closes. Returns the process exit code: 0 on EOF,
/// [`EXIT_IO_ERROR`] on a read error, [`EXIT_NO_LAUNCHER`] when no live endpoint is found.
pub fn run(
    endpoint_path: &Path,
    stdin: impl BufRead,
    mut stdout: impl Write,
    mut stderr: impl Write,
) -> i32 {
    let Some(endpoint) = endpoint::read_live(endpoint_path) else {
        let _ = writeln!(stderr, "{NO_LAUNCHER}");
        let _ = stderr.flush();
        return EXIT_NO_LAUNCHER;
    };
    let mut relay = Relay {
        endpoint,
        endpoint_path: endpoint_path.to_path_buf(),
        session_id: None,
        initialize_line: None,
        stderr: &mut stderr,
    };
    let mut reader = stdin;
    let mut buf = Vec::new();
    loop {
        buf.clear();
        match reader.read_until(b'\n', &mut buf) {
            Ok(0) => {
                relay.end_session();
                return 0;
            }
            Ok(_) => {
                let chunk = String::from_utf8_lossy(&buf).into_owned();
                let chunk = chunk.strip_suffix('\n').unwrap_or(&chunk);
                for line in chunk.split('\r') {
                    if line.trim().is_empty() {
                        continue;
                    }
                    if let Some(out) = relay.handle_line(line) {
                        let _ = stdout.write_all(out.as_bytes());
                        let _ = stdout.write_all(b"\n");
                        let _ = stdout.flush();
                    }
                }
            }
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(e) => {
                let _ = writeln!(relay.stderr, "MCP relay stopped: {:?}", e.kind());
                relay.end_session();
                return EXIT_IO_ERROR;
            }
        }
    }
}

struct Relay<'a, E: Write> {
    endpoint: Endpoint,
    endpoint_path: std::path::PathBuf,
    session_id: Option<String>,
    initialize_line: Option<String>,
    stderr: &'a mut E,
}

/// The line's id when it parses, and whether it is a notification.
fn line_kind(line: &str) -> (Option<Value>, bool, bool) {
    match codec::parse(line) {
        Ok(m) => {
            let is_init = m.method == methods::INITIALIZE;
            let notification = m.is_notification();
            (m.id, notification, is_init)
        }
        // An unparsable line is relayed; the launcher answers -32700 with id null.
        Err(_) => (None, false, false),
    }
}

impl<E: Write> Relay<'_, E> {
    fn handle_line(&mut self, line: &str) -> Option<String> {
        let (_, _, is_init) = line_kind(line);
        if is_init {
            self.initialize_line = Some(line.to_string());
        }
        let session = if is_init {
            None
        } else {
            self.session_id.clone()
        };
        let mut relayed = self.relay(line, session.as_deref());
        if relayed.session_unknown
            && self.initialize_line.is_some()
            && let Some(renewed) = self.reinitialize()
        {
            self.session_id = Some(renewed.clone());
            relayed = self.relay(line, Some(&renewed));
        }
        if let Some(s) = relayed.session_id {
            self.session_id = Some(s);
        }
        relayed.body
    }

    fn post(&self, body: &str, session: Option<&str>) -> io::Result<HttpReply> {
        http_request(
            self.endpoint.port,
            "POST",
            &self.endpoint.token,
            session,
            Some(body),
            REQUEST_TIMEOUT,
        )
    }

    /// Re-reads the endpoint file; `true` when it now names a different port or token.
    fn refresh_endpoint(&mut self) -> bool {
        match endpoint::read_live(&self.endpoint_path) {
            Some(e) if e.port != self.endpoint.port || e.token != self.endpoint.token => {
                self.endpoint = e;
                true
            }
            _ => false,
        }
    }

    fn relay(&mut self, line: &str, session: Option<&str>) -> Relayed {
        let (id, notification, _) = line_kind(line);
        let mut reply = self.post(line, session);
        let retry = match &reply {
            Err(_) => true,
            Ok(r) => r.status == 401,
        };
        if retry && self.refresh_endpoint() {
            // A restarted launcher knows none of our sessions; the 404 path re-initializes.
            reply = self.post(line, session);
        }
        match reply {
            Err(e) => {
                let _ = writeln!(self.stderr, "MCP relay: {:?}", e.kind());
                Relayed {
                    body: (!notification).then(|| {
                        transport_error(
                            id.as_ref(),
                            "Could not reach the launcher. It may have been closed.",
                        )
                    }),
                    ..Relayed::default()
                }
            }
            Ok(r) => {
                let blank = r.body.trim().is_empty();
                if r.status == 404 && session.is_some() {
                    return Relayed {
                        body: (!blank).then_some(r.body),
                        session_id: None,
                        session_unknown: true,
                    };
                }
                if r.status == 202 || ((200..300).contains(&r.status) && blank) {
                    return Relayed {
                        body: None,
                        session_id: r.session_id,
                        session_unknown: false,
                    };
                }
                if r.status == 400 && !blank {
                    return Relayed {
                        body: Some(r.body),
                        ..Relayed::default()
                    };
                }
                if r.status == 200 {
                    return Relayed {
                        body: Some(r.body),
                        session_id: r.session_id,
                        session_unknown: false,
                    };
                }
                let _ = writeln!(
                    self.stderr,
                    "MCP relay: launcher returned HTTP {}",
                    r.status
                );
                Relayed {
                    body: (!notification).then(|| {
                        transport_error(
                            id.as_ref(),
                            &format!(
                                "The launcher refused the request (HTTP {}). The MCP server may have been turned off.",
                                r.status
                            ),
                        )
                    }),
                    ..Relayed::default()
                }
            }
        }
    }

    fn reinitialize(&mut self) -> Option<String> {
        let line = self.initialize_line.clone()?;
        let renewed = self.post(&line, None).ok().and_then(|r| r.session_id);
        let Some(sid) = renewed else {
            let _ = writeln!(self.stderr, "MCP relay: could not re-establish the session");
            return None;
        };
        let _ = writeln!(self.stderr, "MCP relay: session expired; re-initialized");
        let _ = self.post(
            r#"{"jsonrpc":"2.0","method":"notifications/initialized"}"#,
            Some(&sid),
        );
        Some(sid)
    }

    fn end_session(&mut self) {
        if let Some(sid) = self.session_id.take() {
            let _ = http_request(
                self.endpoint.port,
                "DELETE",
                &self.endpoint.token,
                Some(&sid),
                None,
                DELETE_TIMEOUT,
            );
        }
    }
}

/// A -32603 error carrying the request's id.
fn transport_error(id: Option<&Value>, msg: &str) -> String {
    codec::encode(&codec::error(id, codes::INTERNAL_ERROR, msg, None))
}

/// One HTTP/1.1 exchange with `127.0.0.1:<port>/mcp` (`Connection: close`).
fn http_request(
    port: u16,
    method: &str,
    token: &str,
    session: Option<&str>,
    body: Option<&str>,
    timeout: Duration,
) -> io::Result<HttpReply> {
    let addr = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::LOCALHOST, port));
    let mut stream = TcpStream::connect_timeout(&addr, CONNECT_TIMEOUT)?;
    stream.set_read_timeout(Some(timeout))?;
    stream.set_write_timeout(Some(timeout))?;
    let body = body.unwrap_or("");
    let mut req = format!(
        "{method} {PATH} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nAuthorization: Bearer {token}\r\nAccept: application/json\r\nConnection: close\r\nContent-Length: {}\r\n",
        body.len()
    );
    if method == "POST" {
        req.push_str("Content-Type: application/json\r\n");
    }
    if let Some(s) = session {
        req.push_str(&format!("{SESSION_HEADER}: {s}\r\n"));
    }
    req.push_str("\r\n");
    stream.write_all(req.as_bytes())?;
    stream.write_all(body.as_bytes())?;
    stream.flush()?;
    read_response(BufReader::new(stream))
}

fn read_response(mut r: impl BufRead) -> io::Result<HttpReply> {
    let bad = || io::Error::new(io::ErrorKind::InvalidData, "malformed HTTP response");
    let mut line = String::new();
    r.read_line(&mut line)?;
    let status: u16 = line
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .ok_or_else(bad)?;
    let mut length: Option<usize> = None;
    let mut chunked = false;
    let mut session_id = None;
    loop {
        line.clear();
        if r.read_line(&mut line)? == 0 {
            break;
        }
        let l = line.trim_end();
        if l.is_empty() {
            break;
        }
        let Some((k, v)) = l.split_once(':') else {
            continue;
        };
        let (k, v) = (k.trim(), v.trim());
        if k.eq_ignore_ascii_case("content-length") {
            length = v.parse().ok();
        } else if k.eq_ignore_ascii_case("transfer-encoding") {
            chunked = v.to_ascii_lowercase().contains("chunked");
        } else if k.eq_ignore_ascii_case(SESSION_HEADER) && !v.is_empty() {
            session_id = Some(v.to_string());
        }
    }
    let mut body = Vec::new();
    if chunked {
        loop {
            line.clear();
            r.read_line(&mut line)?;
            let size_hex = line.trim().split(';').next().unwrap_or("");
            let size = usize::from_str_radix(size_hex, 16).map_err(|_| bad())?;
            if size == 0 {
                break;
            }
            let mut chunk = vec![0u8; size];
            r.read_exact(&mut chunk)?;
            body.extend_from_slice(&chunk);
            line.clear();
            r.read_line(&mut line)?;
        }
    } else if let Some(n) = length {
        body.resize(n, 0);
        r.read_exact(&mut body)?;
    } else if status != 204 && status != 304 {
        r.read_to_end(&mut body)?;
    }
    Ok(HttpReply {
        status,
        session_id,
        body: String::from_utf8_lossy(&body).into_owned(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_a_content_length_response() {
        let raw = "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\nmcp-session-id: abc\r\ncontent-length: 2\r\n\r\n{}";
        let r = read_response(io::Cursor::new(raw)).unwrap();
        assert_eq!(r.status, 200);
        assert_eq!(r.session_id.as_deref(), Some("abc"));
        assert_eq!(r.body, "{}");
    }

    #[test]
    fn parses_a_chunked_response() {
        let raw =
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n";
        assert_eq!(read_response(io::Cursor::new(raw)).unwrap().body, "abcde");
    }

    #[test]
    fn parses_an_empty_202() {
        let raw = "HTTP/1.1 202 Accepted\r\ncontent-length: 0\r\n\r\n";
        let r = read_response(io::Cursor::new(raw)).unwrap();
        assert_eq!(r.status, 202);
        assert!(r.body.is_empty());
    }

    #[test]
    fn a_garbage_status_line_is_an_error() {
        assert!(read_response(io::Cursor::new("nonsense\r\n\r\n")).is_err());
    }

    #[test]
    fn transport_error_keeps_the_id() {
        let e = transport_error(Some(&serde_json::json!(7)), "x");
        assert_eq!(
            e,
            r#"{"jsonrpc":"2.0","id":7,"error":{"code":-32603,"message":"x"}}"#
        );
    }

    #[test]
    fn line_kinds() {
        assert!(line_kind(r#"{"jsonrpc":"2.0","method":"x"}"#).1);
        assert!(line_kind(r#"{"jsonrpc":"2.0","id":1,"method":"initialize"}"#).2);
        assert!(!line_kind("{bad").1);
    }

    #[test]
    fn absent_endpoint_exits_2_with_nothing_on_stdout() {
        let dir = tempfile::tempdir().unwrap();
        let mut out = Vec::new();
        let mut err = Vec::new();
        let code = run(
            &dir.path().join("mcp-endpoint.json"),
            io::Cursor::new(b"{}\n".to_vec()),
            &mut out,
            &mut err,
        );
        assert_eq!(code, EXIT_NO_LAUNCHER);
        assert!(out.is_empty());
        assert!(
            String::from_utf8(err)
                .unwrap()
                .contains("No running Mica Minecraft Launcher")
        );
    }
}
