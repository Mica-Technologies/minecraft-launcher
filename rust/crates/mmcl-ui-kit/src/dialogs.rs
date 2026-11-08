// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The dialogs service (`GUIUtilities` show* methods), async and never blocking the UI thread.
//!
//! - Every call is `async` and answers through a oneshot; callers `.await` inside a `cx.spawn`
//!   task. Without a GUI this session (server, TUI, or [`init`] never called) every call returns
//!   its "no answer" default immediately: `Answer::Closed`, `false`, `None` (the Java latch that
//!   was never released, and the headless `Platform.startup()` that hung, cannot happen).
//! - Dialogs are **queued**: a second dialog waits for the first instead of re-entering (GPUI's
//!   native `window.prompt` panics on re-entry).
//! - In a window whose root is gpui-component's `Root` the dialog is an in-window, themed
//!   gpui-component `Dialog` (solid launcher surface, Esc = the cancel role). Any other window
//!   falls back to the platform prompt (`window.prompt`), which is native and unthemed.
//! - Caller text is sanitized ([`sanitize_single_line`] / [`sanitize_multiline`]): redacted,
//!   whitespace-normalised and length-capped.

use std::cell::RefCell;
use std::rc::Rc;

use futures::channel::oneshot;
use futures::lock::Mutex as AsyncMutex;
use gpui::{
    AnyWindowHandle, App, AppContext as _, AsyncApp, ClickEvent, Entity, Global,
    InteractiveElement, IntoElement, ParentElement, PromptButton, PromptLevel, SharedString,
    StatefulInteractiveElement, Styled, Window, div, prelude::FluentBuilder as _,
};
use gpui_component::WindowExt as _;
use gpui_component::button::{Button, ButtonVariants as _};
use gpui_component::dialog::Dialog;
use gpui_component::input::{Input, InputState};
use mmcl_i18n::{t, tf};

use crate::scale::{scaled_px, u};
use crate::sys;
use crate::theme::{ThemeExt, TypeStyleExt, hsla};

/// Single-line content cap (characters).
pub const MAX_SINGLE_LINE: usize = 600;
/// Multi-line content cap (characters).
pub const MAX_MULTILINE: usize = 4000;
/// Width of a single-line dialog (px).
pub const WIDTH: f32 = 480.0;
/// Preferred width of a multi-line dialog (px).
pub const WIDTH_MULTILINE: f32 = 640.0;
/// Minimum width of a multi-line dialog (px).
pub const MIN_WIDTH_MULTILINE: f32 = 560.0;

/// How serious a dialog is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum DialogLevel {
    /// Informational.
    Info,
    /// A question with choices.
    Question,
    /// A warning.
    Warning,
    /// An error.
    Error,
}

impl DialogLevel {
    fn prompt_level(self) -> PromptLevel {
        match self {
            DialogLevel::Info | DialogLevel::Question => PromptLevel::Info,
            DialogLevel::Warning => PromptLevel::Warning,
            DialogLevel::Error => PromptLevel::Critical,
        }
    }
}

/// A button's role.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ButtonRole {
    /// The default action (filled).
    Primary,
    /// Another action (tonal).
    Secondary,
    /// Cancels: Esc and closing the dialog choose it.
    Cancel,
}

/// One dialog button.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DialogButton {
    /// The label.
    pub label: SharedString,
    /// The role.
    pub role: ButtonRole,
}

impl DialogButton {
    /// A button.
    pub fn new(label: impl Into<SharedString>, role: ButtonRole) -> Self {
        DialogButton {
            label: label.into(),
            role,
        }
    }
}

/// A complete dialog description.
#[derive(Debug, Clone)]
pub struct DialogSpec {
    /// Level (icon / native prompt level).
    pub level: DialogLevel,
    /// Window or card title.
    pub title: SharedString,
    /// Optional header line above the body.
    pub header: Option<SharedString>,
    /// Body text (already sanitized by the convenience methods).
    pub body: SharedString,
    /// Whether the body keeps line breaks (wider dialog).
    pub multiline: bool,
    /// Buttons, left to right in reading order of importance.
    pub buttons: Vec<DialogButton>,
}

