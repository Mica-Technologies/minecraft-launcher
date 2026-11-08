// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The pure parts of sign-in and the account lists (`AccountListModel`, the sign-in panel's
//! state machine, the device code countdown). No GPUI here, so all of it is unit tested.

use std::time::{Duration, SystemTime};

use mmcl_engine::{AccountStatus, AccountView, DeviceCodePrompt, SignInKind};

// ---------------------------------------------------------------------------------------------
// AccountListModel

/// One account as a list row (`AccountListModel.Row`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AccountRow {
    /// The profile id, for avatars and actions.
    pub uuid: String,
    /// The display name, falling back to the uuid when unknown.
    pub name: String,
    /// The localization key of the status line.
    pub status_key: &'static str,
    /// Whether launches use this account by default.
    pub is_default: bool,
    /// Whether "Make default" applies.
    pub can_make_default: bool,
    /// Whether the row offers "Sign in again" instead of switching.
    pub needs_sign_in: bool,
    /// Whether the account is forgotten when the launcher quits.
    pub session_only: bool,
}

/// The rows: default accounts first, then the rest in the order given (most recently used
/// first, as the engine returns them).
pub fn account_rows(accounts: &[AccountView]) -> Vec<AccountRow> {
    let mut defaults = Vec::new();
    let mut others = Vec::new();
    for a in accounts {
        let needs_sign_in = a.status == AccountStatus::NeedsSignIn;
        let status_key = if needs_sign_in {
            "account.status.needsSignIn"
        } else if a.status == AccountStatus::Refreshing {
            "account.status.refreshing"
        } else if a.is_default {
            "account.status.default"
        } else {
            "account.status.signedIn"
        };
        let name = if a.name.trim().is_empty() {
            a.uuid.clone()
        } else {
            a.name.clone()
        };
        let row = AccountRow {
            uuid: a.uuid.clone(),
            name,
            status_key,
            is_default: a.is_default,
            can_make_default: !a.is_default && !needs_sign_in,
            needs_sign_in,
            session_only: !a.remembered,
        };
        if a.is_default {
            defaults.push(row);
        } else {
            others.push(row);
        }
    }
    defaults.extend(others);
    defaults
}

impl AccountRow {
    /// The status line in the active locale; a session-only account adds
    /// `account.status.withSessionOnly` (the Settings saved-accounts list).
    pub fn status_text(&self) -> String {
        let status = mmcl_i18n::t!(self.status_key).into_owned();
        if self.session_only {
            mmcl_i18n::tf!("account.status.withSessionOnly", status)
        } else {
            status
        }
    }

    /// The switcher item's text: the name, or `account.switcher.needsSignIn(name)`.
    pub fn switcher_label(&self) -> String {
        if self.needs_sign_in {
            mmcl_i18n::tf!("account.switcher.needsSignIn", self.name.as_str())
        } else {
            self.name.clone()
        }
    }

    /// What picking this row in the account switcher does.
    pub fn switcher_action(&self) -> SwitcherAction {
        if self.is_default {
            SwitcherAction::None
        } else if self.needs_sign_in {
            SwitcherAction::SignInAgain
        } else {
            SwitcherAction::MakeDefault(self.uuid.clone())
        }
    }
}

/// What an account switcher item does.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SwitcherAction {
    /// Already the default: nothing.
    None,
    /// Its credentials were rejected: open the Add-account window.
    SignInAgain,
    /// Make it the default account.
    MakeDefault(String),
}

// ---------------------------------------------------------------------------------------------
// Device code

/// How long `prompt`'s code stays valid at `now` (zero once expired).
pub fn remaining(prompt: &DeviceCodePrompt, now: SystemTime) -> Duration {
    prompt
        .expires_at()
        .duration_since(now)
        .unwrap_or(Duration::ZERO)
}

