// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Native file dialogs with a title and file-type filters (JavaFX `FileChooser`).
//!
//! GPUI's `prompt_for_paths` / `prompt_for_new_path` take neither a title nor filters, so these
//! go through `rfd`: `IFileOpenDialog` / `IFileSaveDialog` on Windows, `NSOpenPanel` /
//! `NSSavePanel` (as a sheet on the parent) on macOS, and the xdg-desktop-portal FileChooser on
//! Linux (parented on X11 and on Wayland through xdg-foreign).
//!
//! Build the dialog and start it **synchronously** in the event handler (it reads the parent
//! window's handle there), then await the returned future from `cx.spawn`:
//!
//! ```ignore
//! let pick = OpenDialog::new(t!("dialog.fileChooser.openModpack.title"))
//!     .filter(FileFilter::modpack())
//!     .filter(FileFilter::legacy_json_modpack())
//!     .pick_file(Some(window));
//! cx.spawn(async move |this, cx| {
//!     if let Some(path) = pick.await { /* ... */ }
//! })
//! .detach();
//! ```
//!
//! The futures are `'static` and resolve to `None` / an empty list when the user cancels.

use std::future::Future;
use std::path::{Path, PathBuf};

use gpui::Window;
use mmcl_i18n::t;

/// One file-type filter: a label and its extensions (lower case, without the dot).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileFilter {
    /// The label shown in the type list.
    pub name: String,
    /// Extensions without the dot; `"*"` matches every file.
    pub extensions: Vec<String>,
}

/// `"*.json"`, `".json"`, `"JSON"` -> `"json"`; `"*"` / `"*.*"` -> `"*"`; blank -> `None`.
pub fn normalize_extension(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    if trimmed == "*" || trimmed == "*.*" {
        return Some("*".to_string());
    }
    let ext = trimmed
        .trim_start_matches('*')
        .trim_start_matches('.')
        .trim();
    (!ext.is_empty()).then(|| ext.to_ascii_lowercase())
}

impl FileFilter {
    /// A filter; extensions may be given as globs (`*.json`), dotted (`.json`) or bare.
    pub fn new(name: impl Into<String>, extensions: &[&str]) -> FileFilter {
        let mut exts: Vec<String> = Vec::new();
        for e in extensions.iter().filter_map(|e| normalize_extension(e)) {
            if !exts.contains(&e) {
                exts.push(e);
            }
        }
        FileFilter {
            name: name.into(),
            extensions: exts,
        }
    }

    /// "Mica Minecraft Modpack" (`*.mmcjson`), the editor's primary type.
    pub fn modpack() -> FileFilter {
        use mmcl_core::consts::modpack::{MODPACK_FILE_DESCRIPTION, MODPACK_FILE_GLOB};
        FileFilter::new(MODPACK_FILE_DESCRIPTION, &[MODPACK_FILE_GLOB])
    }

    /// "Legacy JSON Modpack" (`*.json`), the editor's secondary type.
    pub fn legacy_json_modpack() -> FileFilter {
        FileFilter::new(t!("dialog.fileChooser.legacyJsonModpack.filter"), &["json"])
    }

    /// "JSON Files" (`*.json`): settings export / import, hosting manifests.
    pub fn json() -> FileFilter {
        FileFilter::new(t!("dialog.fileChooser.json.filter"), &["json"])
    }

    /// "ZIP archive" (`*.zip`): modpack export.
    pub fn zip_archive() -> FileFilter {
        FileFilter::new(t!("dialog.fileChooser.zipArchive.filter"), &["zip"])
    }

    /// "Mica Modpack ZIP" (`*.zip`): modpack import.
    pub fn mica_modpack_zip() -> FileFilter {
        FileFilter::new(t!("dialog.fileChooser.micaModpackZip.filter"), &["zip"])
    }

    /// "All files" (`*`).
    pub fn all_files() -> FileFilter {
        FileFilter::new(t!("dialog.fileChooser.allFiles.filter"), &["*"])
    }

    /// Whether this filter matches every file.
    pub fn is_wildcard(&self) -> bool {
        self.extensions.is_empty() || self.extensions.iter().any(|e| e == "*")
    }

