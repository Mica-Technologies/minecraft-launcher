// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The model behind one file-list tab: rows in manifest order, plus a view order produced by
//! the filter and the column sort. Sorting and filtering never reorder the model, so saving
//! keeps the manifest's order.

use std::collections::HashSet;

use mmcl_manifest::document::FileList;

use crate::row::{EditorRow, RowId, UrlCheck};

/// A table column.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Column {
    Name,
    Remote,
    Local,
    Hash,
    Type,
    Client,
    Server,
    UrlStatus,
    Calc,
}

impl Column {
    /// Every column, left to right.
    pub const ALL: [Column; 9] = [
        Column::Name,
        Column::Remote,
        Column::Local,
        Column::Hash,
        Column::Type,
        Column::Client,
        Column::Server,
        Column::UrlStatus,
        Column::Calc,
    ];

    /// The columns a list shows: Name only for mods, Client/Server only for lists with side
    /// flags.
    pub fn shown_for(list: FileList) -> Vec<Column> {
        Column::ALL
            .into_iter()
            .filter(|c| match c {
                Column::Name => list.has_name(),
                Column::Client | Column::Server => list.has_side_flags(),
                _ => true,
            })
            .collect()
    }

    /// The header localization key (`None` for the unlabelled calc column).
    pub fn header_key(self) -> Option<&'static str> {
        Some(match self {
            Column::Name => "editor.column.name",
            Column::Remote => "editor.column.remoteUrl",
            Column::Local => "editor.column.localPath",
            Column::Hash => "editor.column.hash",
            Column::Type => "editor.column.type",
            Column::Client => "editor.column.client",
            Column::Server => "editor.column.server",
            Column::UrlStatus => "editor.column.urlStatus",
            Column::Calc => return None,
        })
    }

    /// (preferred, min, max) widths in px; `None` max grows.
    pub fn widths(self) -> (f32, f32, Option<f32>) {
        match self {
            Column::Name => (120.0, 80.0, Some(200.0)),
            Column::Remote => (300.0, 150.0, None),
            Column::Local => (150.0, 100.0, None),
            Column::Hash => (140.0, 80.0, None),
            Column::Type => (55.0, 45.0, Some(65.0)),
            Column::Client | Column::Server => (55.0, 50.0, Some(60.0)),
            Column::UrlStatus => (70.0, 55.0, Some(90.0)),
            Column::Calc => (50.0, 45.0, Some(55.0)),
        }
    }

    /// Whether a click on the header sorts.
    pub fn sortable(self) -> bool {
        self != Column::Calc
    }

    /// Whether the cell is a text editor.
    pub fn is_text(self) -> bool {
        matches!(
            self,
            Column::Name | Column::Remote | Column::Local | Column::Hash | Column::Type
        )
    }

    /// Whether the cell is a checkbox.
    pub fn is_check(self) -> bool {
        matches!(self, Column::Client | Column::Server)
    }

    /// A text cell's value.
    pub fn text_of(self, row: &EditorRow) -> &str {
        match self {
            Column::Name => &row.name,
            Column::Remote => &row.remote,
            Column::Local => &row.local,
            Column::Hash => &row.hash,
            Column::Type => &row.hash_type,
            Column::UrlStatus => row.url_status.as_ref().map_or("", |s| s.label.as_str()),
            _ => "",
        }
    }
}

/// The user's column arrangement for one table: column order (drag a header onto another) and
/// widths (drag a header's right edge), as a JavaFX `TableView` allows. View state only: never
/// saved, never part of the document.
#[derive(Debug, Clone, PartialEq)]
pub struct ColumnLayout {
    order: Vec<Column>,
    widths: Vec<(Column, f32)>,
}

impl ColumnLayout {
    /// The default arrangement of `list`'s columns.
    pub fn new(list: FileList) -> ColumnLayout {
        ColumnLayout {
            order: Column::shown_for(list),
            widths: Vec::new(),
        }
    }

    /// The columns, left to right.
    pub fn order(&self) -> &[Column] {
        &self.order
    }

    /// The width the user gave `col` (px), `None` while it follows the default policy.
    pub fn width(&self, col: Column) -> Option<f32> {
        self.widths.iter().find(|(c, _)| *c == col).map(|(_, w)| *w)
    }

    /// Sets `col`'s width, held to its (min, max) bounds. Returns the width applied.
    pub fn set_width(&mut self, col: Column, width: f32) -> f32 {
        let (_, min, max) = col.widths();
        let w = if width.is_nan() { min } else { width.max(min) };
        let w = max.map_or(w, |m| w.min(m));
        match self.widths.iter_mut().find(|(c, _)| *c == col) {
            Some(slot) => slot.1 = w,
            None => self.widths.push((col, w)),
        }
        w
    }

