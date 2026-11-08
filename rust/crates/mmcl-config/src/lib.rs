// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Launcher configuration (Java `config/`): `<root>/config/configuration.json`.
//!
//! - [`ConfigStore`]: the document, Gson-compatible typed reads ([`coerce`]), debounced atomic
//!   owner-only writes, corrupt-file quarantine, first-run defaults.
//! - [`Config`]: the typed facade, one getter/setter pair per key; defaults in [`keys`].
//! - [`migrate`]: schema migrations (v5 resizable windows, v6 `.mmcjson` URLs, v7 JVM args).
//! - [`ScanFrequency`], [`SecretCipher`] (the injected cipher for the CurseForge key).
//! - [`global`] / [`install_global`]: the process-wide config, set once by `mmcl-app` after the
//!   game mode (and so the data folder) is known.
//!
//! The file format is shared with the Java launcher: both must read each other's files. Rust
//! keys Java does not know (`auth.clientId`, `auth.wam`, `ui.gamepadMode`) are ignored by Java.
//!
//! Every exit path must call [`Config::flush`]: there is no JVM shutdown hook. Dropping the
//! last handle to a store also writes a pending change.

pub mod coerce;
pub mod config;
pub mod error;
pub mod keys;
pub mod migrate;
pub mod scan_frequency;
pub mod secrets;
pub mod store;

use std::sync::OnceLock;

pub use config::{Config, WindowBounds};
pub use error::ConfigError;
pub use keys::{CONFIG_VERSION, JVM_PRESETS, JvmPreset};
pub use migrate::MigrationReport;
pub use mmcl_util::jvm_args::JvmArgsError;
pub use scan_frequency::ScanFrequency;
pub use secrets::SecretCipher;
pub use store::{ConfigStore, Document, LoadOutcome, StoreHooks, StoreOptions};

static GLOBAL: OnceLock<Config> = OnceLock::new();

/// Installs the process-wide config. Returns `false` (and drops `config`) when one is already
/// installed.
pub fn install_global(config: Config) -> bool {
    GLOBAL.set(config).is_ok()
}

/// The process-wide config, if installed.
pub fn try_global() -> Option<&'static Config> {
    GLOBAL.get()
}

/// The process-wide config.
///
/// # Panics
/// When [`install_global`] has not run: reading config before the game mode is known is the
/// early-path trap (paths would resolve against the working directory).
pub fn global() -> &'static Config {
    GLOBAL
        .get()
        .expect("mmcl_config::global() used before install_global()")
}

/// Best-effort, non-blocking flush of the global config for the panic hook and signal handlers.
pub fn try_flush_global() -> bool {
    GLOBAL.get().is_none_or(|c| c.store().try_flush())
}

#[cfg(test)]
mod tests {
    #[test]
    fn every_key_this_crate_logs_exists_in_english() {
        for key in [
            "CONFIG_EXISTS_CORRUPT_RESET_ERROR_TEXT",
            "CONFIG_RESET_SUCCESS_TEXT",
            "CONFIG_SAVE_ERROR_TEXT",
            "log.configStore.typeMismatch",
            "log.configStore.loadFromDisk",
            "log.configStore.writeNow",
            "log.configStore.preservedCorrupt",
            "log.configStore.preserveCorruptFailed",
            "log.configStore.atomicMoveUnsupported",
            "log.configManager.migrating",
            "log.configManager.migrateV5ResizeWindows",
            "log.configManager.migrateV6Urls",
            "log.configManager.migrateV7JvmArgs",
            "log.configManager.jvmArgsValidationFailed",
            "log.configManager.exportFailed",
            "log.configManager.importFailed",
            "log.modPackConfig.alwaysVerifyReadFailed",
            "log.modPackConfig.scanFrequencyReadFailed",
            "log.authTokenStore.cfKeyDecryptFailed",
            "log.authTokenStore.cfKeyEncryptFailed",
            "log.filePermissions.posixTightenFailed",
            "log.filePermissions.aclTightenFailed",
            "config.error.exportFailed",
            "config.error.importFailed",
            "config.error.parse.empty",
            "config.error.parse.notUtf8",
            "config.error.parse.syntax",
            "config.error.parse.notAnObject",
        ] {
            assert!(mmcl_i18n::english(key).is_some(), "missing i18n key {key}");
        }
    }
}
