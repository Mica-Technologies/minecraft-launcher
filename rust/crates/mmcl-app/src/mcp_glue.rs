// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The app-side MCP adapters (mcp spec §8): the GPUI consent dialog behind
//! [`DeadlineConsentPrompt`], and the Settings panel adapter over [`McpBootstrap`].

use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use mmcl_engine::Engine;
use mmcl_mcp::McpBootstrap;
use mmcl_mcp::activity::Decision;
use mmcl_mcp::approval::{
    Answer as McpAnswer, ConsentPresenter, ConsentTicket, DeadlineConsentPrompt, Policy,
};
use mmcl_mcp::client_setup::{self, Client};
use mmcl_ui::GuiHandle;
use mmcl_ui::screens::settings::mcp::{
    McpActivityRow, McpClient, McpPanel, McpPolicy, McpSessionRow, McpToolRow,
};
use mmcl_ui_kit::dialogs::Answer;

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Shows the consent question as a launcher dialog. The deadline is enforced by
/// [`DeadlineConsentPrompt`]; a dialog still open after it expires answers into the void.
pub struct GuiConsentPresenter {
    gui: GuiHandle,
}

impl GuiConsentPresenter {
    /// The consent prompt the MCP host uses.
    pub fn prompt(gui: GuiHandle) -> Arc<DeadlineConsentPrompt> {
        Arc::new(DeadlineConsentPrompt::new(Arc::new(GuiConsentPresenter {
            gui,
        })))
    }
}

/// The answer a dialog button means.
pub fn map_answer(answer: Answer, second: McpAnswer) -> McpAnswer {
    match answer {
        Answer::Primary => McpAnswer::AllowOnce,
        Answer::Secondary => second,
        Answer::Closed => McpAnswer::Deny,
    }
}

impl ConsentPresenter for GuiConsentPresenter {
    fn is_available(&self) -> bool {
        self.gui.is_started()
    }

    fn present(&self, ticket: ConsentTicket) {
        let posted = self.gui.run(move |cx| {
            cx.spawn(async move |cx| {
                let r = ticket.request();
                let q = mmcl_ui::Question::new(
                    r.title(),
                    r.header(),
                    r.body(),
                    r.allow_once_label(),
                    r.second_button_label(),
                );
                let second = r.second_button_answer();
                let answer = mmcl_ui::dialogs::ask(cx, &q, None).await;
                ticket.answer(map_answer(answer, second));
            })
            .detach();
        });
        // When the UI is gone the ticket was dropped with the job: the waiting side denies.
        let _ = posted;
    }
}

/// Settings -> Security over the live server.
pub struct PanelAdapter {
    pub mcp: Arc<McpBootstrap>,
    pub engine: Engine,
}

fn to_policy(p: Option<Policy>) -> McpPolicy {
    match p {
        None => McpPolicy::Default,
        Some(Policy::AlwaysAllow) => McpPolicy::AlwaysAllow,
        Some(Policy::Ask) => McpPolicy::Ask,
        Some(Policy::Disabled) => McpPolicy::Disabled,
    }
}

fn from_policy(p: McpPolicy) -> Option<Policy> {
    match p {
        McpPolicy::Default => None,
        McpPolicy::AlwaysAllow => Some(Policy::AlwaysAllow),
        McpPolicy::Ask => Some(Policy::Ask),
        McpPolicy::Disabled => Some(Policy::Disabled),
    }
}

fn to_client(c: McpClient) -> Client {
    match c {
        McpClient::ClaudeCode => Client::ClaudeCode,
        McpClient::Cursor => Client::Cursor,
        McpClient::Codex => Client::Codex,
        McpClient::Http => Client::Http,
        McpClient::Stdio => Client::Stdio,
    }
}

fn decision_name(d: Decision) -> &'static str {
    match d {
        Decision::Allowed => "allowed",
        Decision::Denied => "denied",
        Decision::Refused => "refused",
        Decision::Failed => "failed",
    }
}

