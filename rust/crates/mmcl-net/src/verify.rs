// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Download integrity: the expected digest of a download, hashed while the bytes stream in and
//! checked before the file is published (so mismatched bytes never reach the destination).

use md5::Md5;
use mmcl_util::hash::{HashAlgorithm, constant_time_hex_eq};
use sha1::{Digest, Sha1};
use sha2::Sha256;

/// The digest a download must have.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ExpectedHash {
    pub algorithm: HashAlgorithm,
    /// Hex, any case.
    pub hex: String,
}

impl ExpectedHash {
    pub fn new(algorithm: HashAlgorithm, hex: impl Into<String>) -> Self {
        ExpectedHash {
            algorithm,
            hex: hex.into().trim().to_string(),
        }
    }

    pub fn sha1(hex: impl Into<String>) -> Self {
        Self::new(HashAlgorithm::Sha1, hex)
    }

    pub fn sha256(hex: impl Into<String>) -> Self {
        Self::new(HashAlgorithm::Sha256, hex)
    }

    pub fn md5(hex: impl Into<String>) -> Self {
        Self::new(HashAlgorithm::Md5, hex)
    }

    /// The strongest usable declared hash: SHA-256, then SHA-1, then MD5. Usable means
    /// present, not blank and not the `"-1"` "no hash" marker.
    pub fn strongest(sha256: Option<&str>, sha1: Option<&str>, md5: Option<&str>) -> Option<Self> {
        [
            (HashAlgorithm::Sha256, sha256),
            (HashAlgorithm::Sha1, sha1),
            (HashAlgorithm::Md5, md5),
        ]
        .into_iter()
        .find_map(|(a, v)| v.filter(|s| is_usable_hash(s)).map(|s| Self::new(a, s)))
    }

    /// `true` when `actual_hex` equals the expected digest (case-insensitive, constant time).
    pub fn matches(&self, actual_hex: &str) -> bool {
        constant_time_hex_eq(&self.hex, actual_hex)
    }
}

/// Present, not blank and not `"-1"`.
pub fn is_usable_hash(s: &str) -> bool {
    let t = s.trim();
    !t.is_empty() && t != "-1"
}

pub(crate) fn algorithm_name(a: HashAlgorithm) -> &'static str {
    match a {
        HashAlgorithm::Sha1 => "SHA-1",
        HashAlgorithm::Sha256 => "SHA-256",
        HashAlgorithm::Md5 => "MD5",
    }
}

/// An incremental digest.
pub(crate) enum StreamHasher {
    Sha1(Sha1),
    Sha256(Sha256),
    Md5(Md5),
}

impl StreamHasher {
    pub(crate) fn new(a: HashAlgorithm) -> Self {
        match a {
            HashAlgorithm::Sha1 => StreamHasher::Sha1(Sha1::new()),
            HashAlgorithm::Sha256 => StreamHasher::Sha256(Sha256::new()),
            HashAlgorithm::Md5 => StreamHasher::Md5(Md5::new()),
        }
    }

    pub(crate) fn update(&mut self, b: &[u8]) {
        match self {
            StreamHasher::Sha1(h) => h.update(b),
            StreamHasher::Sha256(h) => h.update(b),
            StreamHasher::Md5(h) => h.update(b),
        }
    }

    pub(crate) fn finish_hex(self) -> String {
        match self {
            StreamHasher::Sha1(h) => hex::encode(h.finalize()),
            StreamHasher::Sha256(h) => hex::encode(h.finalize()),
            StreamHasher::Md5(h) => hex::encode(h.finalize()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strongest_usable_hash_wins() {
        let h = ExpectedHash::strongest(Some("-1"), Some(" ABC "), Some("def")).unwrap();
        assert_eq!(h, ExpectedHash::sha1("abc".to_uppercase()));
        assert_eq!(
            ExpectedHash::strongest(Some("aa"), Some("bb"), None)
                .unwrap()
                .algorithm,
            HashAlgorithm::Sha256
        );
        assert_eq!(
            ExpectedHash::strongest(None, Some(""), Some("cc"))
                .unwrap()
                .algorithm,
            HashAlgorithm::Md5
        );
        assert!(ExpectedHash::strongest(None, Some("  "), Some("-1")).is_none());
    }

    #[test]
    fn stream_hashes_match_the_one_shot_helpers() {
        for a in [
            HashAlgorithm::Sha1,
            HashAlgorithm::Sha256,
            HashAlgorithm::Md5,
        ] {
            let mut h = StreamHasher::new(a);
            h.update(b"hello ");
            h.update(b"world");
            let got = h.finish_hex();
            let want = match a {
                HashAlgorithm::Sha1 => mmcl_util::hash::sha1_hex(b"hello world"),
                HashAlgorithm::Sha256 => mmcl_util::hash::sha256_hex(b"hello world"),
                HashAlgorithm::Md5 => mmcl_util::hash::md5_hex(b"hello world"),
            };
            assert_eq!(got, want);
            assert!(ExpectedHash::new(a, want.to_uppercase()).matches(&got));
        }
    }
}