    /// Whether `path` has one of this filter's extensions (case-insensitive).
    pub fn matches(&self, path: &Path) -> bool {
        if self.is_wildcard() {
            return true;
        }
        match path.extension().and_then(|e| e.to_str()) {
            Some(ext) => self.extensions.iter().any(|e| e.eq_ignore_ascii_case(ext)),
            None => false,
        }
    }
}

/// A save path with the expected extension: kept when it already matches any filter, else the
/// first filter's first extension is appended (so `My Pack 1.12.2` saves as
/// `My Pack 1.12.2.mmcjson`, not as a file of type `2`). Wildcard-only filters keep the path.
pub fn ensure_extension(path: PathBuf, filters: &[FileFilter]) -> PathBuf {
    let Some(first) = filters
        .iter()
        .find(|f| !f.is_wildcard())
        .and_then(|f| f.extensions.first())
    else {
        return path;
    };
    if filters.iter().any(|f| f.is_wildcard() || f.matches(&path)) {
        return path;
    }
    let mut name = path
        .file_name()
        .map(|n| n.to_os_string())
        .unwrap_or_default();
    if name.is_empty() {
        return path;
    }
    name.push(".");
    name.push(first);
    path.with_file_name(name)
}

/// A default file name safe on every OS: characters Windows forbids (`<>:"/\|?*`) and control
/// characters become `_`, trailing dots and spaces are trimmed, and a reserved device name
/// (`CON`, `NUL`, `COM1`, ...) gets a `_` prefix. `None` when nothing usable is left.
pub fn sanitize_file_name(name: &str) -> Option<String> {
    let mapped: String = name
        .chars()
        .map(|c| {
            if c.is_control() || matches!(c, '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*') {
                '_'
            } else {
                c
            }
        })
        .collect();
    let trimmed = mapped.trim().trim_end_matches(['.', ' ']).to_string();
    if trimmed.is_empty() || trimmed.chars().all(|c| c == '_' || c == '.') {
        return None;
    }
    let stem = trimmed.split('.').next().unwrap_or("").to_ascii_uppercase();
    let reserved = matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || ((stem.starts_with("COM") || stem.starts_with("LPT"))
            && stem.len() == 4
            && stem.as_bytes()[3].is_ascii_digit());
    Some(if reserved {
        format!("_{trimmed}")
    } else {
        trimmed
    })
}

fn add_filters(mut dialog: rfd::AsyncFileDialog, filters: &[FileFilter]) -> rfd::AsyncFileDialog {
    for f in filters {
        dialog = dialog.add_filter(f.name.clone(), f.extensions.as_slice());
    }
    dialog
}

fn base_dialog(
    title: &Option<String>,
    directory: &Option<PathBuf>,
    filters: &[FileFilter],
    parent: Option<&Window>,
) -> rfd::AsyncFileDialog {
    let mut dialog = rfd::AsyncFileDialog::new();
    if let Some(title) = title {
        dialog = dialog.set_title(title.clone());
    }
    if let Some(dir) = directory.as_ref().filter(|d| d.is_dir()) {
        dialog = dialog.set_directory(dir);
    }
    if let Some(window) = parent {
        dialog = dialog.set_parent(window);
    }
    add_filters(dialog, filters)
}

/// An open-file dialog.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct OpenDialog {
    /// The window title.
    pub title: Option<String>,
    /// The folder it opens in (ignored when it does not exist).
    pub directory: Option<PathBuf>,
    /// The type filters, the first being the default selection.
    pub filters: Vec<FileFilter>,
}

impl OpenDialog {
    /// A dialog with a title.
    pub fn new(title: impl Into<String>) -> OpenDialog {
        OpenDialog {
            title: Some(title.into()),
            ..OpenDialog::default()
        }
    }

    /// Opens in `dir`.
    pub fn directory(mut self, dir: impl Into<PathBuf>) -> OpenDialog {
        self.directory = Some(dir.into());
        self
    }

    /// Adds a type filter.
    pub fn filter(mut self, filter: FileFilter) -> OpenDialog {
        self.filters.push(filter);
        self
    }

