// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The content browser's folder work (Java `ModpackContentBrowser`): listing worlds, packs,
//! screenshots, crash reports and mods, folder sizes, the mod enable/disable rename, deleting an
//! entry and reading a crash report. Every function here does blocking I/O: call them on the
//! background executor, never on the UI thread.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;

/// What a section lists.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ContentKind {
    /// `saves/` folders, newest first.
    Worlds,
    /// `resourcepacks/` entries by name.
    ResourcePacks,
    /// `shaderpacks/` entries by name.
    ShaderPacks,
    /// `screenshots/` PNG / JPEG files, newest first.
    Screenshots,
    /// `crash-reports/*.txt`, newest first.
    CrashReports,
    /// `mods/*.jar` and `*.jar.disabled` by name (a disabled jar sorts next to its name).
    Mods,
}

impl ContentKind {
    /// The pack sub-folder.
    pub fn folder(self) -> &'static str {
        match self {
            ContentKind::Worlds => "saves",
            ContentKind::ResourcePacks => "resourcepacks",
            ContentKind::ShaderPacks => "shaderpacks",
            ContentKind::Screenshots => "screenshots",
            ContentKind::CrashReports => "crash-reports",
            ContentKind::Mods => "mods",
        }
    }
}

/// One listed file or folder.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub name: String,
    pub path: PathBuf,
    pub is_dir: bool,
    /// File size, or the folder's total once computed.
    pub size: Option<u64>,
    /// Last modified, ms since the epoch (0 when unknown).
    pub modified_ms: i64,
}

const DISABLED_SUFFIX: &str = ".disabled";

/// Whether a mod file name is a disabled jar (`x.jar.disabled`, any case).
pub fn is_disabled_jar(name: &str) -> bool {
    name.to_lowercase().ends_with(".jar.disabled")
}

/// Whether a file name is a mod jar, enabled or disabled.
pub fn is_mod_file(name: &str) -> bool {
    let l = name.to_lowercase();
    l.ends_with(".jar") || l.ends_with(".jar.disabled")
}

/// The name a mod row shows: a disabled jar without `.disabled`.
pub fn mod_display_name(name: &str) -> &str {
    if is_disabled_jar(name) {
        &name[..name.len() - DISABLED_SUFFIX.len()]
    } else {
        name
    }
}

/// The file name after enabling / disabling: `x.jar` <-> `x.jar.disabled`.
pub fn toggled_name(name: &str) -> String {
    if is_disabled_jar(name) {
        mod_display_name(name).to_string()
    } else {
        format!("{name}{DISABLED_SUFFIX}")
    }
}

fn wants(kind: ContentKind, name: &str, is_dir: bool, is_file: bool) -> bool {
    let lower = name.to_lowercase();
    match kind {
        ContentKind::Worlds => is_dir,
        ContentKind::ResourcePacks | ContentKind::ShaderPacks => {
            !name.starts_with('.') && (is_dir || is_file)
        }
        ContentKind::Screenshots => {
            is_file
                && (lower.ends_with(".png") || lower.ends_with(".jpg") || lower.ends_with(".jpeg"))
        }
        ContentKind::CrashReports => is_file && name.ends_with(".txt"),
        ContentKind::Mods => is_file && is_mod_file(name),
    }
}

