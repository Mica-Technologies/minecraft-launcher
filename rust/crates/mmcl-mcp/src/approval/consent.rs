// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The consent prompt seam (Java `FxConsentPrompt`).
//!
//! [`ConsentPrompt`] is what the authorizer calls on the `mcp-dispatch` thread; the GUI
//! implements it. Most GUIs want [`DeadlineConsentPrompt`]: it hands a [`ConsentTicket`] to a
//! [`ConsentPresenter`] (which posts a dialog to the UI thread and must not block), waits up to
//! [`CONSENT_TIMEOUT`] for the answer, and on timeout denies and asks the presenter to close the
//! dialog (the Java dialog stayed on screen after its answer no longer mattered).
//!
//! Model-chosen strings are untrusted display text: render them as plain text, never markup.
//! Every displayed string here is already passed through the base redactor and bounded.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc;
use std::time::Duration;

use serde_json::Value;

use super::Answer;
use crate::tools::Args;

/// How long the dispatch thread waits for the user.
pub const CONSENT_TIMEOUT: Duration = Duration::from_secs(120);
/// At most this many arguments are shown.
pub const MAX_SUMMARIZED_ARGUMENTS: usize = 6;
/// A longer argument value is cut to this many characters plus `…`.
pub const MAX_SUMMARIZED_VALUE_LEN: usize = 120;
/// The single-line body is cut to this many characters (599 plus `…`).
pub const MAX_BODY_CHARS: usize = 600;

/// What the user is asked about.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ConsentRequest {
    /// The client's self-reported name (redacted, untrusted).
    pub client_name: String,
    /// The tool's wire name.
    pub tool_name: String,
    /// The localized tool title.
    pub tool_title: String,
    /// The localized tool description.
    pub tool_description: String,
    /// [`summarize_arguments`] of the call's arguments (redacted).
    pub arguments_summary: String,
    /// The tool's extra line (for example what an uninstall deletes), redacted.
    pub consent_detail: Option<String>,
    /// Whether the second button is "Allow for this session" (otherwise it is Cancel).
    pub offer_session_grant: bool,
}

impl ConsentRequest {
    /// Builds a request, redacting and bounding every displayed string.
    pub fn new(
        client_name: &str,
        tool_name: &str,
        tool_title: &str,
        tool_description: &str,
        args: &Args,
        consent_detail: Option<String>,
        offer_session_grant: bool,
    ) -> ConsentRequest {
        let clean = |s: &str| mmcl_log::redact(s).into_owned();
        ConsentRequest {
            client_name: bound(&clean(client_name), MAX_SUMMARIZED_VALUE_LEN),
            tool_name: tool_name.to_string(),
            tool_title: clean(tool_title),
            tool_description: clean(tool_description),
            arguments_summary: clean(&summarize_arguments(Some(args))),
            consent_detail: consent_detail
                .filter(|d| !d.trim().is_empty())
                .map(|d| bound(&clean(&d), MAX_BODY_CHARS)),
            offer_session_grant,
        }
    }

    /// `dialog.mcp.consent.title`.
    pub fn title(&self) -> String {
        mmcl_i18n::t!("dialog.mcp.consent.title").into_owned()
    }

    /// `{client} wants to use "{title}"`, single line.
    pub fn header(&self) -> String {
        sanitize_single_line(&mmcl_i18n::tf!(
            "dialog.mcp.consent.header",
            self.client_name.as_str(),
            self.tool_title.as_str()
        ))
    }

    /// The arguments summary plus, when present, the consent detail.
    pub fn detailed_arguments(&self) -> String {
        match &self.consent_detail {
            Some(d) => format!("{}\n\n{}", self.arguments_summary, d),
            None => self.arguments_summary.clone(),
        }
    }

    /// The Java dialog body as one bounded line.
    pub fn body(&self) -> String {
        sanitize_single_line(&mmcl_i18n::tf!(
            "dialog.mcp.consent.body",
            self.client_name.as_str(),
            self.tool_name.as_str(),
            self.tool_description.as_str(),
            self.detailed_arguments()
        ))
    }

    /// The first button: `Allow once`.
    pub fn allow_once_label(&self) -> String {
        mmcl_i18n::t!("dialog.mcp.consent.button.allowOnce").into_owned()
    }

