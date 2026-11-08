// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Who may run what (Java `approval/`).
//!
//! - [`RiskClass`]: what each tool declares.
//! - [`Policy`]: the user's per-tool choice ([`PolicyStore`], durable, config-backed).
//! - [`resolve`]: the pure decision table ([`ApprovalRequest`] → [`Decision`]).
//! - [`GrantStore`]: "Allow for this session" grants, keyed by client name and tool, per run.
//! - [`LauncherAuthorizer`]: the production [`Authorizer`], which asks the user through a
//!   [`ConsentPrompt`] the GUI implements.

use std::time::Duration;

mod authorizer;
mod consent;
mod grants;
mod policies;

pub use authorizer::{Authorizer, LauncherAuthorizer, Settings};
pub use consent::{
    CONSENT_TIMEOUT, ConsentPresenter, ConsentPrompt, ConsentRequest, ConsentTicket,
    DeadlineConsentPrompt, MAX_BODY_CHARS, MAX_SUMMARIZED_ARGUMENTS, MAX_SUMMARIZED_VALUE_LEN,
    sanitize_single_line, summarize_arguments,
};
pub use grants::{Grant, GrantStore};
pub use policies::{PolicyBacking, PolicyStore};

/// How long an "Allow for this session" grant lasts.
pub const DEFAULT_GRANT_TTL: Duration = Duration::from_secs(8 * 60 * 60);
/// [`DEFAULT_GRANT_TTL`] in milliseconds.
pub const DEFAULT_GRANT_TTL_MS: u64 = 8 * 60 * 60 * 1000;

/// What a tool can do (Java `McpRiskClass`).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum RiskClass {
    /// Lists, reads, diagnostics.
    ReadOnly,
    /// A recoverable change: install, create, fork, add or remove a mod.
    Mutating,
    /// Deletes or overwrites user data: uninstall.
    Destructive,
    /// Runs third-party code or processes: launch, stop.
    Execute,
}

impl RiskClass {
    /// Every class.
    pub const ALL: [RiskClass; 4] = [
        RiskClass::ReadOnly,
        RiskClass::Mutating,
        RiskClass::Destructive,
        RiskClass::Execute,
    ];

    /// Anything but read-only.
    pub fn mutates_state(self) -> bool {
        self != RiskClass::ReadOnly
    }

    /// The policy that applies when the user set none. [`resolve`] encodes the same table.
    pub fn default_policy(self) -> Policy {
        match self {
            RiskClass::ReadOnly => Policy::AlwaysAllow,
            _ => Policy::Ask,
        }
    }

    /// The localized label key (`mcp.risk.*`).
    pub fn label_key(self) -> &'static str {
        match self {
            RiskClass::ReadOnly => "mcp.risk.readOnly",
            RiskClass::Mutating => "mcp.risk.mutating",
            RiskClass::Destructive => "mcp.risk.destructive",
            RiskClass::Execute => "mcp.risk.execute",
        }
    }

    /// The Java enum name.
    pub fn name(self) -> &'static str {
        match self {
            RiskClass::ReadOnly => "READ_ONLY",
            RiskClass::Mutating => "MUTATING",
            RiskClass::Destructive => "DESTRUCTIVE",
            RiskClass::Execute => "EXECUTE",
        }
    }
}

/// The user's per-tool policy (Java `McpApprovalPolicy`). "Default" in the UI is no policy.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Policy {
    AlwaysAllow,
    Ask,
    Disabled,
}

impl Policy {
    /// The stored enum name.
    pub fn name(self) -> &'static str {
        match self {
            Policy::AlwaysAllow => "ALWAYS_ALLOW",
            Policy::Ask => "ASK",
            Policy::Disabled => "DISABLED",
        }
    }

    /// Trimmed, upper-cased enum name; anything else is `None` (never a permissive default).
    pub fn parse(s: &str) -> Option<Policy> {
        match s.trim().to_uppercase().as_str() {
            "ALWAYS_ALLOW" => Some(Policy::AlwaysAllow),
            "ASK" => Some(Policy::Ask),
            "DISABLED" => Some(Policy::Disabled),
            _ => None,
        }
    }
}

