// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Packs this launcher writes itself (Java `LiveMcpLauncherActions.install_authored` /
//! `edit_manifest`, and the editor's save-back): create, fork, add / remove a mod, save an
//! edited manifest. Authored manifests live in `<config>/mcp-manifests/created-<key>.json`.

use std::path::{Component, Path, PathBuf};

use mmcl_modpack::document::{FileEntry, ModPackDocument};

use super::Engine;
use crate::error::{EngineError, EngineResult};
use crate::event::EngineEvent;
use crate::model::PackId;

/// The mod-loader document keys.
const KEY_MOD_LOADER: &str = "packModLoader";
const KEY_MOD_LOADER_URL: &str = "packModLoaderURL";
/// The legacy loader URL key, where Forge and Cleanroom installers also go.
const KEY_FORGE_URL: &str = "packForgeURL";
/// The loaders a new pack may name.
pub(crate) const VALID_LOADERS: [&str; 4] = ["forge", "neoforge", "fabric", "cleanroom"];

fn doc_error(e: mmcl_manifest::document::DocError) -> EngineError {
    let args = e.args();
    let fmt: Vec<mmcl_i18n::FmtArg> = args.iter().map(mmcl_i18n::FmtArg::from).collect();
    EngineError::Other(mmcl_i18n::format(e.key(), &fmt))
}

/// Resolves `.` and `..` without touching the disk.
fn normalize(path: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for c in path.components() {
        match c {
            Component::ParentDir => {
                out.pop();
            }
            Component::CurDir => {}
            other => out.push(other.as_os_str()),
        }
    }
    out
}

/// The local file of a `file:` manifest URL (absolute, normalized), else `None`.
pub(crate) fn file_url_path(manifest_url: &str) -> Option<PathBuf> {
    if !manifest_url
        .trim_start()
        .to_ascii_lowercase()
        .starts_with("file:")
    {
        return None;
    }
    let url = url::Url::parse(manifest_url.trim()).ok()?;
    let path = url.to_file_path().ok()?;
    let abs = std::path::absolute(&path).ok()?;
    Some(normalize(&abs))
}

/// The authored manifest a URL points at: a `file:` URL whose path lies component-wise inside
/// `authored_dir` (and is not the dir itself). An imported `.mmcjson` is local but the user's,
/// so a `file:` URL alone is not enough.
pub fn resolve_authored_manifest(manifest_url: &str, authored_dir: &Path) -> Option<PathBuf> {
    let path = file_url_path(manifest_url)?;
    let dir = normalize(&std::path::absolute(authored_dir).ok()?);
    (path.starts_with(&dir) && path != dir).then_some(path)
}

impl Engine {
    /// The authored manifest file of an installed pack, when this launcher wrote it.
    pub(crate) fn authored_manifest_of(&self, id: &PackId) -> EngineResult<PathBuf> {
        let pack = self.require_installed(id)?;
        let dir = self.inner.paths.mcp_manifests_dir();
        pack.manifest_url()
            .and_then(|u| resolve_authored_manifest(u, &dir))
            .filter(|p| p.is_file())
            .ok_or_else(|| {
                EngineError::Other(mmcl_i18n::tf!("engine.error.manifestNotOwned", pack.name()))
            })
    }

    /// Writes `doc` as a new authored manifest and installs it. A stale file of the same key
    /// (no installed pack uses it) is replaced; one in use refuses.
    async fn install_authored(&self, doc: ModPackDocument) -> EngineResult<PackId> {
        let dir = self.inner.paths.mcp_manifests_dir();
        let target = doc.local_manifest_path_in(&dir).map_err(doc_error)?;
        if target.exists() {
            let in_use = self.inner.packs.installed_snapshot().iter().any(|p| {
                p.manifest_url()
                    .and_then(|u| resolve_authored_manifest(u, &dir))
                    .is_some_and(|p| {
                        p == normalize(&std::path::absolute(&target).unwrap_or_default())
                    })
            });
            if !in_use {
                let _ = std::fs::remove_file(&target);
            }
        }
        let url = match doc.write_local_manifest(&dir, false) {
            Ok(u) => u,
            Err(mmcl_manifest::document::DocError::AlreadyExists(_)) => {
                return Err(EngineError::Other(mmcl_i18n::tf!(
                    "engine.error.manifestKeyTaken",
                    doc.get_string(mmcl_manifest::document::KEY_PACK_NAME)
                )));
            }
            Err(e) => return Err(doc_error(e)),
        };
        let pack = self.inner.packs.install_by_url(&url).await?;
        self.emit(EngineEvent::PacksChanged);
        Ok(PackId::of(&pack))
    }