fn modified_ms(meta: &fs::Metadata) -> i64 {
    meta.modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// Lists `<root>/<kind folder>` (empty when it is missing), sorted for the section.
pub fn scan(root: &Path, kind: ContentKind) -> Vec<Entry> {
    let dir = root.join(kind.folder());
    let Ok(read) = fs::read_dir(&dir) else {
        return Vec::new();
    };
    let mut out: Vec<Entry> = read
        .filter_map(Result::ok)
        .filter_map(|e| {
            let name = e.file_name().to_string_lossy().into_owned();
            let meta = e.metadata().ok()?;
            if !wants(kind, &name, meta.is_dir(), meta.is_file()) {
                return None;
            }
            Some(Entry {
                path: e.path(),
                is_dir: meta.is_dir(),
                size: meta.is_file().then_some(meta.len()),
                modified_ms: modified_ms(&meta),
                name,
            })
        })
        .collect();
    sort(kind, &mut out);
    out
}

/// The section's order.
pub fn sort(kind: ContentKind, entries: &mut [Entry]) {
    match kind {
        ContentKind::Worlds | ContentKind::Screenshots | ContentKind::CrashReports => {
            entries.sort_by_key(|e| std::cmp::Reverse(e.modified_ms))
        }
        ContentKind::ResourcePacks | ContentKind::ShaderPacks => {
            entries.sort_by_key(|e| e.name.to_lowercase())
        }
        ContentKind::Mods => entries.sort_by(|a, b| {
            let ka = mod_display_name(&a.name).to_lowercase();
            let kb = mod_display_name(&b.name).to_lowercase();
            ka.cmp(&kb).then(a.name.len().cmp(&b.name.len()))
        }),
    }
}

/// The number of enabled jars in `<root>/mods` (the mod-count chip).
pub fn enabled_mod_count(root: &Path) -> usize {
    scan(root, ContentKind::Mods)
        .iter()
        .filter(|e| !is_disabled_jar(&e.name))
        .count()
}

/// At most this many entries are visited when sizing a folder.
pub const SIZE_ENTRY_BUDGET: usize = 200_000;

/// The total size of the regular files under `dir` (unreadable entries are skipped; the walk
/// stops after [`SIZE_ENTRY_BUDGET`] entries). Symbolic links are not followed.
pub fn directory_size(dir: &Path) -> u64 {
    let mut total = 0u64;
    let mut seen = 0usize;
    let mut stack = vec![dir.to_path_buf()];
    while let Some(d) = stack.pop() {
        let Ok(read) = fs::read_dir(&d) else {
            continue;
        };
        for e in read.filter_map(Result::ok) {
            seen += 1;
            if seen > SIZE_ENTRY_BUDGET {
                return total;
            }
            let Ok(ft) = e.file_type() else { continue };
            if ft.is_dir() {
                stack.push(e.path());
            } else if ft.is_file()
                && let Ok(m) = e.metadata()
            {
                total += m.len();
            }
        }
    }
    total
}

/// Why a mod toggle did not happen.
#[derive(Debug)]
pub enum ToggleError {
    /// A file with the target name already exists (`toggleModConflict`).
    TargetExists(String),
    /// The rename failed (the game holds the file).
    Io(io::Error),
}

/// Enables or disables a mod by renaming it in place; the new file name.
pub fn toggle_mod(path: &Path) -> Result<PathBuf, ToggleError> {
    let name = path
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let target = path.with_file_name(toggled_name(&name));
    if target.exists() {
        return Err(ToggleError::TargetExists(
            target
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_default(),
        ));
    }
    fs::rename(path, &target).map_err(ToggleError::Io)?;
    Ok(target)
}

/// Deletes a file, or a folder with everything in it.
pub fn delete_entry(path: &Path) -> io::Result<()> {
    let meta = fs::symlink_metadata(path)?;
    if meta.is_dir() {
        fs::remove_dir_all(path)
    } else {
        fs::remove_file(path)
    }
}

/// The windows-1252 characters for bytes 0x80..=0x9F (the rest is Latin-1).
const CP1252_HIGH: [char; 32] = [
    '\u{20AC}', '\u{FFFD}', '\u{201A}', '\u{0192}', '\u{201E}', '\u{2026}', '\u{2020}', '\u{2021}',
    '\u{02C6}', '\u{2030}', '\u{0160}', '\u{2039}', '\u{0152}', '\u{FFFD}', '\u{017D}', '\u{FFFD}',
    '\u{FFFD}', '\u{2018}', '\u{2019}', '\u{201C}', '\u{201D}', '\u{2022}', '\u{2013}', '\u{2014}',
    '\u{02DC}', '\u{2122}', '\u{0161}', '\u{203A}', '\u{0153}', '\u{FFFD}', '\u{017E}', '\u{0178}',
];

/// A crash report's text (`readReportText`): UTF-8 when it decodes cleanly, else windows-1252
/// (older Forge wrote reports in the platform charset).
pub fn decode_report(bytes: &[u8]) -> String {
    match std::str::from_utf8(bytes) {
        Ok(s) => s.to_string(),
        Err(_) => bytes
            .iter()
            .map(|&b| match b {
                0x80..=0x9F => CP1252_HIGH[(b - 0x80) as usize],
                _ => b as char,
            })
            .collect(),
    }
}

/// Reads a crash report.
pub fn read_report(path: &Path) -> io::Result<String> {
    fs::read(path).map(|b| decode_report(&b))
}

/// The image format of a screenshot file, by extension.
pub fn image_format(path: &Path) -> Option<gpui::ImageFormat> {
    let ext = path.extension()?.to_string_lossy().to_lowercase();
    match ext.as_str() {
        "png" => Some(gpui::ImageFormat::Png),
        "jpg" | "jpeg" => Some(gpui::ImageFormat::Jpeg),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn touch(p: &Path, bytes: usize) {
        fs::write(p, vec![b'x'; bytes]).unwrap();
    }

    #[test]
    fn mod_names_round_trip() {
        assert!(is_disabled_jar("A.JAR.disabled"));
        assert!(!is_disabled_jar("a.jar"));
        assert_eq!(mod_display_name("a.jar.disabled"), "a.jar");
        assert_eq!(mod_display_name("a.jar"), "a.jar");
        assert_eq!(toggled_name("a.jar"), "a.jar.disabled");
        assert_eq!(toggled_name("a.jar.disabled"), "a.jar");
        assert!(is_mod_file("x.JAR"));
        assert!(!is_mod_file("x.zip"));
    }

    #[test]
    fn scans_filter_and_sort() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        let mods = root.join("mods");
        fs::create_dir_all(&mods).unwrap();
        touch(&mods.join("b.jar"), 3);
        touch(&mods.join("a.jar.disabled"), 1);
        touch(&mods.join("A2.jar"), 1);
        touch(&mods.join("readme.txt"), 1);
        let names: Vec<_> = scan(root, ContentKind::Mods)
            .into_iter()
            .map(|e| e.name)
            .collect();
        assert_eq!(names, vec!["a.jar.disabled", "A2.jar", "b.jar"]);
        assert_eq!(enabled_mod_count(root), 2);

        let rp = root.join("resourcepacks");
        fs::create_dir_all(rp.join("Folder")).unwrap();
        touch(&rp.join("b.zip"), 1);
        touch(&rp.join(".hidden"), 1);
        let names: Vec<_> = scan(root, ContentKind::ResourcePacks)
            .into_iter()
            .map(|e| e.name)
            .collect();
        assert_eq!(names, vec!["b.zip", "Folder"]);

        let shots = root.join("screenshots");
        fs::create_dir_all(&shots).unwrap();
        touch(&shots.join("a.PNG"), 1);
        touch(&shots.join("b.gif"), 1);
        assert_eq!(scan(root, ContentKind::Screenshots).len(), 1);

        let crashes = root.join("crash-reports");
        fs::create_dir_all(&crashes).unwrap();
        touch(&crashes.join("crash-1.txt"), 1);
        touch(&crashes.join("notes.log"), 1);
        assert_eq!(scan(root, ContentKind::CrashReports).len(), 1);

        assert!(scan(root, ContentKind::Worlds).is_empty(), "missing folder");
    }

    #[test]
    fn newest_first_sort() {
        let e = |n: &str, m: i64| Entry {
            name: n.into(),
            path: PathBuf::from(n),
            is_dir: true,
            size: None,
            modified_ms: m,
        };
        let mut v = vec![e("old", 1), e("new", 9), e("mid", 5)];
        sort(ContentKind::Worlds, &mut v);
        let names: Vec<_> = v.iter().map(|e| e.name.as_str()).collect();
        assert_eq!(names, vec!["new", "mid", "old"]);
    }

    #[test]
    fn folder_sizes_add_up() {
        let dir = tempfile::tempdir().unwrap();
        let w = dir.path().join("world");
        fs::create_dir_all(w.join("region")).unwrap();
        touch(&w.join("level.dat"), 10);
        touch(&w.join("region").join("r.0.0.mca"), 32);
        assert_eq!(directory_size(&w), 42);
        assert_eq!(directory_size(&dir.path().join("missing")), 0);
    }

    #[test]
    fn toggling_renames_and_refuses_a_clash() {
        let dir = tempfile::tempdir().unwrap();
        let jar = dir.path().join("m.jar");
        touch(&jar, 1);
        let off = toggle_mod(&jar).unwrap();
        assert_eq!(off.file_name().unwrap(), "m.jar.disabled");
        assert!(!jar.exists());
        touch(&jar, 1);
        match toggle_mod(&off) {
            Err(ToggleError::TargetExists(n)) => assert_eq!(n, "m.jar"),
            other => panic!("{other:?}"),
        }
        assert!(off.exists(), "nothing moved");
    }

    #[test]
    fn deleting_files_and_folders() {
        let dir = tempfile::tempdir().unwrap();
        let f = dir.path().join("a.zip");
        touch(&f, 1);
        delete_entry(&f).unwrap();
        assert!(!f.exists());
        let d = dir.path().join("w");
        fs::create_dir_all(d.join("x")).unwrap();
        touch(&d.join("x").join("y"), 1);
        delete_entry(&d).unwrap();
        assert!(!d.exists());
        assert!(delete_entry(&d).is_err());
    }

    #[test]
    fn reports_fall_back_to_cp1252() {
        assert_eq!(decode_report("caf\u{e9}".as_bytes()), "caf\u{e9}");
        assert_eq!(decode_report(&[b'c', b'a', b'f', 0xE9]), "caf\u{e9}");
        assert_eq!(
            decode_report(&[0x80, 0x93, 0x94]),
            "\u{20AC}\u{201C}\u{201D}"
        );
    }

    #[test]
    fn screenshot_formats() {
        assert_eq!(
            image_format(Path::new("a.PNG")),
            Some(gpui::ImageFormat::Png)
        );
        assert_eq!(
            image_format(Path::new("a.jpeg")),
            Some(gpui::ImageFormat::Jpeg)
        );
        assert_eq!(image_format(Path::new("a.gif")), None);
    }
}
