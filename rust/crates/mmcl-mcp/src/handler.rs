// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The MCP method handler (Java `McpRequestHandler`): pure of transport and GUI.

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Arc;

use serde_json::{Map, Value, json};

use crate::activity::{ActivityLog, Decision};
use crate::approval::{Authorizer, RiskClass};
use crate::logging;
use crate::protocol::{JsonRpcMessage, codec, codes, methods, read_string};
use crate::resources::{DEFAULT_GOVERNING_TOOL, ResourceRegistry};
use crate::session::Session;
use crate::tools::{Args, CallContext, Tool, ToolRegistry, ToolResult};
use crate::{BoxError, logging::panic_text};

/// The MCP protocol revision this server speaks. It does not negotiate.
pub const PROTOCOL_VERSION: &str = "2025-06-18";
/// `serverInfo.name`.
pub const SERVER_NAME: &str = "Mica Minecraft Launcher";

/// Dispatches one parsed message for one session.
pub struct RequestHandler {
    tools: Arc<ToolRegistry>,
    resources: Arc<ResourceRegistry>,
    authorizer: Arc<dyn Authorizer>,
    activity: Arc<ActivityLog>,
    launcher_version: String,
}

/// The stand-in that gates a resource whose governing tool is not registered: read-only,
/// never invoked.
struct GateTool {
    name: String,
    title: String,
    description: String,
}

impl Tool for GateTool {
    fn name(&self) -> &str {
        &self.name
    }
    fn title(&self) -> &str {
        &self.title
    }
    fn description(&self) -> &str {
        &self.description
    }
    fn input_schema(&self) -> Value {
        json!({"type":"object","properties":{}})
    }
    fn risk_class(&self) -> RiskClass {
        RiskClass::ReadOnly
    }
    fn invoke(&self, _: &CallContext, _: &Args) -> Result<ToolResult, BoxError> {
        Ok(ToolResult::error("This gate is never invoked."))
    }
}

impl RequestHandler {
    pub fn new(
        tools: Arc<ToolRegistry>,
        resources: Arc<ResourceRegistry>,
        authorizer: Arc<dyn Authorizer>,
        activity: Arc<ActivityLog>,
        launcher_version: impl Into<String>,
    ) -> RequestHandler {
        RequestHandler {
            tools,
            resources,
            authorizer,
            activity,
            launcher_version: launcher_version.into(),
        }
    }

    pub fn tools(&self) -> &ToolRegistry {
        &self.tools
    }

    pub fn activity(&self) -> &Arc<ActivityLog> {
        &self.activity
    }

    /// Answers `message`, or `None` for every notification (answering one would break the
    /// protocol; an unknown notification is not an error).
    pub fn handle(
        &self,
        message: &JsonRpcMessage,
        session: &Session,
        now_ms: u64,
    ) -> Option<Value> {
        session.record_activity(now_ms, message.method == methods::TOOLS_CALL);
        if message.is_notification() {
            if message.method == methods::INITIALIZED {
                logging::debug(
                    "log.mcpRequestHandler.clientInitialized",
                    &[&session.client_name()],
                );
            }
            return None;
        }
        let id = message.id.as_ref();
        if message.method == methods::INITIALIZE {
            return Some(self.initialize(message, session));
        }
        if !session.is_initialized() && message.method != methods::PING {
            return Some(codec::error(
                id,
                codes::INVALID_REQUEST,
                "Session is not initialized",
                None,
            ));
        }
        Some(match message.method.as_str() {
            methods::PING => codec::result(id, None),
            methods::TOOLS_LIST => codec::result(id, Some(self.tools.list_result())),
            methods::TOOLS_CALL => self.call_tool(message, session, now_ms),
            methods::RESOURCES_LIST => codec::result(id, Some(self.resources.list_result())),
            methods::RESOURCES_TEMPLATES_LIST => {
                codec::result(id, Some(self.resources.templates_list_result()))
            }
            methods::RESOURCES_READ => self.read_resource(message, session, now_ms),
            other => codec::error(
                id,
                codes::METHOD_NOT_FOUND,
                &format!("Unknown method: {other}"),
                None,
            ),
        })
    }

