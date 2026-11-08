// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Pack folders and the content-addressed image cache (Java `GameModPackEnvironment`).
//!
//! Images are cached once for all packs at `<root>/metadata/<sha1>.png`; each pack's
//! `.image_cache` sidecar maps its image URLs to those hashes. Images are transcoded to PNG
//! when they are not natively renderable, exactly as Java does, so both launchers compute the
//! same cache names.
//!
//! **Guard:** caching is serialised per environment, never on the pack itself (holding the
//! pack's lock across downloads froze the Java UI), and one bad URL in a multi-image slot only
//! skips that image.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, Ordering};

use indexmap::IndexSet;
use mmcl_core::GameMode;
use mmcl_i18n::tf;
use mmcl_manifest::pack::{
    BUNDLED_BACKGROUND_URL, BUNDLED_LOGO_URL, is_bundled_background_url, is_bundled_logo_url,
};
use parking_lot::Mutex;
use serde::{Deserialize, Serialize};

use crate::error::Result;
use crate::http::PackHttp;
use crate::managed::blocking;
use crate::pack::GameModPack;

/// The per-pack image sidecar.
pub const IMAGE_CACHE_FILE: &str = ".image_cache";

/// Bytes of the bundled default images, keyed by sentinel URL (registered by the UI layer that
/// ships them). Without a provider a bundled default resolves to "no file" and the UI renders
/// its own copy.
pub type BundledImageProvider = fn(&str) -> Option<&'static [u8]>;

static BUNDLED: OnceLock<BundledImageProvider> = OnceLock::new();

/// Registers the bundled-image provider. `false` when one is already set.
pub fn set_bundled_image_provider(p: BundledImageProvider) -> bool {
    BUNDLED.set(p).is_ok()
}

/// One URL-to-hash mapping.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct ImageRef {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sha1: Option<String>,
}

/// The `.image_cache` sidecar (legacy scalar fields folded into the lists on load).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct ImageCacheInfo {
    #[serde(rename = "logoUrl", skip_serializing_if = "Option::is_none")]
    pub logo_url: Option<String>,
    #[serde(rename = "logoSha1", skip_serializing_if = "Option::is_none")]
    pub logo_sha1: Option<String>,
    #[serde(rename = "backgroundUrl", skip_serializing_if = "Option::is_none")]
    pub background_url: Option<String>,
    #[serde(rename = "backgroundSha1", skip_serializing_if = "Option::is_none")]
    pub background_sha1: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub logos: Option<Vec<ImageRef>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub backgrounds: Option<Vec<ImageRef>>,
}

impl ImageCacheInfo {
    /// Folds the pre-3.6 scalar pairs into the lists and clears them.
    pub fn migrate_legacy(&mut self) {
        if self.logos.is_none() {
            let mut v = Vec::new();
            if let (Some(u), Some(s)) = (&self.logo_url, &self.logo_sha1) {
                v.push(ImageRef {
                    url: Some(u.clone()),
                    sha1: Some(s.clone()),
                });
            }
            self.logos = Some(v);
        }
        if self.backgrounds.is_none() {
            let mut v = Vec::new();
            if let (Some(u), Some(s)) = (&self.background_url, &self.background_sha1) {
                v.push(ImageRef {
                    url: Some(u.clone()),
                    sha1: Some(s.clone()),
                });
            }
            self.backgrounds = Some(v);
        }
        self.logo_url = None;
        self.logo_sha1 = None;
        self.background_url = None;
        self.background_sha1 = None;
    }

    fn list(&self, logo: bool) -> &[ImageRef] {
        let l = if logo { &self.logos } else { &self.backgrounds };
        l.as_deref().unwrap_or(&[])
    }
}

/// `s` when it is a 40-digit hex SHA-1, else `None`.
///
/// **Guard:** the declared `packLogoSha1` / `packBackgroundSha1` and the sidecar's hashes name
/// files under `metadata/`. A manifest is untrusted, so a "hash" like `../../x` would otherwise
/// read or write a `.png` outside the cache folder (Java joined it unchecked).
pub fn valid_sha1(s: Option<&str>) -> Option<&str> {
    let s = s?.trim();
    (s.len() == 40 && s.bytes().all(|b| b.is_ascii_hexdigit())).then_some(s)
}