    /// [`Engine::create_pack`].
    pub(super) async fn create_pack_impl(
        &self,
        name: &str,
        mod_loader: Option<&str>,
        mod_loader_url: Option<&str>,
    ) -> EngineResult<PackId> {
        let name = name.trim();
        if name.is_empty() {
            return Err(EngineError::Other(
                mmcl_i18n::t!("engine.error.packNameRequired").into_owned(),
            ));
        }
        let mut doc = ModPackDocument::blank();
        doc.put_string(mmcl_manifest::document::KEY_PACK_NAME, name);
        if let Some(l) = mod_loader
            .map(|l| l.trim().to_ascii_lowercase())
            .filter(|l| !l.is_empty())
        {
            if !VALID_LOADERS.contains(&l.as_str()) {
                return Err(EngineError::Other(mmcl_i18n::tf!(
                    "engine.error.unknownLoader",
                    l.as_str()
                )));
            }
            doc.put_string(KEY_MOD_LOADER, &l);
        }
        if let Some(u) = mod_loader_url.map(str::trim).filter(|u| !u.is_empty()) {
            doc.put_string(KEY_MOD_LOADER_URL, u);
            // A Cleanroom pack names its installer in packForgeURL (one jar for both sides).
            if doc.get_string(KEY_MOD_LOADER) == "cleanroom" {
                doc.put_string(KEY_FORGE_URL, u);
            }
        }
        self.install_authored(doc).await
    }

    /// [`Engine::fork_pack`].
    pub(super) async fn fork_pack_impl(
        &self,
        source: &PackId,
        new_name: &str,
    ) -> EngineResult<PackId> {
        let pack = self.require_installed(source)?;
        let text = self.pack_manifest_text(source).ok_or_else(|| {
            EngineError::Other(mmcl_i18n::tf!(
                "engine.error.manifestUnreadable",
                pack.name()
            ))
        })?;
        let doc = ModPackDocument::from_json(&text).map_err(doc_error)?;
        let fork = doc.fork_of(new_name).map_err(doc_error)?;
        self.install_authored(fork).await
    }

    /// Reads, edits and writes back an authored manifest, then reloads the pack list. A
    /// failed edit writes nothing.
    async fn edit_authored(
        &self,
        id: &PackId,
        edit: impl FnOnce(&mut ModPackDocument) -> EngineResult<()>,
    ) -> EngineResult<()> {
        let file = self.authored_manifest_of(id)?;
        let text = tokio::fs::read_to_string(&file).await?;
        let mut doc = ModPackDocument::from_json(&text).map_err(doc_error)?;
        edit(&mut doc)?;
        write_atomically(&file, doc.to_pretty_json().as_bytes()).await?;
        self.inner.packs.fetch_installed(false).await;
        self.emit(EngineEvent::PackChanged(id.clone()));
        self.emit(EngineEvent::PacksChanged);
        Ok(())
    }

    /// [`Engine::add_mod`].
    pub(super) async fn add_mod_impl(
        &self,
        id: &PackId,
        mod_name: &str,
        remote_url: &str,
        local_path: &str,
        sha1: Option<&str>,
        sha256: Option<&str>,
    ) -> EngineResult<ModAdded> {
        if let Some(why) = unsafe_local_path(local_path) {
            return Err(EngineError::Other(why));
        }
        let file_name = mod_file_name(local_path)?;
        let pack = self.require_installed(id)?;
        // Refuse a pack we cannot edit before spending a download on it.
        self.authored_manifest_of(id)?;

        let hashes = self.hash_remote_mod(remote_url.trim()).await?;
        if let Some(why) = mod_hash_mismatch(&hashes, sha1, sha256) {
            return Err(EngineError::Other(why));
        }
        let entry = mod_entry(mod_name, remote_url, &file_name, &hashes);

        let pack_name = pack.name();
        let local = file_name.clone();
        self.edit_authored(id, move |doc| {
            if doc.add_mod(entry) {
                Ok(())
            } else {
                Err(EngineError::ModPathTaken {
                    pack_name,
                    local_path: local,
                })
            }
        })
        .await?;
        Ok(ModAdded {
            install_path: pack.pack_root().join("mods").join(&file_name),
            sha256: hashes.sha256,
        })
    }