/// A countdown as `m:ss` (`h:mm:ss` from an hour), rounded up to the next whole second so
/// it reads `0:00` only once the code has really expired.
pub fn format_countdown(left: Duration) -> String {
    let mut secs = left.as_secs();
    if left.subsec_nanos() > 0 {
        secs += 1;
    }
    let (h, m, s) = (secs / 3600, (secs / 60) % 60, secs % 60);
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m}:{s:02}")
    }
}

/// The address the QR code and the "Open the page" button use: the verification page with the
/// code filled in (`?otc=`), so a phone camera lands on a ready-to-confirm page. The shown link
/// stays the plain address the user types.
pub fn prefilled_link(prompt: &DeviceCodePrompt) -> String {
    let uri = prompt.verification_uri.trim();
    if uri.is_empty() || prompt.user_code.trim().is_empty() || uri.contains('?') {
        return uri.to_string();
    }
    let code: String = prompt
        .user_code
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .collect();
    format!("{uri}?otc={code}")
}

/// The link as shown: without the scheme and a trailing slash (`microsoft.com/link`).
pub fn display_link(uri: &str) -> String {
    let s = uri.trim();
    let s = s
        .strip_prefix("https://")
        .or_else(|| s.strip_prefix("http://"))
        .unwrap_or(s);
    let s = s.strip_prefix("www.").unwrap_or(s);
    s.trim_end_matches('/').to_string()
}

// ---------------------------------------------------------------------------------------------
// Sign-in panel state machine

/// Where the sign-in panel is.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Phase {
    /// Pick a way to sign in (also after a failure, with the status label showing).
    Choose,
    /// A flow is waiting for the user: the web view window is open, the WAM broker is asking,
    /// or the device code is being requested / shown (`prompt` once it arrived).
    Waiting {
        kind: SignInKind,
        prompt: Option<DeviceCodePrompt>,
    },
    /// Microsoft answered; the Xbox / Minecraft chain is running (`detail` = the latest step).
    Redeeming { detail: Option<String> },
    /// Cancel was pressed; waiting for the flow to end before another may start.
    Cancelling,
}

/// How a flow ended, as the panel sees it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FlowEnd {
    /// Signed in.
    SignedIn(AccountView),
    /// The user cancelled (our Cancel button, the web view window closed, declined on the page).
    Cancelled,
    /// It failed with this (localized) message.
    Failed(String),
}

/// What the host should do after a flow ended.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Finished {
    /// Signed in: the login screen releases its latch, Add-account closes with a toast.
    SignedIn(AccountView),
    /// Nothing to report; the panel is back on the chooser.
    Idle,
    /// The user cancelled outside the panel (closed the Microsoft window or cancelled on its
    /// page); the panel is back on the chooser. Add-account closes (Java's
    /// `callback.userCancelled() -> stage.close()`), the Login screen stays.
    CancelledOutside,
    /// Failed; the panel shows the message and is back on the chooser.
    Failed,
}

/// The sign-in panel's state (the `SignInPanel` fields Java kept on the controller).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PanelState {
    /// The flows to offer, the default first.
    pub flows: Vec<SignInKind>,
    /// Where the panel is.
    pub phase: Phase,
    /// "Stay signed in".
    pub remember: bool,
    /// The failure line (`signInStatus`), shown on the chooser.
    pub status: Option<String>,
    /// The flow tried last (the Retry button runs it again).
    pub last_kind: Option<SignInKind>,
    /// Bumped on every start, so a result of an older run is ignored.
    pub generation: u64,
}

impl PanelState {
    /// A fresh panel offering `flows`.
    pub fn new(flows: Vec<SignInKind>) -> Self {
        PanelState {
            flows,
            phase: Phase::Choose,
            remember: true,
            status: None,
            last_kind: None,
            generation: 0,
        }
    }

    /// The default flow (the first offered), if any.
    pub fn default_flow(&self) -> Option<SignInKind> {
        self.flows.first().copied()
    }

    /// Whether a flow may start now.
    pub fn can_start(&self) -> bool {
        self.phase == Phase::Choose
    }

    /// Whether the "Stay signed in" switch can change (only before a flow starts: the engine
    /// takes the value when the flow begins).
    pub fn remember_enabled(&self) -> bool {
        self.phase == Phase::Choose
    }

