// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Port of `SynchronizedFileManagerTest`.

use std::sync::Arc;

use mmcl_core::filelock::{file_lock, key_for};

#[test]
fn same_absolute_path_returns_the_same_lock() {
    let dir = tempfile::tempdir().unwrap();
    let p = dir.path().join("a.txt");
    assert!(Arc::ptr_eq(&file_lock(&p), &file_lock(&p)));
}

#[test]
fn string_and_path_spellings_share_one_registry() {
    let dir = tempfile::tempdir().unwrap();
    let p = dir.path().join("b.txt");
    let s = p.to_string_lossy().into_owned();
    assert!(Arc::ptr_eq(
        &file_lock(std::path::Path::new(&s)),
        &file_lock(&p)
    ));
}

#[test]
fn relative_and_absolute_spellings_collapse_to_one_lock() {
    let relative = std::path::Path::new("mmcl-core-filelock-relative.txt");
    let absolute = std::env::current_dir().unwrap().join(relative);
    assert!(Arc::ptr_eq(&file_lock(relative), &file_lock(&absolute)));
}

#[test]
fn redundant_path_segments_normalize_to_the_same_lock() {
    let dir = tempfile::tempdir().unwrap();
    let direct = dir.path().join("a").join("c");
    let redundant = dir
        .path()
        .join("a")
        .join(".")
        .join("b")
        .join("..")
        .join("c");
    assert_eq!(key_for(&direct), key_for(&redundant));
    assert!(Arc::ptr_eq(&file_lock(&direct), &file_lock(&redundant)));
}

#[test]
fn case_variant_paths_collapse_on_windows_only() {
    let dir = tempfile::tempdir().unwrap();
    let lower = dir.path().join("casefile.txt");
    let upper = dir.path().join("CASEFILE.TXT");
    let same = Arc::ptr_eq(&file_lock(&lower), &file_lock(&upper));
    if cfg!(windows) {
        assert!(same, "Windows paths are case-insensitive");
    } else {
        assert!(!same, "case-sensitive systems keep distinct locks");
    }
}

#[test]
fn the_lock_actually_excludes() {
    let dir = tempfile::tempdir().unwrap();
    let p = dir.path().join("x");
    let lock = file_lock(&p);
    let guard = lock.lock();
    assert!(file_lock(&p).try_lock().is_none());
    drop(guard);
    assert!(file_lock(&p).try_lock().is_some());
}