    /// The second button: `Allow for this session` when offered, otherwise `Cancel`.
    pub fn second_button_label(&self) -> String {
        if self.offer_session_grant {
            mmcl_i18n::t!("dialog.mcp.consent.button.allowSession").into_owned()
        } else {
            mmcl_i18n::t!("dialog.button.cancel").into_owned()
        }
    }

    /// The answer the second button means.
    pub fn second_button_answer(&self) -> Answer {
        if self.offer_session_grant {
            Answer::AllowForSession
        } else {
            Answer::Deny
        }
    }
}

/// Asks the user. Called on the `mcp-dispatch` thread; must return within [`CONSENT_TIMEOUT`]
/// and answer [`Answer::Deny`] on any failure (fail closed).
pub trait ConsentPrompt: Send + Sync {
    /// `true` while a top-level launcher window exists; any doubt means `false`.
    fn is_available(&self) -> bool;
    /// Shows the question and waits for the answer.
    fn ask(&self, request: ConsentRequest) -> Answer;
}

/// The GUI side of [`DeadlineConsentPrompt`].
pub trait ConsentPresenter: Send + Sync {
    /// `true` while a top-level launcher window exists.
    fn is_available(&self) -> bool;
    /// Posts the dialog to the UI thread (window-modal to the top launcher window) and returns
    /// at once. The dialog answers through [`ConsentTicket::answer`]; dropping the ticket
    /// unanswered (close, Escape) denies.
    fn present(&self, ticket: ConsentTicket);
    /// The ticket expired: close its dialog if it is still showing.
    fn dismiss(&self, _ticket_id: u64) {}
}

/// One pending question.
pub struct ConsentTicket {
    id: u64,
    request: ConsentRequest,
    tx: mpsc::SyncSender<Answer>,
    expired: Arc<AtomicBool>,
}

impl std::fmt::Debug for ConsentTicket {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ConsentTicket")
            .field("id", &self.id)
            .field("tool", &self.request.tool_name)
            .finish_non_exhaustive()
    }
}

impl ConsentTicket {
    pub fn id(&self) -> u64 {
        self.id
    }

    pub fn request(&self) -> &ConsentRequest {
        &self.request
    }

    /// Whether the waiting side already gave up (the answer would be ignored).
    pub fn is_expired(&self) -> bool {
        self.expired.load(Ordering::SeqCst)
    }

    /// Delivers the answer; `false` when the question had already expired. An
    /// `AllowForSession` that was not offered counts as allow-once upstream.
    pub fn answer(&self, answer: Answer) -> bool {
        if self.is_expired() {
            return false;
        }
        self.tx.try_send(answer).is_ok()
    }
}

static NEXT_TICKET: AtomicU64 = AtomicU64::new(1);

/// A [`ConsentPrompt`] that enforces the deadline around a non-blocking presenter.
pub struct DeadlineConsentPrompt {
    presenter: Arc<dyn ConsentPresenter>,
    timeout: Duration,
}

impl DeadlineConsentPrompt {
    pub fn new(presenter: Arc<dyn ConsentPresenter>) -> DeadlineConsentPrompt {
        DeadlineConsentPrompt {
            presenter,
            timeout: CONSENT_TIMEOUT,
        }
    }

    /// A shorter deadline (tests).
    pub fn with_timeout(mut self, timeout: Duration) -> DeadlineConsentPrompt {
        self.timeout = timeout;
        self
    }
}

impl ConsentPrompt for DeadlineConsentPrompt {
    fn is_available(&self) -> bool {
        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            self.presenter.is_available()
        }))
        .unwrap_or(false)
    }

    fn ask(&self, request: ConsentRequest) -> Answer {
        if !self.is_available() {
            return Answer::Deny;
        }
        let tool = request.tool_name.clone();
        let (tx, rx) = mpsc::sync_channel(1);
        let expired = Arc::new(AtomicBool::new(false));
        let id = NEXT_TICKET.fetch_add(1, Ordering::Relaxed);
        let ticket = ConsentTicket {
            id,
            request,
            tx,
            expired: expired.clone(),
        };
        let presented = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            self.presenter.present(ticket)
        }));
        if presented.is_err() {
            crate::logging::error("log.mcpConsent.promptFailed", &[&tool]);
            return Answer::Deny;
        }
        match rx.recv_timeout(self.timeout) {
            Ok(a) => a,
            Err(mpsc::RecvTimeoutError::Timeout) => {
                expired.store(true, Ordering::SeqCst);
                crate::logging::warn("log.mcpConsent.promptTimedOut", &[&tool]);
                self.presenter.dismiss(id);
                // An answer that raced the deadline still wins; otherwise deny.
                rx.try_recv().unwrap_or(Answer::Deny)
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => Answer::Deny,
        }
    }
}

