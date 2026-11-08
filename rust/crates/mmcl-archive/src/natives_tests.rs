// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Port of `SystemUtilitiesExtractTest` and `SystemUtilitiesExtractSecurityTest`.

use std::fs;
use std::path::{Path, PathBuf};

use crate::test_support::write_zip;
use crate::{ArchiveKind, ExtractError, Limits, extract_jar, extract_jar_with};

/// A deterministic pseudo-random payload; `size` doubles as the seed.
fn payload(size: usize) -> Vec<u8> {
    let mut state = size as u64 | 1;
    (0..size)
        .map(|_| {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state as u8
        })
        .collect()
}

fn single_entry_jar(dir: &Path, file: &str, entry: &str, content: &[u8]) -> PathBuf {
    let jar = dir.join(file);
    write_zip(&jar, &[(entry, content)]);
    jar
}

fn out_dir(dir: &Path) -> PathBuf {
    let out = dir.join("out");
    fs::create_dir_all(&out).unwrap();
    out
}

fn entry_count(dir: &Path) -> usize {
    fs::read_dir(dir).unwrap().count()
}

// ---- SystemUtilitiesExtractTest ----

#[test]
fn extracts_content_exactly() {
    let dir = tempfile::tempdir().unwrap();
    let data = payload(17 * 1024);
    let jar = single_entry_jar(dir.path(), "natives.jar", "lwjgl64.dll", &data);
    let dest = out_dir(dir.path());
    extract_jar(&jar, &dest).unwrap();
    assert_eq!(fs::read(dest.join("lwjgl64.dll")).unwrap(), data);
}

#[test]
fn re_extract_overwrites_and_leaves_no_temp_files() {
    let dir = tempfile::tempdir().unwrap();
    let dest = out_dir(dir.path());
    let a = payload(4 * 1024);
    extract_jar(
        &single_entry_jar(dir.path(), "a.jar", "lwjgl64.dll", &a),
        &dest,
    )
    .unwrap();
    let b = payload(6 * 1024);
    extract_jar(
        &single_entry_jar(dir.path(), "b.jar", "lwjgl64.dll", &b),
        &dest,
    )
    .unwrap();
    assert_eq!(fs::read(dest.join("lwjgl64.dll")).unwrap(), b);
    let temps = fs::read_dir(&dest)
        .unwrap()
        .filter(|e| {
            e.as_ref()
                .unwrap()
                .file_name()
                .to_string_lossy()
                .contains(".tmp-")
        })
        .count();
    assert_eq!(
        temps, 0,
        "no temp files should remain in the destination after extract"
    );
}

#[test]
fn handles_multiple_entries() {
    let dir = tempfile::tempdir().unwrap();
    let dll = payload(8 * 1024);
    let jar = dir.path().join("multi.jar");
    write_zip(
        &jar,
        &[
            ("lwjgl64.dll", &dll),
            ("subdir/openal.dll", b"hello world\n"),
        ],
    );
    let dest = out_dir(dir.path());
    extract_jar(&jar, &dest).unwrap();
    assert_eq!(fs::read(dest.join("lwjgl64.dll")).unwrap(), dll);
    assert_eq!(
        fs::read(dest.join("subdir/openal.dll")).unwrap(),
        b"hello world\n"
    );
}

#[test]
fn creates_the_destination_when_missing() {
    let dir = tempfile::tempdir().unwrap();
    let jar = single_entry_jar(dir.path(), "n.jar", "liblwjgl.so", b"elf");
    let dest = dir.path().join("natives").join("deep");
    extract_jar(&jar, &dest).unwrap();
    assert_eq!(fs::read(dest.join("liblwjgl.so")).unwrap(), b"elf");
}

