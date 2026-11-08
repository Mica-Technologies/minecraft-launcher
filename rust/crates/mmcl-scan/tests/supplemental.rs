// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Port of the `scanJar` / `scanFolder` parts of `SupplementalScannerTest` (the pure-helper
//! vectors are unit tests in `src/supplemental.rs`).

mod common;

use std::path::Path;

use common::classwriter::{ClassWriter, Code};
use common::{build_jar, class_with_ldc_string, class_with_two_method_calls};
use mmcl_scan::CancelToken;
use mmcl_scan::supplemental::{
    Finding, Kind, Severity, compute_file_sha256, scan_folder, scan_jar, sha256_hex,
};

fn scan(p: &Path) -> Vec<Finding> {
    scan_jar(p).expect("jar opens")
}

#[test]
fn flags_embedded_executable_as_high() {
    let tmp = tempfile::tempdir().unwrap();
    let jar = build_jar(tmp.path(), "mod.jar", &[("payload.exe", &[1, 2, 3])]);
    let f = scan(&jar);
    assert_eq!(f.len(), 1);
    assert_eq!(f[0].severity, Severity::High);
    assert_eq!(f[0].kind, Kind::EmbeddedExecutable);
    assert_eq!(f[0].locator, "payload.exe");
    assert_eq!(
        f[0].message,
        "embedded executable / script entry: payload.exe"
    );
    assert_eq!(
        f[0].to_string(),
        format!(
            "[HIGH] {} \u{2014} embedded executable / script entry: payload.exe",
            jar.display()
        )
    );
}

#[test]
fn flags_script_suffixes_as_high() {
    let tmp = tempfile::tempdir().unwrap();
    let jar = build_jar(
        tmp.path(),
        "mod.jar",
        &[
            ("installer.ps1", &[1]),
            ("hidden.vbs", &[1]),
            ("run.bat", &[1]),
            ("UPPER.EXE", &[1]),
        ],
    );
    let f = scan(&jar);
    assert_eq!(f.len(), 4);
    assert!(
        f.iter()
            .all(|f| f.severity == Severity::High && f.kind == Kind::EmbeddedExecutable)
    );
}

#[test]
fn does_not_flag_javascript_resources() {
    let tmp = tempfile::tempdir().unwrap();
    let jar = build_jar(
        tmp.path(),
        "mod.jar",
        &[("cs_adv.js", &[1, 2, 3]), ("x.jse", &[1])],
    );
    assert!(scan(&jar).is_empty());
}

#[test]
fn flags_native_outside_known_path_as_medium() {
    let tmp = tempfile::tempdir().unwrap();
    let jar = build_jar(
        tmp.path(),
        "suspicious-mod.jar",
        &[("weird.jnilib", &[1, 2, 3])],
    );
    let f = scan(&jar);
    assert_eq!(f.len(), 1);
    assert_eq!(f[0].severity, Severity::Medium);
    assert_eq!(f[0].kind, Kind::NativeOutsideKnownPath);
    assert_eq!(
        f[0].inner_sha256.as_deref(),
        Some(sha256_hex(&[1, 2, 3]).as_str())
    );
}

#[test]
fn bare_dll_is_not_flagged_by_filename_convention() {
    let tmp = tempfile::tempdir().unwrap();
    let jar = build_jar(
        tmp.path(),
        "suspicious-mod.jar",
        &[("weird.dll", &[1, 2, 3])],
    );
    assert!(scan(&jar).is_empty());
}

#[test]
fn native_inside_legit_path_is_not_flagged() {
    let tmp = tempfile::tempdir().unwrap();
    let jar = build_jar(
        tmp.path(),
        "lwjgl.jar",
        &[("natives/windows/lwjgl.dll", &[1]), ("linux/x.so", &[1])],
    );
    assert!(scan(&jar).is_empty());
}

#[test]
fn natives_jar_suppresses_native_check_but_still_flags_executable() {
    let tmp = tempfile::tempdir().unwrap();
    let jar = build_jar(
        tmp.path(),
        "lwjgl-platform-2.9.1-natives-windows.jar",
        &[
            ("lwjgl.dll", &[1]),
            ("weird.jnilib", &[1]),
            ("trojan.exe", &[1]),
        ],
    );
    let f = scan(&jar);
    assert_eq!(f.len(), 1);
    assert_eq!(f[0].kind, Kind::EmbeddedExecutable);
}

