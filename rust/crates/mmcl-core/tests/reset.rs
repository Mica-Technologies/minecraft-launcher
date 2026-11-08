// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Port of `LauncherResetMoveAsideTest`.

use std::fs;

use mmcl_core::fsx::move_aside_for_reset;

#[test]
fn moves_the_whole_folder_to_a_stamped_sibling() {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().join(".mica-launcher");
    fs::create_dir_all(root.join("config")).unwrap();
    fs::write(root.join("config").join("configuration.json"), "{}").unwrap();

    let aside = move_aside_for_reset(&root, 42).unwrap().unwrap();

    assert_eq!(aside, dir.path().join(".mica-launcher.reset-42"));
    assert!(!root.exists());
    assert!(aside.join("config").join("configuration.json").exists());
}

#[test]
fn a_missing_folder_has_nothing_to_move() {
    let dir = tempfile::tempdir().unwrap();
    assert_eq!(
        move_aside_for_reset(&dir.path().join("absent"), 1).unwrap(),
        None
    );
}

#[test]
fn a_failed_rename_leaves_the_folder_in_place() {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().join("launcher");
    fs::create_dir_all(&root).unwrap();
    fs::write(root.join("keep.txt"), "x").unwrap();
    // The sibling name is already taken by a non-empty folder, so the rename must fail.
    fs::create_dir_all(dir.path().join("launcher.reset-7").join("occupied")).unwrap();

    assert!(move_aside_for_reset(&root, 7).is_err());
    assert!(root.join("keep.txt").exists());
}

#[test]
fn an_empty_squatter_also_blocks_the_move() {
    // Unix rename would replace an empty folder; the move must still refuse.
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().join("launcher");
    fs::create_dir_all(&root).unwrap();
    fs::create_dir_all(dir.path().join("launcher.reset-9")).unwrap();
    assert!(move_aside_for_reset(&root, 9).is_err());
    assert!(root.exists());
}
