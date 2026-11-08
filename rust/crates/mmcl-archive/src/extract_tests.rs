// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Port of `ArchiveExtractorTest`, plus cap and directory cases.

use std::fs;

use crate::test_support::{TarItem, ZipItem, write_tar_gz, write_zip, write_zip_items};
use crate::{
    ArchiveKind, ExtractError, Limits, extract_tar_gz, extract_tar_gz_with, extract_zip,
    extract_zip_with,
};

// ---- happy path ----

#[test]
fn extracts_tar_gz_entries_under_target_dir() {
    let dir = tempfile::tempdir().unwrap();
    let archive = dir.path().join("jre.tar.gz");
    write_tar_gz(
        &archive,
        &[
            TarItem::File("bin/java", b"binary-content"),
            TarItem::File("lib/modules", b"module-data"),
        ],
    );
    let dest = dir.path().join("runtime");
    extract_tar_gz(&archive, &dest).unwrap();
    assert_eq!(fs::read(dest.join("bin/java")).unwrap(), b"binary-content");
    assert_eq!(fs::read(dest.join("lib/modules")).unwrap(), b"module-data");
}

#[test]
fn extracts_zip_entries_under_target_dir() {
    let dir = tempfile::tempdir().unwrap();
    let archive = dir.path().join("jre.zip");
    write_zip(&archive, &[("bin/java.exe", b"binary-content")]);
    let dest = dir.path().join("runtime");
    extract_zip(&archive, &dest).unwrap();
    assert_eq!(
        fs::read(dest.join("bin/java.exe")).unwrap(),
        b"binary-content"
    );
}

#[test]
fn directory_entries_are_created() {
    let dir = tempfile::tempdir().unwrap();
    let tgz = dir.path().join("a.tar.gz");
    write_tar_gz(&tgz, &[TarItem::Dir("./jdk/"), TarItem::Dir("jdk/conf/")]);
    extract_tar_gz(&tgz, &dir.path().join("t")).unwrap();
    assert!(dir.path().join("t/jdk/conf").is_dir());
    let zip = dir.path().join("a.zip");
    write_zip_items(&zip, &[ZipItem::Dir("jdk/legal/")]);
    extract_zip(&zip, &dir.path().join("z")).unwrap();
    assert!(dir.path().join("z/jdk/legal").is_dir());
}

// ---- zip-slip / absolute-path containment ----

#[test]
fn tar_gz_rejects_entry_that_escapes_target_dir_via_dot_dot() {
    let dir = tempfile::tempdir().unwrap();
    let archive = dir.path().join("evil.tar.gz");
    write_tar_gz(&archive, &[TarItem::File("../../evil.txt", b"payload")]);
    let err = extract_tar_gz(&archive, &dir.path().join("runtime")).unwrap_err();
    assert!(matches!(err, ExtractError::Escapes { .. }), "{err}");
    assert!(!dir.path().join("evil.txt").exists());
}

#[test]
fn tar_gz_rejects_absolute_entry_path() {
    let dir = tempfile::tempdir().unwrap();
    let archive = dir.path().join("evil.tar.gz");
    write_tar_gz(&archive, &[TarItem::File("/etc/evil.txt", b"payload")]);
    let err = extract_tar_gz(&archive, &dir.path().join("runtime")).unwrap_err();
    assert!(matches!(err, ExtractError::AbsolutePath { .. }), "{err}");
}

#[test]
fn zip_rejects_entry_that_escapes_target_dir_via_dot_dot() {
    let dir = tempfile::tempdir().unwrap();
    let archive = dir.path().join("evil.zip");
    write_zip(&archive, &[("../../evil.txt", b"payload")]);
    let err = extract_zip(&archive, &dir.path().join("runtime")).unwrap_err();
    assert!(matches!(err, ExtractError::Escapes { .. }), "{err}");
    assert!(!dir.path().join("evil.txt").exists());
}

#[test]
fn zip_rejects_backslash_escape_on_every_os() {
    let dir = tempfile::tempdir().unwrap();
    let archive = dir.path().join("evil.zip");
    write_zip(&archive, &[("a\\..\\..\\evil.txt", b"payload")]);
    let err = extract_zip(&archive, &dir.path().join("runtime")).unwrap_err();
    assert!(matches!(err, ExtractError::Escapes { .. }), "{err}");
    assert!(!dir.path().join("evil.txt").exists());
}

#[test]
fn zip_rejects_entry_with_nul_byte_in_name() {
    let dir = tempfile::tempdir().unwrap();
    let archive = dir.path().join("evil.zip");
    write_zip(&archive, &[("innocuous.txt\0.jpg", b"payload")]);
    let err = extract_zip(&archive, &dir.path().join("runtime")).unwrap_err();
    assert!(matches!(err, ExtractError::NulByte { .. }), "{err}");
}

// ---- symlink rejection ----

#[test]
fn tar_gz_skips_symlink_entry_without_following_it() {
    let dir = tempfile::tempdir().unwrap();
    let archive = dir.path().join("jre.tar.gz");
    write_tar_gz(
        &archive,
        &[
            TarItem::Symlink("bin/java", "/usr/bin/malicious"),
            TarItem::Hardlink("bin/javac", "/etc/passwd"),
            TarItem::File("lib/modules", b"module-data"),
        ],
    );
    let dest = dir.path().join("runtime");
    extract_tar_gz(&archive, &dest).unwrap();
    assert!(
        fs::symlink_metadata(dest.join("bin/java")).is_err(),
        "symlink entry must not be materialized"
    );
    assert!(
        fs::symlink_metadata(dest.join("bin/javac")).is_err(),
        "hardlink entry must not be materialized"
    );
    assert!(
        dest.join("lib/modules").exists(),
        "subsequent regular entries must still extract"
    );
}

