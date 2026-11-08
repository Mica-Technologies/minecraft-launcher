// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! MCP tools (Java `tools/`).
//!
//! A tool only sees the [`LauncherView`] (and, for the state-changing ones, the
//! [`LauncherActions`]). Their records have no field for a token, an account UUID or a
//! filesystem path: "is this tool read-only?" is a question about its dependencies, and no
//! tool can leak a credential it was never given. [`ToolResult::to_json`] redacts every text
//! block on top of that.

use std::sync::Arc;

use indexmap::IndexMap;
use serde_json::{Map, Value};

use crate::approval::RiskClass;
use crate::{BoxError, McpError};

pub mod authored;
pub mod mutating;
pub mod read_only;

/// A call's arguments.
pub type Args = Map<String, Value>;

/// Who is calling. Holds no credential.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CallContext {
    /// The client's self-reported name; `Unknown client` when blank.
    pub client_name: String,
    pub session_id: String,
}

/// What a blank client name becomes.
pub const UNKNOWN_CLIENT: &str = "Unknown client";

impl CallContext {
    pub fn new(client_name: &str, session_id: &str) -> CallContext {
        let name = if client_name.trim().is_empty() {
            UNKNOWN_CLIENT.to_string()
        } else {
            client_name.to_string()
        };
        CallContext {
            client_name: name,
            session_id: session_id.to_string(),
        }
    }
}

/// One tool. Must be safe off the GUI thread and never block indefinitely (calls are serial).
pub trait Tool: Send + Sync {
    /// The wire name (see [`is_valid_tool_name`]).
    fn name(&self) -> &str;
    /// The English protocol title.
    fn title(&self) -> &str;
    /// The English description, for the model.
    fn description(&self) -> &str;
    /// A JSON Schema object.
    fn input_schema(&self) -> Value;
    fn risk_class(&self) -> RiskClass;
    /// Runs the tool. An ordinary failure is `Ok(ToolResult::error(..))`; `Err` is for the
    /// unexpected and is answered `-32603` without its message.
    fn invoke(&self, ctx: &CallContext, args: &Args) -> Result<ToolResult, BoxError>;

    /// The content gate, run before approval: a refusal never becomes a prompt.
    fn validate_before_approval(&self, _args: &Args) -> Option<String> {
        None
    }

    /// An extra line for the consent dialog. Must be bounded and not block for long.
    fn consent_detail(&self, _args: &Args) -> Option<String> {
        None
    }

    /// The localized `mcp.tool.<name>.title`, else [`Tool::title`].
    fn display_title(&self) -> String {
        localized_or(&format!("mcp.tool.{}.title", self.name()), self.title())
    }

    /// The localized `mcp.tool.<name>.description`, else [`Tool::description`].
    fn display_description(&self) -> String {
        localized_or(
            &format!("mcp.tool.{}.description", self.name()),
            self.description(),
        )
    }

    /// The `tools/list` entry: `name`, `title`, `description`, `inputSchema`.
    fn describe(&self) -> Value {
        let mut m = Map::new();
        m.insert("name".into(), Value::String(self.name().into()));
        m.insert("title".into(), Value::String(self.title().into()));
        m.insert(
            "description".into(),
            Value::String(self.description().into()),
        );
        m.insert("inputSchema".into(), self.input_schema());
        Value::Object(m)
    }
}

fn localized_or(key: &str, fallback: &str) -> String {
    mmcl_i18n::lookup(key)
        .map(str::to_string)
        .unwrap_or_else(|| fallback.to_string())
}

/// What a tool returns. [`to_json`](Self::to_json) is the redaction boundary.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ToolResult {
    blocks: Vec<String>,
    is_error: bool,
}

impl ToolResult {
    /// One text block.
    pub fn text(s: impl Into<String>) -> ToolResult {
        ToolResult {
            blocks: vec![s.into()],
            is_error: false,
        }
    }

    /// Pretty (2-space) JSON in one text block; `null` becomes `{}`.
    pub fn json(v: &Value) -> ToolResult {
        let v = if v.is_null() {
            Value::Object(Map::new())
        } else {
            v.clone()
        };
        ToolResult::text(serde_json::to_string_pretty(&v).unwrap_or_else(|_| "{}".into()))
    }

    /// A failed call (`isError: true`): a successful JSON-RPC result, not a protocol error.
    pub fn error(msg: impl Into<String>) -> ToolResult {
        ToolResult {
            blocks: vec![msg.into()],
            is_error: true,
        }
    }

    /// Several text blocks.
    pub fn texts(blocks: Vec<String>) -> ToolResult {
        ToolResult {
            blocks,
            is_error: false,
        }
    }

