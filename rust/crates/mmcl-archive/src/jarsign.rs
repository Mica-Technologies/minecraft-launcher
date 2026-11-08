// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Strips the Mojang signature from pre-1.6 client jars (Java `JarSigningStripper`).
//!
//! Old clients (1.5.2 and earlier) ship signed jars, and the class patches an old Forge applies
//! break the signature, so the JVM refuses to load them. The jar is rewritten without its
//! signature files and without the per-entry digests in its manifest.
//!
//! **Never for 1.6 and later:** FML's sanity checker then aborts with "CRITICAL TAMPERING WITH
//! MINECRAFT".

use std::fs::{self, File};
use std::io::{self, BufReader, BufWriter, Read, Seek, Write};
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use mmcl_i18n::tf;
use regex::Regex;
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, DateTime, ZipArchive, ZipWriter};

use crate::manifest::Manifest;

const SIG_SUFFIXES: [&str; 4] = [".SF", ".DSA", ".RSA", ".EC"];
const MANIFEST_NAME: &str = "META-INF/MANIFEST.MF";

/// `true` when `mc_version` is a release older than 1.6 (`^1\.(\d+)` with a minor below 6).
/// `None`, empty, snapshots such as `20w28a`, and anything not starting `1.` give `false`.
pub fn is_strip_required_for(mc_version: Option<&str>) -> bool {
    static RELEASE_MINOR: OnceLock<Regex> = OnceLock::new();
    let Some(version) = mc_version.filter(|v| !v.is_empty()) else {
        return false;
    };
    let re = RELEASE_MINOR.get_or_init(|| Regex::new(r"^1\.(\d+)").expect("valid regex"));
    re.captures(version)
        .and_then(|c| c[1].parse::<i32>().ok())
        .is_some_and(|minor| minor < 6)
}

/// `true` for a signature file: under `META-INF/` (any case) and ending `.SF`, `.DSA`, `.RSA`
/// or `.EC` (any case).
pub fn is_signature_file(name: &str) -> bool {
    let upper = name.to_ascii_uppercase();
    upper.starts_with("META-INF/") && SIG_SUFFIXES.iter().any(|s| upper.ends_with(s))
}

/// Rewrites `jar` without its signature. Returns `false`, leaving the file untouched, when it
/// is not a regular file or has no signature file.
///
/// The rewrite drops the signature files, rewrites `META-INF/MANIFEST.MF` without its
/// `Signature-Version` main attribute and without any per-entry section, and copies every
/// other entry (Deflate, with its modified time). It goes to `<jar>.unsigned.tmp`, which then
/// replaces the original; the temp file is removed on any failure.
pub fn strip_signing(jar: &Path) -> io::Result<bool> {
    if !jar.is_file() {
        return Ok(false);
    }
    let mut archive = ZipArchive::new(BufReader::new(File::open(jar)?)).map_err(zip_err)?;
    let signed = archive.file_names().any(is_signature_file);
    if !signed {
        return Ok(false);
    }

    let temp = temp_path(jar);
    let rewritten = write_stripped(&mut archive, &temp);
    // Close the source before replacing it (Windows refuses to replace an open file).
    drop(archive);
    if let Err(e) = rewritten {
        let _ = fs::remove_file(&temp);
        return Err(e);
    }
    if let Err(e) = fs::rename(&temp, jar) {
        let _ = fs::remove_file(&temp);
        return Err(e);
    }
    let file_name = jar
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    tracing::debug!("{}", tf!("log.jarStripper.strippedSigning", &file_name));
    Ok(true)
}

fn temp_path(jar: &Path) -> PathBuf {
    let abs = std::path::absolute(jar).unwrap_or_else(|_| jar.to_path_buf());
    let mut s = abs.into_os_string();
    s.push(".unsigned.tmp");
    PathBuf::from(s)
}

