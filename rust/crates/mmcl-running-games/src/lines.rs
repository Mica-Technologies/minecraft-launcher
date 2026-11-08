// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The log view's pure side: splitting and joining lines, case-insensitive find with wrap, the
//! "n of m" match position, and [`LogLines`], the line buffer with its current match and row
//! selection. Nothing here touches GPUI, so all of it is unit-tested.
//!
//! Columns are **byte** offsets into a line and always fall on char boundaries. Matching is
//! case-insensitive per `char` (as Java's `regionMatches(ignoreCase = true)` is per UTF-16 unit),
//! so a match can differ in byte length from the needle; its end is found by walking the same
//! number of chars.

use std::collections::{BTreeSet, VecDeque};

use gpui::SharedString;

use crate::trim;

/// Splits on `'\n'`. A final `'\n'` ends the last line (no trailing empty line); a `'\r'` right
/// before a `'\n'` (or at the very end) is dropped. `None` and `""` give no lines.
pub fn split_lines(text: Option<&str>) -> Vec<String> {
    let Some(text) = text else {
        return Vec::new();
    };
    let mut out = Vec::new();
    let mut rest = text;
    while !rest.is_empty() {
        let (line, next) = match rest.find('\n') {
            Some(i) => (&rest[..i], Some(&rest[i + 1..])),
            None => (rest, None),
        };
        out.push(line.strip_suffix('\r').unwrap_or(line).to_string());
        match next {
            Some(n) => rest = n,
            None => break,
        }
    }
    out
}

/// Each line followed by `'\n'`; no lines give `""`.
pub fn join_lines<S: AsRef<str>>(lines: &[S]) -> String {
    let size = lines.iter().map(|l| l.as_ref().len() + 1).sum();
    let mut out = String::with_capacity(size);
    for line in lines {
        out.push_str(line.as_ref());
        out.push('\n');
    }
    out
}

fn chars_equal_ignore_case(a: char, b: char) -> bool {
    a == b || a.to_uppercase().eq(b.to_uppercase()) || a.to_lowercase().eq(b.to_lowercase())
}

/// The end (byte offset) of a case-insensitive match of `needle` starting at byte `at`, if any.
fn match_end_at(text: &str, at: usize, needle: &str) -> Option<usize> {
    let mut hay = text.get(at..)?.char_indices();
    let mut end = at;
    for n in needle.chars() {
        let (i, c) = hay.next()?;
        if !chars_equal_ignore_case(c, n) {
            return None;
        }
        end = at + i + c.len_utf8();
    }
    Some(end)
}

/// The first case-insensitive match at or after byte `from` (clamped to the line).
fn index_of_ignore_case(text: &str, needle: &str, from: isize) -> Option<usize> {
    let mut start = usize::try_from(from.max(0)).unwrap_or(0);
    if start > text.len() {
        return None;
    }
    while !text.is_char_boundary(start) {
        start += 1;
    }
    text[start..]
        .char_indices()
        .map(|(i, _)| start + i)
        .find(|&i| match_end_at(text, i, needle).is_some())
}

/// The last case-insensitive match starting at or before byte `from`.
fn last_index_of_ignore_case(text: &str, needle: &str, from: isize) -> Option<usize> {
    if from < 0 {
        return None;
    }
    let mut start = usize::try_from(from).unwrap_or(usize::MAX).min(text.len());
    while !text.is_char_boundary(start) {
        start -= 1;
    }
    let mut candidates: Vec<usize> = text[..start].char_indices().map(|(i, _)| i).collect();
    candidates.push(start);
    candidates
        .into_iter()
        .rev()
        .find(|&i| match_end_at(text, i, needle).is_some())
}

/// The next (`forward`) or previous case-insensitive match of `needle`, wrapping around.
/// Returns `(line, col)`.
///
/// `from_line` is taken modulo the line count (floor-mod). The search visits `n + 1` lines:
/// every line once, then the starting line again for the part skipped on the first visit.
/// Forward: the first visit searches from `from_col`, later ones from 0. Backward: the first
/// visit takes the last start at or before `from_col`, later ones the last start in the line.
pub fn find_match<S: AsRef<str>>(
    lines: &[S],
    needle: &str,
    from_line: isize,
    from_col: isize,
    forward: bool,
) -> Option<(usize, usize)> {
    let n = lines.len();
    if n == 0 || needle.is_empty() {
        return None;
    }
    let n_i = isize::try_from(n).ok()?;
    let start = from_line.rem_euclid(n_i);
    for step in 0..=n_i {
        let line = (start + if forward { step } else { -step }).rem_euclid(n_i);
        let ix = usize::try_from(line).ok()?;
        let text = lines[ix].as_ref();
        let col = if forward {
            index_of_ignore_case(text, needle, if step == 0 { from_col } else { 0 })
        } else {
            let from = if step == 0 {
                from_col
            } else {
                isize::try_from(text.len()).unwrap_or(isize::MAX)
            };
            last_index_of_ignore_case(text, needle, from)
        };
        if let Some(col) = col {
            return Some((ix, col));
        }
    }
    None
}

