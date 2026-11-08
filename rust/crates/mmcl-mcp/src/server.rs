// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The MCP server (Java `McpServer`): session resolution, serial dispatch, the loopback
//! listener and the endpoint file.
//!
//! `tools/call` and `resources/read` run one at a time on a dedicated `mcp-dispatch` thread
//! (pack operations are not safe to interleave, and a call may block up to two minutes on a
//! consent dialog). Every other method runs inline on the HTTP task, so a pending consent
//! never stalls pings, listings or another client's handshake.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::mpsc;

use parking_lot::{Mutex, RwLock};
use serde_json::Value;

use crate::activity::ActivityLog;
use crate::approval::Authorizer;
use crate::endpoint::{self, Endpoint};
use crate::handler::RequestHandler;
use crate::logging;
use crate::protocol::{JsonRpcMessage, codec, codes, methods};
use crate::resources::ResourceRegistry;
use crate::session::{Session, SessionRegistry, SessionSnapshot};
use crate::tools::ToolRegistry;
use crate::transport::http::{LoopbackTransport, bind_loopback};
use crate::{McpError, now_ms, token};

/// What the transport sends back for one POST.
#[derive(Clone, Debug, PartialEq)]
pub struct Response {
    /// `None` for an accepted notification (202, no body) or a refusal with no body.
    pub json: Option<Value>,
    /// Echoed as `Mcp-Session-Id` (only on `initialize`).
    pub session_id: Option<String>,
    pub status: u16,
}

impl Response {
    fn ok(json: Option<Value>, session_id: Option<String>) -> Response {
        Response {
            json,
            session_id,
            status: 200,
        }
    }
}

/// `tools/call` and `resources/read` run on the serial dispatcher; nothing else does.
pub fn requires_serial_dispatch(method: &str) -> bool {
    method == methods::TOOLS_CALL || method == methods::RESOURCES_READ
}

type Job = Box<dyn FnOnce() + Send + 'static>;

/// Shared by the HTTP tasks and the dispatcher.
pub(crate) struct Core {
    sessions: SessionRegistry,
    handler: RequestHandler,
    dispatcher: Mutex<Option<mpsc::Sender<Job>>>,
    secrets: RwLock<Vec<String>>,
}

enum Prepared {
    Done(Response),
    Serial {
        message: JsonRpcMessage,
        session: Arc<Session>,
        now: u64,
    },
}

impl Core {
    pub(crate) fn close_session(&self, id: &str) -> bool {
        self.sessions.remove(id)
    }

    fn prepare(&self, body: &str, session_header: Option<&str>) -> Prepared {
        let message = match codec::parse(body) {
            Ok(m) => m,
            Err(e) => return Prepared::Done(Response::ok(Some(e), None)),
        };
        let now = now_ms();
        let sid = session_header.map(str::trim).filter(|s| !s.is_empty());
        let is_init = message.method == methods::INITIALIZE;
        let id = message.id.as_ref();

        let mut session = sid.and_then(|s| self.sessions.find_live(s, now));
        if session.is_none() {
            if is_init {
                if message.is_notification() {
                    // A session nobody can learn the id of would only use up a slot.
                    return Prepared::Done(Response::ok(None, None));
                }
                session = Some(self.sessions.create(now));
            } else if sid.is_some() {
                let json = (!message.is_notification()).then(|| {
                    codec::error(
                        id,
                        codes::SESSION_NOT_FOUND,
                        "Unknown or expired MCP session; send initialize to start a new one",
                        None,
                    )
                });
                return Prepared::Done(Response {
                    json,
                    session_id: None,
                    status: 404,
                });
            } else if message.method == methods::PING {
                // Liveness needs no handshake and creates nothing.
                session = Some(Arc::new(Session::new("", now)));
            } else {
                let json = (!message.is_notification()).then(|| {
                    codec::error(
                        id,
                        codes::INVALID_REQUEST,
                        "Missing Mcp-Session-Id header; send initialize first",
                        None,
                    )
                });
                return Prepared::Done(Response {
                    json,
                    session_id: None,
                    status: 400,
                });
            }
        }
        let session = session.expect("resolved above");
        if requires_serial_dispatch(&message.method) {
            return Prepared::Serial {
                message,
                session,
                now,
            };
        }
        let json = self.run_handler(&message, &session, now);
        let echo = is_init.then(|| session.id().to_string());
        Prepared::Done(self.finish(json, echo))
    }