    fn initialize(&self, message: &JsonRpcMessage, session: &Session) -> Value {
        let params = message.params_object();
        let info = match params.get("clientInfo") {
            Some(Value::Object(o)) => o.clone(),
            _ => Map::new(),
        };
        session.initialize(&read_string(&info, "name"), &read_string(&info, "version"));
        let result = json!({
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {
                "tools": {"listChanged": false},
                "resources": {"subscribe": false, "listChanged": false}
            },
            "serverInfo": {"name": SERVER_NAME, "version": self.launcher_version}
        });
        codec::result(message.id.as_ref(), Some(result))
    }

    fn authorize(&self, tool: &dyn Tool, ctx: &CallContext, args: &Args) -> Result<bool, ()> {
        catch_unwind(AssertUnwindSafe(|| {
            self.authorizer.authorize(tool, ctx, args)
        }))
        .map_err(|_| ())
    }

    fn call_tool(&self, message: &JsonRpcMessage, session: &Session, now_ms: u64) -> Value {
        let id = message.id.as_ref();
        let params = message.params_object();
        let name = read_string(&params, "name");
        if name.is_empty() {
            return codec::error(id, codes::INVALID_PARAMS, "Missing tool name", None);
        }
        let Some(tool) = self.tools.find(&name) else {
            return codec::error(
                id,
                codes::INVALID_PARAMS,
                &format!("Unknown tool: {name}"),
                None,
            );
        };
        let args = match params.get("arguments") {
            Some(Value::Object(o)) => o.clone(),
            _ => Args::new(),
        };
        let ctx = session.call_context();

        let rejection =
            match catch_unwind(AssertUnwindSafe(|| tool.validate_before_approval(&args))) {
                Ok(r) => r,
                Err(p) => {
                    logging::error_with(
                        "log.mcpRequestHandler.preApprovalFailed",
                        &[&name],
                        &panic_text(p.as_ref()),
                    );
                    Some("The request could not be validated.".to_string())
                }
            };
        if let Some(reason) = rejection {
            logging::std(
                "log.mcpRequestHandler.refusedBeforeApproval",
                &[&name, &reason],
            );
            self.activity
                .record(now_ms, &ctx.client_name, &name, Decision::Refused, &reason);
            return codec::error(id, codes::INVALID_PARAMS, &reason, None);
        }

        let allowed = match self.authorize(tool.as_ref(), &ctx, &args) {
            Ok(a) => a,
            Err(()) => {
                logging::error("log.mcpRequestHandler.toolApprovalFailed", &[&name]);
                false
            }
        };
        if !allowed {
            self.activity.record(
                now_ms,
                &ctx.client_name,
                &name,
                Decision::Denied,
                "not approved",
            );
            return codec::error(
                id,
                codes::REQUEST_DENIED,
                &format!("Tool call was not approved: {name}"),
                None,
            );
        }

        match catch_unwind(AssertUnwindSafe(|| tool.invoke(&ctx, &args))) {
            Ok(Ok(result)) => {
                if result.is_error() {
                    let detail = result
                        .raw_text_blocks()
                        .first()
                        .cloned()
                        .unwrap_or_default();
                    self.activity.record(
                        now_ms,
                        &ctx.client_name,
                        &name,
                        Decision::Failed,
                        &detail,
                    );
                } else {
                    self.activity
                        .record(now_ms, &ctx.client_name, &name, Decision::Allowed, "");
                }
                codec::result(id, Some(result.to_json()))
            }
            Ok(Err(e)) => self.tool_threw(id, &ctx, &name, now_ms, &e.to_string()),
            Err(p) => self.tool_threw(id, &ctx, &name, now_ms, &panic_text(p.as_ref())),
        }
    }

    fn tool_threw(
        &self,
        id: Option<&Value>,
        ctx: &CallContext,
        name: &str,
        now_ms: u64,
        err: &str,
    ) -> Value {
        // The error text goes to the launcher log only: it may carry paths or command lines.
        logging::error_with("log.mcpRequestHandler.toolFailed", &[name], &err);
        self.activity.record(
            now_ms,
            &ctx.client_name,
            name,
            Decision::Failed,
            "the tool threw",
        );
        codec::error(
            id,
            codes::INTERNAL_ERROR,
            &format!("Tool failed: {name}"),
            None,
        )
    }

