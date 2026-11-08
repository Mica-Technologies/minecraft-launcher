// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Java runtimes for the Mica Minecraft Launcher (Rust port of `RuntimeManager`).
//!
//! - [`RuntimeManager`]: verify / install (serialised per component), `java_path`, in-use
//!   registry, delete, installed list, Java-major helpers.
//! - Sources: Mojang runtime components (all.json + per-component manifests, SHA-1 checked,
//!   parallel, `link` entries created as symlinks on macOS/Linux); Bell-SW Liberica 8u392 for
//!   `jre-legacy`; and on Linux ARM64, where Mojang publishes nothing, the latest Liberica JRE of
//!   each component's Java major with Eclipse Adoptium as the second source.
//! - [`liberica`]: API URLs, OS/arch tokens, folder names, release parsing.
//! - [`install`]: manifest-entry containment checks and the installed-runtime scan.
//! - [`fsutil`]: Java executable search and execute-bit restore.
//! - [`progress`]: [`RuntimeProgress`] and the log fan-out.
//! - [`fetch`]: the download seam ([`NetFetcher`] in production).
//!
//! On-disk layout is the Java launcher's: `runtime/mojang-runtime-index.json`,
//! `runtime/<component>/.version` (written last, deleted first), `runtime-manifest.json`,
//! `jre-legacy/jre-legacy.api.json` and the extracted `jre8u392[.jre]/` folder.

pub mod error;
pub mod fetch;
pub mod fsutil;
pub mod install;
pub mod inuse;
pub mod liberica;
pub mod manager;
pub mod progress;

#[cfg(test)]
mod tests;

pub use error::RuntimeError;
pub use fetch::{Fetcher, NetFetcher};
pub use install::InstalledRuntime;
pub use inuse::InUseRegistry;
pub use manager::{
    LEGACY_COMPONENT, ResolvedRuntime, RuntimeManager, RuntimeSource, component_major, source_for,
    uses_alternative_source,
};
pub use mmcl_net::CancelToken;
pub use progress::{RuntimeProgress, SinkProgress, report_progress};
