// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Launcher update check (`UpdateCheckUtilities` and the logic half of `UpdateCheckManager`).
//!
//! The Java identity reads `releases/latest`. The Rust build publishes GitHub **pre-releases**
//! tagged `rs-*` (so the Java launcher's check never sees them), which `releases/latest` skips:
//! for an identity with `releases_are_prereleases` this lists `releases` and takes the newest
//! non-draft release whose tag has the identity's prefix, and the version is the tag without the
//! prefix. The chosen release object is kept in `<config>/update.mica` (layout parity) and the
//! result is memoized for the process once both the version and the page URL are known.
//!
//! The UI half (glyph, taskbar cue, toast, prompt) lives in the app; it uses [`should_check`],
//! [`check_for_update`], [`claim_session_toast`] and [`is_openable_update_url`].

use std::sync::atomic::{AtomicBool, Ordering};

use mmcl_core::{AppIdentity, DataPaths};
use mmcl_i18n::{t, tf};
use parking_lot::Mutex;
use serde_json::Value;

use crate::bounded::{SMALL_JSON_MAX_BYTES, download_string_bounded_accept};
use crate::error::DownloadError;

/// The repository's API root.
pub const GITHUB_REPO_API: &str =
    "https://api.github.com/repos/Mica-Technologies/minecraft-launcher";
/// `Accept` for the GitHub API.
pub const GITHUB_ACCEPT: &str = "application/vnd.github.v3+json";
/// Key of the version in a release object.
pub const LATEST_VERSION_KEY: &str = "tag_name";
/// Key of the release page in a release object.
pub const LATEST_URL_KEY: &str = "html_url";
/// Cap of the release list (bodies of 30 releases).
pub const RELEASE_LIST_MAX_BYTES: u64 = 2 * 1024 * 1024;

/// The newest release of this product.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ReleaseInfo {
    /// The tag as published.
    pub tag: String,
    /// The comparable version (the tag without the identity's prefix).
    pub version: String,
    /// The release page.
    pub url: String,
}

/// The endpoint for an identity.
pub fn api_url(identity: &AppIdentity) -> String {
    if identity.releases_are_prereleases {
        format!("{GITHUB_REPO_API}/releases?per_page=30")
    } else {
        format!("{GITHUB_REPO_API}/releases/latest")
    }
}

fn release_from(obj: &Value, prefix: &str) -> Option<ReleaseInfo> {
    let tag = obj.get(LATEST_VERSION_KEY)?.as_str()?.trim().to_string();
    let url = obj.get(LATEST_URL_KEY)?.as_str()?.trim().to_string();
    if tag.is_empty() || url.is_empty() {
        return None;
    }
    let version = tag.strip_prefix(prefix).unwrap_or(&tag).to_string();
    Some(ReleaseInfo { tag, version, url })
}

/// Picks the release from an API response: the object itself for `releases/latest`, or the
/// first non-draft entry of the list whose tag has the identity's prefix. Returns the chosen
/// release object too (for `update.mica`).
pub fn select_release(json: &Value, identity: &AppIdentity) -> Option<(ReleaseInfo, Value)> {
    let prefix = identity.release_tag_prefix;
    match json {
        Value::Array(list) => list.iter().find_map(|r| {
            let draft = r.get("draft").and_then(Value::as_bool).unwrap_or(false);
            let tag = r
                .get(LATEST_VERSION_KEY)
                .and_then(Value::as_str)
                .unwrap_or("");
            if draft || (!prefix.is_empty() && !tag.starts_with(prefix)) {
                return None;
            }
            release_from(r, prefix).map(|i| (i, r.clone()))
        }),
        Value::Object(_) => release_from(json, prefix).map(|i| (i, json.clone())),
        _ => None,
    }
}

static MEMO: Mutex<Option<ReleaseInfo>> = Mutex::new(None);

/// The newest release, fetched once per process. `Ok(None)` when the API answered without a
/// usable release (logged as `log.updateCheck.emptyApiResponse`).
pub async fn latest_release(paths: &DataPaths) -> Result<Option<ReleaseInfo>, DownloadError> {
    if let Some(r) = MEMO.lock().clone() {
        return Ok(Some(r));
    }
    let identity = AppIdentity::current();
    let url = api_url(identity);
    let cap = if identity.releases_are_prereleases {
        RELEASE_LIST_MAX_BYTES
    } else {
        SMALL_JSON_MAX_BYTES
    };
    let body = download_string_bounded_accept(&url, cap, GITHUB_ACCEPT).await?;
    let json: Value = serde_json::from_str(&body).map_err(|e| DownloadError::Json {
        url: url.clone(),
        message: e.to_string(),
    })?;
    let Some((info, obj)) = select_release(&json, identity) else {
        mmcl_log::std(&t!("log.updateCheck.emptyApiResponse"));
        return Ok(None);
    };
    let file = paths.update_info_file();
    let text = serde_json::to_string_pretty(&obj).unwrap_or_default();
    if let Err(e) = mmcl_core::fsx::atomic_write(&file, text.as_bytes(), false) {
        mmcl_log::warn_silent_err(
            &tf!("log.updateCheck.saveFailed", file.display().to_string()),
            &e,
        );
    }
    *MEMO.lock() = Some(info.clone());
    Ok(Some(info))
}

