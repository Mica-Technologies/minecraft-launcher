// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `ScanBlocked::message` (Java `ModpackScanDetectionException.getExceptionMsg`).

use std::path::{MAIN_SEPARATOR, PathBuf};

use mmcl_scan::{Finding, Kind, Results, ScanBlocked, Severity, clean_finding_line};

const FOOTER: &str = "Remove or replace the flagged file(s) before launching again. If you trust this content, you can change this pack's Security Scan Frequency to \"Disabled\" in its Advanced settings to bypass the scan.";

fn english() {
    mmcl_i18n::set_locale(mmcl_i18n::Locale::en_us());
}

#[test]
fn empty_message() {
    english();
    assert_eq!(
        ScanBlocked::default().message(),
        "The pre-launch security scan completed without any findings."
    );
}

#[test]
fn single_issue_is_singular() {
    english();
    let b = ScanBlocked {
        stage1_and_high: vec!["/packs/x/mods/evil.jar".into()],
        pack_name: Some("My Pack".into()),
        ..Default::default()
    };
    assert_eq!(
        b.message(),
        format!(
            "The pre-launch security scan blocked \"My Pack\" \u{2014} 1 issue found:\n\n\u{2022} /packs/x/mods/evil.jar\n\n{FOOTER}"
        )
    );
    assert_eq!(b.to_string(), b.message());
}

#[test]
fn many_issues_deduplicated_and_blank_name_uses_this_modpack() {
    english();
    let b = ScanBlocked {
        stage1_and_high: vec!["a".into(), "[HIGH] b \u{2014} msg".into(), "a".into()],
        stage2: vec!["c".into(), "a".into()],
        pack_name: Some("   ".into()),
        pack_root: None,
    };
    assert_eq!(
        b.message(),
        format!(
            "The pre-launch security scan blocked this modpack \u{2014} 3 issues found:\n\n\u{2022} a\n\u{2022} b \u{2014} msg\n\u{2022} c\n\n{FOOTER}"
        )
    );
}

#[test]
fn root_stripping_native_and_slash_forms() {
    let root = PathBuf::from(format!("{MAIN_SEPARATOR}packs{MAIN_SEPARATOR}x"));
    let native =
        format!("{MAIN_SEPARATOR}packs{MAIN_SEPARATOR}x{MAIN_SEPARATOR}mods{MAIN_SEPARATOR}e.jar");
    assert_eq!(
        clean_finding_line(&native, Some(&root)),
        format!("mods{MAIN_SEPARATOR}e.jar")
    );
    // A forward-slash line under a backslash root (or the reverse) keeps its own separators.
    let root_bs = PathBuf::from("C:\\packs\\x");
    assert_eq!(
        clean_finding_line("C:/packs/x/mods/e.jar", Some(&root_bs)),
        "mods/e.jar"
    );
    let root_fs = PathBuf::from("C:/packs/x/");
    assert_eq!(
        clean_finding_line(
            "  [HIGH] C:\\packs\\x\\mods\\e.jar \u{2014} m ",
            Some(&root_fs)
        ),
        "mods\\e.jar \u{2014} m"
    );
    // Unrelated paths and no root stay as they are.
    assert_eq!(
        clean_finding_line("/other/e.jar", Some(&root)),
        "/other/e.jar"
    );
    assert_eq!(clean_finding_line("[MEDIUM]  x", None), "x");
    assert_eq!(clean_finding_line("x", Some(&PathBuf::from(""))), "x");
}

#[test]
fn from_results_blocks_on_stage1_high_and_stage2_only() {
    let neko = Results::default();
    let medium = Finding {
        severity: Severity::Medium,
        kind: Kind::Ipv4Literal,
        file: PathBuf::from("m.jar"),
        file_sha256: None,
        inner_sha256: None,
        locator: "A.b".into(),
        message: "hard-coded IPv4 literal in A.b: 45.33.32.156".into(),
    };
    assert!(ScanBlocked::from_results(&neko, std::slice::from_ref(&medium), None, None).is_none());

    let high = Finding {
        severity: Severity::High,
        kind: Kind::EmbeddedExecutable,
        message: "embedded executable / script entry: x.exe".into(),
        ..medium.clone()
    };
    let b = ScanBlocked::from_results(
        &Results {
            stage1: vec!["s1".into()],
            stage2: vec!["s2".into()],
        },
        &[medium, high.clone()],
        Some("P".into()),
        None,
    )
    .unwrap();
    assert_eq!(b.stage1_and_high, vec!["s1".to_owned(), high.to_string()]);
    assert_eq!(b.stage2, vec!["s2".to_owned()]);

    let stage2_only = ScanBlocked::from_results(
        &Results {
            stage1: vec![],
            stage2: vec!["s2".into()],
        },
        &[],
        None,
        None,
    );
    assert!(stage2_only.is_some());
}