fn bound(s: &str, max: usize) -> String {
    if s.chars().count() > max {
        let mut out: String = s.chars().take(max).collect();
        out.push('\u{2026}');
        out
    } else {
        s.to_string()
    }
}

/// `key=value` pairs in insertion order, at most [`MAX_SUMMARIZED_ARGUMENTS`] (then `, …`);
/// values: `null`, `[N items]`, `{object}`, or the primitive's text cut to
/// [`MAX_SUMMARIZED_VALUE_LEN`] characters plus `…`. No arguments → `(none)`.
pub fn summarize_arguments(args: Option<&Args>) -> String {
    let Some(args) = args.filter(|a| !a.is_empty()) else {
        return mmcl_i18n::t!("dialog.mcp.consent.noArguments").into_owned();
    };
    let mut out = String::new();
    for (i, (k, v)) in args.iter().enumerate() {
        if i == MAX_SUMMARIZED_ARGUMENTS {
            out.push_str(", \u{2026}");
            break;
        }
        if i > 0 {
            out.push_str(", ");
        }
        let rendered = match v {
            Value::Null => "null".to_string(),
            Value::Array(a) => format!("[{} items]", a.len()),
            Value::Object(_) => "{object}".to_string(),
            Value::String(s) => bound(s, MAX_SUMMARIZED_VALUE_LEN),
            other => bound(&other.to_string(), MAX_SUMMARIZED_VALUE_LEN),
        };
        out.push_str(k);
        out.push('=');
        out.push_str(&rendered);
    }
    out
}

/// The Java dialog sanitizer: base redaction, line breaks to spaces, whitespace runs squeezed,
/// trimmed, cut to [`MAX_BODY_CHARS`].
pub fn sanitize_single_line(s: &str) -> String {
    let redacted = mmcl_log::redact(s);
    let squeezed = redacted.split_whitespace().collect::<Vec<_>>().join(" ");
    if squeezed.chars().count() > MAX_BODY_CHARS {
        let mut out: String = squeezed.chars().take(MAX_BODY_CHARS - 1).collect();
        out.push('\u{2026}');
        out
    } else {
        squeezed
    }
}

#[cfg(test)]
mod tests {
    //! Port of `FxConsentPromptTest` (argument summaries) plus the deadline bridge.
    use super::*;
    use parking_lot::Mutex;
    use serde_json::json;

    fn args(v: Value) -> Args {
        v.as_object().unwrap().clone()
    }

    #[test]
    fn no_arguments_is_none() {
        assert_eq!(summarize_arguments(None), "(none)");
        assert_eq!(summarize_arguments(Some(&Args::new())), "(none)");
    }

    #[test]
    fn values_render_by_kind() {
        let a = args(json!({"s":"x","n":3,"b":true,"z":null,"arr":[1,2,3],"o":{"k":1}}));
        assert_eq!(
            summarize_arguments(Some(&a)),
            "s=x, n=3, b=true, z=null, arr=[3 items], o={object}"
        );
    }

    #[test]
    fn at_most_six_arguments_are_shown() {
        let a = args(json!({"a":1,"b":2,"c":3,"d":4,"e":5,"f":6,"g":7,"h":8}));
        let s = summarize_arguments(Some(&a));
        assert_eq!(s, "a=1, b=2, c=3, d=4, e=5, f=6, \u{2026}");
    }

