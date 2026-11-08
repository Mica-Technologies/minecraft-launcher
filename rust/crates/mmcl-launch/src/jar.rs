// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Jar helpers: the entry-name set of an installer, single-entry reads and atomic extraction,
//! and `Main-Class` from `META-INF/MANIFEST.MF`.

use std::collections::HashSet;
use std::fs::{self, File};
use std::io::{self, BufReader, Read, Write};
use std::path::Path;

use mmcl_archive::manifest::Manifest;
use zip::ZipArchive;

pub use mmcl_archive::jarsign::{is_strip_required_for, strip_signing};
pub use mmcl_archive::{extract_jar, extract_jar_with};

/// The manifest entry name.
pub const MANIFEST_ENTRY: &str = "META-INF/MANIFEST.MF";

fn zip_err(e: zip::result::ZipError) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, e.to_string())
}

fn open(jar: &Path) -> io::Result<ZipArchive<BufReader<File>>> {
    ZipArchive::new(BufReader::new(File::open(jar)?)).map_err(zip_err)
}

/// Every entry name in `jar` (built once per installer; Java reopened the jar per probe).
pub fn entry_names(jar: &Path) -> io::Result<HashSet<String>> {
    Ok(open(jar)?.file_names().map(str::to_string).collect())
}

/// The largest single entry [`read_entry`] inflates (the declared size is never trusted).
/// The biggest real installer entries (bundled Forge jars, `client.lzma`) are a few tens of MiB.
pub const MAX_ENTRY_BYTES: u64 = 512 * 1024 * 1024;

/// One entry's bytes; `Ok(None)` when the entry is absent. An entry that inflates past
/// [`MAX_ENTRY_BYTES`] is an error.
pub fn read_entry(jar: &Path, entry: &str) -> io::Result<Option<Vec<u8>>> {
    read_entry_capped(jar, entry, MAX_ENTRY_BYTES)
}

fn read_entry_capped(jar: &Path, entry: &str, cap: u64) -> io::Result<Option<Vec<u8>>> {
    let mut z = open(jar)?;
    let f = match z.by_name(entry) {
        Ok(f) => f,
        Err(zip::result::ZipError::FileNotFound) => return Ok(None),
        Err(e) => return Err(zip_err(e)),
    };
    let mut out = Vec::with_capacity(f.size().min(64 * 1024 * 1024).min(cap) as usize);
    f.take(cap + 1).read_to_end(&mut out)?;
    if out.len() as u64 > cap {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("{entry} is larger than {cap} bytes"),
        ));
    }
    Ok(Some(out))
}

/// Writes `bytes` to `dest` through a unique sibling temp file and an atomic rename.
pub fn write_atomic(dest: &Path, bytes: &[u8]) -> io::Result<()> {
    if let Some(p) = dest.parent() {
        fs::create_dir_all(p)?;
    }
    let tmp = mmcl_net::unique_temp_file(dest);
    let res = (|| {
        let mut f = File::create(&tmp)?;
        f.write_all(bytes)?;
        f.sync_all()?;
        drop(f);
        fs::rename(&tmp, dest)
    })();
    if res.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    res
}

/// Extracts `entry` of `jar` to `dest` atomically. `Ok(false)` when the entry is absent.
pub fn extract_entry(jar: &Path, entry: &str, dest: &Path) -> io::Result<bool> {
    match read_entry(jar, entry)? {
        Some(bytes) => write_atomic(dest, &bytes).map(|_| true),
        None => Ok(false),
    }
}

/// `Main-Class` of a jar's manifest. `Ok(None)` when the jar has no manifest or the
/// attribute is missing or blank; errors for an unreadable jar or a malformed manifest.
pub fn read_main_class(jar: &Path) -> io::Result<Option<String>> {
    let Some(bytes) = read_entry(jar, MANIFEST_ENTRY)? else {
        return Ok(None);
    };
    main_class_of(&bytes)
}

/// `Main-Class` from manifest bytes (continuation lines joined, any line ending, names
/// case-insensitive).
pub fn main_class_of(manifest: &[u8]) -> io::Result<Option<String>> {
    let m = Manifest::parse(manifest)?;
    Ok(m.main
        .get("Main-Class")
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty()))
}

/// Builds a jar from `(name, bytes)` entries (deflated). Used for the text2speech stub and
/// by tests.
pub fn build_jar(entries: &[(&str, &[u8])]) -> io::Result<Vec<u8>> {
    let mut w = zip::ZipWriter::new(io::Cursor::new(Vec::new()));
    let opts = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated)
        .last_modified_time(zip::DateTime::default());
    for (name, bytes) in entries {
        w.start_file(*name, opts).map_err(zip_err)?;
        w.write_all(bytes)?;
    }
    Ok(w.finish().map_err(zip_err)?.into_inner())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn main_class_wrapped_and_crlf() {
        let mf = b"Manifest-Version: 1.0\r\nMain-Class: net.minecraftforge.installertools.Cons\r\n oleTool\r\n\r\nName: x\r\nA: b\r\n";
        assert_eq!(
            main_class_of(mf).unwrap().as_deref(),
            Some("net.minecraftforge.installertools.ConsoleTool")
        );
        assert_eq!(
            main_class_of(b"main-class: a.B\n").unwrap().as_deref(),
            Some("a.B")
        );
        assert_eq!(main_class_of(b"Manifest-Version: 1.0\r\r").unwrap(), None);
    }

    #[test]
    fn jar_round_trip() {
        let d = tempfile::tempdir().unwrap();
        let jar = d.path().join("a.jar");
        fs::write(
            &jar,
            build_jar(&[
                (MANIFEST_ENTRY, b"Main-Class: x.Y\n"),
                ("maven/a/b.jar", b"hi"),
            ])
            .unwrap(),
        )
        .unwrap();
        let names = entry_names(&jar).unwrap();
        assert!(names.contains("maven/a/b.jar"));
        assert_eq!(read_main_class(&jar).unwrap().as_deref(), Some("x.Y"));
        let out = d.path().join("o/b.jar");
        assert!(extract_entry(&jar, "maven/a/b.jar", &out).unwrap());
        assert_eq!(fs::read(&out).unwrap(), b"hi");
        assert!(!extract_entry(&jar, "nope", &out).unwrap());
        assert_eq!(
            fs::read_dir(d.path().join("o")).unwrap().count(),
            1,
            "no temp left"
        );
        assert!(entry_names(&d.path().join("missing.jar")).is_err());
        // The inflate cap applies to the real bytes.
        assert_eq!(
            read_entry_capped(&jar, "maven/a/b.jar", 2)
                .unwrap()
                .unwrap(),
            b"hi"
        );
        assert!(read_entry_capped(&jar, "maven/a/b.jar", 1).is_err());
    }
}
