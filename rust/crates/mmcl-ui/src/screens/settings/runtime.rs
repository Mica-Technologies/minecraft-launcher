// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Runtime Management (`MCLauncherRuntimeGui`, gui-shell spec section 7): the installed Java
//! runtimes with their sizes, Refresh, Delete, Delete All and Return (to Settings -> System).
//!
//! Listing sizes every runtime tree, so it always runs on the engine's runtime, including the
//! first load (guarded bug 22). Delete resolves the selection against the one snapshot the list
//! shows (guarded bug 21). Toolbar navigation is off so an install, verify or delete is never
//! abandoned; closing the window closes the app.

use std::sync::Arc;

use gpui::{
    App, ClickEvent, Context, InteractiveElement as _, IntoElement, ParentElement, Pixels, Render,
    SharedString, Size, StatefulInteractiveElement as _, Styled, Subscription, Task, Window, div,
    prelude::FluentBuilder as _, px, size,
};
use gpui_component::Disableable as _;
use gpui_component::button::{Button, ButtonVariants as _};
use gpui_component::scroll::ScrollableElement as _;
use mmcl_engine::{EngineEvent, RuntimeView};
use mmcl_i18n::{t, tf};
use mmcl_theme::shape;
use mmcl_theme::typography::TypeRole;
use mmcl_ui_kit::theme::{ThemeExt as _, TypeStyleExt as _, hsla};
use mmcl_ui_kit::{HelpTopic, Icon, Screen, ScreenId, ScreenRequest, empty_state_badge};

use super::logic::{
    TilePosition, resolve_selection, runtime_count_status, runtime_detail, runtime_title,
    tile_position, tile_radii,
};
use super::widgets::{bottom_bar, hint};
use crate::dialogs::{self, Answer, Question};
use crate::scale::u;
use crate::services::{self, UiEvent};
use crate::widgets::{AnnouncementBanner, NavBar, OfflineChip};

/// The Settings category Return goes back to (System, where "Manage runtimes" lives).
pub const RETURN_CATEGORY: usize = 6;

/// The Runtime Management screen.
pub struct RuntimeScreen {
    /// The list as last loaded (one snapshot; replaced whole on refresh).
    runtimes: Arc<Vec<RuntimeView>>,
    loaded: bool,
    selected: Option<usize>,
    status: SharedString,
    busy: bool,
    announcement: Option<SharedString>,
    disposed: bool,
    _events: Option<Subscription>,
    _load: Option<Task<()>>,
}

impl RuntimeScreen {
    /// A new screen (the list loads in `after_show`).
    pub fn new(_window: &mut Window, cx: &mut Context<Self>) -> Self {
        let events = services::events(cx).map(|ev| {
            cx.subscribe(&ev, |this: &mut Self, _, event: &UiEvent, cx| match event {
                UiEvent::Engine(EngineEvent::AnnouncementsChanged) | UiEvent::Lagged => {
                    this.announcement = announcement(cx);
                    cx.notify();
                }
                _ => {}
            })
        });
        RuntimeScreen {
            runtimes: Arc::new(Vec::new()),
            loaded: false,
            selected: None,
            status: t!("runtime.status.selectAPrompt").into_owned().into(),
            busy: false,
            announcement: announcement(cx),
            disposed: false,
            _events: events,
            _load: None,
        }
    }

    /// `refreshRuntimeList`: lists off the UI thread, then shows the new snapshot.
    pub fn refresh(&mut self, cx: &mut Context<Self>) {
        let Some(task) = services::run(cx, |e| async move { e.runtimes().await }) else {
            self.loaded = true;
            self.status = runtime_count_status(0).into();
            cx.notify();
            return;
        };
        self._load = Some(cx.spawn(async move |this, cx| {
            let list = task.await.unwrap_or_default();
            let _ = this.update(cx, |s, cx| {
                if s.disposed {
                    return;
                }
                s.apply_list(list);
                cx.notify();
            });
        }));
    }

    fn apply_list(&mut self, list: Vec<RuntimeView>) {
        self.status = runtime_count_status(list.len()).into();
        self.selected = self.selected.filter(|i| *i < list.len());
        self.runtimes = Arc::new(list);
        self.loaded = true;
    }

    fn set_status(&mut self, status: impl Into<SharedString>, cx: &mut Context<Self>) {
        self.status = status.into();
        cx.notify();
    }

