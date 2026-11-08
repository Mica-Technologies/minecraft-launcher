// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! One streaming pass that computes every digest the editor writes or checks: SHA-1, SHA-256
//! and MD5 (the manifest's three hash slots) plus SHA-512 (what Modrinth publishes).
//!
//! Java's per-row "Calc" wrote only SHA-1 and dropped any SHA-256 the row had. The port writes
//! all three, SHA-256 primary (the strongest, and the first one the reader prefers), so a
//! recompute never leaves a stale hash behind.

use std::io::{self, Read};
use std::path::Path;

use md5::Md5;
use sha1::{Digest, Sha1};
use sha2::{Sha256, Sha512};

use crate::row::EditorRow;

/// Lower-case hex digests of one file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileHashes {
    pub sha1: String,
    pub sha256: String,
    pub md5: String,
    pub sha512: String,
}

impl FileHashes {
    /// Digests of everything `reader` yields.
    pub fn of_reader(mut reader: impl Read) -> io::Result<FileHashes> {
        let mut s1 = Sha1::new();
        let mut s256 = Sha256::new();
        let mut m5 = Md5::new();
        let mut s512 = Sha512::new();
        let mut buf = vec![0u8; 64 * 1024];
        loop {
            let n = match reader.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => n,
                Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                Err(e) => return Err(e),
            };
            let chunk = &buf[..n];
            s1.update(chunk);
            s256.update(chunk);
            m5.update(chunk);
            s512.update(chunk);
        }
        Ok(FileHashes {
            sha1: hex::encode(s1.finalize()),
            sha256: hex::encode(s256.finalize()),
            md5: hex::encode(m5.finalize()),
            sha512: hex::encode(s512.finalize()),
        })
    }

    /// Digests of a file.
    pub fn of_file(path: &Path) -> io::Result<FileHashes> {
        FileHashes::of_reader(std::io::BufReader::new(std::fs::File::open(path)?))
    }

    /// Writes the three manifest hashes into a row: SHA-256 primary, SHA-1 and MD5 as extras.
    pub fn apply_to(&self, row: &mut EditorRow) {
        row.hash = self.sha256.clone();
        row.hash_type = "sha256".into();
        row.extra_hashes.clear();
        row.put_extra_hash("sha1", Some(&self.sha1));
        row.put_extra_hash("md5", Some(&self.md5));
    }

    /// Whether these digests match a published one: SHA-512 when given (Modrinth's strongest),
    /// else SHA-1. `None` when nothing was published to compare against.
    pub fn verify_published(&self, sha512: Option<&str>, sha1: Option<&str>) -> Option<bool> {
        fn usable(s: Option<&str>) -> Option<&str> {
            s.map(str::trim).filter(|s| !s.is_empty() && *s != "-1")
        }
        if let Some(want) = usable(sha512) {
            return Some(want.eq_ignore_ascii_case(&self.sha512));
        }
        usable(sha1).map(|want| want.eq_ignore_ascii_case(&self.sha1))
    }
}

/// Downloads `url` into a temp file named `{prefix}*{suffix}`, hashes it and deletes it.
pub async fn remote_hashes(
    backend: &dyn crate::backend::EditorBackend,
    url: &str,
    prefix: &str,
    suffix: &str,
) -> Result<FileHashes, crate::backend::NetError> {
    use crate::backend::NetError;
    let tmp = tempfile::Builder::new()
        .prefix(prefix)
        .suffix(suffix)
        .tempfile()
        .map_err(|e| NetError::new(e.to_string()))?;
    // Keep only the path: the downloader writes (and may replace) the file itself; the guard
    // deletes it when this function returns.
    let path = tmp.into_temp_path();
    backend.download(url, &path).await?;
    FileHashes::of_file(&path).map_err(|e| NetError::new(e.to_string()))
}

/// The short form the status line shows (first 8 characters).
pub fn short(hash: &str) -> String {
    hash.chars().take(8).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn known_vectors() {
        let h = FileHashes::of_reader(&b"abc"[..]).unwrap();
        assert_eq!(h.sha1, "a9993e364706816aba3e25717850c26c9cd0d89d");
        assert_eq!(
            h.sha256,
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        assert_eq!(h.md5, "900150983cd24fb0d6963f7d28e17f72");
        assert!(h.sha512.starts_with("ddaf35a193617aba"));
        assert_eq!(h.sha512.len(), 128);
    }

    #[test]
    fn large_inputs_stream() {
        let data = vec![7u8; 200_000];
        let h = FileHashes::of_reader(&data[..]).unwrap();
        assert_eq!(h.sha1, mmcl_hex_sha1(&data));
    }

    fn mmcl_hex_sha1(d: &[u8]) -> String {
        hex::encode(Sha1::digest(d))
    }

    #[test]
    fn applying_writes_all_three_slots() {
        let h = FileHashes::of_reader(&b"abc"[..]).unwrap();
        let mut row = EditorRow {
            hash: "old".into(),
            hash_type: "sha1".into(),
            ..Default::default()
        };
        row.put_extra_hash("md5", Some("stale"));
        h.apply_to(&mut row);
        assert_eq!(row.hash_type, "sha256");
        assert_eq!(row.hash, h.sha256);
        let entry = row.to_entry();
        assert_eq!(entry.extra_hash("sha1"), Some(h.sha1.as_str()));
        assert_eq!(entry.extra_hash("md5"), Some(h.md5.as_str()));
        // Round trip through the document keeps all three.
        let mut doc = mmcl_manifest::document::ModPackDocument::blank();
        doc.write_file_list(mmcl_manifest::document::FileList::Mods, &[entry]);
        let back = &doc.read_file_list(mmcl_manifest::document::FileList::Mods)[0];
        assert_eq!(back.hash(), h.sha256);
        assert_eq!(back.extra_hash("sha1"), Some(h.sha1.as_str()));
        assert_eq!(back.extra_hash("md5"), Some(h.md5.as_str()));
    }

    #[test]
    fn published_hash_verification_prefers_sha512() {
        let h = FileHashes::of_reader(&b"abc"[..]).unwrap();
        assert_eq!(
            h.verify_published(Some(&h.sha512.to_uppercase()), None),
            Some(true)
        );
        assert_eq!(h.verify_published(Some("00"), Some(&h.sha1)), Some(false));
        assert_eq!(h.verify_published(None, Some(&h.sha1)), Some(true));
        assert_eq!(h.verify_published(Some(" "), Some("-1")), None);
        assert_eq!(h.verify_published(None, None), None);
        assert_eq!(short(&h.sha1), "a9993e36");
    }
}
