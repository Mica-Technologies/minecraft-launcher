// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Networking for the Mica Minecraft Launcher (Rust port of `NetworkUtilities`,
//! `DownloadTracker`, `DownloadExecutor`, `CacheManager`, `UpdateCheckUtilities` and
//! `AnnouncementManager`). Async on tokio, HTTP through reqwest over rustls.
//!
//! - [`download`]: [`download_file`] (per-path lock, three attempts with 1 s / 2 s back-off,
//!   unique temp file, 64 KiB writes, stall watchdog, cancel, battery throttle, optional digest
//!   check, atomic publish) and [`download_string`].
//! - [`bounded`]: HTTPS-only, redirect-walking, size-capped fetches for JSON (with the
//!   content-type gate and conditional GET) and binary archives.
//! - [`offline`]: the offline latch, cleared by any successful request, and the multi-host probe.
//! - [`proxy`], [`client`]: HTTP/SOCKS proxy settings (used only for `http(s)` sources) and the
//!   two shared clients with the generic `MicaMinecraftLauncher/<version>` User-Agent.
//! - [`listeners`]: per-launch [`DownloadScope`]s and the scoped retry / progress listeners.
//! - [`tracker`], [`pool`]: [`DownloadTracker`] and the shared [`DownloadPool`] semaphore.
//! - [`cache`], [`update`], [`announcements`]: the content cache, update check and remote
//!   announcements.
//! - [`throttle`]: the hook the platform layer uses for the battery-saver throttle.
//!
//! The User-Agent never carries anything personal, and no request adds identifying headers.

pub mod announcements;
pub mod bounded;
pub mod cache;
pub mod cancel;
pub mod client;
pub mod download;
pub mod error;
pub mod listeners;
pub mod offline;
pub mod pool;
pub mod proxy;
pub mod source;
pub mod throttle;
pub mod tracker;
pub mod update;
pub mod verify;

#[cfg(test)]
mod tests;

pub use announcements::Announcements;
pub use bounded::{
    BoundedFetchResult, MANIFEST_MAX_BYTES, MAX_REDIRECTS, SMALL_JSON_MAX_BYTES,
    assert_acceptable_json_content_type, download_file_bounded, download_string_bounded,
    download_string_bounded_accept, download_string_bounded_conditional,
};
pub use cache::CacheManager;
pub use cancel::CancelToken;
pub use client::{reload_proxy, user_agent};
pub use download::{
    DownloadOpts, download_file, download_file_url, download_string, download_string_url,
    path_lock, read_jar_entry, unique_temp_file,
};
pub use error::{AwaitError, DownloadError};
pub use listeners::{
    DownloadScope, ListenerGuard, add_progress_listener, add_retry_listener, current_scope,
    format_download_progress, in_current_scope, set_scope, with_scope,
};
pub use mmcl_util::hash::HashAlgorithm;
pub use offline::{
    check_network_availability, is_offline, note_network_success, offline_generation, set_offline,
};
pub use pool::{DownloadHandle, DownloadPool, await_all};
pub use proxy::{ProxySettings, set_settings_provider as set_proxy_settings_provider};
pub use source::{Source, url_file_name};
pub use throttle::{ThrottlePolicy, clear_throttle_policy, set_throttle_policy};
pub use tracker::DownloadTracker;
pub use update::ReleaseInfo;
pub use verify::ExpectedHash;
