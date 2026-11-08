// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The launch pipeline of the Mica Minecraft Launcher (Rust port of `GameModPackLauncher`, the
//! `GameModLoader*` family, the I/O half of `GameLibraryManifest` / `GameAssetManifest`,
//! `Lwjgl2ArmPatcher` and `LoaderInstallerRunner`).
//!
//! * [`pipeline`]: [`prepare`](pipeline::prepare) (the parallel sync / loader / Minecraft
//!   branches, processors, scan, classpath merge, verify-state bookkeeping),
//!   [`launch`](pipeline::launch) (argv assembly and process start) and
//!   [`verify_all_files_now`](pipeline::verify_all_files_now).
//! * [`loader`]: the [`ModLoader`](loader::ModLoader) trait, Forge / NeoForge (legacy and modern,
//!   with install processors) and Fabric, plus the [`mmcl_modpack::ModLoaderFactory`]
//!   implementation ([`register_loader_factory`]).
//! * [`vanilla`], [`assets`]: Mojang version manifest, client.json, libraries, natives, the
//!   client jar and the shared asset store.
//! * [`command`]: pure argv helpers (placeholders, classpath merge, heap flags, log4j plan).
//! * [`arm64`]: LWJGL2 on ARM64 macOS / Linux.
//! * [`spawn`]: starting the game process; [`export_install`]: running a loader installer for
//!   the official Minecraft Launcher.

pub mod arm64;
pub mod assets;
mod batch;
mod classfile;
pub mod command;
pub mod env;
pub mod error;
pub mod export_install;
pub mod jar;
pub mod loader;
pub mod pipeline;
pub mod run;
pub mod spawn;
pub mod vanilla;

#[cfg(test)]
mod i18n_keys;
#[cfg(test)]
mod test_support;

pub use env::{Account, AuthArguments, HeapOverride, LaunchConfig, LaunchEnv, StaticConfig};
pub use error::{LaunchError, Result};
pub use loader::{
    LoaderFactory, LoaderKind, ModLoader, loader_of, open_loader, register_loader_factory,
};
pub use pipeline::{
    Branches, GameProcess, LaunchRequest, Prepared, launch, prepare, verify_all_files_now,
};
pub use spawn::{ChildIo, RuntimeLease};
