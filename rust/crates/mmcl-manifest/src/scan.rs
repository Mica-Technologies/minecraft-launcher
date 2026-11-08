// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Scan acknowledgements (`packScanAcknowledgements`): an author's statement that a specific
//! heuristic scan finding is expected. Nekodetector hits can never be acknowledged; only
//! supplemental findings are matched against these.

use serde::{Deserialize, Serialize};

use crate::lenient;

/// The parts of a supplemental scan finding an acknowledgement is matched on. The security
/// crate builds one from its finding type, so this crate does not depend on it.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct FindingKey<'a> {
    /// The finding kind's enum name, e.g. `EMBEDDED_EXECUTABLE`.
    pub kind: Option<&'a str>,
    /// SHA-256 of the scanned file.
    pub file_sha256: Option<&'a str>,
    /// SHA-256 of the nested entry the finding is about.
    pub inner_sha256: Option<&'a str>,
    /// Where inside the file the finding is.
    pub locator: Option<&'a str>,
}

/// One acknowledgement entry.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct ScanAcknowledgement {
    #[serde(
        rename = "fileSha256",
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub file_sha256: Option<String>,
    #[serde(
        rename = "innerSha256",
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub inner_sha256: Option<String>,
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub kind: Option<String>,
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub locator: Option<String>,
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub reason: Option<String>,
}

/// Non-blank ack value equals the finding's value (trimmed, ignoring case); a blank finding
/// value never matches.
fn hash_matches(ack: &str, finding: Option<&str>) -> bool {
    match crate::non_blank(finding) {
        Some(f) => crate::eq_ignore_case(ack.trim(), f),
        None => false,
    }
}

