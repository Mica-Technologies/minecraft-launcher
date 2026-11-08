// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Small zip-writing helpers shared by backups and export, plus the capped extraction the
//! importers use (Java `BoundedZipExtraction`).

use std::fs::{self, File};
use std::io::{self, Read, Seek, Write};
use std::path::{Path, PathBuf};
use std::time::SystemTime;

use chrono::{Datelike, Local, Timelike};
use mmcl_i18n::tf;
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, DateTime, ZipWriter};

/// Max decompressed bytes of one entry an importer extracts.
pub const MAX_ENTRY_BYTES: u64 = 1024 * 1024 * 1024;
/// Max decompressed bytes of a whole archive an importer extracts.
pub const MAX_TOTAL_BYTES: u64 = 4 * 1024 * 1024 * 1024;
/// Max entries in an imported archive.
pub const MAX_ENTRIES: usize = 200_000;

/// A zip timestamp (local time, like Java's `ZipEntry.setTime`); before 1980 is clamped.
pub fn zip_time(t: SystemTime) -> DateTime {
    let d: chrono::DateTime<Local> = t.into();
    DateTime::from_date_and_time(
        d.year().clamp(1980, 2107) as u16,
        d.month() as u8,
        d.day() as u8,
        d.hour() as u8,
        d.minute() as u8,
        d.second() as u8,
    )
    .unwrap_or_default()
}

/// Deflate options with the given modification time.
pub fn options(t: Option<SystemTime>) -> SimpleFileOptions {
    let o = SimpleFileOptions::default()
        .compression_method(CompressionMethod::Deflated)
        .large_file(false);
    match t {
        Some(t) => o.last_modified_time(zip_time(t)),
        None => o,
    }
}

/// Writes `path` as entry `name`.
pub fn add_file<W: Write + Seek>(
    zip: &mut ZipWriter<W>,
    name: &str,
    path: &Path,
) -> io::Result<u64> {
    // Opened before the entry starts, so an unreadable file fails without a half-written entry.
    add_open_file(zip, name, File::open(path)?)
}

/// Writes an already opened file as entry `name`. A failure here leaves a partial entry, so
/// the caller must treat it as fatal for the archive.
pub fn add_open_file<W: Write + Seek>(
    zip: &mut ZipWriter<W>,
    name: &str,
    mut f: File,
) -> io::Result<u64> {
    let meta = f.metadata()?;
    let opts = options(meta.modified().ok()).large_file(meta.len() >= 0xFFFF_FFFF);
    zip.start_file(name, opts).map_err(io::Error::other)?;
    io::copy(&mut f, zip)
}

/// Writes a directory entry `name/`.
pub fn add_dir<W: Write + Seek>(zip: &mut ZipWriter<W>, name: &str) -> io::Result<()> {
    let n = if name.ends_with('/') {
        name.to_string()
    } else {
        format!("{name}/")
    };
    zip.add_directory(n, options(None))
        .map_err(io::Error::other)
}

/// Writes bytes as entry `name`.
pub fn add_bytes<W: Write + Seek>(
    zip: &mut ZipWriter<W>,
    name: &str,
    bytes: &[u8],
) -> io::Result<()> {
    zip.start_file(name, options(Some(SystemTime::now())))
        .map_err(io::Error::other)?;
    zip.write_all(bytes)
}

/// Copies `input` to `target` under the per-entry cap and the remaining archive budget, both
/// counted on the bytes actually read. Returns the bytes written.
pub fn copy_capped(input: &mut dyn Read, target: &Path, remaining_total: u64) -> io::Result<u64> {
    let mut out = io::BufWriter::new(File::create(target)?);
    let mut buf = vec![0u8; 64 * 1024];
    let mut written = 0u64;
    loop {
        let n = input.read(&mut buf)?;
        if n == 0 {
            break;
        }
        written += n as u64;
        if written > MAX_ENTRY_BYTES {
            drop(out);
            let _ = fs::remove_file(target);
            return Err(io::Error::other(tf!(
                "importer.error.entryTooLarge",
                target
                    .file_name()
                    .map(|n| n.to_string_lossy().into_owned())
                    .unwrap_or_default(),
                MAX_ENTRY_BYTES.to_string()
            )));
        }
        if written > remaining_total {
            drop(out);
            let _ = fs::remove_file(target);
            return Err(io::Error::other(tf!(
                "importer.error.archiveTooLarge",
                MAX_TOTAL_BYTES.to_string()
            )));
        }
        out.write_all(&buf[..n])?;
    }
    out.flush()?;
    Ok(written)
}