/// `(ordinal, total)`: the 1-based number of the match at `(line, col)` among every
/// non-overlapping match in the log (at least 1), and how many there are.
pub fn match_position<S: AsRef<str>>(
    lines: &[S],
    needle: &str,
    line: usize,
    col: usize,
) -> (usize, usize) {
    let mut total = 0;
    let mut ordinal = 0;
    if !needle.is_empty() {
        for (l, text) in lines.iter().enumerate() {
            let text = text.as_ref();
            let mut from = 0isize;
            while let Some(i) = index_of_ignore_case(text, needle, from) {
                total += 1;
                if l < line || (l == line && i <= col) {
                    ordinal = total;
                }
                let end = match_end_at(text, i, needle).unwrap_or(i + 1);
                from = isize::try_from(end.max(i + 1)).unwrap_or(isize::MAX);
            }
        }
    }
    (ordinal.max(1), total)
}

/// The current find match: a byte range in one line.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Match {
    pub line: usize,
    pub start: usize,
    pub end: usize,
}

/// How a row click changes the selection.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SelectMode {
    /// A plain click: just this row.
    Single,
    /// Shift-click: the range from the anchor.
    Range,
    /// Ctrl/Cmd-click: toggle this row.
    Toggle,
}

/// The log view's lines, the current find match and the row selection.
#[derive(Debug, Default, Clone)]
pub struct LogLines {
    lines: VecDeque<SharedString>,
    current: Option<Match>,
    selection: BTreeSet<usize>,
    anchor: Option<usize>,
    /// The keyboard focus row (where the arrow keys move from; the far end of a Shift range).
    lead: Option<usize>,
}

impl LogLines {
    /// No lines.
    pub fn new() -> Self {
        Self::default()
    }

    /// Number of lines.
    pub fn len(&self) -> usize {
        self.lines.len()
    }

    /// Whether there are no lines.
    pub fn is_empty(&self) -> bool {
        self.lines.is_empty()
    }

    /// One line.
    pub fn line(&self, ix: usize) -> Option<&SharedString> {
        self.lines.get(ix)
    }

    /// The current match.
    pub fn current_match(&self) -> Option<Match> {
        self.current
    }

    /// Whether a row is selected.
    pub fn is_selected(&self, ix: usize) -> bool {
        self.selection.contains(&ix)
    }

    /// The selected rows, ascending.
    pub fn selected_rows(&self) -> Vec<usize> {
        self.selection.iter().copied().collect()
    }

    /// Replaces every line with the lines of `text`; clears the match and the selection.
    pub fn set_text(&mut self, text: &str) {
        self.current = None;
        self.clear_selection();
        self.lines = split_lines(Some(text))
            .into_iter()
            .map(SharedString::from)
            .collect();
    }

    /// Appends lines, then trims the front past the cap's slack ([`trim::display_lines_to_drop`]).
    /// The match and the selection move with their lines (and go when their line is dropped).
    /// Returns how many lines were dropped.
    pub fn append<I, S>(&mut self, added: I, max_lines: i32) -> usize
    where
        I: IntoIterator<Item = S>,
        S: Into<SharedString>,
    {
        self.lines.extend(added.into_iter().map(Into::into));
        let count = i32::try_from(self.lines.len()).unwrap_or(i32::MAX);
        let dropped = usize::try_from(trim::display_lines_to_drop(count, max_lines)).unwrap_or(0);
        if dropped == 0 {
            return 0;
        }
        self.lines.drain(..dropped.min(self.lines.len()));
        self.current = self
            .current
            .and_then(|m| m.line.checked_sub(dropped).map(|line| Match { line, ..m }));
        self.selection = self
            .selection
            .iter()
            .filter_map(|ix| ix.checked_sub(dropped))
            .collect();
        self.anchor = self.anchor.and_then(|a| a.checked_sub(dropped));
        self.lead = self.lead.and_then(|l| l.checked_sub(dropped));
        dropped
    }

    /// Every line, each followed by `'\n'`.
    pub fn text(&self) -> String {
        let (a, b) = self.lines.as_slices();
        let mut out = join_lines(a);
        out.push_str(&join_lines(b));
        out
    }

