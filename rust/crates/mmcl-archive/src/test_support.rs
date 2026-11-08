// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Fixture writers for the tests.

use std::fs::File;
use std::io::Write;
use std::path::Path;

use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, DateTime, ZipWriter};

/// One zip fixture entry.
pub enum ZipItem<'a> {
    File(&'a str, &'a [u8]),
    Symlink(&'a str, &'a str),
    Dir(&'a str),
}

fn options() -> SimpleFileOptions {
    SimpleFileOptions::default()
        .compression_method(CompressionMethod::Deflated)
        .last_modified_time(DateTime::from_date_and_time(2020, 1, 2, 3, 4, 6).unwrap())
}

/// Writes a zip of plain files.
pub fn write_zip(path: &Path, files: &[(&str, &[u8])]) {
    let items: Vec<ZipItem<'_>> = files.iter().map(|(n, b)| ZipItem::File(n, b)).collect();
    write_zip_items(path, &items);
}

/// Writes a zip with files, symlinks and directories.
pub fn write_zip_items(path: &Path, items: &[ZipItem<'_>]) {
    let mut zip = ZipWriter::new(File::create(path).unwrap());
    for item in items {
        match item {
            ZipItem::File(name, bytes) => {
                zip.start_file(*name, options()).unwrap();
                zip.write_all(bytes).unwrap();
            }
            ZipItem::Symlink(name, target) => zip.add_symlink(*name, *target, options()).unwrap(),
            ZipItem::Dir(name) => zip.add_directory(*name, options()).unwrap(),
        }
    }
    zip.finish().unwrap();
}

/// One tar fixture entry.
pub enum TarItem<'a> {
    File(&'a str, &'a [u8]),
    Symlink(&'a str, &'a str),
    Hardlink(&'a str, &'a str),
    Dir(&'a str),
}

/// Writes a gzipped tarball. Names are written raw into the header, so `..` and absolute
/// names reach the extractor unchanged.
pub fn write_tar_gz(path: &Path, items: &[TarItem<'_>]) {
    let gz =
        flate2::write::GzEncoder::new(File::create(path).unwrap(), flate2::Compression::fast());
    let mut tar = tar::Builder::new(gz);
    for item in items {
        let mut header = tar::Header::new_gnu();
        header.set_mode(0o644);
        let (name, data): (&str, &[u8]) = match item {
            TarItem::File(name, bytes) => {
                header.set_entry_type(tar::EntryType::Regular);
                header.set_size(bytes.len() as u64);
                (name, bytes)
            }
            TarItem::Symlink(name, target) | TarItem::Hardlink(name, target) => {
                header.set_entry_type(if matches!(item, TarItem::Symlink(..)) {
                    tar::EntryType::Symlink
                } else {
                    tar::EntryType::Link
                });
                header.set_size(0);
                header.set_link_name(target).unwrap();
                (name, &[])
            }
            TarItem::Dir(name) => {
                header.set_entry_type(tar::EntryType::Directory);
                header.set_size(0);
                (name, &[])
            }
        };
        // Bypass `set_path`'s own validation so hostile names can be written.
        let raw = &mut header.as_old_mut().name;
        raw.fill(0);
        raw[..name.len()].copy_from_slice(name.as_bytes());
        header.set_cksum();
        tar.append(&header, data).unwrap();
    }
    tar.into_inner().unwrap().finish().unwrap();
}
