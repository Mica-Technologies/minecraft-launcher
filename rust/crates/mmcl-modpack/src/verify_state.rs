// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `<packRoot>/.verify_state.json` (Java `VerifyState`) and the verify-mode and scan-frequency
//! decisions built on it.

use std::fs;
use std::path::Path;

use mmcl_i18n::tf;
use serde::{Deserialize, Serialize};

use crate::context::VerifyMode;
pub use mmcl_config::ScanFrequency;

/// The sidecar file name.
pub const VERIFY_STATE_FILE: &str = ".verify_state.json";
/// A full verify stays good for 24 hours.
pub const DEFAULT_TTL_MS: i64 = 24 * 60 * 60 * 1000;

/// The last successful full verify and the last scan of a pack.
///
/// Serialised like Gson: compact, null strings omitted, zero numbers written.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct VerifyState {
    #[serde(
        rename = "manifestSha256",
        skip_serializing_if = "Option::is_none",
        deserialize_with = "mmcl_manifest::lenient::string"
    )]
    pub manifest_sha256: Option<String>,
    #[serde(rename = "verifiedAt", deserialize_with = "lenient_i64")]
    pub verified_at: i64,
    #[serde(
        rename = "launcherVersion",
        skip_serializing_if = "Option::is_none",
        deserialize_with = "mmcl_manifest::lenient::string"
    )]
    pub launcher_version: Option<String>,
    #[serde(rename = "lastScannedAt", deserialize_with = "lenient_i64")]
    pub last_scanned_at: i64,
    #[serde(
        rename = "lastScannedManifestSha256",
        skip_serializing_if = "Option::is_none",
        deserialize_with = "mmcl_manifest::lenient::string"
    )]
    pub last_scanned_manifest_sha256: Option<String>,
}

fn lenient_i64<'de, D: serde::Deserializer<'de>>(d: D) -> Result<i64, D::Error> {
    let v = serde_json::Value::deserialize(d)?;
    Ok(mmcl_util::json::as_gson_i64(&v).unwrap_or(0))
}

fn eq_ic(a: &str, b: &str) -> bool {
    a.eq_ignore_ascii_case(b)
}

fn now_ms() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

impl VerifyState {
    /// The pack's state, or `None` when missing, unreadable or unparseable (forcing a full
    /// verify). `pack_name` labels the warning.
    pub fn load(pack_root: &Path, pack_name: &str) -> Option<VerifyState> {
        let p = pack_root.join(VERIFY_STATE_FILE);
        if !p.is_file() {
            return None;
        }
        let parsed = fs::read_to_string(&p)
            .map_err(|e| e.to_string())
            .and_then(|t| serde_json::from_str::<VerifyState>(&t).map_err(|e| e.to_string()));
        match parsed {
            Ok(s) => Some(s),
            Err(e) => {
                mmcl_log::warn_silent(&tf!("log.verifyState.readFailed", pack_name, e));
                None
            }
        }
    }

    /// Writes the state through a temp file and a rename. Failures are logged.
    pub fn save(&self, pack_root: &Path, pack_name: &str) {
        let p = pack_root.join(VERIFY_STATE_FILE);
        let json = serde_json::to_value(self)
            .map(|v| mmcl_manifest::gson::to_compact_string(&v))
            .unwrap_or_default();
        if let Err(e) = mmcl_core::fsx::atomic_write(&p, json.as_bytes(), false) {
            mmcl_log::warn_silent(&tf!(
                "log.verifyState.writeFailed",
                pack_name,
                e.to_string()
            ));
        }
    }

    /// After a successful full verify: new manifest hash and time, scan fields kept.
    pub fn successful_verify(existing: Option<VerifyState>, sha: Option<&str>) -> VerifyState {
        let mut s = existing.unwrap_or_default();
        s.manifest_sha256 = sha.map(str::to_string);
        s.verified_at = now_ms();
        s.launcher_version = Some(mmcl_core::build::VERSION.to_string());
        s
    }

    /// After a successful scan: new scan time and hash, verify fields kept.
    pub fn successful_scan(existing: Option<VerifyState>, sha: Option<&str>) -> VerifyState {
        let mut s = existing.unwrap_or_default();
        s.last_scanned_at = now_ms();
        s.last_scanned_manifest_sha256 = sha.map(str::to_string);
        s.launcher_version = Some(mmcl_core::build::VERSION.to_string());
        s
    }
}

/// Lowercase hex SHA-256 of the manifest body's UTF-8 bytes.
pub fn compute_manifest_sha256(body: &str) -> String {
    mmcl_util::hash::sha256_hex(body.as_bytes())
}

/// Full or fast-path verify for this launch.
pub fn decide_mode(
    state: Option<&VerifyState>,
    current_sha: Option<&str>,
    ttl_ms: i64,
    always_verify: bool,
    global_force: bool,
    now_ms: i64,
) -> VerifyMode {
    if global_force || always_verify {
        return VerifyMode::Full;
    }
    let (Some(cur), Some(s)) = (current_sha, state) else {
        return VerifyMode::Full;
    };
    match &s.manifest_sha256 {
        Some(h) if eq_ic(h, cur) => {}
        _ => return VerifyMode::Full,
    }
    let age = now_ms - s.verified_at;
    if age < 0 || age > ttl_ms {
        VerifyMode::Full
    } else {
        VerifyMode::FastPath
    }
}

