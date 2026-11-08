// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The per-pack update history (Java `ModPackUpdateLog`): `.last_seen_remote_version` holds the
//! last `packVersion` seen on the network, `.update_log.txt` one `epochMs|old|new` line per
//! change.

use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::path::Path;

use mmcl_i18n::tf;

use crate::pack::{GameModPack, PackKind};

/// The tracker file.
pub const LAST_SEEN_VERSION_FILE: &str = ".last_seen_remote_version";
/// The log file.
pub const UPDATE_LOG_FILE: &str = ".update_log.txt";
/// Lines kept in the log.
pub const MAX_ENTRIES: usize = 200;

const LINE_SEP: &str = if cfg!(windows) { "\r\n" } else { "\n" };

/// One version change.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Entry {
    pub ts_ms: i64,
    pub old: String,
    pub new: String,
}

impl Entry {
    /// `ts|old|new` (three parts, numeric timestamp), else `None`.
    pub fn parse(line: &str) -> Option<Entry> {
        let t = line.trim();
        if t.is_empty() {
            return None;
        }
        let mut parts = t.splitn(3, '|');
        let ts = parts.next()?.parse::<i64>().ok()?;
        let old = parts.next()?.to_string();
        let new = parts.next()?.to_string();
        Some(Entry {
            ts_ms: ts,
            old,
            new,
        })
    }

    /// The local date of the change, `yyyy-MM-dd`.
    pub fn format_timestamp(&self) -> String {
        use chrono::TimeZone;
        chrono::Local
            .timestamp_millis_opt(self.ts_ms)
            .single()
            .map(|d| d.format("%Y-%m-%d").to_string())
            .unwrap_or_default()
    }
}

fn sanitize(v: &str) -> String {
    v.replace('|', "/").replace(['\n', '\r'], " ")
}

/// Records that `version` was seen for the pack at `root`. Errors are logged, never returned.
pub fn record_version_seen_at(root: &Path, version: &str, pack_name: &str) {
    if version.trim().is_empty() {
        return;
    }
    // One lock per pack: the per-path lock of the tracker file.
    let lock = mmcl_core::filelock::file_lock(&root.join(LAST_SEEN_VERSION_FILE));
    let _g = lock.lock();
    let res = (|| -> io::Result<()> {
        fs::create_dir_all(root)?;
        let tracker = root.join(LAST_SEEN_VERSION_FILE);
        let last = fs::read_to_string(&tracker)
            .ok()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty());
        let Some(last) = last else {
            return fs::write(&tracker, version);
        };
        if last == version.trim() {
            return Ok(());
        }
        let log = root.join(UPDATE_LOG_FILE);
        let line = format!(
            "{}|{}|{}{LINE_SEP}",
            chrono::Utc::now().timestamp_millis(),
            sanitize(&last),
            sanitize(version)
        );
        OpenOptions::new()
            .create(true)
            .append(true)
            .open(&log)?
            .write_all(line.as_bytes())?;
        fs::write(&tracker, version)?;
        let text = fs::read_to_string(&log)?;
        let lines: Vec<&str> = text.lines().collect();
        if lines.len() > MAX_ENTRIES {
            let mut out = lines[lines.len() - MAX_ENTRIES..].join(LINE_SEP);
            out.push_str(LINE_SEP);
            fs::write(&log, out)?;
        }
        Ok(())
    })();
    if let Err(e) = res {
        mmcl_log::warn_silent(&tf!(
            "log.modPackUpdateLog.writeFailed",
            pack_name,
            e.to_string()
        ));
    }
}

/// Records the remote version of a freshly fetched pack. Skips vanilla, failed and stub packs
/// (by kind: the Java check compared against the English "Error" text, so failed packs were
/// recorded in other languages) and blank versions.
pub fn record_remote_version_seen(pack: &GameModPack) {
    if !matches!(pack.kind(), PackKind::Manifest) {
        return;
    }
    let Some(version) = pack.manifest().pack_version.as_deref() else {
        return;
    };
    if pack.sanitized_name().is_empty() {
        return;
    }
    record_version_seen_at(&pack.pack_root(), version, &pack.name());
}

/// The log entries at `root`, newest first.
pub fn read_entries_at(root: &Path, pack_name: &str) -> Vec<Entry> {
    let log = root.join(UPDATE_LOG_FILE);
    if !log.exists() {
        return Vec::new();
    }
    match fs::read_to_string(&log) {
        Ok(t) => {
            let mut v: Vec<Entry> = t.lines().filter_map(Entry::parse).collect();
            v.reverse();
            v
        }
        Err(e) => {
            mmcl_log::warn_silent(&tf!(
                "log.modPackUpdateLog.readFailed",
                pack_name,
                e.to_string()
            ));
            Vec::new()
        }
    }
}

/// The pack's update log, newest first.
pub fn read_entries(pack: &GameModPack) -> Vec<Entry> {
    read_entries_at(&pack.pack_root(), &pack.name())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_sighting_seeds_then_changes_append() {
        let d = tempfile::tempdir().unwrap();
        let r = d.path();
        record_version_seen_at(r, "1.0", "P");
        assert_eq!(
            fs::read_to_string(r.join(LAST_SEEN_VERSION_FILE)).unwrap(),
            "1.0"
        );
        assert!(read_entries_at(r, "P").is_empty());
        record_version_seen_at(r, "1.0", "P");
        assert!(read_entries_at(r, "P").is_empty());
        record_version_seen_at(r, "1.1|x\ny", "P");
        record_version_seen_at(r, "1.2", "P");
        let e = read_entries_at(r, "P");
        assert_eq!(e.len(), 2);
        assert_eq!((e[0].old.as_str(), e[0].new.as_str()), ("1.1/x y", "1.2"));
        assert_eq!((e[1].old.as_str(), e[1].new.as_str()), ("1.0", "1.1/x y"));
        assert!(!e[0].format_timestamp().is_empty());
    }

    #[test]
    fn trims_to_max_entries() {
        let d = tempfile::tempdir().unwrap();
        for i in 0..(MAX_ENTRIES + 20) {
            record_version_seen_at(d.path(), &i.to_string(), "P");
        }
        assert_eq!(read_entries_at(d.path(), "P").len(), MAX_ENTRIES);
    }

    #[test]
    fn parse_rules() {
        assert_eq!(
            Entry::parse(" 5|a|b|c "),
            Some(Entry {
                ts_ms: 5,
                old: "a".into(),
                new: "b|c".into()
            })
        );
        assert!(Entry::parse("x|a|b").is_none());
        assert!(Entry::parse("5|a").is_none());
        assert!(Entry::parse("  ").is_none());
    }
}