fn write_stripped<R: Read + Seek>(archive: &mut ZipArchive<R>, temp: &Path) -> io::Result<()> {
    let mut out = ZipWriter::new(BufWriter::new(File::create(temp)?));
    let mut manifest_written = false;
    for index in 0..archive.len() {
        let mut entry = archive.by_index(index).map_err(zip_err)?;
        let name = entry.name().to_owned();
        if is_signature_file(&name) {
            continue;
        }
        let options = SimpleFileOptions::default()
            .compression_method(CompressionMethod::Deflated)
            .last_modified_time(
                entry
                    .last_modified()
                    .unwrap_or_else(DateTime::default_for_write),
            )
            .large_file(entry.size() >= u64::from(u32::MAX));

        if name.eq_ignore_ascii_case(MANIFEST_NAME) {
            if manifest_written {
                continue;
            }
            let mut raw = Vec::new();
            entry.read_to_end(&mut raw)?;
            let mut manifest = Manifest::parse(&raw)?;
            manifest.main.remove("Signature-Version");
            manifest.entries.clear();
            out.start_file(MANIFEST_NAME, options).map_err(zip_err)?;
            out.write_all(&manifest.to_bytes())?;
            manifest_written = true;
            continue;
        }

        if entry.is_dir() {
            out.add_directory(name, options).map_err(zip_err)?;
            continue;
        }
        out.start_file(name, options).map_err(zip_err)?;
        io::copy(&mut entry, &mut out)?;
    }
    let mut inner = out.finish().map_err(zip_err)?;
    inner.flush()?;
    Ok(())
}