#[test]
fn cap_breach_leaves_no_temp_file_and_keeps_the_previous_copy() {
    let dir = tempfile::tempdir().unwrap();
    let dest = out_dir(dir.path());
    fs::write(dest.join("lwjgl64.dll"), b"previous-good").unwrap();
    let jar = single_entry_jar(dir.path(), "big.jar", "lwjgl64.dll", &payload(4096));
    let limits = Limits {
        per_entry: 1024,
        total: 1 << 20,
    };
    let err = extract_jar_with(&jar, &dest, limits).unwrap_err();
    assert!(
        matches!(
            err,
            ExtractError::EntryTooLarge {
                kind: ArchiveKind::Jar,
                ..
            }
        ),
        "{err}"
    );
    assert_eq!(
        fs::read(dest.join("lwjgl64.dll")).unwrap(),
        b"previous-good"
    );
    assert_eq!(entry_count(&dest), 1);
}

#[test]
fn total_cap_spans_entries() {
    let dir = tempfile::tempdir().unwrap();
    let jar = dir.path().join("multi.jar");
    write_zip(&jar, &[("a.dll", &[1u8; 700]), ("b.dll", &[1u8; 700])]);
    let limits = Limits {
        per_entry: 1024,
        total: 1024,
    };
    let err = extract_jar_with(&jar, &out_dir(dir.path()), limits).unwrap_err();
    assert!(matches!(err, ExtractError::TotalTooLarge { .. }), "{err}");
}

// ---- SystemUtilitiesExtractSecurityTest ----

#[test]
fn rejects_entry_that_escapes_base_dir_via_dot_dot() {
    let dir = tempfile::tempdir().unwrap();
    let jar = single_entry_jar(dir.path(), "raw.jar", "../../evil.txt", b"payload");
    let err = extract_jar(&jar, &out_dir(dir.path())).unwrap_err();
    assert!(matches!(err, ExtractError::Escapes { .. }), "{err}");
    assert!(!dir.path().join("evil.txt").exists());
}

#[test]
fn rejects_absolute_unix_style_entry_path() {
    let dir = tempfile::tempdir().unwrap();
    let jar = single_entry_jar(dir.path(), "raw.jar", "/etc/evil.txt", b"payload");
    let err = extract_jar(&jar, &out_dir(dir.path())).unwrap_err();
    assert!(matches!(err, ExtractError::AbsolutePath { .. }), "{err}");
}

#[test]
fn rejects_entry_name_containing_nul_byte() {
    let dir = tempfile::tempdir().unwrap();
    let jar = single_entry_jar(dir.path(), "raw.jar", "innocuous.txt\0.jpg", b"payload");
    let err = extract_jar(&jar, &out_dir(dir.path())).unwrap_err();
    assert!(matches!(err, ExtractError::NulByte { .. }), "{err}");
}

#[test]
fn skips_windows_reserved_device_name_entry_without_throwing() {
    let dir = tempfile::tempdir().unwrap();
    let jar = single_entry_jar(dir.path(), "raw.jar", "con.txt", b"payload");
    let dest = out_dir(dir.path());
    extract_jar(&jar, &dest).unwrap();
    assert_eq!(
        entry_count(&dest),
        0,
        "reserved device name must not be materialized"
    );
}

#[test]
fn reserved_name_check_is_case_insensitive_and_extension_stripped() {
    let dir = tempfile::tempdir().unwrap();
    let jar = single_entry_jar(dir.path(), "raw.jar", "COM1.dat", b"payload");
    let dest = out_dir(dir.path());
    extract_jar(&jar, &dest).unwrap();
    assert_eq!(entry_count(&dest), 0);
}

#[test]
fn skips_meta_inf_entries_without_error() {
    let dir = tempfile::tempdir().unwrap();
    let jar = dir.path().join("raw.jar");
    write_zip(
        &jar,
        &[
            ("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n"),
            ("natives/META-INF-x/a.dll", b"x"),
        ],
    );
    let dest = out_dir(dir.path());
    extract_jar(&jar, &dest).unwrap();
    assert_eq!(
        entry_count(&dest),
        0,
        "META-INF entries must not be extracted"
    );
}
