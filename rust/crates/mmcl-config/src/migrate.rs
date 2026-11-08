// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Schema migrations (`ConfigManager.migrateConfigIfNeeded`). Pure functions over the document,
//! so a Rust-migrated config holds exactly the values a Java-migrated one does.

use serde_json::{Map, Value};

use crate::coerce;
use crate::keys::*;

pub use crate::keys::CONFIG_VERSION;

/// The Mica host whose `.json` pack URLs moved to `.mmcjson` in v6.
pub const V6_HOST_PREFIX: &str = "https://micauseaststorage.blob.core.windows.net/mc-launcher-api/";
const V6_OLD_SUFFIX: &str = ".json";
const V6_NEW_SUFFIX: &str = ".mmcjson";

/// What [`migrate`] did.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MigrationReport {
    /// The stored `configVersion` (0 when absent or unreadable).
    pub from: i64,
    /// `true` when the document was migrated (and so changed and must be written).
    pub migrated: bool,
    /// v5: `resizableWindows` was flipped back to true.
    pub v5_resizable_windows: bool,
    /// v6: number of pack URLs rewritten from `.json` to `.mmcjson`.
    pub v6_urls: usize,
    /// v7: the old default JVM args were replaced.
    pub v7_jvm_args: bool,
}

/// The defaults the "touch every key" step writes when absent, in Java's getter order.
fn touched_defaults(doc: &mut Map<String, Value>) {
    let defaults: [(&str, Value); 18] = [
        (MIN_RAM_KEY, Value::from(MIN_RAM_MEGABYTES_DEFAULT)),
        (MAX_RAM_KEY, Value::from(MAX_RAM_MEGABYTES_DEFAULT)),
        (LOG_DEBUG_ENABLE_KEY, Value::from(LOG_DEBUG_ENABLE_DEFAULT)),
        (
            DISCORD_RPC_ENABLE_KEY,
            Value::from(DISCORD_RPC_ENABLE_DEFAULT),
        ),
        (
            RESIZE_WINDOWS_ENABLE_KEY,
            Value::from(RESIZE_WINDOWS_ENABLE_DEFAULT),
        ),
        (
            LOG_ENHANCED_ENABLE_KEY,
            Value::from(LOG_ENHANCED_ENABLE_DEFAULT),
        ),
        (JVM_ARGS_KEY, Value::from(JVM_ARGS_VALUE_DEFAULT)),
        (LAST_MP_KEY, Value::from("")),
        (THEME_KEY, Value::from(THEME_DEFAULT)),
        (MOD_PACKS_INSTALLED_KEY, Value::Array(Vec::new())),
        (
            INGAME_CONSOLE_ENABLE_KEY,
            Value::from(INGAME_CONSOLE_ENABLE_DEFAULT),
        ),
        (PROXY_ENABLE_KEY, Value::from(PROXY_ENABLE_DEFAULT)),
        (PROXY_HOST_KEY, Value::from(PROXY_HOST_DEFAULT)),
        (PROXY_PORT_KEY, Value::from(PROXY_PORT_DEFAULT)),
        (PROXY_TYPE_KEY, Value::from(PROXY_TYPE_DEFAULT)),
        (VANILLA_VERSIONS_INSTALLED_KEY, Value::Array(Vec::new())),
        (
            LWJGL_ARM_PATCH_ENABLE_KEY,
            Value::from(LWJGL_ARM_PATCH_ENABLE_DEFAULT),
        ),
        (
            DISCORD_INVITES_ENABLE_KEY,
            Value::from(DISCORD_INVITES_ENABLE_DEFAULT),
        ),
    ];
    for (key, value) in defaults {
        if !doc.contains_key(key) {
            doc.insert(key.to_string(), value);
        }
    }
}

/// The stored schema version: a coercing read with default 0 (Java's uncoerced `getAsInt`
/// threw on a non-numeric value).
pub fn stored_version(doc: &Map<String, Value>) -> i64 {
    doc.get(CONFIG_VERSION_KEY)
        .filter(|v| !v.is_null())
        .and_then(coerce::as_i64)
        .unwrap_or(0)
}

