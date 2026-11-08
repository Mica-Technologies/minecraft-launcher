// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `HashUtilities`: SHA-1, SHA-256 and MD5 of files and bytes, and constant-time verification.
//!
//! Digests are lowercase, zero-padded hex. Files are read with a 256 KiB buffer: hashing
//! dominates a full verify, and an 8 KB buffer was two to four times slower.

use std::fs::File;
use std::io::{self, Read};
use std::path::Path;

use md5::Md5;
use mmcl_i18n::tf;
use sha1::{Digest, Sha1};
use sha2::Sha256;

/// Read buffer for file hashing.
pub const HASH_BUFFER_BYTES: usize = 256 * 1024;

/// The supported digests.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum HashAlgorithm {
    Sha1,
    Sha256,
    Md5,
}

impl HashAlgorithm {
    /// Hex length of the digest (40, 64 or 32).
    pub const fn hex_len(self) -> usize {
        match self {
            HashAlgorithm::Sha1 => 40,
            HashAlgorithm::Sha256 => 64,
            HashAlgorithm::Md5 => 32,
        }
    }

    fn failure_key(self) -> &'static str {
        match self {
            HashAlgorithm::Sha1 => "log.hashUtil.sha1CalcFailed",
            HashAlgorithm::Sha256 => "log.hashUtil.sha256CalcFailed",
            HashAlgorithm::Md5 => "log.hashUtil.md5CalcFailed",
        }
    }
}

/// Lowercase hex, two chars per byte.
pub fn bytes_to_hex(bytes: &[u8]) -> String {
    hex::encode(bytes)
}

/// SHA-1 of `data` as hex.
pub fn sha1_hex(data: &[u8]) -> String {
    hex::encode(Sha1::digest(data))
}

/// SHA-256 of `data` as hex.
pub fn sha256_hex(data: &[u8]) -> String {
    hex::encode(Sha256::digest(data))
}

/// SHA-256 of `s`'s UTF-8 bytes as hex.
pub fn sha256_hex_str(s: &str) -> String {
    sha256_hex(s.as_bytes())
}

/// MD5 of `data` as hex.
pub fn md5_hex(data: &[u8]) -> String {
    hex::encode(Md5::digest(data))
}

fn digest_reader<D: Digest>(mut r: impl Read) -> io::Result<String> {
    let mut hasher = D::new();
    let mut buf = vec![0u8; HASH_BUFFER_BYTES];
    loop {
        match r.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => hasher.update(&buf[..n]),
            Err(e) if e.kind() == io::ErrorKind::Interrupted => {}
            Err(e) => return Err(e),
        }
    }
    Ok(hex::encode(hasher.finalize()))
}

/// The digest of a file's contents, or the I/O error.
pub fn try_file_hash(path: &Path, algo: HashAlgorithm) -> io::Result<String> {
    let f = File::open(path)?;
    match algo {
        HashAlgorithm::Sha1 => digest_reader::<Sha1>(f),
        HashAlgorithm::Sha256 => digest_reader::<Sha256>(f),
        HashAlgorithm::Md5 => digest_reader::<Md5>(f),
    }
}

/// The digest of a file's contents; on an I/O error, logs it and returns `None`.
pub fn file_hash(path: &Path, algo: HashAlgorithm) -> Option<String> {
    match try_file_hash(path, algo) {
        Ok(h) => Some(h),
        Err(e) => {
            tracing::error!(error = %e, "{}", tf!(algo.failure_key(), path));
            None
        }
    }
}

/// `getFileSHA1`.
pub fn file_sha1(path: &Path) -> Option<String> {
    file_hash(path, HashAlgorithm::Sha1)
}

/// `getFileSHA256`.
pub fn file_sha256(path: &Path) -> Option<String> {
    file_hash(path, HashAlgorithm::Sha256)
}

/// `getFileMD5`.
pub fn file_md5(path: &Path) -> Option<String> {
    file_hash(path, HashAlgorithm::Md5)
}

/// Compares two hex strings case-insensitively in time independent of where they differ.
/// Different lengths are unequal.
pub fn constant_time_hex_eq(a: &str, b: &str) -> bool {
    let (a, b) = (a.as_bytes(), b.as_bytes());
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b) {
        diff |= x.to_ascii_lowercase() ^ y.to_ascii_lowercase();
    }
    diff == 0
}

/// `true` when `path` is a regular file whose digest equals `expected` (any case).
pub fn verify(path: &Path, expected: &str, algo: HashAlgorithm) -> bool {
    if !path.is_file() {
        return false;
    }
    file_hash(path, algo).is_some_and(|actual| constant_time_hex_eq(&actual, expected.trim()))
}

/// `verifySHA1`.
pub fn verify_sha1(path: &Path, expected: &str) -> bool {
    verify(path, expected, HashAlgorithm::Sha1)
}

/// `verifySHA256`.
pub fn verify_sha256(path: &Path, expected: &str) -> bool {
    verify(path, expected, HashAlgorithm::Sha256)
}

