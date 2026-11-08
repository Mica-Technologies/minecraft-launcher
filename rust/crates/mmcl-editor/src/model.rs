// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The editor's state without GPUI: the working document, the form, one table per file list,
//! the saved snapshot (dirty check), the backing file, and the document operations.

use std::path::{Path, PathBuf};

use mmcl_i18n::{FmtArg, t, tf};
use mmcl_manifest::document::{FileList, ModPackDocument, bump_version};

use crate::form::EditorForm;
use crate::row::EditorRow;
use crate::table::FileTable;

/// The `.mmcjson` extension (without the dot).
pub const MODPACK_EXTENSION: &str = "mmcjson";

/// The editor model.
#[derive(Debug, Clone)]
pub struct EditorModel {
    pub document: ModPackDocument,
    pub form: EditorForm,
    pub tables: Vec<FileTable>,
    pub saved_snapshot: String,
    pub current_file: Option<PathBuf>,
}

impl Default for EditorModel {
    fn default() -> Self {
        EditorModel::new()
    }
}

/// Localizes a validation message: no args means a plain lookup (no MessageFormat pass, so
/// apostrophes survive).
pub fn localize(key: &str, args: &[String]) -> String {
    if args.is_empty() {
        t!(key).into_owned()
    } else {
        let a: Vec<FmtArg<'_>> = args.iter().map(|s| FmtArg::from(s.as_str())).collect();
        mmcl_i18n::format(key, &a)
    }
}

impl EditorModel {
    /// A model holding a blank document (`new_document`).
    pub fn new() -> EditorModel {
        let mut m = EditorModel {
            document: ModPackDocument::blank(),
            form: EditorForm::default(),
            tables: FileList::ALL.iter().map(|l| FileTable::new(*l)).collect(),
            saved_snapshot: String::new(),
            current_file: None,
        };
        m.new_document();
        m
    }

    /// The table of `list`.
    pub fn table(&self, list: FileList) -> &FileTable {
        self.tables
            .iter()
            .find(|t| t.list() == list)
            .expect("one table per list")
    }

    /// The table of `list`, mutable.
    pub fn table_mut(&mut self, list: FileList) -> &mut FileTable {
        self.tables
            .iter_mut()
            .find(|t| t.list() == list)
            .expect("one table per list")
    }

    /// New: a blank document, no file; snapshot taken before populating (a blank document
    /// round-trips through the form unchanged).
    pub fn new_document(&mut self) {
        self.document = ModPackDocument::blank();
        self.current_file = None;
        self.saved_snapshot = self.document.to_pretty_json();
        self.populate();
    }

    /// Adopts a loaded document: populate, collect (normalises: adds every key the form owns),
    /// snapshot after the collect so the added keys don't read as edits.
    pub fn adopt(&mut self, document: ModPackDocument, file: Option<PathBuf>) {
        self.document = document;
        self.current_file = file;
        self.populate();
        self.collect();
        self.saved_snapshot = self.document.to_pretty_json();
    }

    /// document -> form and tables.
    pub fn populate(&mut self) {
        self.form = EditorForm::populate(&self.document);
        for list in FileList::ALL {
            let rows = EditorForm::rows_of(&self.document, list);
            self.table_mut(list).set_rows(rows);
        }
    }

    /// form and tables -> document (model order, never the sorted/filtered view).
    pub fn collect(&mut self) {
        let snapshots: Vec<(FileList, Vec<EditorRow>)> = self
            .tables
            .iter()
            .map(|t| (t.list(), t.snapshot()))
            .collect();
        self.form.collect(
            &mut self.document,
            snapshots.iter().map(|(l, r)| (*l, r.as_slice())),
        );
    }

    /// The JSON the document would save as now.
    pub fn current_json(&mut self) -> String {
        self.collect();
        self.document.to_pretty_json()
    }

    /// Whether there are unsaved edits.
    pub fn is_dirty(&mut self) -> bool {
        self.current_json() != self.saved_snapshot
    }

    /// Bumps the version field (0 major, 1 minor, 2 patch); the new version.
    pub fn bump(&mut self, position: usize) -> String {
        self.form.version = bump_version(Some(&self.form.version), position);
        self.form.version.clone()
    }

    /// Validation issues of the current state (one per line, newlines stripped).
    pub fn validate(&mut self) -> Vec<String> {
        self.collect();
        self.document
            .validate(&localize)
            .into_iter()
            .map(|s| s.replace(['\r', '\n'], " ").trim().to_string())
            .filter(|s| !s.is_empty())
            .collect()
    }

    /// The diff of the saved snapshot -> now; `None` when nothing changed.
    pub fn diff(&mut self) -> Option<String> {
        let current = self.current_json();
        (current != self.saved_snapshot)
            .then(|| crate::diff::line_diff(&self.saved_snapshot, &current))
    }

    /// Records a successful save of `text` to `path`.
    pub fn mark_saved(&mut self, path: PathBuf, text: String) {
        self.current_file = Some(path);
        self.saved_snapshot = text;
    }

    /// The suggested save name: the current file's name, else `<sanitized pack name>.mmcjson`.
    pub fn suggested_file_name(&mut self) -> Option<String> {
        if let Some(name) = self
            .current_file
            .as_ref()
            .and_then(|p| p.file_name())
            .map(|n| n.to_string_lossy().into_owned())
        {
            return Some(name);
        }
        self.collect();
        let base = self.document.sanitized_file_base_name();
        (!base.is_empty()).then(|| format!("{base}.{MODPACK_EXTENSION}"))
    }