/// Brings `doc` up to [`CONFIG_VERSION`]. A document already at (or above, from a newer Java
/// launcher) the current version is left untouched.
pub fn migrate(doc: &mut Map<String, Value>) -> MigrationReport {
    let from = stored_version(doc);
    let mut report = MigrationReport {
        from,
        ..MigrationReport::default()
    };
    if from >= CONFIG_VERSION {
        return report;
    }
    report.migrated = true;

    if from < 5 {
        report.v5_resizable_windows = migrate_v5_resizable_windows(doc);
    }
    if from < 6 {
        report.v6_urls = migrate_v6_pack_urls(doc);
    }
    if from < 7 {
        report.v7_jvm_args = migrate_default_jvm_args(doc);
    }
    touched_defaults(doc);
    doc.insert(CONFIG_VERSION_KEY.to_string(), Value::from(CONFIG_VERSION));
    report
}

/// v5: a `resizableWindows` persisted as false by a pre-2026-05-13 install becomes true.
pub fn migrate_v5_resizable_windows(doc: &mut Map<String, Value>) -> bool {
    let is_false = doc
        .get(RESIZE_WINDOWS_ENABLE_KEY)
        .filter(|v| !v.is_null())
        .and_then(coerce::as_bool)
        == Some(false);
    if is_false {
        doc.insert(RESIZE_WINDOWS_ENABLE_KEY.to_string(), Value::Bool(true));
    }
    is_false
}

/// The v6 rewrite of one URL, when it applies.
pub fn v6_rewrite(url: &str) -> Option<String> {
    if url.starts_with(V6_HOST_PREFIX) && url.ends_with(V6_OLD_SUFFIX) {
        Some(format!(
            "{}{V6_NEW_SUFFIX}",
            &url[..url.len() - V6_OLD_SUFFIX.len()]
        ))
    } else {
        None
    }
}

/// v6: Mica-hosted `.json` pack URLs in `modpacks[]` and `lastModPack` become `.mmcjson`.
/// Non-string elements and third-party URLs are kept as-is. Returns the number rewritten.
pub fn migrate_v6_pack_urls(doc: &mut Map<String, Value>) -> usize {
    let mut migrated = 0;
    if let Some(Value::Array(packs)) = doc.get_mut(MOD_PACKS_INSTALLED_KEY) {
        for el in packs.iter_mut() {
            if let Value::String(url) = el
                && let Some(new) = v6_rewrite(url)
            {
                *url = new;
                migrated += 1;
            }
        }
    }
    if let Some(Value::String(last)) = doc.get_mut(LAST_MP_KEY)
        && let Some(new) = v6_rewrite(last)
    {
        *last = new;
        migrated += 1;
    }
    migrated
}

/// v7: a `jvmArgs` string whose trimmed value is exactly the pre-2026.10 default becomes the
/// current default. Idempotent; absent or non-string values are left alone.
pub fn migrate_default_jvm_args(doc: &mut Map<String, Value>) -> bool {
    match doc.get(JVM_ARGS_KEY) {
        Some(Value::String(s)) if s.trim() == JVM_ARGS_VALUE_DEFAULT_PRE_2026_10 => {
            doc.insert(
                JVM_ARGS_KEY.to_string(),
                Value::from(JVM_ARGS_VALUE_DEFAULT),
            );
            true
        }
        _ => false,
    }
}

