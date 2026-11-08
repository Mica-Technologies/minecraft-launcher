// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `cargo +nightly fuzz run classfile_parse` (from `crates/mmcl-scan`): the class reader and both
//! scanners' class paths must never panic on arbitrary bytes.

#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let _ = mmcl_scan::classfile::parse(data);
    let _ = mmcl_scan::neko::scan_class(data);
    if let Ok(s) = std::str::from_utf8(data) {
        let _ = mmcl_scan::supplemental::inspect_string(s);
    }
});