    /// Moves `col` to where `target` is (dropping a header on another). Returns whether the
    /// order changed.
    pub fn move_column(&mut self, col: Column, target: Column) -> bool {
        if col == target {
            return false;
        }
        let (Some(from), Some(to)) = (
            self.order.iter().position(|c| *c == col),
            self.order.iter().position(|c| *c == target),
        ) else {
            return false;
        };
        let c = self.order.remove(from);
        self.order.insert(to, c);
        true
    }

    /// Back to the default order and widths (double-click on a resize edge resets one width).
    pub fn reset_width(&mut self, col: Column) {
        self.widths.retain(|(c, _)| *c != col);
    }
}

/// Sort direction.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SortDir {
    Ascending,
    Descending,
}

/// One file-list tab's rows.
#[derive(Debug, Clone)]
pub struct FileTable {
    list: FileList,
    rows: Vec<(RowId, EditorRow)>,
    next_id: u64,
    filter: String,
    sort: Option<(Column, SortDir)>,
    selection: HashSet<RowId>,
    /// Anchor of shift-click range selection.
    anchor: Option<RowId>,
    /// The keyboard focus row (where arrow keys move from; the far end of a Shift range).
    lead: Option<RowId>,
}

impl FileTable {
    /// An empty table for `list`.
    pub fn new(list: FileList) -> FileTable {
        FileTable {
            list,
            rows: Vec::new(),
            next_id: 1,
            filter: String::new(),
            sort: None,
            selection: HashSet::new(),
            anchor: None,
            lead: None,
        }
    }

    /// Which list this is.
    pub fn list(&self) -> FileList {
        self.list
    }

    /// Replaces every row (populate). Selection and URL statuses are cleared.
    pub fn set_rows(&mut self, rows: Vec<EditorRow>) {
        self.rows.clear();
        self.selection.clear();
        self.anchor = None;
        self.lead = None;
        for r in rows {
            self.push(r);
        }
    }

    fn push(&mut self, row: EditorRow) -> RowId {
        let id = RowId(self.next_id);
        self.next_id += 1;
        self.rows.push((id, row));
        id
    }

    /// Rows in model (manifest) order.
    pub fn rows(&self) -> impl Iterator<Item = &EditorRow> {
        self.rows.iter().map(|(_, r)| r)
    }

    /// Row ids and rows in model order.
    pub fn entries(&self) -> &[(RowId, EditorRow)] {
        &self.rows
    }

    /// Every row, cloned, in model order (for collect).
    pub fn snapshot(&self) -> Vec<EditorRow> {
        self.rows().cloned().collect()
    }

    /// The row with `id`.
    pub fn row(&self, id: RowId) -> Option<&EditorRow> {
        self.rows.iter().find(|(i, _)| *i == id).map(|(_, r)| r)
    }

    /// The row with `id`, mutable.
    pub fn row_mut(&mut self, id: RowId) -> Option<&mut EditorRow> {
        self.rows.iter_mut().find(|(i, _)| *i == id).map(|(_, r)| r)
    }

    /// Number of rows (all, not only filtered).
    pub fn len(&self) -> usize {
        self.rows.len()
    }

    /// Whether there are no rows.
    pub fn is_empty(&self) -> bool {
        self.rows.is_empty()
    }

    /// Appends a blank row and returns its id.
    pub fn add_row(&mut self) -> RowId {
        self.push(EditorRow::default())
    }

    /// Appends rows (Modrinth "Add selected").
    pub fn append(&mut self, rows: Vec<EditorRow>) -> Vec<RowId> {
        rows.into_iter().map(|r| self.push(r)).collect()
    }

    /// Deletes every selected row; the number removed.
    pub fn remove_selected(&mut self) -> usize {
        let before = self.rows.len();
        let selected = std::mem::take(&mut self.selection);
        self.rows.retain(|(id, _)| !selected.contains(id));
        self.anchor = None;
        self.lead = None;
        before - self.rows.len()
    }

    /// The filter text.
    pub fn filter(&self) -> &str {
        &self.filter
    }

    /// Sets the filter (case-insensitive substring over name, local and remote).
    pub fn set_filter(&mut self, filter: &str) {
        self.filter = filter.to_string();
    }

