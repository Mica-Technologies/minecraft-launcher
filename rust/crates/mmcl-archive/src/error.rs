// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Extraction errors. The messages stay English on purpose, as Java's exception messages do;
//! a screen that shows one maps it to a localization key itself.

use std::fmt;
use std::io;

/// Which extractor produced an error; only changes the wording of the message.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ArchiveKind {
    /// A runtime tarball or zip ([`crate::extract_tar_gz`], [`crate::extract_zip`]).
    Archive,
    /// A natives jar ([`crate::extract_jar`]).
    Jar,
}

impl fmt::Display for ArchiveKind {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            ArchiveKind::Archive => "archive",
            ArchiveKind::Jar => "JAR",
        })
    }
}

/// Why an extraction stopped. Every variant aborts the whole extraction; entries that are
/// merely skipped (links, devices, reserved names, unreadable compression) are logged instead.
#[derive(Debug, thiserror::Error)]
pub enum ExtractError {
    /// An entry name contains a NUL byte.
    #[error("Refusing {kind} entry with NUL byte: {name}")]
    NulByte { kind: ArchiveKind, name: String },
    /// An entry name is an absolute path.
    #[error("Refusing absolute {kind} entry path: {name}")]
    AbsolutePath { kind: ArchiveKind, name: String },
    /// An entry resolves outside the destination folder.
    #[error("Refusing {kind} entry that escapes base dir: {name}")]
    Escapes { kind: ArchiveKind, name: String },
    /// One entry decompressed to more than the per-entry cap.
    #[error("{kind} entry exceeds per-entry size cap ({cap} bytes): {name}")]
    EntryTooLarge {
        kind: ArchiveKind,
        name: String,
        cap: u64,
    },
    /// The archive decompressed to more than the total cap.
    #[error("{kind} extraction exceeded total size cap ({cap} bytes) at entry: {name}")]
    TotalTooLarge {
        kind: ArchiveKind,
        name: String,
        cap: u64,
    },
    /// Reading a natives jar entry, or writing its temp file, failed.
    #[error("Unable to read file from jar during extraction.")]
    JarRead(#[source] io::Error),
    /// Moving an extracted natives jar entry into place failed.
    #[error("Unable to publish extracted JAR entry: {name}")]
    Publish {
        name: String,
        #[source]
        source: io::Error,
    },
    /// The archive could not be opened or its directory read.
    #[error("Unable to read archive: {0}")]
    Zip(#[from] zip::result::ZipError),
    /// Any other I/O failure (opening the archive, creating folders, writing a file).
    #[error(transparent)]
    Io(#[from] io::Error),
}

impl From<ExtractError> for io::Error {
    fn from(e: ExtractError) -> io::Error {
        match e {
            ExtractError::Io(io) => io,
            other => io::Error::other(other),
        }
    }
}
