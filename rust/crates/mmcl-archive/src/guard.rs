// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The containment checks every extraction path shares.
//!
//! Tar, zip and natives-jar extraction all go through [`validate_and_resolve`] and
//! [`copy_capped`], so the three paths cannot drift apart on what they refuse. (The Java zip
//! path once had a weaker symlink check than the tar path purely by accident of how it read
//! the archive.)

use std::io::{self, Read, Write};
use std::path::{Component, Path, PathBuf};

use mmcl_i18n::tf;

use crate::error::{ArchiveKind, ExtractError};

/// Windows reserved device names. Skipped on every OS so behaviour is portable. Compared
/// case-insensitively against the leaf's stem (the text before its last `.`).
pub const WINDOWS_RESERVED_NAMES: [&str; 22] = [
    "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8",
    "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
];

/// Copy buffer size, as in Java.
pub(crate) const BUFFER_SIZE: usize = 8192;

/// Decompressed-size ceilings for one extraction. Bytes are counted as they are read; the
/// size an entry declares is attacker-controlled and is never trusted.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Limits {
    /// Most bytes a single entry may produce.
    pub per_entry: u64,
    /// Most bytes the whole archive may produce.
    pub total: u64,
}

impl Limits {
    /// Runtime tarballs and zips: 512 MiB per entry, 2 GiB in total. A JRE ships single files
    /// up to about 200 MB; a full JRE is 80-120 MB unpacked.
    pub const RUNTIME_ARCHIVE: Limits = Limits {
        per_entry: 512 * 1024 * 1024,
        total: 2 * 1024 * 1024 * 1024,
    };

    /// Natives jars: 64 MiB per entry, 256 MiB in total.
    pub const NATIVES_JAR: Limits = Limits {
        per_entry: 64 * 1024 * 1024,
        total: 256 * 1024 * 1024,
    };
}

/// Why an entry that passed the name checks is still not materialised.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Special {
    /// A tar symlink, hardlink, device or FIFO; the text is the type name for the log line.
    Tar(&'static str),
    /// A zip entry whose Unix mode marks it as a symlink.
    ZipSymlink,
}

/// `true` when `name` contains a NUL byte.
pub fn has_nul(name: &str) -> bool {
    name.contains('\0')
}

/// `true` for an absolute entry name: one starting with `/` or `\`, or with `:` as its second
/// character (a drive letter).
pub fn is_absolute_name(name: &str) -> bool {
    name.starts_with('/') || name.starts_with('\\') || name.as_bytes().get(1) == Some(&b':')
}

/// `true` when the stem of `leaf` (the text before its last `.`, lowercased) is a Windows
/// reserved device name.
pub fn is_reserved_leaf(leaf: &str) -> bool {
    let stem = match leaf.rfind('.') {
        Some(dot) => &leaf[..dot],
        None => leaf,
    };
    let stem = stem.to_ascii_lowercase();
    WINDOWS_RESERVED_NAMES.contains(&stem.as_str())
}

/// The absolute, lexically normalised form of `dir`, without touching the file system.
pub fn normalize_base(dir: &Path) -> io::Result<PathBuf> {
    let abs = std::path::absolute(dir)?;
    Ok(normalize_lexically(&abs))
}

/// Collapses `.` and `..` components without resolving symlinks (Java's `Path.normalize`).
/// A `..` never climbs above the root or prefix.
pub fn normalize_lexically(path: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for comp in path.components() {
        match comp {
            Component::CurDir => {}
            Component::ParentDir => {
                if !matches!(
                    out.components().next_back(),
                    None | Some(Component::RootDir) | Some(Component::Prefix(_))
                ) {
                    out.pop();
                }
            }
            other => out.push(other.as_os_str()),
        }
    }
    out
}

/// `base` joined with the archive entry `name`, normalised lexically. Both `/` and `\` are
/// separators on every OS, so a Windows-style name cannot sneak a `..\` past a Unix check.
/// Returns `None` when a segment is not a plain name on this OS (a `:` on Windows, which would
/// otherwise name a drive or an alternate data stream).
pub fn lexical_join(base: &Path, name: &str) -> Option<PathBuf> {
    let mut out = base.to_path_buf();
    for seg in name.split(['/', '\\']) {
        match seg {
            "" | "." => {}
            ".." => {
                if !matches!(
                    out.components().next_back(),
                    None | Some(Component::RootDir) | Some(Component::Prefix(_))
                ) {
                    out.pop();
                }
            }
            s => {
                if cfg!(windows) && s.contains(':') {
                    return None;
                }
                out.push(s);
            }
        }
    }
    Some(out)
}

