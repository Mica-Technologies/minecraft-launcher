// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The production authorizer (Java `LauncherMcpAuthorizer`). Every failure denies.

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Arc;

use super::{
    Answer, ApprovalRequest, ConsentPrompt, ConsentRequest, DEFAULT_GRANT_TTL_MS, Decision,
    GrantStore, Policy, resolve,
};
use crate::BoxError;
use crate::logging;
use crate::tools::{Args, CallContext, Tool};

/// Decides whether one call may run.
pub trait Authorizer: Send + Sync {
    fn authorize(&self, tool: &dyn Tool, ctx: &CallContext, args: &Args) -> bool;
}

/// The settings the authorizer reads on every call (no restart needed).
pub trait Settings: Send + Sync {
    fn server_enabled(&self) -> Result<bool, BoxError>;
    fn auto_approve_read_only(&self) -> Result<bool, BoxError>;
    fn policy_for(&self, tool: &str) -> Result<Option<Policy>, BoxError>;
}

/// Settings + grants + consent prompt + clock.
pub struct LauncherAuthorizer {
    settings: Arc<dyn Settings>,
    grants: Arc<GrantStore>,
    prompt: Arc<dyn ConsentPrompt>,
    clock: Arc<dyn Fn() -> u64 + Send + Sync>,
}

impl LauncherAuthorizer {
    pub fn new(
        settings: Arc<dyn Settings>,
        grants: Arc<GrantStore>,
        prompt: Arc<dyn ConsentPrompt>,
        clock: Arc<dyn Fn() -> u64 + Send + Sync>,
    ) -> LauncherAuthorizer {
        LauncherAuthorizer {
            settings,
            grants,
            prompt,
            clock,
        }
    }

    fn gather(
        &self,
        tool: &dyn Tool,
        ctx: &CallContext,
    ) -> Result<(ApprovalRequest, Option<Policy>), BoxError> {
        let now = (self.clock)();
        let explicit = self.settings.policy_for(tool.name())?;
        let req = ApprovalRequest {
            risk_class: tool.risk_class(),
            explicit_policy: explicit,
            server_enabled: self.settings.server_enabled()?,
            auto_approve_read_only: self.settings.auto_approve_read_only()?,
            gui_available: catch_unwind(AssertUnwindSafe(|| self.prompt.is_available()))
                .unwrap_or(false),
            grant_expires_at_ms: self.grants.expiry_for(&ctx.client_name, tool.name()),
            now_ms: now,
        };
        Ok((req, explicit))
    }
}

fn consent_detail_of(tool: &dyn Tool, args: &Args) -> Option<String> {
    match catch_unwind(AssertUnwindSafe(|| tool.consent_detail(args))) {
        Ok(d) => d,
        Err(_) => {
            logging::warn("log.mcpConsent.detailFailed", &[tool.name()]);
            None
        }
    }
}

impl Authorizer for LauncherAuthorizer {
    fn authorize(&self, tool: &dyn Tool, ctx: &CallContext, args: &Args) -> bool {
        let gathered = catch_unwind(AssertUnwindSafe(|| self.gather(tool, ctx)));
        let (req, explicit) = match gathered {
            Ok(Ok(v)) => v,
            Ok(Err(e)) => {
                logging::error_with("log.mcpAuthorizer.resolveFailed", &[tool.name()], &e);
                return false;
            }
            Err(_) => {
                logging::error("log.mcpAuthorizer.resolveFailed", &[tool.name()]);
                return false;
            }
        };
        match resolve(&req) {
            Decision::Allow => true,
            Decision::Deny => {
                logging::std("log.mcpAuthorizer.denied", &[&ctx.client_name, tool.name()]);
                false
            }
            Decision::Prompt => {
                let offer_session_grant = explicit != Some(Policy::Ask);
                let request = ConsentRequest::new(
                    &ctx.client_name,
                    tool.name(),
                    &tool.display_title(),
                    &tool.display_description(),
                    args,
                    consent_detail_of(tool, args),
                    offer_session_grant,
                );
                let answer = match catch_unwind(AssertUnwindSafe(|| self.prompt.ask(request))) {
                    Ok(a) => a,
                    Err(_) => {
                        logging::error("log.mcpConsent.promptFailed", &[tool.name()]);
                        return false;
                    }
                };
                if answer == Answer::AllowForSession && offer_session_grant {
                    let now = (self.clock)();
                    self.grants.grant(
                        &ctx.client_name,
                        tool.name(),
                        now.saturating_add(DEFAULT_GRANT_TTL_MS),
                    );
                }
                let allowed = answer != Answer::Deny;
                let key = if allowed {
                    "log.mcpAuthorizer.allowedAnswer"
                } else {
                    "log.mcpAuthorizer.deniedAnswer"
                };
                logging::std(key, &[&ctx.client_name, tool.name(), answer.name()]);
                allowed
            }
        }
    }
}