    /// Whether the panel is busy (a flow runs or is being cancelled).
    pub fn is_busy(&self) -> bool {
        self.phase != Phase::Choose
    }

    /// Starts `kind`: clears the status, returns the run's generation. `None` when a flow is
    /// already running.
    pub fn start(&mut self, kind: SignInKind) -> Option<u64> {
        if !self.can_start() {
            return None;
        }
        self.generation += 1;
        self.status = None;
        self.last_kind = Some(kind);
        self.phase = Phase::Waiting { kind, prompt: None };
        Some(self.generation)
    }

    /// A device code arrived (or was renewed). Ignored unless a device code flow waits.
    pub fn on_device_code(&mut self, prompt: DeviceCodePrompt) -> bool {
        match &mut self.phase {
            Phase::Waiting {
                kind: SignInKind::DeviceCode,
                prompt: p,
            } => {
                *p = Some(prompt);
                true
            }
            _ => false,
        }
    }

    /// A chain step was reported: the flow is past Microsoft's page. Ignored when no flow runs
    /// (account renewals in the background report steps too).
    pub fn on_status(&mut self, section: &str, detail: &str) -> bool {
        match self.phase {
            Phase::Waiting { .. } | Phase::Redeeming { .. } => {
                let text = [section.trim(), detail.trim()]
                    .into_iter()
                    .filter(|s| !s.is_empty())
                    .collect::<Vec<_>>()
                    .join(" · ");
                self.phase = Phase::Redeeming {
                    detail: (!text.is_empty()).then_some(text),
                };
                true
            }
            _ => false,
        }
    }

    /// Cancel was pressed. `true` when the engine's flow should be cancelled.
    pub fn cancel(&mut self) -> bool {
        match self.phase {
            Phase::Waiting { .. } => {
                self.phase = Phase::Cancelling;
                true
            }
            _ => false,
        }
    }

    /// The run `generation` ended with `end`. Results of older runs are ignored (`None`).
    pub fn finish(&mut self, generation: u64, end: FlowEnd) -> Option<Finished> {
        if generation != self.generation || self.phase == Phase::Choose {
            return None;
        }
        Some(match end {
            FlowEnd::SignedIn(account) => {
                // Stay on the redeeming view: the host moves on (screen swap / window close).
                self.phase = Phase::Redeeming { detail: None };
                Finished::SignedIn(account)
            }
            FlowEnd::Cancelled => {
                // Our Cancel button moved the phase to Cancelling first; otherwise the user
                // cancelled outside the panel (closed the Microsoft window, declined there).
                let ours = self.phase == Phase::Cancelling;
                self.phase = Phase::Choose;
                if ours {
                    Finished::Idle
                } else {
                    Finished::CancelledOutside
                }
            }
            FlowEnd::Failed(message) => {
                self.phase = Phase::Choose;
                self.status = Some(message);
                Finished::Failed
            }
        })
    }

    /// Clears the status label (the Retry button and starting a flow do this).
    pub fn clear_status(&mut self) {
        self.status = None;
    }
}

/// Whether `kind`'s flow is one the device code view shows.
pub fn is_device_code(kind: SignInKind) -> bool {
    kind == SignInKind::DeviceCode
}

#[cfg(test)]
mod tests {
    use super::*;

    fn acct(
        uuid: &str,
        name: &str,
        status: AccountStatus,
        def: bool,
        remembered: bool,
    ) -> AccountView {
        AccountView {
            uuid: uuid.into(),
            name: name.into(),
            status,
            is_default: def,
            remembered,
            last_used_ms: 0,
        }
    }

    // ---- AccountListModelTest

    #[test]
    fn default_first_rest_keep_order() {
        let rows = account_rows(&[
            acct("a", "Alpha", AccountStatus::Ready, false, true),
            acct("b", "Bravo", AccountStatus::Ready, true, true),
            acct("c", "Charlie", AccountStatus::Ready, false, true),
        ]);
        let names: Vec<_> = rows.iter().map(|r| r.name.as_str()).collect();
        assert_eq!(names, ["Bravo", "Alpha", "Charlie"]);
    }