impl PanelAdapter {
    fn port(&self) -> u16 {
        match self.mcp.port() {
            0 => u16::try_from(self.engine.settings().mcp_port)
                .ok()
                .filter(|p| *p != 0)
                .unwrap_or(mmcl_core::AppIdentity::current().mcp_port),
            p => p,
        }
    }
}

impl McpPanel for PanelAdapter {
    fn refresh(&self) {
        self.mcp.refresh();
    }

    fn is_running(&self) -> bool {
        self.mcp.is_running()
    }

    fn endpoint_url(&self) -> String {
        client_setup::endpoint_url(self.port())
    }

    fn sessions(&self) -> Vec<McpSessionRow> {
        let now = now_ms();
        self.mcp
            .sessions()
            .into_iter()
            .map(|s| McpSessionRow {
                name: Some(s.client_name).filter(|n| !n.trim().is_empty()),
                age_ms: now.saturating_sub(s.connected_at_ms),
                calls: s.call_count,
            })
            .collect()
    }

    fn grant_count(&self) -> usize {
        self.mcp.grants().live_grants(now_ms()).len()
    }

    fn revoke_grants(&self) {
        self.mcp.grants().revoke_all();
    }

    fn activity(&self, max: usize) -> Vec<McpActivityRow> {
        let now = now_ms();
        self.mcp
            .activity()
            .recent()
            .into_iter()
            .take(max)
            .map(|e| McpActivityRow {
                age_ms: now.saturating_sub(e.timestamp_ms),
                tool: e.tool_name,
                decision: decision_name(e.decision).to_string(),
            })
            .collect()
    }

    fn clear_activity(&self) {
        self.mcp.activity().clear();
    }

    fn tools(&self) -> Vec<McpToolRow> {
        let policies = self.mcp.policies();
        self.mcp
            .describe_tools()
            .into_iter()
            .map(|t| McpToolRow {
                policy: to_policy(policies.policy_for(&t.name)),
                risk_key: t.risk_class.label_key().to_string(),
                title: t.title,
                name: t.name,
            })
            .collect()
    }

    fn set_policy(&self, tool: &str, policy: McpPolicy) {
        self.mcp.policies().set_policy(tool, from_policy(policy));
    }

    fn ensure_token(&self) -> String {
        self.mcp.ensure_token()
    }

    fn masked_token(&self) -> String {
        mmcl_mcp::token::mask(&self.mcp.ensure_token())
    }

    fn regenerate_token(&self) -> String {
        self.mcp.regenerate_token()
    }

    fn setup_snippet(&self, client: McpClient, token: &str) -> Option<String> {
        let launcher = mmcl_core::install::launcher_executable()
            .map(|p| p.display().to_string())
            .unwrap_or_default();
        let endpoint = mmcl_mcp::endpoint::default_path().display().to_string();
        Some(client_setup::snippet(
            to_client(client),
            &launcher,
            true,
            self.port(),
            &endpoint,
            Some(token),
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn buttons_map_to_answers() {
        assert_eq!(
            map_answer(Answer::Primary, McpAnswer::AllowForSession),
            McpAnswer::AllowOnce
        );
        assert_eq!(
            map_answer(Answer::Secondary, McpAnswer::AllowForSession),
            McpAnswer::AllowForSession
        );
        assert_eq!(
            map_answer(Answer::Secondary, McpAnswer::Deny),
            McpAnswer::Deny
        );
        assert_eq!(
            map_answer(Answer::Closed, McpAnswer::AllowForSession),
            McpAnswer::Deny
        );
    }

    #[test]
    fn policies_round_trip() {
        for p in McpPolicy::ALL {
            assert_eq!(to_policy(from_policy(p)), p);
        }
    }

    #[test]
    fn decision_names_match_the_settings_labels() {
        for d in [
            Decision::Allowed,
            Decision::Denied,
            Decision::Refused,
            Decision::Failed,
        ] {
            assert!(d.label_key().ends_with(decision_name(d)), "{d:?}");
        }
    }
}
