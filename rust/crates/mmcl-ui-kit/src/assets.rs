// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The application asset source: launcher icons, bundled images, and gpui-component's own icons.
//!
//! Install once with `gpui_platform::application().with_assets(mmcl_ui_kit::Assets)`.

use std::borrow::Cow;

use gpui::{AssetSource, Result, SharedString};

use crate::icons::{ASSET_DIR, Icon};

/// Path of the launcher window icon (`micaminecraftlauncher.png`).
pub const APP_ICON: &str = "mmcl/images/micaminecraftlauncher.png";
/// Path of the signed-out avatar placeholder (`no_user.png`).
pub const NO_USER: &str = "mmcl/images/no_user.png";
/// Path of the default pack background (`default_background.png`).
pub const DEFAULT_BACKGROUND: &str = "mmcl/images/default_background.png";
/// Path of the update badge image (`update.png`).
pub const UPDATE_BADGE: &str = "mmcl/images/update.png";

const IMAGES: [(&str, &[u8]); 4] = [
    (
        APP_ICON,
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../src/main/resources/micaminecraftlauncher.png"
        )),
    ),
    (
        NO_USER,
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../src/main/resources/no_user.png"
        )),
    ),
    (
        DEFAULT_BACKGROUND,
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../src/main/resources/default_background.png"
        )),
    ),
    (
        UPDATE_BADGE,
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../src/main/resources/update.png"
        )),
    ),
];

/// The launcher's asset source. Paths under `mmcl/` are the launcher's own; everything else
/// (gpui-component's `icons/*.svg`) is delegated to `gpui_kit_assets::Assets`.
#[derive(Debug, Clone, Copy, Default)]
pub struct Assets;

impl Assets {
    /// The bytes of a launcher-owned asset, without going through GPUI.
    pub fn launcher_asset(path: &str) -> Option<&'static [u8]> {
        if let Some(icon) = Icon::from_asset_path(path) {
            return Some(icon.svg_document().as_bytes());
        }
        IMAGES.iter().find(|(p, _)| *p == path).map(|(_, b)| *b)
    }

    /// Every launcher-owned asset path.
    pub fn launcher_paths() -> impl Iterator<Item = SharedString> {
        Icon::ALL
            .into_iter()
            .map(Icon::asset_path)
            .chain(IMAGES.iter().map(|(p, _)| SharedString::from(*p)))
    }
}

impl AssetSource for Assets {
    fn load(&self, path: &str) -> Result<Option<Cow<'static, [u8]>>> {
        if path.is_empty() {
            return Ok(None);
        }
        if path.starts_with("mmcl/") {
            return Ok(Self::launcher_asset(path).map(Cow::Borrowed));
        }
        gpui_kit_assets::Assets.load(path)
    }

    fn list(&self, path: &str) -> Result<Vec<SharedString>> {
        let mut out: Vec<SharedString> = Self::launcher_paths()
            .filter(|p| p.starts_with(path))
            .collect();
        if !path.starts_with(ASSET_DIR) && !path.starts_with("mmcl/") {
            out.extend(gpui_kit_assets::Assets.list(path)?);
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn serves_icons_and_images() {
        let svg = Assets.load("mmcl/icons/play.svg").unwrap().unwrap();
        assert!(
            std::str::from_utf8(&svg)
                .unwrap()
                .contains("<path d=\"M 8 17.175")
        );
        let png = Assets.load(APP_ICON).unwrap().unwrap();
        assert_eq!(&png[..4], b"\x89PNG");
        assert!(Assets.load("mmcl/icons/nope.svg").unwrap().is_none());
        assert!(Assets.load("").unwrap().is_none());
    }

    #[test]
    fn lists_launcher_icons() {
        let icons = Assets.list(ASSET_DIR).unwrap();
        assert_eq!(icons.len(), Icon::ALL.len());
    }

    #[test]
    fn delegates_component_icons() {
        // gpui-component's own glyphs live under `icons/`.
        let listed = Assets.list("icons/").unwrap();
        assert!(!listed.is_empty());
        let first = listed[0].clone();
        assert!(Assets.load(&first).unwrap().is_some());
    }
}
