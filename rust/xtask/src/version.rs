// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Every version string the packages need, derived from one launcher version.
//!
//! - `version`: the full string stamped into the binary (`MMCL_VERSION`): `2026.05.12`,
//!   `2026.05.12.1`, `2026.05.12-pre.1432.UTC+0000+abc1234`, `2026.05.12-3-gabc1234.dirty`,
//!   or the unstamped sentinel `0.0.0-dev`.
//! - `numeric`: cut at the first `-`, then `+` (macOS / Linux package versions).
//! - `win_version`: MSI ProductVersion (major <= 255): the year loses its century.
//! - `bundle_version`: Burn's a.b.c.d (three win_version parts + the minute-based revision).
//! - `mac_short_version`: `CFBundleShortVersionString` (three integer parts).
//!
//! The release algorithms are the Java `build-release.yml` shell, ported exactly; the fixes
//! the spec lists (5-part bundle versions, 4-part mac short versions) are applied here.

use chrono::{DateTime, Datelike, TimeZone, Timelike, Utc};

/// The version of a build that has neither `MMCL_VERSION` nor an `rs-*` tag to describe.
pub const UNSTAMPED: &str = "0.0.0-dev";

/// `MMCL_VERSION` (non-empty) wins, then `git describe` on the `rs-*` tags (prefix removed),
/// then [`UNSTAMPED`].
pub fn resolve(env_version: Option<&str>, describe: Option<&str>, tag_prefix: &str) -> String {
    if let Some(v) = env_version.map(str::trim).filter(|v| !v.is_empty()) {
        return v.to_string();
    }
    if let Some(d) = describe.map(str::trim).filter(|d| !d.is_empty()) {
        let stripped = strip_tag_prefix(d, tag_prefix);
        if !stripped.is_empty() {
            return stripped.to_string();
        }
    }
    UNSTAMPED.to_string()
}

/// `rs-2026.05.12` -> `2026.05.12`; a tag without the prefix is returned unchanged.
pub fn strip_tag_prefix<'a>(tag: &'a str, prefix: &str) -> &'a str {
    tag.strip_prefix(prefix).unwrap_or(tag)
}

/// `2026.05.12` (UTC, zero padded).
pub fn base_date(now_utc: DateTime<Utc>) -> String {
    format!(
        "{:04}.{:02}.{:02}",
        now_utc.year(),
        now_utc.month(),
        now_utc.day()
    )
}

/// The release version for `base` given the existing tags (prefix already removed): `base`
/// when neither `base` nor `base.N` is tagged, else `base.(max N + 1)` where a bare `base`
/// tag counts as N = 0 and only all-digit suffixes count.
pub fn release_version(base: &str, existing_tags: &[String]) -> String {
    let mut max_n: i64 = -1;
    let dotted = format!("{base}.");
    for tag in existing_tags {
        let tag = tag.trim();
        if tag == base {
            max_n = max_n.max(0);
        } else if let Some(suffix) = tag.strip_prefix(&dotted)
            && !suffix.is_empty()
            && suffix.bytes().all(|b| b.is_ascii_digit())
            && let Ok(n) = suffix.parse::<i64>()
        {
            max_n = max_n.max(n);
        }
    }
    if max_n >= 0 {
        format!("{base}.{}", max_n + 1)
    } else {
        base.to_string()
    }
}

/// `2026.05.12-pre.1432.UTC+0000+abc1234` (the doubled `+` is deliberate, as in Java).
pub fn pre_release_version(base: &str, now_utc: DateTime<Utc>, short_sha: &str) -> String {
    format!(
        "{base}-pre.{:02}{:02}.UTC+0000+{}",
        now_utc.hour(),
        now_utc.minute(),
        short_sha.trim()
    )
}

/// The version cut at the first `-`, then the first `+`; `0.0.0` when that leaves nothing.
pub fn numeric(version: &str) -> String {
    mmcl_core::build::numeric(version)
}

/// MSI ProductVersion: [`numeric`], with a four-digit year shortened to two digits
/// (`2026.05.12.1` -> `26.05.12.1`) so the major part fits in 255, padded to three parts.
pub fn win_version(version: &str) -> String {
    let numeric = numeric(version);
    let mut parts: Vec<String> = numeric.split('.').map(str::to_string).collect();
    if let Some(first) = parts.first_mut()
        && first.len() == 4
        && first.bytes().all(|b| b.is_ascii_digit())
    {
        *first = first[2..].to_string();
    }
    while parts.len() < 3 {
        parts.push("0".to_string());
    }
    parts.join(".")
}

