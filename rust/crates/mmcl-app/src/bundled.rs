// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The bundled default pack images (Java `ModPackConstants.MODPACK_DEFAULT_LOGO_URL` /
//! `MODPACK_DEFAULT_BG_URL`), handed to `mmcl-modpack`'s image cache so a pack that uses the
//! defaults gets the same `<sha1>.png` cache file as the Java launcher writes.
//!
//! The bytes come from the Java resources folder, so both launchers hash the same files. A Java
//! resource URL ending in `micaminecraftlauncher.png` names the launcher icon in either slot
//! (Java's default background fell back to the icon when the background resource was missing,
//! and generated manifests recorded that URL).

use mmcl_manifest::pack::{BUNDLED_BACKGROUND_URL, BUNDLED_LOGO_URL};

/// The launcher icon, the default pack logo.
pub const LOGO_PNG: &[u8] =
    include_bytes!("../../../../src/main/resources/micaminecraftlauncher.png");
/// The default pack background.
pub const BACKGROUND_PNG: &[u8] =
    include_bytes!("../../../../src/main/resources/default_background.png");

fn ends_with_resource(url: &str, name: &str) -> bool {
    let lower = url.trim().to_ascii_lowercase();
    (lower.starts_with("file:") || lower.starts_with("jar:"))
        && lower.ends_with(&format!("/{name}"))
}

/// The bytes for a bundled-image URL, or `None` for any other URL.
pub fn bundled_image(url: &str) -> Option<&'static [u8]> {
    let trimmed = url.trim();
    if trimmed == BUNDLED_LOGO_URL || ends_with_resource(trimmed, "micaminecraftlauncher.png") {
        return Some(LOGO_PNG);
    }
    if trimmed == BUNDLED_BACKGROUND_URL || ends_with_resource(trimmed, "default_background.png") {
        return Some(BACKGROUND_PNG);
    }
    None
}

/// Registers [`bundled_image`] with the pack image cache (once per process).
pub fn install() {
    let _ = mmcl_modpack::environment::set_bundled_image_provider(bundled_image);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sentinels_and_java_resource_urls_resolve() {
        assert_eq!(bundled_image(BUNDLED_LOGO_URL), Some(LOGO_PNG));
        assert_eq!(bundled_image(BUNDLED_BACKGROUND_URL), Some(BACKGROUND_PNG));
        assert_eq!(
            bundled_image("jar:file:/C:/app/launcher.jar!/micaminecraftlauncher.png"),
            Some(LOGO_PNG)
        );
        assert_eq!(
            bundled_image("file:/opt/mmcl/default_background.png"),
            Some(BACKGROUND_PNG)
        );
        assert_eq!(
            bundled_image("https://example.com/micaminecraftlauncher.png"),
            None
        );
        assert_eq!(bundled_image(""), None);
    }

    #[test]
    fn the_bytes_are_png_files() {
        for bytes in [LOGO_PNG, BACKGROUND_PNG] {
            assert!(bytes.starts_with(b"\x89PNG"));
        }
    }
}
