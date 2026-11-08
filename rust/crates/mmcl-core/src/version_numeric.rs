// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.
//
// Shared between the library (`build::numeric`) and `build.rs` (through `include!`), so the
// numeric version stamped at build time and the one computed at run time can never disagree.
// Keep this file free of `use` statements and crate paths.

/// The numeric part of a version string: cut at the first `-`, then at the first `+`; an empty
/// result becomes `0.0.0`. `2026.5.12-15-gabc1234.dirty` gives `2026.5.12`, and
/// `2026.05.12-pre.1432.UTC+0000+abc1234` gives `2026.05.12`.
pub fn version_numeric(version: &str) -> String {
    let cut = version.split('-').next().unwrap_or("");
    let cut = cut.split('+').next().unwrap_or("");
    let cut = cut.trim();
    if cut.is_empty() {
        "0.0.0".to_string()
    } else {
        cut.to_string()
    }
}
