// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Contained extraction of runtime tarballs and zips (Java `ArchiveExtractor`).
//!
//! The target folder is the containment root. Permissions and timestamps are not kept; the
//! runtime subsystem restores `+x` on `bin/*` itself.

use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Read, Seek, Write};
use std::path::{Path, PathBuf};

use mmcl_i18n::tf;
use tar::EntryType;
use zip::result::ZipError;

use crate::error::{ArchiveKind, ExtractError};
use crate::guard::{self, Limits, Special};

/// Unix file-type mask and the symlink type, as stored in a zip entry's external attributes.
const S_IFMT: u32 = 0o170000;
const S_IFLNK: u32 = 0o120000;

/// Extracts the gzipped tarball `archive` into `target` with the runtime caps
/// ([`Limits::RUNTIME_ARCHIVE`]).
pub fn extract_tar_gz(archive: &Path, target: &Path) -> Result<(), ExtractError> {
    extract_tar_gz_with(archive, target, Limits::RUNTIME_ARCHIVE)
}

/// [`extract_tar_gz`] with explicit size caps.
pub fn extract_tar_gz_with(
    archive: &Path,
    target: &Path,
    limits: Limits,
) -> Result<(), ExtractError> {
    let file = File::open(archive)?;
    let gz = flate2::read::GzDecoder::new(BufReader::new(file));
    extract_tar_stream(gz, target, limits)
}

/// Extracts an uncompressed tar stream into `target`. The checks are the ones every format
/// shares; `Entry::unpack_in` is deliberately not used, so those checks are the only way a
/// byte reaches the disk.
pub fn extract_tar_stream<R: Read>(
    reader: R,
    target: &Path,
    limits: Limits,
) -> Result<(), ExtractError> {
    let base = prepare_base(target)?;
    let mut tar = tar::Archive::new(reader);
    let mut total = 0u64;
    for entry in tar.entries()? {
        let mut entry = entry?;
        let header_type = entry.header().entry_type();
        // Metadata records (PAX headers, GNU long names) describe the next entry; the tar
        // crate folds most of them in already. They are never files of their own.
        if matches!(
            header_type,
            EntryType::XGlobalHeader
                | EntryType::XHeader
                | EntryType::GNULongName
                | EntryType::GNULongLink
        ) {
            continue;
        }
        let name = String::from_utf8_lossy(&entry.path_bytes()).into_owned();
        let special = match header_type {
            EntryType::Symlink => Some(Special::Tar("symlink")),
            EntryType::Link => Some(Special::Tar("hardlink")),
            EntryType::Char => Some(Special::Tar("chardev")),
            EntryType::Block => Some(Special::Tar("blockdev")),
            EntryType::Fifo => Some(Special::Tar("fifo")),
            _ => None,
        };
        let Some(dest) = guard::validate_and_resolve(&name, &base, special, ArchiveKind::Archive)?
        else {
            continue;
        };
        if header_type == EntryType::Directory {
            fs::create_dir_all(&dest)?;
            continue;
        }
        copy_entry(&mut entry, &dest, &name, limits, &mut total)?;
    }
    Ok(())
}

/// Extracts the zip `archive` into `target` with the runtime caps ([`Limits::RUNTIME_ARCHIVE`]).
pub fn extract_zip(archive: &Path, target: &Path) -> Result<(), ExtractError> {
    extract_zip_with(archive, target, Limits::RUNTIME_ARCHIVE)
}

/// [`extract_zip`] with explicit size caps.
pub fn extract_zip_with(archive: &Path, target: &Path, limits: Limits) -> Result<(), ExtractError> {
    let file = File::open(archive)?;
    extract_zip_reader(BufReader::new(file), target, limits)
}

/// Extracts a zip read from `reader` into `target`.
///
/// The entry metadata comes from the central directory, which is what makes the symlink
/// check meaningful: a streaming reader only sees local headers, where the Unix mode bits are
/// absent, so its symlink check would silently do nothing. Entries are visited in physical
/// order so the file is still read roughly front to back.
pub fn extract_zip_reader<R: Read + Seek>(
    reader: R,
    target: &Path,
    limits: Limits,
) -> Result<(), ExtractError> {
    let base = prepare_base(target)?;
    let mut zip = zip::ZipArchive::new(reader)?;
    let mut order = Vec::with_capacity(zip.len());
    for index in 0..zip.len() {
        order.push((zip.by_index_raw(index)?.header_start(), index));
    }
    order.sort_unstable();

    let mut total = 0u64;
    for (_, index) in order {
        let (name, is_dir, mode) = {
            let raw = zip.by_index_raw(index)?;
            (raw.name().to_owned(), raw.is_dir(), raw.unix_mode())
        };
        let special = mode
            .filter(|m| m & S_IFMT == S_IFLNK)
            .map(|_| Special::ZipSymlink);
        let Some(dest) = guard::validate_and_resolve(&name, &base, special, ArchiveKind::Archive)?
        else {
            continue;
        };
        if is_dir {
            fs::create_dir_all(&dest)?;
            continue;
        }
        // An entry using a compression method this build cannot read is skipped: an empty
        // file in its place would be worse, and failing the whole extraction over one odd
        // entry worse still.
        let mut entry = match zip.by_index(index) {
            Ok(entry) => entry,
            Err(ZipError::UnsupportedArchive(_)) => {
                tracing::warn!(
                    "{}",
                    tf!("log.archiveExtractor.skippingUnreadableZipEntry", &name)
                );
                continue;
            }
            Err(e) => return Err(e.into()),
        };
        copy_entry(&mut entry, &dest, &name, limits, &mut total)?;
    }
    Ok(())
}

/// Creates the containment root and returns its absolute, normalised path.
fn prepare_base(target: &Path) -> Result<PathBuf, ExtractError> {
    let base = guard::normalize_base(target)?;
    fs::create_dir_all(&base)?;
    Ok(base)
}

/// Writes one entry to `dest` under the caps, creating its parent folders.
fn copy_entry(
    input: &mut dyn Read,
    dest: &Path,
    name: &str,
    limits: Limits,
    total: &mut u64,
) -> Result<(), ExtractError> {
    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut out = BufWriter::new(File::create(dest)?);
    guard::copy_capped(input, &mut out, name, limits, total, ArchiveKind::Archive)?;
    out.flush()?;
    Ok(())
}