/// Minutes since 2024-01-01T00:00Z, mod 65536 (Burn's strictly increasing 4th part).
pub fn bundle_revision(now_utc: DateTime<Utc>) -> u16 {
    let epoch = Utc.with_ymd_and_hms(2024, 1, 1, 0, 0, 0).unwrap();
    let minutes = (now_utc - epoch).num_minutes();
    minutes.rem_euclid(65536) as u16
}

/// Burn needs exactly `a.b.c.d`: the first three parts of `win_version` plus `revision`.
pub fn bundle_version(win_version: &str, revision: u16) -> String {
    let mut parts: Vec<&str> = win_version.split('.').take(3).collect();
    while parts.len() < 3 {
        parts.push("0");
    }
    format!("{}.{}", parts.join("."), revision)
}

/// MSI ProductVersion limits: 1 to 4 numeric parts, major <= 255, minor <= 255,
/// build <= 65535 (the 4th part is stored but ignored by Windows Installer).
pub fn validate_msi_version(v: &str) -> Result<(), String> {
    let parts: Vec<&str> = v.split('.').collect();
    if parts.is_empty() || parts.len() > 4 {
        return Err(format!("MSI version '{v}' must have 1 to 4 parts"));
    }
    let limits = [255u32, 255, 65535, 65535];
    for (i, part) in parts.iter().enumerate() {
        if part.is_empty() || !part.bytes().all(|b| b.is_ascii_digit()) {
            return Err(format!("MSI version '{v}': part '{part}' is not a number"));
        }
        let n: u32 = part
            .parse()
            .map_err(|_| format!("MSI version '{v}': part '{part}' is too large"))?;
        if n > limits[i] {
            return Err(format!(
                "MSI version '{v}': part {} is {n}, the limit is {}",
                i + 1,
                limits[i]
            ));
        }
    }
    Ok(())
}

/// Integer parts of a numeric version (`2026.05.12` -> `[2026, 5, 12]`); a non-numeric part
/// takes its leading digits (0 when there are none).
fn int_parts(numeric: &str) -> Vec<u64> {
    numeric
        .split('.')
        .map(|p| {
            let digits: String = p.chars().take_while(|c| c.is_ascii_digit()).collect();
            digits.parse().unwrap_or(0)
        })
        .collect()
}

/// `CFBundleShortVersionString`: the first three integer parts (`2026.05.12.1` -> `2026.5.12`).
pub fn mac_short_version(numeric: &str) -> String {
    let mut parts = int_parts(numeric);
    parts.truncate(3);
    while parts.len() < 3 {
        parts.push(0);
    }
    join(&parts)
}

/// `CFBundleVersion`: every integer part (`2026.05.12.1` -> `2026.5.12.1`).
pub fn mac_bundle_version(numeric: &str) -> String {
    let mut parts = int_parts(numeric);
    while parts.len() < 3 {
        parts.push(0);
    }
    join(&parts)
}

fn join(parts: &[u64]) -> String {
    parts
        .iter()
        .map(u64::to_string)
        .collect::<Vec<_>>()
        .join(".")
}

/// All the strings one dist run needs.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct VersionSet {
    pub version: String,
    pub numeric: String,
    pub win_version: String,
    pub bundle_version: String,
    pub mac_short_version: String,
    pub mac_bundle_version: String,
}

