// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The event bus: everything that changes behind a front end's back.
//! [`crate::Engine::subscribe`] hands out a `tokio::sync::broadcast::Receiver<EngineEvent>`.
//! A slow receiver gets `RecvError::Lagged` and should re-read the state it shows (every
//! event has a matching getter), so no event carries data a getter cannot give again.

use std::sync::Arc;

use mmcl_auth::DeviceCodePrompt;

use crate::model::{PackId, RgbStatusView};
use crate::session::{SessionId, SessionInfo};

/// How a notification looks.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum NotificationKind {
    Info,
    Success,
    Warning,
    Error,
}

/// A toast / OS notification, already localized.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Notification {
    pub kind: NotificationKind,
    pub title: String,
    pub body: String,
    /// The pack it is about, so a click can open it.
    pub pack: Option<PackId>,
    /// The session it is about, so a click can bring its tab forward.
    pub session: Option<SessionId>,
}

impl Notification {
    /// A notification with no pack or session.
    pub fn new(kind: NotificationKind, title: impl Into<String>, body: impl Into<String>) -> Self {
        Notification {
            kind,
            title: title.into(),
            body: body.into(),
            pack: None,
            session: None,
        }
    }
}

/// One operation's progress (install, verify, runtime install, export, import, scan).
#[derive(Clone, Debug, PartialEq)]
pub struct OperationProgress {
    /// Which operation (from [`crate::Engine::next_operation_id`] or the session id).
    pub operation: OperationId,
    /// Upper heading (what), localized.
    pub label: String,
    /// Current step, localized.
    pub text: String,
    /// 0..1, negative for indeterminate.
    pub fraction: f64,
}

/// Id of a long-running engine operation.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct OperationId(pub u64);

/// What a sign-in reports while it runs.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SignInEvent {
    /// The device code flow has a code to show (code, `microsoft.com/link`, expiry). May repeat
    /// when the code is renewed.
    DeviceCode(DeviceCodePrompt),
    /// A chain step (`section`, `detail`), localized.
    Status { section: String, detail: String },
}

/// What the web view sign-in's page is doing (the helper's page lines), for Java's loading
/// overlay and its "couldn't reach Microsoft's sign-in page, Try again" state.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum SignInPageState {
    /// Microsoft's page is loading: show the spinner with [`SignInPageState::message`].
    Loading,
    /// The page is up: hide the overlay.
    Loaded,
    /// The page could not be reached: show the message and a "Try again" button
    /// ([`crate::Engine::retry_sign_in_page`]).
    LoadFailed,
}

impl SignInPageState {
    /// The overlay text (`signIn.loading` / `signIn.loadFailed`); `None` when loaded.
    pub fn message(self) -> Option<String> {
        match self {
            SignInPageState::Loading => Some(mmcl_i18n::t!("signIn.loading").into_owned()),
            SignInPageState::LoadFailed => Some(mmcl_i18n::t!("signIn.loadFailed").into_owned()),
            SignInPageState::Loaded => None,
        }
    }

    /// Whether the overlay offers "Try again" (label `signIn.retry`).
    pub fn offers_retry(self) -> bool {
        self == SignInPageState::LoadFailed
    }
}

impl From<mmcl_auth::SignInPageEvent> for SignInPageState {
    fn from(e: mmcl_auth::SignInPageEvent) -> Self {
        match e {
            mmcl_auth::SignInPageEvent::Loading => SignInPageState::Loading,
            mmcl_auth::SignInPageEvent::Loaded => SignInPageState::Loaded,
            mmcl_auth::SignInPageEvent::LoadFailed => SignInPageState::LoadFailed,
        }
    }
}

/// The step rows of a running verify / update / launch operation (Java's Launch Progress
/// window), from [`crate::Engine::operation_steps`] or [`EngineEvent::OperationSteps`].
#[derive(Clone, Debug, PartialEq)]
pub struct OperationStepsView {
    pub operation: OperationId,
    /// The pack the rows are about (verify-all moves from pack to pack).
    pub pack: Option<PackId>,
    /// The session, when the operation is a launch.
    pub session: Option<SessionId>,
    /// Upper heading, localized (`engine.progress.verify` / `engine.progress.launch`).
    pub label: String,
    /// The rows in pipeline order.
    pub steps: Vec<crate::session::LaunchStepView>,
    /// 0..1 over every row.
    pub fraction: f64,
    /// Cancel was asked for.
    pub cancel_requested: bool,
}

/// Everything that changes behind a front end's back.
#[derive(Clone, Debug)]
pub enum EngineEvent {
    /// The installed or available pack lists changed (re-read [`crate::Engine::installed_packs`]).
    PacksChanged,
    /// One pack's state changed (install, update, play stats, news seen).
    PackChanged(PackId),
    /// Accounts were added, removed, refreshed, or the default moved.
    AccountsChanged,
    /// A sign-in progressed.
    SignIn(SignInEvent),
    /// A session was created.
    SessionStarted(Arc<SessionInfo>),
    /// A session's phase, steps or crash report changed.
    SessionChanged(Arc<SessionInfo>),
    /// A session was dismissed or pruned.
    SessionRemoved(SessionId),
    /// New output lines (redacted). For a lossless stream use
    /// [`crate::Engine::subscribe_log`]; this event may lag.
    SessionOutput {
        session: SessionId,
        lines: Arc<[String]>,
    },
    /// The web view sign-in's page changed state (loading / loaded / load failed).
    SignInPage(SignInPageState),
    /// An operation progressed.
    Progress(OperationProgress),
    /// The step rows of a verify / update / launch operation changed (throttled like
    /// `Progress`; terminal step states always arrive).
    OperationSteps(Arc<OperationStepsView>),
    /// An operation finished (successfully or not; the operation's own future has the result).
    OperationFinished(OperationId),
    /// The launcher went offline or came back.
    OfflineChanged(bool),
    /// A setting changed (the config key).
    SettingChanged(&'static str),
    /// Show a toast.
    Notification(Notification),
    /// The RGB status chip changed.
    RgbStatusChanged(RgbStatusView),
    /// Announcement banners changed.
    AnnouncementsChanged,
    /// The engine asks the front end to restart the launcher (language change, reset).
    RestartRequested,
    /// The engine is shutting down.
    ShuttingDown,
}