    #[test]
    fn long_values_are_truncated() {
        let long = "x".repeat(500);
        let s = summarize_arguments(Some(&args(json!({ "url": long }))));
        assert_eq!(
            s.chars().count(),
            "url=".len() + MAX_SUMMARIZED_VALUE_LEN + 1
        );
        assert!(s.ends_with('\u{2026}'));
        let exact = "y".repeat(MAX_SUMMARIZED_VALUE_LEN);
        let s = summarize_arguments(Some(&args(json!({ "u": exact.clone() }))));
        assert_eq!(s, format!("u={exact}"));
    }

    #[test]
    fn sanitizer_redacts_squeezes_and_bounds() {
        let s = sanitize_single_line("a\r\n\n  b\t--accessToken abc.def");
        assert_eq!(s, "a b --accessToken [REDACTED]");
        let long = sanitize_single_line(&"z ".repeat(1000));
        assert_eq!(long.chars().count(), MAX_BODY_CHARS);
        assert!(long.ends_with('\u{2026}'));
    }

    #[test]
    fn the_request_body_and_buttons() {
        let r = ConsentRequest::new(
            "Claude",
            "uninstall_modpack",
            "Uninstall modpack",
            "Removes it.",
            &args(json!({"friendlyName":"Alto"})),
            Some("This permanently deletes 1.0 GB.".into()),
            true,
        );
        assert_eq!(r.header(), "Claude wants to use \"Uninstall modpack\"");
        let body = r.body();
        assert!(body.contains("uninstall_modpack"));
        assert!(body.contains("friendlyName=Alto This permanently deletes 1.0 GB."));
        assert!(!body.contains('\n'));
        assert_eq!(r.second_button_answer(), Answer::AllowForSession);
        let r2 = ConsentRequest {
            offer_session_grant: false,
            ..r
        };
        assert_eq!(r2.second_button_answer(), Answer::Deny);
        assert_eq!(
            r2.second_button_label(),
            mmcl_i18n::t!("dialog.button.cancel")
        );
    }

    struct Presenter {
        available: bool,
        reply: Option<Answer>,
        drop_ticket: bool,
        dismissed: Mutex<Vec<u64>>,
        held: Mutex<Vec<ConsentTicket>>,
    }

    impl ConsentPresenter for Presenter {
        fn is_available(&self) -> bool {
            self.available
        }
        fn present(&self, ticket: ConsentTicket) {
            if let Some(a) = self.reply {
                ticket.answer(a);
            }
            if !self.drop_ticket {
                self.held.lock().push(ticket);
            }
        }
        fn dismiss(&self, id: u64) {
            self.dismissed.lock().push(id);
        }
    }

    fn presenter(available: bool, reply: Option<Answer>, drop_ticket: bool) -> Arc<Presenter> {
        Arc::new(Presenter {
            available,
            reply,
            drop_ticket,
            dismissed: Mutex::new(Vec::new()),
            held: Mutex::new(Vec::new()),
        })
    }

    fn req() -> ConsentRequest {
        ConsentRequest::new("c", "t", "T", "d", &Args::new(), None, true)
    }

    #[test]
    fn the_deadline_prompt_returns_the_answer() {
        let p = presenter(true, Some(Answer::AllowOnce), false);
        let prompt = DeadlineConsentPrompt::new(p);
        assert_eq!(prompt.ask(req()), Answer::AllowOnce);
    }

    #[test]
    fn no_window_denies_without_presenting() {
        let p = presenter(false, Some(Answer::AllowOnce), false);
        let prompt = DeadlineConsentPrompt::new(p.clone());
        assert!(!prompt.is_available());
        assert_eq!(prompt.ask(req()), Answer::Deny);
        assert!(p.held.lock().is_empty());
    }

    #[test]
    fn closing_the_dialog_denies() {
        let p = presenter(true, None, true);
        let prompt = DeadlineConsentPrompt::new(p);
        assert_eq!(prompt.ask(req()), Answer::Deny);
    }

    #[test]
    fn a_timeout_denies_and_dismisses_the_dialog() {
        let p = presenter(true, None, false);
        let prompt = DeadlineConsentPrompt::new(p.clone()).with_timeout(Duration::from_millis(30));
        assert_eq!(prompt.ask(req()), Answer::Deny);
        let held = p.held.lock();
        assert_eq!(p.dismissed.lock().as_slice(), &[held[0].id()]);
        assert!(held[0].is_expired());
        assert!(!held[0].answer(Answer::AllowOnce));
    }
}
