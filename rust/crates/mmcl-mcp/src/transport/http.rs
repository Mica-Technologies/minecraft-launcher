// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The loopback Streamable HTTP listener (Java `LoopbackHttpTransport`): hyper 1 on a small
//! tokio runtime the server owns.
//!
//! One path, `/mcp`, bound to `127.0.0.1` only (there is deliberately no way to configure
//! another address). POST carries JSON-RPC; DELETE ends a session. No CORS headers are ever
//! sent. On Windows the socket is bound with `SO_EXCLUSIVEADDRUSE` so no other process can
//! co-bind it.

use std::convert::Infallible;
use std::io;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::sync::Arc;
use std::time::Duration;

use bytes::Bytes;
use http_body_util::{BodyExt, Full};
use hyper::body::Incoming;
use hyper::header::{HeaderMap, HeaderValue};
use hyper::{Request, StatusCode};
use hyper_util::rt::{TokioIo, TokioTimer};
use socket2::{Domain, Protocol, Socket, Type};

use super::guard::{self, DRAIN_LIMIT, MAX_BODY_BYTES, RequestMeta, Verdict};
use crate::logging;
use crate::server::Core;

/// The only served path.
pub const PATH: &str = "/mcp";
/// The session header.
pub const SESSION_HEADER: &str = "Mcp-Session-Id";

const WORKER_THREADS: usize = 2;
const HEADER_READ_TIMEOUT: Duration = Duration::from_secs(30);

/// A running listener.
pub struct LoopbackTransport {
    rt: Option<tokio::runtime::Runtime>,
    shutdown: Option<tokio::sync::oneshot::Sender<()>>,
    closed: Option<std::sync::mpsc::Receiver<()>>,
    port: u16,
}

const STOP_WAIT: Duration = Duration::from_secs(1);

/// Binds `127.0.0.1:port` (0 = OS-assigned).
pub fn bind_loopback(port: u16) -> io::Result<std::net::TcpListener> {
    let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP))?;
    #[cfg(windows)]
    set_exclusive_addr_use(&socket)?;
    let addr = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::LOCALHOST, port));
    socket.bind(&addr.into())?;
    socket.listen(64)?;
    let l: std::net::TcpListener = socket.into();
    l.set_nonblocking(true)?;
    Ok(l)
}