    /// Shows the dialog (parented on `parent` when given) for one file.
    pub fn pick_file(
        self,
        parent: Option<&Window>,
    ) -> impl Future<Output = Option<PathBuf>> + use<> {
        let dialog = base_dialog(&self.title, &self.directory, &self.filters, parent);
        let fut = dialog.pick_file();
        async move { fut.await.map(|h| h.path().to_path_buf()) }
    }

    /// Shows the dialog for several files (empty on cancel).
    pub fn pick_files(self, parent: Option<&Window>) -> impl Future<Output = Vec<PathBuf>> + use<> {
        let dialog = base_dialog(&self.title, &self.directory, &self.filters, parent);
        let fut = dialog.pick_files();
        async move {
            fut.await
                .unwrap_or_default()
                .into_iter()
                .map(|h| h.path().to_path_buf())
                .collect()
        }
    }

    /// Shows a folder picker with this title and directory (filters do not apply).
    pub fn pick_folder(
        self,
        parent: Option<&Window>,
    ) -> impl Future<Output = Option<PathBuf>> + use<> {
        let dialog = base_dialog(&self.title, &self.directory, &[], parent);
        let fut = dialog.pick_folder();
        async move { fut.await.map(|h| h.path().to_path_buf()) }
    }
}

/// A save-file dialog.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SaveDialog {
    /// The window title.
    pub title: Option<String>,
    /// The folder it opens in (ignored when it does not exist).
    pub directory: Option<PathBuf>,
    /// The suggested file name (sanitised when shown).
    pub file_name: Option<String>,
    /// The type filters; the first one's extension is added when the user types none.
    pub filters: Vec<FileFilter>,
}

impl SaveDialog {
    /// A dialog with a title.
    pub fn new(title: impl Into<String>) -> SaveDialog {
        SaveDialog {
            title: Some(title.into()),
            ..SaveDialog::default()
        }
    }

    /// Opens in `dir`.
    pub fn directory(mut self, dir: impl Into<PathBuf>) -> SaveDialog {
        self.directory = Some(dir.into());
        self
    }

    /// Suggests `name` (JavaFX `setInitialFileName`).
    pub fn file_name(mut self, name: impl Into<String>) -> SaveDialog {
        self.file_name = Some(name.into());
        self
    }

    /// Adds a type filter.
    pub fn filter(mut self, filter: FileFilter) -> SaveDialog {
        self.filters.push(filter);
        self
    }

    /// The suggested name as it is shown: sanitised, with the first filter's extension.
    pub fn suggested_name(&self) -> Option<String> {
        let name = sanitize_file_name(self.file_name.as_deref()?)?;
        let with_ext = ensure_extension(PathBuf::from(&name), &self.filters);
        Some(with_ext.to_string_lossy().into_owned())
    }