/// Extracts every entry of `zip` into `dest` with Zip-Slip and bomb checks. `skip(name)`
/// (name with `/` separators) leaves an entry out; `on_file(name)` sees each extracted file.
pub fn extract_bounded<R: Read + Seek>(
    zip: &mut zip::ZipArchive<R>,
    dest: &Path,
    skip: impl Fn(&str) -> bool,
    mut on_file: impl FnMut(&str),
) -> io::Result<()> {
    if zip.len() > MAX_ENTRIES {
        return Err(io::Error::other(tf!(
            "importer.error.tooManyEntries",
            MAX_ENTRIES.to_string()
        )));
    }
    fs::create_dir_all(dest)?;
    let base = crate::managed::abs_norm(dest);
    let mut total = 0u64;
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i).map_err(io::Error::other)?;
        let raw = entry.name().to_string();
        let name = raw.replace('\\', "/");
        if skip(&name) || is_launcher_state_file(&name) {
            continue;
        }
        let target = resolve_entry(&base, &name)
            .ok_or_else(|| io::Error::other(tf!("importer.error.entryEscapes", &raw)))?;
        if entry.is_dir() || name.ends_with('/') {
            fs::create_dir_all(&target)?;
            continue;
        }
        // Symlink entries are never materialised.
        if entry.unix_mode().is_some_and(|m| m & 0o170000 == 0o120000) {
            continue;
        }
        if let Some(p) = target.parent() {
            fs::create_dir_all(p)?;
        }
        total += copy_capped(&mut entry, &target, MAX_TOTAL_BYTES.saturating_sub(total))?;
        on_file(&name);
    }
    Ok(())
}

/// Launcher-owned verify and audit state, never taken from an imported archive.
///
/// **Guard:** `.verify_state.json` records when the pack was last hashed and security-scanned.
/// An archive is untrusted, so a forged one (fresh timestamps, the embedded manifest's hash)
/// would put the first launch on the fast path and skip the pre-launch malware scan for the
/// very jars the archive just delivered. The audit log is dropped for the same reason: it
/// steers the "re-downloaded every launch" diagnosis.
pub fn is_launcher_state_file(name: &str) -> bool {
    let leaf = name.rsplit(['/', '\\']).next().unwrap_or(name);
    leaf.eq_ignore_ascii_case(crate::verify_state::VERIFY_STATE_FILE)
        || leaf.eq_ignore_ascii_case(crate::audit::AUDIT_FILE)
}

/// `base` + `name`, normalised; `None` when it escapes `base`, is absolute, or has a NUL.
fn resolve_entry(base: &Path, name: &str) -> Option<PathBuf> {
    if name.contains('\0') || name.starts_with('/') || mmcl_archive::guard::is_absolute_name(name) {
        return None;
    }
    let target = mmcl_archive::guard::lexical_join(base, name)?;
    crate::managed::path_within(base, &target, true).then_some(target)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn zip_with(entries: &[(&str, &[u8])]) -> zip::ZipArchive<io::Cursor<Vec<u8>>> {
        let mut w = ZipWriter::new(io::Cursor::new(Vec::new()));
        for (n, b) in entries {
            w.start_file(*n, SimpleFileOptions::default()).unwrap();
            w.write_all(b).unwrap();
        }
        let c = w.finish().unwrap();
        zip::ZipArchive::new(io::Cursor::new(c.into_inner())).unwrap()
    }

    #[test]
    fn zip_slip_is_refused() {
        let d = tempfile::tempdir().unwrap();
        let mut z = zip_with(&[("ok/a.txt", b"a"), ("../evil.txt", b"x")]);
        let e = extract_bounded(&mut z, &d.path().join("out"), |_| false, |_| {}).unwrap_err();
        assert!(e.to_string().contains("evil"), "{e}");
        assert!(!d.path().join("evil.txt").exists());
        let mut z = zip_with(&[("a\\..\\..\\evil2.txt", b"x")]);
        assert!(extract_bounded(&mut z, &d.path().join("out"), |_| false, |_| {}).is_err());
    }

    #[test]
    fn extracts_and_skips() {
        let d = tempfile::tempdir().unwrap();
        let mut z = zip_with(&[
            ("mods/a.jar", b"a"),
            ("skip.me", b"x"),
            ("dir/sub/b.txt", b"b"),
        ]);
        let mut seen = Vec::new();
        extract_bounded(
            &mut z,
            d.path(),
            |n| n == "skip.me",
            |n| seen.push(n.to_string()),
        )
        .unwrap();
        assert_eq!(fs::read(d.path().join("mods/a.jar")).unwrap(), b"a");
        assert!(!d.path().join("skip.me").exists());
        assert_eq!(seen, ["mods/a.jar", "dir/sub/b.txt"]);
    }

    #[test]
    fn launcher_state_files_are_never_extracted() {
        let d = tempfile::tempdir().unwrap();
        let mut z = zip_with(&[
            (".verify_state.json", br#"{"lastScannedAt":9999999999999}"#),
            ("overrides/.VERIFY_STATE.json", b"x"),
            (".audit_log", b"x"),
            ("mods/a.jar", b"a"),
        ]);
        let mut seen = Vec::new();
        extract_bounded(&mut z, d.path(), |_| false, |n| seen.push(n.to_string())).unwrap();
        assert_eq!(seen, ["mods/a.jar"]);
        assert!(!d.path().join(".verify_state.json").exists());
        assert!(!d.path().join("overrides").join(".VERIFY_STATE.json").exists());
        assert!(!d.path().join(".audit_log").exists());
    }

    #[test]
    fn total_budget_is_enforced() {
        let d = tempfile::tempdir().unwrap();
        let mut input: &[u8] = &[0u8; 100];
        assert!(copy_capped(&mut input, &d.path().join("x"), 50).is_err());
        assert!(!d.path().join("x").exists());
        let mut input: &[u8] = &[0u8; 100];
        assert_eq!(
            copy_capped(&mut input, &d.path().join("y"), 100).unwrap(),
            100
        );
    }
}
