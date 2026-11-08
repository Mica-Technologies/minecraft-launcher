// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The mod-conflict dialog before a launch (Java `LauncherCore.promptForConflicts`): the known
//! bad combinations [`Engine::mod_conflicts`] found in the pack's `mods/` folder, with Disable,
//! Launch anyway and Cancel.
//!
//! Java offered to disable only the first jar of the first conflict. Here each conflict lists
//! both jars and the user picks which one to turn off (the first by default); Disable renames
//! every picked jar to `.jar.disabled` ([`Engine::disable_mod_jar`]) and the launch goes on.
//! A rename that fails (the file is locked) warns and stops the launch, as Java did.

use std::cell::RefCell;
use std::rc::Rc;

use futures::channel::oneshot;
use gpui::{
    AnyWindowHandle, AppContext as _, AsyncApp, Context, FontWeight, InteractiveElement as _,
    IntoElement, ParentElement, Render, StatefulInteractiveElement as _, Styled, Window, div,
};
use gpui_component::WindowExt as _;
use mmcl_engine::{Engine, ModConflictView, PackId};
use mmcl_i18n::{t, tf};
use mmcl_ui::Question;
use mmcl_ui_kit::focus::nav_scope;
use mmcl_ui_kit::scale::{scaled_px, u};
use mmcl_ui_kit::{Answer, ThemeExt as _, hsla};

use super::widgets::{Pill, Tone, body, card, hint, title};

/// The dialog width (px at 100 %).
pub const WIDTH: f32 = 600.0;

/// Which jar of a conflict to turn off.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Side {
    First,
    Second,
}

/// The user's answer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConflictOutcome {
    /// Disable these jars (in order, no duplicates), then launch.
    Disable(Vec<String>),
    /// Launch with every mod enabled.
    LaunchAnyway,
    /// Do not launch.
    Cancel,
}

/// The dialog's pure state.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConflictChoice {
    conflicts: Vec<ModConflictView>,
    picks: Vec<Side>,
}

impl ConflictChoice {
    /// Every conflict, the first jar of each picked.
    pub fn new(conflicts: Vec<ModConflictView>) -> Self {
        let picks = vec![Side::First; conflicts.len()];
        ConflictChoice { conflicts, picks }
    }

    /// The conflicts.
    pub fn conflicts(&self) -> &[ModConflictView] {
        &self.conflicts
    }

    /// Picks the jar to turn off for conflict `i`.
    pub fn pick(&mut self, i: usize, side: Side) {
        if let Some(p) = self.picks.get_mut(i) {
            *p = side;
        }
    }

    /// The side picked for conflict `i`.
    pub fn picked(&self, i: usize) -> Side {
        self.picks.get(i).copied().unwrap_or(Side::First)
    }

    fn jar(c: &ModConflictView, side: Side) -> &str {
        match side {
            Side::First => &c.first_jar,
            Side::Second => &c.second_jar,
        }
    }

    /// The jars Disable turns off: one per conflict, duplicates dropped, blank names skipped.
    pub fn jars_to_disable(&self) -> Vec<String> {
        let mut out: Vec<String> = Vec::new();
        for (c, side) in self.conflicts.iter().zip(&self.picks) {
            let jar = Self::jar(c, *side).trim();
            if !jar.is_empty() && !out.iter().any(|j| j == jar) {
                out.push(jar.to_string());
            }
        }
        out
    }

    /// For each jar to disable, the jar it conflicted with (for the log line).
    pub fn partner_of(&self, jar: &str) -> String {
        for (c, side) in self.conflicts.iter().zip(&self.picks) {
            if Self::jar(c, *side) == jar {
                let other = match side {
                    Side::First => Side::Second,
                    Side::Second => Side::First,
                };
                return Self::jar(c, other).to_string();
            }
        }
        String::new()
    }

    /// The Disable button's label: `Disable {jar}` for one jar, `Disable {n} mods` for more.
    pub fn disable_label(&self) -> String {
        let jars = self.jars_to_disable();
        match jars.as_slice() {
            [one] => tf!("launch.modConflict.dialog.button.disable", one.as_str()),
            many => tf!(
                "launch.modConflict.dialog.button.disableCount",
                many.len() as i64
            ),
        }
    }
}

type Reply = Rc<RefCell<Option<oneshot::Sender<ConflictOutcome>>>>;

fn reply(slot: &Reply, value: ConflictOutcome) {
    if let Some(tx) = slot.borrow_mut().take() {
        let _ = tx.send(value);
    }
}

/// The dialog's content view.
pub struct ConflictView {
    choice: ConflictChoice,
    slot: Reply,
}

impl ConflictView {
    fn answer(&self, outcome: ConflictOutcome, window: &mut Window, cx: &mut gpui::App) {
        reply(&self.slot, outcome);
        window.close_dialog(cx);
    }
}

