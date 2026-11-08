// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Mod loaders (Java `GameModLoader` and its implementations): the [`ModLoader`] trait,
//! [`LoaderKind`], [`open_loader`], and the [`ModLoaderFactory`] the modpack runtime builds
//! loaders through.

pub mod fabric;
pub mod forge;
pub mod planner;
mod processors;

use std::any::Any;
use std::fmt;
use std::sync::Arc;

use futures::future::BoxFuture;
use mmcl_modpack::{GameModPack, ManagedFile, ModLoaderFactory, ModpackError, PackLoader};
use mmcl_net::CancelToken;
use mmcl_progress::ProgressSink;

use crate::env::LaunchEnv;
use crate::error::Result;
use crate::run::PackRun;

/// Which loader a pack uses.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum LoaderKind {
    Forge,
    NeoForge,
    Fabric,
}

impl LoaderKind {
    /// From `packModLoader` (trimmed, case-insensitive). Blank is Forge.
    pub fn from_type(t: &str) -> std::result::Result<LoaderKind, ModpackError> {
        match t.trim().to_ascii_lowercase().as_str() {
            "" | "forge" => Ok(LoaderKind::Forge),
            "neoforge" => Ok(LoaderKind::NeoForge),
            "fabric" => Ok(LoaderKind::Fabric),
            other => Err(ModpackError::UnsupportedLoader(other.to_string())),
        }
    }

    /// Display name.
    pub fn name(self) -> &'static str {
        match self {
            LoaderKind::Forge => "Forge",
            LoaderKind::NeoForge => "NeoForge",
            LoaderKind::Fabric => "Fabric",
        }
    }

    /// The manifest type string (`forge`, `neoforge`, `fabric`).
    pub fn type_name(self) -> &'static str {
        match self {
            LoaderKind::Forge => "forge",
            LoaderKind::NeoForge => "neoforge",
            LoaderKind::Fabric => "fabric",
        }
    }

    /// Whether the loader runs install processors ("Game patching").
    pub fn uses_post_install_steps(self) -> bool {
        self != LoaderKind::Fabric
    }
}

/// A pack's mod loader.
pub trait ModLoader: Send + Sync + fmt::Debug {
    /// Which loader.
    fn kind(&self) -> LoaderKind;
    /// Display name (`Forge`, `NeoForge`, `Fabric`).
    fn name(&self) -> &'static str {
        self.kind().name()
    }
    /// The Minecraft version the loader targets.
    fn minecraft_version(&self) -> String;
    /// The loader's version (Forge: the version manifest id; Fabric: the loader number).
    fn loader_version(&self) -> String;
    /// The loader's game arguments as one string (Java shape; split before use).
    fn minecraft_arguments(&self) -> String;
    /// The loader's JVM arguments as argv tokens (client launches only).
    fn jvm_arguments(&self) -> Vec<String>;
    /// The client main class.
    fn main_class(&self) -> String;
    /// The server main class; `None` means "use the client one".
    fn server_main_class(&self) -> Option<String>;
    /// Downloads and verifies the loader's libraries and returns its classpath entries.
    fn build_classpath<'a>(
        &'a self,
        run: &'a PackRun,
        sink: &'a Arc<dyn ProgressSink>,
    ) -> BoxFuture<'a, Result<Vec<String>>>;
    /// Post-install steps (Forge/NeoForge install processors). `runtime_component` is the
    /// vanilla client.json's Java component.
    fn run_post_install<'a>(
        &'a self,
        _run: &'a PackRun,
        _sink: &'a Arc<dyn ProgressSink>,
        _runtime_component: &'a str,
    ) -> BoxFuture<'a, Result<()>> {
        Box::pin(async { Ok(()) })
    }
    /// The verified installer jar (Forge/NeoForge), for the official-launcher export.
    fn installer_file(&self) -> Option<&ManagedFile> {
        None
    }
}

