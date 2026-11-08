// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The scrolling game-log pane of the Logs view (Java `TuiApp.LogBox` + `refreshLogsBox`).
//!
//! Lines arrive from the session's lossless log subscription into a 4000-line ring. The shown
//! text (the last 500 lines) is reloaded only while the view follows the bottom: scrolled up, it
//! freezes so the user can read, and scrolling back to the bottom re-engages following on the
//! next refresh. A monotonically increasing "lines appended" counter decides whether anything is
//! new, so the view keeps updating once the ring is full (the Java view stopped there).

use std::collections::VecDeque;

/// Lines kept from the subscription.
pub const RING_CAPACITY: usize = 4000;
/// Lines shown in the pane.
pub const SHOWN_LINES: usize = 500;

/// A navigation key of the log pane.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Scroll {
    Up,
    Down,
    Left,
    Right,
    PageUp,
    PageDown,
    Home,
    End,
}

/// The log pane's state.
#[derive(Clone, Debug)]
pub struct LogView {
    ring: VecDeque<String>,
    appended: u64,
    shown: Vec<String>,
    /// `appended` when `shown` was last loaded; `None` = never loaded.
    shown_at: Option<u64>,
    /// First shown line at the top of the pane.
    offset: usize,
    /// Columns scrolled to the right.
    column: usize,
    /// Rows of the pane at the last render.
    height: usize,
    exited: bool,
}

impl Default for LogView {
    fn default() -> Self {
        LogView::new()
    }
}

impl LogView {
    /// An empty pane.
    pub fn new() -> LogView {
        LogView {
            ring: VecDeque::new(),
            appended: 0,
            shown: Vec::new(),
            shown_at: None,
            offset: 0,
            column: 0,
            height: 1,
            exited: false,
        }
    }

    /// Appends the subscription's snapshot text (one line per `\n`).
    pub fn push_text(&mut self, text: &str) {
        if text.is_empty() {
            return;
        }
        let body = text.strip_suffix('\n').unwrap_or(text);
        self.push_lines(
            body.split('\n')
                .map(|l| l.trim_end_matches('\r').to_string()),
        );
    }

    /// Appends lines to the ring, dropping the oldest past [`RING_CAPACITY`].
    pub fn push_lines(&mut self, lines: impl IntoIterator<Item = String>) {
        for l in lines {
            if self.ring.len() == RING_CAPACITY {
                self.ring.pop_front();
            }
            self.ring.push_back(l);
            self.appended += 1;
        }
    }

    /// Lines appended since the start (never decreases).
    pub fn appended(&self) -> u64 {
        self.appended
    }

    /// The lines on screen.
    pub fn shown(&self) -> &[String] {
        &self.shown
    }

    /// Top line and scrolled columns, for `Paragraph::scroll`.
    pub fn scroll_offset(&self) -> (usize, usize) {
        (self.offset, self.column)
    }

    /// Records the pane height of this render and keeps the offset in range.
    pub fn set_height(&mut self, rows: usize) {
        let was_following = self.is_following();
        self.height = rows.max(1);
        if was_following {
            self.offset = self.max_offset();
        } else {
            self.offset = self.offset.min(self.max_offset());
        }
    }

    fn max_offset(&self) -> usize {
        self.shown.len().saturating_sub(self.height)
    }

    /// Whether the last line is on screen (the view follows new output).
    pub fn is_following(&self) -> bool {
        self.offset >= self.max_offset()
    }

    /// Reloads the shown lines when something is new and the view follows the bottom.
    /// Returns `true` when the shown text changed.
    pub fn refresh(&mut self) -> bool {
        if self.exited {
            return false;
        }
        if self.shown_at == Some(self.appended) {
            return false;
        }
        if self.shown_at.is_some() && !self.is_following() {
            // Scrolled up: freeze, and do not record the count, so returning to the bottom
            // reloads the latest tail on the next refresh.
            return false;
        }
        let from = self.ring.len().saturating_sub(SHOWN_LINES);
        self.shown = self.ring.iter().skip(from).cloned().collect();
        self.shown_at = Some(self.appended);
        self.offset = self.max_offset();
        true
    }

    /// The game exited: shows `notice` once as the last line and stops reloading.
    pub fn mark_exited(&mut self, notice: &str) -> bool {
        if self.exited {
            return false;
        }
        self.refresh_force();
        self.exited = true;
        self.shown.push(notice.to_string());
        self.offset = self.max_offset();
        true
    }

    fn refresh_force(&mut self) {
        let from = self.ring.len().saturating_sub(SHOWN_LINES);
        self.shown = self.ring.iter().skip(from).cloned().collect();
        self.shown_at = Some(self.appended);
    }

    /// Whether [`LogView::mark_exited`] ran.
    pub fn is_exited(&self) -> bool {
        self.exited
    }

