// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The typed facade: defaults, get-or-init vs plain reads, validation, per-pack maps, secrets
//! and the Rust-only keys.

use std::error::Error;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use mmcl_config::keys::*;
use mmcl_config::{
    Config, ConfigStore, Document, ScanFrequency, SecretCipher, StoreHooks, StoreOptions,
    WindowBounds,
};
use serde_json::{Value, json};

fn config_with(doc: Value, is_dev: bool) -> Config {
    let doc: Document = doc.as_object().unwrap().clone();
    Config::new(ConfigStore::in_memory_with(
        doc,
        StoreHooks::silent(),
        StoreOptions {
            debounce: Duration::from_millis(500),
            is_dev,
            default_mcp_port: 47833,
        },
    ))
}

fn empty() -> Config {
    config_with(json!({}), false)
}

#[test]
fn get_or_init_keys_persist_their_defaults() {
    let c = empty();
    assert_eq!(c.min_ram_mb(), 512);
    assert_eq!(c.max_ram_mb(), 2048);
    assert_eq!(c.max_ram_gb(), 2.0);
    assert_eq!(c.custom_jvm_args(), JVM_ARGS_VALUE_DEFAULT);
    assert_eq!(c.theme(), "Native (Mica)");
    assert!(c.wavy_progress());
    assert!(!c.reduce_motion());
    assert!(c.pack_colors());
    assert_eq!(c.ui_scale_percent(), 100);
    assert!(!c.debug_logging());
    assert!(c.enhanced_logging());
    assert!(c.discord_rpc());
    assert!(c.discord_invites());
    assert!(c.resizable_windows());
    assert!(!c.in_game_console());
    assert!(!c.running_games_docked());
    assert_eq!(c.running_games_dock_height(), 420);
    assert!(c.launcher_update_check());
    assert!(c.uri_handler_enabled());
    assert!(!c.mcp_server_enabled());
    assert!(c.mcp_auto_approve_read_only());
    assert_eq!(c.mcp_tool_policies(), "");
    assert!(!c.mcp_allow_state_changes());
    assert_eq!(c.mcp_port(), 47833);
    assert_eq!(c.mcp_token(), "");
    assert_eq!(c.default_account_uuid(), "");
    assert!(!c.quick_start_completed());
    assert!(c.battery_throttle());
    assert!(c.lwjgl_arm_patch());
    assert!(!c.rgb_enable());
    assert_eq!(c.rgb_backend(), "auto");
    assert!(c.rgb_use_pack_colors());
    assert!(c.rgb_highlight_keys());
    assert!(c.rgb_enable_open_rgb());
    assert!(c.rgb_enable_chroma_native());
    assert!(!c.rgb_enable_chroma_rest());
    assert!(c.rgb_enable_windows_dl());
    assert!(c.rgb_enable_corsair());
    assert!(c.rgb_enable_asus_aura());
    assert!(c.rgb_menu_effect_enable());
    assert_eq!(c.rgb_effect_style(), "breathe");
    assert!(!c.proxy_enable());
    assert_eq!(c.proxy_host(), "");
    assert_eq!(c.proxy_port(), 8080);
    assert_eq!(c.proxy_type(), "HTTP");
    assert_eq!(c.last_mod_pack(), "");

    let doc = c.store().snapshot();
    for key in [
        MIN_RAM_KEY,
        MAX_RAM_KEY,
        JVM_ARGS_KEY,
        THEME_KEY,
        WAVY_PROGRESS_KEY,
        UI_SCALE_KEY,
        LOG_DEBUG_ENABLE_KEY,
        MCP_PORT_KEY,
        RGB_BACKEND_KEY,
        RGB_EFFECT_STYLE_KEY,
        PROXY_TYPE_KEY,
        LAST_MP_KEY,
    ] {
        assert!(doc.contains_key(key), "{key} should be default-written");
    }
}

#[test]
fn plain_keys_never_write_their_defaults() {
    let c = empty();
    assert_eq!(c.locale_override(), "");
    assert!(c.show_pack_backgrounds());
    assert_eq!(c.image_cycle_interval(), "30s");
    assert!(!c.image_cycle_shuffle());
    assert_eq!(c.console_log_max_lines(), 10_000);
    assert!(c.backup_auto_before_update());
    assert_eq!(c.backup_max_count_per_pack(), 3);
    assert_eq!(c.backup_max_age_days(), 14);
    assert!(!c.backup_include_saves());
    let b = c.window_bounds();
    assert!(b.x.is_nan() && b.y.is_nan() && b.width.is_nan() && b.height.is_nan());
    assert!(!b.maximized);
    assert_eq!(c.default_scan_frequency(), ScanFrequency::Daily);
    assert_eq!(c.auth_client_id(), "00000000402b5328");
    assert!(!c.auth_wam());
    assert_eq!(c.gamepad_mode(), None);
    assert!(c.store().snapshot().is_empty());
}

