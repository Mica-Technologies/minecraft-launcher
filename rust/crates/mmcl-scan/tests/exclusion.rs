// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Port of `ScanExclusionPolicyTest`.

use mmcl_scan::exclusion::{filter_untrusted, filter_untrusted_opt, rejection_reason};

#[test]
fn protected_content_roots_are_rejected() {
    for root in [
        "mods",
        "config",
        "scripts",
        "resourcepacks",
        "shaderpacks",
        "kubejs",
        "defaultconfigs",
    ] {
        assert!(rejection_reason(root).is_some(), "{root}");
    }
}

#[test]
fn protected_roots_rejected_regardless_of_form() {
    for v in [
        "MODS",
        "mods/",
        "/mods",
        "\\mods",
        "./mods",
        "mods\\",
        "mods/optifine.jar",
        "Config/secret",
        "./mods/",
        "mods\\sub",
        "/config",
    ] {
        assert!(rejection_reason(v).is_some(), "{v}");
    }
}

#[test]
fn pack_root_covering_exclusions_are_rejected() {
    for v in ["", " ", ".", "/", "\\", "./"] {
        assert!(rejection_reason(v).is_some(), "{v:?}");
    }
}

#[test]
fn traversal_segments_are_rejected() {
    assert!(rejection_reason("..").is_some());
    assert!(rejection_reason("custom/../mods").is_some());
    assert!(rejection_reason("custom/../other").is_some());
}

#[test]
fn benign_exclusions_are_allowed() {
    for ok in [
        "libraries",
        "bin",
        "runtime",
        "libraries/foo",
        "custom-tools",
        "docs/examples",
        "modsextra",
    ] {
        assert!(rejection_reason(ok).is_none(), "{ok}");
    }
}

#[test]
fn filter_drops_only_disallowed_and_preserves_original_form() {
    let filtered = filter_untrusted(&["mods", "Libraries/Foo", ".", "custom-tools", ""]);
    assert_eq!(filtered, vec!["Libraries/Foo", "custom-tools"]);
}

#[test]
fn filter_handles_none_and_empty() {
    assert!(filter_untrusted_opt::<&str>(None).is_empty());
    assert!(filter_untrusted::<&str>(&[]).is_empty());
}