    fn run_handler(&self, message: &JsonRpcMessage, session: &Session, now: u64) -> Option<Value> {
        match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            self.handler.handle(message, session, now)
        })) {
            Ok(v) => v,
            Err(p) => {
                logging::error_with(
                    "log.mcpServer.dispatchFailed",
                    &[],
                    &logging::panic_text(p.as_ref()),
                );
                (!message.is_notification()).then(|| {
                    codec::error(
                        message.id.as_ref(),
                        codes::INTERNAL_ERROR,
                        "Request failed",
                        None,
                    )
                })
            }
        }
    }

    /// Scrubs literal secrets (the bearer and IPC tokens) from every outbound string, on top
    /// of the strict redaction tool and resource text already had.
    fn finish(&self, json: Option<Value>, echo: Option<String>) -> Response {
        let json = json.map(|mut v| {
            let secrets = self.secrets.read();
            if !secrets.is_empty() {
                scrub(&mut v, &secrets);
            }
            v
        });
        Response::ok(json, echo)
    }

    fn submit(
        self: &Arc<Self>,
        message: JsonRpcMessage,
        session: Arc<Session>,
        now: u64,
        reply: Box<dyn FnOnce(Option<Value>) + Send>,
    ) -> Result<(), ()> {
        let core = self.clone();
        let job: Job = Box::new(move || {
            let v = core.run_handler(&message, &session, now);
            reply(v);
        });
        match self.dispatcher.lock().as_ref() {
            Some(tx) => tx.send(job).map_err(|_| ()),
            None => Err(()),
        }
    }

    fn stopping(id: Option<&Value>) -> Response {
        Response::ok(
            Some(codec::error(
                id,
                codes::INTERNAL_ERROR,
                "The MCP server is stopping",
                None,
            )),
            None,
        )
    }

    fn interrupted(id: Option<&Value>) -> Response {
        Response::ok(
            Some(codec::error(
                id,
                codes::INTERNAL_ERROR,
                "Request was interrupted",
                None,
            )),
            None,
        )
    }

    /// Blocking dispatch (in-process callers and tests). Never call from an async task.
    pub(crate) fn dispatch_blocking(
        self: &Arc<Self>,
        body: &str,
        session_header: Option<&str>,
    ) -> Response {
        match self.prepare(body, session_header) {
            Prepared::Done(r) => r,
            Prepared::Serial {
                message,
                session,
                now,
            } => {
                let id = message.id.clone();
                let (tx, rx) = mpsc::channel();
                let reply = Box::new(move |v| {
                    let _ = tx.send(v);
                });
                if self.submit(message, session, now, reply).is_err() {
                    return Self::stopping(id.as_ref());
                }
                match rx.recv() {
                    Ok(v) => self.finish(v, None),
                    Err(_) => Self::interrupted(id.as_ref()),
                }
            }
        }
    }

    /// The transport's dispatch: serial work is awaited without blocking a worker.
    pub(crate) async fn dispatch_async(
        self: &Arc<Self>,
        body: String,
        session_header: Option<String>,
    ) -> Response {
        match self.prepare(&body, session_header.as_deref()) {
            Prepared::Done(r) => r,
            Prepared::Serial {
                message,
                session,
                now,
            } => {
                let id = message.id.clone();
                let (tx, rx) = tokio::sync::oneshot::channel();
                let reply = Box::new(move |v| {
                    let _ = tx.send(v);
                });
                if self.submit(message, session, now, reply).is_err() {
                    return Self::stopping(id.as_ref());
                }
                match rx.await {
                    Ok(v) => self.finish(v, None),
                    Err(_) => Self::interrupted(id.as_ref()),
                }
            }
        }
    }
}