/// `false` for unstamped builds (no version, `0.0.0*`, `no-git-tag`, `-dev`), which skip the
/// check (logged as `log.updateCheck.skipUnstampedBuild`).
pub fn should_check(installed_version: &str) -> bool {
    if mmcl_core::build::is_unstamped(installed_version) {
        mmcl_log::debug(&tf!(
            "log.updateCheck.skipUnstampedBuild",
            installed_version
        ));
        return false;
    }
    true
}

/// `true` when `latest` is newer than `installed`.
pub fn is_newer(installed: &str, latest: &str) -> bool {
    mmcl_util::compare_versions(installed, latest) == std::cmp::Ordering::Less
}

/// The newer release, when there is one. Unstamped builds never check.
pub async fn check_for_update(
    paths: &DataPaths,
    installed_version: &str,
) -> Result<Option<ReleaseInfo>, DownloadError> {
    if !should_check(installed_version) {
        return Ok(None);
    }
    let latest = latest_release(paths).await.inspect_err(|e| {
        mmcl_log::error_silent(&t!("log.updateCheck.checkError"));
        mmcl_log::debug(&e.to_string());
    })?;
    Ok(latest.filter(|r| is_newer(installed_version, &r.version)))
}

static TOAST_SHOWN: AtomicBool = AtomicBool::new(false);

/// `true` exactly once per session: the "update available" toast is shown once.
pub fn claim_session_toast() -> bool {
    !TOAST_SHOWN.swap(true, Ordering::SeqCst)
}

/// Only `http(s)` release pages are opened (logs `log.updateCheck.refuseNonHttpUrl` otherwise).
pub fn is_openable_update_url(url: &str) -> bool {
    let lower = url.trim().to_ascii_lowercase();
    if lower.starts_with("https://") || lower.starts_with("http://") {
        return true;
    }
    mmcl_log::warn_silent(&tf!("log.updateCheck.refuseNonHttpUrl", url));
    false
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_core::identity::{JAVA_RELEASE, RUST_RELEASE};
    use serde_json::json;

    #[test]
    fn latest_endpoint_object_is_used_as_is_for_the_java_identity() {
        let j = json!({"tag_name": "2026.3", "html_url": "https://github.com/x/releases/2026.3"});
        let (r, _) = select_release(&j, &JAVA_RELEASE).unwrap();
        assert_eq!(r.version, "2026.3");
        assert!(api_url(&JAVA_RELEASE).ends_with("/releases/latest"));
        assert!(select_release(&json!({"tag_name": "x"}), &JAVA_RELEASE).is_none());
        assert!(select_release(&json!({}), &JAVA_RELEASE).is_none());
    }

    #[test]
    fn rust_identity_takes_the_newest_prefixed_non_draft_release() {
        let j = json!([
            {"tag_name": "rs-2026.12.0", "html_url": "https://g/d", "draft": true},
            {"tag_name": "2026.11.5", "html_url": "https://g/java"},
            {"tag_name": "rs-2026.11.2", "html_url": "https://g/rs", "prerelease": true},
            {"tag_name": "rs-2026.10.0", "html_url": "https://g/old"}
        ]);
        let (r, obj) = select_release(&j, &RUST_RELEASE).unwrap();
        assert_eq!(
            r,
            ReleaseInfo {
                tag: "rs-2026.11.2".into(),
                version: "2026.11.2".into(),
                url: "https://g/rs".into()
            }
        );
        assert_eq!(obj["html_url"], "https://g/rs");
        assert!(api_url(&RUST_RELEASE).contains("/releases?"));
    }

    #[test]
    fn version_gates() {
        assert!(is_newer("2026.10.1", "2026.11.0"));
        assert!(!is_newer("2026.11.0", "2026.11.0"));
        assert!(!is_newer("2026.12", "2026.11.9"));
        assert!(!should_check("0.0.0-dev"));
        assert!(!should_check(""));
        assert!(should_check("2026.11.0"));
        assert!(is_openable_update_url("https://github.com/x"));
        assert!(!is_openable_update_url("file:///etc/passwd"));
    }

    #[test]
    fn the_session_toast_is_claimed_once() {
        let first = claim_session_toast();
        assert!(!claim_session_toast());
        let _ = first;
    }
}
