// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The hosted modpack manifest (`.mmcjson`) as pure data.
//!
//! * [`PackManifest`] is the runtime view of a manifest, parsed with the same leniency Gson
//!   gives the Java launcher: type coercion on primitives, last duplicate key wins, `null`
//!   means absent, `null` list elements are dropped, and a `json5` fallback for manifests
//!   written with comments or single quotes (reported through [`Syntax::Lenient`]).
//! * [`document::ModPackDocument`] is the editor model: a raw, order-preserving JSON object
//!   so unknown keys survive an edit, printed exactly the way Gson pretty-prints so editor
//!   diffs stay minimal ([`gson::to_pretty_string`]).
//! * Small pure helpers the pack runtime and the UI share: news and links, scan
//!   acknowledgements, server addresses, play-time labels, version comparison, mod conflict
//!   rules and the pending-update diff.
//!
//! The crate does no network or async work. The only file I/O is
//! [`document::ModPackDocument::write_local_manifest`].

pub mod conflicts;
pub mod diff;
pub mod document;
pub mod error;
pub mod files;
pub mod gson;
pub mod lenient;
pub mod news;
pub mod pack;
pub mod play_time;
pub mod scan;
pub mod server;
pub mod string_or_array;
pub mod url_validation;
pub mod version;

pub use error::ManifestParseError;
pub use files::{HashAlgo, ManifestFile, Side, usable_hash};
pub use gson::Syntax;
pub use news::{LinkItem, NewsItem, NewsKind};
pub use pack::{ModLoaderKind, PackManifest, ParsedManifest};
pub use scan::{FindingKey, ScanAcknowledgement};
pub use server::ServerFavorite;
pub use string_or_array::StringOrArray;

/// Localization key for the warning a caller logs when a manifest only parsed through the
/// lenient (`json5`) fallback. Argument `{0}` is the manifest URL or path.
pub const LOG_KEY_LENIENT_SYNTAX: &str = "log.manifest.lenientSyntax";

/// Every character outside ASCII `[A-Za-z0-9]` removed, as Java's `replaceAll("[^a-zA-Z0-9]", "")`.
pub(crate) fn ascii_alnum_only(s: &str) -> String {
    s.chars().filter(|c| c.is_ascii_alphanumeric()).collect()
}

/// Case-insensitive equality in the spirit of Java's `equalsIgnoreCase`.
pub(crate) fn eq_ignore_case(a: &str, b: &str) -> bool {
    a == b || a.to_lowercase() == b.to_lowercase()
}

/// `Some(trimmed)` when the value has non-whitespace content.
pub(crate) fn non_blank(s: Option<&str>) -> Option<&str> {
    s.map(str::trim).filter(|t| !t.is_empty())
}
