// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Pure and general helpers for the Mica Minecraft Launcher (Rust port of the launcher's
//! `utilities` package, the pure parts).
//!
//! - [`version`]: dotted version comparison that never panics.
//! - [`jvm_args`]: custom JVM argument validation and hardware-tuned G1 defaults.
//! - [`uri`]: a strict, non-normalising parser with `java.net.URI.create` semantics.
//! - [`url_validation`]: the http/https gate for author-supplied links.
//! - [`redact`]: token and UUID scrubbing for logs and command lines.
//! - [`json`]: Gson-compatible parsing and printing, null-safe getters, `StringOrArray`.
//! - [`units`]: localized byte sizes and rates.
//! - [`hash`]: SHA-1 / SHA-256 / MD5 and constant-time verification.
//! - [`process`]: child process launch with environment filtering, command-line splitting.
//! - [`quote`]: shell, `.desktop` and Windows quoting, file-name sanitising.
//! - [`deeplink`]: `mmcl://` parsing and the install-URL safety classifier.
//! - [`throttle`]: the battery download throttle's deadline arithmetic.
//! - [`progress`]: combining several launches' progress into one value.
//! - [`tasks`]: the re-creatable background pool, bounded shutdown steps, UI hand-off.
//! - [`startup_log`]: the always-on `startup.log` and the panic hook.
//! - [`profiler`]: opt-in cold-start phase timing.
//! - [`client_token`]: the persistent client token.
//! - [`time`], [`recent`]: the legacy greeting and the recent-packs selection rule.

pub mod client_token;
pub mod deeplink;
pub mod hash;
pub mod json;
pub mod jvm_args;
pub mod process;
pub mod profiler;
pub mod progress;
pub mod quote;
pub mod recent;
pub mod redact;
pub mod startup_log;
pub mod tasks;
pub mod throttle;
pub mod time;
pub mod units;
pub mod uri;
pub mod url_validation;
pub mod version;

pub use deeplink::{DeepLink, DeepLinkError, InstallUrlVerdict};
pub use json::StringOrArray;
pub use mmcl_core::GameMode;
pub use process::ChildIoMode;
pub use progress::Progress;
pub use version::compare_versions;