/// Applies every containment and safety check to one entry and resolves its destination.
///
/// Order, as in Java: an empty name is skipped; a NUL byte or an absolute path is an error;
/// a `special` entry (link, device, FIFO) is skipped and logged; a target outside `base` is
/// an error; a reserved leaf is skipped and logged. `Ok(None)` means skip the entry.
pub(crate) fn validate_and_resolve(
    name: &str,
    base: &Path,
    special: Option<Special>,
    kind: ArchiveKind,
) -> Result<Option<PathBuf>, ExtractError> {
    if name.is_empty() {
        return Ok(None);
    }
    if has_nul(name) {
        return Err(ExtractError::NulByte {
            kind,
            name: name.to_owned(),
        });
    }
    if is_absolute_name(name) {
        return Err(ExtractError::AbsolutePath {
            kind,
            name: name.to_owned(),
        });
    }
    match special {
        Some(Special::Tar(type_name)) => {
            tracing::warn!(
                "{}",
                tf!(
                    "log.archiveExtractor.skippingNonRegularTar",
                    type_name,
                    name
                )
            );
            return Ok(None);
        }
        Some(Special::ZipSymlink) => {
            tracing::warn!("{}", tf!("log.archiveExtractor.skippingZipSymlink", name));
            return Ok(None);
        }
        None => {}
    }
    let target = match lexical_join(base, name) {
        Some(t) if t.starts_with(base) => t,
        _ => {
            return Err(ExtractError::Escapes {
                kind,
                name: name.to_owned(),
            });
        }
    };
    if let Some(leaf) = target.file_name().and_then(|l| l.to_str())
        && is_reserved_leaf(leaf)
    {
        let key = match kind {
            ArchiveKind::Archive => "log.archiveExtractor.skippingReservedName",
            ArchiveKind::Jar => "log.systemUtil.skippingReservedNameEntry",
        };
        tracing::warn!("{}", tf!(key, name));
        return Ok(None);
    }
    Ok(Some(target))
}

/// Streams `input` into `output` with an 8 KiB buffer under the per-entry and cumulative caps.
/// `total` is the running byte count for the whole extraction and is updated in place. I/O
/// failures come back as [`ExtractError::Io`]; cap breaches as their own variants.
pub(crate) fn copy_capped(
    input: &mut dyn Read,
    output: &mut dyn Write,
    name: &str,
    limits: Limits,
    total: &mut u64,
    kind: ArchiveKind,
) -> Result<(), ExtractError> {
    let mut buffer = [0u8; BUFFER_SIZE];
    let mut entry_bytes = 0u64;
    loop {
        let read = match input.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => n,
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(ExtractError::Io(e)),
        };
        entry_bytes += read as u64;
        if entry_bytes > limits.per_entry {
            return Err(ExtractError::EntryTooLarge {
                kind,
                name: name.to_owned(),
                cap: limits.per_entry,
            });
        }
        *total += read as u64;
        if *total > limits.total {
            return Err(ExtractError::TotalTooLarge {
                kind,
                name: name.to_owned(),
                cap: limits.total,
            });
        }
        output
            .write_all(&buffer[..read])
            .map_err(ExtractError::Io)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn absolute_names() {
        assert!(is_absolute_name("/etc/passwd"));
        assert!(is_absolute_name("\\windows"));
        assert!(is_absolute_name("C:/x"));
        assert!(is_absolute_name("c:x"));
        assert!(!is_absolute_name("bin/java"));
        assert!(!is_absolute_name("a"));
        assert!(!is_absolute_name(""));
    }

    #[test]
    fn reserved_leaf_is_case_insensitive_and_extension_stripped() {
        assert!(is_reserved_leaf("con"));
        assert!(is_reserved_leaf("CON.txt"));
        assert!(is_reserved_leaf("COM1.dat"));
        assert!(is_reserved_leaf("lpt9"));
        assert!(!is_reserved_leaf("com10"));
        assert!(!is_reserved_leaf("console.txt"));
        // Only the last extension is stripped, as in Java.
        assert!(!is_reserved_leaf("con.tar.gz"));
    }

    #[test]
    fn lexical_join_contains_and_detects_escapes() {
        let base = normalize_base(Path::new("base-dir")).unwrap();
        let ok = lexical_join(&base, "a/./b/../c.txt").unwrap();
        assert_eq!(ok, base.join("a").join("c.txt"));
        assert!(
            !lexical_join(&base, "../../evil.txt")
                .unwrap()
                .starts_with(&base)
        );
        assert!(
            !lexical_join(&base, "..\\..\\evil.txt")
                .unwrap()
                .starts_with(&base)
        );
        assert!(
            !lexical_join(&base, "a/../../evil.txt")
                .unwrap()
                .starts_with(&base)
        );
        // Climbing out and back into the same folder stays contained, as Java allows.
        let back = format!("../{}/x", base.file_name().unwrap().to_str().unwrap());
        assert_eq!(lexical_join(&base, &back).unwrap(), base.join("x"));
    }

    #[cfg(windows)]
    #[test]
    fn colon_segments_are_refused_on_windows() {
        let base = normalize_base(Path::new("base-dir")).unwrap();
        assert!(lexical_join(&base, "a/C:/x").is_none());
        assert!(lexical_join(&base, "file.txt:stream").is_none());
    }

    #[test]
    fn normalize_lexically_never_climbs_above_root() {
        let root = normalize_base(Path::new("/")).unwrap();
        assert_eq!(normalize_lexically(&root.join("..").join("..")), root);
    }

    #[test]
    fn copy_capped_enforces_both_caps_by_bytes_read() {
        let limits = Limits {
            per_entry: 10,
            total: 15,
        };
        let mut total = 0;
        let mut out = Vec::new();
        copy_capped(
            &mut &[1u8; 10][..],
            &mut out,
            "a",
            limits,
            &mut total,
            ArchiveKind::Archive,
        )
        .unwrap();
        assert_eq!(total, 10);
        let err = copy_capped(
            &mut &[1u8; 11][..],
            &mut out,
            "b",
            limits,
            &mut 0,
            ArchiveKind::Archive,
        )
        .unwrap_err();
        assert!(matches!(err, ExtractError::EntryTooLarge { .. }));
        let err = copy_capped(
            &mut &[1u8; 6][..],
            &mut out,
            "c",
            limits,
            &mut total,
            ArchiveKind::Archive,
        )
        .unwrap_err();
        assert!(matches!(err, ExtractError::TotalTooLarge { .. }));
    }
}