#[test]
fn list_keys_insert_an_empty_array_without_scheduling() {
    let c = empty();
    assert!(c.installed_mod_packs().is_empty());
    assert!(c.installed_vanilla_versions().is_empty());
    assert_eq!(
        c.store().get_value(MOD_PACKS_INSTALLED_KEY),
        Some(json!([]))
    );
    c.set_installed_mod_packs(&["a", "b"]);
    assert_eq!(c.installed_mod_packs(), vec!["a", "b"]);
    let c = config_with(json!({"modpacks": ["a", 5, null, {"x": 1}, true]}), false);
    assert_eq!(c.installed_mod_packs(), vec!["a", "5", "true"]);
    let c = config_with(json!({"modpacks": "oops"}), false);
    assert!(c.installed_mod_packs().is_empty());
}

#[test]
fn dev_builds_always_debug_and_the_setter_notifies_the_logger() {
    assert!(config_with(json!({"debug": false}), true).debug_logging());
    let seen = Arc::new(AtomicBool::new(false));
    let s = seen.clone();
    let c = Config::new(ConfigStore::in_memory_with(
        Document::new(),
        StoreHooks {
            on_error_async: Arc::new(|_| {}),
            on_debug_changed: Arc::new(move |v| s.store(v, Ordering::SeqCst)),
        },
        StoreOptions::default(),
    ));
    c.set_debug_logging(true);
    assert!(seen.load(Ordering::SeqCst));
    assert_eq!(c.store().get_value(LOG_DEBUG_ENABLE_KEY), Some(json!(true)));
}

#[test]
fn stored_jvm_args_failing_validation_read_as_the_default() {
    let c = config_with(
        json!({"jvmArgs": "-Xmx1G\n-Dx=${auth_access_token}"}),
        false,
    );
    assert_eq!(c.custom_jvm_args(), JVM_ARGS_VALUE_DEFAULT);
    assert!(c.set_custom_jvm_args("a\u{7f}").is_err());
    assert!(c.set_custom_jvm_args("").is_ok());
    assert_eq!(c.custom_jvm_args(), "");
}

#[test]
fn validated_setters_and_getters() {
    let c = empty();
    c.set_mcp_port(0);
    assert_eq!(c.mcp_port(), 47833);
    c.set_mcp_port(70000);
    assert_eq!(c.mcp_port(), 47833);
    c.set_mcp_port(5000);
    assert_eq!(c.mcp_port(), 5000);

    c.set_rgb_backend("corsair");
    assert_eq!(c.store().get_value(RGB_BACKEND_KEY), Some(json!("auto")));
    c.set_rgb_backend("openrgb");
    assert_eq!(c.rgb_backend(), "openrgb");
    let c2 = config_with(json!({"rgbBackend": "asus-aura"}), false);
    assert_eq!(c2.rgb_backend(), "auto");

    c.set_rgb_effect_style("rainbow");
    c.set_rgb_effect_style("disco");
    assert_eq!(c.rgb_effect_style(), "rainbow");
    let c3 = config_with(json!({"rgbEffectStyle": "disco"}), false);
    assert_eq!(c3.rgb_effect_style(), "breathe");

    c.set_image_cycle_interval("2m");
    assert_eq!(
        c.store().get_value(IMAGE_CYCLE_INTERVAL_KEY),
        Some(json!("2m"))
    );
    assert_eq!(c.image_cycle_interval(), "30s");
    c.set_image_cycle_interval("never");
    assert_eq!(c.image_cycle_interval(), "never");

    c.set_console_log_max_lines(-5);
    assert_eq!(c.console_log_max_lines(), 0);
    c.set_backup_max_count_per_pack(-1);
    assert_eq!(c.backup_max_count_per_pack(), 0);
    c.set_backup_max_age_days(-1);
    assert_eq!(c.backup_max_age_days(), 0);
}

#[test]
fn window_bounds_round_trip_and_nan_is_stored_as_null() {
    let c = empty();
    c.set_window_bounds(WindowBounds {
        x: 10.0,
        y: f64::NAN,
        width: 800.5,
        height: 600.0,
        maximized: true,
    });
    assert_eq!(c.store().get_value(WINDOW_Y_KEY), Some(Value::Null));
    let b = c.window_bounds();
    assert_eq!(b.x, 10.0);
    assert!(b.y.is_nan());
    assert_eq!(b.width, 800.5);
    assert!(b.maximized);
    assert!(!b.is_complete());
}

#[test]
fn always_verify_map() {
    let c = empty();
    assert!(!c.always_verify_on_launch("u"));
    assert!(!c.always_verify_on_launch(""));
    c.set_always_verify_on_launch("u", true);
    c.set_always_verify_on_launch(" ", true);
    assert!(c.always_verify_on_launch("u"));
    assert_eq!(
        c.store().get_value(ALWAYS_VERIFY_BY_PACK_KEY),
        Some(json!({"u": true}))
    );
    // A malformed section reads false and is replaced on write (Java threw).
    let c = config_with(json!({"alwaysVerifyOnLaunchByPack": [1]}), false);
    assert!(!c.always_verify_on_launch("u"));
    c.set_always_verify_on_launch("u", true);
    assert!(c.always_verify_on_launch("u"));
}

