// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Import with confirmation (Java `MCLauncherGameLibraryGui.runModrinthImportFlow` +
//! `MCLauncherImportConfirmDialog`): prepare (download and read the archive, nothing installed),
//! show the preview, then commit (write out and install) or discard.

use std::path::Path;
use std::sync::atomic::Ordering;

use mmcl_modpack::import::mrpack::{IndexFile, PreparedMrpack};

use super::Engine;
use crate::error::{EngineError, EngineResult};
use crate::event::EngineEvent;
use crate::model::{
    ImportFileCategory, ImportFileView, ImportOutcome, ImportPreview, ImportPreviewSource,
    ImportRequest, ImportToken, ImportTotals, ModrinthProjectView, PackId,
};

/// How many prepared imports are kept; preparing one more drops the oldest.
pub(crate) const MAX_PREPARED_IMPORTS: usize = 8;

/// An import waiting for its confirmation.
pub(crate) enum PendingImport {
    /// A read `.mrpack` (Modrinth project or local file).
    Mrpack(Box<PreparedMrpack>),
    /// A source that is only read on confirm (Mica URL, zip, manifest file, instance folder).
    Deferred(ImportRequest),
}

/// The file rows of an index, in index order (entries without a path are skipped, as Java did).
pub(crate) fn file_views(files: &[IndexFile]) -> Vec<ImportFileView> {
    files
        .iter()
        .filter_map(|f| {
            let path = f.path.clone()?;
            let norm = path.replace('\\', "/");
            let file_name = norm.rsplit('/').next().unwrap_or(&norm).to_string();
            Some(ImportFileView {
                category: ImportFileCategory::of_path(&path),
                file_name,
                size: u64::try_from(f.file_size).unwrap_or(0),
                client_supported: !f
                    .env_client
                    .as_deref()
                    .is_some_and(|c| c.eq_ignore_ascii_case("unsupported")),
                path,
            })
        })
        .collect()
}

/// The preview of a prepared `.mrpack`.
pub(crate) fn mrpack_preview(
    token: ImportToken,
    source: ImportPreviewSource,
    p: &PreparedMrpack,
    icon_url: Option<&str>,
    source_label: String,
) -> ImportPreview {
    let files = file_views(&p.index.files);
    let totals = ImportTotals::of(&files);
    let some = |s: &str| Some(s.to_string()).filter(|s| !s.trim().is_empty());
    ImportPreview {
        token,
        source,
        has_details: true,
        pack_name: p.index.name.as_deref().and_then(some),
        pack_version: Some(p.pack_version()),
        minecraft_version: some(&p.mc_version),
        loader_type: some(p.loader_type),
        loader_version: some(&p.loader_version),
        summary: p.index.summary.as_deref().and_then(some),
        icon_url: icon_url.and_then(some),
        files,
        totals,
        source_label,
    }
}

/// The preview of a source read only on confirm.
pub(crate) fn deferred_preview(token: ImportToken, request: &ImportRequest) -> ImportPreview {
    let (source, label) = match request {
        ImportRequest::Url(u) => (ImportPreviewSource::MicaUrl, u.trim().to_string()),
        ImportRequest::MrpackFile(p) => (ImportPreviewSource::MrpackFile, file_label(p)),
        ImportRequest::ZipFile(p) => (ImportPreviewSource::ZipFile, file_label(p)),
        ImportRequest::ManifestFile(p) => (ImportPreviewSource::ManifestFile, file_label(p)),
        ImportRequest::PrismInstance(p) => (ImportPreviewSource::PrismInstance, file_label(p)),
    };
    ImportPreview {
        token,
        source,
        has_details: false,
        pack_name: None,
        pack_version: None,
        minecraft_version: None,
        loader_type: None,
        loader_version: None,
        summary: None,
        icon_url: None,
        files: Vec::new(),
        totals: ImportTotals::default(),
        source_label: label,
    }
}

fn file_label(p: &Path) -> String {
    p.file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_else(|| p.display().to_string())
}

impl Engine {
    fn store_prepared(&self, pending: PendingImport) -> ImportToken {
        let token = ImportToken(self.inner.next_import.fetch_add(1, Ordering::Relaxed));
        let mut map = self.inner.prepared_imports.lock();
        map.insert(token.0, pending);
        while map.len() > MAX_PREPARED_IMPORTS {
            let Some(oldest) = map.keys().next().copied() else {
                break;
            };
            // Dropping a prepared `.mrpack` deletes its download.
            map.remove(&oldest);
        }
        token
    }