/// The answer to a two-choice question (`showQuestionMessage`'s 0/1/2).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Answer {
    /// Closed or cancelled (0).
    Closed = 0,
    /// The first button (1).
    Primary = 1,
    /// The second button (2).
    Secondary = 2,
}

impl Answer {
    /// The Java integer code.
    pub fn code(self) -> u8 {
        self as u8
    }
}

/// `GUIUtilities.sanitizeDialogText`: redact; `\n`/`\r` to spaces; collapse whitespace runs to
/// one space; trim; over 600 characters keeps the first 599 plus `…`.
pub fn sanitize_single_line(s: &str) -> String {
    if s.is_empty() {
        return String::new();
    }
    let redacted = sys::redact(s);
    let one_line = redacted.split_whitespace().collect::<Vec<_>>().join(" ");
    cap(one_line, MAX_SINGLE_LINE)
}

/// `GUIUtilities.sanitizeDialogTextMultiline`: redact; CRLF and lone CR to LF; strip trailing
/// spaces/tabs per line; collapse 3+ newlines to 2; trim; over 4000 characters keeps the first
/// 3999 plus `…`.
pub fn sanitize_multiline(s: &str) -> String {
    if s.is_empty() {
        return String::new();
    }
    let redacted = sys::redact(s);
    let normalized = redacted.replace("\r\n", "\n").replace('\r', "\n");
    let trimmed_lines = normalized
        .split('\n')
        .map(|line| line.trim_end_matches([' ', '\t']))
        .collect::<Vec<_>>()
        .join("\n");
    let mut out = String::with_capacity(trimmed_lines.len());
    let mut run = 0usize;
    for c in trimmed_lines.chars() {
        if c == '\n' {
            run += 1;
            if run <= 2 {
                out.push(c);
            }
        } else {
            run = 0;
            out.push(c);
        }
    }
    cap(out.trim().to_string(), MAX_MULTILINE)
}

fn cap(s: String, max: usize) -> String {
    if s.chars().count() > max {
        let mut out: String = s.chars().take(max - 1).collect();
        out.push('…');
        out
    } else {
        s
    }
}

/// Whether a label is a cancel label (`"Cancel"` or the localized `dialog.button.cancel`,
/// trimmed, case-insensitive).
pub fn is_cancel_label(label: &str) -> bool {
    let label = label.trim();
    label.eq_ignore_ascii_case("Cancel")
        || label.to_lowercase() == t!("dialog.button.cancel").trim().to_lowercase()
}

/// The buttons of a two-choice question: `b1`, `b2`, plus a separate Cancel - unless `b2` is
/// itself a cancel label, in which case it becomes the cancel-role button and no extra Cancel
/// is added (no two Cancel buttons).
pub fn question_buttons(b1: &str, b2: &str) -> Vec<DialogButton> {
    if is_cancel_label(b2) {
        vec![
            DialogButton::new(b1.to_string(), ButtonRole::Primary),
            DialogButton::new(b2.to_string(), ButtonRole::Cancel),
        ]
    } else {
        vec![
            DialogButton::new(b1.to_string(), ButtonRole::Primary),
            DialogButton::new(b2.to_string(), ButtonRole::Secondary),
            DialogButton::new(t!("dialog.button.cancel").into_owned(), ButtonRole::Cancel),
        ]
    }
}

/// Maps a clicked button index of [`question_buttons`] to an [`Answer`]: the first button is
/// 1, a non-cancel second button is 2, anything else (cancel, close, dropped) is 0.
pub fn answer_for(buttons: &[DialogButton], index: Option<usize>) -> Answer {
    match index {
        Some(i) if i < buttons.len() && buttons[i].role != ButtonRole::Cancel => match i {
            0 => Answer::Primary,
            1 => Answer::Secondary,
            _ => Answer::Closed,
        },
        _ => Answer::Closed,
    }
}