fn scrub(v: &mut Value, secrets: &[String]) {
    match v {
        Value::String(s) => {
            for secret in secrets {
                if s.contains(secret.as_str()) {
                    *s = s.replace(secret.as_str(), mmcl_log::redact::REDACTED);
                }
            }
        }
        Value::Array(a) => a.iter_mut().for_each(|x| scrub(x, secrets)),
        Value::Object(o) => o.values_mut().for_each(|x| scrub(x, secrets)),
        _ => {}
    }
}

struct Running {
    transport: LoopbackTransport,
    endpoint: Option<PathBuf>,
}

#[derive(Default)]
struct State {
    running: Option<Running>,
    port: u16,
    token: String,
    extra_secrets: Vec<String>,
}

/// The server. Lifecycle methods are mutually exclusive.
pub struct McpServer {
    core: Arc<Core>,
    activity: Arc<ActivityLog>,
    launcher_version: String,
    state: Mutex<State>,
}

impl McpServer {
    pub fn new(
        tools: Arc<ToolRegistry>,
        resources: Arc<ResourceRegistry>,
        authorizer: Arc<dyn Authorizer>,
        activity: Arc<ActivityLog>,
        launcher_version: &str,
    ) -> McpServer {
        let handler = RequestHandler::new(
            tools,
            resources,
            authorizer,
            activity.clone(),
            launcher_version,
        );
        McpServer {
            core: Arc::new(Core {
                sessions: SessionRegistry::new(),
                handler,
                dispatcher: Mutex::new(None),
                secrets: RwLock::new(Vec::new()),
            }),
            activity,
            launcher_version: launcher_version.to_string(),
            state: Mutex::new(State::default()),
        }
    }

    /// Another literal that must never leave the process (the single-instance IPC token).
    /// Values shorter than 8 characters are ignored.
    pub fn add_secret(&self, secret: &str) {
        let s = secret.trim();
        if s.len() < 8 {
            return;
        }
        let mut st = self.state.lock();
        if !st.extra_secrets.iter().any(|x| x == s) {
            st.extra_secrets.push(s.to_string());
        }
        let mut list = st.extra_secrets.clone();
        if !st.token.is_empty() {
            list.push(st.token.clone());
        }
        *self.core.secrets.write() = list;
    }

    /// Binds `requested_port` (a busy port falls back to an OS-assigned one), writes the
    /// endpoint file when a path is given, and returns the bound port. A blank token is
    /// replaced by a fresh one (test paths); production passes the persisted token.
    pub fn start(
        &self,
        requested_port: u16,
        endpoint_file: Option<&Path>,
        bearer_token: &str,
    ) -> Result<u16, McpError> {
        let mut st = self.state.lock();
        if st.running.is_some() {
            return Err(McpError::IllegalState(
                "The MCP server is already running".into(),
            ));
        }
        let token = if bearer_token.trim().is_empty() {
            token::generate()
        } else {
            bearer_token.trim().to_string()
        };
        let listener = match bind_loopback(requested_port) {
            Ok(l) => l,
            Err(_) => {
                logging::warn(
                    "log.mcpServer.portUnavailable",
                    &[&requested_port.to_string()],
                );
                bind_loopback(0)?
            }
        };

        let (tx, rx) = mpsc::channel::<Job>();
        std::thread::Builder::new()
            .name("mcp-dispatch".into())
            .spawn(move || {
                while let Ok(job) = rx.recv() {
                    job();
                }
            })?;
        *self.core.dispatcher.lock() = Some(tx);
        let mut secrets = st.extra_secrets.clone();
        secrets.push(token.clone());
        *self.core.secrets.write() = secrets;

        let transport = match LoopbackTransport::start(listener, token.clone(), self.core.clone()) {
            Ok(t) => t,
            Err(e) => {
                *self.core.dispatcher.lock() = None;
                return Err(e.into());
            }
        };
        let port = transport.port();
        let mut running = Running {
            transport,
            endpoint: None,
        };
        if let Some(path) = endpoint_file {
            let e = Endpoint {
                port,
                token: token.clone(),
                pid: i64::from(std::process::id()),
                launcher_version: self.launcher_version.clone(),
            };
            if let Err(err) = endpoint::write(path, &e) {
                running.transport.stop();
                *self.core.dispatcher.lock() = None;
                self.core.sessions.clear();
                return Err(err.into());
            }
            running.endpoint = Some(path.to_path_buf());
        }
        st.running = Some(running);
        st.port = port;
        st.token = token;
        logging::std("log.mcpServer.listening", &[&port.to_string()]);
        Ok(port)
    }

