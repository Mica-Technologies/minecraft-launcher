// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Fabric (Java `GameModLoaderFabric`): the meta-service profile JSON at
//! `bin/fabric-profile.json` and its libraries. B-17: the libraries are verified on the
//! shared pool (Java did it sequentially); the classpath keeps declaration order.

use std::fs;
use std::sync::Arc;

use futures::future::BoxFuture;
use mmcl_i18n::tf;
use mmcl_modpack::ManagedFile;
use mmcl_mojang::HostInfo;
use mmcl_mojang::fabric_profile::{FABRIC_SERVER_MAIN_CLASS, FabricProfile};
use mmcl_progress::ProgressSink;

use super::{LoaderKind, ModLoader};
use crate::batch::{BATCH_TIMEOUT, BatchKeys, blocking, join_rel, run_batch, spec};
use crate::error::Result;
use crate::run::PackRun;

/// The profile's path under the pack.
pub const PROFILE_FILE: [&str; 2] = ["bin", "fabric-profile.json"];

/// One Fabric library: URL and path under `libraries/` (forward slashes).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FabricAsset {
    pub url: String,
    pub path: String,
    pub sha1: Option<String>,
}

/// The libraries of a profile. Malformed coordinates are skipped; a traversal attempt is an
/// error (never skipped silently).
pub fn plan_libraries(profile: &FabricProfile) -> Result<Vec<FabricAsset>> {
    let mut out = Vec::new();
    for lib in profile.libraries() {
        let Some(path) = mmcl_mojang::maven::to_relative_path_or_none(&lib.name)? else {
            continue;
        };
        out.push(FabricAsset {
            url: format!("{}{path}", lib.repository_base()),
            path,
            sha1: lib.sha1.clone(),
        });
    }
    Ok(out)
}

/// A Fabric loader.
pub struct FabricLoader {
    profile: Arc<FabricProfile>,
    file: ManagedFile,
    host: HostInfo,
}

impl std::fmt::Debug for FabricLoader {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("FabricLoader")
            .field("id", &self.profile.id())
            .field("minecraft_version", &self.profile.minecraft_version())
            .finish_non_exhaustive()
    }
}

impl FabricLoader {
    /// Downloads (hash only when one is given: the meta service publishes none) and parses
    /// the profile.
    pub async fn open(run: &PackRun, url: &str, sha1: Option<&str>) -> Result<FabricLoader> {
        let path = run.root()?.join(PROFILE_FILE[0]).join(PROFILE_FILE[1]);
        let file = run.managed_at(spec(url, "", sha1), &path);
        file.update(&run.ctx, run.env.http.as_ref()).await?;
        let p = path.clone();
        let bytes = blocking(move || fs::read(p)).await??;
        let profile = FabricProfile::parse(&bytes)?;
        Ok(FabricLoader {
            profile: Arc::new(profile),
            file,
            host: run.env.host.clone(),
        })
    }

    /// The parsed profile.
    pub fn profile(&self) -> &FabricProfile {
        &self.profile
    }

    /// The profile's managed file.
    pub fn profile_file(&self) -> &ManagedFile {
        &self.file
    }

    async fn build(&self, run: &PackRun, sink: &Arc<dyn ProgressSink>) -> Result<Vec<String>> {
        let assets = plan_libraries(&self.profile)?;
        let libs = run.libraries_dir()?;
        let n = assets.len().max(1) as f64;
        let jobs = assets
            .iter()
            .map(|a| {
                let mf = run.managed(
                    spec(&a.url, &a.path, a.sha1.as_deref()),
                    &libs,
                    Some(sink.as_ref()),
                );
                let (ctx, http, sink) = (run.ctx.clone(), run.env.http.clone(), sink.clone());
                let fut: BoxFuture<'static, Result<()>> = Box::pin(async move {
                    mf.update(&ctx, http.as_ref()).await?;
                    sink.submit_progress(
                        &tf!("forgeLoader.verifiedAsset", mf.file_name()),
                        60.0 / n,
                    );
                    Ok(())
                });
                fut
            })
            .collect();
        run_batch(
            &run.env.pool,
            jobs,
            BATCH_TIMEOUT,
            &run.cancel,
            BatchKeys {
                timeout: "forge.error.libsTimedOut",
                interrupted: "forge.error.libsInterrupted",
                failed: Some("forge.error.libDownloadFailed"),
                pass_modpack: true,
            },
        )
        .await?;
        let cp = crate::command::ordered_unique(
            assets
                .iter()
                .map(|a| join_rel(&libs, &a.path).to_string_lossy().into_owned()),
        );
        mmcl_log::debug(&tf!(
            "log.gameModLoaderFabric.classpathCount",
            cp.len().to_string()
        ));
        Ok(cp)
    }
}

impl ModLoader for FabricLoader {
    fn kind(&self) -> LoaderKind {
        LoaderKind::Fabric
    }
    fn minecraft_version(&self) -> String {
        self.profile.minecraft_version().to_string()
    }
    fn loader_version(&self) -> String {
        self.profile.loader_version()
    }
    fn minecraft_arguments(&self) -> String {
        self.profile.minecraft_arguments(&self.host)
    }
    fn jvm_arguments(&self) -> Vec<String> {
        self.profile.jvm_arguments_vec(&self.host)
    }
    fn main_class(&self) -> String {
        self.profile.main_class().to_string()
    }
    fn server_main_class(&self) -> Option<String> {
        Some(FABRIC_SERVER_MAIN_CLASS.to_string())
    }
    fn build_classpath<'a>(
        &'a self,
        run: &'a PackRun,
        sink: &'a Arc<dyn ProgressSink>,
    ) -> BoxFuture<'a, Result<Vec<String>>> {
        Box::pin(self.build(run, sink))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn library_plan() {
        let p = FabricProfile::parse(
            br#"{"id":"fabric-loader-0.16.10-1.21.5","inheritsFrom":"1.21.5",
                "mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
                "libraries":[
                  {"name":"net.fabricmc:fabric-loader:0.16.10","url":"https://maven.fabricmc.net/","sha1":"aa"},
                  {"name":"org.ow2.asm:asm:9.7.1","url":"https://maven.fabricmc.net"},
                  {"name":"bad"},
                  {"name":"net.minecraft:x:1"}
                ]}"#,
        )
        .unwrap();
        let l = plan_libraries(&p).unwrap();
        assert_eq!(l.len(), 3);
        assert_eq!(
            l[0].url,
            "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.16.10/fabric-loader-0.16.10.jar"
        );
        assert_eq!(l[0].sha1.as_deref(), Some("aa"));
        assert_eq!(
            l[1].url,
            "https://maven.fabricmc.net/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar"
        );
        assert_eq!(
            l[2].url,
            "https://libraries.minecraft.net/net/minecraft/x/1/x-1.jar"
        );
        let evil = FabricProfile::parse(
            br#"{"inheritsFrom":"1","mainClass":"m","libraries":[{"name":"a:..:1"}]}"#,
        )
        .unwrap();
        assert!(plan_libraries(&evil).is_err());
    }
}