    /// The current sort.
    pub fn sort(&self) -> Option<(Column, SortDir)> {
        self.sort
    }

    /// A header click: ascending, then descending, then unsorted (JavaFX's cycle). Clicking a
    /// different column starts ascending.
    pub fn click_header(&mut self, col: Column) {
        if !col.sortable() {
            return;
        }
        self.sort = match self.sort {
            Some((c, SortDir::Ascending)) if c == col => Some((col, SortDir::Descending)),
            Some((c, SortDir::Descending)) if c == col => None,
            _ => Some((col, SortDir::Ascending)),
        };
    }

    /// Whether `row` passes the filter. Unicode lower-casing on both sides (Java used the
    /// default locale, which broke dotless-i in Turkish).
    pub fn matches_filter(filter: &str, row: &EditorRow) -> bool {
        let f = filter.trim();
        if f.is_empty() {
            return true;
        }
        let f = f.to_lowercase();
        [&row.name, &row.local, &row.remote]
            .iter()
            .any(|s| s.to_lowercase().contains(&f))
    }

    /// Row ids in display order: filtered, then stably sorted.
    pub fn view_order(&self) -> Vec<RowId> {
        let mut shown: Vec<&(RowId, EditorRow)> = self
            .rows
            .iter()
            .filter(|(_, r)| Self::matches_filter(&self.filter, r))
            .collect();
        if let Some((col, dir)) = self.sort {
            shown.sort_by(|(_, a), (_, b)| {
                let ord = compare_by(col, a, b);
                match dir {
                    SortDir::Ascending => ord,
                    SortDir::Descending => ord.reverse(),
                }
            });
        }
        shown.into_iter().map(|(id, _)| *id).collect()
    }

    /// Whether `id` is selected.
    pub fn is_selected(&self, id: RowId) -> bool {
        self.selection.contains(&id)
    }

    /// Selected ids.
    pub fn selection(&self) -> &HashSet<RowId> {
        &self.selection
    }

    /// Clears the selection.
    pub fn clear_selection(&mut self) {
        self.selection.clear();
        self.anchor = None;
        self.lead = None;
    }

    /// A click on a row: plain replaces the selection; `toggle` (Ctrl/Cmd) adds or removes;
    /// `extend` (Shift) selects the view range from the anchor.
    pub fn click_row(&mut self, id: RowId, toggle: bool, extend: bool) {
        if extend && let Some(anchor) = self.anchor {
            let order = self.view_order();
            let a = order.iter().position(|r| *r == anchor);
            let b = order.iter().position(|r| *r == id);
            if let (Some(a), Some(b)) = (a, b) {
                let (lo, hi) = if a <= b { (a, b) } else { (b, a) };
                if !toggle {
                    self.selection.clear();
                }
                self.selection.extend(order[lo..=hi].iter().copied());
                self.lead = Some(id);
                return;
            }
        }
        if toggle {
            if !self.selection.remove(&id) {
                self.selection.insert(id);
            }
        } else {
            self.selection.clear();
            self.selection.insert(id);
        }
        self.anchor = Some(id);
        self.lead = Some(id);
    }

    /// Selects every row (Ctrl/Cmd+A over the table).
    pub fn select_all_shown(&mut self) {
        let order = self.view_order();
        if self.lead.is_none_or(|l| !order.contains(&l)) {
            self.lead = order.first().copied();
            self.anchor = self.lead;
        }
        self.selection = order.into_iter().collect();
    }

    /// The keyboard focus row, when it is still shown.
    pub fn lead(&self) -> Option<RowId> {
        self.lead.filter(|l| self.view_order().contains(l))
    }

    /// Up / Down (`delta` -1 / +1, Page Up / Down a larger step) over the shown rows, as a
    /// JavaFX `TableView` does: plain moves the selection to the new row; `extend` (Shift)
    /// selects the range from the anchor to it. With no focus row yet, Down starts at the first
    /// row and Up at the last. Returns the new focus row (to scroll to).
    pub fn move_lead(&mut self, delta: isize, extend: bool) -> Option<RowId> {
        let order = self.view_order();
        let last = order.len().checked_sub(1)?;
        let target = match self.lead.and_then(|l| order.iter().position(|r| *r == l)) {
            Some(pos) => pos.saturating_add_signed(delta).min(last),
            None if delta >= 0 => 0,
            None => last,
        };
        self.focus_view_index(&order, target, extend)
    }

