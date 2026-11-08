// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Load, quarantine, migration on disk, atomic and debounced writes, Java interop.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use mmcl_config::keys::*;
use mmcl_config::migrate::V6_HOST_PREFIX;
use mmcl_config::{Config, ConfigStore, Document, LoadOutcome, StoreHooks, StoreOptions};
use serde_json::{Value, json};

fn options(debounce_ms: u64) -> StoreOptions {
    StoreOptions {
        debounce: Duration::from_millis(debounce_ms),
        is_dev: false,
        default_mcp_port: 47833,
    }
}

fn counting_hooks() -> (StoreHooks, Arc<AtomicUsize>) {
    let errors = Arc::new(AtomicUsize::new(0));
    let e = errors.clone();
    let hooks = StoreHooks {
        on_error_async: Arc::new(move |_| {
            e.fetch_add(1, Ordering::SeqCst);
        }),
        on_debug_changed: Arc::new(|_| {}),
    };
    (hooks, errors)
}

fn config_file(dir: &Path) -> PathBuf {
    dir.join("config").join("configuration.json")
}

fn read_doc(path: &Path) -> Document {
    let bytes = fs::read(path).unwrap();
    serde_json::from_slice::<Value>(&bytes)
        .unwrap()
        .as_object()
        .unwrap()
        .clone()
}

fn corrupt_files(dir: &Path) -> Vec<PathBuf> {
    fs::read_dir(dir)
        .unwrap()
        .map(|e| e.unwrap().path())
        .filter(|p| {
            p.file_name()
                .unwrap()
                .to_string_lossy()
                .starts_with("configuration.json.corrupt-")
        })
        .collect()
}

#[test]
fn missing_file_gets_first_run_defaults_and_is_written() {
    let dir = tempfile::tempdir().unwrap();
    let file = config_file(dir.path());
    let store = ConfigStore::open_with(file.clone(), StoreHooks::silent(), options(10_000));
    assert_eq!(store.load_outcome(), &LoadOutcome::Missing);
    assert!(store.has_pending_write());
    store.flush();
    let doc = read_doc(&file);
    assert_eq!(
        Value::Object(doc),
        json!({
            "configVersion": 7, "minRAM": 512, "maxRAM": 2048, "debug": false,
            "discordRpc": true, "resizableWindows": true, "modpacks": []
        })
    );
    assert!(!file.with_file_name("configuration.json.tmp").exists());
}

#[test]
fn corrupt_shapes_are_quarantined_and_replaced_by_defaults() {
    for (name, bytes) in [
        ("truncated", &b"{\"minRAM\": 1024, \"maxR"[..]),
        ("zero", &b""[..]),
        ("array", &b"[1, 2, 3]"[..]),
        ("primitive", &b"42"[..]),
        ("whitespace", &b"  \n "[..]),
        ("not utf8", &[0xff, 0xfe, 0x00][..]),
    ] {
        let dir = tempfile::tempdir().unwrap();
        let file = config_file(dir.path());
        fs::create_dir_all(file.parent().unwrap()).unwrap();
        fs::write(&file, bytes).unwrap();
        let (hooks, errors) = counting_hooks();
        let store = ConfigStore::open_with(file.clone(), hooks, options(10_000));
        let LoadOutcome::Corrupt { quarantined } = store.load_outcome().clone() else {
            panic!("{name}: expected corrupt, got {:?}", store.load_outcome());
        };
        let q = quarantined.expect("quarantined path");
        assert_eq!(fs::read(&q).unwrap(), bytes, "{name}: contents preserved");
        assert_eq!(corrupt_files(file.parent().unwrap()).len(), 1, "{name}");
        assert_eq!(errors.load(Ordering::SeqCst), 1, "{name}: user told once");
        assert_eq!(store.get_i64(MAX_RAM_KEY, 0), 2048, "{name}");
        store.flush();
        assert_eq!(read_doc(&file)[CONFIG_VERSION_KEY], json!(7), "{name}");
    }
}