impl Render for ConflictView {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let cards = self.choice.conflicts().iter().enumerate().map(|(i, c)| {
            let picked = self.choice.picked(i);
            let first = cx.entity().downgrade();
            let second = cx.entity().downgrade();
            card(cx)
                .child(title(c.title.clone(), cx))
                .child(body(c.description.clone(), cx))
                .child(
                    div()
                        .flex()
                        .flex_wrap()
                        .items_center()
                        .gap(u(6.0))
                        .child(hint(t!("launch.modConflict.dialog.pick").into_owned(), cx))
                        .child(
                            Pill::new(("conflict-first", i), c.first_jar.clone(), Tone::Outline)
                                .height(30.0)
                                .selected(picked == Side::First)
                                .on_click(move |_, cx| {
                                    let _ = first.update(cx, |this, cx| {
                                        this.choice.pick(i, Side::First);
                                        cx.notify();
                                    });
                                }),
                        )
                        .child(
                            Pill::new(("conflict-second", i), c.second_jar.clone(), Tone::Outline)
                                .height(30.0)
                                .selected(picked == Side::Second)
                                .on_click(move |_, cx| {
                                    let _ = second.update(cx, |this, cx| {
                                        this.choice.pick(i, Side::Second);
                                        cx.notify();
                                    });
                                }),
                        ),
                )
        });
        let cancel = cx.entity().downgrade();
        let anyway = cx.entity().downgrade();
        let disable = cx.entity().downgrade();
        nav_scope("mod-conflict")
            .flex()
            .flex_col()
            .gap(u(10.0))
            .child(
                div()
                    .text_size(u(15.0))
                    .font_weight(FontWeight::SEMIBOLD)
                    .text_color(hsla(t.md_on_surface))
                    .child(t!("launch.modConflict.dialog.header").into_owned()),
            )
            .child(
                div()
                    .id("conflict-list")
                    .max_h(u(360.0))
                    .overflow_y_scroll()
                    .flex()
                    .flex_col()
                    .gap(u(8.0))
                    .children(cards),
            )
            .child(
                div()
                    .pt(u(6.0))
                    .flex()
                    .flex_wrap()
                    .justify_end()
                    .gap(u(8.0))
                    .child(
                        Pill::new(
                            "conflict-cancel",
                            t!("dialog.button.cancel").into_owned(),
                            Tone::Text,
                        )
                        .on_click(move |window, cx| {
                            let _ = cancel.update(cx, |this, cx| {
                                this.answer(ConflictOutcome::Cancel, window, cx)
                            });
                        }),
                    )
                    .child(
                        Pill::new(
                            "conflict-anyway",
                            t!("launch.modConflict.dialog.button.launchAnyway").into_owned(),
                            Tone::Tonal,
                        )
                        .on_click(move |window, cx| {
                            let _ = anyway.update(cx, |this, cx| {
                                this.answer(ConflictOutcome::LaunchAnyway, window, cx)
                            });
                        }),
                    )
                    .child(
                        Pill::new(
                            "conflict-disable",
                            self.choice.disable_label(),
                            Tone::Primary,
                        )
                        .disabled(self.choice.jars_to_disable().is_empty())
                        .on_click(move |window, cx| {
                            let _ = disable.update(cx, |this, cx| {
                                let jars = this.choice.jars_to_disable();
                                this.answer(ConflictOutcome::Disable(jars), window, cx)
                            });
                        }),
                    ),
            )
    }
}

/// The body of the plain mod-conflict question: `• title\n  description`, blank lines between.
pub fn conflict_body(conflicts: &[ModConflictView]) -> String {
    conflicts
        .iter()
        .map(|c| format!("\u{2022} {}\n  {}", c.title, c.description))
        .collect::<Vec<_>>()
        .join("\n\n")
}

/// The plain-question fallback for a window without gpui-component's root (Java's dialog).
async fn ask_plain(
    cx: &mut AsyncApp,
    choice: &ConflictChoice,
    owner: Option<AnyWindowHandle>,
) -> ConflictOutcome {
    let body = conflict_body(choice.conflicts());
    let q = Question::new(
        t!("launch.modConflict.dialog.title"),
        t!("launch.modConflict.dialog.header"),
        body,
        choice.disable_label(),
        t!("launch.modConflict.dialog.button.launchAnyway"),
    );
    match mmcl_ui::dialogs::ask_multiline(cx, &q, owner).await {
        Answer::Primary => ConflictOutcome::Disable(choice.jars_to_disable()),
        Answer::Secondary => ConflictOutcome::LaunchAnyway,
        Answer::Closed => ConflictOutcome::Cancel,
    }
}

