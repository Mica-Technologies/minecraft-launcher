// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Archives and images for the Mica Minecraft Launcher.
//!
//! - [`extract_tar_gz`] and [`extract_zip`]: contained extraction of Java runtime archives
//!   (Java `ArchiveExtractor`).
//! - [`extract_jar`]: natives extraction with temp-file, fsync and atomic replace (Java
//!   `SystemUtilities.extractJarFile`).
//! - [`jarsign`]: signature stripping for pre-1.6 client jars (Java `JarSigningStripper`), with
//!   a Java-compatible [`manifest`] reader and writer.
//! - [`image`]: transcode to PNG (Java `ImageFormatUtilities`) and the ICO/ICNS writers for
//!   desktop shortcuts.
//!
//! Every extractor refuses NUL bytes, absolute paths and entries that escape the target folder;
//! skips symlinks, hardlinks, devices, FIFOs and Windows reserved device names on every OS; and
//! caps decompressed bytes per entry and in total by counting what is actually read.

mod error;
mod extract;
pub mod guard;
pub mod image;
pub mod jarsign;
pub mod manifest;
mod natives;

#[cfg(test)]
mod extract_tests;
#[cfg(test)]
mod natives_tests;
#[cfg(test)]
pub(crate) mod test_support;

pub use error::{ArchiveKind, ExtractError};
pub use extract::{
    extract_tar_gz, extract_tar_gz_with, extract_tar_stream, extract_zip, extract_zip_reader,
    extract_zip_with,
};
pub use guard::{Limits, WINDOWS_RESERVED_NAMES};
pub use natives::{extract_jar, extract_jar_reader, extract_jar_with};
