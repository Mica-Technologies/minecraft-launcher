// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! JSON-RPC 2.0 framing (Java `protocol/`): the parsed [`JsonRpcMessage`], the [`codec`],
//! the error [`codes`] and the MCP [`methods`].

use serde_json::{Map, Value};

pub mod codec;

/// JSON-RPC and MCP error codes (Java `McpErrors`).
pub mod codes {
    /// Empty body, malformed JSON, bare `null` root.
    pub const PARSE_ERROR: i64 = -32700;
    /// Batch, non-object root, bad id / `jsonrpc` / `method` / `params`; pre-handshake call.
    pub const INVALID_REQUEST: i64 = -32600;
    /// `Unknown method: <m>`.
    pub const METHOD_NOT_FOUND: i64 = -32601;
    /// Missing or unknown tool, content-gate refusal, missing resource uri.
    pub const INVALID_PARAMS: i64 = -32602;
    /// A tool or resource failed, the dispatcher is stopping, relay-synthesized errors.
    pub const INTERNAL_ERROR: i64 = -32603;
    /// Unknown or expired session id (HTTP 404).
    pub const SESSION_NOT_FOUND: i64 = -32001;
    /// `No such resource: <uri>`.
    pub const RESOURCE_NOT_FOUND: i64 = -32002;
    /// The approval system said no.
    pub const REQUEST_DENIED: i64 = -32003;
}

/// MCP method names (Java `McpMethods`).
pub mod methods {
    pub const INITIALIZE: &str = "initialize";
    pub const INITIALIZED: &str = "notifications/initialized";
    pub const PING: &str = "ping";
    pub const TOOLS_LIST: &str = "tools/list";
    pub const TOOLS_CALL: &str = "tools/call";
    pub const RESOURCES_LIST: &str = "resources/list";
    pub const RESOURCES_READ: &str = "resources/read";
    pub const RESOURCES_TEMPLATES_LIST: &str = "resources/templates/list";
    /// Not implemented (answered with -32601).
    pub const PROMPTS_LIST: &str = "prompts/list";
    /// Not implemented (answered with -32601).
    pub const PROMPTS_GET: &str = "prompts/get";
}

/// The JSON-RPC version string.
pub const JSONRPC_VERSION: &str = "2.0";

/// One parsed request or notification.
#[derive(Clone, Debug, PartialEq)]
pub struct JsonRpcMessage {
    /// A string or number; `None` for a notification.
    pub id: Option<Value>,
    pub method: String,
    /// An object or array; `None` when absent or JSON `null`.
    pub params: Option<Value>,
}

impl JsonRpcMessage {
    /// A message with no id expects no answer.
    pub fn is_notification(&self) -> bool {
        self.id.is_none()
    }

    /// The params when they are an object; otherwise (absent or an array) an empty object.
    pub fn params_object(&self) -> Map<String, Value> {
        match &self.params {
            Some(Value::Object(m)) => m.clone(),
            _ => Map::new(),
        }
    }
}

/// Java `getAsString` on a member: absent, JSON `null` or a non-primitive → `""`; a primitive →
/// its string form (numbers and booleans stringify). Not trimmed.
pub fn read_string(obj: &Map<String, Value>, key: &str) -> String {
    primitive_string(obj.get(key))
}

/// The string form of a primitive value; `""` for anything else.
pub fn primitive_string(v: Option<&Value>) -> String {
    match v {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => n.to_string(),
        Some(Value::Bool(b)) => b.to_string(),
        _ => String::new(),
    }
}