#[cfg(windows)]
fn set_exclusive_addr_use(socket: &Socket) -> io::Result<()> {
    use std::os::windows::io::AsRawSocket;
    use windows_sys::Win32::Networking::WinSock::{SO_EXCLUSIVEADDRUSE, SOL_SOCKET, setsockopt};
    let on: i32 = 1;
    // SAFETY: a valid socket handle and a 4-byte option value.
    let rc = unsafe {
        setsockopt(
            socket.as_raw_socket() as usize,
            SOL_SOCKET,
            SO_EXCLUSIVEADDRUSE,
            (&on as *const i32).cast(),
            std::mem::size_of::<i32>() as i32,
        )
    };
    if rc == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

impl LoopbackTransport {
    /// Starts serving `listener`.
    pub(crate) fn start(
        listener: std::net::TcpListener,
        token: String,
        core: Arc<Core>,
    ) -> io::Result<LoopbackTransport> {
        let port = listener.local_addr()?.port();
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(WORKER_THREADS)
            .thread_name("mcp-http")
            .enable_all()
            .build()?;
        let listener = {
            let _guard = rt.enter();
            tokio::net::TcpListener::from_std(listener)?
        };
        let (tx, mut rx) = tokio::sync::oneshot::channel::<()>();
        let (closed_tx, closed_rx) = std::sync::mpsc::channel::<()>();
        let token = Arc::new(token);
        rt.spawn(async move {
            let listener = listener;
            loop {
                tokio::select! {
                    _ = &mut rx => break,
                    accepted = listener.accept() => {
                        let Ok((stream, peer)) = accepted else {
                            tokio::time::sleep(Duration::from_millis(20)).await;
                            continue;
                        };
                        let core = core.clone();
                        let token = token.clone();
                        tokio::spawn(async move {
                            let svc = hyper::service::service_fn(move |req| {
                                serve(req, peer, core.clone(), token.clone())
                            });
                            let _ = hyper::server::conn::http1::Builder::new()
                                .timer(TokioTimer::new())
                                .header_read_timeout(HEADER_READ_TIMEOUT)
                                .serve_connection(TokioIo::new(stream), svc)
                                .await;
                        });
                    }
                }
            }
            drop(listener);
            let _ = closed_tx.send(());
        });
        Ok(LoopbackTransport {
            rt: Some(rt),
            shutdown: Some(tx),
            closed: Some(closed_rx),
            port,
        })
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    /// Stops at once: the listener closes and in-flight exchanges are abandoned. Idempotent;
    /// safe inside an async context.
    pub fn stop(&mut self) {
        if let Some(tx) = self.shutdown.take()
            && tx.send(()).is_ok()
            && let Some(closed) = self.closed.take()
        {
            // Wait (briefly) until the listening socket is really closed, so the port is
            // free when this returns.
            let _ = closed.recv_timeout(STOP_WAIT);
        }
        if let Some(rt) = self.rt.take() {
            rt.shutdown_background();
        }
    }
}

impl Drop for LoopbackTransport {
    fn drop(&mut self) {
        self.stop();
    }
}

type HttpResponse = hyper::Response<Full<Bytes>>;

fn status_only(code: u16) -> HttpResponse {
    let mut r = hyper::Response::new(Full::new(Bytes::new()));
    *r.status_mut() = StatusCode::from_u16(code).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);
    r
}

fn first_header(h: &HeaderMap, name: &str) -> Option<String> {
    h.get(name)
        .and_then(|v| v.to_str().ok())
        .map(str::to_string)
}

/// Reads and discards up to [`DRAIN_LIMIT`] bytes so the client receives the status rather
/// than a connection reset.
async fn drain(body: &mut Incoming, mut already: u64) {
    while already < DRAIN_LIMIT {
        match body.frame().await {
            Some(Ok(f)) => {
                if let Some(d) = f.data_ref() {
                    already += d.len() as u64;
                }
            }
            _ => break,
        }
    }
}

enum BodyRead {
    Ok(Vec<u8>),
    TooLarge,
    Failed,
}

async fn read_capped(body: &mut Incoming) -> BodyRead {
    let mut buf: Vec<u8> = Vec::new();
    loop {
        match body.frame().await {
            None => return BodyRead::Ok(buf),
            Some(Err(_)) => return BodyRead::Failed,
            Some(Ok(f)) => {
                if let Some(d) = f.data_ref() {
                    buf.extend_from_slice(d);
                    if buf.len() as u64 > MAX_BODY_BYTES {
                        let read = buf.len() as u64;
                        drain(body, read).await;
                        return BodyRead::TooLarge;
                    }
                }
            }
        }
    }
}

async fn serve(
    req: Request<Incoming>,
    peer: SocketAddr,
    core: Arc<Core>,
    token: Arc<String>,
) -> Result<HttpResponse, Infallible> {
    if req.uri().path() != PATH {
        return Ok(status_only(404));
    }
    let h = req.headers();
    let meta = RequestMeta {
        method: req.method().as_str().to_string(),
        authorization: first_header(h, "authorization"),
        origin: first_header(h, "origin"),
        content_type: first_header(h, "content-type"),
        content_length: guard::parse_content_length(first_header(h, "content-length").as_deref()),
        remote_is_loopback: guard::is_loopback(Some(peer.ip())),
    };
    let session_header = first_header(h, SESSION_HEADER).filter(|s| !s.trim().is_empty());
    let verdict = guard::admit(&meta, &token);
    let mut body = req.into_body();
    if verdict != Verdict::Allow {
        if verdict == Verdict::PayloadTooLarge {
            drain(&mut body, 0).await;
        }
        return Ok(status_only(verdict.status()));
    }
    if meta.method.eq_ignore_ascii_case("DELETE") {
        return Ok(match session_header {
            None => status_only(400),
            Some(id) if core.close_session(id.trim()) => status_only(204),
            Some(_) => status_only(404),
        });
    }
    let bytes = match read_capped(&mut body).await {
        BodyRead::Ok(b) => b,
        BodyRead::TooLarge => return Ok(status_only(413)),
        BodyRead::Failed => {
            logging::error("log.mcpTransport.serveFailed", &[]);
            return Ok(status_only(500));
        }
    };
    let text = String::from_utf8_lossy(&bytes).into_owned();
    let resp = core.dispatch_async(text, session_header).await;
    let Some(json) = resp.json else {
        return Ok(status_only(if resp.status == 200 {
            202
        } else {
            resp.status
        }));
    };
    let payload = crate::protocol::codec::encode(&json);
    let mut r = hyper::Response::new(Full::new(Bytes::from(payload)));
    *r.status_mut() = StatusCode::from_u16(resp.status).unwrap_or(StatusCode::OK);
    r.headers_mut().insert(
        hyper::header::CONTENT_TYPE,
        HeaderValue::from_static("application/json; charset=utf-8"),
    );
    if let Some(sid) = resp.session_id
        && let Ok(v) = HeaderValue::from_str(&sid)
    {
        r.headers_mut().insert(SESSION_HEADER, v);
    }
    Ok(r)
}
