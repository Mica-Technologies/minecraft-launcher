// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `SessionLogView`: the game log as a virtualized list, one row per line.
//!
//! It replaced a wrapping text area that re-laid out every paragraph on each appended batch.
//! Here only the visible rows are laid out (`gpui::list`), rows wrap to the view's width, and
//! there is no horizontal scrolling. Row selection stands in for free text selection: click,
//! shift-click (range), ctrl/cmd-click (toggle), or the keyboard (Up / Down, Page Up / Down,
//! Home / End, Shift to extend, Ctrl/Cmd+A for all); ctrl/cmd+C and the context menu copy the
//! selected rows. The current find match is highlighted in its row.

use gpui::{
    App, ClipboardItem, Context, FocusHandle, Focusable, HighlightStyle, InteractiveElement,
    IntoElement, KeyDownEvent, ListAlignment, ListOffset, ListState, MouseButton, MouseDownEvent,
    ParentElement, Render, SharedString, Styled, StyledText, Window, div, list,
    prelude::FluentBuilder as _, px,
};
use gpui_component::menu::{ContextMenuExt as _, PopupMenuItem};
use mmcl_i18n::t;
use mmcl_ui_kit::scale::u;
use mmcl_ui_kit::{ThemeExt as _, hsla};

use crate::lines::{LogLines, SelectMode};

/// Rows of context kept above a find match when scrolling to it.
pub const MATCH_CONTEXT_ROWS: usize = 3;
/// The log's preferred height: small, so it takes only what its parent leaves and never squeezes
/// the crash card above it.
pub const PREF_HEIGHT: f32 = 120.0;
/// Rows Page Up / Page Down move the selection.
pub const PAGE_ROWS: isize = 20;

/// The log view entity.
pub struct SessionLogView {
    lines: LogLines,
    list: ListState,
    focus: FocusHandle,
}

impl SessionLogView {
    /// An empty log view.
    pub fn new(cx: &mut Context<Self>) -> Self {
        SessionLogView {
            lines: LogLines::new(),
            list: ListState::new(0, ListAlignment::Top, px(400.0)),
            focus: cx.focus_handle(),
        }
    }

    /// The lines (read-only).
    pub fn lines(&self) -> &LogLines {
        &self.lines
    }

    /// Number of lines.
    pub fn line_count(&self) -> usize {
        self.lines.len()
    }

    /// Replaces the text (clears the match and the selection).
    pub fn set_text(&mut self, text: &str, cx: &mut Context<Self>) {
        self.lines.set_text(text);
        self.list.reset(self.lines.len());
        cx.notify();
    }

    /// Appends a batch; trims the front past the cap's slack. Following, it scrolls to the end;
    /// otherwise, when lines left the front, it keeps the reader on the same line. Returns how
    /// many lines were dropped.
    pub fn append(
        &mut self,
        added: &[String],
        max_lines: i32,
        follow: bool,
        cx: &mut Context<Self>,
    ) -> usize {
        if added.is_empty() {
            return 0;
        }
        let top = self.list.logical_scroll_top();
        let before = self.lines.len();
        let dropped = self.lines.append(added.iter().cloned(), max_lines);
        self.list.splice(before..before, added.len());
        if dropped > 0 {
            self.list.splice(0..dropped.min(self.list.item_count()), 0);
        }
        if follow {
            self.list.scroll_to_end();
        } else if dropped > 0 {
            self.list.scroll_to(ListOffset {
                item_ix: top.item_ix.saturating_sub(dropped),
                offset_in_item: if top.item_ix >= dropped {
                    top.offset_in_item
                } else {
                    px(0.0)
                },
            });
        }
        cx.notify();
        dropped
    }

    /// Scrolls to the newest line.
    pub fn scroll_to_end(&mut self, cx: &mut Context<Self>) {
        if !self.lines.is_empty() {
            self.list.scroll_to_end();
            cx.notify();
        }
    }

    /// Scrolls to the first line.
    pub fn scroll_to_start(&mut self, cx: &mut Context<Self>) {
        self.list.scroll_to(ListOffset::default());
        cx.notify();
    }

    /// The whole shown text, each line followed by `'\n'`.
    pub fn text(&self) -> String {
        self.lines.text()
    }

    /// Finds the next / previous match, highlights it and scrolls to it with a few rows of
    /// context above. Returns `(ordinal, total)` or `None`.
    pub fn find(
        &mut self,
        needle: &str,
        forward: bool,
        cx: &mut Context<Self>,
    ) -> Option<(usize, usize)> {
        let found = self.lines.find(needle, forward);
        if let Some(m) = self.lines.current_match() {
            self.list.scroll_to(ListOffset {
                item_ix: m.line.saturating_sub(MATCH_CONTEXT_ROWS),
                offset_in_item: px(0.0),
            });
        }
        cx.notify();
        found
    }

    /// Copies the selected rows to the clipboard.
    pub fn copy_selection(&mut self, cx: &mut Context<Self>) {
        if let Some(text) = self.lines.selected_text() {
            cx.write_to_clipboard(ClipboardItem::new_string(text));
        }
    }