#[test]
fn lenient_json_is_read_not_quarantined() {
    let dir = tempfile::tempdir().unwrap();
    let file = config_file(dir.path());
    fs::create_dir_all(file.parent().unwrap()).unwrap();
    fs::write(
        &file,
        "{ // hand edited\n configVersion: 7, 'theme': 'Dark', maxRAM: 4096, }",
    )
    .unwrap();
    let store = ConfigStore::open_with(file.clone(), StoreHooks::silent(), options(10_000));
    assert_eq!(store.load_outcome(), &LoadOutcome::Loaded);
    assert_eq!(store.get_string(THEME_KEY, ""), "Dark");
    assert_eq!(store.get_i64(MAX_RAM_KEY, 0), 4096);
    assert!(corrupt_files(file.parent().unwrap()).is_empty());
}

#[test]
fn old_versions_migrate_on_open_and_are_written() {
    let dir = tempfile::tempdir().unwrap();
    let file = config_file(dir.path());
    fs::create_dir_all(file.parent().unwrap()).unwrap();
    let mica = format!("{V6_HOST_PREFIX}packs/a.json");
    fs::write(
        &file,
        serde_json::to_vec(&json!({
            "configVersion": 4,
            "resizableWindows": false,
            "modpacks": [mica, "https://example.com/b.json", 7],
            "lastModPack": mica,
            "jvmArgs": JVM_ARGS_VALUE_DEFAULT_PRE_2026_10,
        }))
        .unwrap(),
    )
    .unwrap();
    let store = ConfigStore::open_with(file.clone(), StoreHooks::silent(), options(10_000));
    assert!(store.has_pending_write());
    store.flush();
    let doc = read_doc(&file);
    assert_eq!(doc["configVersion"], json!(7));
    assert_eq!(doc["resizableWindows"], json!(true));
    assert_eq!(
        doc["modpacks"],
        json!([
            format!("{V6_HOST_PREFIX}packs/a.mmcjson"),
            "https://example.com/b.json",
            7
        ])
    );
    assert_eq!(
        doc["lastModPack"],
        json!(format!("{V6_HOST_PREFIX}packs/a.mmcjson"))
    );
    assert_eq!(doc["jvmArgs"], json!(JVM_ARGS_VALUE_DEFAULT));
    assert_eq!(doc["discordInvites"], json!(true));
}

#[test]
fn writes_are_atomic_and_owner_only() {
    let dir = tempfile::tempdir().unwrap();
    let file = config_file(dir.path());
    let store = ConfigStore::open_with(file.clone(), StoreHooks::silent(), options(10_000));
    store.set_value(MCP_TOKEN_KEY, json!("secret-token"));
    store.flush();
    assert!(file.is_file());
    assert!(!file.with_file_name("configuration.json.tmp").exists());
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = fs::metadata(&file).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600);
    }
    assert_eq!(read_doc(&file)[MCP_TOKEN_KEY], json!("secret-token"));
}

#[test]
fn debounce_coalesces_a_burst_into_one_write() {
    let dir = tempfile::tempdir().unwrap();
    let file = config_file(dir.path());
    fs::create_dir_all(file.parent().unwrap()).unwrap();
    fs::write(&file, br#"{"configVersion":7}"#).unwrap();
    let store = ConfigStore::open_with(file.clone(), StoreHooks::silent(), options(150));
    assert!(!store.has_pending_write(), "a current file needs no write");
    for i in 0..25 {
        store.set_value(MIN_RAM_KEY, json!(i));
    }
    assert_eq!(
        store.write_count(),
        0,
        "nothing written before the debounce"
    );
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    while store.write_count() == 0 && std::time::Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(20));
    }
    std::thread::sleep(Duration::from_millis(400));
    assert_eq!(store.write_count(), 1);
    assert_eq!(read_doc(&file)[MIN_RAM_KEY], json!(24));
}