/// The dialogs service global: the queue lock. Installed by [`init`] in GUI sessions only.
pub struct Dialogs {
    queue: Rc<AsyncMutex<()>>,
}

impl Global for Dialogs {}

/// Enables dialogs for this session (GUI mode). Without it every dialog answers "no answer".
pub fn init(cx: &mut App) {
    cx.set_global(Dialogs {
        queue: Rc::new(AsyncMutex::new(())),
    });
}

/// Whether dialogs are enabled.
pub fn is_enabled(cx: &App) -> bool {
    cx.has_global::<Dialogs>()
}

type Reply<T> = Rc<RefCell<Option<oneshot::Sender<T>>>>;

fn reply<T>(slot: &Reply<T>, value: T) {
    if let Some(tx) = slot.borrow_mut().take() {
        let _ = tx.send(value);
    }
}

fn pick_window(cx: &App, owner: Option<AnyWindowHandle>) -> Option<AnyWindowHandle> {
    let windows = cx.windows();
    owner
        .filter(|o| windows.contains(o))
        .or_else(|| cx.active_window())
        .or_else(|| windows.first().copied())
}

fn has_component_root(window: &Window) -> bool {
    matches!(window.root::<gpui_component::Root>(), Some(Some(_)))
}

/// Runs one dialog: waits its turn, presents it in the chosen window, awaits the answer.
async fn run<T: 'static>(
    cx: &AsyncApp,
    owner: Option<AnyWindowHandle>,
    default: T,
    present: impl FnOnce(&mut Window, &mut App, Reply<T>) + 'static,
) -> T {
    let Some(queue) = cx.try_read_global::<Dialogs, _>(|d, _| d.queue.clone()) else {
        tracing::debug!("{}", t!("log.guiUtilities.fxTaskSkippedNoToolkit"));
        return default;
    };
    let _turn = queue.lock().await;
    let Some(window) = cx.update(|cx| pick_window(cx, owner)) else {
        return default;
    };
    let (tx, rx) = oneshot::channel::<T>();
    let slot: Reply<T> = Rc::new(RefCell::new(Some(tx)));
    let presented = cx.update(|cx| {
        window
            .update(cx, |_, window, cx| present(window, cx, slot))
            .is_ok()
    });
    if !presented {
        return default;
    }
    match rx.await {
        Ok(v) => v,
        Err(_) => {
            // The window went away mid-dialog: treat as cancel.
            tracing::warn!("{}", t!("log.guiUtilities.fxTaskWaitInterrupted"));
            default
        }
    }
}

fn body_element(spec: &DialogSpec, cx: &App) -> impl IntoElement {
    let t = cx.tokens();
    div()
        .flex()
        .flex_col()
        .gap(u(8.0))
        .when_some(spec.header.clone(), |el, header| {
            el.child(
                div()
                    .type_role(mmcl_theme::typography::TypeRole::TitleMedium)
                    .text_color(hsla(t.md_on_surface))
                    .child(header),
            )
        })
        .child(
            div()
                .text_color(hsla(t.md_on_surface_variant))
                .when(spec.multiline, |el| el.max_w(u(600.0)))
                .child(spec.body.clone()),
        )
}

fn styled_button(id: usize, button: &DialogButton) -> Button {
    let b = Button::new(("mmcl-dialog-button", id)).label(button.label.clone());
    match button.role {
        ButtonRole::Primary => b.primary(),
        ButtonRole::Secondary => b,
        ButtonRole::Cancel => b.ghost(),
    }
}

