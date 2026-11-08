// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Shared dialogs (`GUIUtilities.show*Message`), owned by the main window by default.
//!
//! These wrap [`mmcl_ui_kit::dialogs`] (queued, themed, in-window gpui-component dialogs, with
//! the Java sanitizing rules: one-line bodies capped at 600 chars, multi-line at 4000, a second
//! "Cancel" button folded into the cancel role). Await them from a foreground `cx.spawn`; from
//! another thread use [`crate::GuiHandle::ask`] and friends.

use gpui::{AnyWindowHandle, App, AsyncApp};
use mmcl_ui_kit::dialogs as kit;

pub use mmcl_ui_kit::dialogs::{
    Answer, ButtonRole, DialogButton, DialogLevel, DialogSpec, sanitize_multiline,
    sanitize_single_line,
};

/// A two-choice question.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Question {
    /// Dialog title.
    pub title: String,
    /// Bold header line (may be empty).
    pub header: String,
    /// Body text.
    pub body: String,
    /// First (primary) button.
    pub primary: String,
    /// Second button (when it reads "Cancel" it is the cancel button).
    pub secondary: String,
}

impl Question {
    /// A question from its parts.
    pub fn new(
        title: impl Into<String>,
        header: impl Into<String>,
        body: impl Into<String>,
        primary: impl Into<String>,
        secondary: impl Into<String>,
    ) -> Self {
        Question {
            title: title.into(),
            header: header.into(),
            body: body.into(),
            primary: primary.into(),
            secondary: secondary.into(),
        }
    }
}

/// The main window, as the default dialog owner.
pub fn main_owner(cx: &App) -> Option<AnyWindowHandle> {
    crate::shell::main_window_handle(cx)
}

fn owner(cx: &AsyncApp, owner: Option<AnyWindowHandle>) -> Option<AnyWindowHandle> {
    owner.or_else(|| cx.update(|cx| main_owner(cx)))
}

/// `showQuestionMessage`.
pub async fn ask(cx: &AsyncApp, q: &Question, window: Option<AnyWindowHandle>) -> Answer {
    let window = owner(cx, window);
    kit::ask(
        cx,
        &q.title,
        &q.header,
        &q.body,
        &q.primary,
        &q.secondary,
        window,
    )
    .await
}

/// `showQuestionMessageMultiline`.
pub async fn ask_multiline(cx: &AsyncApp, q: &Question, window: Option<AnyWindowHandle>) -> Answer {
    let window = owner(cx, window);
    kit::ask_multiline(
        cx,
        &q.title,
        &q.header,
        &q.body,
        &q.primary,
        &q.secondary,
        window,
    )
    .await
}

/// `showErrorMessage`.
pub async fn error(cx: &AsyncApp, body: &str, window: Option<AnyWindowHandle>) {
    let window = owner(cx, window);
    kit::error(cx, body, window).await;
}

/// `showErrorMessageMultiline`.
pub async fn error_multiline(cx: &AsyncApp, body: &str, window: Option<AnyWindowHandle>) {
    let window = owner(cx, window);
    kit::error_multiline(cx, body, window).await;
}

/// `showWarningMessage`.
pub async fn warning(cx: &AsyncApp, body: &str, window: Option<AnyWindowHandle>) {
    let window = owner(cx, window);
    kit::warning(cx, body, window).await;
}

/// `showErrorMessageRetry`: `true` when the user chose to retry.
pub async fn error_retry(
    cx: &AsyncApp,
    body: &str,
    retry_label: &str,
    window: Option<AnyWindowHandle>,
) -> bool {
    let window = owner(cx, window);
    kit::error_retry(cx, body, window, retry_label).await
}