    /// Finds the next (`forward`) or previous match after the current one, or from the start /
    /// end of the log. Stores it and clears the row selection (a selected row's colour would hide
    /// the highlight). Returns `(ordinal, total)`, or `None` (match cleared) when there is none.
    pub fn find(&mut self, needle: &str, forward: bool) -> Option<(usize, usize)> {
        if needle.is_empty() || self.lines.is_empty() {
            self.current = None;
            return None;
        }
        let lines = self.lines.make_contiguous();
        let (from_line, from_col) = match self.current.filter(|m| m.line < lines.len()) {
            Some(m) if forward => (m.line, as_isize(m.end)),
            Some(m) => (m.line, as_isize(m.start) - 1),
            None if forward => (0, 0),
            None => {
                let last = lines.len() - 1;
                (last, as_isize(lines[last].len()))
            }
        };
        let Some((line, start)) = find_match(lines, needle, as_isize(from_line), from_col, forward)
        else {
            self.current = None;
            return None;
        };
        let end = match_end_at(&lines[line], start, needle).unwrap_or(start);
        let position = match_position(lines, needle, line, start);
        self.current = Some(Match { line, start, end });
        self.clear_selection();
        Some(position)
    }

    /// Clears the current match.
    pub fn clear_match(&mut self) {
        self.current = None;
    }

    /// Changes the selection for a click on row `ix`.
    pub fn select(&mut self, ix: usize, mode: SelectMode) {
        if ix >= self.lines.len() {
            return;
        }
        match mode {
            SelectMode::Single => {
                self.selection.clear();
                self.selection.insert(ix);
                self.anchor = Some(ix);
            }
            SelectMode::Range => {
                let anchor = *self.anchor.get_or_insert(ix);
                self.selection.clear();
                self.selection.extend(anchor.min(ix)..=anchor.max(ix));
            }
            SelectMode::Toggle => {
                if !self.selection.remove(&ix) {
                    self.selection.insert(ix);
                }
                self.anchor = Some(ix);
            }
        }
        self.lead = Some(ix);
    }

    /// Ctrl/Cmd+A: selects every line.
    pub fn select_all(&mut self) {
        if self.lines.is_empty() {
            return;
        }
        self.selection = (0..self.lines.len()).collect();
        if self.lead.is_none_or(|l| l >= self.lines.len()) {
            self.lead = Some(0);
            self.anchor = Some(0);
        }
    }

    /// The keyboard focus row.
    pub fn lead(&self) -> Option<usize> {
        self.lead.filter(|l| *l < self.lines.len())
    }

    /// Up / Down (`delta` -1 / +1; Page Up / Down a larger step), as a JavaFX `ListView` with
    /// multiple selection: plain selects the new row alone; `extend` (Shift) selects the range
    /// from the anchor to it. With no focus row yet, Down starts at the first row and Up at the
    /// last. Returns the new focus row (to scroll to).
    pub fn move_lead(&mut self, delta: isize, extend: bool) -> Option<usize> {
        let last = self.lines.len().checked_sub(1)?;
        let target = match self.lead() {
            Some(l) => l.saturating_add_signed(delta).min(last),
            None if delta >= 0 => 0,
            None => last,
        };
        self.focus_row(target, extend);
        Some(target)
    }

    /// Home / End: the first or last row (`extend` selects the range to it).
    pub fn move_lead_to_edge(&mut self, end: bool, extend: bool) -> Option<usize> {
        let last = self.lines.len().checked_sub(1)?;
        let target = if end { last } else { 0 };
        self.focus_row(target, extend);
        Some(target)
    }

    fn focus_row(&mut self, ix: usize, extend: bool) {
        let mode = if extend && self.anchor.is_some() {
            SelectMode::Range
        } else {
            SelectMode::Single
        };
        self.select(ix, mode);
    }

    /// Clears the row selection.
    pub fn clear_selection(&mut self) {
        self.selection.clear();
        self.anchor = None;
        self.lead = None;
    }

    /// The selected rows' text in row order, each line followed by `'\n'`; `None` when nothing
    /// is selected.
    pub fn selected_text(&self) -> Option<String> {
        if self.selection.is_empty() {
            return None;
        }
        let picked: Vec<&SharedString> = self
            .selection
            .iter()
            .filter_map(|&ix| self.lines.get(ix))
            .collect();
        Some(join_lines(&picked))
    }
}

fn as_isize(v: usize) -> isize {
    isize::try_from(v).unwrap_or(isize::MAX)
}

#[cfg(test)]
mod tests {
    //! Port of `GameSessionPaneSearchTest`, plus the buffer behaviour of `SessionLogView`.
    use super::*;