    /// Home / End: the first or last shown row (`extend` selects the range to it).
    pub fn move_lead_to_edge(&mut self, end: bool, extend: bool) -> Option<RowId> {
        let order = self.view_order();
        let last = order.len().checked_sub(1)?;
        self.focus_view_index(&order, if end { last } else { 0 }, extend)
    }

    fn focus_view_index(&mut self, order: &[RowId], pos: usize, extend: bool) -> Option<RowId> {
        let id = *order.get(pos)?;
        let anchor_pos = self.anchor.and_then(|a| order.iter().position(|r| *r == a));
        match anchor_pos {
            Some(a) if extend => {
                let (lo, hi) = if a <= pos { (a, pos) } else { (pos, a) };
                self.selection = order[lo..=hi].iter().copied().collect();
            }
            _ => {
                self.selection.clear();
                self.selection.insert(id);
                self.anchor = Some(id);
            }
        }
        self.lead = Some(id);
        Some(id)
    }

    /// The cell Tab (`forward`) or Shift+Tab moves an open editor to: the next text column of
    /// the row in `cols` order, wrapping to the first (last) text column of the next (previous)
    /// shown row. `None` past the last (first) cell.
    pub fn neighbor_cell(
        &self,
        cols: &[Column],
        row: RowId,
        col: Column,
        forward: bool,
    ) -> Option<(RowId, Column)> {
        let text: Vec<Column> = cols.iter().copied().filter(|c| c.is_text()).collect();
        let at = text.iter().position(|c| *c == col)?;
        let in_row = if forward {
            text.get(at + 1)
        } else {
            at.checked_sub(1).and_then(|i| text.get(i))
        };
        if let Some(c) = in_row {
            return Some((row, *c));
        }
        let order = self.view_order();
        let pos = order.iter().position(|r| *r == row)?;
        let next = if forward {
            order.get(pos + 1)
        } else {
            pos.checked_sub(1).and_then(|i| order.get(i))
        }?;
        let c = if forward { text.first() } else { text.last() }?;
        Some((*next, *c))
    }

    /// Sets a text cell. Returns whether the value changed.
    pub fn set_text(&mut self, id: RowId, col: Column, value: &str) -> bool {
        let Some(row) = self.row_mut(id) else {
            return false;
        };
        let slot = match col {
            Column::Name => &mut row.name,
            Column::Remote => &mut row.remote,
            Column::Local => &mut row.local,
            Column::Hash => &mut row.hash,
            Column::Type => &mut row.hash_type,
            _ => return false,
        };
        if slot == value {
            return false;
        }
        *slot = value.to_string();
        true
    }

    /// Sets a checkbox cell.
    pub fn set_check(&mut self, id: RowId, col: Column, value: bool) -> bool {
        let Some(row) = self.row_mut(id) else {
            return false;
        };
        let slot = match col {
            Column::Client => &mut row.client_req,
            Column::Server => &mut row.server_req,
            _ => return false,
        };
        let changed = *slot != value;
        *slot = value;
        changed
    }

    /// Clears the URL status of the given rows.
    pub fn clear_url_status(&mut self, ids: &[RowId]) {
        for id in ids {
            if let Some(r) = self.row_mut(*id) {
                r.url_status = None;
            }
        }
    }

    /// Records a URL check result (ignored when the row was removed meanwhile).
    pub fn set_url_status(&mut self, id: RowId, status: UrlCheck) {
        if let Some(r) = self.row_mut(id) {
            r.url_status = Some(status);
        }
    }

    /// After a URL check: select every broken row among `checked`, returning the first broken
    /// row in view order (to scroll to).
    pub fn select_broken(&mut self, checked: &[RowId]) -> Option<RowId> {
        self.selection.clear();
        self.anchor = None;
        self.lead = None;
        for id in checked {
            if self
                .row(*id)
                .and_then(|r| r.url_status.as_ref())
                .is_some_and(|s| !s.ok)
            {
                self.selection.insert(*id);
            }
        }
        self.view_order()
            .into_iter()
            .find(|id| self.selection.contains(id))
    }

    /// Whether some row's local path equals `local`, ignoring case.
    pub fn has_local(&self, local: &str) -> bool {
        let l = local.trim().to_lowercase();
        !l.is_empty() && self.rows().any(|r| r.local.trim().to_lowercase() == l)
    }
}