    #[test]
    fn default_cannot_be_made_default() {
        let rows = account_rows(&[acct("b", "Bravo", AccountStatus::Ready, true, true)]);
        assert!(!rows[0].can_make_default);
        assert!(rows[0].is_default);
        assert_eq!(rows[0].status_key, "account.status.default");
        assert_eq!(rows[0].switcher_action(), SwitcherAction::None);
    }

    #[test]
    fn another_ready_account_can_be_made_default() {
        let rows = account_rows(&[acct("a", "Alpha", AccountStatus::Ready, false, true)]);
        assert!(rows[0].can_make_default);
        assert_eq!(rows[0].status_key, "account.status.signedIn");
        assert_eq!(
            rows[0].switcher_action(),
            SwitcherAction::MakeDefault("a".into())
        );
    }

    #[test]
    fn rejected_credentials_offer_sign_in_instead() {
        let rows = account_rows(&[acct("a", "Alpha", AccountStatus::NeedsSignIn, false, true)]);
        assert!(rows[0].needs_sign_in);
        assert!(!rows[0].can_make_default);
        assert_eq!(rows[0].status_key, "account.status.needsSignIn");
        assert_eq!(rows[0].switcher_action(), SwitcherAction::SignInAgain);
        assert_eq!(
            rows[0].switcher_label(),
            mmcl_i18n::tf!("account.switcher.needsSignIn", "Alpha")
        );
    }

    #[test]
    fn needs_sign_in_wins_over_default_status() {
        let rows = account_rows(&[acct("a", "Alpha", AccountStatus::NeedsSignIn, true, true)]);
        assert_eq!(rows[0].status_key, "account.status.needsSignIn");
        assert!(!rows[0].can_make_default);
    }

    #[test]
    fn refreshing_status() {
        let rows = account_rows(&[acct("a", "Alpha", AccountStatus::Refreshing, true, true)]);
        assert_eq!(rows[0].status_key, "account.status.refreshing");
    }

    #[test]
    fn session_only_marked() {
        let rows = account_rows(&[
            acct("a", "Alpha", AccountStatus::Ready, false, false),
            acct("b", "Bravo", AccountStatus::Ready, false, true),
        ]);
        assert!(rows[0].session_only);
        assert!(!rows[1].session_only);
        let status = mmcl_i18n::t!("account.status.signedIn").into_owned();
        assert_eq!(
            rows[0].status_text(),
            mmcl_i18n::tf!("account.status.withSessionOnly", status.as_str())
        );
        assert_eq!(rows[1].status_text(), status);
    }

    #[test]
    fn unknown_name_falls_back_to_uuid() {
        let rows = account_rows(&[acct("uuid-1", "  ", AccountStatus::Ready, false, true)]);
        assert_eq!(rows[0].name, "uuid-1");
        assert_eq!(rows[0].switcher_label(), "uuid-1");
    }

    #[test]
    fn no_accounts_no_rows() {
        assert!(account_rows(&[]).is_empty());
    }

    #[test]
    fn every_status_key_exists() {
        for key in [
            "account.status.needsSignIn",
            "account.status.refreshing",
            "account.status.default",
            "account.status.signedIn",
            "account.status.withSessionOnly",
            "account.switcher.needsSignIn",
            "account.switcher.header",
            "account.switcher.addAccount",
            "account.switcher.manage",
        ] {
            assert!(mmcl_i18n::english(key).is_some(), "{key}");
        }
    }

    // ---- device code

    fn prompt(code: &str, uri: &str, secs: u64) -> DeviceCodePrompt {
        DeviceCodePrompt {
            user_code: code.into(),
            verification_uri: uri.into(),
            expires_in: Duration::from_secs(secs),
            issued_at: SystemTime::UNIX_EPOCH + Duration::from_secs(1_000),
        }
    }