/// `verifyMD5`.
pub fn verify_md5(path: &Path, expected: &str) -> bool {
    verify(path, expected, HashAlgorithm::Md5)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn write_file(dir: &Path, name: &str, content: &str) -> PathBuf {
        let p = dir.join(name);
        std::fs::write(&p, content.as_bytes()).unwrap();
        p
    }

    fn is_lower_hex(s: &str, len: usize) -> bool {
        s.len() == len
            && s.bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    }

    // ---- HashUtilitiesHexTest ----

    #[test]
    fn bytes_to_hex_is_lower_case_two_chars_per_byte_with_leading_zeros() {
        assert_eq!(bytes_to_hex(&[0x00, 0x0f, 0x10, 0xff]), "000f10ff");
        assert_eq!(bytes_to_hex(&[]), "");
    }

    #[test]
    fn sha256_hex_matches_known_vectors() {
        assert_eq!(
            sha256_hex_str(""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        assert_eq!(
            sha256_hex_str("abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn sha1_hex_matches_known_vector() {
        assert_eq!(sha1_hex(b"abc"), "a9993e364706816aba3e25717850c26c9cd0d89d");
    }

    #[test]
    fn sha256_hex_is_always_64_hex_chars() {
        for s in ["", "a", "hello world", "\u{e9}\u{e8}\u{ea}"] {
            assert_eq!(sha256_hex_str(s).len(), 64);
        }
    }

    // ---- HashUtilitiesFileTest ----

    #[test]
    fn verify_sha1_matches_known_vector() {
        let d = tempfile::tempdir().unwrap();
        let f = write_file(d.path(), "a.txt", "abc");
        assert!(verify_sha1(&f, "a9993e364706816aba3e25717850c26c9cd0d89d"));
    }

    #[test]
    fn verify_sha1_accepts_mixed_case_expected_hash() {
        let d = tempfile::tempdir().unwrap();
        let f = write_file(d.path(), "a.txt", "abc");
        assert!(verify_sha1(&f, "A9993E364706816ABA3E25717850C26C9CD0D89D"));
    }

    #[test]
    fn verify_sha1_rejects_tampered_content() {
        let d = tempfile::tempdir().unwrap();
        let f = write_file(d.path(), "a.txt", "abcd");
        assert!(!verify_sha1(&f, "a9993e364706816aba3e25717850c26c9cd0d89d"));
    }

    #[test]
    fn verify_sha1_returns_false_for_missing_file() {
        let d = tempfile::tempdir().unwrap();
        assert!(!verify_sha1(
            &d.path().join("does-not-exist.txt"),
            "a9993e364706816aba3e25717850c26c9cd0d89d"
        ));
    }

    #[test]
    fn verify_sha256_matches_known_vector() {
        let d = tempfile::tempdir().unwrap();
        let f = write_file(d.path(), "a.txt", "abc");
        assert!(verify_sha256(
            &f,
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        ));
    }

    #[test]
    fn verify_sha256_rejects_wrong_hash() {
        let d = tempfile::tempdir().unwrap();
        let f = write_file(d.path(), "a.txt", "abc");
        assert!(!verify_sha256(
            &f,
            "0000000000000000000000000000000000000000000000000000000000000000"
        ));
    }

    #[test]
    fn verify_md5_matches_known_vector() {
        let d = tempfile::tempdir().unwrap();
        let f = write_file(d.path(), "a.txt", "abc");
        assert!(verify_md5(&f, "900150983cd24fb0d6963f7d28e17f72"));
    }

    #[test]
    fn get_file_sha1_produces_lower_case_40_char_hex() {
        let d = tempfile::tempdir().unwrap();
        let f = write_file(d.path(), "a.txt", "hello world");
        assert!(is_lower_hex(&file_sha1(&f).unwrap(), 40));
    }

    #[test]
    fn get_file_sha256_produces_lower_case_64_char_hex() {
        let d = tempfile::tempdir().unwrap();
        let f = write_file(d.path(), "a.txt", "hello world");
        assert!(is_lower_hex(&file_sha256(&f).unwrap(), 64));
    }

    #[test]
    fn get_file_md5_produces_lower_case_32_char_hex() {
        let d = tempfile::tempdir().unwrap();
        let f = write_file(d.path(), "a.txt", "hello world");
        assert!(is_lower_hex(&file_md5(&f).unwrap(), 32));
    }

    #[test]
    fn verify_sha1_returns_false_for_directory() {
        let d = tempfile::tempdir().unwrap();
        assert!(!verify_sha1(d.path(), "anything"));
    }

    #[test]
    fn missing_file_hash_is_none() {
        let d = tempfile::tempdir().unwrap();
        assert_eq!(file_sha1(&d.path().join("nope")), None);
    }

    #[test]
    fn large_file_hash_spans_several_buffers() {
        let d = tempfile::tempdir().unwrap();
        let data = vec![7u8; HASH_BUFFER_BYTES * 2 + 13];
        let p = d.path().join("big.bin");
        std::fs::write(&p, &data).unwrap();
        assert_eq!(file_sha256(&p).unwrap(), sha256_hex(&data));
        assert_eq!(file_md5(&p).unwrap(), md5_hex(&data));
    }

    #[test]
    fn constant_time_compare_rules() {
        assert!(constant_time_hex_eq("abCD", "ABcd"));
        assert!(!constant_time_hex_eq("abc", "abcd"));
        assert!(!constant_time_hex_eq("abce", "abcd"));
        assert!(constant_time_hex_eq("", ""));
    }
}
