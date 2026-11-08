// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Library-side operations that are not plain pack-manager calls: vanilla versions as packs,
//! loader versions installed as empty packs, the Modrinth preview-then-install import, and the
//! pack image cache.

use std::sync::Arc;

use mmcl_modpack::GameModPack;
use mmcl_modpack::catalog::loaders::{self, LoaderVersion};
use mmcl_modpack::import::modrinth::ProjectSummary;

use super::Engine;
use crate::error::{EngineError, EngineResult};
use crate::event::EngineEvent;
use crate::model::{ImportOutcome, LoaderVersionView, ModrinthProjectView, PackId, PackImagesView};

/// The installable loader version a view names (its installer / profile URL rebuilt the way
/// the catalogue builds it). `None` for an unknown loader type or blank versions.
pub(crate) fn loader_version_of(v: &LoaderVersionView) -> Option<LoaderVersion> {
    let t = v.loader_type.trim().to_ascii_lowercase();
    let mc = v.minecraft_version.trim();
    let lv = v.loader_version.trim();
    if lv.is_empty() || (t != "neoforge" && mc.is_empty()) {
        return None;
    }
    let installer_url = match t.as_str() {
        "forge" => loaders::forge_installer_url(mc, lv),
        "neoforge" => loaders::neoforge_installer_url(lv),
        "fabric" => loaders::fabric_profile_url(mc, lv),
        _ => return None,
    };
    Some(LoaderVersion {
        loader_type: t,
        mc_version: mc.to_string(),
        loader_version: lv.to_string(),
        installer_url,
    })
}

/// The preview view of a fetched Modrinth project.
pub(crate) fn project_view(slug: &str, p: &ProjectSummary) -> ModrinthProjectView {
    let v = p.latest_version.as_ref();
    let or_project = |from_version: Option<&Vec<String>>, project: &Vec<String>| {
        from_version
            .filter(|l| !l.is_empty())
            .unwrap_or(project)
            .clone()
    };
    ModrinthProjectView {
        project_id: p.project_id.clone(),
        slug: if slug.trim().is_empty() {
            p.slug.clone().unwrap_or_default()
        } else {
            slug.to_string()
        },
        title: p.title.clone(),
        description: p.description.clone(),
        icon_url: p.icon_url.clone(),
        version_id: v.and_then(|v| v.version_id.clone()),
        version_name: v.and_then(|v| v.name.clone()),
        version_number: v.and_then(|v| v.version_number.clone()),
        game_versions: or_project(v.map(|v| &v.minecraft_versions), &p.minecraft_versions),
        loaders: or_project(v.map(|v| &v.loaders), &p.loaders),
        mrpack_url: v
            .and_then(mmcl_modpack::import::modrinth::mrpack_url)
            .map(str::to_string),
    }
}

/// Keeps only the image files that exist.
fn existing(paths: Vec<std::path::PathBuf>) -> Vec<std::path::PathBuf> {
    paths.into_iter().filter(|p| p.is_file()).collect()
}

impl Engine {
    /// Removes a vanilla version from the library (and its folder when `delete_files`).
    pub(super) async fn uninstall_vanilla(
        &self,
        pack: &Arc<GameModPack>,
        version: &str,
        delete_files: bool,
    ) -> EngineResult<()> {
        if delete_files {
            let root = pack.checked_pack_root()?;
            if root.exists()
                && let Err(e) = tokio::fs::remove_dir_all(&root).await
            {
                mmcl_log::warn_silent(&mmcl_i18n::tf!(
                    "log.gameLibrary.deleteFolderFailed",
                    e.to_string()
                ));
            }
        }
        mmcl_modpack::catalog::vanilla::uninstall_version(&self.inner.config, version);
        self.inner.vanilla.lock().remove(version);
        self.emit(EngineEvent::PacksChanged);
        Ok(())
    }

    /// Installs a loader version as an empty pack (Browse loader cards' Install button): writes
    /// the bare-loader manifest under `config/imported-manifests` (if absent) and installs it.
    pub async fn install_loader_version(
        &self,
        version: &LoaderVersionView,
    ) -> EngineResult<PackId> {
        let v = loader_version_of(version).ok_or_else(|| {
            EngineError::Other(mmcl_i18n::tf!(
                "engine.error.unknownLoader",
                version.loader_type.as_str()
            ))
        })?;
        let url = loaders::install_version(&self.inner.packs, &v).await?;
        self.emit(EngineEvent::PacksChanged);
        Ok(self
            .inner
            .packs
            .installed_by_url(&url)
            .map(|p| PackId::of(&p))
            .unwrap_or_else(|| PackId::new(url)))
    }

    /// Fetches a Modrinth modpack project (and the version the URL names, else the newest)
    /// without installing anything, for the import-confirm dialog. `Ok(None)` when Modrinth has
    /// no such project (or is unreachable); an error when `url` is not a Modrinth project URL.
    pub async fn preview_modrinth(&self, url: &str) -> EngineResult<Option<ModrinthProjectView>> {
        let c = mmcl_modpack::import::classify(Some(url));
        if c.source != mmcl_modpack::import::ImportSource::Modrinth {
            return Err(EngineError::Other(mmcl_i18n::tf!(
                "engine.error.notModrinthUrl",
                url.trim()
            )));
        }
        let Some(slug) = c.slug.filter(|s| !s.trim().is_empty()) else {
            return Ok(None);
        };
        let http = self.inner.http.clone();
        Ok(mmcl_modpack::import::modrinth::fetch_project(
            http.as_ref(),
            &slug,
            c.version_id.as_deref(),
        )
        .await
        .map(|p| project_view(&slug, &p)))
    }

