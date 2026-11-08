// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `cargo xtask checksums <dir>`: the release body's "## Checksums (SHA-256)" block, in
//! `sha256sum` format, over every installer and archive in `<dir>` (sorted by name).

use std::io::Read;
use std::path::Path;

use anyhow::{Context, Result};
use sha2::{Digest, Sha256};

/// File names the release attaches.
pub const RELEASE_SUFFIXES: &[&str] = &[
    ".msi",
    ".exe",
    ".dmg",
    ".pkg",
    ".deb",
    ".rpm",
    ".zip",
    ".tar.gz",
    ".AppImage",
];

pub fn is_release_file(name: &str) -> bool {
    RELEASE_SUFFIXES.iter().any(|s| name.ends_with(s))
}

pub fn sha256_file(path: &Path) -> Result<String> {
    let mut file =
        std::fs::File::open(path).with_context(|| format!("cannot open {}", path.display()))?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 1 << 16];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hex::encode(hasher.finalize()))
}

/// `(sha256, name)` for each release file directly inside `dir`, sorted by name.
pub fn collect(dir: &Path) -> Result<Vec<(String, String)>> {
    let mut out = Vec::new();
    for entry in std::fs::read_dir(dir).with_context(|| format!("cannot list {}", dir.display()))? {
        let entry = entry?;
        if !entry.file_type()?.is_file() {
            continue;
        }
        let name = entry.file_name().to_string_lossy().into_owned();
        if is_release_file(&name) {
            out.push((sha256_file(&entry.path())?, name));
        }
    }
    out.sort_by(|a, b| a.1.cmp(&b.1));
    Ok(out)
}

/// The Markdown block for the release body.
pub fn render(sums: &[(String, String)]) -> String {
    let mut s = String::from("## Checksums (SHA-256)\n\n```\n");
    for (hash, name) in sums {
        s.push_str(&format!("{hash}  {name}\n"));
    }
    s.push_str("```\n");
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn renders_sorted_known_files_only() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("b.deb"), b"").unwrap();
        std::fs::write(dir.path().join("a App Setup.exe"), b"abc").unwrap();
        std::fs::write(dir.path().join("notes.txt"), b"x").unwrap();
        std::fs::write(dir.path().join("x.tar.gz"), b"x").unwrap();
        let sums = collect(dir.path()).unwrap();
        let names: Vec<_> = sums.iter().map(|s| s.1.as_str()).collect();
        assert_eq!(names, vec!["a App Setup.exe", "b.deb", "x.tar.gz"]);
        assert_eq!(
            sums[0].0,
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        let md = render(&sums);
        assert!(md.starts_with("## Checksums (SHA-256)\n\n```\n"));
        assert!(
            md.contains(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  b.deb\n"
            )
        );
        assert!(md.ends_with("```\n"));
    }
}