#[test]
fn zip_symlink_entry_is_skipped() {
    let dir = tempfile::tempdir().unwrap();
    let archive = dir.path().join("jre.zip");
    write_zip_items(
        &archive,
        &[
            ZipItem::Symlink("bin/java", "/usr/bin/malicious"),
            ZipItem::File("lib/modules", b"module-data"),
        ],
    );
    let dest = dir.path().join("runtime");
    extract_zip(&archive, &dest).unwrap();
    assert!(
        fs::symlink_metadata(dest.join("bin/java")).is_err(),
        "the symlink entry must not be materialized"
    );
    assert!(
        dest.join("lib/modules").exists(),
        "subsequent regular entries must still extract"
    );
}

#[test]
fn a_zip_symlink_is_skipped_wherever_it_appears() {
    let dir = tempfile::tempdir().unwrap();
    let archive = dir.path().join("jre.zip");
    write_zip_items(
        &archive,
        &[
            ZipItem::File("lib/first", b"data"),
            ZipItem::Symlink("bin/java", "/usr/bin/malicious"),
            ZipItem::File("lib/last", b"data"),
        ],
    );
    let dest = dir.path().join("runtime");
    extract_zip(&archive, &dest).unwrap();
    assert!(fs::symlink_metadata(dest.join("bin/java")).is_err());
    assert!(dest.join("lib/first").exists());
    assert!(dest.join("lib/last").exists());
}

// ---- reserved names ----

#[test]
fn tar_gz_skips_windows_reserved_device_name_entry() {
    let dir = tempfile::tempdir().unwrap();
    let archive = dir.path().join("jre.tar.gz");
    write_tar_gz(
        &archive,
        &[
            TarItem::File("con.txt", b"payload"),
            TarItem::File("ok.txt", b"x"),
        ],
    );
    let dest = dir.path().join("runtime");
    extract_tar_gz(&archive, &dest).unwrap();
    let names: Vec<_> = fs::read_dir(&dest)
        .unwrap()
        .map(|e| e.unwrap().file_name())
        .collect();
    assert_eq!(names, vec![std::ffi::OsString::from("ok.txt")]);
}

#[test]
fn zip_skips_windows_reserved_device_name_entry() {
    let dir = tempfile::tempdir().unwrap();
    let archive = dir.path().join("jre.zip");
    write_zip(&archive, &[("sub/AUX.log", b"payload"), ("sub/ok", b"x")]);
    let dest = dir.path().join("runtime");
    extract_zip(&archive, &dest).unwrap();
    let names: Vec<_> = fs::read_dir(dest.join("sub"))
        .unwrap()
        .map(|e| e.unwrap().file_name())
        .collect();
    assert_eq!(names, vec![std::ffi::OsString::from("ok")]);
}

// ---- size caps ----

#[test]
fn per_entry_cap_counts_bytes_read() {
    let dir = tempfile::tempdir().unwrap();
    let limits = Limits {
        per_entry: 1000,
        total: 1 << 20,
    };
    let archive = dir.path().join("bomb.zip");
    write_zip(&archive, &[("big", &[0u8; 4096])]);
    let err = extract_zip_with(&archive, &dir.path().join("t"), limits).unwrap_err();
    assert!(matches!(err, ExtractError::EntryTooLarge { .. }), "{err}");
    let tgz = dir.path().join("bomb.tar.gz");
    write_tar_gz(&tgz, &[TarItem::File("big", &[0u8; 4096])]);
    let err = extract_tar_gz_with(&tgz, &dir.path().join("u"), limits).unwrap_err();
    assert!(matches!(err, ExtractError::EntryTooLarge { .. }), "{err}");
}

#[test]
fn total_cap_spans_entries() {
    let dir = tempfile::tempdir().unwrap();
    let archive = dir.path().join("bomb.zip");
    write_zip(&archive, &[("a", &[0u8; 600]), ("b", &[0u8; 600])]);
    let limits = Limits {
        per_entry: 1000,
        total: 1000,
    };
    let err = extract_zip_with(&archive, &dir.path().join("t"), limits).unwrap_err();
    assert!(matches!(err, ExtractError::TotalTooLarge { .. }), "{err}");
}

#[test]
fn runtime_caps_match_java() {
    assert_eq!(Limits::RUNTIME_ARCHIVE.per_entry, 512 * 1024 * 1024);
    assert_eq!(Limits::RUNTIME_ARCHIVE.total, 2 * 1024 * 1024 * 1024);
    assert_eq!(Limits::NATIVES_JAR.per_entry, 64 * 1024 * 1024);
    assert_eq!(Limits::NATIVES_JAR.total, 256 * 1024 * 1024);
}

#[test]
fn errors_convert_to_io_errors() {
    let e: std::io::Error = ExtractError::Escapes {
        kind: ArchiveKind::Archive,
        name: "x".into(),
    }
    .into();
    assert!(e.to_string().contains("escapes base dir"));
}