    /// Installs a previewed Modrinth project: downloads its `.mrpack`, translates it into a
    /// local manifest and installs that (the second step after [`Engine::preview_modrinth`]).
    pub async fn install_modrinth(
        &self,
        project: &ModrinthProjectView,
    ) -> EngineResult<ImportOutcome> {
        let not_found = || {
            EngineError::Other(mmcl_i18n::tf!(
                "engine.error.modrinthProjectNotFound",
                project.slug.as_str()
            ))
        };
        let dl = project.mrpack_url.as_deref().ok_or_else(not_found)?;
        let slug = Some(project.slug.as_str()).filter(|s| !s.trim().is_empty());
        let r = mmcl_modpack::import::mrpack::import_and_install_mrpack(
            &self.inner.packs,
            dl,
            slug,
            project.icon_url.as_deref(),
        )
        .await?;
        self.emit(EngineEvent::PacksChanged);
        let pack = self
            .inner
            .packs
            .installed_by_url(&r.local_manifest_url)
            .map(|p| PackId::of(&p))
            .unwrap_or_else(|| PackId::new(r.local_manifest_url.clone()));
        Ok(ImportOutcome {
            pack,
            mod_count: Some(r.mod_count),
        })
    }

    /// The cached image files of an installed pack (modpack or vanilla), primary first, for
    /// cycling. Disk only: missing images are absent. Empty for a pack that is not installed.
    pub fn pack_images(&self, id: &PackId) -> PackImagesView {
        let Some(pack) = self.installed_pack(id) else {
            return PackImagesView::default();
        };
        let env = pack.environment();
        PackImagesView {
            logos: existing(env.logo_paths_raw(&pack)),
            backgrounds: existing(env.background_paths_raw(&pack)),
            cached: env.did_cache_images(),
        }
    }

    /// Downloads every declared logo and background of an installed pack into the image cache
    /// (the `cache_images` retry for a declared-but-missing image), then returns
    /// [`Engine::pack_images`]. Offline, nothing downloads and `cached` stays `false`.
    pub async fn cache_pack_images(&self, id: &PackId) -> EngineResult<PackImagesView> {
        let pack = self.require_installed(id)?;
        if !self.inner.http.is_offline() {
            pack.environment()
                .cache_images(&pack, self.inner.http.as_ref())
                .await;
            self.emit(EngineEvent::PackChanged(id.clone()));
        }
        Ok(self.pack_images(id))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_modpack::import::modrinth::VersionSummary;

    fn view(t: &str, mc: &str, v: &str) -> LoaderVersionView {
        LoaderVersionView {
            loader_type: t.into(),
            minecraft_version: mc.into(),
            loader_version: v.into(),
            display_name: String::new(),
            installed: false,
        }
    }

    #[test]
    fn loader_views_rebuild_the_catalogue_urls() {
        let f = loader_version_of(&view("Forge", "1.20.1", "47.3.0")).unwrap();
        assert_eq!(f.loader_type, "forge");
        assert_eq!(
            f.installer_url,
            "https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.3.0/forge-1.20.1-47.3.0-installer.jar"
        );
        let n = loader_version_of(&view("neoforge", "", "21.1.77")).unwrap();
        assert!(n.installer_url.ends_with("neoforge-21.1.77-installer.jar"));
        let fa = loader_version_of(&view("fabric", "1.21.5", "0.16.10")).unwrap();
        assert_eq!(
            fa.installer_url,
            "https://meta.fabricmc.net/v2/versions/loader/1.21.5/0.16.10/profile/json"
        );
        assert_eq!(fa.manifest_filename(), "loader-fabric-1.21.5-0.16.10.json");
        assert!(loader_version_of(&view("quilt", "1.21", "1")).is_none());
        assert!(loader_version_of(&view("forge", "", "47.3.0")).is_none());
        assert!(loader_version_of(&view("fabric", "1.21", " ")).is_none());
    }

    #[test]
    fn a_project_view_prefers_the_versions_facts() {
        let mut p = ProjectSummary {
            project_id: Some("AANobbMI".into()),
            slug: Some("fab-pack".into()),
            title: Some("Fab Pack".into()),
            description: Some("d".into()),
            icon_url: None,
            minecraft_versions: vec!["1.20.1".into(), "1.21".into()],
            loaders: vec!["fabric".into(), "quilt".into()],
            latest_version: Some(VersionSummary {
                version_id: Some("v1".into()),
                name: Some("Release 2".into()),
                version_number: Some("2.0".into()),
                minecraft_versions: vec!["1.21".into()],
                loaders: Vec::new(),
                files: Vec::new(),
            }),
        };
        let v = project_view("", &p);
        assert_eq!(v.slug, "fab-pack");
        assert_eq!(v.version_number.as_deref(), Some("2.0"));
        assert_eq!(v.game_versions, ["1.21"]);
        assert_eq!(v.loaders, ["fabric", "quilt"]);
        assert_eq!(v.mrpack_url, None);
        p.latest_version = None;
        let v = project_view("named", &p);
        assert_eq!(v.slug, "named");
        assert_eq!(v.game_versions, ["1.20.1", "1.21"]);
        assert!(v.version_id.is_none());
    }
}
