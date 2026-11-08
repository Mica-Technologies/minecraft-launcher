// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

#![allow(dead_code)]

pub mod classwriter;

use std::io::Write;
use std::path::{Path, PathBuf};

use zip::write::SimpleFileOptions;

/// Writes `dir/name` as a jar with the given entries, in order.
pub fn build_jar(dir: &Path, name: &str, entries: &[(&str, &[u8])]) -> PathBuf {
    std::fs::create_dir_all(dir).unwrap();
    let path = dir.join(name);
    let file = std::fs::File::create(&path).unwrap();
    let mut zip = zip::ZipWriter::new(file);
    let opts = SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    for (entry, bytes) in entries {
        zip.start_file(*entry, opts).unwrap();
        zip.write_all(bytes).unwrap();
    }
    zip.finish().unwrap();
    path
}

use classwriter::{ClassWriter, Code};

/// A class whose `trigger()` LDCs `constant` (Java test helper `classWithLdcString`).
pub fn class_with_ldc_string(internal_name: &str, constant: &str) -> Vec<u8> {
    let mut cw = ClassWriter::new(internal_name);
    let s = cw.string(constant);
    cw.method("trigger", "()V", Code::new().ldc(s).op(0x57).ret());
    cw.finish()
}

/// A class with two methods each calling a static `op()V` on the given owners (Java helper
/// `classWithTwoMethodCalls`).
pub fn class_with_two_method_calls(internal_name: &str, owner1: &str, owner2: &str) -> Vec<u8> {
    let mut cw = ClassWriter::new(internal_name);
    let m1 = cw.methodref(owner1, "op", "()V");
    let m2 = cw.methodref(owner2, "op", "()V");
    cw.method("step1", "()V", Code::new().invokestatic(m1).ret());
    cw.method("step2", "()V", Code::new().invokestatic(m2).ret());
    cw.finish()
}