    /// Downloads a mod jar once to a temporary file and hashes it.
    async fn hash_remote_mod(&self, url: &str) -> EngineResult<ModHashes> {
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let n = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let file =
            std::env::temp_dir().join(format!("mmcl-mcp-mod-{}-{n}.jar", std::process::id()));
        let downloaded = self
            .inner
            .http
            .download_bounded(url, &file, MAX_MOD_BYTES)
            .await;
        let hashed = file.clone();
        let hashes = match downloaded {
            Ok(()) => tokio::task::spawn_blocking(move || {
                (
                    mmcl_util::hash::file_sha1(&hashed),
                    mmcl_util::hash::file_sha256(&hashed),
                )
            })
            .await
            .map_err(|e| EngineError::Other(e.to_string())),
            Err(e) => Err(EngineError::Other(format!(
                "Could not download the mod from that URL to record its hash ({e}). Nothing was added."
            ))),
        };
        let _ = tokio::fs::remove_file(&file).await;
        let (sha1, sha256) = hashes?;
        match (sha1, sha256) {
            (Some(sha1), Some(sha256)) => Ok(ModHashes {
                sha1: sha1.to_ascii_lowercase(),
                sha256: sha256.to_ascii_lowercase(),
            }),
            _ => Err(EngineError::Other(
                "The downloaded mod could not be hashed. Nothing was added.".into(),
            )),
        }
    }

    /// [`Engine::remove_mod`].
    pub(super) async fn remove_mod_impl(&self, id: &PackId, identifier: &str) -> EngineResult<()> {
        let pack_name = self.require_installed(id)?.name();
        let wanted = identifier.trim().to_string();
        self.edit_authored(id, move |doc| {
            if doc.remove_mod(&wanted) {
                Ok(())
            } else {
                Err(EngineError::ModNotFound {
                    identifier: wanted.clone(),
                    pack_name,
                })
            }
        })
        .await
    }

    /// [`Engine::save_manifest`]: writes back the `file:` manifest of an installed pack (an
    /// authored or imported pack; a remote manifest is not ours to change).
    pub(super) async fn save_manifest_impl(&self, id: &PackId, json: &str) -> EngineResult<()> {
        let pack = self.require_installed(id)?;
        if self.inner.sessions.active_for_pack(id).is_some() {
            return Err(EngineError::PackInUse(pack.name()));
        }
        let file = pack
            .manifest_url()
            .and_then(file_url_path)
            .filter(|p| p.is_file())
            .ok_or_else(|| {
                EngineError::Other(mmcl_i18n::tf!("engine.error.manifestNotOwned", pack.name()))
            })?;
        let doc = ModPackDocument::from_json(json).map_err(doc_error)?;
        write_atomically(&file, doc.to_pretty_json().as_bytes()).await?;
        self.inner.packs.fetch_installed(false).await;
        self.emit(EngineEvent::PackChanged(id.clone()));
        self.emit(EngineEvent::PacksChanged);
        Ok(())
    }
}

/// Why a mod's local path is refused (Java `reject_unsafe_local_path`, plus the `:` and
/// reserved-name hardening): absolute, escaping, or with control characters.
pub fn unsafe_local_path(p: &str) -> Option<String> {
    let t = p.trim();
    if t.is_empty() {
        return Some(mmcl_i18n::t!("engine.error.localPath.required").into_owned());
    }
    let bytes: Vec<char> = t.chars().collect();
    if t.starts_with('/') || t.starts_with('\\') || bytes.get(1) == Some(&':') {
        return Some(mmcl_i18n::t!("engine.error.localPath.absolute").into_owned());
    }
    if t.split(['/', '\\']).any(|s| s == "..") {
        return Some(mmcl_i18n::t!("engine.error.localPath.escapes").into_owned());
    }
    if t.chars().any(|c| c == '\0' || c.is_control() || c == ':') {
        return Some(mmcl_i18n::t!("engine.error.localPath.illegalChar").into_owned());
    }
    let reserved = t.split(['/', '\\']).any(|seg| {
        let stem = seg.split('.').next().unwrap_or("").to_ascii_lowercase();
        mmcl_archive::WINDOWS_RESERVED_NAMES.contains(&stem.as_str())
    });
    if reserved {
        return Some(mmcl_i18n::t!("engine.error.localPath.illegalChar").into_owned());
    }
    None
}

/// The largest mod jar [`Engine::add_mod`] downloads to hash.
pub(crate) const MAX_MOD_BYTES: u64 = 512 * 1024 * 1024;

/// What [`Engine::add_mod`] recorded.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ModAdded {
    /// Where the jar installs on the next sync (`<pack root>/mods/<file>`).
    pub install_path: PathBuf,
    /// The jar's SHA-256, lower-case hex.
    pub sha256: String,
}

/// A downloaded jar's hashes, lower-case hex.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct ModHashes {
    pub(crate) sha1: String,
    pub(crate) sha256: String,
}