    fn next_import_token(&self) -> ImportToken {
        ImportToken(self.inner.next_import.load(Ordering::Relaxed))
    }

    /// Step one of a Modrinth import with confirmation: downloads the project's `.mrpack`,
    /// reads its index, checks the loader and versions and fetches the loader installer's
    /// hash, without installing or writing into the library. Show the returned preview, then
    /// [`Engine::commit_import`] or [`Engine::discard_import`].
    pub async fn prepare_modrinth(
        &self,
        project: &ModrinthProjectView,
    ) -> EngineResult<ImportPreview> {
        let dl = project.mrpack_url.as_deref().ok_or_else(|| {
            EngineError::Other(mmcl_i18n::tf!(
                "engine.error.modrinthProjectNotFound",
                project.slug.as_str()
            ))
        })?;
        let slug = Some(project.slug.as_str()).filter(|s| !s.trim().is_empty());
        let http = self.inner.http.clone();
        let prepared = mmcl_modpack::import::mrpack::prepare_mrpack(
            &self.inner.paths,
            http.as_ref(),
            dl,
            slug,
            project.icon_url.as_deref(),
        )
        .await?;
        let label = project
            .title
            .clone()
            .filter(|t| !t.trim().is_empty())
            .unwrap_or_else(|| project.slug.clone());
        Ok(self.keep_mrpack(
            ImportPreviewSource::ModrinthProject,
            prepared,
            project.icon_url.as_deref(),
            label,
        ))
    }

    fn keep_mrpack(
        &self,
        source: ImportPreviewSource,
        prepared: PreparedMrpack,
        icon_url: Option<&str>,
        label: String,
    ) -> ImportPreview {
        let mut preview =
            mrpack_preview(self.next_import_token(), source, &prepared, icon_url, label);
        mmcl_log::std(&mmcl_i18n::tf!(
            "log.engine.import.prepared",
            prepared.pack_name(),
            preview.totals.file_count().to_string()
        ));
        preview.token = self.store_prepared(PendingImport::Mrpack(Box::new(prepared)));
        preview
    }

    /// Step one of any import with confirmation. A Modrinth URL is previewed and prepared
    /// ([`Engine::preview_modrinth`] + [`Engine::prepare_modrinth`]) and a `.mrpack` file is
    /// read, both with the full file list; CurseForge / Technic URLs are refused as in
    /// [`Engine::import`]; other sources (a Mica URL, zip, manifest file, instance folder) are
    /// only read on confirm, so their preview has `has_details == false`.
    pub async fn prepare_import(&self, request: ImportRequest) -> EngineResult<ImportPreview> {
        use mmcl_modpack::import;
        match &request {
            ImportRequest::Url(u) => {
                let c = import::classify(Some(u));
                match c.source {
                    import::ImportSource::Modrinth => {
                        let project = self.preview_modrinth(u).await?.ok_or_else(|| {
                            EngineError::Other(mmcl_i18n::tf!(
                                "engine.error.modrinthProjectNotFound",
                                c.slug.as_deref().unwrap_or_default()
                            ))
                        })?;
                        return self.prepare_modrinth(&project).await;
                    }
                    import::ImportSource::CurseForge => {
                        return Err(EngineError::Other(
                            mmcl_i18n::t!("dialog.import.curseforge.header").into_owned(),
                        ));
                    }
                    import::ImportSource::Technic => {
                        return Err(EngineError::Other(
                            mmcl_i18n::t!("dialog.import.technic.header").into_owned(),
                        ));
                    }
                    import::ImportSource::Mica | import::ImportSource::Unknown => {}
                }
            }
            ImportRequest::MrpackFile(p) => {
                let http = self.inner.http.clone();
                let prepared = import::mrpack::prepare_mrpack_file(
                    &self.inner.paths,
                    http.as_ref(),
                    p,
                    None,
                    None,
                )
                .await?;
                return Ok(self.keep_mrpack(
                    ImportPreviewSource::MrpackFile,
                    prepared,
                    None,
                    file_label(p),
                ));
            }
            _ => {}
        }
        let preview = deferred_preview(self.next_import_token(), &request);
        let token = self.store_prepared(PendingImport::Deferred(request));
        Ok(ImportPreview { token, ..preview })
    }