#[test]
fn stamps_file_and_inner_sha256() {
    let tmp = tempfile::tempdir().unwrap();
    let bytes = b"malicious content";
    let jar = build_jar(tmp.path(), "mod.jar", &[("bad.exe", bytes)]);
    let f = scan(&jar);
    assert_eq!(f.len(), 1);
    assert_eq!(
        f[0].inner_sha256.as_deref(),
        Some(sha256_hex(bytes).as_str())
    );
    let outer = compute_file_sha256(&jar);
    assert!(outer.is_some());
    assert_eq!(f[0].file_sha256, outer);
}

#[test]
fn flags_discord_webhook_as_high() {
    let tmp = tempfile::tempdir().unwrap();
    let webhook =
        "https://discord.com/api/webhooks/111111111111111111/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    let class = class_with_ldc_string("com/evil/Stealer", webhook);
    let jar = build_jar(tmp.path(), "mod.jar", &[("com/evil/Stealer.class", &class)]);
    let f = scan(&jar);
    assert_eq!(f.len(), 1);
    assert_eq!(f[0].severity, Severity::High);
    assert_eq!(f[0].kind, Kind::DiscordWebhookUrl);
    assert_eq!(f[0].locator, "com/evil/Stealer.trigger");
    assert_eq!(
        f[0].message,
        "Discord webhook URL in com/evil/Stealer.trigger"
    );
    assert_eq!(
        f[0].inner_sha256.as_deref(),
        Some(sha256_hex(&class).as_str())
    );
}

#[test]
fn discord_webhook_is_case_insensitive_with_subdomain() {
    let tmp = tempfile::tempdir().unwrap();
    let class = class_with_ldc_string(
        "a/B",
        "HTTPS://canary.DiscordApp.com/api/webhooks/123/abc-DEF_9",
    );
    let jar = build_jar(tmp.path(), "mod.jar", &[("a/B.class", &class)]);
    let f = scan(&jar);
    assert_eq!(f.len(), 1);
    assert_eq!(f[0].kind, Kind::DiscordWebhookUrl);
}

#[test]
fn flags_paste_host_as_high_and_quotes_the_java_pattern() {
    let tmp = tempfile::tempdir().unwrap();
    let a = class_with_ldc_string("com/evil/Stage2", "https://pastebin.com/raw/AbCdEfGh");
    let b = class_with_ldc_string("com/evil/Up", "curl https://transfer.sh/abc/x.bin");
    let jar = build_jar(
        tmp.path(),
        "mod.jar",
        &[("com/evil/Stage2.class", &a), ("com/evil/Up.class", &b)],
    );
    let f = scan(&jar);
    assert_eq!(f.len(), 2);
    assert!(
        f.iter()
            .all(|f| f.severity == Severity::High && f.kind == Kind::SuspiciousHostUrl)
    );
    assert_eq!(
        f[0].message,
        r"suspicious host URL in com/evil/Stage2.trigger (https?://(?:www\.)?pastebin\.com/raw/[\w-]+)"
    );
    assert_eq!(
        f[1].message,
        r"suspicious host URL in com/evil/Up.trigger (https?://(?:www\.)?transfer\.sh/[\w./?=&-]+)"
    );
}

#[test]
fn flags_launcher_credential_file_as_high() {
    let tmp = tempfile::tempdir().unwrap();
    let class = class_with_ldc_string(
        "com/evil/Grabber",
        "AppData/Roaming/.minecraft/launcher_profiles.json",
    );
    let jar = build_jar(tmp.path(), "mod.jar", &[("com/evil/Grabber.class", &class)]);
    let f = scan(&jar);
    assert_eq!(f.len(), 1);
    assert_eq!(f[0].kind, Kind::LauncherCredentialFileRef);
    assert_eq!(
        f[0].message,
        "reference to launcher credential file 'launcher_profiles.json' in com/evil/Grabber.trigger"
    );
}

