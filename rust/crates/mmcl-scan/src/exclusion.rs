// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Policy gate for manifest-supplied scan exclusions (`packScanExclusions`), port of Java's
//! `ScanExclusionPolicy`.
//!
//! The exclusion list comes straight from the (attacker-controllable) modpack JSON, and both scan
//! passes skip matching subtrees. Without this gate a pack could declare `["mods"]` or `["."]`
//! and exempt exactly the folders its malware lives in. An exclusion is refused when it covers
//! the pack root, contains a `..` segment, or starts with a protected content folder. Both
//! scanners re-apply [`filter_untrusted`] internally, so a caller cannot bypass it.

use mmcl_i18n::{t, tf};

/// Pack-relative folders whose contents arrive unverified from the pack and are loaded as code
/// (or code-adjacent scripts / data) by the game.
pub const PROTECTED_ROOTS: [&str; 7] = [
    "mods",
    "config",
    "scripts",
    "resourcepacks",
    "shaderpacks",
    "kubejs",
    "defaultconfigs",
];

/// Why `exclusion` must be refused (localized), or `None` when it is safe to honor.
pub fn rejection_reason(exclusion: &str) -> Option<String> {
    let normalized = exclusion.trim().replace('\\', "/").to_lowercase();
    let segments: Vec<&str> = normalized
        .split('/')
        .filter(|s| !s.is_empty() && *s != ".")
        .collect();
    let Some(first) = segments.first() else {
        return Some(t!("log.scanExclusion.reasonPackRoot").into_owned());
    };
    if segments.contains(&"..") {
        return Some(t!("log.scanExclusion.reasonTraversal").into_owned());
    }
    if PROTECTED_ROOTS.contains(first) {
        return Some(tf!("log.scanExclusion.reasonProtectedFolder", *first));
    }
    None
}

/// The entries of an untrusted exclusion list the launcher will honor, in their original form.
/// Each refused entry is logged (silent warning) with its reason.
pub fn filter_untrusted<S: AsRef<str>>(untrusted: &[S]) -> Vec<String> {
    let mut allowed = Vec::with_capacity(untrusted.len());
    for entry in untrusted {
        let entry = entry.as_ref();
        match rejection_reason(entry) {
            Some(reason) => {
                mmcl_log::warn_silent(&tf!("log.scanExclusion.ignored", entry, reason));
            }
            None => allowed.push(entry.to_owned()),
        }
    }
    allowed
}

/// [`filter_untrusted`] for an optional list (Java's `null`), which gives an empty list.
pub fn filter_untrusted_opt<S: AsRef<str>>(untrusted: Option<&[S]>) -> Vec<String> {
    untrusted.map(filter_untrusted).unwrap_or_default()
}