    fn read_resource(&self, message: &JsonRpcMessage, session: &Session, now_ms: u64) -> Value {
        let id = message.id.as_ref();
        let params = message.params_object();
        let uri = read_string(&params, "uri");
        if uri.is_empty() {
            return codec::error(id, codes::INVALID_PARAMS, "Missing resource uri", None);
        }
        let Some(m) = self.resources.resolve(&uri) else {
            return codec::error(
                id,
                codes::RESOURCE_NOT_FOUND,
                &format!("No such resource: {uri}"),
                None,
            );
        };
        let gate_name = {
            let g = m.resource.governing_tool_name();
            if g.trim().is_empty() {
                DEFAULT_GOVERNING_TOOL.to_string()
            } else {
                g.to_string()
            }
        };
        let gate: Arc<dyn Tool> = match self.tools.find(&gate_name) {
            Some(t) => t,
            None => Arc::new(GateTool {
                name: gate_name.clone(),
                title: m.resource.name().to_string(),
                description: m.resource.description().to_string(),
            }),
        };
        let mut gate_args = Args::new();
        for (k, v) in &m.params {
            gate_args.insert(k.clone(), Value::String(v.clone()));
        }
        gate_args.insert("uri".into(), Value::String(uri.clone()));
        let ctx = session.call_context();
        let allowed = match self.authorize(gate.as_ref(), &ctx, &gate_args) {
            Ok(a) => a,
            Err(()) => {
                logging::error("log.mcpRequestHandler.resourceApprovalFailed", &[&uri]);
                false
            }
        };
        let detail = format!("resource {uri}");
        if !allowed {
            self.activity.record(
                now_ms,
                &ctx.client_name,
                &gate_name,
                Decision::Denied,
                &detail,
            );
            return codec::error(
                id,
                codes::REQUEST_DENIED,
                &format!("Resource read was not approved: {uri}"),
                None,
            );
        }
        let text = match catch_unwind(AssertUnwindSafe(|| m.resource.read(&m.params))) {
            Ok(Ok(t)) => t,
            Ok(Err(e)) => {
                return self.read_failed(id, &ctx, &gate_name, &uri, now_ms, &e.to_string());
            }
            Err(p) => {
                return self.read_failed(
                    id,
                    &ctx,
                    &gate_name,
                    &uri,
                    now_ms,
                    &panic_text(p.as_ref()),
                );
            }
        };
        self.activity.record(
            now_ms,
            &ctx.client_name,
            &gate_name,
            Decision::Allowed,
            &detail,
        );
        let mut content = Map::new();
        content.insert("uri".into(), Value::String(uri));
        content.insert(
            "mimeType".into(),
            Value::String(m.resource.mime_type().to_string()),
        );
        content.insert(
            "text".into(),
            Value::String(mmcl_log::redact_strict(&text).into_owned()),
        );
        codec::result(id, Some(json!({ "contents": [Value::Object(content)] })))
    }

    fn read_failed(
        &self,
        id: Option<&Value>,
        ctx: &CallContext,
        gate: &str,
        uri: &str,
        now_ms: u64,
        err: &str,
    ) -> Value {
        logging::error_with("log.mcpRequestHandler.resourceReadFailed", &[uri], &err);
        self.activity.record(
            now_ms,
            &ctx.client_name,
            gate,
            Decision::Failed,
            &format!("resource {uri}"),
        );
        codec::error(
            id,
            codes::INTERNAL_ERROR,
            &format!("Could not read resource: {uri}"),
            None,
        )
    }
}

#[cfg(test)]
mod tests {
    //! Port of `McpRequestHandlerTest`.
    use super::*;
    use crate::protocol::codec::parse;
    use crate::resources::{Resource, launcher};
    use crate::seam_error;
    use crate::tools::test_support::*;
    use crate::tools::{CrashInfo, read_only};
    use indexmap::IndexMap;
    use parking_lot::Mutex;
    use std::sync::atomic::{AtomicUsize, Ordering};

    /// Allows or denies everything, recording what it saw.
    struct StubAuth {
        allow: bool,
        panics: bool,
        seen: Mutex<Vec<(String, Args)>>,
        deny_tool: Option<&'static str>,
    }

