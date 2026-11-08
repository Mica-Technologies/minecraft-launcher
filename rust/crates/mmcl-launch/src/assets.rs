// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Game assets (the I/O half of Java `GameAssetManifest`): the shared asset index and object
//! store under `shared/assets`, the flat legacy trees (`assets/virtual/<id>`, `resources/`),
//! and the one-shot cleanup of the old per-pack store.
//!
//! Fix over Java (B-3): the index is managed with `assetIndex.sha1`, so a republished index is
//! refreshed.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use mmcl_i18n::{t, tf};
use mmcl_mojang::asset_index::{AssetIndex, LegacyLayout, index_file_name};
use mmcl_mojang::client_json::ClientJson;
use mmcl_progress::ProgressSink;

use crate::batch::{BATCH_TIMEOUT, BatchKeys, blocking, join_rel, run_batch, spec};
use crate::error::{LaunchError, Result};
use crate::lerr;
use crate::run::PackRun;

/// The index facts a launch needs.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AssetInfo {
    /// `assetIndex.id` (`${assets_index_name}`).
    pub index_id: String,
    /// Which flat legacy tree the index asks for.
    pub layout: LegacyLayout,
}

/// `<pack>/assets/virtual/<id>`.
pub fn virtual_assets_path(pack_root: &Path, index_id: &str) -> PathBuf {
    pack_root.join("assets").join("virtual").join(index_id)
}

/// `<pack>/resources`.
pub fn resources_path(pack_root: &Path) -> PathBuf {
    pack_root.join("resources")
}

/// `${game_assets}`: the virtual tree, else the resources tree, else the shared root.
pub fn game_assets_path(shared_root: &Path, pack_root: &Path, info: Option<&AssetInfo>) -> PathBuf {
    match info {
        Some(AssetInfo {
            index_id,
            layout: LegacyLayout::Virtual,
        }) => virtual_assets_path(pack_root, index_id),
        Some(AssetInfo {
            layout: LegacyLayout::MapToResources,
            ..
        }) => resources_path(pack_root),
        _ => shared_root.to_path_buf(),
    }
}

/// Deletes the pre-shared-store `assets/objects` and `assets/indexes` of a pack in the
/// background (it can be tens of thousands of files). Never touches `assets/virtual`.
pub fn cleanup_legacy_per_pack_tree(pack_root: &Path) {
    let dirs = [
        pack_root.join("assets").join("objects"),
        pack_root.join("assets").join("indexes"),
    ];
    let job = move || {
        for d in dirs {
            if !d.exists() {
                continue;
            }
            if let Err(e) = fs::remove_dir_all(&d) {
                mmcl_log::warn_silent(&tf!(
                    "log.assetManifest.cleanLegacyPathError",
                    d.display().to_string(),
                    e.to_string()
                ));
            }
        }
    };
    match tokio::runtime::Handle::try_current() {
        Ok(h) => {
            h.spawn_blocking(job);
        }
        Err(_) => {
            std::thread::spawn(job);
        }
    }
}

/// Verifies the asset index and every object (shared pool, 30-minute bound), then builds the
/// legacy flat tree when the index asks for one.
pub async fn download_assets(
    run: &PackRun,
    client: &ClientJson,
    sink: &Arc<dyn ProgressSink>,
) -> Result<AssetInfo> {
    let root = run.root()?;
    cleanup_legacy_per_pack_tree(&root);
    let air = client.asset_index()?;
    let shared = run.env.paths.shared_assets_dir();
    let index_path = shared.join("indexes").join(index_file_name(&air.id));
    let index_file = run.managed_at(spec(&air.url, "", air.sha1.as_deref()), &index_path);
    index_file
        .update(&run.ctx, run.env.http.as_ref())
        .await
        .map_err(|e| lerr!("minecraft.error.assetsFailed", e.to_string()))?;
    let p = index_path.clone();
    let bytes = blocking(move || fs::read(p)).await??;
    let index = AssetIndex::parse(&bytes)?;
    run.check_cancelled()?;

    let objects = shared.join("objects");
    let n = index.objects.len().max(1) as f64;
    let label = t!("VERIFIED_ASSET_PROGRESS_TEXT").into_owned();
    let jobs = index
        .objects
        .values()
        .map(|obj| {
            let mf = run.managed(
                spec(&obj.url(), &obj.relative_path(), Some(&obj.hash)),
                &objects,
                Some(sink.as_ref()),
            );
            let (ctx, http, sink, label) = (
                run.ctx.clone(),
                run.env.http.clone(),
                sink.clone(),
                label.clone(),
            );
            let fut: futures::future::BoxFuture<'static, Result<()>> = Box::pin(async move {
                mf.update(&ctx, http.as_ref()).await?;
                sink.submit_progress(&format!("{label} {}", mf.file_name()), 50.0 / n);
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
            timeout: "minecraft.error.assetsTimedOut",
            interrupted: "minecraft.error.assetsInterrupted",
            failed: Some("minecraft.error.assetsFailed"),
            pass_modpack: false,
        },
    )
    .await?;

    let info = AssetInfo {
        index_id: air.id.clone(),
        layout: index.legacy_layout(),
    };
    let dest = match info.layout {
        LegacyLayout::None => None,
        LegacyLayout::Virtual => Some(virtual_assets_path(&root, &info.index_id)),
        LegacyLayout::MapToResources => Some(resources_path(&root)),
    };
    if let Some(dest) = dest {
        blocking(move || materialize_virtual_tree(&index, &objects, &dest)).await??;
    }
    Ok(info)
}