#[test]
fn usercache_json_is_not_flagged() {
    let tmp = tempfile::tempdir().unwrap();
    let class = class_with_ldc_string("net/minecraft/server/MinecraftServer", "usercache.json");
    let jar = build_jar(
        tmp.path(),
        "minecraft.jar",
        &[("net/minecraft/server/MinecraftServer.class", &class)],
    );
    assert!(scan(&jar).is_empty());
}

#[test]
fn flags_genuine_ipv4_literal_as_medium() {
    let tmp = tempfile::tempdir().unwrap();
    let class = class_with_ldc_string("com/evil/C2", "45.33.32.156");
    let jar = build_jar(tmp.path(), "mod.jar", &[("com/evil/C2.class", &class)]);
    let f = scan(&jar);
    assert_eq!(f.len(), 1);
    assert_eq!(f[0].severity, Severity::Medium);
    assert_eq!(f[0].kind, Kind::Ipv4Literal);
    assert_eq!(
        f[0].message,
        "hard-coded IPv4 literal in com/evil/C2.trigger: 45.33.32.156"
    );
}

#[test]
fn loopback_and_multicast_are_not_flagged() {
    let tmp = tempfile::tempdir().unwrap();
    let a = class_with_ldc_string("com/mod/A", "127.0.0.1");
    let b = class_with_ldc_string("com/mod/B", "224.0.2.60");
    let jar = build_jar(
        tmp.path(),
        "mod.jar",
        &[("com/mod/A.class", &a), ("com/mod/B.class", &b)],
    );
    assert!(scan(&jar).is_empty());
}

#[test]
fn clipboard_stealer_needs_both_apis() {
    let tmp = tempfile::tempdir().unwrap();
    let both = class_with_two_method_calls(
        "com/evil/ClipStealer",
        "java/awt/Robot",
        "java/awt/datatransfer/Clipboard",
    );
    let jar = build_jar(
        tmp.path(),
        "mod.jar",
        &[("com/evil/ClipStealer.class", &both)],
    );
    let f = scan(&jar);
    assert_eq!(f.len(), 1);
    assert_eq!(f[0].severity, Severity::Medium);
    assert_eq!(f[0].kind, Kind::ClipboardStealerPattern);
    assert_eq!(f[0].locator, "com/evil/ClipStealer");

    let only = class_with_two_method_calls(
        "com/util/CopyIp",
        "java/lang/String",
        "java/awt/datatransfer/Clipboard",
    );
    let jar = build_jar(tmp.path(), "mod2.jar", &[("com/util/CopyIp.class", &only)]);
    assert!(scan(&jar).is_empty());
}

#[test]
fn clipboard_via_interface_invoke_counts_but_invokedynamic_does_not_matter() {
    let tmp = tempfile::tempdir().unwrap();
    let mut cw = ClassWriter::new("com/evil/I");
    let robot = cw.methodref("java/awt/Robot", "op", "()V");
    let clip = cw.interface_methodref("java/awt/datatransfer/Clipboard", "op", "()V");
    cw.method(
        "m",
        "()V",
        Code::new()
            .invokestatic(robot)
            .raw(&[0xb9])
            .u2(clip)
            .raw(&[1, 0])
            .ret(),
    );
    let jar = build_jar(tmp.path(), "mod.jar", &[("com/evil/I.class", &cw.finish())]);
    let f = scan(&jar);
    assert_eq!(f.len(), 1);
    assert_eq!(f[0].kind, Kind::ClipboardStealerPattern);
}

#[test]
fn malformed_class_is_skipped_and_later_entries_still_scanned() {
    let tmp = tempfile::tempdir().unwrap();
    let jar = build_jar(
        tmp.path(),
        "mod.jar",
        &[
            ("Bad.class", &[0xCA, 0xFE, 0xBA, 0xBE, 0, 0]),
            ("evil.exe", &[1]),
        ],
    );
    let f = scan(&jar);
    assert_eq!(f.len(), 1);
    assert_eq!(f[0].kind, Kind::EmbeddedExecutable);
}

#[test]
fn non_zip_jar_is_an_error() {
    let tmp = tempfile::tempdir().unwrap();
    let p = tmp.path().join("broken.jar");
    std::fs::write(&p, b"not a zip").unwrap();
    assert!(scan_jar(&p).is_err());
}