    fn click_row(
        &mut self,
        ix: usize,
        event: &MouseDownEvent,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let mode = if event.modifiers.shift {
            SelectMode::Range
        } else if event.modifiers.secondary(cx) {
            SelectMode::Toggle
        } else {
            SelectMode::Single
        };
        self.lines.select(ix, mode);
        window.focus(&self.focus, cx);
        cx.notify();
    }

    fn render_row(&self, ix: usize, entity: &gpui::Entity<Self>, cx: &App) -> gpui::AnyElement {
        let t = cx.tokens();
        let Some(line) = self.lines.line(ix).cloned() else {
            return div().into_any_element();
        };
        let selected = self.lines.is_selected(ix);
        let fg = if selected {
            hsla(t.md_on_secondary_container)
        } else {
            hsla(t.md_on_surface)
        };
        let current = self
            .lines
            .current_match()
            .filter(|m| m.line == ix && m.end <= line.len() && line.is_char_boundary(m.start));
        let text: gpui::AnyElement = match current {
            Some(m) => StyledText::new(line)
                .with_highlights([(
                    m.start..m.end,
                    HighlightStyle {
                        background_color: Some(hsla(t.md_active_indicator)),
                        color: Some(hsla(t.md_on_active_indicator)),
                        ..Default::default()
                    },
                )])
                .into_any_element(),
            None if line.is_empty() => SharedString::from(" ").into_any_element(),
            None => line.into_any_element(),
        };
        let entity = entity.clone();
        div()
            .id(ix)
            .w_full()
            .px(u(10.0))
            .pt(u(1.0))
            .text_color(fg)
            .when(selected, |el| {
                el.bg(hsla(t.md_secondary_container)).rounded(u(4.0))
            })
            .on_mouse_down(MouseButton::Left, move |event, window, cx| {
                entity.update(cx, |view, cx| view.click_row(ix, event, window, cx));
            })
            .child(text)
            .into_any_element()
    }

    /// Selects every line (Ctrl/Cmd+A).
    pub fn select_all(&mut self, cx: &mut Context<Self>) {
        self.lines.select_all();
        cx.notify();
    }

    /// The keys of a JavaFX multiple-selection `ListView`: Ctrl/Cmd+C copies, Ctrl/Cmd+A selects
    /// all, Up / Down / Page Up / Page Down / Home / End move the selection (Shift extends it).
    fn on_key(&mut self, event: &KeyDownEvent, _window: &mut Window, cx: &mut Context<Self>) {
        let ks = &event.keystroke;
        let m = &ks.modifiers;
        let command = m.secondary(cx) && !m.shift && !m.alt;
        let plain = !m.control && !m.platform && !m.alt && !m.function;
        let moved = match ks.key.as_str() {
            "c" if command => {
                self.copy_selection(cx);
                cx.stop_propagation();
                return;
            }
            "a" if command => {
                self.select_all(cx);
                cx.stop_propagation();
                return;
            }
            "up" if plain => self.lines.move_lead(-1, m.shift),
            "down" if plain => self.lines.move_lead(1, m.shift),
            "pageup" if plain => self.lines.move_lead(-PAGE_ROWS, m.shift),
            "pagedown" if plain => self.lines.move_lead(PAGE_ROWS, m.shift),
            "home" if plain => self.lines.move_lead_to_edge(false, m.shift),
            "end" if plain => self.lines.move_lead_to_edge(true, m.shift),
            _ => return,
        };
        if let Some(ix) = moved {
            self.list.scroll_to_reveal_item(ix);
        }
        cx.notify();
        cx.stop_propagation();
    }
}

impl Focusable for SessionLogView {
    fn focus_handle(&self, _cx: &App) -> FocusHandle {
        self.focus.clone()
    }
}

impl Render for SessionLogView {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.tokens();
        let entity = cx.entity();
        let weak = entity.downgrade();
        let rows = list(self.list.clone(), move |ix, _window, cx| {
            match weak.upgrade() {
                Some(e) => {
                    let view = e.read(cx);
                    view.render_row(ix, &e, cx)
                }
                None => div().into_any_element(),
            }
        })
        .size_full();
        let copy_target = entity.clone();
        div()
            .id("session-log")
            .key_context("SessionLog")
            .track_focus(&self.focus)
            .on_key_down(cx.listener(Self::on_key))
            .flex_1()
            .min_h(u(PREF_HEIGHT))
            .w_full()
            .overflow_hidden()
            .rounded(u(16.0))
            .bg(hsla(t.md_surface_container_lowest))
            .pt(u(10.0))
            .pb(u(10.0))
            .pl(u(8.0))
            .pr(u(4.0))
            .font_family(mmcl_ui_kit::fonts::MONO_FAMILY)
            .text_size(u(12.0))
            .child(rows)
            .context_menu(move |menu, _window, _cx| {
                let target = copy_target.clone();
                menu.item(
                    PopupMenuItem::new(t!("console.copyBtn.label").into_owned()).on_click(
                        move |_, _window, cx| {
                            target.update(cx, |view, cx| view.copy_selection(cx));
                        },
                    ),
                )
            })
    }
}