    pub fn is_error(&self) -> bool {
        self.is_error
    }

    /// The unredacted blocks (in-process and tests only).
    pub fn raw_text_blocks(&self) -> &[String] {
        &self.blocks
    }

    /// `{"content":[{"type":"text","text":<redact_strict(block)>}…],"isError":bool}`.
    pub fn to_json(&self) -> Value {
        let content: Vec<Value> = self
            .blocks
            .iter()
            .map(|b| {
                let mut m = Map::new();
                m.insert("type".into(), Value::String("text".into()));
                m.insert(
                    "text".into(),
                    Value::String(mmcl_log::redact_strict(b).into_owned()),
                );
                Value::Object(m)
            })
            .collect();
        let mut m = Map::new();
        m.insert("content".into(), Value::Array(content));
        m.insert("isError".into(), Value::Bool(self.is_error));
        Value::Object(m)
    }
}

/// 1–64 characters of `[a-z0-9_-]`, starting and ending with a letter or digit.
pub fn is_valid_tool_name(name: &str) -> bool {
    let b = name.as_bytes();
    if b.is_empty() || b.len() > 64 {
        return false;
    }
    let edge = |c: u8| c.is_ascii_lowercase() || c.is_ascii_digit();
    let inner = |c: u8| edge(c) || c == b'_' || c == b'-';
    edge(b[0]) && edge(b[b.len() - 1]) && b.iter().all(|&c| inner(c))
}

/// The pattern [`is_valid_tool_name`] checks.
pub const VALID_TOOL_NAME: &str = "^[a-z0-9](?:[a-z0-9_-]{0,62}[a-z0-9])?$";

/// The registered tools, in registration order.
#[derive(Default)]
pub struct ToolRegistry {
    tools: IndexMap<String, Arc<dyn Tool>>,
}

impl ToolRegistry {
    pub fn new() -> ToolRegistry {
        ToolRegistry::default()
    }

    /// Adds a tool; refuses an invalid or duplicate name.
    pub fn register(&mut self, tool: Arc<dyn Tool>) -> Result<(), McpError> {
        let name = tool.name().to_string();
        if !is_valid_tool_name(&name) {
            return Err(McpError::InvalidArgument(format!(
                "Invalid MCP tool name: {name:?}"
            )));
        }
        if self.tools.contains_key(&name) {
            return Err(McpError::IllegalState(format!(
                "Duplicate MCP tool name: {name}"
            )));
        }
        self.tools.insert(name, tool);
        Ok(())
    }

    /// Exact, case-sensitive lookup.
    pub fn find(&self, name: &str) -> Option<Arc<dyn Tool>> {
        self.tools.get(name).cloned()
    }

    pub fn all(&self) -> Vec<Arc<dyn Tool>> {
        self.tools.values().cloned().collect()
    }

    pub fn len(&self) -> usize {
        self.tools.len()
    }

    pub fn is_empty(&self) -> bool {
        self.tools.is_empty()
    }

    /// `{"tools":[…]}` (the array is always present).
    pub fn list_result(&self) -> Value {
        let tools: Vec<Value> = self.tools.values().map(|t| t.describe()).collect();
        let mut m = Map::new();
        m.insert("tools".into(), Value::Array(tools));
        Value::Object(m)
    }
}

// ---- the launcher seams -----------------------------------------------------------------------

/// One pack as the model sees it.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct PackSummary {
    pub friendly_name: String,
    /// The installed version (the published one for an available pack).
    pub version: String,
    /// The published version.
    pub latest_version: String,
    pub update_available: bool,
    /// `""` when unknown.
    pub mod_loader: String,
    pub installed: bool,
    pub unstable: bool,
}

/// The launcher-wide status.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct LauncherStatus {
    pub launcher_version: String,
    pub signed_in: bool,
    /// The username only, never a UUID or token. `""` when signed out.
    pub username: String,
    pub installed_pack_count: usize,
}

/// A crash report with its diagnosis.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct CrashInfo {
    pub report_text: String,
    pub title: String,
    pub summary: String,
    /// The analyzer's category name, or `""`.
    pub category: String,
    /// Suggestion labels only.
    pub suggestions: Vec<String>,
}

/// A pack's disk use.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct PackFootprint {
    pub size_bytes: u64,
    pub world_count: u32,
    /// The bounded walk stopped early.
    pub approximate: bool,
}

/// A game that is preparing or running.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct RunningGame {
    pub friendly_name: String,
    /// The username, or `""` in server mode.
    pub account: String,
    /// `preparing` or `running`.
    pub phase: String,
    pub uptime_seconds: u64,
}