#[cfg(test)]
mod tests {
    //! Port of `LauncherMcpAuthorizerTest`.
    use super::*;
    use crate::approval::RiskClass;
    use crate::seam_error;
    use crate::tools::ToolResult;
    use parking_lot::Mutex;
    use serde_json::{Value, json};
    use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};

    struct StubTool {
        name: &'static str,
        risk: RiskClass,
        detail_panics: bool,
    }

    impl Tool for StubTool {
        fn name(&self) -> &str {
            self.name
        }
        fn title(&self) -> &str {
            "Stub"
        }
        fn description(&self) -> &str {
            "A stub."
        }
        fn input_schema(&self) -> Value {
            json!({"type":"object","properties":{}})
        }
        fn risk_class(&self) -> RiskClass {
            self.risk
        }
        fn invoke(&self, _: &CallContext, _: &Args) -> Result<ToolResult, BoxError> {
            Ok(ToolResult::text("ok"))
        }
        fn consent_detail(&self, _: &Args) -> Option<String> {
            if self.detail_panics {
                panic!("detail");
            }
            Some("detail".into())
        }
    }

    #[derive(Default)]
    struct StubSettings {
        disabled: bool,
        no_auto: bool,
        policy: Option<Policy>,
        fail: bool,
    }

    impl Settings for StubSettings {
        fn server_enabled(&self) -> Result<bool, BoxError> {
            if self.fail {
                return Err(seam_error("x"));
            }
            Ok(!self.disabled)
        }
        fn auto_approve_read_only(&self) -> Result<bool, BoxError> {
            Ok(!self.no_auto)
        }
        fn policy_for(&self, _: &str) -> Result<Option<Policy>, BoxError> {
            Ok(self.policy)
        }
    }

    struct StubPrompt {
        available: bool,
        answer: Answer,
        panics: bool,
        asked: AtomicUsize,
        last: Mutex<Option<ConsentRequest>>,
    }

    impl ConsentPrompt for StubPrompt {
        fn is_available(&self) -> bool {
            self.available
        }
        fn ask(&self, r: ConsentRequest) -> Answer {
            self.asked.fetch_add(1, Ordering::SeqCst);
            if self.panics {
                panic!("prompt");
            }
            *self.last.lock() = Some(r);
            self.answer
        }
    }

    fn prompt(available: bool, answer: Answer) -> Arc<StubPrompt> {
        Arc::new(StubPrompt {
            available,
            answer,
            panics: false,
            asked: AtomicUsize::new(0),
            last: Mutex::new(None),
        })
    }

    struct Fixture {
        auth: LauncherAuthorizer,
        grants: Arc<GrantStore>,
        clock: Arc<AtomicU64>,
    }

    fn fixture(settings: StubSettings, p: Arc<StubPrompt>) -> Fixture {
        let grants = Arc::new(GrantStore::new());
        let clock = Arc::new(AtomicU64::new(1_000));
        let c = clock.clone();
        Fixture {
            auth: LauncherAuthorizer::new(
                Arc::new(settings),
                grants.clone(),
                p,
                Arc::new(move || c.load(Ordering::SeqCst)),
            ),
            grants,
            clock,
        }
    }

    fn tool(risk: RiskClass) -> StubTool {
        StubTool {
            name: "stub_tool",
            risk,
            detail_panics: false,
        }
    }

    fn ctx(name: &str) -> CallContext {
        CallContext::new(name, "sid")
    }

    #[test]
    fn read_only_runs_without_asking() {
        let p = prompt(true, Answer::Deny);
        let f = fixture(StubSettings::default(), p.clone());
        assert!(
            f.auth
                .authorize(&tool(RiskClass::ReadOnly), &ctx("c"), &Args::new())
        );
        assert_eq!(p.asked.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn mutating_asks_and_respects_deny() {
        let p = prompt(true, Answer::Deny);
        let f = fixture(StubSettings::default(), p.clone());
        assert!(
            !f.auth
                .authorize(&tool(RiskClass::Mutating), &ctx("c"), &Args::new())
        );
        assert_eq!(p.asked.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn allow_once_is_not_carried_over() {
        let p = prompt(true, Answer::AllowOnce);
        let f = fixture(StubSettings::default(), p.clone());
        let t = tool(RiskClass::Execute);
        assert!(f.auth.authorize(&t, &ctx("c"), &Args::new()));
        assert!(f.auth.authorize(&t, &ctx("c"), &Args::new()));
        assert_eq!(p.asked.load(Ordering::SeqCst), 2);
        assert!(f.grants.is_empty());
    }

    #[test]
    fn allow_for_session_grants_for_eight_hours() {
        let p = prompt(true, Answer::AllowForSession);
        let f = fixture(StubSettings::default(), p.clone());
        let t = tool(RiskClass::Destructive);
        assert!(f.auth.authorize(&t, &ctx("Claude"), &Args::new()));
        assert_eq!(
            f.grants.expiry_for("claude", "stub_tool"),
            1_000 + DEFAULT_GRANT_TTL_MS
        );
        assert!(f.auth.authorize(&t, &ctx("Claude"), &Args::new()));
        assert_eq!(
            p.asked.load(Ordering::SeqCst),
            1,
            "the grant answered the second call"
        );
    }

    #[test]
    fn a_grant_expires() {
        let p = prompt(true, Answer::AllowForSession);
        let f = fixture(StubSettings::default(), p.clone());
        let t = tool(RiskClass::Mutating);
        f.auth.authorize(&t, &ctx("c"), &Args::new());
        f.clock
            .store(1_000 + DEFAULT_GRANT_TTL_MS, Ordering::SeqCst);
        f.auth.authorize(&t, &ctx("c"), &Args::new());
        assert_eq!(p.asked.load(Ordering::SeqCst), 2);
    }

    #[test]
    fn grants_are_per_client_and_per_tool() {
        let p = prompt(true, Answer::AllowForSession);
        let f = fixture(StubSettings::default(), p.clone());
        let t = tool(RiskClass::Mutating);
        f.auth.authorize(&t, &ctx("a"), &Args::new());
        f.auth.authorize(&t, &ctx("b"), &Args::new());
        let other = StubTool {
            name: "other_tool",
            ..tool(RiskClass::Mutating)
        };
        f.auth.authorize(&other, &ctx("a"), &Args::new());
        assert_eq!(p.asked.load(Ordering::SeqCst), 3);
    }

    #[test]
    fn ask_is_never_offered_a_session_grant() {
        let p = prompt(true, Answer::AllowForSession);
        let f = fixture(
            StubSettings {
                policy: Some(Policy::Ask),
                ..StubSettings::default()
            },
            p.clone(),
        );
        assert!(
            f.auth
                .authorize(&tool(RiskClass::ReadOnly), &ctx("c"), &Args::new())
        );
        assert!(!p.last.lock().as_ref().unwrap().offer_session_grant);
        assert!(
            f.grants.is_empty(),
            "an unoffered session answer is allow-once"
        );
    }

    #[test]
    fn disabled_overrides_a_grant() {
        let p = prompt(true, Answer::AllowOnce);
        let f = fixture(
            StubSettings {
                policy: Some(Policy::Disabled),
                ..StubSettings::default()
            },
            p.clone(),
        );
        f.grants.grant("c", "stub_tool", u64::MAX);
        assert!(
            !f.auth
                .authorize(&tool(RiskClass::ReadOnly), &ctx("c"), &Args::new())
        );
        assert_eq!(p.asked.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn headless_denies_anything_that_would_prompt() {
        let p = prompt(false, Answer::AllowOnce);
        let f = fixture(StubSettings::default(), p.clone());
        assert!(
            !f.auth
                .authorize(&tool(RiskClass::Mutating), &ctx("c"), &Args::new())
        );
        assert!(
            f.auth
                .authorize(&tool(RiskClass::ReadOnly), &ctx("c"), &Args::new())
        );
        assert_eq!(p.asked.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn auto_approve_off_prompts_read_only() {
        let p = prompt(true, Answer::AllowOnce);
        let f = fixture(
            StubSettings {
                no_auto: true,
                ..StubSettings::default()
            },
            p.clone(),
        );
        assert!(
            f.auth
                .authorize(&tool(RiskClass::ReadOnly), &ctx("c"), &Args::new())
        );
        assert_eq!(p.asked.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn a_settings_error_denies() {
        let p = prompt(true, Answer::AllowOnce);
        let f = fixture(
            StubSettings {
                fail: true,
                ..StubSettings::default()
            },
            p,
        );
        assert!(
            !f.auth
                .authorize(&tool(RiskClass::ReadOnly), &ctx("c"), &Args::new())
        );
    }

    #[test]
    fn server_disabled_denies() {
        let p = prompt(true, Answer::AllowOnce);
        let f = fixture(
            StubSettings {
                disabled: true,
                policy: Some(Policy::AlwaysAllow),
                ..StubSettings::default()
            },
            p,
        );
        assert!(
            !f.auth
                .authorize(&tool(RiskClass::ReadOnly), &ctx("c"), &Args::new())
        );
    }

    #[test]
    fn a_panicking_prompt_denies() {
        let p = Arc::new(StubPrompt {
            available: true,
            answer: Answer::AllowOnce,
            panics: true,
            asked: AtomicUsize::new(0),
            last: Mutex::new(None),
        });
        let f = fixture(StubSettings::default(), p);
        assert!(
            !f.auth
                .authorize(&tool(RiskClass::Mutating), &ctx("c"), &Args::new())
        );
    }

    #[test]
    fn a_failing_consent_detail_still_asks() {
        let p = prompt(true, Answer::AllowOnce);
        let f = fixture(StubSettings::default(), p.clone());
        let t = StubTool {
            detail_panics: true,
            ..tool(RiskClass::Mutating)
        };
        assert!(f.auth.authorize(&t, &ctx("c"), &Args::new()));
        assert!(p.last.lock().as_ref().unwrap().consent_detail.is_none());
    }

    #[test]
    fn the_request_carries_the_detail_and_arguments() {
        let p = prompt(true, Answer::AllowOnce);
        let f = fixture(StubSettings::default(), p.clone());
        let args = json!({"friendlyName":"Alto"}).as_object().unwrap().clone();
        f.auth
            .authorize(&tool(RiskClass::Mutating), &ctx("c"), &args);
        let r = p.last.lock().clone().unwrap();
        assert_eq!(r.consent_detail.as_deref(), Some("detail"));
        assert_eq!(r.arguments_summary, "friendlyName=Alto");
        assert!(r.offer_session_grant);
    }
}