    #[test]
    fn countdown_counts_down_and_stops_at_zero() {
        let p = prompt("ABCD1234", "https://www.microsoft.com/link", 900);
        let at = |s: u64| SystemTime::UNIX_EPOCH + Duration::from_secs(1_000 + s);
        assert_eq!(remaining(&p, at(0)), Duration::from_secs(900));
        assert_eq!(format_countdown(remaining(&p, at(0))), "15:00");
        assert_eq!(format_countdown(remaining(&p, at(841))), "0:59");
        assert_eq!(remaining(&p, at(5_000)), Duration::ZERO);
        assert_eq!(format_countdown(Duration::ZERO), "0:00");
    }

    #[test]
    fn countdown_rounds_up_and_shows_hours() {
        assert_eq!(format_countdown(Duration::from_millis(500)), "0:01");
        assert_eq!(format_countdown(Duration::from_secs(3_725)), "1:02:05");
    }

    #[test]
    fn prefilled_link_adds_the_code() {
        let p = prompt("ABCD-1234", "https://www.microsoft.com/link", 900);
        assert_eq!(
            prefilled_link(&p),
            "https://www.microsoft.com/link?otc=ABCD1234"
        );
        let q = prompt("ABCD", "https://example.test/x?y=1", 900);
        assert_eq!(prefilled_link(&q), "https://example.test/x?y=1");
    }

    #[test]
    fn display_link_is_short() {
        assert_eq!(
            display_link("https://www.microsoft.com/link"),
            "microsoft.com/link"
        );
        assert_eq!(display_link("http://example.test/a/"), "example.test/a");
    }

    // ---- panel state machine

    fn view(uuid: &str) -> AccountView {
        acct(uuid, "Name", AccountStatus::Ready, true, true)
    }

    #[test]
    fn panel_starts_on_the_chooser_with_remember_on() {
        let s = PanelState::new(vec![SignInKind::WebView, SignInKind::DeviceCode]);
        assert_eq!(s.phase, Phase::Choose);
        assert!(s.remember);
        assert!(s.remember_enabled());
        assert_eq!(s.default_flow(), Some(SignInKind::WebView));
    }

    #[test]
    fn start_disables_remember_and_refuses_a_second_start() {
        let mut s = PanelState::new(vec![SignInKind::WebView]);
        s.status = Some("old".into());
        let g = s.start(SignInKind::WebView).unwrap();
        assert_eq!(g, 1);
        assert!(s.status.is_none(), "starting clears the status label");
        assert!(!s.remember_enabled());
        assert!(s.start(SignInKind::DeviceCode).is_none());
    }

    #[test]
    fn device_code_prompt_only_lands_on_a_device_code_flow() {
        let mut s = PanelState::new(vec![SignInKind::WebView, SignInKind::DeviceCode]);
        let p = prompt("CODE", "https://www.microsoft.com/link", 900);
        assert!(!s.on_device_code(p.clone()), "nothing runs");
        s.start(SignInKind::WebView);
        assert!(!s.on_device_code(p.clone()), "web view flow");
        s.finish(1, FlowEnd::Cancelled);
        s.start(SignInKind::DeviceCode);
        assert!(s.on_device_code(p.clone()));
        assert_eq!(
            s.phase,
            Phase::Waiting {
                kind: SignInKind::DeviceCode,
                prompt: Some(p)
            }
        );
    }

    #[test]
    fn status_steps_switch_to_redeeming_only_while_running() {
        let mut s = PanelState::new(vec![SignInKind::WebView]);
        assert!(
            !s.on_status("Xbox", "token"),
            "background renewals are ignored"
        );
        s.start(SignInKind::WebView);
        assert!(s.on_status("Xbox Live", "Getting a token"));
        assert_eq!(
            s.phase,
            Phase::Redeeming {
                detail: Some("Xbox Live · Getting a token".into())
            }
        );
        assert!(s.on_status("", ""));
        assert_eq!(s.phase, Phase::Redeeming { detail: None });
    }

