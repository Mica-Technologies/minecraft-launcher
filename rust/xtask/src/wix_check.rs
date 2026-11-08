// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Static checks of the WiX sources that need no WiX install: every file is well-formed XML,
//! every `$(var.X)` is passed by xtask (or defined in the file), every `!(loc.X)` / `#(loc.X)`
//! has a string, and the identity values that must never collide with the Java launcher are
//! the ones passed in.

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use crate::ctx::{Args, Ctx, workspace_dir};
use crate::windows::{self, Scope, Staged};

/// Strings the stock WixUI extension provides that our sources reference.
const STOCK_WIXUI_STRINGS: &[&str] = &[
    "WixUINext",
    "WixUIBack",
    "WixUICancel",
    "InstallDirDlgBannerBitmap",
];

fn win(path: &str) -> PathBuf {
    workspace_dir().join("packaging/windows").join(path)
}

fn read(path: &Path) -> String {
    std::fs::read_to_string(path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

fn assert_well_formed(path: &Path) {
    let text = read(path);
    let mut reader = quick_xml::Reader::from_str(&text);
    let mut depth = 0i32;
    loop {
        match reader.read_event() {
            Ok(quick_xml::events::Event::Start(_)) => depth += 1,
            Ok(quick_xml::events::Event::End(_)) => depth -= 1,
            Ok(quick_xml::events::Event::Eof) => break,
            Ok(_) => {}
            Err(e) => panic!("{} is not well-formed XML: {e}", path.display()),
        }
    }
    assert_eq!(depth, 0, "{} has unbalanced elements", path.display());
}

/// Names inside `open ... )` markers, e.g. `$(var.` -> `Version`.
fn refs(text: &str, open: &str) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    let mut rest = text;
    while let Some(i) = rest.find(open) {
        rest = &rest[i + open.len()..];
        if let Some(end) = rest.find(')') {
            out.insert(rest[..end].to_string());
        }
    }
    out
}

/// `<?define Name = ...?>` names.
fn in_file_defines(text: &str) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    for line in text.lines() {
        if let Some(rest) = line.trim().strip_prefix("<?define ") {
            let name: String = rest
                .chars()
                .take_while(|c| c.is_alphanumeric() || *c == '_')
                .collect();
            out.insert(name);
        }
    }
    out
}

/// `<String Id="X"` ids of a .wxl file.
fn wxl_ids(text: &str) -> BTreeSet<String> {
    let open = "<String Id=\"";
    let mut out = BTreeSet::new();
    let mut rest = text;
    while let Some(i) = rest.find(open) {
        rest = &rest[i + open.len()..];
        if let Some(end) = rest.find('"') {
            out.insert(rest[..end].to_string());
        }
    }
    out
}

fn test_ctx() -> Ctx {
    let args = Args::parse(&["--version".to_string(), "2026.05.12".to_string()], &[]).unwrap();
    Ctx::new(&args, windows::DEFAULT_TARGET).unwrap()
}

fn staged() -> Staged {
    Staged {
        exe: PathBuf::from("C:/stage/app.exe"),
        license_txt: PathBuf::from("C:/stage/LICENSE.txt"),
        license_rtf: PathBuf::from("C:/stage/LICENSE.rtf"),
    }
}

#[test]
fn every_packaging_xml_file_is_well_formed() {
    let root = workspace_dir().join("packaging");
    let mut count = 0;
    for entry in walkdir::WalkDir::new(&root) {
        let entry = entry.unwrap();
        let path = entry.path();
        let ext = path.extension().and_then(|e| e.to_str()).unwrap_or("");
        if matches!(ext, "wxs" | "wxl" | "xml" | "plist") {
            assert_well_formed(path);
            count += 1;
        }
    }
    assert!(
        count >= 8,
        "expected the WiX and plist sources, found {count}"
    );
}

#[test]
fn msi_variables_are_all_passed() {
    let ctx = test_ctx();
    let passed: BTreeSet<String> = windows::msi_defines(&ctx, &staged(), Scope::PerUser)
        .into_iter()
        .map(|(k, _)| k)
        .collect();
    for file in ["product.wxs", "ui.wxs", "ShortcutPromptDlg.wxs"] {
        let text = read(&win(file));
        let defined = in_file_defines(&text);
        for var in refs(&text, "$(var.") {
            assert!(
                passed.contains(&var) || defined.contains(&var),
                "{file} uses $(var.{var}) but xtask does not pass it"
            );
        }
    }
}