fn zip_err(e: zip::result::ZipError) -> io::Error {
    match e {
        zip::result::ZipError::Io(io) => io,
        other => io::Error::new(io::ErrorKind::InvalidData, other),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::write_zip;

    #[test]
    fn requires_strip_for_pre_1_6_releases() {
        assert!(is_strip_required_for(Some("1.5.2")));
        assert!(is_strip_required_for(Some("1.0")));
        assert!(is_strip_required_for(Some("1.5")));
    }

    #[test]
    fn does_not_require_strip_for_1_6_and_later() {
        for v in ["1.6", "1.6.4", "1.12.2", "1.21"] {
            assert!(!is_strip_required_for(Some(v)), "{v}");
        }
    }

    #[test]
    fn treats_snapshot_and_oddball_strings_as_modern() {
        assert!(!is_strip_required_for(Some("20w28a")));
        assert!(!is_strip_required_for(Some("1.21-pre3")));
        // A minor that overflows i32 is not a match, as in Java.
        assert!(!is_strip_required_for(Some("1.99999999999")));
    }

    #[test]
    fn treats_none_and_empty_as_not_requiring_strip() {
        assert!(!is_strip_required_for(None));
        assert!(!is_strip_required_for(Some("")));
    }

    #[test]
    fn treats_version_without_leading_one_dot_as_modern() {
        assert!(!is_strip_required_for(Some("2.0")));
        assert!(!is_strip_required_for(Some("b1.7.3")));
    }

    fn signed_jar(dir: &Path) -> PathBuf {
        let jar = dir.join("signed.jar");
        write_zip(
            &jar,
            &[
                (
                    "META-INF/MANIFEST.MF",
                    b"Manifest-Version: 1.0\nSignature-Version: 1.0\nCreated-By: test\n\nName: net/minecraft/client/Minecraft.class\nSHA1-Digest: abc\n\n".as_slice(),
                ),
                ("META-INF/MOJANG_C.SF", b"signature-file-content"),
                ("META-INF/MOJANG_C.DSA", b"signature-block-content"),
                ("net/minecraft/client/Minecraft.class", b"class-bytes"),
                ("lang/en_US.lang", b"hello=world"),
            ],
        );
        jar
    }

    fn read_entry(jar: &Path, name: &str) -> Option<Vec<u8>> {
        let mut zip = ZipArchive::new(File::open(jar).unwrap()).unwrap();
        let mut entry = zip.by_name(name).ok()?;
        let mut buf = Vec::new();
        entry.read_to_end(&mut buf).unwrap();
        Some(buf)
    }

    #[test]
    fn strip_signing_removes_signature_files_and_preserves_other_entries() {
        let dir = tempfile::tempdir().unwrap();
        let jar = signed_jar(dir.path());
        assert!(
            strip_signing(&jar).unwrap(),
            "a signed jar should be reported as rewritten"
        );
        assert!(read_entry(&jar, "META-INF/MOJANG_C.SF").is_none());
        assert!(read_entry(&jar, "META-INF/MOJANG_C.DSA").is_none());
        assert_eq!(
            read_entry(&jar, "net/minecraft/client/Minecraft.class").unwrap(),
            b"class-bytes"
        );
        assert_eq!(read_entry(&jar, "lang/en_US.lang").unwrap(), b"hello=world");
    }

    #[test]
    fn strip_signing_removes_signature_version_and_entry_sections() {
        let dir = tempfile::tempdir().unwrap();
        let jar = signed_jar(dir.path());
        strip_signing(&jar).unwrap();
        let bytes = read_entry(&jar, MANIFEST_NAME).unwrap();
        let manifest = Manifest::parse(&bytes).unwrap();
        assert_eq!(manifest.main.get("Signature-Version"), None);
        assert_eq!(manifest.main.get("Created-By"), Some("test"));
        assert!(manifest.entries.is_empty());
        assert_eq!(bytes, b"Manifest-Version: 1.0\r\nCreated-By: test\r\n\r\n");
    }

    #[test]
    fn strip_signing_returns_false_and_leaves_unsigned_jar_untouched() {
        let dir = tempfile::tempdir().unwrap();
        let jar = dir.path().join("unsigned.jar");
        write_zip(
            &jar,
            &[
                (
                    "META-INF/MANIFEST.MF",
                    b"Manifest-Version: 1.0\n".as_slice(),
                ),
                ("net/minecraft/client/Minecraft.class", b"class-bytes"),
            ],
        );
        let before = fs::read(&jar).unwrap();
        assert!(!strip_signing(&jar).unwrap());
        assert_eq!(fs::read(&jar).unwrap(), before);
    }

    #[test]
    fn strip_signing_returns_false_for_missing_file() {
        let dir = tempfile::tempdir().unwrap();
        assert!(!strip_signing(&dir.path().join("does-not-exist.jar")).unwrap());
    }

    #[test]
    fn strip_signing_returns_false_for_a_directory() {
        let dir = tempfile::tempdir().unwrap();
        assert!(!strip_signing(dir.path()).unwrap());
    }

    #[test]
    fn strip_signing_leaves_no_temp_file_behind_on_success() {
        let dir = tempfile::tempdir().unwrap();
        let jar = signed_jar(dir.path());
        strip_signing(&jar).unwrap();
        assert!(!temp_path(&jar).exists());
    }

    #[test]
    fn strip_signing_leaves_no_temp_file_behind_on_failure() {
        let dir = tempfile::tempdir().unwrap();
        let jar = dir.path().join("bad.jar");
        write_zip(
            &jar,
            &[
                (
                    "META-INF/MANIFEST.MF",
                    b"this is not a manifest\n".as_slice(),
                ),
                ("META-INF/X.RSA", b"sig"),
            ],
        );
        let before = fs::read(&jar).unwrap();
        assert!(strip_signing(&jar).is_err());
        assert!(!temp_path(&jar).exists());
        assert_eq!(fs::read(&jar).unwrap(), before);
    }

    #[test]
    fn signature_file_detection_is_case_insensitive() {
        assert!(is_signature_file("META-INF/MOJANG_C.SF"));
        assert!(is_signature_file("meta-inf/cert.rsa"));
        assert!(is_signature_file("META-INF/KEY.EC"));
        assert!(!is_signature_file("META-INF/MANIFEST.MF"));
        assert!(!is_signature_file("net/a.SF"));
    }

    #[test]
    fn keeps_entry_modified_times() {
        let dir = tempfile::tempdir().unwrap();
        let jar = signed_jar(dir.path());
        let before = {
            let mut zip = ZipArchive::new(File::open(&jar).unwrap()).unwrap();
            zip.by_name("lang/en_US.lang").unwrap().last_modified()
        };
        strip_signing(&jar).unwrap();
        let mut zip = ZipArchive::new(File::open(&jar).unwrap()).unwrap();
        let entry = zip.by_name("lang/en_US.lang").unwrap();
        assert_eq!(entry.last_modified(), before);
        assert_eq!(entry.compression(), CompressionMethod::Deflated);
    }
}
