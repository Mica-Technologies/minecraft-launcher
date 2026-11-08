// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! What Settings -> Security needs from the MCP server (`McpBootstrap`, `McpClientSetup`,
//! `McpAccessToken`). `mmcl-mcp` sits beside this crate, not below it, so the panel talks to it
//! through [`McpPanel`]; `mmcl-app` installs an adapter over its `McpBootstrap` with
//! [`set_mcp_panel`]. Without one, the MCP switches still save (the engine owns those keys) and
//! the live parts (status, endpoint, token, sessions, activity, tool policies) show the server
//! as stopped.

use std::rc::Rc;

use gpui::{App, Global};

/// A per-tool approval policy (`settings.mcp.policy.*`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum McpPolicy {
    /// Follow the risk class and the global switches.
    Default,
    /// Never ask.
    AlwaysAllow,
    /// Always ask.
    Ask,
    /// Refuse every call.
    Disabled,
}

impl McpPolicy {
    /// Every policy, in menu order.
    pub const ALL: [McpPolicy; 4] = [
        McpPolicy::Default,
        McpPolicy::AlwaysAllow,
        McpPolicy::Ask,
        McpPolicy::Disabled,
    ];

    /// The label key.
    pub const fn label_key(self) -> &'static str {
        match self {
            McpPolicy::Default => "settings.mcp.policy.default",
            McpPolicy::AlwaysAllow => "settings.mcp.policy.alwaysAllow",
            McpPolicy::Ask => "settings.mcp.policy.ask",
            McpPolicy::Disabled => "settings.mcp.policy.disabled",
        }
    }

    /// The menu index.
    pub fn index(self) -> usize {
        Self::ALL.iter().position(|p| *p == self).unwrap_or(0)
    }
}

/// The MCP clients a setup snippet can be generated for (`settings.mcp.connect.client.*`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum McpClient {
    ClaudeCode,
    Cursor,
    Codex,
    Http,
    Stdio,
}

impl McpClient {
    /// Every client, in menu order.
    pub const ALL: [McpClient; 5] = [
        McpClient::ClaudeCode,
        McpClient::Cursor,
        McpClient::Codex,
        McpClient::Http,
        McpClient::Stdio,
    ];

    /// The label key.
    pub const fn label_key(self) -> &'static str {
        match self {
            McpClient::ClaudeCode => "settings.mcp.connect.client.claudeCode",
            McpClient::Cursor => "settings.mcp.connect.client.cursor",
            McpClient::Codex => "settings.mcp.connect.client.codex",
            McpClient::Http => "settings.mcp.connect.client.http",
            McpClient::Stdio => "settings.mcp.connect.client.stdio",
        }
    }
}

/// One connected MCP session.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct McpSessionRow {
    /// The client's name (`None` = unnamed).
    pub name: Option<String>,
    /// How long ago it connected (ms).
    pub age_ms: u64,
    /// Tool calls so far.
    pub calls: u64,
}

/// One recent tool call.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct McpActivityRow {
    /// How long ago (ms).
    pub age_ms: u64,
    /// The tool name.
    pub tool: String,
    /// The decision, lower case (`allowed`, `denied`, `refused`, `failed`).
    pub decision: String,
}

/// One tool with its policy.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct McpToolRow {
    /// The tool id.
    pub name: String,
    /// The display title.
    pub title: String,
    /// The risk class's localization key (`mcp.risk.*`).
    pub risk_key: String,
    /// The current policy.
    pub policy: McpPolicy,
}

/// The MCP server, as the Settings panel sees it. Every call must return quickly (no I/O
/// beyond the server's in-memory state).
pub trait McpPanel {
    /// `McpBootstrap.refresh`: start or stop to match the settings, rebuild the tool registry.
    fn refresh(&self);
    /// Whether the server is listening.
    fn is_running(&self) -> bool;
    /// The endpoint URL for the bound (or configured) port.
    fn endpoint_url(&self) -> String;
    /// The connected sessions.
    fn sessions(&self) -> Vec<McpSessionRow>;
    /// How many remembered approvals exist.
    fn grant_count(&self) -> usize;
    /// Forgets every remembered approval.
    fn revoke_grants(&self);
    /// The newest tool calls, newest first, at most `max`.
    fn activity(&self, max: usize) -> Vec<McpActivityRow>;
    /// Clears the activity log.
    fn clear_activity(&self);
    /// Every tool with its policy.
    fn tools(&self) -> Vec<McpToolRow>;
    /// Sets (or with [`McpPolicy::Default`] clears) a tool's policy.
    fn set_policy(&self, tool: &str, policy: McpPolicy);
    /// The access token, creating it if needed.
    fn ensure_token(&self) -> String;
    /// The token masked for display (`McpAccessToken.mask`).
    fn masked_token(&self) -> String;
    /// Replaces the token (connected clients must be reconfigured).
    fn regenerate_token(&self) -> String;
    /// The setup snippet for `client` (`McpClientSetup.snippet`); `None` when it cannot be
    /// built.
    fn setup_snippet(&self, client: McpClient, token: &str) -> Option<String>;
}

/// The installed panel adapter.
#[derive(Clone)]
pub struct McpPanelGlobal(pub Rc<dyn McpPanel>);

impl Global for McpPanelGlobal {}

/// Installs the MCP adapter (`mmcl-app`, once per app).
pub fn set_mcp_panel(cx: &mut App, panel: Rc<dyn McpPanel>) {
    cx.set_global(McpPanelGlobal(panel));
}

/// The installed adapter, if any.
pub fn mcp_panel(cx: &App) -> Option<Rc<dyn McpPanel>> {
    cx.try_global::<McpPanelGlobal>().map(|g| g.0.clone())
}

/// How many activity rows the panel shows (`MCP_ACTIVITY_ROWS_SHOWN`).
pub const ACTIVITY_ROWS_SHOWN: usize = 8;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_label_resolves() {
        for p in McpPolicy::ALL {
            assert!(mmcl_i18n::english(p.label_key()).is_some(), "{p:?}");
            assert_eq!(McpPolicy::ALL[p.index()], p);
        }
        for c in McpClient::ALL {
            assert!(mmcl_i18n::english(c.label_key()).is_some(), "{c:?}");
        }
    }
}
