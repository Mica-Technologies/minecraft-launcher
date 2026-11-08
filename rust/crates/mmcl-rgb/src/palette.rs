// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The pack-logo dominant-colour sampler (Java `MCLauncherMainGui.sampleDominantPackPalette`),
//! pure and GPUI-free so RGB and the GUI hero card can share it.
//!
//! Algorithm: decode the logo scaled to fit 64x64 (aspect kept, nearest-neighbour, as Java's
//! `smooth = false`), sample every `max(1, min(w, h) / 32)` pixels, skip pixels under 50 %
//! opacity, weight each by `1 + floor(saturation * 20)`, bucket at 16 levels per channel, then
//! greedily pick the heaviest bucket whose average colour is more than 0.25 (Euclidean, 0..1
//! RGB) from every colour already picked.

use std::collections::HashMap;
use std::path::Path;

use image::{DynamicImage, GenericImageView, imageops::FilterType};
use parking_lot::Mutex;

use crate::color::RgbColor;

/// Logos are decoded to fit this square.
pub const DECODE_SIZE: u32 = 64;
/// Minimum distance between picked colours.
pub const DIST_THRESHOLD: f64 = 0.25;
const LEVELS: usize = 16;
const BUCKETS: usize = LEVELS * LEVELS * LEVELS;

/// Up to `count` distinct dominant colours of `image` as decoded (no rescale); empty when
/// `count == 0` or no pixel is opaque enough.
pub fn sample_palette(image: &DynamicImage, count: usize) -> Vec<RgbColor> {
    let (w, h) = image.dimensions();
    if count == 0 || w == 0 || h == 0 {
        return Vec::new();
    }
    let rgba = image.to_rgba8();
    let mut weights = vec![0u64; BUCKETS];
    let mut sums = vec![[0f64; 3]; BUCKETS];
    let step = (w.min(h) / 32).max(1) as usize;
    for y in (0..h).step_by(step) {
        for x in (0..w).step_by(step) {
            let p = rgba.get_pixel(x, y).0;
            if (p[3] as f64 / 255.0) < 0.5 {
                continue;
            }
            let c = [
                p[0] as f64 / 255.0,
                p[1] as f64 / 255.0,
                p[2] as f64 / 255.0,
            ];
            let max = c[0].max(c[1]).max(c[2]);
            let min = c[0].min(c[1]).min(c[2]);
            let weight = 1 + ((max - min) * 20.0) as u64;
            let idx = |v: f64| ((v * LEVELS as f64) as usize).min(LEVELS - 1);
            let i = (idx(c[0]) * LEVELS + idx(c[1])) * LEVELS + idx(c[2]);
            weights[i] += weight;
            for ch in 0..3 {
                sums[i][ch] += c[ch] * weight as f64;
            }
        }
    }

    let avg = |i: usize| -> [f64; 3] {
        let w = weights[i] as f64;
        [sums[i][0] / w, sums[i][1] / w, sums[i][2] / w]
    };
    let dist = |a: &[f64; 3], b: &[f64; 3]| {
        ((a[0] - b[0]).powi(2) + (a[1] - b[1]).powi(2) + (a[2] - b[2]).powi(2)).sqrt()
    };

    let mut picked: Vec<[f64; 3]> = Vec::with_capacity(count);
    for _ in 0..count {
        let mut best: Option<usize> = None;
        let mut best_weight = 0;
        for (i, &w) in weights.iter().enumerate() {
            if w == 0 || w <= best_weight {
                continue;
            }
            let cand = avg(i);
            if picked.iter().any(|p| dist(&cand, p) <= DIST_THRESHOLD) {
                continue;
            }
            best_weight = w;
            best = Some(i);
        }
        match best {
            Some(i) => picked.push(avg(i)),
            None => break,
        }
    }
    picked.into_iter().map(to_color).collect()
}

fn to_color(c: [f64; 3]) -> RgbColor {
    let ch = |v: f64| (v * 255.0).round().clamp(0.0, 255.0) as u8;
    RgbColor::new(ch(c[0]), ch(c[1]), ch(c[2]))
}