/// The jar's file name in the mods folder, or why the path is refused (issue 101).
///
/// A mod's local path is resolved against `mods/`, and the sync's cleanup only looks at that
/// folder's top level, so a leading `mods/` is dropped and any folder left after that
/// (including `mods/mods/x.jar`) is refused with where it would have landed.
pub fn mod_file_name(local_path: &str) -> EngineResult<String> {
    let name = mmcl_modpack::document::normalize_mod_local_path(local_path);
    if name.is_empty() {
        return Err(EngineError::Other(
            "localPath must name the jar, e.g. jei.jar.".into(),
        ));
    }
    if name.contains('/') {
        return Err(EngineError::Other(format!(
            "localPath is the jar's file name inside the pack's mods folder, e.g. jei.jar. \"{}\" would install to mods/{name}, a subfolder the game does not load mods from.",
            local_path.trim()
        )));
    }
    Ok(name)
}

/// Why a downloaded jar does not match the hashes the caller expected, if it does not.
pub(crate) fn mod_hash_mismatch(
    actual: &ModHashes,
    sha1: Option<&str>,
    sha256: Option<&str>,
) -> Option<String> {
    if let Some(want) = sha256.filter(|w| !w.eq_ignore_ascii_case(&actual.sha256)) {
        return Some(format!(
            "The jar at that URL has SHA-256 {}, not the expected {want}. Nothing was added.",
            actual.sha256
        ));
    }
    if let Some(want) = sha1.filter(|w| !w.eq_ignore_ascii_case(&actual.sha1)) {
        return Some(format!(
            "The jar at that URL has SHA-1 {}, not the expected {want}. Nothing was added.",
            actual.sha1
        ));
    }
    None
}

/// The manifest entry for a mod: SHA-256 as its primary hash with the SHA-1 kept beside it,
/// so the sync verifies strongest-first. Required on both sides.
pub(crate) fn mod_entry(
    mod_name: &str,
    remote_url: &str,
    file_name: &str,
    hashes: &ModHashes,
) -> FileEntry {
    let mut entry = FileEntry::new(
        mod_name.trim(),
        remote_url.trim(),
        file_name,
        hashes.sha256.as_str(),
        Some("sha256"),
        true,
        true,
    );
    entry.put_extra_hash("sha1", Some(&hashes.sha1));
    entry
}

/// Writes `bytes` to a sibling temp file and renames it over `target`.
pub(crate) async fn write_atomically(target: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let mut tmp = target.as_os_str().to_owned();
    tmp.push(".tmp");
    let tmp = PathBuf::from(tmp);
    tokio::fs::write(&tmp, bytes).await?;
    match tokio::fs::rename(&tmp, target).await {
        Ok(()) => Ok(()),
        Err(e) => {
            let _ = tokio::fs::remove_file(&tmp).await;
            Err(e)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn authored_manifests_must_sit_inside_the_dir() {
        let d = tempfile::tempdir().unwrap();
        let dir = d.path().join("mcp-manifests");
        let inside = url::Url::from_file_path(dir.join("created-x.json")).unwrap();
        assert!(resolve_authored_manifest(inside.as_str(), &dir).is_some());
        let sibling =
            url::Url::from_file_path(d.path().join("mcp-manifests-evil").join("x.json")).unwrap();
        assert!(resolve_authored_manifest(sibling.as_str(), &dir).is_none());
        let dir_url = url::Url::from_file_path(&dir).unwrap();
        assert!(resolve_authored_manifest(dir_url.as_str(), &dir).is_none());
        let escape = format!("{}/../other.json", dir_url.as_str().trim_end_matches('/'));
        assert!(resolve_authored_manifest(&escape, &dir).is_none());
        assert!(resolve_authored_manifest("https://example.com/x.json", &dir).is_none());
    }

    #[test]
    fn unsafe_local_paths_are_refused() {
        assert!(unsafe_local_path("").is_some());
        assert!(unsafe_local_path("/etc/passwd").is_some());
        assert!(unsafe_local_path("\\x").is_some());
        assert!(unsafe_local_path("C:\\x.jar").is_some());
        assert!(unsafe_local_path("mods/../../x.jar").is_some());
        assert!(unsafe_local_path("mods/a\u{7}.jar").is_some());
        assert!(unsafe_local_path("mods/a.jar:ads").is_some());
        assert!(unsafe_local_path("mods/CON.jar").is_some());
        assert!(unsafe_local_path("mods/jei.jar").is_none());
        assert!(unsafe_local_path("config/sub/a.toml").is_none());
    }
}
