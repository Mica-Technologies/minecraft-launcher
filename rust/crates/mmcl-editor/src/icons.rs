// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Images the editor shows: Modrinth result icons (a process-wide 128-entry LRU keyed by slug)
//! and the logo / background previews. Decoding and scaling are GPUI's (`img` with
//! `object_fit`), so the cache holds the encoded bytes as a [`gpui::Image`].

use std::collections::VecDeque;
use std::sync::{Arc, LazyLock};

use gpui::{Image, ImageFormat};
use parking_lot::Mutex;

use crate::backend::{EditorBackend, NetError};

/// Icon cache capacity.
pub const ICON_CACHE_CAPACITY: usize = 128;

/// A small least-recently-used map.
#[derive(Debug)]
pub struct Lru<V> {
    capacity: usize,
    entries: VecDeque<(String, V)>,
}

impl<V: Clone> Lru<V> {
    /// An empty cache.
    pub fn new(capacity: usize) -> Lru<V> {
        Lru {
            capacity: capacity.max(1),
            entries: VecDeque::new(),
        }
    }

    /// The value for `key`, marking it most recently used.
    pub fn get(&mut self, key: &str) -> Option<V> {
        let i = self.entries.iter().position(|(k, _)| k == key)?;
        let e = self.entries.remove(i)?;
        let v = e.1.clone();
        self.entries.push_back(e);
        Some(v)
    }

    /// Inserts or replaces, evicting the least recently used entry when full.
    pub fn put(&mut self, key: &str, value: V) {
        if let Some(i) = self.entries.iter().position(|(k, _)| k == key) {
            self.entries.remove(i);
        }
        while self.entries.len() >= self.capacity {
            self.entries.pop_front();
        }
        self.entries.push_back((key.to_string(), value));
    }

    /// Entries held.
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// Whether empty.
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}

static ICONS: LazyLock<Mutex<Lru<Arc<Image>>>> =
    LazyLock::new(|| Mutex::new(Lru::new(ICON_CACHE_CAPACITY)));

/// A cached icon, if any.
pub fn cached_icon(slug: &str) -> Option<Arc<Image>> {
    if slug.is_empty() {
        return None;
    }
    ICONS.lock().get(slug)
}

/// The image format from magic bytes (or an SVG text prefix).
pub fn sniff_format(bytes: &[u8]) -> Option<ImageFormat> {
    if bytes.starts_with(&[0x89, b'P', b'N', b'G']) {
        Some(ImageFormat::Png)
    } else if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        Some(ImageFormat::Jpeg)
    } else if bytes.len() >= 12 && &bytes[0..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        Some(ImageFormat::Webp)
    } else if bytes.starts_with(b"GIF8") {
        Some(ImageFormat::Gif)
    } else if bytes.starts_with(b"BM") {
        Some(ImageFormat::Bmp)
    } else {
        let head = String::from_utf8_lossy(&bytes[..bytes.len().min(256)]).to_lowercase();
        (head.contains("<svg") || head.trim_start().starts_with("<?xml"))
            .then_some(ImageFormat::Svg)
    }
}

/// Bytes -> image, `None` when the format is not recognised.
pub fn image_from_bytes(bytes: Vec<u8>) -> Option<Arc<Image>> {
    let format = sniff_format(&bytes)?;
    Some(Arc::new(Image::from_bytes(format, bytes)))
}

/// Loads a preview image from a URL (logo / background). Failures are `Err` and clear the
/// preview.
pub async fn load_image(backend: &dyn EditorBackend, url: &str) -> Result<Arc<Image>, NetError> {
    let bytes = backend.image_bytes(url).await?;
    image_from_bytes(bytes).ok_or_else(|| NetError::new("unsupported image"))
}

/// Fetches a Modrinth hit's icon: its `icon_url`, else the project's; cached under the slug.
/// Every failure is silent (`None`).
pub async fn load_icon(
    backend: &dyn EditorBackend,
    slug: &str,
    icon_url: &str,
) -> Option<Arc<Image>> {
    if let Some(hit) = cached_icon(slug) {
        return Some(hit);
    }
    let mut url = icon_url.trim().to_string();
    if url.is_empty() && !slug.is_empty() {
        url = crate::modrinth::project_icon_url(backend, slug).await?;
    }
    if url.is_empty() {
        return None;
    }
    let image = load_image(backend, &url).await.ok()?;
    if !slug.is_empty() {
        ICONS.lock().put(slug, image.clone());
    }
    Some(image)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::backend::fake::FakeBackend;

    #[test]
    fn lru_evicts_the_least_recent() {
        let mut l = Lru::new(2);
        l.put("a", 1);
        l.put("b", 2);
        assert_eq!(l.get("a"), Some(1));
        l.put("c", 3);
        assert_eq!(l.get("b"), None);
        assert_eq!(l.get("a"), Some(1));
        assert_eq!(l.get("c"), Some(3));
        l.put("c", 4);
        assert_eq!(l.len(), 2);
        assert_eq!(l.get("c"), Some(4));
    }

    #[test]
    fn formats_are_sniffed() {
        assert_eq!(
            sniff_format(&[0x89, b'P', b'N', b'G', 0]),
            Some(ImageFormat::Png)
        );
        assert_eq!(
            sniff_format(&[0xFF, 0xD8, 0xFF, 0xE0]),
            Some(ImageFormat::Jpeg)
        );
        assert_eq!(
            sniff_format(b"RIFF\0\0\0\0WEBPVP8 "),
            Some(ImageFormat::Webp)
        );
        assert_eq!(sniff_format(b"GIF89a"), Some(ImageFormat::Gif));
        assert_eq!(sniff_format(b"<svg xmlns=..."), Some(ImageFormat::Svg));
        assert_eq!(sniff_format(b"hello"), None);
    }

    #[test]
    fn icons_are_cached_by_slug() {
        let png = [0x89, b'P', b'N', b'G', 1, 2, 3];
        let b = FakeBackend::default().file("https://cdn/icon.png", &png);
        let img = futures::executor::block_on(load_icon(&b, "cached-slug", "https://cdn/icon.png"));
        assert!(img.is_some());
        assert!(cached_icon("cached-slug").is_some());
        // A second load hits the cache even when the URL is gone.
        let empty = FakeBackend::default();
        assert!(
            futures::executor::block_on(load_icon(&empty, "cached-slug", "https://x")).is_some()
        );
        assert!(futures::executor::block_on(load_icon(&empty, "", "")).is_none());
    }
}