/// Logs what a migration did, with the Java launcher's messages.
pub(crate) fn log_report(report: &MigrationReport) {
    if !report.migrated {
        return;
    }
    mmcl_log::std(&mmcl_i18n::tf!(
        "log.configManager.migrating",
        report.from.to_string(),
        CONFIG_VERSION.to_string()
    ));
    if report.v5_resizable_windows {
        mmcl_log::std(&mmcl_i18n::t!("log.configManager.migrateV5ResizeWindows"));
    }
    if report.v6_urls > 0 {
        mmcl_log::std(&mmcl_i18n::tf!(
            "log.configManager.migrateV6Urls",
            report.v6_urls.to_string()
        ));
    }
    if report.v7_jvm_args {
        mmcl_log::std(&mmcl_i18n::t!("log.configManager.migrateV7JvmArgs"));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn obj(v: Value) -> Map<String, Value> {
        v.as_object().unwrap().clone()
    }

    #[test]
    fn current_or_newer_version_is_untouched() {
        let mut doc = obj(json!({"configVersion": 7, "resizableWindows": false}));
        let before = doc.clone();
        assert!(!migrate(&mut doc).migrated);
        assert_eq!(doc, before);
        let mut doc = obj(json!({"configVersion": 9}));
        assert!(!migrate(&mut doc).migrated);
        assert_eq!(doc, obj(json!({"configVersion": 9})));
    }

    #[test]
    fn non_numeric_version_counts_as_zero() {
        let mut doc = obj(json!({"configVersion": "garbage"}));
        let r = migrate(&mut doc);
        assert_eq!(r.from, 0);
        assert_eq!(doc["configVersion"], json!(7));
    }

    #[test]
    fn v5_flips_resizable_windows() {
        let mut doc = obj(json!({"configVersion": 4, "resizableWindows": false}));
        let r = migrate(&mut doc);
        assert!(r.v5_resizable_windows);
        assert_eq!(doc["resizableWindows"], json!(true));

        let mut doc = obj(json!({"configVersion": 5, "resizableWindows": false}));
        migrate(&mut doc);
        assert_eq!(
            doc["resizableWindows"],
            json!(false),
            "v5+ keeps the user's choice"
        );
    }

    #[test]
    fn v6_rewrites_only_mica_urls_and_keeps_non_strings() {
        let mica = format!("{V6_HOST_PREFIX}packs/a.json");
        let mut doc = obj(json!({
            "configVersion": 5,
            "modpacks": [mica, "https://example.com/b.json", 42, null, format!("{V6_HOST_PREFIX}c.mmcjson")],
            "lastModPack": format!("{V6_HOST_PREFIX}packs/a.json"),
        }));
        let r = migrate(&mut doc);
        assert_eq!(r.v6_urls, 2);
        assert_eq!(
            doc["modpacks"],
            json!([
                format!("{V6_HOST_PREFIX}packs/a.mmcjson"),
                "https://example.com/b.json",
                42,
                null,
                format!("{V6_HOST_PREFIX}c.mmcjson")
            ])
        );
        assert_eq!(
            doc["lastModPack"],
            json!(format!("{V6_HOST_PREFIX}packs/a.mmcjson"))
        );
    }

    #[test]
    fn v6_needs_both_prefix_and_suffix() {
        assert_eq!(
            v6_rewrite("https://example.com/mc-launcher-api/a.json"),
            None
        );
        assert_eq!(v6_rewrite(&format!("{V6_HOST_PREFIX}a.json5")), None);
        assert_eq!(v6_rewrite("Pack Name"), None);
    }

    #[test]
    fn touched_keys_are_written_in_java_order_and_version_keeps_its_place() {
        let mut doc = obj(json!({"configVersion": 6, "theme": "Dark"}));
        migrate(&mut doc);
        let keys: Vec<&str> = doc.keys().map(String::as_str).collect();
        assert_eq!(
            keys,
            [
                "configVersion",
                "theme",
                "minRAM",
                "maxRAM",
                "debug",
                "discordRpc",
                "resizableWindows",
                "enhancedLogging",
                "jvmArgs",
                "lastModPack",
                "modpacks",
                "inGameConsole",
                "proxyEnable",
                "proxyHost",
                "proxyPort",
                "proxyType",
                "vanillaVersions",
                "lwjglArmPatch",
                "discordInvites",
            ]
        );
        assert_eq!(doc["theme"], json!("Dark"));
        assert_eq!(doc["configVersion"], json!(7));
        assert_eq!(doc["jvmArgs"], json!(JVM_ARGS_VALUE_DEFAULT));
        assert_eq!(doc["proxyPort"], json!(8080));
    }

    #[test]
    fn v7_runs_inside_migrate() {
        let mut doc =
            obj(json!({"configVersion": 6, "jvmArgs": JVM_ARGS_VALUE_DEFAULT_PRE_2026_10}));
        assert!(migrate(&mut doc).v7_jvm_args);
        assert_eq!(doc["jvmArgs"], json!(JVM_ARGS_VALUE_DEFAULT));
    }
}
