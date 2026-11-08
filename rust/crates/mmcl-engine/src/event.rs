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
    /// An operation progressed.
    Progress(OperationProgress),
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