    /// Delete: one snapshot, one question, then the engine.
    fn delete_selected(&mut self, cx: &mut Context<Self>) {
        let snapshot = self.runtimes.clone();
        let Some(runtime) = resolve_selection(&snapshot, self.selected).cloned() else {
            self.set_status(t!("runtime.status.noneSelected").into_owned(), cx);
            return;
        };
        self.busy = true;
        cx.notify();
        cx.spawn(async move |this, cx| {
            let q = Question::new(
                t!("dialog.runtime.delete.title"),
                tf!("dialog.runtime.delete.header", runtime.component.as_str()),
                t!("dialog.runtime.delete.body"),
                t!("dialog.runtime.delete.button.delete"),
                t!("dialog.button.cancel"),
            );
            if dialogs::ask(cx, &q, None).await != Answer::Primary {
                let _ = this.update(cx, |s, cx| {
                    s.busy = false;
                    cx.notify();
                });
                return;
            }
            let component = runtime.component.clone();
            let task = cx.update(|cx| {
                services::run(cx, move |e| async move {
                    let res = e.remove_runtime(&component).await;
                    let in_use = e
                        .runtimes()
                        .await
                        .iter()
                        .any(|r| r.component == component && r.in_use);
                    (res, in_use)
                })
            });
            let status = match task {
                Some(task) => match task.await {
                    Ok((Ok(()), _)) => tf!("runtime.status.deleted", runtime.component.as_str()),
                    Ok((Err(e), in_use)) => {
                        tracing::warn!(
                            "{}: {}",
                            tf!("log.runtime.deleteFailed", runtime.component.as_str()),
                            e.message()
                        );
                        if in_use || runtime.in_use {
                            tf!("runtime.error.inUse", runtime.component.as_str())
                        } else {
                            t!("runtime.status.deleteFailed").into_owned()
                        }
                    }
                    Err(_) => t!("runtime.status.deleteFailed").into_owned(),
                },
                None => t!("runtime.status.deleteFailed").into_owned(),
            };
            let _ = this.update(cx, |s, cx| {
                if s.disposed {
                    return;
                }
                s.busy = false;
                s.selected = None;
                s.refresh(cx);
                s.set_status(status, cx);
            });
        })
        .detach();
    }

    /// Delete All: snapshot, question, clear each (failures logged), refresh.
    fn delete_all(&mut self, cx: &mut Context<Self>) {
        if self.runtimes.is_empty() {
            self.set_status(t!("runtime.status.noneInstalled").into_owned(), cx);
            return;
        }
        self.busy = true;
        cx.notify();
        cx.spawn(async move |this, cx| {
            let q = Question::new(
                t!("dialog.runtime.deleteAll.title"),
                t!("dialog.runtime.deleteAll.header"),
                t!("dialog.runtime.deleteAll.body"),
                t!("dialog.runtime.deleteAll.button.deleteAll"),
                t!("dialog.button.cancel"),
            );
            let go = dialogs::ask(cx, &q, None).await == Answer::Primary;
            if go {
                let task = cx.update(|cx| {
                    services::run(cx, |e| async move { e.remove_all_runtimes().await })
                });
                if let Some(task) = task {
                    for component in task.await.unwrap_or_default() {
                        tracing::warn!("{}", tf!("log.runtime.deleteFailed", component.as_str()));
                    }
                }
            }
            let _ = this.update(cx, |s, cx| {
                if s.disposed {
                    return;
                }
                s.busy = false;
                if go {
                    s.selected = None;
                    s.refresh(cx);
                    s.set_status(t!("runtime.status.allDeleted").into_owned(), cx);
                } else {
                    cx.notify();
                }
            });
        })
        .detach();
    }

    fn render_row(
        &self,
        i: usize,
        r: &RuntimeView,
        n: usize,
        cx: &mut Context<Self>,
    ) -> impl IntoElement {
        let t = cx.tokens();
        let selected = self.selected == Some(i);
        let pos = tile_position(i, n);
        let (title, detail) = (runtime_title(r), runtime_detail(r));
        let (top, bottom) = tile_radii(pos, shape::LARGE, shape::EXTRA_SMALL);
        let hover = hsla(t.md_state_hover_on_surface);
        div()
            .id(("runtime-row", i))
            .w_full()
            .flex()
            .items_center()
            .gap(u(12.0))
            .px(u(16.0))
            .py(u(10.0))
            .rounded_t(u(top))
            .rounded_b(u(bottom))
            .bg(hsla(if selected {
                t.md_secondary_container
            } else {
                t.md_surface_container_low
            }))
            .when(!selected, |d| d.hover(move |s| s.bg(hover)))
            .when(
                pos != TilePosition::Last && pos != TilePosition::Only,
                |d| d.mb(u(2.0)),
            )
            .cursor_pointer()
            .on_click(cx.listener(move |s, _: &ClickEvent, _, cx| {
                s.selected = Some(i);
                cx.notify();
            }))
            .child(
                div()
                    .flex_none()
                    .size(u(36.0))
                    .rounded_full()
                    .bg(hsla(t.md_icon_green))
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(Icon::Runtime.svg(20.0).text_color(hsla(t.md_on_icon_green))),
            )
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .type_role(TypeRole::TitleSmall)
                            .text_color(hsla(t.color_text))
                            .truncate()
                            .child(title),
                    )
                    .child(hint(detail, cx)),
            )
    }
}

fn announcement(cx: &App) -> Option<SharedString> {
    services::try_engine(cx)
        .map(|e| e.announcements().settings)
        .filter(|s| !s.trim().is_empty())
        .map(Into::into)
}

impl Screen for RuntimeScreen {
    fn id(&self) -> ScreenId {
        ScreenId::RUNTIME
    }