    /// Deletes the endpoint file, closes the listener, abandons queued and running calls
    /// (a blocked consent wait answers deny), clears the token and sessions. Idempotent.
    pub fn stop(&self) {
        let mut st = self.state.lock();
        let Some(mut running) = st.running.take() else {
            return;
        };
        if let Some(p) = &running.endpoint {
            endpoint::delete(p);
        }
        running.transport.stop();
        *self.core.dispatcher.lock() = None;
        st.token.clear();
        st.port = 0;
        *self.core.secrets.write() = st.extra_secrets.clone();
        self.core.sessions.clear();
        logging::std("log.mcpServer.stopped", &[]);
    }

    pub fn is_running(&self) -> bool {
        self.state.lock().running.is_some()
    }

    /// The bound port; 0 when stopped.
    pub fn port(&self) -> u16 {
        self.state.lock().port
    }

    /// The bearer token in use (in-process only: it must never reach a client).
    pub fn token(&self) -> String {
        self.state.lock().token.clone()
    }

    /// The live sessions (idle ones are swept first).
    pub fn sessions(&self) -> Vec<SessionSnapshot> {
        self.core.sessions.evict_idle(now_ms());
        self.core
            .sessions
            .all()
            .iter()
            .map(|s| s.snapshot())
            .collect()
    }

    pub fn activity_log(&self) -> &Arc<ActivityLog> {
        &self.activity
    }

    /// Handles one POST body as the transport would. Blocks while a serial call runs; never
    /// call from an async task.
    pub fn dispatch(&self, body: &str, session_header: Option<&str>) -> Response {
        self.core.dispatch_blocking(body, session_header)
    }

    /// [`dispatch`](Self::dispatch) for async callers.
    pub async fn dispatch_async(&self, body: String, session_header: Option<String>) -> Response {
        self.core.dispatch_async(body, session_header).await
    }

    /// Ends a session (what a DELETE does); `true` when one was removed.
    pub fn close_session(&self, id: &str) -> bool {
        self.core.close_session(id)
    }
}