/// Shows the dialog for `conflicts` in `owner` (the main window when `None`).
pub async fn ask_conflicts(
    cx: &mut AsyncApp,
    conflicts: Vec<ModConflictView>,
    owner: Option<AnyWindowHandle>,
) -> ConflictOutcome {
    let choice = ConflictChoice::new(conflicts);
    let (tx, rx) = oneshot::channel::<ConflictOutcome>();
    let slot: Reply = Rc::new(RefCell::new(Some(tx)));
    let for_view = choice.clone();
    let presented = cx.update(|cx| {
        let Some(host) = super::host_window(cx, owner) else {
            return false;
        };
        host.update(cx, |_, window, cx| {
            if !super::has_root(window) {
                return false;
            }
            let view = cx.new(|_| ConflictView {
                choice: for_view,
                slot: slot.clone(),
            });
            let on_close = slot.clone();
            window.open_dialog(cx, move |dialog, _, cx| {
                let on_close = on_close.clone();
                dialog
                    .title(t!("launch.modConflict.dialog.title").into_owned())
                    .bg(cx.launcher_theme().solid_bg(true))
                    .w(scaled_px(cx, WIDTH))
                    .keyboard(true)
                    .overlay_closable(false)
                    .child(view.clone())
                    .on_close(move |_, _, _| reply(&on_close, ConflictOutcome::Cancel))
            });
            true
        })
        .unwrap_or(false)
    });
    if !presented {
        return ask_plain(cx, &choice, owner).await;
    }
    rx.await.unwrap_or(ConflictOutcome::Cancel)
}

/// Checks the pack for known mod conflicts and asks; `true` to go on with the launch (none
/// found, mods disabled, or Launch anyway), `false` to stop.
pub async fn confirm_mod_conflicts(
    engine: &Engine,
    id: &PackId,
    owner: Option<AnyWindowHandle>,
    cx: &mut AsyncApp,
) -> bool {
    let conflicts = match engine.mod_conflicts(id) {
        Ok(c) if !c.is_empty() => c,
        _ => return true,
    };
    let choice = ConflictChoice::new(conflicts.clone());
    match ask_conflicts(cx, conflicts, owner).await {
        ConflictOutcome::Disable(jars) => {
            for jar in &jars {
                if engine.disable_mod_jar(id, jar).is_err() {
                    mmcl_ui_kit::notify::warn(
                        &tf!("notification.launch.disableModFailed.title", jar.as_str()),
                        &t!("notification.launch.disableModFailed.body"),
                    );
                    return false;
                }
                tracing::info!(
                    "{}",
                    tf!(
                        "log.launcherCore.preLaunchDisabledMod",
                        jar.as_str(),
                        choice.partner_of(jar)
                    )
                );
            }
            true
        }
        ConflictOutcome::LaunchAnyway => {
            if let Some(c) = choice.conflicts().first() {
                tracing::info!(
                    "{}",
                    tf!(
                        "log.launcherCore.preLaunchLaunchAnyway",
                        c.first_jar.as_str(),
                        c.second_jar.as_str()
                    )
                );
            }
            true
        }
        ConflictOutcome::Cancel => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn c(a: &str, b: &str) -> ModConflictView {
        ModConflictView {
            title: format!("{a} vs {b}"),
            description: "They crash".into(),
            first_jar: a.into(),
            second_jar: b.into(),
        }
    }

    #[test]
    fn the_first_jar_is_picked_by_default() {
        let choice = ConflictChoice::new(vec![c("optifine.jar", "sodium.jar")]);
        assert_eq!(choice.picked(0), Side::First);
        assert_eq!(choice.jars_to_disable(), vec!["optifine.jar"]);
        assert_eq!(choice.disable_label(), "Disable optifine.jar");
        assert_eq!(choice.partner_of("optifine.jar"), "sodium.jar");
    }

    #[test]
    fn picks_change_what_is_disabled() {
        let mut choice = ConflictChoice::new(vec![c("a.jar", "b.jar"), c("c.jar", "d.jar")]);
        choice.pick(1, Side::Second);
        assert_eq!(choice.jars_to_disable(), vec!["a.jar", "d.jar"]);
        assert_eq!(choice.partner_of("d.jar"), "c.jar");
        assert!(
            choice.disable_label().contains('2'),
            "{}",
            choice.disable_label()
        );
        choice.pick(9, Side::Second);
        assert_eq!(choice.picked(9), Side::First);
    }

    #[test]
    fn a_shared_jar_is_disabled_once() {
        let mut choice = ConflictChoice::new(vec![c("x.jar", "a.jar"), c("x.jar", "b.jar")]);
        assert_eq!(choice.jars_to_disable(), vec!["x.jar"]);
        assert_eq!(choice.disable_label(), "Disable x.jar");
        choice.pick(0, Side::Second);
        assert_eq!(choice.jars_to_disable(), vec!["a.jar", "x.jar"]);
    }

    #[test]
    fn conflict_lines_are_bulleted_and_separated() {
        let mut a = c("a.jar", "b.jar");
        a.title = "A vs B".into();
        let mut b = c("c.jar", "d.jar");
        b.title = "C vs D".into();
        b.description = "Broken".into();
        assert_eq!(
            conflict_body(&[a, b]),
            "\u{2022} A vs B\n  They crash\n\n\u{2022} C vs D\n  Broken"
        );
    }

    #[test]
    fn blank_jar_names_are_skipped() {
        let choice = ConflictChoice::new(vec![c(" ", "b.jar")]);
        assert!(choice.jars_to_disable().is_empty());
    }
}