impl VersionSet {
    /// Derives everything from `version`; `win_override` replaces the derived MSI version.
    pub fn new(version: &str, win_override: Option<&str>, now_utc: DateTime<Utc>) -> Self {
        let numeric = numeric(version);
        let win_version = win_override
            .map(str::trim)
            .filter(|w| !w.is_empty())
            .map(str::to_string)
            .unwrap_or_else(|| win_version(version));
        let bundle_version = bundle_version(&win_version, bundle_revision(now_utc));
        VersionSet {
            version: version.to_string(),
            mac_short_version: mac_short_version(&numeric),
            mac_bundle_version: mac_bundle_version(&numeric),
            numeric,
            win_version,
            bundle_version,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn at(y: i32, mo: u32, d: u32, h: u32, mi: u32) -> DateTime<Utc> {
        Utc.with_ymd_and_hms(y, mo, d, h, mi, 0).unwrap()
    }

    fn tags(list: &[&str]) -> Vec<String> {
        list.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn resolve_order() {
        assert_eq!(resolve(Some("1.2.3"), Some("rs-9.9.9"), "rs-"), "1.2.3");
        assert_eq!(
            resolve(Some("  "), Some("rs-2026.05.12-3-gabc"), "rs-"),
            "2026.05.12-3-gabc"
        );
        assert_eq!(resolve(None, None, "rs-"), UNSTAMPED);
        assert_eq!(resolve(None, Some(""), "rs-"), UNSTAMPED);
    }

    #[test]
    fn base_date_is_zero_padded() {
        assert_eq!(base_date(at(2026, 5, 2, 0, 0)), "2026.05.02");
    }

    #[test]
    fn release_version_bumps() {
        assert_eq!(release_version("2026.05.12", &[]), "2026.05.12");
        assert_eq!(
            release_version("2026.05.12", &tags(&["2026.05.12"])),
            "2026.05.12.1"
        );
        assert_eq!(
            release_version(
                "2026.05.12",
                &tags(&[
                    "2026.05.12.1",
                    "2026.05.12.3",
                    "2026.05.12.x",
                    "2026.05.12.2a"
                ])
            ),
            "2026.05.12.4"
        );
        assert_eq!(
            release_version("2026.05.12", &tags(&["2026.05.11.7"])),
            "2026.05.12"
        );
    }

    #[test]
    fn pre_release_format() {
        assert_eq!(
            pre_release_version("2026.05.12", at(2026, 5, 12, 14, 32), "abc1234"),
            "2026.05.12-pre.1432.UTC+0000+abc1234"
        );
    }

    #[test]
    fn win_versions() {
        assert_eq!(win_version("2026.05.12.1"), "26.05.12.1");
        assert_eq!(win_version("2026.05.12-pre.1432.UTC+0000+abc"), "26.05.12");
        assert_eq!(win_version("2026.5.12-15-gabc1234.dirty"), "26.5.12");
        assert_eq!(win_version(UNSTAMPED), "0.0.0");
        assert_eq!(win_version("1.2"), "1.2.0");
        for v in ["26.05.12.1", "26.05.12", "0.0.0", "1.2.0"] {
            assert!(validate_msi_version(v).is_ok(), "{v}");
        }
    }

    #[test]
    fn numeric_of_describe_forms() {
        assert_eq!(numeric("2026.5.12"), "2026.5.12");
        assert_eq!(numeric("2026.5.12-15-gabc1234"), "2026.5.12");
        assert_eq!(numeric("2026.5.12-15-gabc1234.dirty"), "2026.5.12");
        assert_eq!(
            numeric("2026.05.12-pre.1432.UTC+0000+abc1234"),
            "2026.05.12"
        );
        assert_eq!(numeric("2026.05.12+meta"), "2026.05.12");
    }

    #[test]
    fn bundle_revision_wraps() {
        assert_eq!(bundle_revision(at(2024, 1, 1, 0, 0)), 0);
        assert_eq!(bundle_revision(at(2024, 1, 1, 0, 1)), 1);
        let wrap =
            Utc.with_ymd_and_hms(2024, 1, 1, 0, 0, 0).unwrap() + chrono::Duration::minutes(65536);
        assert_eq!(bundle_revision(wrap), 0);
    }

    #[test]
    fn bundle_version_has_four_parts() {
        assert_eq!(bundle_version("26.05.12.1", 7), "26.05.12.7");
        assert_eq!(bundle_version("26.05.12", 7), "26.05.12.7");
        assert_eq!(bundle_version("1", 7), "1.0.0.7");
    }

    #[test]
    fn msi_version_limits() {
        assert!(validate_msi_version("256.0.0").is_err());
        assert!(validate_msi_version("1.256.0").is_err());
        assert!(validate_msi_version("1.2.65536").is_err());
        assert!(validate_msi_version("1.2.3.4.5").is_err());
        assert!(validate_msi_version("1.x.3").is_err());
        assert!(validate_msi_version("").is_err());
        assert!(validate_msi_version("255.255.65535.65535").is_ok());
    }

    #[test]
    fn mac_versions() {
        assert_eq!(mac_short_version("2026.05.12.1"), "2026.5.12");
        assert_eq!(mac_short_version("2026.05"), "2026.5.0");
        assert_eq!(mac_bundle_version("2026.05.12.1"), "2026.5.12.1");
        assert_eq!(mac_short_version("0.0.0"), "0.0.0");
    }

    #[test]
    fn version_set() {
        let set = VersionSet::new("2026.05.12.1", None, at(2024, 1, 1, 0, 5));
        assert_eq!(set.numeric, "2026.05.12.1");
        assert_eq!(set.win_version, "26.05.12.1");
        assert_eq!(set.bundle_version, "26.05.12.5");
        assert_eq!(set.mac_short_version, "2026.5.12");
        let set = VersionSet::new("2026.05.12", Some("26.05.13"), at(2024, 1, 1, 0, 5));
        assert_eq!(set.win_version, "26.05.13");
    }
}