    #[test]
    fn cancel_waits_for_the_flow_to_end() {
        let mut s = PanelState::new(vec![SignInKind::DeviceCode]);
        let g = s.start(SignInKind::DeviceCode).unwrap();
        assert!(s.cancel());
        assert_eq!(s.phase, Phase::Cancelling);
        assert!(!s.can_start(), "no new flow before the old one ended");
        assert!(!s.cancel());
        assert_eq!(s.finish(g, FlowEnd::Cancelled), Some(Finished::Idle));
        assert!(s.can_start());
        assert!(s.status.is_none(), "a cancellation is not an error");
    }

    #[test]
    fn redeeming_cannot_be_cancelled() {
        let mut s = PanelState::new(vec![SignInKind::WebView]);
        s.start(SignInKind::WebView);
        s.on_status("Minecraft", "profile");
        assert!(!s.cancel());
    }

    #[test]
    fn failure_shows_the_message_and_returns_to_the_chooser() {
        let mut s = PanelState::new(vec![SignInKind::WebView]);
        let g = s.start(SignInKind::WebView).unwrap();
        assert_eq!(
            s.finish(g, FlowEnd::Failed("not owned".into())),
            Some(Finished::Failed)
        );
        assert_eq!(s.phase, Phase::Choose);
        assert_eq!(s.status.as_deref(), Some("not owned"));
        assert_eq!(s.last_kind, Some(SignInKind::WebView));
        assert!(s.remember_enabled());
    }

    #[test]
    fn success_stays_on_redeeming() {
        let mut s = PanelState::new(vec![SignInKind::WebView]);
        let g = s.start(SignInKind::WebView).unwrap();
        assert_eq!(
            s.finish(g, FlowEnd::SignedIn(view("u"))),
            Some(Finished::SignedIn(view("u")))
        );
        assert!(matches!(s.phase, Phase::Redeeming { .. }));
    }

    #[test]
    fn stale_results_are_ignored() {
        let mut s = PanelState::new(vec![SignInKind::WebView]);
        let g1 = s.start(SignInKind::WebView).unwrap();
        s.finish(g1, FlowEnd::Cancelled);
        let g2 = s.start(SignInKind::WebView).unwrap();
        assert_eq!(s.finish(g1, FlowEnd::Failed("old".into())), None);
        assert!(s.status.is_none());
        assert_eq!(
            s.finish(g2, FlowEnd::Cancelled),
            Some(Finished::CancelledOutside)
        );
        assert_eq!(s.finish(g2, FlowEnd::Cancelled), None, "already finished");
    }

    #[test]
    fn cancelling_outside_the_panel_is_told_apart_from_our_cancel() {
        let mut s = PanelState::new(vec![SignInKind::WebView]);
        let g = s.start(SignInKind::WebView).unwrap();
        // The Microsoft window was closed: no Cancel press before the flow ended.
        assert_eq!(
            s.finish(g, FlowEnd::Cancelled),
            Some(Finished::CancelledOutside)
        );
        assert_eq!(s.phase, Phase::Choose);
        assert!(s.status.is_none());
        let g = s.start(SignInKind::WebView).unwrap();
        assert!(s.cancel());
        assert_eq!(s.finish(g, FlowEnd::Cancelled), Some(Finished::Idle));
    }

    #[test]
    fn rows_put_the_default_first_with_its_status() {
        let accounts = vec![
            acct("a", "Alex", AccountStatus::Ready, false, true),
            acct("b", "Blake", AccountStatus::Refreshing, false, true),
            acct("c", "Casey", AccountStatus::Ready, true, true),
        ];
        let rows = account_rows(&accounts);
        assert_eq!(
            rows.iter().map(|r| r.uuid.as_str()).collect::<Vec<_>>(),
            ["c", "a", "b"]
        );
        assert_eq!(rows[0].status_key, "account.status.default");
        assert!(!rows[0].can_make_default);
        assert!(
            rows[2].can_make_default,
            "a refreshing account can still be made the default"
        );
    }
}