/// Decodes `path`, scales it to fit [`DECODE_SIZE`], and samples it. `None` when the file is
/// missing or undecodable, `count == 0`, or nothing could be sampled.
pub fn sample_palette_file(path: &Path, count: usize) -> Option<Vec<RgbColor>> {
    if count == 0 || !path.is_file() {
        return None;
    }
    let img = image::open(path).ok()?;
    let img = img.resize(DECODE_SIZE, DECODE_SIZE, FilterType::Nearest);
    let p = sample_palette(&img, count);
    (!p.is_empty()).then_some(p)
}

/// A palette cache keyed by the pack-logo key. A cached palette with at least `count` colours
/// is sliced; a shorter one is re-sampled with the larger request.
#[derive(Default)]
pub struct PaletteCache {
    map: Mutex<HashMap<String, Vec<RgbColor>>>,
}

impl PaletteCache {
    pub fn new() -> PaletteCache {
        PaletteCache::default()
    }

    /// The process-wide cache.
    pub fn global() -> &'static PaletteCache {
        static GLOBAL: std::sync::OnceLock<PaletteCache> = std::sync::OnceLock::new();
        GLOBAL.get_or_init(PaletteCache::new)
    }

    /// Up to `count` colours of the logo at `path`, cached under `key`.
    pub fn palette(&self, key: &str, path: &Path, count: usize) -> Option<Vec<RgbColor>> {
        if count == 0 {
            return None;
        }
        if let Some(p) = self.map.lock().get(key)
            && p.len() >= count
        {
            return Some(p[..count].to_vec());
        }
        let p = sample_palette_file(path, count)?;
        self.map.lock().insert(key.to_string(), p.clone());
        Some(p)
    }

    /// Forgets `key` (the logo changed).
    pub fn invalidate(&self, key: &str) {
        self.map.lock().remove(key);
    }

    /// Forgets everything.
    pub fn clear(&self) {
        self.map.lock().clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::{Rgba, RgbaImage};

    fn image_of(w: u32, h: u32, f: impl Fn(u32, u32) -> [u8; 4]) -> DynamicImage {
        DynamicImage::ImageRgba8(RgbaImage::from_fn(w, h, |x, y| Rgba(f(x, y))))
    }

    #[test]
    fn two_colour_split_gives_both() {
        let img = image_of(64, 64, |x, _| {
            if x < 40 {
                [200, 30, 30, 255]
            } else {
                [30, 30, 200, 255]
            }
        });
        let p = sample_palette(&img, 4);
        assert_eq!(p.len(), 2, "{p:?}");
        assert_eq!(p[0], RgbColor::new(200, 30, 30), "the larger half wins");
        assert_eq!(p[1], RgbColor::new(30, 30, 200));
    }

    #[test]
    fn saturation_outweighs_area() {
        // 60 % grey, 40 % vivid red: red has weight 1 + 20*0.84 per pixel and wins.
        let img = image_of(50, 50, |x, _| {
            if x < 30 {
                [128, 128, 128, 255]
            } else {
                [230, 20, 20, 255]
            }
        });
        let p = sample_palette(&img, 1);
        assert_eq!(p, vec![RgbColor::new(230, 20, 20)]);
    }

    #[test]
    fn transparent_and_monochrome() {
        let clear = image_of(32, 32, |_, _| [255, 0, 0, 100]);
        assert!(sample_palette(&clear, 2).is_empty());
        let mono = image_of(32, 32, |_, _| [10, 200, 10, 255]);
        assert_eq!(sample_palette(&mono, 3), vec![RgbColor::new(10, 200, 10)]);
        assert!(sample_palette(&mono, 0).is_empty());
    }

    #[test]
    fn file_sampler_and_cache() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("logo.png");
        let img = RgbaImage::from_fn(128, 64, |x, _| {
            if x < 80 {
                Rgba([250, 200, 0, 255])
            } else {
                Rgba([0, 120, 255, 255])
            }
        });
        img.save(&path).unwrap();
        let cache = PaletteCache::new();
        let two = cache.palette("k", &path, 2).unwrap();
        assert_eq!(two.len(), 2);
        assert_eq!(two[0], RgbColor::new(250, 200, 0));
        assert_eq!(cache.palette("k", &path, 1).unwrap(), vec![two[0]]);
        assert!(
            cache
                .palette("missing", &dir.path().join("nope.png"), 2)
                .is_none()
        );
        std::fs::write(dir.path().join("bad.png"), b"not a png").unwrap();
        assert!(sample_palette_file(&dir.path().join("bad.png"), 2).is_none());
    }
}
