// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Natives jar extraction (Java `SystemUtilities.extractJarFile`).
//!
//! Natives (`lwjgl64.dll` and friends) are re-extracted over the previous copies on every
//! launch. A partial write from an interrupted launch, antivirus interference or a concurrent
//! re-extract once left a truncated DLL on disk, and loading it failed with "DLL
//! initialization routine failed" / 0xC0000005. So every entry is written to a sibling temp
//! file, synced, then renamed over the target: the target holds either the previous good file
//! or the new complete one, never a half-written one.

use std::fs::{self, File};
use std::io::{self, BufReader, Read, Seek, Write};
use std::path::Path;

use crate::error::{ArchiveKind, ExtractError};
use crate::guard::{self, Limits};

/// Extracts the jar at `jar` into `dest` with the natives caps ([`Limits::NATIVES_JAR`]).
///
/// Every entry whose name contains `META-INF` is skipped (signatures do not survive the
/// re-pack). A NUL byte, an absolute path or an escape from `dest` is an error; a Windows
/// reserved name is skipped and logged. Directories are created.
pub fn extract_jar(jar: &Path, dest: &Path) -> Result<(), ExtractError> {
    extract_jar_with(jar, dest, Limits::NATIVES_JAR)
}

/// [`extract_jar`] with explicit size caps.
pub fn extract_jar_with(jar: &Path, dest: &Path, limits: Limits) -> Result<(), ExtractError> {
    let file = File::open(jar)?;
    extract_jar_reader(BufReader::new(file), dest, limits)
}

/// Extracts a jar read from `reader` into `dest`.
pub fn extract_jar_reader<R: Read + Seek>(
    reader: R,
    dest: &Path,
    limits: Limits,
) -> Result<(), ExtractError> {
    let base = guard::normalize_base(dest)?;
    let mut zip = zip::ZipArchive::new(reader)?;
    let mut total = 0u64;
    for index in 0..zip.len() {
        let (name, is_dir) = {
            let raw = zip.by_index_raw(index)?;
            (raw.name().to_owned(), raw.is_dir())
        };
        if name.contains("META-INF") {
            continue;
        }
        let Some(target) = guard::validate_and_resolve(&name, &base, None, ArchiveKind::Jar)?
        else {
            continue;
        };
        if is_dir {
            // Java ignores a failed mkdirs here; a later file in it fails loudly instead.
            let _ = fs::create_dir_all(&target);
            continue;
        }
        if let Some(parent) = target.parent() {
            let _ = fs::create_dir_all(parent);
        }

        let file_name = target
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default();
        let temp = target.with_file_name(format!("{file_name}.tmp-{}", uuid::Uuid::new_v4()));
        let written = (|| -> Result<(), ExtractError> {
            let mut entry = zip
                .by_index(index)
                .map_err(|e| ExtractError::JarRead(io::Error::other(e)))?;
            let mut out = File::create(&temp).map_err(ExtractError::JarRead)?;
            guard::copy_capped(
                &mut entry,
                &mut out,
                &name,
                limits,
                &mut total,
                ArchiveKind::Jar,
            )
            .map_err(|e| match e {
                ExtractError::Io(io) => ExtractError::JarRead(io),
                other => other,
            })?;
            out.flush().map_err(ExtractError::JarRead)?;
            // Flush to disk before the rename, so a power loss between the rename and the
            // next launch cannot reveal an empty file.
            out.sync_all().map_err(ExtractError::JarRead)?;
            Ok(())
        })();
        if let Err(e) = written {
            let _ = fs::remove_file(&temp);
            return Err(e);
        }
        publish(&temp, &target).map_err(|source| {
            let _ = fs::remove_file(&temp);
            ExtractError::Publish {
                name: name.clone(),
                source,
            }
        })?;
    }
    Ok(())
}

/// Moves `temp` over `target`. The temp file is a sibling, so the rename stays on one volume
/// and replaces atomically. If the rename itself is refused, fall back to a plain copy over
/// the target (no worse than writing it directly) and remove the temp file.
fn publish(temp: &Path, target: &Path) -> io::Result<()> {
    match fs::rename(temp, target) {
        Ok(()) => Ok(()),
        Err(rename_err) => {
            if fs::copy(temp, target).is_err() {
                return Err(rename_err);
            }
            let _ = fs::remove_file(temp);
            Ok(())
        }
    }
}