fn sidecar_sha1<'a>(list: &'a [ImageRef], url: &str) -> Option<&'a str> {
    list.iter()
        .find(|r| r.url.as_deref() == Some(url) && valid_sha1(r.sha1.as_deref()).is_some())
        .and_then(|r| valid_sha1(r.sha1.as_deref()))
}

#[derive(Clone, Copy)]
enum Slot {
    Logo,
    Background,
}

impl Slot {
    fn is_logo(self) -> bool {
        matches!(self, Slot::Logo)
    }
    fn default_url(self) -> &'static str {
        match self {
            Slot::Logo => BUNDLED_LOGO_URL,
            Slot::Background => BUNDLED_BACKGROUND_URL,
        }
    }
    fn is_bundled(self, url: &str) -> bool {
        match self {
            Slot::Logo => is_bundled_logo_url(url),
            Slot::Background => is_bundled_background_url(url),
        }
    }
    fn temp_name(self) -> &'static str {
        match self {
            Slot::Logo => ".logo_download.tmp",
            Slot::Background => ".background_download.tmp",
        }
    }
    fn label(self) -> String {
        match self {
            Slot::Logo => mmcl_i18n::t!("modpack.image.label.logo").into_owned(),
            Slot::Background => mmcl_i18n::t!("modpack.image.label.background").into_owned(),
        }
    }
    fn failed_key(self) -> &'static str {
        match self {
            Slot::Logo => "log.gameModPackEnvironment.logoSourceFailed",
            Slot::Background => "log.gameModPackEnvironment.backgroundSourceFailed",
        }
    }
}

/// Per-pack lazy helper for folders and images.
#[derive(Debug, Default)]
pub struct PackEnvironment {
    did_cache: AtomicBool,
    lock: tokio::sync::Mutex<()>,
    sidecar: Mutex<Option<ImageCacheInfo>>,
}

/// Outcome of resolving one image source.
enum Resolved {
    Sha(String),
    /// A bundled default with no bytes available: the UI shows its own copy.
    BundledWithoutBytes,
}

impl PackEnvironment {
    pub fn new() -> Self {
        Self::default()
    }

    /// Creates `bin`, `mods`, `config`, `bin/natives` and, in client mode, `resourcepacks`
    /// and `shaderpacks`. Refuses a pack whose name sanitizes to nothing.
    pub fn prepare(&self, pack: &GameModPack) -> Result<()> {
        let root = pack.checked_pack_root()?;
        fs::create_dir_all(&root)?;
        let mut dirs = vec![
            root.join("bin"),
            root.join("mods"),
            root.join("config"),
            root.join("bin").join("natives"),
        ];
        if pack.paths().mode() == GameMode::Client {
            dirs.push(root.join("resourcepacks"));
            dirs.push(root.join("shaderpacks"));
        }
        for d in dirs {
            if !d.exists() {
                fs::create_dir(&d)?;
            }
        }
        Ok(())
    }

    /// `true` once [`PackEnvironment::cache_images`] succeeded.
    pub fn did_cache_images(&self) -> bool {
        self.did_cache.load(Ordering::SeqCst)
    }

    fn load_sidecar(&self, pack: &GameModPack) -> ImageCacheInfo {
        let mut slot = self.sidecar.lock();
        if let Some(c) = slot.as_ref() {
            return c.clone();
        }
        let file = pack.pack_root().join(IMAGE_CACHE_FILE);
        let mut info = if file.exists() {
            match fs::read_to_string(&file).map(|t| serde_json::from_str::<ImageCacheInfo>(&t)) {
                Ok(Ok(c)) => c,
                _ => {
                    mmcl_log::warn_silent(&tf!(
                        "log.gameModPackEnvironment.imageCacheReadFailed",
                        pack.name()
                    ));
                    ImageCacheInfo::default()
                }
            }
        } else {
            ImageCacheInfo::default()
        };
        info.migrate_legacy();
        *slot = Some(info.clone());
        info
    }

    fn save_sidecar(&self, pack: &GameModPack, info: ImageCacheInfo) {
        let file = pack.pack_root().join(IMAGE_CACHE_FILE);
        let json = serde_json::to_value(&info)
            .map(|v| mmcl_manifest::gson::to_compact_string(&v))
            .unwrap_or_default();
        let res = fs::create_dir_all(pack.pack_root()).and_then(|_| fs::write(&file, json));
        match res {
            Ok(()) => *self.sidecar.lock() = Some(info),
            Err(_) => mmcl_log::warn_silent(&tf!(
                "log.gameModPackEnvironment.imageCacheSaveFailed",
                pack.name()
            )),
        }
    }