    fn title(&self, _cx: &App) -> SharedString {
        t!("runtime.navbar.title").into_owned().into()
    }

    fn help_topic(&self) -> HelpTopic {
        HelpTopic::RuntimeManagement
    }

    fn allows_toolbar_navigation(&self) -> bool {
        false
    }

    fn installs_global_shortcuts(&self) -> bool {
        true
    }

    fn min_size(&self) -> Size<Pixels> {
        size(px(750.0), px(475.0))
    }

    fn has_own_help_button(&self) -> bool {
        false
    }

    fn after_show(&mut self, _window: &mut Window, cx: &mut Context<Self>) {
        self.refresh(cx);
    }

    fn cleanup(&mut self, _cx: &mut Context<Self>) {
        self.disposed = true;
        self._load = None;
        self._events = None;
    }
}

impl Render for RuntimeScreen {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let busy = self.busy;
        let list = self.runtimes.clone();
        let n = list.len();
        let body = if self.loaded && n == 0 {
            div()
                .flex_1()
                .flex()
                .flex_col()
                .items_center()
                .justify_center()
                .gap(u(12.0))
                .p(u(24.0))
                .child(empty_state_badge(Icon::EmptyBox, cx))
                .child(
                    div()
                        .type_role(TypeRole::HeadingH3)
                        .text_color(hsla(t.color_text))
                        .child(t!("runtime.empty.heading").into_owned()),
                )
                .child(
                    div()
                        .max_w(u(420.0))
                        .text_center()
                        .type_role(TypeRole::BodyMedium)
                        .text_color(hsla(t.color_text_muted))
                        .child(t!("runtime.empty.body").into_owned()),
                )
                .into_any_element()
        } else {
            let rows: Vec<_> = list
                .iter()
                .enumerate()
                .map(|(i, r)| self.render_row(i, r, n, cx).into_any_element())
                .collect();
            div()
                .id("runtime-list")
                .flex_1()
                .min_h_0()
                .overflow_y_scrollbar()
                .child(div().flex().flex_col().w_full().children(rows))
                .into_any_element()
        };
        let announcement = self.announcement.clone();
        let this = cx.entity().downgrade();
        div()
            .size_full()
            .flex()
            .flex_col()
            .child(
                NavBar::new(t!("runtime.navbar.title").into_owned())
                    .action(OfflineChip::new("runtime-offline")),
            )
            .child(
                AnnouncementBanner::new("runtime-announcement", announcement).on_dismiss(
                    move |_, cx| {
                        let _ = this.update(cx, |s, cx| {
                            if let (Some(e), Some(text)) =
                                (services::try_engine(cx), s.announcement.take())
                            {
                                e.dismiss_announcement(&text);
                            }
                            cx.notify();
                        });
                    },
                ),
            )
            .child(
                div()
                    .flex_1()
                    .min_h_0()
                    .flex()
                    .flex_col()
                    .gap(u(10.0))
                    .px(u(24.0))
                    .py(u(16.0))
                    .child(
                        div()
                            .type_role(TypeRole::HeadingH3)
                            .text_color(hsla(t.color_text))
                            .child(t!("runtime.section.installed").into_owned()),
                    )
                    .child(body)
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap(u(8.0))
                            .child(
                                div()
                                    .flex_1()
                                    .min_w_0()
                                    .type_role(TypeRole::BodyMedium)
                                    .text_color(hsla(t.color_text_muted))
                                    .child(self.status.clone()),
                            )
                            .child(
                                Button::new("runtime-refresh")
                                    .label(t!("runtime.refreshBtn").into_owned())
                                    .icon(Icon::Refresh)
                                    .outline()
                                    .disabled(busy)
                                    .on_click(
                                        cx.listener(|s, _: &ClickEvent, _, cx| s.refresh(cx)),
                                    ),
                            )
                            .child(
                                Button::new("runtime-delete")
                                    .label(t!("runtime.deleteBtn").into_owned())
                                    .icon(Icon::Delete)
                                    .danger()
                                    .disabled(busy || self.selected.is_none())
                                    .on_click(cx.listener(|s, _: &ClickEvent, _, cx| {
                                        s.delete_selected(cx)
                                    })),
                            )
                            .child(
                                Button::new("runtime-delete-all")
                                    .label(t!("runtime.deleteAllBtn").into_owned())
                                    .text()
                                    .disabled(busy || n == 0)
                                    .on_click(
                                        cx.listener(|s, _: &ClickEvent, _, cx| s.delete_all(cx)),
                                    ),
                            ),
                    ),
            )
            .child(bottom_bar(
                hint(t!("runtime.infoLabel").into_owned(), cx).into_any_element(),
                vec![
                    Button::new("runtime-return")
                        .label(t!("runtime.returnBtn").into_owned())
                        .primary()
                        .disabled(busy)
                        .on_click(|_: &ClickEvent, _, cx| {
                            crate::shell::go_to(
                                cx,
                                ScreenRequest::with_payload(ScreenId::SETTINGS, RETURN_CATEGORY),
                            );
                        })
                        .into_any_element(),
                ],
                cx,
            ))
    }
}