/// Whether the pre-launch scan is due.
pub fn should_scan(
    frequency: ScanFrequency,
    state: Option<&VerifyState>,
    current_sha: Option<&str>,
    now_ms: i64,
) -> bool {
    match frequency {
        ScanFrequency::Disabled => false,
        ScanFrequency::EveryTime => true,
        ScanFrequency::OnChangesOnly => {
            let Some(last) = state.and_then(|s| s.last_scanned_manifest_sha256.as_deref()) else {
                return true;
            };
            match current_sha {
                None => true,
                Some(cur) => !eq_ic(cur, last),
            }
        }
        ScanFrequency::Daily => {
            let Some(s) = state else {
                return true;
            };
            if let Some(cur) = current_sha {
                match s.last_scanned_manifest_sha256.as_deref() {
                    Some(last) if eq_ic(cur, last) => {}
                    _ => return true,
                }
            }
            let age = now_ms - s.last_scanned_at;
            !(0..=DEFAULT_TTL_MS).contains(&age)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn st(sha: &str, at: i64) -> VerifyState {
        VerifyState {
            manifest_sha256: Some(sha.into()),
            verified_at: at,
            last_scanned_at: at,
            last_scanned_manifest_sha256: Some(sha.into()),
            ..Default::default()
        }
    }

    #[test]
    fn decide_mode_rules() {
        let now = 10 * DEFAULT_TTL_MS;
        let s = st("AB", now - 1000);
        assert_eq!(
            decide_mode(Some(&s), Some("ab"), DEFAULT_TTL_MS, false, false, now),
            VerifyMode::FastPath
        );
        assert_eq!(
            decide_mode(Some(&s), Some("ab"), DEFAULT_TTL_MS, false, true, now),
            VerifyMode::Full
        );
        assert_eq!(
            decide_mode(Some(&s), Some("ab"), DEFAULT_TTL_MS, true, false, now),
            VerifyMode::Full
        );
        assert_eq!(
            decide_mode(Some(&s), None, DEFAULT_TTL_MS, false, false, now),
            VerifyMode::Full
        );
        assert_eq!(
            decide_mode(None, Some("ab"), DEFAULT_TTL_MS, false, false, now),
            VerifyMode::Full
        );
        assert_eq!(
            decide_mode(Some(&s), Some("cd"), DEFAULT_TTL_MS, false, false, now),
            VerifyMode::Full
        );
        let old = st("ab", now - DEFAULT_TTL_MS - 1);
        assert_eq!(
            decide_mode(Some(&old), Some("ab"), DEFAULT_TTL_MS, false, false, now),
            VerifyMode::Full
        );
        let future = st("ab", now + 5);
        assert_eq!(
            decide_mode(Some(&future), Some("ab"), DEFAULT_TTL_MS, false, false, now),
            VerifyMode::Full
        );
        let no_hash = VerifyState {
            verified_at: now,
            ..Default::default()
        };
        assert_eq!(
            decide_mode(
                Some(&no_hash),
                Some("ab"),
                DEFAULT_TTL_MS,
                false,
                false,
                now
            ),
            VerifyMode::Full
        );
    }

    #[test]
    fn should_scan_rules() {
        let now = 10 * DEFAULT_TTL_MS;
        let s = st("ab", now - 1000);
        use ScanFrequency::*;
        assert!(!should_scan(Disabled, None, None, now));
        assert!(should_scan(EveryTime, Some(&s), Some("ab"), now));
        assert!(should_scan(OnChangesOnly, None, Some("ab"), now));
        assert!(should_scan(OnChangesOnly, Some(&s), None, now));
        assert!(!should_scan(OnChangesOnly, Some(&s), Some("AB"), now));
        assert!(should_scan(OnChangesOnly, Some(&s), Some("cd"), now));
        assert!(should_scan(Daily, None, None, now));
        assert!(!should_scan(Daily, Some(&s), Some("ab"), now));
        assert!(!should_scan(Daily, Some(&s), None, now));
        assert!(should_scan(Daily, Some(&s), Some("cd"), now));
        let old = st("ab", now - DEFAULT_TTL_MS - 1);
        assert!(should_scan(Daily, Some(&old), Some("ab"), now));
        let unscanned = VerifyState {
            last_scanned_at: now,
            ..Default::default()
        };
        assert!(should_scan(Daily, Some(&unscanned), Some("ab"), now));
    }

    #[test]
    fn save_load_round_trip_in_gson_shape() {
        let d = tempfile::tempdir().unwrap();
        let s = VerifyState::successful_verify(None, Some("ab12"));
        let s = VerifyState::successful_scan(Some(s), Some("ab12"));
        s.save(d.path(), "P");
        let text = fs::read_to_string(d.path().join(VERIFY_STATE_FILE)).unwrap();
        assert!(text.starts_with("{\"manifestSha256\":\"ab12\",\"verifiedAt\":"));
        assert_eq!(VerifyState::load(d.path(), "P"), Some(s));
        // Missing / corrupt: None.
        let e = tempfile::tempdir().unwrap();
        assert!(VerifyState::load(e.path(), "P").is_none());
        fs::write(e.path().join(VERIFY_STATE_FILE), "{bad").unwrap();
        assert!(VerifyState::load(e.path(), "P").is_none());
        // Null strings omitted, zeros written.
        let blank = VerifyState::default();
        blank.save(e.path(), "P");
        assert_eq!(
            fs::read_to_string(e.path().join(VERIFY_STATE_FILE)).unwrap(),
            r#"{"verifiedAt":0,"lastScannedAt":0}"#
        );
    }

    #[test]
    fn sha_is_lowercase_hex() {
        assert_eq!(
            compute_manifest_sha256(""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
    }
}