#[test]
fn scan_frequency_maps() {
    let c = empty();
    assert_eq!(
        c.effective_scan_frequency_for_pack("u"),
        ScanFrequency::Daily
    );
    c.set_default_scan_frequency(ScanFrequency::Disabled);
    assert_eq!(
        c.store().get_value(DEFAULT_SCAN_FREQUENCY_KEY),
        Some(json!("DISABLED"))
    );
    assert_eq!(
        c.effective_scan_frequency_for_pack("u"),
        ScanFrequency::Disabled
    );
    c.set_scan_frequency_for_pack("u", Some(ScanFrequency::EveryTime));
    assert_eq!(
        c.scan_frequency_for_pack("u"),
        Some(ScanFrequency::EveryTime)
    );
    assert_eq!(
        c.effective_scan_frequency_for_pack("u"),
        ScanFrequency::EveryTime
    );
    c.set_scan_frequency_for_pack("u", None);
    assert_eq!(c.scan_frequency_for_pack("u"), None);
    assert_eq!(
        c.store().get_value(SCAN_FREQUENCY_BY_PACK_KEY),
        Some(json!({}))
    );

    let c = config_with(
        json!({"defaultScanFrequency": "weekly", "scanFrequencyByPack": {"a": "bogus", "b": " "}}),
        false,
    );
    assert_eq!(c.default_scan_frequency(), ScanFrequency::Daily);
    assert_eq!(c.scan_frequency_for_pack("a"), None);
    assert_eq!(c.scan_frequency_for_pack("b"), None);
    let c = config_with(json!({"scanFrequencyByPack": "x"}), false);
    assert_eq!(c.scan_frequency_for_pack("a"), None);
    c.set_scan_frequency_for_pack("a", Some(ScanFrequency::OnChangesOnly));
    assert_eq!(
        c.scan_frequency_for_pack("a"),
        Some(ScanFrequency::OnChangesOnly)
    );
}

/// Reverses the text and prefixes it; fails on demand.
struct FakeCipher {
    fail: bool,
}

impl SecretCipher for FakeCipher {
    fn encrypt(&self, plaintext: &str) -> Result<String, Box<dyn Error + Send + Sync>> {
        if self.fail {
            return Err("no key".into());
        }
        Ok(format!(
            "enc:{}",
            plaintext.chars().rev().collect::<String>()
        ))
    }

    fn decrypt(&self, encoded: &str) -> Result<String, Box<dyn Error + Send + Sync>> {
        match encoded.strip_prefix("enc:") {
            Some(rest) if !self.fail => Ok(rest.chars().rev().collect()),
            _ => Err("tampered".into()),
        }
    }
}

#[test]
fn curseforge_key_is_stored_encrypted() {
    let c = empty();
    let ok = FakeCipher { fail: false };
    assert!(!c.has_curseforge_api_key());
    assert_eq!(c.curseforge_api_key(&ok), None);
    c.set_curseforge_api_key(&ok, Some("abc123"));
    assert_eq!(
        c.store().get_value(CURSEFORGE_API_KEY_KEY),
        Some(json!("enc:321cba"))
    );
    assert!(c.has_curseforge_api_key());
    assert_eq!(c.curseforge_api_key(&ok).as_deref(), Some("abc123"));
    assert_eq!(c.curseforge_api_key(&FakeCipher { fail: true }), None);

    // An encryption failure stores nothing.
    c.set_curseforge_api_key(&FakeCipher { fail: true }, Some("new"));
    assert_eq!(c.curseforge_api_key(&ok).as_deref(), Some("abc123"));

    c.set_curseforge_api_key(&ok, Some("  "));
    assert!(!c.has_curseforge_api_key());
    assert!(!c.store().contains(CURSEFORGE_API_KEY_KEY));

    let c = config_with(json!({"curseForgeApiKey": 5}), false);
    assert!(!c.has_curseforge_api_key());
}

#[test]
fn rust_only_sign_in_and_gamepad_keys() {
    let c = empty();
    assert!(!c.has_custom_auth_client_id());
    c.set_auth_wam(true);
    assert!(!c.wam_active(), "WAM needs a client ID of our own");
    c.set_auth_client_id("  11111111-2222-3333-4444-555555555555 ");
    assert_eq!(c.auth_client_id(), "11111111-2222-3333-4444-555555555555");
    assert!(c.wam_active());
    c.set_auth_client_id("");
    assert!(!c.store().contains(AUTH_CLIENT_ID_KEY));
    assert_eq!(c.auth_client_id(), AUTH_CLIENT_ID_DEFAULT);

    c.set_gamepad_mode(Some(true));
    assert_eq!(c.gamepad_mode(), Some(true));
    assert_eq!(c.store().get_value(UI_GAMEPAD_MODE_KEY), Some(json!(true)));
    c.set_gamepad_mode(None);
    assert_eq!(c.gamepad_mode(), None);
    assert!(!c.store().contains(UI_GAMEPAD_MODE_KEY));

    let c = config_with(
        json!({"auth.clientId": "   ", "ui.gamepadMode": null}),
        false,
    );
    assert_eq!(c.auth_client_id(), AUTH_CLIENT_ID_DEFAULT);
    assert_eq!(c.gamepad_mode(), None);
}