fn present_component(
    window: &mut Window,
    cx: &mut App,
    spec: DialogSpec,
    slot: Reply<Option<usize>>,
) {
    let cancel = spec
        .buttons
        .iter()
        .position(|b| b.role == ButtonRole::Cancel);
    window.open_dialog(cx, move |dialog: Dialog, _window, cx| {
        let footer =
            div()
                .flex()
                .justify_end()
                .gap(u(8.0))
                .children(spec.buttons.iter().enumerate().map(|(i, b)| {
                    let slot = slot.clone();
                    styled_button(i, b).on_click(move |_: &ClickEvent, window, cx| {
                        reply(&slot, Some(i));
                        window.close_dialog(cx);
                    })
                }));
        let on_close = slot.clone();
        let on_enter = slot.clone();
        let has_buttons = !spec.buttons.is_empty();
        dialog
            // Enter (the `Confirm` action) chooses the first button; Esc closes (cancel role).
            .on_ok(move |_, _, _| {
                if has_buttons {
                    reply(&on_enter, Some(0));
                }
                true
            })
            .title(spec.title.clone())
            // Solid surface: the native sheets' translucent background would vanish in a popup.
            .bg(cx.launcher_theme().solid_bg(true))
            .w(scaled_px(cx, if spec.multiline {
                WIDTH_MULTILINE
            } else {
                WIDTH
            }))
            .when(spec.multiline, |d| {
                d.max_w(scaled_px(cx, WIDTH_MULTILINE.max(MIN_WIDTH_MULTILINE)))
            })
            .keyboard(true)
            .overlay_closable(false)
            .child(body_element(&spec, cx))
            .footer(footer)
            .on_close(move |_, _, _| reply(&on_close, cancel))
    });
}

fn present_native(window: &mut Window, cx: &mut App, spec: DialogSpec, slot: Reply<Option<usize>>) {
    let answers: Vec<PromptButton> = spec
        .buttons
        .iter()
        .map(|b| match b.role {
            ButtonRole::Cancel => PromptButton::Cancel(b.label.clone()),
            ButtonRole::Primary => PromptButton::Ok(b.label.clone()),
            ButtonRole::Secondary => PromptButton::Other(b.label.clone()),
        })
        .collect();
    let message = spec.header.clone().unwrap_or_else(|| spec.title.clone());
    let detail = spec.body.to_string();
    let rx = window.prompt(
        spec.level.prompt_level(),
        &message,
        Some(&detail),
        &answers,
        cx,
    );
    cx.spawn(async move |_| {
        let index = rx.await.ok();
        reply(&slot, index);
    })
    .detach();
}

/// Shows any dialog; the clicked button's index, or `None` when closed/cancelled by Esc.
/// (Cancel-role buttons report their index too; use [`answer_for`] to fold them.)
pub async fn show(
    cx: &AsyncApp,
    spec: DialogSpec,
    owner: Option<AnyWindowHandle>,
) -> Option<usize> {
    run(cx, owner, None, move |window, cx, slot| {
        if has_component_root(window) {
            present_component(window, cx, spec, slot);
        } else {
            present_native(window, cx, spec, slot);
        }
    })
    .await
}

/// `showQuestionMessage`: a two-choice question (single-line body).
pub async fn ask(
    cx: &AsyncApp,
    title: &str,
    header: &str,
    body: &str,
    b1: &str,
    b2: &str,
    owner: Option<AnyWindowHandle>,
) -> Answer {
    ask_inner(
        cx,
        title,
        header,
        sanitize_single_line(body),
        false,
        b1,
        b2,
        owner,
    )
    .await
}

/// `showQuestionMessageMultiline`: a two-choice question keeping line breaks (wider dialog).
pub async fn ask_multiline(
    cx: &AsyncApp,
    title: &str,
    header: &str,
    body: &str,
    b1: &str,
    b2: &str,
    owner: Option<AnyWindowHandle>,
) -> Answer {
    ask_inner(
        cx,
        title,
        header,
        sanitize_multiline(body),
        true,
        b1,
        b2,
        owner,
    )
    .await
}

#[allow(clippy::too_many_arguments)]
async fn ask_inner(
    cx: &AsyncApp,
    title: &str,
    header: &str,
    body: String,
    multiline: bool,
    b1: &str,
    b2: &str,
    owner: Option<AnyWindowHandle>,
) -> Answer {
    let buttons = question_buttons(b1, b2);
    let spec = DialogSpec {
        level: DialogLevel::Question,
        title: title.to_string().into(),
        header: (!header.is_empty()).then(|| header.to_string().into()),
        body: body.into(),
        multiline,
        buttons: buttons.clone(),
    };
    answer_for(&buttons, show(cx, spec, owner).await)
}