/// The only read surface tools and resources get.
pub trait LauncherView: Send + Sync {
    /// Installed packs first, then available ones; one entry per friendly name.
    fn packs(&self) -> Vec<PackSummary>;
    /// Exact (case-sensitive) friendly-name match over [`packs`](Self::packs).
    fn pack(&self, friendly_name: &str) -> Option<PackSummary> {
        self.packs()
            .into_iter()
            .find(|p| p.friendly_name == friendly_name)
    }
    /// The manifest text, verbatim.
    fn manifest_of(&self, friendly_name: &str) -> Option<String>;
    fn latest_crash_of(&self, friendly_name: &str) -> Option<CrashInfo>;
    /// Bounded work.
    fn footprint_of(&self, friendly_name: &str) -> Option<PackFootprint>;
    fn status(&self) -> LauncherStatus;
    /// Oldest first.
    fn running_games(&self) -> Vec<RunningGame>;
}

/// The result of an action. Messages are model-facing and never carry exception text.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Outcome {
    pub ok: bool,
    pub message: String,
}

impl Outcome {
    pub fn ok(message: impl Into<String>) -> Outcome {
        Outcome {
            ok: true,
            message: message.into(),
        }
    }

    pub fn failed(message: impl Into<String>) -> Outcome {
        Outcome {
            ok: false,
            message: message.into(),
        }
    }
}

/// The only write surface. Ordinary failures are `Outcome { ok: false }`; nothing blocks
/// indefinitely.
pub trait LauncherActions: Send + Sync {
    fn install_from_url(&self, url: &str) -> Outcome;
    fn uninstall(&self, friendly_name: &str) -> Outcome;
    /// Returns once the launch has *started*, not when the game has loaded.
    fn launch(&self, friendly_name: &str, account_username: Option<&str>) -> Outcome;
    fn why_launch_blocked(&self, friendly_name: &str, account: Option<&str>) -> Option<String>;
    fn stop_game(&self, friendly_name: &str) -> Outcome;
    fn create_pack(
        &self,
        name: &str,
        mod_loader: Option<&str>,
        mod_loader_url: Option<&str>,
    ) -> Outcome;
    fn fork_pack(&self, source_friendly_name: &str, new_name: &str) -> Outcome;
    fn add_mod(
        &self,
        friendly_name: &str,
        mod_name: &str,
        remote_url: &str,
        local_path: &str,
    ) -> Outcome;
    fn remove_mod(&self, friendly_name: &str, identifier: &str) -> Outcome;
}

/// A string argument: absent or non-primitive → `""`, then trimmed.
pub(crate) fn string_arg(args: &Args, key: &str) -> String {
    crate::protocol::read_string(args, key).trim().to_string()
}

/// `No modpack named "<n>". Call list_modpacks to see the available friendly names.`
pub(crate) fn unknown_pack(name: &str) -> String {
    format!("No modpack named \"{name}\". Call list_modpacks to see the available friendly names.")
}

/// The pack JSON entry: `friendlyName, version, latestVersion, updateAvailable, modLoader,
/// installed, unstable`.
pub fn pack_entry(p: &PackSummary) -> Value {
    let mut m = Map::new();
    m.insert(
        "friendlyName".into(),
        Value::String(p.friendly_name.clone()),
    );
    m.insert("version".into(), Value::String(p.version.clone()));
    m.insert(
        "latestVersion".into(),
        Value::String(p.latest_version.clone()),
    );
    m.insert("updateAvailable".into(), Value::Bool(p.update_available));
    m.insert("modLoader".into(), Value::String(p.mod_loader.clone()));
    m.insert("installed".into(), Value::Bool(p.installed));
    m.insert("unstable".into(), Value::Bool(p.unstable));
    Value::Object(m)
}

#[cfg(test)]
pub(crate) mod test_support;

#[cfg(test)]
mod tests {
    //! Ports of `McpToolRegistryTest` and `McpToolResultTest`.
    use super::*;
    use serde_json::json;

