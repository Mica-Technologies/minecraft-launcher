// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Dotted version comparison (`VersionUtilities.compareVersionNumbers`), used for the
//! "update available" check against `.installed_version`.

use std::cmp::Ordering;

/// Strips build metadata: from the first `-`, then the first `+`, then the first `_`
/// (Forge's `14.23.5.2855_230729ER` convention). Empty becomes `"0"`.
fn strip_suffixes(version: &str) -> &str {
    let mut v = version;
    for sep in ['-', '+', '_'] {
        if let Some(i) = v.find(sep) {
            v = &v[..i];
        }
    }
    if v.is_empty() { "0" } else { v }
}

/// A segment's leading run of ASCII digits as a number; empty or overflowing is 0.
fn parse_segment(s: &str) -> i32 {
    let end = s.find(|c: char| !c.is_ascii_digit()).unwrap_or(s.len());
    s[..end].parse().unwrap_or(0)
}

/// Compares two dotted versions segment by segment; missing segments count as 0.
/// Never panics.
pub fn compare_versions(a: &str, b: &str) -> Ordering {
    let left: Vec<&str> = strip_suffixes(a).split('.').collect();
    let right: Vec<&str> = strip_suffixes(b).split('.').collect();
    for i in 0..left.len().max(right.len()) {
        let x = left.get(i).map_or(0, |s| parse_segment(s));
        let y = right.get(i).map_or(0, |s| parse_segment(s));
        match x.cmp(&y) {
            Ordering::Equal => {}
            other => return other,
        }
    }
    Ordering::Equal
}

/// `compare_versions` as Java's `-1 / 0 / 1`.
pub fn compare_versions_i32(a: &str, b: &str) -> i32 {
    match compare_versions(a, b) {
        Ordering::Less => -1,
        Ordering::Equal => 0,
        Ordering::Greater => 1,
    }
}

/// True when `remote` is newer than `installed`.
pub fn is_newer(installed: &str, remote: &str) -> bool {
    compare_versions(installed, remote) == Ordering::Less
}

#[cfg(test)]
mod tests {
    use super::*;

    fn c(a: &str, b: &str) -> i32 {
        compare_versions_i32(a, b)
    }

    #[test]
    fn semver_suffixes_are_stripped() {
        assert_eq!(c("1.2.3", "1.2.3+abc"), 0);
        assert_eq!(c("1.2.3", "1.2.3-rc1"), 0);
        assert_eq!(c("2026.1.0-15-gabc1234.dirty", "2026.1.0"), 0);
    }

    #[test]
    fn underscore_build_metadata_is_stripped() {
        assert_eq!(c("14.23.5.2855_230729ER", "14.23.5.2855_230729ER"), 0);
        assert_eq!(c("14.23.5.2855_230729ER", "14.23.5.2855_231005AB"), 0);
        assert_eq!(c("14.23.5.2854", "14.23.5.2855_230729ER"), -1);
    }

    #[test]
    fn leading_digits_extracted_from_mixed_segments() {
        assert_eq!(c("1.2rc1.3", "1.2.3"), 0);
        assert_eq!(c("1.alpha.3", "1.0.3"), 0);
    }

    #[test]
    fn basic_numeric_ordering() {
        assert_eq!(c("1.2.3", "1.2.3"), 0);
        assert_eq!(c("1.2.3", "1.2.4"), -1);
        assert_eq!(c("1.2.4", "1.2.3"), 1);
        assert_eq!(c("1.9.0", "1.10.0"), -1);
        assert_eq!(c("2.0", "1.99.99"), 1);
    }

    #[test]
    fn mismatched_segment_counts() {
        assert_eq!(c("1.0", "1.0.0"), 0);
        assert_eq!(c("1.0", "1.0.1"), -1);
        assert_eq!(c("1.0.1", "1.0"), 1);
    }

    #[test]
    fn empty_and_degenerate_inputs() {
        assert_eq!(c("", "0"), 0);
        assert_eq!(c("", ""), 0);
        assert_eq!(c("_230729ER", "0"), 0);
        assert_eq!(c("99999999999.1", "0.1"), 0);
        assert_eq!(c("...", "0"), 0);
    }

    #[test]
    fn newer() {
        assert!(is_newer("26.11.4.2", "26.11.4.3"));
        assert!(!is_newer("26.11.4.3", "26.11.4.3"));
    }
}