fn alert_spec(level: DialogLevel, body: String, multiline: bool) -> DialogSpec {
    let (title, header) = match level {
        DialogLevel::Warning => (
            t!("dialog.alert.warning.title"),
            t!("dialog.alert.warning.header"),
        ),
        _ => (
            t!("dialog.alert.error.title"),
            t!("dialog.alert.error.header"),
        ),
    };
    DialogSpec {
        level,
        title: title.into_owned().into(),
        header: Some(header.into_owned().into()),
        body: body.into(),
        multiline,
        buttons: vec![DialogButton::new(
            t!("dialog.button.ok").into_owned(),
            ButtonRole::Primary,
        )],
    }
}

/// `showErrorMessage`.
pub async fn error(cx: &AsyncApp, body: &str, owner: Option<AnyWindowHandle>) {
    show(
        cx,
        alert_spec(DialogLevel::Error, sanitize_single_line(body), false),
        owner,
    )
    .await;
}

/// `showErrorMessageMultiline`.
pub async fn error_multiline(cx: &AsyncApp, body: &str, owner: Option<AnyWindowHandle>) {
    show(
        cx,
        alert_spec(DialogLevel::Error, sanitize_multiline(body), true),
        owner,
    )
    .await;
}

/// `showWarningMessage`.
pub async fn warning(cx: &AsyncApp, body: &str, owner: Option<AnyWindowHandle>) {
    show(
        cx,
        alert_spec(DialogLevel::Warning, sanitize_single_line(body), false),
        owner,
    )
    .await;
}

/// `showErrorMessageRetry`: `true` only when the user chose `retry_label`. With no owner (or a
/// hidden one) it is shown in the active window instead of forcing a hidden owner to show.
pub async fn error_retry(
    cx: &AsyncApp,
    body: &str,
    owner: Option<AnyWindowHandle>,
    retry_label: &str,
) -> bool {
    let mut spec = alert_spec(DialogLevel::Error, sanitize_single_line(body), false);
    spec.buttons = vec![
        DialogButton::new(retry_label.to_string(), ButtonRole::Primary),
        DialogButton::new(t!("dialog.button.cancel").into_owned(), ButtonRole::Cancel),
    ];
    show(cx, spec, owner).await == Some(0)
}

/// A text prompt: `Some(text)` on OK, `None` on cancel/close or when no themed window exists.
pub async fn input(
    cx: &AsyncApp,
    title: &str,
    header: &str,
    label: &str,
    initial: &str,
    owner: Option<AnyWindowHandle>,
) -> Option<String> {
    let title: SharedString = title.to_string().into();
    let header: SharedString = header.to_string().into();
    let label: SharedString = label.to_string().into();
    let initial = initial.to_string();
    run(cx, owner, None, move |window, cx, slot| {
        if !has_component_root(window) {
            tracing::warn!("{}", tf!("log.uiKit.dialogNeedsRoot", title.as_ref()));
            reply(&slot, None);
            return;
        }
        let state = cx.new(|cx| InputState::new(window, cx).default_value(initial));
        state.update(cx, |s, cx| s.focus(window, cx));
        window.open_dialog(cx, move |dialog: Dialog, _window, cx| {
            let tokens = cx.tokens();
            let ok_slot = slot.clone();
            let ok_state = state.clone();
            let cancel_slot = slot.clone();
            let close_slot = slot.clone();
            dialog
                .title(title.clone())
                .bg(cx.launcher_theme().solid_bg(true))
                .w(scaled_px(cx, WIDTH))
                .keyboard(true)
                .overlay_closable(false)
                .child(
                    div()
                        .flex()
                        .flex_col()
                        .gap(u(8.0))
                        .when(!header.is_empty(), |el| {
                            el.child(
                                div()
                                    .text_color(hsla(tokens.md_on_surface_variant))
                                    .child(header.clone()),
                            )
                        })
                        .child(
                            div()
                                .type_role(mmcl_theme::typography::TypeRole::LabelLarge)
                                .text_color(hsla(tokens.md_on_surface))
                                .child(label.clone()),
                        )
                        .child(Input::new(&state)),
                )
                .footer(
                    div()
                        .flex()
                        .justify_end()
                        .gap(u(8.0))
                        .child(
                            Button::new("mmcl-input-cancel")
                                .ghost()
                                .label(t!("dialog.button.cancel").into_owned())
                                .on_click(move |_, window, cx| {
                                    reply(&cancel_slot, None);
                                    window.close_dialog(cx);
                                }),
                        )
                        .child(
                            Button::new("mmcl-input-ok")
                                .primary()
                                .label(t!("dialog.button.ok").into_owned())
                                .on_click(move |_, window, cx| {
                                    let value = ok_state.read(cx).value().to_string();
                                    reply(&ok_slot, Some(value));
                                    window.close_dialog(cx);
                                }),
                        ),
                )
                .on_close(move |_, _, _| reply(&close_slot, None))
        });
    })
    .await
}