impl ScanAcknowledgement {
    /// Whether this acknowledgement silences `finding`:
    ///
    /// 1. the kind must be set and equal the finding's kind, ignoring case;
    /// 2. at least one of `fileSha256` / `innerSha256` must be set, and every one that is set
    ///    must equal the finding's (both set means both must match);
    /// 3. a set `locator` must equal the finding's; a blank one matches any locator.
    pub fn matches(&self, finding: Option<&FindingKey<'_>>) -> bool {
        let Some(finding) = finding else {
            return false;
        };
        let Some(kind) = crate::non_blank(self.kind.as_deref()) else {
            return false;
        };
        let Some(finding_kind) = finding.kind else {
            return false;
        };
        if !crate::eq_ignore_case(kind, finding_kind) {
            return false;
        }
        let file = crate::non_blank(self.file_sha256.as_deref());
        let inner = crate::non_blank(self.inner_sha256.as_deref());
        if file.is_none() && inner.is_none() {
            return false;
        }
        if file.is_some_and(|f| !hash_matches(f, finding.file_sha256)) {
            return false;
        }
        if inner.is_some_and(|i| !hash_matches(i, finding.inner_sha256)) {
            return false;
        }
        if let Some(locator) = crate::non_blank(self.locator.as_deref()) {
            return match finding.locator {
                Some(found) => crate::eq_ignore_case(locator, found.trim()),
                None => false,
            };
        }
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const OUTER_A: &str = "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111";
    const OUTER_B: &str = "bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222";
    const INNER_X: &str = "cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333";
    const INNER_Y: &str = "dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444";
    const MARY: &str = "marytts/machinelearning/GMMTrainer.exe";
    const KIND: &str = "EMBEDDED_EXECUTABLE";

    fn finding<'a>(
        file: Option<&'a str>,
        inner: Option<&'a str>,
        locator: Option<&'a str>,
    ) -> FindingKey<'a> {
        FindingKey {
            kind: Some(KIND),
            file_sha256: file,
            inner_sha256: inner,
            locator,
        }
    }

    fn ack(
        file: Option<&str>,
        inner: Option<&str>,
        kind: Option<&str>,
        locator: Option<&str>,
    ) -> ScanAcknowledgement {
        ScanAcknowledgement {
            file_sha256: file.map(Into::into),
            inner_sha256: inner.map(Into::into),
            kind: kind.map(Into::into),
            locator: locator.map(Into::into),
            reason: Some("test".into()),
        }
    }

    #[test]
    fn inner_only_ack_matches_even_when_outer_changes() {
        let a = ack(None, Some(INNER_X), Some(KIND), Some(MARY));
        assert!(a.matches(Some(&finding(Some(OUTER_A), Some(INNER_X), Some(MARY)))));
        assert!(a.matches(Some(&finding(Some(OUTER_B), Some(INNER_X), Some(MARY)))));
    }

    #[test]
    fn inner_only_ack_rejects_when_inner_changes() {
        let a = ack(None, Some(INNER_X), Some(KIND), None);
        assert!(!a.matches(Some(&finding(Some(OUTER_A), Some(INNER_Y), Some(MARY)))));
    }

    #[test]
    fn outer_only_ack() {
        let a = ack(Some(OUTER_A), None, Some(KIND), None);
        assert!(a.matches(Some(&finding(Some(OUTER_A), Some(INNER_X), Some(MARY)))));
        assert!(!a.matches(Some(&finding(Some(OUTER_B), Some(INNER_X), Some(MARY)))));
    }

    #[test]
    fn both_hashes_set_requires_both_to_match() {
        let a = ack(Some(OUTER_A), Some(INNER_X), Some(KIND), None);
        assert!(a.matches(Some(&finding(Some(OUTER_A), Some(INNER_X), Some(MARY)))));
        assert!(!a.matches(Some(&finding(Some(OUTER_A), Some(INNER_Y), Some(MARY)))));
        assert!(!a.matches(Some(&finding(Some(OUTER_B), Some(INNER_X), Some(MARY)))));
    }

    #[test]
    fn neither_hash_set_never_matches() {
        let a = ack(None, None, Some(KIND), None);
        assert!(!a.matches(Some(&finding(Some(OUTER_A), Some(INNER_X), Some(MARY)))));
    }

    #[test]
    fn kind_rules() {
        let f = finding(Some(OUTER_A), Some(INNER_X), Some(MARY));
        assert!(!ack(None, Some(INNER_X), None, None).matches(Some(&f)));
        assert!(!ack(None, Some(INNER_X), Some("  "), None).matches(Some(&f)));
        assert!(!ack(None, Some(INNER_X), Some("IPV4_LITERAL"), None).matches(Some(&f)));
        assert!(ack(None, Some(INNER_X), Some(" embedded_executable "), None).matches(Some(&f)));
        let no_kind = FindingKey { kind: None, ..f };
        assert!(!ack(None, Some(INNER_X), Some(KIND), None).matches(Some(&no_kind)));
    }

    #[test]
    fn hash_comparison_is_case_insensitive() {
        let upper = INNER_X.to_uppercase();
        let a = ack(None, Some(&upper), Some(KIND), None);
        assert!(a.matches(Some(&finding(Some(OUTER_A), Some(INNER_X), Some(MARY)))));
    }

    #[test]
    fn locator_rules() {
        let a = ack(None, Some(INNER_X), Some(KIND), Some(MARY));
        assert!(a.matches(Some(&finding(Some(OUTER_A), Some(INNER_X), Some(MARY)))));
        assert!(!a.matches(Some(&finding(
            Some(OUTER_A),
            Some(INNER_X),
            Some("other/Entry.exe")
        ))));
        assert!(!a.matches(Some(&finding(Some(OUTER_A), Some(INNER_X), None))));
        let wildcard = ack(None, Some(INNER_X), Some(KIND), Some(""));
        assert!(wildcard.matches(Some(&finding(Some(OUTER_A), Some(INNER_X), Some(MARY)))));
        assert!(wildcard.matches(Some(&finding(
            Some(OUTER_A),
            Some(INNER_X),
            Some("different/path.exe")
        ))));
    }

    #[test]
    fn null_finding_never_matches() {
        assert!(!ack(None, Some(INNER_X), Some(KIND), None).matches(None));
    }

    #[test]
    fn blank_finding_hash_never_satisfies_a_set_ack() {
        let a = ack(None, Some(INNER_X), Some(KIND), None);
        assert!(!a.matches(Some(&finding(Some(OUTER_A), None, Some(MARY)))));
        assert!(!a.matches(Some(&finding(Some(OUTER_A), Some(""), Some(MARY)))));
    }
}