/// The engine's verdict (Java `McpApprovalDecision`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Decision {
    Allow,
    Prompt,
    Deny,
}

/// The user's answer to a consent prompt.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Answer {
    Deny,
    AllowOnce,
    AllowForSession,
}

impl Answer {
    /// The Java enum name (log lines).
    pub fn name(self) -> &'static str {
        match self {
            Answer::Deny => "DENY",
            Answer::AllowOnce => "ALLOW_ONCE",
            Answer::AllowForSession => "ALLOW_FOR_SESSION",
        }
    }
}

/// Everything [`resolve`] looks at.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ApprovalRequest {
    pub risk_class: RiskClass,
    pub explicit_policy: Option<Policy>,
    pub server_enabled: bool,
    pub auto_approve_read_only: bool,
    pub gui_available: bool,
    /// `0` = no grant.
    pub grant_expires_at_ms: u64,
    pub now_ms: u64,
}

/// A grant counts while it expires strictly after `now`.
pub fn has_unexpired_grant(grant_expires_at_ms: u64, now_ms: u64) -> bool {
    grant_expires_at_ms > 0 && grant_expires_at_ms > now_ms
}

/// The decision table (Java `McpApprovalEngine.resolve`).
///
/// In order: server off → deny; DISABLED → deny; ALWAYS_ALLOW → allow; ASK → prompt (or deny
/// with no window; a grant does **not** satisfy ASK); a live grant → allow; read-only with
/// auto-approve → allow; otherwise prompt, or deny with no window.
pub fn resolve(req: &ApprovalRequest) -> Decision {
    let ask = if req.gui_available {
        Decision::Prompt
    } else {
        Decision::Deny
    };
    if !req.server_enabled {
        return Decision::Deny;
    }
    match req.explicit_policy {
        Some(Policy::Disabled) => return Decision::Deny,
        Some(Policy::AlwaysAllow) => return Decision::Allow,
        Some(Policy::Ask) => return ask,
        None => {}
    }
    if has_unexpired_grant(req.grant_expires_at_ms, req.now_ms) {
        return Decision::Allow;
    }
    if req.risk_class == RiskClass::ReadOnly && req.auto_approve_read_only {
        return Decision::Allow;
    }
    ask
}

#[cfg(test)]
mod tests {
    //! Port of `McpApprovalEngineTest`.
    use super::*;

    fn req(risk: RiskClass) -> ApprovalRequest {
        ApprovalRequest {
            risk_class: risk,
            explicit_policy: None,
            server_enabled: true,
            auto_approve_read_only: true,
            gui_available: true,
            grant_expires_at_ms: 0,
            now_ms: 1_000,
        }
    }

    #[test]
    fn server_off_denies_everything() {
        for r in RiskClass::ALL {
            let mut q = req(r);
            q.server_enabled = false;
            q.explicit_policy = Some(Policy::AlwaysAllow);
            assert_eq!(resolve(&q), Decision::Deny);
        }
    }

    #[test]
    fn disabled_denies_even_with_a_grant() {
        let mut q = req(RiskClass::ReadOnly);
        q.explicit_policy = Some(Policy::Disabled);
        q.grant_expires_at_ms = 10_000;
        assert_eq!(resolve(&q), Decision::Deny);
    }

    #[test]
    fn always_allow_allows_every_class_headless() {
        for r in RiskClass::ALL {
            let mut q = req(r);
            q.explicit_policy = Some(Policy::AlwaysAllow);
            q.gui_available = false;
            assert_eq!(resolve(&q), Decision::Allow);
        }
    }