#[test]
fn scan_folder_skips_built_in_exclusions() {
    let tmp = tempfile::tempdir().unwrap();
    build_jar(
        &tmp.path().join("libraries"),
        "evil.jar",
        &[("trojan.exe", &[1])],
    );
    build_jar(&tmp.path().join("bin"), "evil.jar", &[("trojan.exe", &[1])]);
    build_jar(
        &tmp.path().join("Runtime").join("x"),
        "evil.jar",
        &[("trojan.exe", &[1])],
    );
    build_jar(
        &tmp.path().join("mods"),
        "evil2.jar",
        &[("trojan2.exe", &[1])],
    );
    let f = scan_folder::<&str>(tmp.path(), &[], 2, &CancelToken::new()).unwrap();
    assert_eq!(f.len(), 1);
    assert!(f[0].file.to_string_lossy().contains("evil2.jar"));
}

#[test]
fn scan_folder_manifest_cannot_exempt_mods() {
    let tmp = tempfile::tempdir().unwrap();
    build_jar(
        &tmp.path().join("mods"),
        "evil.jar",
        &[("trojan.exe", &[1])],
    );
    let f = scan_folder(
        tmp.path(),
        &["mods", "./MODS/", "."],
        2,
        &CancelToken::new(),
    )
    .unwrap();
    assert_eq!(f.len(), 1);
}

#[test]
fn scan_folder_honors_legitimate_exclusion_with_any_separator() {
    let tmp = tempfile::tempdir().unwrap();
    build_jar(
        &tmp.path().join("custom-tools"),
        "evil.jar",
        &[("trojan.exe", &[1])],
    );
    build_jar(
        &tmp.path().join("a").join("b"),
        "evil.jar",
        &[("trojan.exe", &[1])],
    );
    build_jar(
        &tmp.path().join("a").join("c"),
        "evil.jar",
        &[("trojan.exe", &[1])],
    );
    let f = scan_folder(
        tmp.path(),
        &["custom-tools", "a\\b"],
        2,
        &CancelToken::new(),
    )
    .unwrap();
    assert_eq!(f.len(), 1);
    assert!(
        f[0].file
            .to_string_lossy()
            .replace('\\', "/")
            .contains("a/c/evil.jar")
    );
}

#[test]
fn scan_folder_is_case_insensitive_on_jar_suffix_and_deterministic() {
    let tmp = tempfile::tempdir().unwrap();
    for n in ["a.JAR", "b.jar", "c.Jar"] {
        build_jar(&tmp.path().join("mods"), n, &[("x.exe", &[1])]);
    }
    build_jar(&tmp.path().join("mods"), "d.zip", &[("x.exe", &[1])]);
    let f = scan_folder::<&str>(tmp.path(), &[], 3, &CancelToken::new()).unwrap();
    let names: Vec<String> = f
        .iter()
        .map(|f| f.file.file_name().unwrap().to_string_lossy().into_owned())
        .collect();
    assert_eq!(names.len(), 3);
    let again: Vec<String> = scan_folder::<&str>(tmp.path(), &[], 1, &CancelToken::new())
        .unwrap()
        .iter()
        .map(|f| f.file.file_name().unwrap().to_string_lossy().into_owned())
        .collect();
    assert_eq!(names, again);
}

#[test]
fn scan_folder_unreadable_root_is_an_error_and_bad_jar_is_not() {
    let tmp = tempfile::tempdir().unwrap();
    assert!(scan_folder::<&str>(&tmp.path().join("missing"), &[], 1, &CancelToken::new()).is_err());
    std::fs::create_dir_all(tmp.path().join("mods")).unwrap();
    std::fs::write(tmp.path().join("mods").join("broken.jar"), b"nope").unwrap();
    assert!(
        scan_folder::<&str>(tmp.path(), &[], 1, &CancelToken::new())
            .unwrap()
            .is_empty()
    );
}

#[test]
fn scan_folder_cancelled() {
    let tmp = tempfile::tempdir().unwrap();
    build_jar(
        &tmp.path().join("mods"),
        "evil.jar",
        &[("trojan.exe", &[1])],
    );
    let c = CancelToken::new();
    c.cancel();
    let e = scan_folder::<&str>(tmp.path(), &[], 1, &c).unwrap_err();
    assert_eq!(e.kind(), std::io::ErrorKind::Interrupted);
}
