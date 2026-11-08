// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The byte / file-count meter a progress sink carries for downloads.

use std::any::Any;

/// Download speed / ETA / file-count accounting shared by every file of one sync.
///
/// This is the surface of Java's `DownloadTracker` that progress code touches. The concrete
/// tracker lives in the network crate (`mmcl_net::tracker::DownloadTracker`), which implements
/// this trait; code that needs the concrete type (to hand it to a download call) recovers it
/// with [`DownloadMeter::as_any`] and `downcast_ref`.
pub trait DownloadMeter: Send + Sync {
    /// Clear every counter.
    fn reset(&self);
    /// Total number of files this sync will process (downloaded or not).
    fn set_total_file_count(&self, count: u64);
    /// A download is starting; `expected_bytes <= 0` means unknown length.
    fn register_download(&self, expected_bytes: i64);
    /// Bytes received (may be negative when a retry rewinds).
    fn add_bytes(&self, bytes: i64);
    /// A download finished (successfully or not).
    fn complete_download(&self);
    /// A file was processed, whether or not it was downloaded.
    fn complete_file(&self);
    /// Formatted `speed — eta — file count` status, possibly empty.
    fn formatted_status(&self) -> String;
    /// The concrete meter, for downcasting.
    fn as_any(&self) -> &dyn Any;
}