    /// The folder the save dialog opens in.
    pub fn suggested_dir(&self) -> Option<PathBuf> {
        self.current_file
            .as_ref()
            .and_then(|p| p.parent())
            .map(Path::to_path_buf)
    }
}

/// A picked save path with `.mmcjson` appended when the name has no extension (the platform
/// save dialog has no file-type filter).
pub fn with_default_extension(path: PathBuf) -> PathBuf {
    if path.extension().is_some() {
        path
    } else {
        path.with_extension(MODPACK_EXTENSION)
    }
}

/// Parses manifest text into a document.
pub fn parse_document(text: &str) -> Result<ModPackDocument, String> {
    ModPackDocument::from_json(text).map_err(|e| {
        let args: Vec<String> = e.args();
        let msg = localize(e.key(), &args);
        if msg == e.key() { e.to_string() } else { msg }
    })
}

/// The file name for status lines.
pub fn display_name(path: &Path) -> String {
    path.file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_else(|| path.display().to_string())
}

/// `file:` URL -> path (pre-loaded packs whose manifest lives on disk).
pub fn file_url_path(url: &str) -> Option<PathBuf> {
    if !url.trim().to_ascii_lowercase().starts_with("file:") {
        return None;
    }
    url::Url::parse(url.trim()).ok()?.to_file_path().ok()
}

/// The validation dialog body.
pub fn validation_body(issues: &[String]) -> String {
    format!(
        "{}\n{}\n",
        t!("dialog.editor.validate.bodyIssuesPrefix"),
        issues.join("\n")
    )
}

/// The entry count label.
pub fn entry_count(n: usize) -> String {
    tf!("editor.entryCount", n.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_new_document_is_clean() {
        let mut m = EditorModel::new();
        assert!(!m.is_dirty());
        assert_eq!(m.form.version, "1.0.0");
        assert!(m.current_file.is_none());
    }

    #[test]
    fn edits_make_it_dirty_and_saving_cleans() {
        let mut m = EditorModel::new();
        m.form.name = "My Pack".into();
        assert!(m.is_dirty());
        let text = m.current_json();
        m.mark_saved(PathBuf::from("/tmp/MyPack.mmcjson"), text);
        assert!(!m.is_dirty());
        m.table_mut(FileList::Mods).add_row();
        assert!(m.is_dirty());
    }

    #[test]
    fn sorting_and_filtering_never_dirty_the_document() {
        let mut m = EditorModel::new();
        let doc = ModPackDocument::from_json(
            r#"{"packName":"P","packVersion":"1","packMods":[
                {"name":"b","remote":"r1","local":"l1"},{"name":"a","remote":"r2","local":"l2"}]}"#,
        )
        .unwrap();
        m.adopt(doc, None);
        assert!(!m.is_dirty());
        let t = m.table_mut(FileList::Mods);
        t.click_header(crate::table::Column::Name);
        t.set_filter("a");
        assert!(!m.is_dirty());
        let mods = m.document.read_file_list(FileList::Mods);
        assert_eq!(mods[0].name(), "b");
    }

    #[test]
    fn adopting_normalises_before_the_snapshot() {
        let mut m = EditorModel::new();
        let doc = ModPackDocument::from_json(r#"{"packName":"P"}"#).unwrap();
        m.adopt(doc, Some(PathBuf::from("x.mmcjson")));
        assert!(!m.is_dirty());
        assert!(m.saved_snapshot.contains("packModLoader"));
        assert_eq!(m.suggested_file_name().as_deref(), Some("x.mmcjson"));
    }

    #[test]
    fn bump_validate_and_diff() {
        let mut m = EditorModel::new();
        assert_eq!(m.bump(1), "1.1.0");
        assert_eq!(m.bump(0), "2.0.0");
        let issues = m.validate();
        assert_eq!(issues.len(), 1, "{issues:?}");
        assert!(!issues[0].contains('\n'));
        let d = m.diff().unwrap();
        assert!(d.contains("- \"packVersion\": \"1.0.0\","), "{d}");
        assert!(d.contains("+ \"packVersion\": \"2.0.0\","), "{d}");
        m.form.min_ram = "two".into();
        assert_eq!(m.validate().len(), 2);
        let body = validation_body(&["a".into(), "b".into()]);
        assert!(body.ends_with("a\nb\n"));
    }

    #[test]
    fn no_diff_when_clean() {
        let mut m = EditorModel::new();
        assert_eq!(m.diff(), None);
    }

    #[test]
    fn suggested_names_and_extensions() {
        let mut m = EditorModel::new();
        assert_eq!(m.suggested_file_name(), None);
        m.form.name = "My Pack!".into();
        assert_eq!(m.suggested_file_name().as_deref(), Some("MyPack.mmcjson"));
        assert_eq!(
            with_default_extension(PathBuf::from("a/b")),
            PathBuf::from("a/b.mmcjson")
        );
        assert_eq!(
            with_default_extension(PathBuf::from("a/b.json")),
            PathBuf::from("a/b.json")
        );
    }

    #[test]
    fn parse_errors_and_file_urls() {
        assert!(parse_document("").is_err());
        assert!(parse_document("[1]").is_err());
        assert!(parse_document("{}").is_ok());
        assert_eq!(file_url_path("https://x/y"), None);
        #[cfg(windows)]
        assert_eq!(
            file_url_path("file:///C:/packs/created-x.json"),
            Some(PathBuf::from("C:\\packs\\created-x.json"))
        );
        #[cfg(not(windows))]
        assert_eq!(
            file_url_path("file:///packs/created-x.json"),
            Some(PathBuf::from("/packs/created-x.json"))
        );
        assert_eq!(entry_count(3), tf!("editor.entryCount", "3"));
    }
}