    /// Moves the view; at the edges it stays put.
    pub fn scroll(&mut self, s: Scroll) {
        let max = self.max_offset();
        let page = self.height.saturating_sub(1).max(1);
        match s {
            Scroll::Up => self.offset = self.offset.saturating_sub(1),
            Scroll::Down => self.offset = (self.offset + 1).min(max),
            Scroll::PageUp => self.offset = self.offset.saturating_sub(page),
            Scroll::PageDown => self.offset = (self.offset + page).min(max),
            Scroll::Home => self.offset = 0,
            Scroll::End => self.offset = max,
            Scroll::Left => self.column = self.column.saturating_sub(4),
            Scroll::Right => {
                let widest = self
                    .shown
                    .iter()
                    .map(|l| l.chars().count())
                    .max()
                    .unwrap_or(0);
                self.column = (self.column + 4).min(widest.saturating_sub(1));
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lines(n: usize, prefix: &str) -> Vec<String> {
        (0..n).map(|i| format!("{prefix}{i}")).collect()
    }

    #[test]
    fn follows_the_bottom() {
        let mut v = LogView::new();
        v.set_height(10);
        v.push_lines(lines(30, "a"));
        assert!(v.refresh());
        assert_eq!(v.shown().len(), 30);
        assert_eq!(v.scroll_offset().0, 20);
        assert!(v.is_following());
        v.push_lines(lines(5, "b"));
        assert!(v.refresh());
        assert_eq!(v.scroll_offset().0, 25);
        assert_eq!(v.shown().last().unwrap(), "b4");
    }

    #[test]
    fn nothing_new_does_not_fight_the_scroll() {
        let mut v = LogView::new();
        v.set_height(10);
        v.push_lines(lines(30, "a"));
        v.refresh();
        v.scroll(Scroll::Up);
        assert!(!v.refresh());
        assert_eq!(v.scroll_offset().0, 19);
    }

    #[test]
    fn freezes_when_scrolled_up_and_re_engages_at_the_bottom() {
        let mut v = LogView::new();
        v.set_height(10);
        v.push_lines(lines(30, "a"));
        v.refresh();
        v.scroll(Scroll::PageUp);
        assert!(!v.is_following());
        v.push_lines(lines(3, "b"));
        assert!(!v.refresh(), "frozen while scrolled up");
        assert_eq!(v.shown().last().unwrap(), "a29");
        v.scroll(Scroll::End);
        assert!(v.is_following());
        assert!(v.refresh(), "back at the bottom reloads");
        assert_eq!(v.shown().last().unwrap(), "b2");
        assert_eq!(v.scroll_offset().0, v.shown().len() - 10);
    }

    #[test]
    fn offset_never_passes_the_last_line() {
        let mut v = LogView::new();
        v.set_height(10);
        v.push_lines(lines(5, "a"));
        v.refresh();
        assert_eq!(v.scroll_offset().0, 0);
        for _ in 0..5 {
            v.scroll(Scroll::Down);
            v.scroll(Scroll::PageDown);
        }
        assert_eq!(v.scroll_offset().0, 0);
        v.scroll(Scroll::Up);
        v.scroll(Scroll::Home);
        assert_eq!(v.scroll_offset().0, 0);
        // An empty log never underflows either.
        let mut empty = LogView::new();
        empty.scroll(Scroll::End);
        empty.scroll(Scroll::Down);
        assert_eq!(empty.scroll_offset(), (0, 0));
    }

    #[test]
    fn shows_only_the_last_500_lines() {
        let mut v = LogView::new();
        v.push_lines(lines(800, "a"));
        v.refresh();
        assert_eq!(v.shown().len(), SHOWN_LINES);
        assert_eq!(v.shown()[0], "a300");
    }

    #[test]
    fn keeps_updating_once_the_ring_is_full() {
        let mut v = LogView::new();
        v.push_lines(lines(RING_CAPACITY, "a"));
        v.refresh();
        v.push_lines(vec!["new".to_string()]);
        assert!(v.refresh());
        assert_eq!(v.shown().last().unwrap(), "new");
        assert_eq!(v.appended(), RING_CAPACITY as u64 + 1);
    }

    #[test]
    fn exited_notice_is_shown_once() {
        let mut v = LogView::new();
        v.push_text("one\ntwo\n");
        v.refresh();
        assert!(v.mark_exited("(game exited)"));
        assert!(!v.mark_exited("(game exited)"));
        assert_eq!(v.shown(), ["one", "two", "(game exited)"]);
        v.push_lines(vec!["late".into()]);
        assert!(!v.refresh());
        assert!(v.is_exited());
    }

    #[test]
    fn snapshot_text_splits_into_lines() {
        let mut v = LogView::new();
        v.push_text("a\r\nb\nc");
        v.refresh();
        assert_eq!(v.shown(), ["a", "b", "c"]);
        v.push_text("");
        assert_eq!(v.appended(), 3);
    }

    #[test]
    fn horizontal_scroll_is_bounded() {
        let mut v = LogView::new();
        v.push_lines(vec!["0123456789".into()]);
        v.refresh();
        for _ in 0..10 {
            v.scroll(Scroll::Right);
        }
        assert_eq!(v.scroll_offset().1, 9);
        v.scroll(Scroll::Left);
        assert_eq!(v.scroll_offset().1, 5);
    }

    #[test]
    fn a_taller_pane_keeps_following() {
        let mut v = LogView::new();
        v.set_height(5);
        v.push_lines(lines(20, "a"));
        v.refresh();
        v.set_height(10);
        assert_eq!(v.scroll_offset().0, 10);
        assert!(v.is_following());
    }
}