    impl Authorizer for StubAuth {
        fn authorize(&self, tool: &dyn Tool, _: &CallContext, args: &Args) -> bool {
            if self.panics {
                panic!("auth");
            }
            self.seen
                .lock()
                .push((tool.name().to_string(), args.clone()));
            self.allow && self.deny_tool != Some(tool.name())
        }
    }

    fn auth(allow: bool) -> Arc<StubAuth> {
        Arc::new(StubAuth {
            allow,
            panics: false,
            seen: Mutex::new(Vec::new()),
            deny_tool: None,
        })
    }

    struct CountingTool {
        calls: AtomicUsize,
        behavior: &'static str,
    }

    impl Tool for CountingTool {
        fn name(&self) -> &str {
            "counting"
        }
        fn title(&self) -> &str {
            "Counting"
        }
        fn description(&self) -> &str {
            "Counts."
        }
        fn input_schema(&self) -> Value {
            json!({"type":"object","properties":{}})
        }
        fn risk_class(&self) -> RiskClass {
            RiskClass::Mutating
        }
        fn invoke(&self, _: &CallContext, args: &Args) -> Result<ToolResult, BoxError> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            match self.behavior {
                "err" => Err(seam_error("secret C:\\Users\\x path")),
                "panic" => panic!("secret panic"),
                "error" => Ok(ToolResult::error("bad thing")),
                _ => Ok(ToolResult::text(format!(
                    "ran {} 069a79f4-44e9-4726-a5be-fca90e38aaf5",
                    args.len()
                ))),
            }
        }
        fn validate_before_approval(&self, args: &Args) -> Option<String> {
            match self.behavior {
                "gate" if args.contains_key("bad") => Some("bad arg".into()),
                "gate-panic" => panic!("gate"),
                _ => None,
            }
        }
    }

    struct Fx {
        h: RequestHandler,
        auth: Arc<StubAuth>,
        tool: Arc<CountingTool>,
        activity: Arc<ActivityLog>,
        session: Session,
    }

    fn fx_with(a: Arc<StubAuth>, behavior: &'static str, view: StubView) -> Fx {
        let view = Arc::new(view);
        let tool = Arc::new(CountingTool {
            calls: AtomicUsize::new(0),
            behavior,
        });
        let mut tools = ToolRegistry::new();
        read_only::register_all(&mut tools, view.clone()).unwrap();
        tools.register(tool.clone()).unwrap();
        let mut res = ResourceRegistry::new();
        launcher::register_all(&mut res, view).unwrap();
        let activity = Arc::new(ActivityLog::new());
        let h = RequestHandler::new(
            Arc::new(tools),
            Arc::new(res),
            a.clone(),
            activity.clone(),
            "1.2.3",
        );
        Fx {
            h,
            auth: a,
            tool,
            activity,
            session: Session::new("sid", 0),
        }
    }

    fn fx(allow: bool) -> Fx {
        fx_with(
            auth(allow),
            "ok",
            StubView::with_packs(vec![pack("Alto", true)]),
        )
    }

    fn send(f: &Fx, raw: &str) -> Option<Value> {
        let m = parse(raw).unwrap();
        f.h.handle(&m, &f.session, 10)
    }

    fn init(f: &Fx) {
        send(
            f,
            r#"{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"clientInfo":{"name":"Claude","version":"9"}}}"#,
        );
    }

    fn call(f: &Fx, tool: &str, args: Value) -> Value {
        let raw = json!({"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":tool,"arguments":args}});
        send(f, &raw.to_string()).unwrap()
    }

    fn read(f: &Fx, uri: &str) -> Value {
        let raw = json!({"jsonrpc":"2.0","id":6,"method":"resources/read","params":{"uri":uri}});
        send(f, &raw.to_string()).unwrap()
    }

    fn code(v: &Value) -> i64 {
        v["error"]["code"].as_i64().unwrap_or(0)
    }

    #[test]
    fn initialize_shape_and_identity() {
        let f = fx(true);
        let r = send(
            &f,
            r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999","clientInfo":{"name":"Claude","version":"2"}}}"#,
        )
        .unwrap();
        assert_eq!(
            r["result"],
            json!({"protocolVersion":"2025-06-18",
                   "capabilities":{"tools":{"listChanged":false},"resources":{"subscribe":false,"listChanged":false}},
                   "serverInfo":{"name":"Mica Minecraft Launcher","version":"1.2.3"}})
        );
        assert_eq!(f.session.client_name(), "Claude");
        send(
            &f,
            r#"{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"clientInfo":{"name":"Evil"}}}"#,
        );
        assert_eq!(f.session.client_name(), "Claude");
    }

    #[test]
    fn initialize_without_client_info() {
        let f = fx(true);
        let r = send(&f, r#"{"jsonrpc":"2.0","id":1,"method":"initialize"}"#).unwrap();
        assert!(r.get("result").is_some());
        assert!(f.session.is_initialized());
        assert_eq!(f.session.call_context().client_name, "Unknown client");
    }

    #[test]
    fn every_non_ping_method_is_refused_before_the_handshake() {
        let f = fx(true);
        for m in [
            "tools/list",
            "tools/call",
            "resources/list",
            "resources/read",
            "resources/templates/list",
            "bogus",
        ] {
            let r = send(&f, &format!(r#"{{"jsonrpc":"2.0","id":1,"method":"{m}"}}"#)).unwrap();
            assert_eq!(code(&r), codes::INVALID_REQUEST, "{m}");
            assert_eq!(r["error"]["message"], json!("Session is not initialized"));
        }
        let r = send(&f, r#"{"jsonrpc":"2.0","id":1,"method":"ping"}"#).unwrap();
        assert_eq!(r["result"], json!({}));
    }

    #[test]
    fn notifications_are_never_answered() {
        let f = fx(true);
        for m in [
            "notifications/initialized",
            "notifications/whatever",
            "tools/call",
            "initialize",
        ] {
            assert!(
                send(&f, &format!(r#"{{"jsonrpc":"2.0","method":"{m}"}}"#)).is_none(),
                "{m}"
            );
        }
    }

    #[test]
    fn unknown_method_and_prompts() {
        let f = fx(true);
        init(&f);
        for m in ["nope", "prompts/list", "prompts/get"] {
            let r = send(&f, &format!(r#"{{"jsonrpc":"2.0","id":1,"method":"{m}"}}"#)).unwrap();
            assert_eq!(code(&r), codes::METHOD_NOT_FOUND);
            assert_eq!(r["error"]["message"], json!(format!("Unknown method: {m}")));
        }
    }

    #[test]
    fn tools_list_after_handshake() {
        let f = fx(true);
        init(&f);
        let r = send(&f, r#"{"jsonrpc":"2.0","id":1,"method":"tools/list"}"#).unwrap();
        assert_eq!(r["result"]["tools"].as_array().unwrap().len(), 8);
    }

    #[test]
    fn an_approved_call_runs_and_is_redacted() {
        let f = fx(true);
        init(&f);
        let r = call(&f, "counting", json!({"a":1}));
        let text = r["result"]["content"][0]["text"].as_str().unwrap();
        assert!(text.starts_with("ran 1"));
        assert!(!text.contains("069a79f4"));
        assert_eq!(r["result"]["isError"], json!(false));
        assert_eq!(f.activity.recent()[0].decision, Decision::Allowed);
        assert_eq!(f.session.call_count(), 1);
    }

    #[test]
    fn missing_and_unknown_tool_names() {
        let f = fx(true);
        init(&f);
        let r = send(
            &f,
            r#"{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{}}"#,
        )
        .unwrap();
        assert_eq!(code(&r), codes::INVALID_PARAMS);
        assert_eq!(r["error"]["message"], json!("Missing tool name"));
        let r = call(&f, "install_modpack", json!({}));
        assert_eq!(
            r["error"]["message"],
            json!("Unknown tool: install_modpack")
        );
    }

    #[test]
    fn a_denied_call_never_reaches_the_tool() {
        let f = fx(false);
        init(&f);
        let r = call(&f, "counting", json!({}));
        assert_eq!(code(&r), codes::REQUEST_DENIED);
        assert_eq!(
            r["error"]["message"],
            json!("Tool call was not approved: counting")
        );
        assert_eq!(f.tool.calls.load(Ordering::SeqCst), 0);
        let e = &f.activity.recent()[0];
        assert_eq!(e.decision, Decision::Denied);
        assert_eq!(e.detail, "not approved");
    }

    #[test]
    fn a_throwing_authorizer_denies() {
        let a = Arc::new(StubAuth {
            allow: true,
            panics: true,
            seen: Mutex::new(Vec::new()),
            deny_tool: None,
        });
        let f = fx_with(a, "ok", StubView::default());
        init(&f);
        assert_eq!(
            code(&call(&f, "counting", json!({}))),
            codes::REQUEST_DENIED
        );
        assert_eq!(f.tool.calls.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn the_content_gate_runs_before_approval() {
        let f = fx_with(auth(true), "gate", StubView::default());
        init(&f);
        let r = call(&f, "counting", json!({"bad":1}));
        assert_eq!(code(&r), codes::INVALID_PARAMS);
        assert_eq!(r["error"]["message"], json!("bad arg"));
        assert!(f.auth.seen.lock().is_empty(), "authorizer never consulted");
        let e = &f.activity.recent()[0];
        assert_eq!(e.decision, Decision::Refused);
        assert_eq!(e.detail, "bad arg");
    }

    #[test]
    fn a_throwing_gate_refuses() {
        let f = fx_with(auth(true), "gate-panic", StubView::default());
        init(&f);
        let r = call(&f, "counting", json!({}));
        assert_eq!(
            r["error"]["message"],
            json!("The request could not be validated.")
        );
        assert_eq!(f.tool.calls.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn a_throwing_tool_does_not_leak_its_message() {
        for behavior in ["err", "panic"] {
            let f = fx_with(auth(true), behavior, StubView::default());
            init(&f);
            let r = call(&f, "counting", json!({}));
            assert_eq!(code(&r), codes::INTERNAL_ERROR);
            assert_eq!(r["error"]["message"], json!("Tool failed: counting"));
            assert!(!r.to_string().contains("secret"));
            let e = &f.activity.recent()[0];
            assert_eq!(e.decision, Decision::Failed);
            assert_eq!(e.detail, "the tool threw");
        }
    }

    #[test]
    fn a_tool_error_is_a_successful_result_recorded_as_failed() {
        let f = fx_with(auth(true), "error", StubView::default());
        init(&f);
        let r = call(&f, "counting", json!({}));
        assert_eq!(r["result"]["isError"], json!(true));
        let e = &f.activity.recent()[0];
        assert_eq!(e.decision, Decision::Failed);
        assert_eq!(e.detail, "bad thing");
    }

    #[test]
    fn non_object_arguments_read_as_empty() {
        let f = fx(true);
        init(&f);
        let r = call(&f, "counting", json!([1, 2]));
        assert!(
            r["result"]["content"][0]["text"]
                .as_str()
                .unwrap()
                .starts_with("ran 0")
        );
    }

    #[test]
    fn resource_read_content_and_redaction() {
        let v = StubView::with_packs(vec![pack("Alto", true)]);
        v.crashes.lock().push((
            "Alto".into(),
            CrashInfo {
                report_text: "--accessToken eyJ.secret crash".into(),
                ..CrashInfo::default()
            },
        ));
        let f = fx_with(auth(true), "ok", v);
        init(&f);
        let r = read(&f, "mica://modpack/Alto/crash-report");
        let c = &r["result"]["contents"][0];
        assert_eq!(c["uri"], json!("mica://modpack/Alto/crash-report"));
        assert_eq!(c["mimeType"], json!("text/plain"));
        assert!(!c["text"].as_str().unwrap().contains("eyJ.secret"));
        let e = &f.activity.recent()[0];
        assert_eq!(e.tool_name, "get_crash_report");
        assert_eq!(e.decision, Decision::Allowed);
        assert_eq!(e.detail, "resource mica://modpack/Alto/crash-report");
        assert_eq!(f.session.call_count(), 0, "reads are not tool calls");
    }

    #[test]
    fn resource_reads_are_authorized_as_the_governing_tool() {
        let f = fx(true);
        init(&f);
        read(&f, "mica://modpack/Alto/manifest");
        let seen = f.auth.seen.lock();
        assert_eq!(seen[0].0, "get_modpack_manifest");
        assert_eq!(seen[0].1["friendlyName"], json!("Alto"));
        assert_eq!(seen[0].1["uri"], json!("mica://modpack/Alto/manifest"));
    }

    #[test]
    fn a_disabled_tool_cannot_be_read_through_its_resource() {
        let a = Arc::new(StubAuth {
            allow: true,
            panics: false,
            seen: Mutex::new(Vec::new()),
            deny_tool: Some("list_modpacks"),
        });
        let f = fx_with(a, "ok", StubView::default());
        init(&f);
        let r = read(&f, "mica://packs");
        assert_eq!(code(&r), codes::REQUEST_DENIED);
        assert_eq!(
            r["error"]["message"],
            json!("Resource read was not approved: mica://packs")
        );
        assert_eq!(f.activity.recent()[0].decision, Decision::Denied);
    }

    #[test]
    fn missing_unknown_and_traversal_uris() {
        let f = fx(true);
        init(&f);
        let r = send(
            &f,
            r#"{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{}}"#,
        )
        .unwrap();
        assert_eq!(r["error"]["message"], json!("Missing resource uri"));
        for u in [
            "mica://nope",
            "mica://modpack/../manifest",
            "mica://modpack/%2e%2e/manifest",
        ] {
            let r = read(&f, u);
            assert_eq!(code(&r), codes::RESOURCE_NOT_FOUND, "{u}");
            assert_eq!(
                r["error"]["message"],
                json!(format!("No such resource: {u}"))
            );
        }
    }

    #[test]
    fn a_failing_read_does_not_leak() {
        let f = fx(true);
        init(&f);
        let r = read(&f, "mica://modpack/Nope/manifest");
        assert_eq!(code(&r), codes::INTERNAL_ERROR);
        assert_eq!(
            r["error"]["message"],
            json!("Could not read resource: mica://modpack/Nope/manifest")
        );
        assert_eq!(f.activity.recent()[0].decision, Decision::Failed);
    }

    struct Orphan;

    impl Resource for Orphan {
        fn uri_template(&self) -> &str {
            "mica://orphan"
        }
        fn name(&self) -> &str {
            "Orphan"
        }
        fn description(&self) -> &str {
            "No governing tool."
        }
        fn mime_type(&self) -> &str {
            "text/plain"
        }
        fn read(&self, _: &IndexMap<String, String>) -> Result<String, BoxError> {
            Ok("hi".into())
        }
    }

    #[test]
    fn an_unregistered_governing_tool_is_gated_as_read_only() {
        struct RiskAuth(Mutex<Vec<(String, RiskClass)>>);
        impl Authorizer for RiskAuth {
            fn authorize(&self, t: &dyn Tool, _: &CallContext, _: &Args) -> bool {
                self.0.lock().push((t.name().to_string(), t.risk_class()));
                true
            }
        }
        let a = Arc::new(RiskAuth(Mutex::new(Vec::new())));
        let mut res = ResourceRegistry::new();
        res.register(Arc::new(Orphan)).unwrap();
        let h = RequestHandler::new(
            Arc::new(ToolRegistry::new()),
            Arc::new(res),
            a.clone(),
            Arc::new(ActivityLog::new()),
            "v",
        );
        let s = Session::new("s", 0);
        s.initialize("c", "");
        let m = parse(r#"{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"mica://orphan"}}"#).unwrap();
        let r = h.handle(&m, &s, 1).unwrap();
        assert_eq!(r["result"]["contents"][0]["text"], json!("hi"));
        assert_eq!(
            a.0.lock()[0],
            ("read_resource".to_string(), RiskClass::ReadOnly)
        );
    }

    #[test]
    fn listings_are_served() {
        let f = fx(true);
        init(&f);
        let r = send(&f, r#"{"jsonrpc":"2.0","id":1,"method":"resources/list"}"#).unwrap();
        assert!(r["result"]["resources"].as_array().unwrap().len() >= 3);
        let r = send(
            &f,
            r#"{"jsonrpc":"2.0","id":1,"method":"resources/templates/list"}"#,
        )
        .unwrap();
        assert_eq!(
            r["result"]["resourceTemplates"].as_array().unwrap().len(),
            2
        );
    }
}