    #[test]
    fn ask_prompts_with_a_window_and_a_grant_does_not_satisfy_it() {
        let mut q = req(RiskClass::ReadOnly);
        q.explicit_policy = Some(Policy::Ask);
        q.grant_expires_at_ms = 10_000;
        assert_eq!(resolve(&q), Decision::Prompt);
        q.gui_available = false;
        assert_eq!(resolve(&q), Decision::Deny);
    }

    #[test]
    fn a_live_grant_allows_even_headless() {
        let mut q = req(RiskClass::Destructive);
        q.grant_expires_at_ms = 2_000;
        q.gui_available = false;
        assert_eq!(resolve(&q), Decision::Allow);
    }

    #[test]
    fn a_grant_expiring_exactly_now_is_expired() {
        let mut q = req(RiskClass::Mutating);
        q.grant_expires_at_ms = 1_000;
        assert_eq!(resolve(&q), Decision::Prompt);
        assert!(!has_unexpired_grant(1_000, 1_000));
        assert!(has_unexpired_grant(1_001, 1_000));
        assert!(!has_unexpired_grant(0, 0));
    }

    #[test]
    fn read_only_auto_approves_and_runs_headless() {
        let mut q = req(RiskClass::ReadOnly);
        assert_eq!(resolve(&q), Decision::Allow);
        q.gui_available = false;
        assert_eq!(resolve(&q), Decision::Allow);
    }

    #[test]
    fn auto_approve_off_prompts_for_read_only() {
        let mut q = req(RiskClass::ReadOnly);
        q.auto_approve_read_only = false;
        assert_eq!(resolve(&q), Decision::Prompt);
        q.gui_available = false;
        assert_eq!(resolve(&q), Decision::Deny);
    }

    #[test]
    fn auto_approve_off_does_not_undo_explicit_always_allow() {
        let mut q = req(RiskClass::ReadOnly);
        q.auto_approve_read_only = false;
        q.explicit_policy = Some(Policy::AlwaysAllow);
        assert_eq!(resolve(&q), Decision::Allow);
    }

    #[test]
    fn mutating_classes_never_allow_without_policy_or_grant() {
        for r in [
            RiskClass::Mutating,
            RiskClass::Destructive,
            RiskClass::Execute,
        ] {
            let q = req(r);
            assert_eq!(resolve(&q), Decision::Prompt);
            let mut h = req(r);
            h.gui_available = false;
            assert_eq!(resolve(&h), Decision::Deny);
        }
    }

    #[test]
    fn the_declared_defaults_match_what_the_engine_does() {
        for r in RiskClass::ALL {
            let q = req(r);
            let d = resolve(&q);
            match r.default_policy() {
                Policy::AlwaysAllow => assert_eq!(d, Decision::Allow),
                Policy::Ask => assert_eq!(d, Decision::Prompt),
                Policy::Disabled => assert_eq!(d, Decision::Deny),
            }
            assert_eq!(r.mutates_state(), r != RiskClass::ReadOnly);
        }
    }

    #[test]
    fn risk_labels_exist() {
        for r in RiskClass::ALL {
            assert!(mmcl_i18n::english(r.label_key()).is_some());
        }
    }

    #[test]
    fn policy_parse_is_strict() {
        assert_eq!(Policy::parse(" always_allow "), Some(Policy::AlwaysAllow));
        assert_eq!(Policy::parse("Ask"), Some(Policy::Ask));
        assert_eq!(Policy::parse("DISABLED"), Some(Policy::Disabled));
        assert_eq!(Policy::parse("ALLOW"), None);
        assert_eq!(Policy::parse(""), None);
    }

    #[test]
    fn the_grant_ttl_is_eight_hours() {
        assert_eq!(DEFAULT_GRANT_TTL.as_millis() as u64, DEFAULT_GRANT_TTL_MS);
        assert_eq!(DEFAULT_GRANT_TTL_MS, 28_800_000);
    }
}
