// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! One process-wide lock per file path (`SynchronizedFileManager`).
//!
//! Java handed out one canonical `File` object per path so callers could `synchronized` on it.
//! Rust hands out the lock itself: `let _guard = file_lock(path).lock();`. Spellings of the same
//! path share one lock: the key is the absolute, lexically normalised path (no symlink
//! resolution, so missing files work), lowercased on Windows. macOS keeps case, like Java.
//! The map grows by one entry per distinct file, as Java's did.

use std::path::Path;
use std::sync::{Arc, LazyLock};

use dashmap::DashMap;
use parking_lot::Mutex;

use crate::paths::normalize_lexical;

static LOCKS: LazyLock<DashMap<String, Arc<Mutex<()>>>> = LazyLock::new(DashMap::new);

/// The registry key for a path: absolute, normalised, lowercased on Windows.
pub fn key_for(path: &Path) -> String {
    let absolute = std::path::absolute(path).unwrap_or_else(|_| path.to_path_buf());
    let key = normalize_lexical(&absolute).to_string_lossy().into_owned();
    if cfg!(windows) {
        key.to_lowercase()
    } else {
        key
    }
}

/// The lock shared by every spelling of `path`.
pub fn file_lock(path: &Path) -> Arc<Mutex<()>> {
    LOCKS
        .entry(key_for(path))
        .or_insert_with(|| Arc::new(Mutex::new(())))
        .clone()
}
