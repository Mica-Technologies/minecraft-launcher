// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The hooks through which log calls reach a user interface.
//!
//! The logger has no GUI dependency. The app crate registers a [`UiSink`] once a window exists
//! (dialogs for `error*`, the toast for `warn`), and the engine may register a [`ToastHook`] that
//! turns warnings into notification events. Both are optional; without them every call is a
//! plain log line.

/// GUI side effects of the dialog-raising log calls. Set by `mmcl-app` once the GUI exists.
///
/// Implementations are called with no logger lock held, so they may log themselves (nested
/// calls from inside a sink are logged but not re-dispatched to the UI).
pub trait UiSink: Send + Sync {
    /// A **blocking** modal error. Must not be called while holding locks.
    fn error_modal(&self, msg: &str);
    /// A non-blocking error dialog posted to the UI thread.
    fn error_async(&self, msg: &str);
    /// A blocking modal error that keeps the message's line breaks.
    fn error_multiline(&self, msg: &str);
    /// A blocking error dialog with a retry button; `true` when the user chose retry.
    fn error_retry(&self, msg: &str, retry: &str) -> bool;
    /// A non-modal warning toast.
    fn warn_toast(&self, title: &str, msg: &str);
    /// `true` while a launcher window is on screen (Java `getTopStageOrNull() != null`).
    fn has_window(&self) -> bool;
}

/// The engine's warning-toast callback: `(title, message)`.
///
/// When registered it receives every [`crate::warn`] call, whether or not a window is up (the
/// engine decides how to surface it, typically as a notification event). When none is
/// registered, warnings go to the [`UiSink`]'s toast if it has a window.
pub type ToastHook = dyn Fn(&str, &str) + Send + Sync;