#[test]
fn durable_set_persists_without_a_pending_write() {
    // Regression: Java's flushNow() after an unscheduled change wrote nothing.
    let dir = tempfile::tempdir().unwrap();
    let file = config_file(dir.path());
    fs::create_dir_all(file.parent().unwrap()).unwrap();
    fs::write(&file, br#"{"configVersion":7}"#).unwrap();
    let store = ConfigStore::open_with(file.clone(), StoreHooks::silent(), options(60_000));
    let config = Config::new(store.clone());
    assert!(!store.has_pending_write());
    config.set_custom_jvm_args("-Xss2m").unwrap();
    assert_eq!(read_doc(&file)[JVM_ARGS_KEY], json!("-Xss2m"));
    assert!(
        config
            .set_custom_jvm_args("-Da=${auth_access_token}")
            .is_err()
    );
    assert_eq!(read_doc(&file)[JVM_ARGS_KEY], json!("-Xss2m"));
}

#[test]
fn flush_never_returns_before_a_due_debounced_write_lands() {
    // Regression: the writer thread used to claim the deadline before taking the write lock,
    // so a flush in that window saw nothing pending and nothing running and returned early.
    let dir = tempfile::tempdir().unwrap();
    let file = config_file(dir.path());
    fs::create_dir_all(file.parent().unwrap()).unwrap();
    fs::write(&file, br#"{"configVersion":7}"#).unwrap();
    let store = ConfigStore::open_with(file.clone(), StoreHooks::silent(), options(1));
    for i in 0..150 {
        store.set_value(MIN_RAM_KEY, json!(i));
        // Land the flush near the 1 ms deadline so it races the writer thread.
        std::thread::sleep(Duration::from_micros(900 + (i % 5) * 100));
        store.flush();
        assert_eq!(read_doc(&file)[MIN_RAM_KEY], json!(i), "iteration {i}");
    }
}

#[test]
fn dropping_the_store_writes_a_pending_change() {
    let dir = tempfile::tempdir().unwrap();
    let file = config_file(dir.path());
    fs::create_dir_all(file.parent().unwrap()).unwrap();
    fs::write(&file, br#"{"configVersion":7}"#).unwrap();
    {
        let store = ConfigStore::open_with(file.clone(), StoreHooks::silent(), options(60_000));
        store.set_value(THEME_KEY, json!("Creeper"));
    }
    assert_eq!(read_doc(&file)[THEME_KEY], json!("Creeper"));
}

#[test]
fn try_flush_writes_when_uncontended() {
    let dir = tempfile::tempdir().unwrap();
    let file = config_file(dir.path());
    let store = ConfigStore::open_with(file.clone(), StoreHooks::silent(), options(60_000));
    store.set_value(THEME_KEY, json!("Light"));
    assert!(store.try_flush());
    assert_eq!(read_doc(&file)[THEME_KEY], json!("Light"));
    assert!(store.try_flush(), "nothing pending");
}

#[test]
fn a_write_failure_is_reported_and_leaves_no_tmp() {
    let dir = tempfile::tempdir().unwrap();
    // The config folder is a file, so the write cannot create it.
    let blocker = dir.path().join("config");
    fs::write(&blocker, b"x").unwrap();
    let (hooks, errors) = counting_hooks();
    let store = ConfigStore::open_with(blocker.join("configuration.json"), hooks, options(60_000));
    store.flush();
    assert_eq!(errors.load(Ordering::SeqCst), 1);
}

/// A config as the Java launcher writes it: compact Gson output with HTML-safe escapes.
const JAVA_SAMPLE: &str = r#"{"configVersion":7,"minRAM":1024,"maxRAM":6144,"debug":false,"discordRpc":true,"resizableWindows":true,"modpacks":["https://micauseaststorage.blob.core.windows.net/mc-launcher-api/alto.mmcjson","https://example.com/p.mmcjson"],"enhancedLogging":true,"jvmArgs":"-XX:+UseG1GC -XX:MaxGCPauseMillis\u003d50 -XX:G1HeapRegionSize\u003d1M","lastModPack":"https://example.com/p.mmcjson","theme":"Native (Mica)","inGameConsole":false,"proxyEnable":false,"proxyHost":"","proxyPort":8080,"proxyType":"HTTP","vanillaVersions":["1.20.4"],"lwjglArmPatch":true,"discordInvites":true,"windowX":120.0,"windowY":80.5,"windowWidth":1280.0,"windowHeight":800.0,"windowMaximized":false,"localeOverride":"pt-BR","alwaysVerifyOnLaunchByPack":{"https://example.com/p.mmcjson":true},"scanFrequencyByPack":{"https://example.com/p.mmcjson":"EVERY_TIME"},"accountOverrideByPack":{"vanilla:1.20.4":"0f5d2c1a-1111-2222-3333-444455556666"},"mcpToolPolicies":"{\"launch_pack\":\"ASK\"}","proxyHost2":"a\u0026b \u003cc\u003e it\u0027s","uiScalePercent":125,"rgbBackend":"chroma-native"}"#;

#[test]
fn a_java_written_file_round_trips_through_rust() {
    let dir = tempfile::tempdir().unwrap();
    let file = config_file(dir.path());
    fs::create_dir_all(file.parent().unwrap()).unwrap();
    fs::write(&file, JAVA_SAMPLE).unwrap();
    let store = ConfigStore::open_with(file.clone(), StoreHooks::silent(), options(60_000));
    assert_eq!(store.load_outcome(), &LoadOutcome::Loaded);
    let config = Config::new(store.clone());

    assert_eq!(
        config.custom_jvm_args(),
        "-XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=1M"
    );
    assert_eq!(config.max_ram_mb(), 6144);
    assert_eq!(config.installed_vanilla_versions(), vec!["1.20.4"]);
    assert_eq!(config.window_bounds().y, 80.5);
    assert_eq!(config.locale_override(), "pt-BR");
    assert!(config.always_verify_on_launch("https://example.com/p.mmcjson"));
    assert_eq!(
        config.scan_frequency_for_pack("https://example.com/p.mmcjson"),
        Some(mmcl_config::ScanFrequency::EveryTime)
    );
    assert_eq!(config.ui_scale_percent(), 125);
    assert_eq!(config.rgb_backend(), "chroma-native");
    assert_eq!(config.mcp_tool_policies(), r#"{"launch_pack":"ASK"}"#);
    // Gson's HTML-safe escapes decode, and an unknown key is kept as-is.
    assert_eq!(store.get_string("proxyHost2", ""), "a&b <c> it's");

    store.write_now();
    let original: Value = serde_json::from_str(JAVA_SAMPLE).unwrap();
    let written_text = fs::read_to_string(&file).unwrap();
    let written: Value = serde_json::from_str(&written_text).unwrap();
    // Every value survives (reads above inserted a few get-or-init defaults: compare the
    // original keys only), in the original order.
    let w = written.as_object().unwrap();
    for (k, v) in original.as_object().unwrap() {
        assert_eq!(w.get(k), Some(v), "{k}");
    }
    let orig_keys: Vec<&String> = original.as_object().unwrap().keys().collect();
    let written_keys: Vec<&String> = w.keys().take(orig_keys.len()).collect();
    assert_eq!(orig_keys, written_keys);
    // Compact, and doubles keep their fraction so Java reads them back as doubles.
    assert!(!written_text.contains('\n'));
    assert!(written_text.contains(r#""windowX":120.0"#));
}

#[test]
fn import_replaces_migrates_and_writes_immediately() {
    let dir = tempfile::tempdir().unwrap();
    let file = config_file(dir.path());
    let store = ConfigStore::open_with(file.clone(), StoreHooks::silent(), options(60_000));
    store.flush();
    let config = Config::new(store);
    let src = dir.path().join("import.json");
    fs::write(
        &src,
        br#"{"configVersion":6,"theme":"Dark","jvmArgs":"-Xss2m"}"#,
    )
    .unwrap();
    config.import_from(&src).unwrap();
    let doc = read_doc(&file);
    assert_eq!(doc[THEME_KEY], json!("Dark"));
    assert_eq!(doc[CONFIG_VERSION_KEY], json!(7));
    assert_eq!(doc[MIN_RAM_KEY], json!(512));

    let bad = dir.path().join("bad.json");
    fs::write(&bad, b"[1]").unwrap();
    let err = config.import_from(&bad).unwrap_err();
    assert_eq!(err.key(), "config.error.importFailed");
    assert_eq!(config.theme(), "Dark", "a failed import changes nothing");

    let out = dir.path().join("export").join("settings.json");
    config.export_to(&out).unwrap();
    assert_eq!(read_doc(&out), read_doc(&file));
}
