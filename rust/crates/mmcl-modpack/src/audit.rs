// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The per-pack re-download audit log (Java `ModPackAuditLog`, `<packRoot>/.audit_log`).
//!
//! One compact JSON object per line, recorded only when an existing file is downloaded again
//! during an audited launch. A file re-downloaded with identical bytes launch after launch has a
//! manifest hash that does not match what its server serves; [`analyze_problems`] finds those.

use std::collections::{BTreeSet, HashMap};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::Path;

use mmcl_core::filelock::file_lock;
use mmcl_core::paths::normalize_lexical;
use mmcl_i18n::tf;
use serde_json::{Map, Value};

/// The audit file's name inside a pack folder.
pub const AUDIT_FILE: &str = ".audit_log";
/// Past this size the file is trimmed before the next append.
pub const MAX_AUDIT_BYTES: u64 = 512 * 1024;
/// Lines kept by a trim.
pub const TRIM_RETAIN_LINES: usize = 2000;
/// Consecutive launches that make a file a problem.
pub const DEFAULT_PROBLEM_THRESHOLD: i32 = 3;

/// The platform line separator Java's `System.lineSeparator()` writes.
const LINE_SEP: &str = if cfg!(windows) { "\r\n" } else { "\n" };

/// A file that was re-downloaded in each of the last `consecutive_launches` launches.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Problem {
    /// Path relative to the pack folder, `/`-separated.
    pub file: String,
    pub consecutive_launches: i32,
    /// Every one of those re-downloads produced the same bytes it replaced.
    pub content_unchanged: bool,
}

/// `full` relative to `root` with `/` separators, or `full` itself (with `/`) when it is not
/// under `root`.
fn relativize(root: &Path, full: &Path) -> String {
    let abs = |p: &Path| normalize_lexical(&std::path::absolute(p).unwrap_or_else(|_| p.into()));
    let (r, f) = (abs(root), abs(full));
    match f.strip_prefix(&r) {
        Ok(rel) => rel.to_string_lossy().replace('\\', "/"),
        Err(_) => full.to_string_lossy().replace('\\', "/"),
    }
}

/// Records one re-download. Never fails the caller; a write failure is logged.
pub fn record_redownload(
    pack_root: &Path,
    launch_id: i32,
    full_local_path: &Path,
    old_hash: Option<&str>,
    new_hash: Option<&str>,
    expected: Option<&str>,
    algo: Option<&str>,
) {
    let changed = matches!((old_hash, new_hash), (Some(o), Some(n)) if !o.eq_ignore_ascii_case(n));
    let mut entry = Map::new();
    entry.insert(
        "ts".into(),
        Value::from(chrono::Utc::now().timestamp_millis()),
    );
    entry.insert("launch".into(), Value::from(launch_id));
    entry.insert(
        "file".into(),
        Value::from(relativize(pack_root, full_local_path)),
    );
    entry.insert("oldHash".into(), Value::from(old_hash.unwrap_or("")));
    entry.insert("newHash".into(), Value::from(new_hash.unwrap_or("")));
    entry.insert("expected".into(), Value::from(expected.unwrap_or("")));
    entry.insert("algo".into(), Value::from(algo.unwrap_or("")));
    entry.insert("changed".into(), Value::Bool(changed));
    let line = mmcl_manifest::gson::to_compact_string(&Value::Object(entry));
    if let Err(e) = append_line(pack_root, &line) {
        mmcl_log::warn_silent(&tf!("log.modPackAuditLog.writeFailed", e.to_string()));
    }
}

fn append_line(pack_root: &Path, line: &str) -> std::io::Result<()> {
    let path = pack_root.join(AUDIT_FILE);
    let lock = file_lock(&path);
    let _guard = lock.lock();
    if fs::metadata(&path).is_ok_and(|m| m.len() > MAX_AUDIT_BYTES) {
        trim(&path);
    }
    fs::create_dir_all(pack_root)?;
    let mut f = OpenOptions::new().create(true).append(true).open(&path)?;
    f.write_all(format!("{line}{LINE_SEP}").as_bytes())
}