#[test]
fn bundle_variables_are_all_passed() {
    let ctx = test_ctx();
    let passed: BTreeSet<String> =
        windows::bundle_defines(&ctx, Path::new("u.msi"), Path::new("m.msi"))
            .into_iter()
            .map(|(k, _)| k)
            .collect();
    let text = read(&win("bundle/bundle.wxs"));
    for var in refs(&text, "$(var.") {
        assert!(
            passed.contains(&var),
            "bundle.wxs uses $(var.{var}) but xtask does not pass it"
        );
    }
}

#[test]
fn msi_loc_strings_exist() {
    let ids = wxl_ids(&read(&win("strings_en-us.wxl")));
    for file in ["product.wxs", "ui.wxs", "ShortcutPromptDlg.wxs"] {
        for id in refs(&read(&win(file)), "!(loc.") {
            assert!(
                ids.contains(&id) || STOCK_WIXUI_STRINGS.contains(&id.as_str()),
                "{file} uses !(loc.{id}) with no string"
            );
        }
    }
}

#[test]
fn theme_loc_strings_exist() {
    let ids = wxl_ids(&read(&win("bundle/theme.wxl")));
    for id in refs(&read(&win("bundle/theme.xml")), "#(loc.") {
        assert!(
            ids.contains(&id),
            "theme.xml uses #(loc.{id}) with no string in theme.wxl"
        );
    }
}

#[test]
fn identity_never_reuses_java_codes() {
    let ctx = test_ctx();
    let d = windows::msi_defines(&ctx, &staged(), Scope::PerMachine);
    let get = |k: &str| {
        d.iter()
            .find(|(n, _)| n == k)
            .map(|(_, v)| v.clone())
            .unwrap()
    };
    let java = mmcl_core::identity::JAVA_RELEASE;
    assert_ne!(get("UpgradeCode"), java.win_upgrade_code);
    assert_ne!(get("Aumid"), java.win_aumid);
    assert_eq!(get("Scope"), "perMachine");
    assert!(get("ExeName").ends_with(" (Rust).exe"));
    // The side-by-side build registers neither mmcl:// nor .mmcjson.
    assert_eq!(get("RegisterUrlScheme"), "0");
    assert_eq!(get("RegisterModpackType"), "0");
    let b = windows::bundle_defines(&ctx, Path::new("u"), Path::new("m"));
    let code = b.iter().find(|(k, _)| k == "BundleUpgradeCode").unwrap();
    assert_ne!(code.1, java.win_bundle_upgrade_code);
    let version = b.iter().find(|(k, _)| k == "BundleVersion").unwrap();
    assert_eq!(version.1.split('.').count(), 4);
}

#[test]
fn running_gate_is_wired_before_removal() {
    let text = read(&win("product.wxs"));
    assert!(text.contains("Property=\"MMCL_LAUNCHER_RUNNING\""));
    assert!(text.contains("CloseMessage=\"no\""));
    assert!(text.contains("RebootPrompt=\"no\""));
    assert!(text.contains("Action=\"override Wix4CloseApplications_$(sys.BUILDARCHSHORT)\" Before=\"MmclLauncherRunning\""));
    assert!(text.contains("Action=\"MmclLauncherRunning\" Before=\"RemoveExistingProducts\""));
    assert!(text.contains("NOT Installed OR REMOVE~=&quot;ALL&quot;"));
}

#[test]
fn rust_workflow_parses_and_has_its_jobs() {
    let path = workspace_dir().join("../.github/workflows/rust-build.yml");
    let doc: serde_yaml::Value = serde_yaml::from_str(&read(&path)).expect("valid YAML");
    let jobs = doc.get("jobs").and_then(|j| j.as_mapping()).expect("jobs");
    for job in ["prepare", "test", "package", "release"] {
        assert!(jobs.contains_key(job), "missing job {job}");
    }
    let matrix = &doc["jobs"]["package"]["strategy"]["matrix"]["include"];
    let names: Vec<&str> = matrix
        .as_sequence()
        .unwrap()
        .iter()
        .map(|m| m["dist"].as_str().unwrap())
        .collect();
    assert_eq!(names, vec!["windows", "macos", "macos", "linux"]);
    assert_eq!(
        doc["jobs"]["release"]["steps"]
            .as_sequence()
            .unwrap()
            .last()
            .unwrap()["with"]["prerelease"],
        serde_yaml::Value::Bool(true)
    );
}