fn compare_by(col: Column, a: &EditorRow, b: &EditorRow) -> std::cmp::Ordering {
    match col {
        Column::Client => a.client_req.cmp(&b.client_req),
        Column::Server => a.server_req.cmp(&b.server_req),
        _ => col
            .text_of(a)
            .to_lowercase()
            .cmp(&col.text_of(b).to_lowercase()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(name: &str, local: &str, remote: &str) -> EditorRow {
        EditorRow {
            name: name.into(),
            local: local.into(),
            remote: remote.into(),
            ..Default::default()
        }
    }

    fn table() -> FileTable {
        let mut t = FileTable::new(FileList::Mods);
        t.set_rows(vec![
            row("Zeta", "mods/zeta.jar", "https://a/zeta.jar"),
            row("alpha", "mods/alpha.jar", "https://b/alpha.jar"),
            row("Mid", "mods/MID.jar", "https://c/mid.jar"),
        ]);
        t
    }

    #[test]
    fn columns_follow_the_list_shape() {
        let mods = Column::shown_for(FileList::Mods);
        assert_eq!(mods.len(), 9);
        let res = Column::shown_for(FileList::ResourcePacks);
        assert!(!res.contains(&Column::Name));
        assert!(!res.contains(&Column::Client));
        let cfg = Column::shown_for(FileList::Configs);
        assert!(cfg.contains(&Column::Client) && !cfg.contains(&Column::Name));
        assert_eq!(Column::Calc.header_key(), None);
    }

    #[test]
    fn filter_is_case_insensitive_over_name_local_remote() {
        let mut t = table();
        t.set_filter("ALPHA");
        assert_eq!(t.view_order().len(), 1);
        t.set_filter("mid");
        assert_eq!(t.view_order().len(), 1);
        t.set_filter("https://c");
        assert_eq!(t.view_order().len(), 1);
        t.set_filter("");
        assert_eq!(t.view_order().len(), 3);
        // Unicode lower-casing (no Turkish dotless-i surprise).
        let mut u = FileTable::new(FileList::Mods);
        u.set_rows(vec![row("İstanbul", "", "")]);
        u.set_filter("i̇stanbul");
        assert_eq!(u.view_order().len(), 1);
    }

    #[test]
    fn sorting_is_view_only_and_cycles() {
        let mut t = table();
        let model: Vec<String> = t.rows().map(|r| r.name.clone()).collect();
        t.click_header(Column::Name);
        let names = |t: &FileTable| -> Vec<String> {
            t.view_order()
                .iter()
                .map(|id| t.row(*id).unwrap().name.clone())
                .collect()
        };
        assert_eq!(names(&t), ["alpha", "Mid", "Zeta"]);
        t.click_header(Column::Name);
        assert_eq!(names(&t), ["Zeta", "Mid", "alpha"]);
        t.click_header(Column::Name);
        assert_eq!(t.sort(), None);
        assert_eq!(names(&t), model);
        // The model order never changed.
        assert_eq!(t.rows().map(|r| r.name.clone()).collect::<Vec<_>>(), model);
        t.click_header(Column::Calc);
        assert_eq!(t.sort(), None);
    }

    #[test]
    fn add_and_remove_selected() {
        let mut t = table();
        let id = t.add_row();
        assert_eq!(t.len(), 4);
        assert_eq!(t.row(id).unwrap(), &EditorRow::default());
        let order = t.view_order();
        t.click_row(order[0], false, false);
        t.click_row(order[2], true, false);
        assert_eq!(t.remove_selected(), 2);
        assert_eq!(t.len(), 2);
        assert!(t.selection().is_empty());
    }

    #[test]
    fn shift_click_selects_a_range() {
        let mut t = table();
        let order = t.view_order();
        t.click_row(order[0], false, false);
        t.click_row(order[2], false, true);
        assert_eq!(t.selection().len(), 3);
        t.click_row(order[1], true, false);
        assert_eq!(t.selection().len(), 2);
    }

    #[test]
    fn select_all_selects_the_shown_rows_only() {
        let mut t = table();
        t.set_filter("a");
        let shown = t.view_order();
        t.select_all_shown();
        assert_eq!(t.selection().len(), shown.len());
        assert_eq!(t.lead(), shown.first().copied());
        t.set_filter("");
        t.select_all_shown();
        assert_eq!(t.selection().len(), 3);
    }

    #[test]
    fn arrow_keys_move_and_shift_arrows_extend() {
        let mut t = table();
        let order = t.view_order();
        // No focus yet: Down starts at the top, Up at the bottom.
        assert_eq!(t.move_lead(1, false), Some(order[0]));
        assert_eq!(t.move_lead(1, false), Some(order[1]));
        assert_eq!(t.selection().len(), 1);
        assert!(t.is_selected(order[1]));
        // Shift+Down extends from the anchor; Shift+Up shrinks back.
        assert_eq!(t.move_lead(1, true), Some(order[2]));
        assert_eq!(t.selection().len(), 2);
        assert_eq!(t.move_lead(-1, true), Some(order[1]));
        assert_eq!(t.selection().len(), 1);
        assert_eq!(t.move_lead(-1, true), Some(order[0]));
        assert!(t.is_selected(order[0]) && t.is_selected(order[1]));
        // Held at the edges.
        assert_eq!(t.move_lead(-5, false), Some(order[0]));
        assert_eq!(t.move_lead(50, false), Some(order[2]));
        assert_eq!(t.move_lead_to_edge(false, true), Some(order[0]));
        assert_eq!(t.selection().len(), 3);
        let mut u = table();
        assert_eq!(u.move_lead(-1, false), Some(order[2]));
        assert_eq!(FileTable::new(FileList::Mods).move_lead(1, false), None);
        // A click moves the focus row too.
        u.click_row(order[0], false, false);
        assert_eq!(u.move_lead(1, false), Some(order[1]));
    }

    #[test]
    fn tab_walks_the_text_cells() {
        let t = table();
        let order = t.view_order();
        let cols = Column::shown_for(FileList::Mods);
        assert_eq!(
            t.neighbor_cell(&cols, order[0], Column::Name, true),
            Some((order[0], Column::Remote))
        );
        // Type is the last text column; the next is the next row's first.
        assert_eq!(
            t.neighbor_cell(&cols, order[0], Column::Type, true),
            Some((order[1], Column::Name))
        );
        assert_eq!(
            t.neighbor_cell(&cols, order[1], Column::Name, false),
            Some((order[0], Column::Type))
        );
        assert_eq!(t.neighbor_cell(&cols, order[2], Column::Type, true), None);
        assert_eq!(t.neighbor_cell(&cols, order[0], Column::Name, false), None);
        assert_eq!(t.neighbor_cell(&cols, order[0], Column::Client, true), None);
    }

    #[test]
    fn columns_resize_within_their_bounds_and_reorder() {
        let mut l = ColumnLayout::new(FileList::Mods);
        assert_eq!(l.order(), Column::shown_for(FileList::Mods).as_slice());
        assert_eq!(l.width(Column::Remote), None);
        assert_eq!(
            l.set_width(Column::Remote, 900.0),
            900.0,
            "Remote has no max"
        );
        assert_eq!(l.set_width(Column::Remote, 10.0), 150.0, "held to its min");
        assert_eq!(l.set_width(Column::Type, 500.0), 65.0, "held to its max");
        assert_eq!(l.set_width(Column::Type, f32::NAN), 45.0);
        assert_eq!(l.width(Column::Type), Some(45.0));
        l.reset_width(Column::Type);
        assert_eq!(l.width(Column::Type), None);
        assert!(l.move_column(Column::Hash, Column::Name));
        assert_eq!(l.order()[0], Column::Hash);
        assert_eq!(l.order()[1], Column::Name);
        assert!(!l.move_column(Column::Hash, Column::Hash));
        // A column the list does not show cannot move.
        let mut r = ColumnLayout::new(FileList::ResourcePacks);
        assert!(!r.move_column(Column::Name, Column::Remote));
    }

    #[test]
    fn broken_rows_are_selected_after_a_check() {
        let mut t = table();
        let order = t.view_order();
        t.set_url_status(
            order[0],
            UrlCheck {
                ok: true,
                label: "OK".into(),
            },
        );
        t.set_url_status(
            order[1],
            UrlCheck {
                ok: false,
                label: "404".into(),
            },
        );
        t.set_url_status(
            order[2],
            UrlCheck {
                ok: false,
                label: "ERR".into(),
            },
        );
        let first = t.select_broken(&order);
        assert_eq!(first, Some(order[1]));
        assert_eq!(t.selection().len(), 2);
        t.clear_url_status(&order);
        assert!(t.rows().all(|r| r.url_status.is_none()));
    }

    #[test]
    fn cell_edits_and_duplicate_local_paths() {
        let mut t = table();
        let id = t.view_order()[0];
        assert!(t.set_text(id, Column::Hash, "abc"));
        assert!(!t.set_text(id, Column::Hash, "abc"));
        assert!(t.set_check(id, Column::Client, false));
        assert!(!t.row(id).unwrap().client_req);
        assert!(t.has_local("MODS/ALPHA.JAR"));
        assert!(!t.has_local(""));
        assert!(!t.has_local("mods/other.jar"));
    }
}