    fn slot_paths(&self, pack: &GameModPack, slot: Slot) -> Vec<PathBuf> {
        let m = pack.manifest();
        let (urls, declared) = match slot {
            Slot::Logo => (m.logo_urls(), m.pack_logo_sha1.as_deref()),
            Slot::Background => (m.background_urls(), m.pack_background_sha1.as_deref()),
        };
        let sidecar = self.load_sidecar(pack);
        resolve_slot_paths(
            urls,
            declared,
            slot.default_url(),
            sidecar.list(slot.is_logo()),
            &pack.paths().metadata_dir(),
        )
    }

    /// Every cached logo path (no network). Missing images are absent.
    pub fn logo_paths_raw(&self, pack: &GameModPack) -> Vec<PathBuf> {
        self.slot_paths(pack, Slot::Logo)
    }

    /// Every cached background path (no network).
    pub fn background_paths_raw(&self, pack: &GameModPack) -> Vec<PathBuf> {
        self.slot_paths(pack, Slot::Background)
    }

    fn primary(&self, pack: &GameModPack, slot: Slot) -> PathBuf {
        if let Some(p) = self.slot_paths(pack, slot).into_iter().next() {
            return p;
        }
        let m = pack.manifest();
        let (declared, prefix) = match slot {
            Slot::Logo => (m.pack_logo_sha1.as_deref(), "logo_"),
            Slot::Background => (m.pack_background_sha1.as_deref(), "background_"),
        };
        let name = match valid_sha1(declared) {
            Some(s) => format!("{s}.png"),
            None => format!("{prefix}{}.png", pack.sanitized_name()),
        };
        pack.paths().metadata_dir().join(name)
    }

    /// The primary logo path (may not exist; callers check).
    pub fn logo_path_raw(&self, pack: &GameModPack) -> PathBuf {
        self.primary(pack, Slot::Logo)
    }

    /// The primary background path (may not exist).
    pub fn background_path_raw(&self, pack: &GameModPack) -> PathBuf {
        self.primary(pack, Slot::Background)
    }

    /// Logo paths, caching the images first when that has not succeeded yet.
    pub async fn logo_paths(&self, pack: &GameModPack, http: &dyn PackHttp) -> Vec<PathBuf> {
        if !self.did_cache_images() {
            self.cache_images(pack, http).await;
        }
        self.logo_paths_raw(pack)
    }

    /// Background paths, caching first when needed.
    pub async fn background_paths(&self, pack: &GameModPack, http: &dyn PackHttp) -> Vec<PathBuf> {
        if !self.did_cache_images() {
            self.cache_images(pack, http).await;
        }
        self.background_paths_raw(pack)
    }

    /// Downloads and caches every logo and background. Returns success; failures are logged.
    pub async fn cache_images(&self, pack: &GameModPack, http: &dyn PackHttp) -> bool {
        let _g = self.lock.lock().await;
        let res = async {
            self.cache_slot(pack, http, Slot::Logo).await?;
            self.cache_slot(pack, http, Slot::Background).await
        }
        .await;
        match res {
            Ok(()) => {
                self.did_cache.store(true, Ordering::SeqCst);
                true
            }
            Err(e) => {
                self.did_cache.store(false, Ordering::SeqCst);
                mmcl_log::warn_silent(&tf!(
                    "log.gameModPackEnvironment.imageDownloadFailed",
                    pack.friendly_name().unwrap_or_default()
                ));
                mmcl_log::warn_silent(&e);
                false
            }
        }
    }

