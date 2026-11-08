// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Mod conflict detection on disk (Java `ModConflictDetector`); the rules are pure and live in
//! `mmcl_manifest::conflicts`.

use std::fs;
use std::path::Path;

pub use mmcl_manifest::conflicts::{Conflict, detect_from_names, is_active_jar};

use crate::sync::DISABLED_SUFFIX;

/// Active jars in `mods_dir` (sorted by name for a stable order).
pub fn active_jar_names(mods_dir: &Path) -> Vec<String> {
    let Ok(rd) = fs::read_dir(mods_dir) else {
        return Vec::new();
    };
    let mut v: Vec<String> = rd
        .flatten()
        .filter(|e| e.file_type().is_ok_and(|t| t.is_file()))
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .filter(|n| is_active_jar(n))
        .collect();
    v.sort();
    v
}

/// The known conflicts among the active jars in `mods_dir`.
pub fn scan(mods_dir: &Path) -> Vec<Conflict> {
    detect_from_names(&active_jar_names(mods_dir))
}

/// Renames `mods/<name>` to `mods/<name>.disabled`. `false` when the jar is missing, the
/// target exists, the name is not a plain file name, or the rename fails.
pub fn disable_jar(mods_dir: &Path, name: &str) -> bool {
    if name.is_empty() || name.contains(['/', '\\']) || name == "." || name == ".." {
        return false;
    }
    let src = mods_dir.join(name);
    let dst = mods_dir.join(format!("{name}{DISABLED_SUFFIX}"));
    if !src.is_file() || dst.exists() {
        return false;
    }
    fs::rename(&src, &dst).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| s.to_string()).collect()
    }

    // ---- ModConflictDetectorTest ----

    #[test]
    fn rules() {
        assert_eq!(
            detect_from_names(&names(&["OptiFine-1.20.jar", "sodium-fabric-0.5.jar"])).len(),
            1
        );
        // `_` is a word character, so `\b` does not split `OptiFine_1.20` (same as Java).
        assert!(detect_from_names(&names(&["OptiFine_1.20.jar", "sodium.jar"])).is_empty());
        assert_eq!(
            detect_from_names(&names(&["optifabric-1.0.jar", "Sodium-1.jar"])).len(),
            1
        );
        assert_eq!(
            detect_from_names(&names(&["jei-1.20.jar", "rei-12.jar"])).len(),
            1
        );
        assert_eq!(
            detect_from_names(&names(&[
                "JustEnoughItems-1.jar",
                "RoughlyEnoughItems-2.jar"
            ]))
            .len(),
            1
        );
        assert!(detect_from_names(&names(&["optifine.jar"])).is_empty());
        assert!(detect_from_names(&names(&["create.jar", "jeiaddon.jar"])).is_empty());
        let both = detect_from_names(&names(&[
            "optifine.jar",
            "sodium.jar",
            "jei.jar",
            "rei.jar",
        ]));
        assert_eq!(both.len(), 2);
        assert_eq!(both[0].first_jar, "optifine.jar");
        assert_eq!(both[1].second_jar, "rei.jar");
    }

    #[test]
    fn scan_and_disable() {
        let d = tempfile::tempdir().unwrap();
        fs::write(d.path().join("optifine.jar"), "x").unwrap();
        fs::write(d.path().join("sodium.jar"), "x").unwrap();
        fs::write(d.path().join("jei.jar.disabled"), "x").unwrap();
        fs::write(d.path().join("rei.jar"), "x").unwrap();
        assert_eq!(scan(d.path()).len(), 1);
        assert!(disable_jar(d.path(), "sodium.jar"));
        assert!(d.path().join("sodium.jar.disabled").is_file());
        assert!(scan(d.path()).is_empty());
        assert!(!disable_jar(d.path(), "sodium.jar"), "missing");
        fs::write(d.path().join("rei.jar.disabled"), "x").unwrap();
        assert!(!disable_jar(d.path(), "rei.jar"), "target exists");
        assert!(!disable_jar(d.path(), "../rei.jar"));
    }
}