    const LOG: [&str; 5] = ["warn a", "info b", "warn c", "error d", "warn e"];

    #[test]
    fn counts_every_match_and_numbers_the_current_one() {
        assert_eq!(match_position(&LOG, "warn", 0, 0), (1, 3));
        assert_eq!(match_position(&LOG, "warn", 2, 0), (2, 3));
        assert_eq!(match_position(&LOG, "warn", 4, 0), (3, 3));
    }

    #[test]
    fn matches_do_not_overlap() {
        assert_eq!(match_position(&["aaaa"], "aa", 0, 2), (2, 2));
    }

    #[test]
    fn a_single_match_is_one_of_one() {
        assert_eq!(match_position(&LOG, "error", 3, 0), (1, 1));
    }

    #[test]
    fn search_ignores_case() {
        assert_eq!(find_match(&LOG, "ERROR", 0, 0, true), Some((3, 0)));
        assert_eq!(match_position(&LOG, "WARN", 4, 0), (3, 3));
    }

    #[test]
    fn forward_finds_the_next_match_and_wraps_to_the_start() {
        assert_eq!(find_match(&LOG, "warn", 0, 4, true), Some((2, 0)));
        assert_eq!(find_match(&LOG, "warn", 4, 4, true), Some((0, 0)));
        assert_eq!(find_match(&["ab ab"], "ab", 0, 2, true), Some((0, 3)));
    }

    #[test]
    fn backward_finds_the_previous_match_and_wraps_to_the_end() {
        assert_eq!(find_match(&LOG, "warn", 4, -1, false), Some((2, 0)));
        assert_eq!(find_match(&LOG, "warn", 0, -1, false), Some((4, 0)));
        assert_eq!(find_match(&["ab ab"], "ab", 0, 2, false), Some((0, 0)));
    }

    #[test]
    fn no_match_is_none() {
        assert_eq!(find_match(&LOG, "fatal", 0, 0, true), None);
        let empty: [&str; 0] = [];
        assert_eq!(find_match(&empty, "warn", 0, 0, true), None);
        assert_eq!(match_position(&LOG, "fatal", 0, 0), (1, 0));
    }

    #[test]
    fn splits_text_into_lines_without_a_trailing_empty_one() {
        assert_eq!(split_lines(Some("a\nb\n")), ["a", "b"]);
        assert_eq!(split_lines(Some("a\r\nb")), ["a", "b"]);
        assert_eq!(split_lines(Some("a\n\nb\n")), ["a", "", "b"]);
        assert_eq!(split_lines(Some("a\r")), ["a"]);
        assert!(split_lines(Some("")).is_empty());
        assert!(split_lines(None).is_empty());
    }

    #[test]
    fn copied_text_ends_every_line_with_a_break() {
        assert_eq!(join_lines(&["a", "b"]), "a\nb\n");
        let none: [&str; 0] = [];
        assert_eq!(join_lines(&none), "");
        assert_eq!(join_lines(&split_lines(Some("x\ny\n"))), "x\ny\n");
    }

    #[test]
    fn non_ascii_logs_never_panic() {
        let lines = ["日本語のログ ERROR 発生", "straße STRASSE", "ÄÖÜ äöü"];
        assert_eq!(find_match(&lines, "error", 0, 0, true), Some((0, 19)));
        assert_eq!(find_match(&lines, "äöü", 0, 0, true), Some((2, 0)));
        // Starting inside a multi-byte char moves to the next boundary instead of panicking.
        assert!(find_match(&lines, "ログ", 0, 1, true).is_some());
        assert!(find_match(&lines, "ログ", 0, 1, false).is_some());
        assert_eq!(match_position(&lines, "äöü", 2, 0), (1, 2));
    }

    #[test]
    fn find_walks_matches_and_reports_positions() {
        let mut log = LogLines::new();
        log.set_text(&join_lines(&LOG));
        assert_eq!(log.find("warn", true), Some((1, 3)));
        assert_eq!(
            log.current_match(),
            Some(Match {
                line: 0,
                start: 0,
                end: 4
            })
        );
        assert_eq!(log.find("warn", true), Some((2, 3)));
        assert_eq!(log.find("warn", true), Some((3, 3)));
        assert_eq!(log.find("warn", true), Some((1, 3)), "wraps to the first");
        assert_eq!(
            log.find("warn", false),
            Some((3, 3)),
            "and back to the last"
        );
        assert_eq!(log.find("nothing", true), None);
        assert_eq!(log.current_match(), None);
        assert_eq!(log.find("", true), None);
    }