/// The [`PackLoader`] wrapper the pack memoises.
#[derive(Debug, Clone)]
pub struct LoaderHandle(pub Arc<dyn ModLoader>);

impl PackLoader for LoaderHandle {
    fn loader_name(&self) -> String {
        self.0.name().to_string()
    }
    fn loader_version(&self) -> String {
        self.0.loader_version()
    }
    fn minecraft_version(&self) -> String {
        self.0.minecraft_version()
    }
    fn as_any(&self) -> &dyn Any {
        self
    }
}

/// Opens (downloads, verifies and parses) the loader of `pack` under the pack's current
/// prepare context. Prefer [`loader_of`], which memoises it on the pack.
pub async fn open_loader(env: &LaunchEnv, pack: &Arc<GameModPack>) -> Result<Arc<dyn ModLoader>> {
    let kind = LoaderKind::from_type(&pack.mod_loader_type().unwrap_or_default())?;
    let url = pack.mod_loader_url().unwrap_or("").trim().to_string();
    let hash = pack
        .mod_loader_hash()
        .map(str::trim)
        .filter(|h| !h.is_empty() && *h != "-1")
        .map(str::to_string);
    let run = PackRun::new(env.clone(), pack.clone(), CancelToken::new());
    Ok(match kind {
        LoaderKind::Forge | LoaderKind::NeoForge => {
            Arc::new(forge::ForgeLoader::open(&run, kind, &url, hash.as_deref()).await?)
        }
        LoaderKind::Fabric => {
            Arc::new(fabric::FabricLoader::open(&run, &url, hash.as_deref()).await?)
        }
    })
}

/// The [`ModLoaderFactory`] of this crate.
#[derive(Clone, Debug)]
pub struct LoaderFactory {
    env: LaunchEnv,
}

impl LoaderFactory {
    pub fn new(env: LaunchEnv) -> Self {
        LoaderFactory { env }
    }
}

impl ModLoaderFactory for LoaderFactory {
    fn create<'a>(
        &'a self,
        pack: &'a Arc<GameModPack>,
    ) -> BoxFuture<'a, std::result::Result<Arc<dyn PackLoader>, ModpackError>> {
        Box::pin(async move {
            let l = open_loader(&self.env, pack).await?;
            Ok(Arc::new(LoaderHandle(l)) as Arc<dyn PackLoader>)
        })
    }
}

/// Registers this crate's loaders with the modpack runtime (once per process). `false` when
/// a factory was already registered.
pub fn register_loader_factory(env: LaunchEnv) -> bool {
    mmcl_modpack::set_mod_loader_factory(Arc::new(LoaderFactory::new(env)))
}

/// The pack's memoised loader (`None` for vanilla), built with `env` on first use.
pub async fn loader_of(
    env: &LaunchEnv,
    pack: &Arc<GameModPack>,
) -> Result<Option<Arc<dyn ModLoader>>> {
    let factory = LoaderFactory::new(env.clone());
    let Some(l) = pack.mod_loader_with(&factory).await? else {
        return Ok(None);
    };
    match l.as_any().downcast_ref::<LoaderHandle>() {
        Some(h) => Ok(Some(h.0.clone())),
        // A loader built by another factory: rebuild one of ours (not memoised).
        None => Ok(Some(open_loader(env, pack).await?)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn kinds_from_type() {
        assert_eq!(LoaderKind::from_type("").unwrap(), LoaderKind::Forge);
        assert_eq!(
            LoaderKind::from_type(" NeoForge ").unwrap(),
            LoaderKind::NeoForge
        );
        assert_eq!(LoaderKind::from_type("fabric").unwrap(), LoaderKind::Fabric);
        assert!(matches!(
            LoaderKind::from_type("quilt"),
            Err(ModpackError::UnsupportedLoader(t)) if t == "quilt"
        ));
        assert!(!LoaderKind::Fabric.uses_post_install_steps());
        assert!(LoaderKind::NeoForge.uses_post_install_steps());
    }
}
