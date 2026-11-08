// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The modpack runtime of the Mica Minecraft Launcher (Rust port of `game.modpack`, minus the
//! loaders, and `game.modpack.import_`).
//!
//! * [`pack`]: [`GameModPack`] (manifest + identity + per-instance caches), [`PackKind`], the
//!   [`ModLoaderFactory`] seam the launch crate plugs its loaders into.
//! * [`managed`]: [`ManagedFile`] verify/download with the containment, scheme, post-download
//!   hash, session and offline gates; [`context`]: [`PrepareContext`] / [`VerifyMode`].
//! * [`sync`]: [`FileSync`] for mods (parallel, with the incomplete-manifest and sweep guards),
//!   configs, resource packs, shader packs and initial files.
//! * [`fetch`]: [`ManifestFetcher`] (offline cache, conditional GET, `file:` manifests);
//!   [`index`]: the install index; [`manager`]: [`ModPackManager`] (installed / available lists).
//! * Sidecars: [`sidecar`] (`PackDotFile`, server favourites), [`verify_state`], [`audit`],
//!   [`update_log`], [`environment`] (folders and the content-addressed image cache).
//! * [`backup`], [`export`], [`diff`], [`conflicts`], [`modrinth_updates`], [`catalog`],
//!   [`scan`] (pre-launch scan orchestration), [`import`] (mrpack, Prism, Technic, Mica ZIP,
//!   `.mmcjson`).
//! * [`document`]: the editor document model (re-exported from `mmcl-manifest`).
//!
//! All network access goes through [`http::PackHttp`]; [`http::NetHttp`] is the real one.

pub mod audit;
pub mod backup;
pub mod catalog;
pub mod conflicts;
pub mod context;
pub mod diff;
pub mod environment;
pub mod error;
pub mod export;
pub mod fetch;
pub mod http;
pub mod import;
pub mod index;
pub mod managed;
pub mod manager;
pub mod meter;
pub mod modrinth_updates;
pub mod pack;
pub mod scan;
pub mod sidecar;
pub mod sync;
pub mod update_log;
pub mod verify_state;
pub mod ziputil;

/// The editor document model (`ModPackDocument`, `FileList`, `ModPackFileEntry`).
pub mod document {
    pub use mmcl_manifest::document::*;
    /// The Java name of [`FileEntry`].
    pub type ModPackFileEntry = FileEntry;
}

pub use context::{PrepareContext, VerifyMode};
pub use environment::PackEnvironment;
pub use error::{ModpackError, Result};
pub use fetch::ManifestFetcher;
pub use http::{NetHttp, PackHttp};
pub use index::{IndexEntry, InstallIndex};
pub use managed::{ManagedFile, SessionVerified, is_jar_url_contained_in};
pub use manager::ModPackManager;
pub use mmcl_manifest::{PackManifest, ServerFavorite, Side};
pub use pack::{
    GameModPack, LaunchHistory, ModLoaderFactory, PackKind, PackLoader, mod_loader_factory,
    set_mod_loader_factory,
};
pub use sync::{FileSync, SyncEnv};
pub use verify_state::{ScanFrequency, VerifyState};

#[cfg(test)]
mod i18n_keys;