    /// Shows the dialog (parented on `parent` when given). The chosen path gets the first
    /// filter's extension when it has none of the filters' extensions.
    pub fn save(self, parent: Option<&Window>) -> impl Future<Output = Option<PathBuf>> + use<> {
        let mut dialog = base_dialog(&self.title, &self.directory, &self.filters, parent);
        if let Some(name) = self.suggested_name() {
            dialog = dialog.set_file_name(name);
        }
        let fut = dialog.save_file();
        let filters = self.filters;
        async move {
            fut.await
                .map(|h| ensure_extension(h.path().to_path_buf(), &filters))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extensions_normalise() {
        assert_eq!(normalize_extension("*.JSON").as_deref(), Some("json"));
        assert_eq!(normalize_extension(".mmcjson").as_deref(), Some("mmcjson"));
        assert_eq!(normalize_extension(" zip ").as_deref(), Some("zip"));
        assert_eq!(normalize_extension("*").as_deref(), Some("*"));
        assert_eq!(normalize_extension("*.*").as_deref(), Some("*"));
        assert_eq!(normalize_extension(""), None);
        assert_eq!(normalize_extension("*."), None);
    }

    #[test]
    fn filters_dedupe_and_match_case_insensitively() {
        let f = FileFilter::new("JSON", &["*.json", ".JSON", "json5"]);
        assert_eq!(f.extensions, vec!["json", "json5"]);
        assert!(f.matches(Path::new("a/settings.JSON")));
        assert!(!f.matches(Path::new("a/settings.zip")));
        assert!(!f.matches(Path::new("a/settings")));
        assert!(FileFilter::new("all", &["*"]).matches(Path::new("x")));
    }

    #[test]
    fn presets_carry_the_java_labels_and_types() {
        let m = FileFilter::modpack();
        assert_eq!(m.name, "Mica Minecraft Modpack");
        assert_eq!(m.extensions, vec!["mmcjson"]);
        assert_eq!(FileFilter::legacy_json_modpack().extensions, vec!["json"]);
        assert_eq!(FileFilter::json().extensions, vec!["json"]);
        assert_eq!(FileFilter::zip_archive().extensions, vec!["zip"]);
        assert_eq!(FileFilter::mica_modpack_zip().extensions, vec!["zip"]);
        assert!(FileFilter::all_files().is_wildcard());
        for key in [
            "dialog.fileChooser.legacyJsonModpack.filter",
            "dialog.fileChooser.json.filter",
            "dialog.fileChooser.zipArchive.filter",
            "dialog.fileChooser.micaModpackZip.filter",
            "dialog.fileChooser.allFiles.filter",
        ] {
            assert!(mmcl_i18n::contains(key), "missing {key}");
        }
    }

    #[test]
    fn save_paths_get_the_expected_extension() {
        let editor = [FileFilter::modpack(), FileFilter::legacy_json_modpack()];
        assert_eq!(
            ensure_extension(PathBuf::from("/p/My Pack 1.12.2"), &editor),
            PathBuf::from("/p/My Pack 1.12.2.mmcjson")
        );
        assert_eq!(
            ensure_extension(PathBuf::from("/p/pack.json"), &editor),
            PathBuf::from("/p/pack.json"),
            "a secondary filter's extension is kept"
        );
        assert_eq!(
            ensure_extension(PathBuf::from("/p/pack.MMCJSON"), &editor),
            PathBuf::from("/p/pack.MMCJSON")
        );
        assert_eq!(
            ensure_extension(PathBuf::from("/p/notes"), &[]),
            PathBuf::from("/p/notes")
        );
        assert_eq!(
            ensure_extension(
                PathBuf::from("/p/notes.txt"),
                &[FileFilter::json(), FileFilter::all_files()]
            ),
            PathBuf::from("/p/notes.txt"),
            "an all-files filter accepts any name"
        );
    }

    #[test]
    fn default_names_are_sanitised() {
        assert_eq!(
            sanitize_file_name("Pack: Reloaded?").as_deref(),
            Some("Pack_ Reloaded_")
        );
        assert_eq!(sanitize_file_name("a/b\\c").as_deref(), Some("a_b_c"));
        assert_eq!(sanitize_file_name("name. . ").as_deref(), Some("name"));
        assert_eq!(sanitize_file_name("con").as_deref(), Some("_con"));
        assert_eq!(
            sanitize_file_name("COM3.json").as_deref(),
            Some("_COM3.json")
        );
        assert_eq!(sanitize_file_name("COMMON").as_deref(), Some("COMMON"));
        assert_eq!(sanitize_file_name("  "), None);
        assert_eq!(sanitize_file_name("???"), None);
    }

    #[test]
    fn suggested_save_names_carry_the_extension() {
        let d = SaveDialog::new("Export")
            .file_name("My Pack-1700000000000.zip")
            .filter(FileFilter::zip_archive());
        assert_eq!(
            d.suggested_name().as_deref(),
            Some("My Pack-1700000000000.zip")
        );
        let d = SaveDialog::new("Save")
            .file_name("Pack 1.0")
            .filter(FileFilter::modpack());
        assert_eq!(d.suggested_name().as_deref(), Some("Pack 1.0.mmcjson"));
        assert_eq!(SaveDialog::new("x").suggested_name(), None);
    }

    #[test]
    fn builders_collect_their_options() {
        let d = OpenDialog::new("Open")
            .directory("/nowhere")
            .filter(FileFilter::json());
        assert_eq!(d.title.as_deref(), Some("Open"));
        assert_eq!(d.directory, Some(PathBuf::from("/nowhere")));
        assert_eq!(d.filters.len(), 1);
    }
}