impl Drop for McpServer {
    fn drop(&mut self) {
        self.stop();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::approval::Authorizer;
    use crate::tools::test_support::*;
    use crate::tools::{Args, CallContext, Tool, read_only};
    use serde_json::json;

    struct Allow;
    impl Authorizer for Allow {
        fn authorize(&self, _: &dyn Tool, _: &CallContext, _: &Args) -> bool {
            true
        }
    }

    fn server() -> McpServer {
        let view = Arc::new(StubView::with_packs(vec![pack("Alto", true)]));
        let mut tools = ToolRegistry::new();
        read_only::register_all(&mut tools, view).unwrap();
        McpServer::new(
            Arc::new(tools),
            Arc::new(ResourceRegistry::new()),
            Arc::new(Allow),
            Arc::new(ActivityLog::new()),
            "9.9.9",
        )
    }

    #[test]
    fn requires_serial_dispatch_only_for_calls_and_reads() {
        assert!(requires_serial_dispatch("tools/call"));
        assert!(requires_serial_dispatch("resources/read"));
        for m in [
            "initialize",
            "ping",
            "tools/list",
            "resources/list",
            "resources/templates/list",
            "notifications/initialized",
        ] {
            assert!(!requires_serial_dispatch(m), "{m}");
        }
    }

    #[test]
    fn a_parse_error_is_http_200() {
        let s = server();
        let r = s.dispatch("{oops", None);
        assert_eq!(r.status, 200);
        assert_eq!(r.json.unwrap()["error"]["code"], json!(-32700));
    }

    #[test]
    fn sessionless_ping_creates_nothing() {
        let s = server();
        let r = s.dispatch(r#"{"jsonrpc":"2.0","id":1,"method":"ping"}"#, None);
        assert_eq!(r.json.unwrap()["result"], json!({}));
        assert!(r.session_id.is_none());
        assert!(s.sessions().is_empty());
    }

    #[test]
    fn missing_and_unknown_session() {
        let s = server();
        let r = s.dispatch(r#"{"jsonrpc":"2.0","id":1,"method":"tools/list"}"#, None);
        assert_eq!(r.status, 400);
        assert_eq!(r.json.unwrap()["error"]["code"], json!(-32600));
        let r = s.dispatch(
            r#"{"jsonrpc":"2.0","id":1,"method":"tools/list"}"#,
            Some("nope"),
        );
        assert_eq!(r.status, 404);
        assert_eq!(r.json.unwrap()["error"]["code"], json!(-32001));
        let r = s.dispatch(
            r#"{"jsonrpc":"2.0","method":"notifications/initialized"}"#,
            Some("nope"),
        );
        assert_eq!(r.status, 404);
        assert!(r.json.is_none());
    }

    #[test]
    fn an_id_less_initialize_registers_nothing() {
        let s = server();
        let r = s.dispatch(r#"{"jsonrpc":"2.0","method":"initialize"}"#, None);
        assert!(r.json.is_none());
        assert_eq!(r.status, 200);
        assert!(s.sessions().is_empty());
    }

    #[test]
    fn handshake_then_call_in_process() {
        let s = server();
        s.start(0, None, "").unwrap();
        let r = s.dispatch(r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"clientInfo":{"name":"c"}}}"#, None);
        let sid = r.session_id.unwrap();
        let r = s.dispatch(
            r#"{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_modpacks"}}"#,
            Some(&sid),
        );
        assert!(r.session_id.is_none(), "only initialize echoes the id");
        let text = r.json.unwrap()["result"]["content"][0]["text"]
            .as_str()
            .unwrap()
            .to_string();
        assert!(text.contains("Alto"));
        s.stop();
    }

    #[test]
    fn a_stopped_server_answers_serial_calls_with_stopping() {
        let s = server();
        let r = s.dispatch(r#"{"jsonrpc":"2.0","id":1,"method":"initialize"}"#, None);
        let sid = r.session_id.unwrap();
        let r = s.dispatch(
            r#"{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_modpacks"}}"#,
            Some(&sid),
        );
        assert_eq!(
            r.json.unwrap()["error"]["message"],
            json!("The MCP server is stopping")
        );
    }

    #[test]
    fn secrets_are_scrubbed_from_output() {
        let mut v = json!({"a":["xx SECRETVALUE yy", {"b":"SECRETVALUE"}], "n": 1});
        scrub(&mut v, &["SECRETVALUE".to_string()]);
        assert_eq!(
            v,
            json!({"a":["xx [REDACTED] yy", {"b":"[REDACTED]"}], "n": 1})
        );
    }

    #[test]
    fn start_twice_is_refused_and_stop_twice_is_harmless() {
        let s = server();
        let port = s.start(0, None, "").unwrap();
        assert!(port > 0);
        assert_eq!(s.port(), port);
        assert!(crate::token::is_well_formed(&s.token()));
        assert!(matches!(
            s.start(0, None, ""),
            Err(McpError::IllegalState(_))
        ));
        s.stop();
        s.stop();
        assert_eq!(s.port(), 0);
        assert!(s.token().is_empty());
        assert!(!s.is_running());
    }
}