    #[test]
    fn backward_find_without_a_match_starts_at_the_end() {
        let mut log = LogLines::new();
        log.set_text(&join_lines(&LOG));
        assert_eq!(log.find("warn", false), Some((3, 3)));
        assert_eq!(log.current_match().map(|m| m.line), Some(4));
    }

    #[test]
    fn a_find_clears_the_row_selection() {
        let mut log = LogLines::new();
        log.set_text(&join_lines(&LOG));
        log.select(1, SelectMode::Single);
        assert!(log.find("info", true).is_some());
        assert!(log.selected_rows().is_empty());
    }

    #[test]
    fn append_trims_past_the_slack_and_moves_the_match() {
        let mut log = LogLines::new();
        let lines: Vec<String> = (0..100).map(|i| format!("line {i}")).collect();
        assert_eq!(log.append(lines.clone(), 100), 0);
        assert!(log.find("line 99", true).is_some());
        log.select(99, SelectMode::Single);
        // 100 + 51 = 151 > 100 + 50 -> drop back to 100.
        let more: Vec<String> = (100..151).map(|i| format!("line {i}")).collect();
        assert_eq!(log.append(more, 100), 51);
        assert_eq!(log.len(), 100);
        assert_eq!(log.line(0).map(|s| s.as_ref()), Some("line 51"));
        assert_eq!(log.current_match().map(|m| m.line), Some(48));
        assert_eq!(log.selected_rows(), [48]);
        // The matched line itself falls off: the match goes.
        let lots: Vec<String> = (0..200).map(|i| format!("x {i}")).collect();
        assert!(log.append(lots, 100) > 0);
        assert_eq!(log.current_match(), None);
        assert!(log.selected_rows().is_empty());
    }

    #[test]
    fn unlimited_never_trims() {
        let mut log = LogLines::new();
        let lines: Vec<String> = (0..5000).map(|i| i.to_string()).collect();
        assert_eq!(log.append(lines, 0), 0);
        assert_eq!(log.len(), 5000);
    }

    #[test]
    fn selection_modes_and_copy() {
        let mut log = LogLines::new();
        log.set_text("a\nb\nc\nd\n");
        assert_eq!(log.selected_text(), None);
        log.select(2, SelectMode::Single);
        log.select(0, SelectMode::Range);
        assert_eq!(log.selected_rows(), [0, 1, 2]);
        log.select(1, SelectMode::Toggle);
        assert_eq!(log.selected_text().as_deref(), Some("a\nc\n"));
        log.select(3, SelectMode::Toggle);
        assert_eq!(log.selected_text().as_deref(), Some("a\nc\nd\n"));
        log.select(99, SelectMode::Single);
        assert_eq!(log.selected_rows(), [0, 2, 3], "out of range is ignored");
        assert_eq!(log.text(), "a\nb\nc\nd\n");
        log.set_text("z");
        assert!(log.selected_rows().is_empty());
        assert_eq!(log.text(), "z\n");
    }

    #[test]
    fn select_all_and_keyboard_moves() {
        let mut log = LogLines::new();
        log.select_all();
        assert!(log.selected_rows().is_empty(), "nothing to select");
        assert_eq!(log.move_lead(1, false), None);
        log.set_text(
            "a
b
c
d
",
        );
        log.select_all();
        assert_eq!(log.selected_rows(), [0, 1, 2, 3]);
        assert_eq!(
            log.selected_text().as_deref(),
            Some(
                "a
b
c
d
"
            )
        );
        log.clear_selection();
        // No focus row yet: Down starts at the top, Up at the bottom.
        assert_eq!(log.move_lead(1, false), Some(0));
        assert_eq!(log.move_lead(1, false), Some(1));
        assert_eq!(log.selected_rows(), [1]);
        assert_eq!(log.move_lead(1, true), Some(2));
        assert_eq!(log.move_lead(1, true), Some(3));
        assert_eq!(log.selected_rows(), [1, 2, 3]);
        assert_eq!(log.move_lead(-3, true), Some(0));
        assert_eq!(log.selected_rows(), [0, 1]);
        assert_eq!(log.move_lead(-1, false), Some(0), "held at the top");
        assert_eq!(log.move_lead_to_edge(true, true), Some(3));
        assert_eq!(log.selected_rows(), [0, 1, 2, 3]);
        assert_eq!(log.move_lead_to_edge(false, false), Some(0));
        assert_eq!(log.selected_rows(), [0]);
        log.clear_selection();
        assert_eq!(log.move_lead(-1, false), Some(3));
        // A click moves the focus row; the focus row follows trimmed lines.
        log.select(2, SelectMode::Single);
        assert_eq!(log.lead(), Some(2));
        assert_eq!(log.move_lead(1, false), Some(3));
    }
}
