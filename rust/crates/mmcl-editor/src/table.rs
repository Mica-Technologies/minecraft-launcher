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
    }

    /// Selects every row (Ctrl/Cmd+A over the table).
    pub fn select_all_shown(&mut self) {
        self.selection = self.view_order().into_iter().collect();
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