    struct Named(&'static str);

    impl Tool for Named {
        fn name(&self) -> &str {
            self.0
        }
        fn title(&self) -> &str {
            "T"
        }
        fn description(&self) -> &str {
            "D"
        }
        fn input_schema(&self) -> Value {
            json!({"type":"object","properties":{}})
        }
        fn risk_class(&self) -> RiskClass {
            RiskClass::ReadOnly
        }
        fn invoke(&self, _: &CallContext, _: &Args) -> Result<ToolResult, BoxError> {
            Ok(ToolResult::text("x"))
        }
    }

    #[test]
    fn valid_names_are_accepted() {
        for n in ["a", "list_modpacks", "a-b", "x1", "9", &"a".repeat(64)] {
            assert!(is_valid_tool_name(n), "{n}");
        }
    }

    #[test]
    fn invalid_names_are_rejected() {
        for n in [
            "",
            " ",
            "a b",
            "a\tb",
            "a\nb",
            "a\0b",
            "List",
            "a;b",
            "a/b",
            "a\\b",
            "../x",
            "a$",
            "_a",
            "a_",
            "-a",
            "a-",
            &"a".repeat(65),
            "a.b",
        ] {
            assert!(!is_valid_tool_name(n), "{n:?}");
        }
    }

    #[test]
    fn register_refuses_invalid_and_duplicate() {
        let mut r = ToolRegistry::new();
        assert!(matches!(
            r.register(Arc::new(Named("Bad Name"))),
            Err(McpError::InvalidArgument(_))
        ));
        r.register(Arc::new(Named("a"))).unwrap();
        assert!(matches!(
            r.register(Arc::new(Named("a"))),
            Err(McpError::IllegalState(_))
        ));
    }

    #[test]
    fn order_is_preserved_and_lookup_is_exact() {
        let mut r = ToolRegistry::new();
        for n in ["c", "a", "b"] {
            r.register(Arc::new(Named(n))).unwrap();
        }
        let names: Vec<String> = r.all().iter().map(|t| t.name().to_string()).collect();
        assert_eq!(names, ["c", "a", "b"]);
        assert!(r.find("a").is_some());
        assert!(r.find("A").is_none());
        assert_eq!(r.len(), 3);
    }

    #[test]
    fn listing_fields_in_order_and_empty_array() {
        let r = ToolRegistry::new();
        assert_eq!(r.list_result(), json!({"tools":[]}));
        let mut r = ToolRegistry::new();
        r.register(Arc::new(Named("a"))).unwrap();
        let l = r.list_result();
        let keys: Vec<&String> = l["tools"][0].as_object().unwrap().keys().collect();
        assert_eq!(keys, ["name", "title", "description", "inputSchema"]);
    }

    const UUID_D: &str = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    const UUID_U: &str = "069a79f444e94726a5befca90e38aaf5";

    fn text_of(r: &ToolResult, i: usize) -> String {
        r.to_json()["content"][i]["text"]
            .as_str()
            .unwrap()
            .to_string()
    }

    #[test]
    fn tokens_and_uuids_are_redacted() {
        let r = ToolResult::text(format!(
            "--accessToken eyJ.abc --clientToken xyz {UUID_D} {UUID_U} token:abc:{UUID_U}"
        ));
        let t = text_of(&r, 0);
        for s in ["eyJ.abc", "xyz", UUID_D, UUID_U] {
            assert!(!t.contains(s), "{s} leaked: {t}");
        }
    }

    #[test]
    fn error_and_multi_block_and_json_results_are_redacted() {
        let e = ToolResult::error(format!("failed for {UUID_D}"));
        assert!(!text_of(&e, 0).contains(UUID_D));
        assert_eq!(e.to_json()["isError"], json!(true));
        let m = ToolResult::texts(vec!["ok".into(), UUID_U.into()]);
        assert_eq!(text_of(&m, 0), "ok");
        assert!(!text_of(&m, 1).contains(UUID_U));
        let j = ToolResult::json(&json!({"uuid": UUID_D}));
        assert!(!text_of(&j, 0).contains(UUID_D));
    }

    #[test]
    fn the_raw_accessor_is_not_redacted() {
        let r = ToolResult::text(UUID_D);
        assert_eq!(r.raw_text_blocks(), [UUID_D.to_string()]);
    }

    #[test]
    fn a_sha1_survives() {
        let sha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
        assert_eq!(text_of(&ToolResult::text(sha1), 0), sha1);
    }

    #[test]
    fn shapes_and_null_handling() {
        let r = ToolResult::text("x");
        assert_eq!(
            r.to_json(),
            json!({"content":[{"type":"text","text":"x"}],"isError":false})
        );
        assert_eq!(text_of(&ToolResult::json(&Value::Null), 0), "{}");
        assert_eq!(ToolResult::texts(vec![]).to_json()["content"], json!([]));
        assert_eq!(
            text_of(&ToolResult::json(&json!({"a":1})), 0),
            "{\n  \"a\": 1\n}"
        );
    }

    #[test]
    fn call_context_defaults() {
        assert_eq!(CallContext::new("  ", "").client_name, UNKNOWN_CLIENT);
        assert_eq!(CallContext::new("Claude", "s").client_name, "Claude");
    }
}
