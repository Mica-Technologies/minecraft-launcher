// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `VersionUtilities.compareVersionNumbers`.
//!
//! The implementation lives in `mmcl-manifest` (layer 0, which needs it for update detection
//! against `.installed_version`); it is re-exported here so callers have one home for it and
//! the two can never disagree.

pub use mmcl_manifest::version::compare_versions;

#[cfg(test)]
mod tests {
    use super::compare_versions;
    use std::cmp::Ordering::{Equal, Greater, Less};

    #[test]
    fn semver_suffixes_are_stripped() {
        assert_eq!(compare_versions("1.2.3", "1.2.3+abc"), Equal);
        assert_eq!(compare_versions("1.2.3", "1.2.3-rc1"), Equal);
        assert_eq!(
            compare_versions("2026.1.0-15-gabc1234.dirty", "2026.1.0"),
            Equal
        );
    }

    #[test]
    fn underscore_build_metadata_is_stripped() {
        assert_eq!(
            compare_versions("14.23.5.2855_230729ER", "14.23.5.2855_230729ER"),
            Equal
        );
        assert_eq!(
            compare_versions("14.23.5.2855_230729ER", "14.23.5.2855_231005AB"),
            Equal
        );
        assert_eq!(
            compare_versions("14.23.5.2854", "14.23.5.2855_230729ER"),
            Less
        );
    }

    #[test]
    fn leading_digits_extracted_from_mixed_segments() {
        assert_eq!(compare_versions("1.2rc1.3", "1.2.3"), Equal);
        assert_eq!(compare_versions("1.alpha.3", "1.0.3"), Equal);
    }

    #[test]
    fn basic_numeric_ordering() {
        assert_eq!(compare_versions("1.2.3", "1.2.3"), Equal);
        assert_eq!(compare_versions("1.2.3", "1.2.4"), Less);
        assert_eq!(compare_versions("1.2.4", "1.2.3"), Greater);
        assert_eq!(compare_versions("1.9.0", "1.10.0"), Less);
        assert_eq!(compare_versions("2.0", "1.99.99"), Greater);
    }

    #[test]
    fn mismatched_segment_counts() {
        assert_eq!(compare_versions("1.0", "1.0.0"), Equal);
        assert_eq!(compare_versions("1.0", "1.0.1"), Less);
        assert_eq!(compare_versions("1.0.1", "1.0"), Greater);
    }

    #[test]
    fn empty_and_degenerate_inputs() {
        assert_eq!(compare_versions("", "0"), Equal);
        assert_eq!(compare_versions("", ""), Equal);
        assert_eq!(compare_versions("_230729ER", "0"), Equal);
        // Overflowing segments read as 0 instead of panicking.
        assert_eq!(compare_versions("99999999999.1", "0.1"), Equal);
    }
}
