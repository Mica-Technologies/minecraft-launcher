// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Process-wide services the UI crates leave to the app: the avatar downloader over
//! `mmcl-net` and the shared redactor. (The OS notification backend is the shell's,
//! `mmcl_ui::install_desktop_integration`.)

use std::path::{Path, PathBuf};
use std::sync::Arc;

use gpui::App;
use mmcl_engine::Engine;

/// The largest avatar the fetcher accepts (the 100 px busts are a few kB).
pub const AVATAR_MAX_BYTES: u64 = 2 * 1024 * 1024;

/// Installs the avatar fetcher and the redactor.
pub fn install(cx: &mut App, engine: &Engine) {
    mmcl_ui_kit::sys::set_redactor(redact_owned);
    let e = engine.clone();
    let cache = engine.paths().cache_dir();
    mmcl_ui_kit::Avatars::set_fetcher(
        cx,
        Arc::new(move |url: String| {
            let cache = cache.clone();
            let task = e.spawn(async move { fetch_avatar(&url, &cache).await });
            Box::pin(async move { task.await.unwrap_or_else(|_| Err(String::new())) })
        }),
    );
}

/// `mmcl_log::redact` in the shape the dialogs' redactor takes.
pub fn redact_owned(input: &str) -> String {
    mmcl_log::redact(input).into_owned()
}

/// A temp file for one avatar download inside `cache`.
pub fn avatar_temp_file(cache: &Path) -> PathBuf {
    mmcl_net::unique_temp_file(&cache.join("avatar.png"))
}

/// Downloads `url` (HTTPS only, capped, the launcher's generic User-Agent and proxy) through a
/// temp file in the cache folder and returns the bytes.
async fn fetch_avatar(url: &str, cache: &Path) -> Result<Vec<u8>, String> {
    tokio::fs::create_dir_all(cache)
        .await
        .map_err(|e| e.to_string())?;
    let tmp = avatar_temp_file(cache);
    let result = match mmcl_net::download_file_bounded(url, &tmp, AVATAR_MAX_BYTES).await {
        Ok(()) => tokio::fs::read(&tmp).await.map_err(|e| e.to_string()),
        Err(e) => Err(e.to_string()),
    };
    let _ = tokio::fs::remove_file(&tmp).await;
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn avatar_temp_files_stay_in_the_cache_and_never_collide() {
        let dir = tempfile::tempdir().unwrap();
        let a = avatar_temp_file(dir.path());
        let b = avatar_temp_file(dir.path());
        assert_ne!(a, b);
        assert!(a.starts_with(std::path::absolute(dir.path()).unwrap()));
    }

    #[test]
    fn the_redactor_masks_tokens() {
        let line = "--accessToken abc.def.ghi";
        assert!(
            !redact_owned(line).contains("abc.def.ghi"),
            "{}",
            redact_owned(line)
        );
    }
}