/// Indices of `items` matching `filter` (case-insensitive substring on every whitespace-split
/// token), in their original order.
pub fn filter_items(items: &[SharedString], filter: &str) -> Vec<usize> {
    let tokens: Vec<String> = filter
        .split_whitespace()
        .map(|t| t.to_lowercase())
        .collect();
    items
        .iter()
        .enumerate()
        .filter(|(_, item)| {
            let hay = item.to_lowercase();
            tokens.iter().all(|t| hay.contains(t.as_str()))
        })
        .map(|(i, _)| i)
        .collect()
}

/// A filterable list picker (the editor's loader-version pickers): the chosen item's index.
pub async fn choose(
    cx: &AsyncApp,
    title: &str,
    header: &str,
    label: &str,
    items: Vec<SharedString>,
    owner: Option<AnyWindowHandle>,
) -> Option<usize> {
    let title: SharedString = title.to_string().into();
    let header: SharedString = header.to_string().into();
    let label: SharedString = label.to_string().into();
    let items = Rc::new(items);
    run(cx, owner, None, move |window, cx, slot| {
        if !has_component_root(window) {
            tracing::warn!("{}", tf!("log.uiKit.dialogNeedsRoot", title.as_ref()));
            reply(&slot, None);
            return;
        }
        let filter: Entity<InputState> = cx.new(|cx| {
            InputState::new(window, cx).placeholder(t!("filter.search.prompt").into_owned())
        });
        filter.update(cx, |s, cx| s.focus(window, cx));
        window.open_dialog(cx, move |dialog: Dialog, _window, cx| {
            let tokens = cx.tokens();
            let query = filter.read(cx).value().to_string();
            let shown = filter_items(&items, &query);
            let rows = shown.into_iter().map(|i| {
                let slot = slot.clone();
                div()
                    .id(("mmcl-choose-row", i))
                    .px(u(12.0))
                    .py(u(6.0))
                    .rounded(u(mmcl_theme::shape::SMALL))
                    .cursor_pointer()
                    .text_color(hsla(tokens.md_on_surface))
                    .hover(|s| s.bg(hsla(tokens.md_state_hover_on_surface)))
                    .child(items[i].clone())
                    .on_click(move |_, window, cx| {
                        reply(&slot, Some(i));
                        window.close_dialog(cx);
                    })
            });
            let cancel_slot = slot.clone();
            let close_slot = slot.clone();
            dialog
                .title(title.clone())
                .bg(cx.launcher_theme().solid_bg(true))
                .w(scaled_px(cx, WIDTH))
                .keyboard(true)
                .overlay_closable(false)
                .child(
                    div()
                        .flex()
                        .flex_col()
                        .gap(u(8.0))
                        .when(!header.is_empty(), |el| {
                            el.child(
                                div()
                                    .text_color(hsla(tokens.md_on_surface_variant))
                                    .child(header.clone()),
                            )
                        })
                        .child(
                            div()
                                .text_color(hsla(tokens.md_on_surface))
                                .child(label.clone()),
                        )
                        .child(Input::new(&filter))
                        .child(
                            div()
                                .id("mmcl-choose-list")
                                .max_h(u(320.0))
                                .overflow_y_scroll()
                                .flex()
                                .flex_col()
                                .children(rows),
                        ),
                )
                .footer(
                    div().flex().justify_end().child(
                        Button::new("mmcl-choose-cancel")
                            .ghost()
                            .label(t!("dialog.button.cancel").into_owned())
                            .on_click(move |_, window, cx| {
                                reply(&cancel_slot, None);
                                window.close_dialog(cx);
                            }),
                    ),
                )
                .on_close(move |_, _, _| reply(&close_slot, None))
        });
    })
    .await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn single_line_collapses_and_caps() {
        assert_eq!(sanitize_single_line(""), "");
        assert_eq!(sanitize_single_line("  a\nb\r\n  c\t d  "), "a b c d");
        let long = "x".repeat(700);
        let out = sanitize_single_line(&long);
        assert_eq!(out.chars().count(), MAX_SINGLE_LINE);
        assert!(out.ends_with('…'));
        assert_eq!(out.chars().filter(|c| *c == 'x').count(), 599);
        let exact = "y".repeat(600);
        assert_eq!(sanitize_single_line(&exact), exact);
    }

    #[test]
    fn single_line_redacts() {
        assert_eq!(
            sanitize_single_line("failed: --accessToken abc.def\nretry"),
            "failed: --accessToken [REDACTED] retry"
        );
    }

    #[test]
    fn multiline_keeps_paragraphs() {
        assert_eq!(sanitize_multiline("a  \r\nb\t\rc"), "a\nb\nc");
        assert_eq!(sanitize_multiline("a\n\n\n\n\nb"), "a\n\nb");
        assert_eq!(sanitize_multiline("\n\n  a\n  "), "a");
        let long = "z".repeat(5000);
        let out = sanitize_multiline(&long);
        assert_eq!(out.chars().count(), MAX_MULTILINE);
        assert!(out.ends_with('…'));
        // Non-ASCII is capped by characters, never split mid-codepoint.
        let wide = "é".repeat(4500);
        assert_eq!(sanitize_multiline(&wide).chars().count(), MAX_MULTILINE);
    }

    #[test]
    fn cancel_is_folded_into_b2() {
        let folded = question_buttons("Delete", "  cancel ");
        assert_eq!(folded.len(), 2);
        assert_eq!(folded[1].role, ButtonRole::Cancel);
        let three = question_buttons("Leave running", "Stop games");
        assert_eq!(three.len(), 3);
        assert_eq!(three[1].role, ButtonRole::Secondary);
        assert_eq!(three[2].role, ButtonRole::Cancel);
        assert_eq!(three[2].label.as_ref(), t!("dialog.button.cancel"));
    }

    #[test]
    fn answers_map_to_java_codes() {
        let three = question_buttons("A", "B");
        assert_eq!(answer_for(&three, Some(0)), Answer::Primary);
        assert_eq!(answer_for(&three, Some(1)), Answer::Secondary);
        assert_eq!(answer_for(&three, Some(2)), Answer::Closed);
        assert_eq!(answer_for(&three, None), Answer::Closed);
        assert_eq!(answer_for(&three, Some(9)), Answer::Closed);
        let two = question_buttons("A", "Cancel");
        assert_eq!(answer_for(&two, Some(1)), Answer::Closed);
        assert_eq!(Answer::Secondary.code(), 2);
    }

    #[test]
    fn filtering_is_token_and_case_insensitive() {
        let items: Vec<SharedString> = vec![
            "47.1.0 (recommended)".into(),
            "47.0.3".into(),
            "46.0.1 (latest)".into(),
        ];
        assert_eq!(filter_items(&items, ""), vec![0, 1, 2]);
        assert_eq!(filter_items(&items, "47"), vec![0, 1]);
        assert_eq!(filter_items(&items, "RECOMMENDED 47"), vec![0]);
        assert!(filter_items(&items, "nope").is_empty());
    }
}