    /// Step two: installs a prepared import (the confirm dialog's Import button). A prepared
    /// `.mrpack` has its overrides extracted and its manifest written, then installs; a deferred
    /// source runs [`Engine::import`]. The token is used up either way.
    /// [`EngineError::ImportNotPrepared`] when it names nothing (already committed, discarded,
    /// or dropped because more than eight were waiting).
    pub async fn commit_import(&self, token: ImportToken) -> EngineResult<ImportOutcome> {
        let pending = self
            .inner
            .prepared_imports
            .lock()
            .remove(&token.0)
            .ok_or(EngineError::ImportNotPrepared(token.0))?;
        match pending {
            PendingImport::Deferred(request) => self.import(request).await,
            PendingImport::Mrpack(prepared) => {
                mmcl_log::std(&mmcl_i18n::tf!(
                    "log.gameLibrary.modrinthConfirmedModList",
                    prepared.pack_name()
                ));
                let r = mmcl_modpack::import::mrpack::install_prepared_mrpack(
                    &self.inner.packs,
                    *prepared,
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
        }
    }

    /// Cancels a prepared import (the confirm dialog's Cancel): its download is deleted and
    /// nothing was installed. `false` when the token names nothing.
    pub fn discard_import(&self, token: ImportToken) -> bool {
        let pending = self.inner.prepared_imports.lock().remove(&token.0);
        match pending {
            Some(PendingImport::Mrpack(p)) => {
                (*p).discard();
                true
            }
            Some(PendingImport::Deferred(_)) => true,
            None => false,
        }
    }

    /// The tokens of the imports waiting for confirmation, oldest first.
    pub fn prepared_imports(&self) -> Vec<ImportToken> {
        self.inner
            .prepared_imports
            .lock()
            .keys()
            .map(|k| ImportToken(*k))
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn file(path: &str, size: i64, client: Option<&str>) -> IndexFile {
        IndexFile {
            path: Some(path.into()),
            file_size: size,
            env_client: client.map(str::to_string),
            ..Default::default()
        }
    }

    #[test]
    fn rows_and_totals_follow_the_java_dialog() {
        let files = vec![
            file("mods/sodium.jar", 1_000, Some("required")),
            file("Mods\\Lithium.jar", 500, None),
            file("mods/server.jar", 10, Some("unsupported")),
            file("config/a.toml", 20, None),
            file("resourcepacks/r.zip", 30, None),
            file("shaderpacks/s.zip", 40, None),
            file("options.txt", -5, None),
            IndexFile::default(),
        ];
        let rows = file_views(&files);
        assert_eq!(rows.len(), 7, "entries without a path are skipped");
        assert_eq!(rows[1].file_name, "Lithium.jar");
        assert_eq!(rows[1].category, ImportFileCategory::Mod);
        assert!(!rows[2].client_supported);
        assert_eq!(rows[6].size, 0, "a negative size reads as unknown");
        let t = ImportTotals::of(&rows);
        assert_eq!(
            (t.mods, t.configs, t.resource_packs, t.shader_packs, t.other),
            (3, 1, 1, 1, 1)
        );
        assert_eq!(t.total_bytes, 1_600);
        assert_eq!(t.file_count(), 7);
    }

    #[test]
    fn categories_ignore_case_and_never_split_a_character() {
        assert_eq!(
            ImportFileCategory::of_path("SHADERPACKS/x.zip"),
            ImportFileCategory::ShaderPack
        );
        assert_eq!(
            ImportFileCategory::of_path("mod\u{e9}s/x.jar"),
            ImportFileCategory::Other
        );
        assert_eq!(ImportFileCategory::of_path("m"), ImportFileCategory::Other);
    }

    #[test]
    fn deferred_sources_name_what_will_be_read() {
        let p = deferred_preview(
            ImportToken(4),
            &ImportRequest::ZipFile(PathBuf::from("/x/My Pack.zip")),
        );
        assert_eq!(p.source, ImportPreviewSource::ZipFile);
        assert!(!p.has_details);
        assert_eq!(p.source_label, "My Pack.zip");
        let p = deferred_preview(
            ImportToken(5),
            &ImportRequest::Url(" https://example.test/p.json ".into()),
        );
        assert_eq!(p.source, ImportPreviewSource::MicaUrl);
        assert_eq!(p.source_label, "https://example.test/p.json");
        assert_eq!(p.mods().count(), 0);
    }
}