/// Copies every object of `index` from the hashed store into `dest` under its asset name.
/// Missing sources are skipped (one stale entry must not block a launch), same-length
/// destinations are kept, names that would leave `dest` are skipped, copies keep the source's
/// modified time, and a failed copy is fatal.
pub fn materialize_virtual_tree(
    index: &AssetIndex,
    objects: &Path,
    dest: &Path,
) -> Result<(usize, usize)> {
    let base = mmcl_core::paths::normalize_lexical(
        &std::path::absolute(dest).unwrap_or_else(|_| dest.to_path_buf()),
    );
    let (mut copied, mut skipped) = (0usize, 0usize);
    for (name, obj) in &index.objects {
        let src = join_rel(objects, &obj.relative_path());
        let Ok(src_meta) = fs::metadata(&src) else {
            continue;
        };
        if name.contains('\0') || name.starts_with(['/', '\\']) || name.contains(':') {
            continue;
        }
        let target = mmcl_core::paths::normalize_lexical(&join_rel(&base, name));
        if !target.starts_with(&base) || target == base {
            continue;
        }
        if fs::metadata(&target).is_ok_and(|m| m.len() == src_meta.len()) {
            skipped += 1;
            continue;
        }
        let res = (|| -> std::io::Result<()> {
            if let Some(p) = target.parent() {
                fs::create_dir_all(p)?;
            }
            fs::copy(&src, &target)?;
            if let Ok(m) = src_meta.modified() {
                let _ = fs::File::options()
                    .write(true)
                    .open(&target)
                    .and_then(|f| f.set_modified(m));
            }
            Ok(())
        })();
        if let Err(e) = res {
            mmcl_log::warn_silent(&e.to_string());
            return Err(LaunchError::Localized {
                key: "minecraft.error.assetMaterializeFailed",
                args: vec![name.clone()],
            });
        }
        copied += 1;
    }
    mmcl_log::debug(&tf!(
        "log.assetManifest.virtualTreeReady",
        dest.display().to_string(),
        copied.to_string(),
        skipped.to_string()
    ));
    Ok((copied, skipped))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn index(json: &str) -> AssetIndex {
        AssetIndex::parse(json.as_bytes()).unwrap()
    }

    #[test]
    fn virtual_tree_copies_skips_and_contains() {
        let d = tempfile::tempdir().unwrap();
        let objects = d.path().join("objects");
        let h1 = "a".repeat(40);
        let h2 = "b".repeat(40);
        fs::create_dir_all(objects.join("aa")).unwrap();
        fs::write(objects.join("aa").join(&h1), b"one").unwrap();
        let idx = index(&format!(
            r#"{{"virtual":true,"objects":{{
                "sounds/x.ogg":{{"hash":"{h1}"}},
                "missing.ogg":{{"hash":"{h2}"}},
                "../../escape.ogg":{{"hash":"{h1}"}}}}}}"#
        ));
        let dest = d.path().join("pack/assets/virtual/legacy");
        assert_eq!(
            materialize_virtual_tree(&idx, &objects, &dest).unwrap(),
            (1, 0)
        );
        assert_eq!(fs::read(dest.join("sounds/x.ogg")).unwrap(), b"one");
        assert!(!d.path().join("pack/escape.ogg").exists());
        assert!(!d.path().join("escape.ogg").exists());
        // Second run: same length, skipped.
        assert_eq!(
            materialize_virtual_tree(&idx, &objects, &dest).unwrap(),
            (0, 1)
        );
        let m_src = fs::metadata(objects.join("aa").join(&h1))
            .unwrap()
            .modified()
            .unwrap();
        let m_dst = fs::metadata(dest.join("sounds/x.ogg"))
            .unwrap()
            .modified()
            .unwrap();
        assert_eq!(m_src, m_dst);
    }

    #[test]
    fn game_assets_path_choice() {
        let (s, p) = (Path::new("/s"), Path::new("/p"));
        let i = |layout| AssetInfo {
            index_id: "legacy".into(),
            layout,
        };
        assert_eq!(
            game_assets_path(s, p, Some(&i(LegacyLayout::Virtual))),
            p.join("assets/virtual/legacy")
        );
        assert_eq!(
            game_assets_path(s, p, Some(&i(LegacyLayout::MapToResources))),
            p.join("resources")
        );
        assert_eq!(game_assets_path(s, p, Some(&i(LegacyLayout::None))), s);
        assert_eq!(game_assets_path(s, p, None), s);
    }

    #[tokio::test]
    async fn legacy_cleanup_keeps_virtual() {
        let d = tempfile::tempdir().unwrap();
        for f in ["objects/aa", "indexes", "virtual/legacy"] {
            fs::create_dir_all(d.path().join("assets").join(f)).unwrap();
        }
        cleanup_legacy_per_pack_tree(d.path());
        for _ in 0..100 {
            if !d.path().join("assets/objects").exists()
                && !d.path().join("assets/indexes").exists()
            {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
        assert!(!d.path().join("assets/objects").exists());
        assert!(!d.path().join("assets/indexes").exists());
        assert!(d.path().join("assets/virtual/legacy").exists());
    }
}