    async fn cache_slot(
        &self,
        pack: &GameModPack,
        http: &dyn PackHttp,
        slot: Slot,
    ) -> std::result::Result<(), String> {
        let m = pack.manifest();
        let (urls, declared) = match slot {
            Slot::Logo => (m.logo_urls(), m.pack_logo_sha1.as_deref()),
            Slot::Background => (m.background_urls(), m.pack_background_sha1.as_deref()),
        };
        let declared = valid_sha1(declared).map(str::to_string);
        let declared_urls: Vec<String> = urls
            .iter()
            .cloned()
            .collect::<IndexSet<_>>()
            .into_iter()
            .collect();
        let mut cache = self.load_sidecar(pack);
        let existing = cache.list(slot.is_logo()).to_vec();
        let meta = pack.paths().metadata_dir();
        let mut resolved: Vec<ImageRef> = Vec::new();
        let mut any_bundled = false;
        let mut last_error: Option<String> = None;
        for (i, url) in declared_urls.iter().enumerate() {
            let r = self
                .resolve_one(
                    pack,
                    http,
                    slot,
                    url,
                    if i == 0 { declared.as_deref() } else { None },
                    &existing,
                    &meta,
                )
                .await;
            match r {
                Ok(Resolved::Sha(sha)) => resolved.push(ImageRef {
                    url: Some(url.clone()),
                    sha1: Some(sha),
                }),
                Ok(Resolved::BundledWithoutBytes) => any_bundled = true,
                Err(e) => {
                    mmcl_log::warn_silent(&tf!(slot.failed_key(), pack.name(), url, &e));
                    last_error = Some(e);
                }
            }
        }
        if resolved.is_empty() && !any_bundled {
            match self
                .resolve_one(pack, http, slot, slot.default_url(), None, &existing, &meta)
                .await
            {
                Ok(Resolved::Sha(sha)) => resolved.push(ImageRef {
                    url: Some(slot.default_url().into()),
                    sha1: Some(sha),
                }),
                Ok(Resolved::BundledWithoutBytes) => any_bundled = true,
                Err(e) => last_error = Some(e),
            }
        }
        if resolved.is_empty() && !any_bundled {
            return Err(tf!(
                "log.gameModPackEnvironment.allSourcesFailed",
                slot.label(),
                last_error.unwrap_or_default()
            ));
        }
        if slot.is_logo() {
            cache.logos = Some(resolved);
        } else {
            cache.backgrounds = Some(resolved);
        }
        self.save_sidecar(pack, cache);
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    async fn resolve_one(
        &self,
        pack: &GameModPack,
        http: &dyn PackHttp,
        slot: Slot,
        url: &str,
        declared_sha1: Option<&str>,
        existing: &[ImageRef],
        meta: &Path,
    ) -> std::result::Result<Resolved, String> {
        if slot.is_bundled(url) {
            let Some(bytes) = BUNDLED.get().and_then(|p| p(url)) else {
                return Ok(Resolved::BundledWithoutBytes);
            };
            let sha = mmcl_util::hash::sha1_hex(bytes);
            let dest = meta.join(format!("{sha}.png"));
            if !dest.exists() {
                fs::create_dir_all(meta).map_err(|e| e.to_string())?;
                fs::write(&dest, bytes).map_err(|e| e.to_string())?;
            }
            return Ok(Resolved::Sha(sha));
        }
        // Per-URL HTTPS requirement (Java aborted the whole slot here).
        if !url.trim().to_ascii_lowercase().starts_with("https://") {
            return Err(tf!("managedFile.error.nonHttps", url));
        }
        if let Some(declared) = valid_sha1(declared_sha1) {
            let dest = meta.join(format!("{declared}.png"));
            let tmp = pack.pack_root().join(slot.temp_name());
            return resolve_declared_image(http, &dest, declared, url, &tmp, meta)
                .await
                .map(Resolved::Sha);
        }
        if let Some(known) = sidecar_sha1(existing, url)
            && meta.join(format!("{known}.png")).exists()
        {
            return Ok(Resolved::Sha(known.to_string()));
        }
        let tmp = pack.pack_root().join(slot.temp_name());
        download_and_hash(http, url, &tmp, meta)
            .await
            .map(Resolved::Sha)
    }
}

/// The raw (no network) path list of one slot.
pub fn resolve_slot_paths(
    urls: &[String],
    declared_sha1: Option<&str>,
    default_url: &str,
    sidecar: &[ImageRef],
    metadata_dir: &Path,
) -> Vec<PathBuf> {
    let defaults = [default_url.to_string()];
    let list: &[String] = if urls.is_empty() { &defaults } else { urls };
    list.iter()
        .enumerate()
        .filter_map(|(i, url)| {
            let sha = sidecar_sha1(sidecar, url)
                .or_else(|| (i == 0).then(|| valid_sha1(declared_sha1)).flatten())?;
            Some(metadata_dir.join(format!("{sha}.png")))
        })
        .collect()
}

/// Resolves an image with a declared SHA-1; returns the hash it is cached under.
///
/// **Guard:** `metadata/` is shared by every pack and named by content. Java downloaded
/// straight into `metadata/<declared>.png` without checking the bytes, so a manifest that
/// declared another pack's hash with its own URL put its image under that pack's name, and the
/// "exists and renders" branch then kept it for good. The download now goes to a temp file and
/// takes the declared name only when its bytes hash to it; otherwise it is cached under its own
/// hash, so the image still shows and nothing else is overwritten.
async fn resolve_declared_image(
    http: &dyn PackHttp,
    dest: &Path,
    declared: &str,
    url: &str,
    tmp: &Path,
    meta: &Path,
) -> std::result::Result<String, String> {
    let (d, decl) = (dest.to_path_buf(), declared.to_string());
    let matches =
        blocking(move || mmcl_util::hash::verify(&d, &decl, mmcl_util::hash::HashAlgorithm::Sha1))
            .await
            .map_err(|e| e.to_string())?;
    if matches {
        let d = dest.to_path_buf();
        blocking(move || {
            if !mmcl_archive::image::is_natively_decodable(&d) {
                mmcl_archive::image::ensure_decodable(&d);
            }
        })
        .await
        .map_err(|e| e.to_string())?;
        return Ok(declared.to_string());
    }
    // Transcoded earlier: its hash no longer matches the WebP source, but it renders.
    if dest.exists() && mmcl_archive::image::is_natively_decodable(dest) {
        return Ok(declared.to_string());
    }
    if let Some(p) = tmp.parent() {
        fs::create_dir_all(p).map_err(|e| e.to_string())?;
    }
    http.download(url, tmp, None)
        .await
        .map_err(|e| e.to_string())?;
    let (t, decl) = (tmp.to_path_buf(), declared.to_string());
    let matches =
        blocking(move || mmcl_util::hash::verify(&t, &decl, mmcl_util::hash::HashAlgorithm::Sha1))
            .await
            .map_err(|e| e.to_string())?;
    if !matches {
        mmcl_log::warn_silent(&tf!(
            "log.gameModPackEnvironment.declaredHashMismatch",
            url,
            declared
        ));
        return hash_and_store(url, tmp, meta).await;
    }
    let t = tmp.to_path_buf();
    blocking(move || mmcl_archive::image::ensure_decodable(&t))
        .await
        .map_err(|e| e.to_string())?;
    fs::create_dir_all(meta).map_err(|e| e.to_string())?;
    if dest.exists() {
        let _ = fs::remove_file(tmp);
    } else if fs::rename(tmp, dest).is_err() {
        let copied = fs::copy(tmp, dest).map(|_| ());
        let _ = fs::remove_file(tmp);
        copied.map_err(|e| e.to_string())?;
    }
    Ok(declared.to_string())
}

async fn download_and_hash(
    http: &dyn PackHttp,
    url: &str,
    tmp: &Path,
    meta: &Path,
) -> std::result::Result<String, String> {
    if let Some(p) = tmp.parent() {
        fs::create_dir_all(p).map_err(|e| e.to_string())?;
    }
    http.download(url, tmp, None)
        .await
        .map_err(|e| e.to_string())?;
    hash_and_store(url, tmp, meta).await
}

/// Transcodes the downloaded `tmp` when needed, then moves it to `metadata/<sha1>.png`.
async fn hash_and_store(url: &str, tmp: &Path, meta: &Path) -> std::result::Result<String, String> {
    let t = tmp.to_path_buf();
    let sha = blocking(move || {
        mmcl_archive::image::ensure_decodable(&t);
        mmcl_util::hash::try_file_hash(&t, mmcl_util::hash::HashAlgorithm::Sha1)
    })
    .await
    .map_err(|e| e.to_string())?
    .map_err(|e| {
        tf!(
            "log.gameModPackEnvironment.imageHashFailed",
            url,
            e.to_string()
        )
    })?;
    let dest = meta.join(format!("{sha}.png"));
    fs::create_dir_all(meta).map_err(|e| e.to_string())?;
    if dest.exists() {
        let _ = fs::remove_file(tmp);
    } else if fs::rename(tmp, &dest).is_err() {
        fs::copy(tmp, &dest).map_err(|e| e.to_string())?;
        let _ = fs::remove_file(tmp);
    }
    Ok(sha)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::http::fake::FakeHttp;
    use mmcl_core::DataPaths;
    use mmcl_manifest::{PackManifest, StringOrArray};
    use std::sync::Arc;

    /// A 1x1 PNG.
    const PNG: &[u8] = &[
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44,
        0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F,
        0x15, 0xC4, 0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0x00,
        0x01, 0x00, 0x00, 0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00, 0x00, 0x00, 0x00, 0x49,
        0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82,
    ];

    fn pack(root: &Path, logos: &[&str], bgs: &[&str], logo_sha: Option<&str>) -> Arc<GameModPack> {
        let paths = Arc::new(DataPaths::at_root(root, GameMode::Client));
        let m = PackManifest {
            pack_name: Some("Img Pack".into()),
            pack_version: Some("1".into()),
            pack_logo_url: Some(StringOrArray::of(logos.iter().copied())),
            pack_background_url: Some(StringOrArray::of(bgs.iter().copied())),
            pack_logo_sha1: logo_sha.map(str::to_string),
            ..Default::default()
        };
        GameModPack::from_manifest(paths, m, Some("https://x/m.json".into()), None)
    }

    #[test]
    fn legacy_sidecar_migration() {
        let mut c: ImageCacheInfo = serde_json::from_str(
            r#"{"logoUrl":"https://a","logoSha1":"aa","backgroundUrl":"https://b"}"#,
        )
        .unwrap();
        c.migrate_legacy();
        assert_eq!(
            c.logos.as_ref().unwrap(),
            &vec![ImageRef {
                url: Some("https://a".into()),
                sha1: Some("aa".into())
            }]
        );
        assert!(c.backgrounds.as_ref().unwrap().is_empty());
        let out = mmcl_manifest::gson::to_compact_string(&serde_json::to_value(&c).unwrap());
        assert_eq!(
            out,
            r#"{"logos":[{"url":"https://a","sha1":"aa"}],"backgrounds":[]}"#
        );
    }

    #[test]
    fn raw_paths_use_sidecar_then_declared_for_first() {
        let meta = Path::new("/m");
        let (bb, dd) = ("b".repeat(40), "d".repeat(40));
        let side = vec![
            ImageRef {
                url: Some("https://b".into()),
                sha1: Some(bb.clone()),
            },
            // A hostile sidecar hash is ignored.
            ImageRef {
                url: Some("https://c".into()),
                sha1: Some("../../evil".into()),
            },
        ];
        let urls = vec![
            "https://a".to_string(),
            "https://b".to_string(),
            "https://c".to_string(),
        ];
        assert_eq!(
            resolve_slot_paths(&urls, Some(&dd), BUNDLED_LOGO_URL, &side, meta),
            vec![
                meta.join(format!("{dd}.png")),
                meta.join(format!("{bb}.png"))
            ]
        );
        // A declared "hash" that is a path is never joined.
        assert!(
            resolve_slot_paths(&urls[..1], Some("../../x"), BUNDLED_LOGO_URL, &[], meta).is_empty()
        );
        assert!(resolve_slot_paths(&[], None, BUNDLED_LOGO_URL, &[], meta).is_empty());
    }

    #[tokio::test]
    async fn one_bad_url_skips_only_that_image() {
        let d = tempfile::tempdir().unwrap();
        let p = pack(
            d.path(),
            &["https://img/a.png"],
            &[
                "http://insecure/b.png",
                "https://img/c.png",
                "https://img/missing.png",
            ],
            None,
        );
        let http = FakeHttp::new();
        http.body("https://img/a.png", PNG.to_vec());
        http.body("https://img/c.png", PNG.to_vec());
        assert!(p.environment().cache_images(&p, http.as_ref()).await);
        let bgs = p.environment().background_paths_raw(&p);
        assert_eq!(bgs.len(), 1);
        assert!(bgs[0].exists());
        let sha = mmcl_util::hash::sha1_hex(PNG);
        assert_eq!(bgs[0], d.path().join("metadata").join(format!("{sha}.png")));
        assert_eq!(p.environment().logo_paths_raw(&p), vec![bgs[0].clone()]);
        // Cached: a second run does not download the resolved images again (the failing one
        // is retried).
        let before = http.count("DL https://img/a.png") + http.count("DL https://img/c.png");
        assert!(p.environment().cache_images(&p, http.as_ref()).await);
        assert_eq!(
            http.count("DL https://img/a.png") + http.count("DL https://img/c.png"),
            before
        );
    }

    #[tokio::test]
    async fn declared_sha_trusts_a_local_transcoded_file() {
        let d = tempfile::tempdir().unwrap();
        // Declared sha of the (WebP) source; the file on disk is a PNG with another hash.
        let p = pack(
            d.path(),
            &["https://img/a.webp"],
            &[],
            Some("0123456789abcdef0123456789abcdef01234567"),
        );
        let meta = d.path().join("metadata");
        fs::create_dir_all(&meta).unwrap();
        fs::write(
            meta.join("0123456789abcdef0123456789abcdef01234567.png"),
            PNG,
        )
        .unwrap();
        let http = FakeHttp::new();
        assert!(p.environment().cache_images(&p, http.as_ref()).await);
        assert_eq!(http.count("DL"), 0, "no re-download");
    }

    #[tokio::test]
    async fn a_wrong_declared_sha_cannot_claim_another_images_cache_name() {
        let d = tempfile::tempdir().unwrap();
        // Another pack's logo hash, declared with this pack's own image URL.
        let victim = "0123456789abcdef0123456789abcdef01234567";
        let p = pack(d.path(), &["https://img/a.png"], &[], Some(victim));
        let http = FakeHttp::new();
        http.body("https://img/a.png", PNG.to_vec());
        assert!(p.environment().cache_images(&p, http.as_ref()).await);
        let meta = d.path().join("metadata");
        assert!(!meta.join(format!("{victim}.png")).exists(), "not poisoned");
        let own = meta.join(format!("{}.png", mmcl_util::hash::sha1_hex(PNG)));
        assert!(own.is_file());
        assert_eq!(p.environment().logo_paths_raw(&p), vec![own]);
        // A matching declared hash still takes the declared name.
        let d2 = tempfile::tempdir().unwrap();
        let sha = mmcl_util::hash::sha1_hex(PNG);
        let p2 = pack(d2.path(), &["https://img/a.png"], &[], Some(&sha));
        assert!(p2.environment().cache_images(&p2, http.as_ref()).await);
        assert!(
            d2.path()
                .join("metadata")
                .join(format!("{sha}.png"))
                .is_file()
        );
    }

    #[tokio::test]
    async fn hostile_declared_sha_never_writes_outside_the_cache() {
        let d = tempfile::tempdir().unwrap();
        let root = d.path().join("root");
        let p = pack(&root, &["https://img/a.png"], &[], Some("../../escaped"));
        let http = FakeHttp::new();
        http.body("https://img/a.png", PNG.to_vec());
        assert!(p.environment().cache_images(&p, http.as_ref()).await);
        assert!(!d.path().join("escaped.png").exists());
        assert!(!root.join("escaped.png").exists());
        // The image is cached under its real hash instead.
        let want = root
            .join("metadata")
            .join(format!("{}.png", mmcl_util::hash::sha1_hex(PNG)));
        assert_eq!(p.environment().logo_paths_raw(&p), vec![want.clone()]);
        assert_eq!(p.environment().logo_path_raw(&p), want);
    }

    #[tokio::test]
    async fn all_sources_failing_is_an_error_but_bundled_default_is_not() {
        let d = tempfile::tempdir().unwrap();
        let p = pack(d.path(), &["https://img/none.png"], &[], None);
        let http = FakeHttp::new();
        // Logo fails; the bundled default has no bytes registered, so the slot succeeds empty.
        assert!(p.environment().cache_images(&p, http.as_ref()).await);
        assert!(p.environment().logo_paths_raw(&p).is_empty());
        assert!(p.environment().did_cache_images());
    }

    #[test]
    fn prepare_creates_folders_and_refuses_empty_names() {
        let d = tempfile::tempdir().unwrap();
        let p = pack(d.path(), &[], &[], None);
        p.prepare_environment().unwrap();
        for f in [
            "bin",
            "mods",
            "config",
            "bin/natives",
            "resourcepacks",
            "shaderpacks",
        ] {
            assert!(p.pack_root().join(f).is_dir(), "{f}");
        }
        let paths = Arc::new(DataPaths::at_root(d.path(), GameMode::Server));
        let bad = GameModPack::from_manifest(
            paths.clone(),
            PackManifest {
                pack_name: Some("日本".into()),
                ..Default::default()
            },
            None,
            None,
        );
        assert!(bad.prepare_environment().is_err());
        let srv = GameModPack::from_manifest(
            paths,
            PackManifest {
                pack_name: Some("Srv".into()),
                ..Default::default()
            },
            None,
            None,
        );
        srv.prepare_environment().unwrap();
        assert!(!srv.pack_root().join("resourcepacks").exists());
    }
}