/// Keeps the newest [`TRIM_RETAIN_LINES`] lines. Errors are ignored (the append still runs).
fn trim(path: &Path) {
    let Ok(text) = fs::read_to_string(path) else {
        return;
    };
    let lines: Vec<&str> = text.lines().collect();
    if lines.len() > TRIM_RETAIN_LINES {
        let keep = &lines[lines.len() - TRIM_RETAIN_LINES..];
        let mut out = keep.join(LINE_SEP);
        out.push_str(LINE_SEP);
        let _ = fs::write(path, out);
    }
}

/// Files re-downloaded in every one of the launches `most_recent, most_recent - 1, ...,
/// most_recent - threshold + 1`, sorted by name ignoring case. Empty when `threshold <= 0`,
/// `most_recent < threshold`, or there is no log. Malformed lines are skipped.
pub fn analyze_problems(pack_root: &Path, most_recent: i32, threshold: i32) -> Vec<Problem> {
    if threshold <= 0 || most_recent < threshold {
        return Vec::new();
    }
    let path = pack_root.join(AUDIT_FILE);
    if !path.exists() {
        return Vec::new();
    }
    let text = match fs::read_to_string(&path) {
        Ok(t) => t,
        Err(e) => {
            mmcl_log::warn_silent(&tf!("log.modPackAuditLog.readFailed", e.to_string()));
            return Vec::new();
        }
    };
    let mut launches: HashMap<String, BTreeSet<i32>> = HashMap::new();
    let mut unchanged: HashMap<String, BTreeSet<i32>> = HashMap::new();
    for line in text.lines().filter(|l| !l.trim().is_empty()) {
        let Ok(Value::Object(o)) = serde_json::from_str::<Value>(line) else {
            continue;
        };
        let (Some(file), Some(launch)) = (
            o.get("file").and_then(mmcl_util::json::as_gson_string),
            o.get("launch")
                .and_then(mmcl_util::json::as_gson_i64)
                .and_then(|v| i32::try_from(v).ok()),
        ) else {
            continue;
        };
        launches.entry(file.clone()).or_default().insert(launch);
        if o.get("changed").and_then(Value::as_bool) == Some(false) {
            unchanged.entry(file).or_default().insert(launch);
        }
    }
    let empty = BTreeSet::new();
    let mut problems: Vec<Problem> = launches
        .iter()
        .filter_map(|(file, set)| {
            let unchanged_set = unchanged.get(file).unwrap_or(&empty);
            let wanted = (0..threshold).map(|i| most_recent - i);
            let mut all_unchanged = true;
            for w in wanted {
                if !set.contains(&w) {
                    return None;
                }
                if !unchanged_set.contains(&w) {
                    all_unchanged = false;
                }
            }
            Some(Problem {
                file: file.clone(),
                consecutive_launches: threshold,
                content_unchanged: all_unchanged,
            })
        })
        .collect();
    problems.sort_by_key(|p| p.file.to_lowercase());
    problems
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rec(root: &Path, launch: i32, file: &str, old: &str, new: &str) {
        record_redownload(
            root,
            launch,
            &root.join(file),
            Some(old),
            Some(new),
            Some("exp"),
            Some("sha1"),
        );
    }

    #[test]
    fn flags_a_three_launch_streak() {
        let d = tempfile::tempdir().unwrap();
        for l in 1..=3 {
            rec(d.path(), l, "mods/jei.jar", "aa", "aa");
        }
        let p = analyze_problems(d.path(), 3, 3);
        assert_eq!(
            p,
            vec![Problem {
                file: "mods/jei.jar".into(),
                consecutive_launches: 3,
                content_unchanged: true
            }]
        );
    }

    #[test]
    fn short_or_stale_streak_not_flagged() {
        let d = tempfile::tempdir().unwrap();
        rec(d.path(), 2, "mods/a.jar", "aa", "aa");
        rec(d.path(), 3, "mods/a.jar", "aa", "aa");
        assert!(analyze_problems(d.path(), 3, 3).is_empty());
        // Stale: launches 1-3 but the newest launch is 5.
        rec(d.path(), 1, "mods/a.jar", "aa", "aa");
        assert!(analyze_problems(d.path(), 5, 3).is_empty());
        assert_eq!(analyze_problems(d.path(), 3, 3).len(), 1);
        assert!(analyze_problems(d.path(), 2, 3).is_empty());
        assert!(analyze_problems(d.path(), 3, 0).is_empty());
    }

    #[test]
    fn content_changed_flag() {
        let d = tempfile::tempdir().unwrap();
        rec(d.path(), 1, "config/x.cfg", "aa", "aa");
        rec(d.path(), 2, "config/x.cfg", "aa", "bb");
        rec(d.path(), 3, "config/x.cfg", "bb", "bb");
        let p = analyze_problems(d.path(), 3, 3);
        assert_eq!(p.len(), 1);
        assert!(!p[0].content_unchanged);
    }

    #[test]
    fn no_log_no_problems_and_malformed_lines_skipped() {
        let d = tempfile::tempdir().unwrap();
        assert!(analyze_problems(d.path(), 10, 3).is_empty());
        fs::write(d.path().join(AUDIT_FILE), "garbage\n{\"file\":1}\n\n").unwrap();
        assert!(analyze_problems(d.path(), 10, 3).is_empty());
    }

    #[test]
    fn two_packs_concurrently_write_their_own_logs() {
        let a = tempfile::tempdir().unwrap();
        let b = tempfile::tempdir().unwrap();
        std::thread::scope(|s| {
            for i in 0..8 {
                let (ra, rb) = (a.path(), b.path());
                s.spawn(move || rec(ra, 1, &format!("mods/a{i}.jar"), "x", "x"));
                s.spawn(move || rec(rb, 2, &format!("mods/b{i}.jar"), "x", "y"));
            }
        });
        let la = fs::read_to_string(a.path().join(AUDIT_FILE)).unwrap();
        let lb = fs::read_to_string(b.path().join(AUDIT_FILE)).unwrap();
        assert_eq!(la.lines().count(), 8);
        assert_eq!(lb.lines().count(), 8);
        assert!(
            la.lines()
                .all(|l| l.contains("\"launch\":1") && l.contains("mods/a"))
        );
        assert!(lb.lines().all(|l| l.contains("\"changed\":true")));
    }

    #[test]
    fn line_shape_and_outside_paths() {
        let d = tempfile::tempdir().unwrap();
        record_redownload(
            d.path(),
            4,
            Path::new("/elsewhere/x.jar"),
            None,
            None,
            None,
            None,
        );
        let text = fs::read_to_string(d.path().join(AUDIT_FILE)).unwrap();
        let v: Value = serde_json::from_str(text.lines().next().unwrap()).unwrap();
        let keys: Vec<&String> = v.as_object().unwrap().keys().collect();
        assert_eq!(
            keys,
            [
                "ts", "launch", "file", "oldHash", "newHash", "expected", "algo", "changed"
            ]
        );
        assert_eq!(v["file"], "/elsewhere/x.jar");
        assert_eq!(v["changed"], false);
        assert_eq!(v["oldHash"], "");
    }

    #[test]
    fn trims_when_over_cap() {
        let d = tempfile::tempdir().unwrap();
        let big: String = (0..3000)
            .map(|i| {
                format!(
                    "{{\"file\":\"f{i}\",\"launch\":1,\"pad\":\"{}\"}}\n",
                    "x".repeat(200)
                )
            })
            .collect();
        fs::write(d.path().join(AUDIT_FILE), big).unwrap();
        rec(d.path(), 2, "mods/z.jar", "a", "a");
        let n = fs::read_to_string(d.path().join(AUDIT_FILE))
            .unwrap()
            .lines()
            .count();
        assert_eq!(n, TRIM_RETAIN_LINES + 1);
    }
}
