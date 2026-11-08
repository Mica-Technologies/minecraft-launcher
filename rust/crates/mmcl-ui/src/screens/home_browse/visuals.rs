// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Pack card visuals shared by Home, Browse (and the detail modal): the dominant-colour palette
//! (`computeDominantColorPalette`), the dynamic gradient (`applyDynamicBackground`), logo
//! transparency (`LogoTransparencyDetector`), the cover viewport and decode width
//! (`PackBackgroundLayer`), and a per-app cache that loads logos and backgrounds off the UI
//! thread.
//!
//! GPUI's `linear_gradient` takes two stops, so a multi-stop gradient is rendered once per
//! palette into a small PNG and drawn stretched (cached by the pack's logo key).
//!
//! Rendering is a pure function of the pack a card shows: every async result is stored by its
//! source key, so a late result for an older bind can never land on the wrong card (guarded
//! bug 5) and there is no per-card identity token to check.

use std::collections::{HashMap, HashSet};
use std::io::Cursor;
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::Arc;

use gpui::{App, AppContext as _, AssetSource, Global, Image, ImageFormat, ImageSource};
use image::{Rgba, RgbaImage};

use super::backend::LibraryBackend;

/// An RGB colour with components in `0..=1`.
pub type Rgbf = [f32; 3];

/// Colour buckets per channel.
const LEVELS: usize = 16;
/// Picked colours closer than this (Euclidean RGB) count as the same.
const MIN_DISTANCE: f32 = 0.25;

/// The dominant colours of an image, most common first (`computeDominantColorPalette`).
/// Transparent pixels (alpha < 0.5) are skipped; saturated pixels weigh more.
pub fn dominant_palette(image: &RgbaImage, count: usize) -> Option<Vec<Rgbf>> {
    let (w, h) = image.dimensions();
    if w == 0 || h == 0 || count == 0 {
        return None;
    }
    let step = (w.min(h) / 32).max(1) as usize;
    let mut counts = vec![0u64; LEVELS * LEVELS * LEVELS];
    let mut sums = vec![[0f64; 3]; LEVELS * LEVELS * LEVELS];
    for y in (0..h).step_by(step) {
        for x in (0..w).step_by(step) {
            let Rgba([r, g, b, a]) = *image.get_pixel(x, y);
            if (a as f32 / 255.0) < 0.5 {
                continue;
            }
            let (r, g, b) = (r as f32 / 255.0, g as f32 / 255.0, b as f32 / 255.0);
            let sat = r.max(g).max(b) - r.min(g).min(b);
            let weight = 1 + (sat * 20.0).floor() as u64;
            let q = |c: f32| ((c * LEVELS as f32).floor() as usize).min(LEVELS - 1);
            let idx = (q(r) * LEVELS + q(g)) * LEVELS + q(b);
            counts[idx] += weight;
            let wf = weight as f64;
            sums[idx][0] += r as f64 * wf;
            sums[idx][1] += g as f64 * wf;
            sums[idx][2] += b as f64 * wf;
        }
    }
    let avg = |i: usize| -> Rgbf {
        let c = counts[i] as f64;
        [
            (sums[i][0] / c) as f32,
            (sums[i][1] / c) as f32,
            (sums[i][2] / c) as f32,
        ]
    };
    let dist = |a: Rgbf, b: Rgbf| {
        ((a[0] - b[0]).powi(2) + (a[1] - b[1]).powi(2) + (a[2] - b[2]).powi(2)).sqrt()
    };
    let mut picked: Vec<Rgbf> = Vec::new();
    for _ in 0..count {
        let mut best: Option<usize> = None;
        let mut best_count = 0u64;
        for (i, &c) in counts.iter().enumerate() {
            if c <= best_count {
                continue;
            }
            let a = avg(i);
            if picked.iter().any(|p| dist(*p, a) <= MIN_DISTANCE) {
                continue;
            }
            best = Some(i);
            best_count = c;
        }
        match best {
            Some(i) => picked.push(avg(i)),
            None => break,
        }
    }
    (!picked.is_empty()).then_some(picked)
}

fn rgb_to_hsv(c: Rgbf) -> (f32, f32, f32) {
    let max = c[0].max(c[1]).max(c[2]);
    let min = c[0].min(c[1]).min(c[2]);
    let d = max - min;
    let h = if d == 0.0 {
        0.0
    } else if max == c[0] {
        60.0 * (((c[1] - c[2]) / d).rem_euclid(6.0))
    } else if max == c[1] {
        60.0 * ((c[2] - c[0]) / d + 2.0)
    } else {
        60.0 * ((c[0] - c[1]) / d + 4.0)
    };
    let s = if max == 0.0 { 0.0 } else { d / max };
    (h, s, max)
}

fn hsv_to_rgb(h: f32, s: f32, v: f32) -> Rgbf {
    let c = v * s;
    let x = c * (1.0 - ((h / 60.0).rem_euclid(2.0) - 1.0).abs());
    let m = v - c;
    let (r, g, b) = match (h / 60.0) as i32 {
        0 => (c, x, 0.0),
        1 => (x, c, 0.0),
        2 => (0.0, c, x),
        3 => (0.0, x, c),
        4 => (x, 0.0, c),
        _ => (c, 0.0, x),
    };
    [r + m, g + m, b + m]
}

/// `derive(p, brightness * factor)` in HSB.
pub fn derive_brightness(c: Rgbf, factor: f32) -> Rgbf {
    let (h, s, v) = rgb_to_hsv(c);
    hsv_to_rgb(h, s, (v * factor).clamp(0.0, 1.0))
}

/// A gradient stop: position in `0..=1` and colour.
pub type Stop = (f32, Rgbf);

/// The gradient of a palette: one colour fades darker (`0 / 55 / 100 %`); several colours
/// get their own stops with a 50/50 blend between each pair.
pub fn gradient_stops(palette: &[Rgbf]) -> Vec<Stop> {
    match palette.len() {
        0 => Vec::new(),
        1 => {
            let p = palette[0];
            vec![
                (0.0, p),
                (0.55, derive_brightness(p, 0.60)),
                (1.0, derive_brightness(p, 0.35)),
            ]
        }
        n => {
            let span = (n - 1) as f32;
            let mut out = Vec::new();
            for i in 0..n {
                out.push((i as f32 / span, palette[i]));
                if i + 1 < n {
                    let (a, b) = (palette[i], palette[i + 1]);
                    out.push((
                        (i as f32 + 0.5) / span,
                        [
                            (a[0] + b[0]) / 2.0,
                            (a[1] + b[1]) / 2.0,
                            (a[2] + b[2]) / 2.0,
                        ],
                    ));
                }
            }
            out
        }
    }
}

/// `#RRGGBB` with rounding.
pub fn to_hex(c: Rgbf) -> String {
    let b = |v: f32| (v.clamp(0.0, 1.0) * 255.0).round() as u8;
    format!("#{:02X}{:02X}{:02X}", b(c[0]), b(c[1]), b(c[2]))
}

fn sample(stops: &[Stop], t: f32) -> Rgbf {
    let t = t.clamp(0.0, 1.0);
    let Some(first) = stops.first() else {
        return [0.0; 3];
    };
    if t <= first.0 {
        return first.1;
    }
    for w in stops.windows(2) {
        let ((p0, c0), (p1, c1)) = (w[0], w[1]);
        if t <= p1 {
            let f = if p1 > p0 { (t - p0) / (p1 - p0) } else { 1.0 };
            return [
                c0[0] + (c1[0] - c0[0]) * f,
                c0[1] + (c1[1] - c0[1]) * f,
                c0[2] + (c1[2] - c0[2]) * f,
            ];
        }
    }
    stops[stops.len() - 1].1
}

/// A `to bottom right` gradient bitmap: isolines run parallel to the bottom-left / top-right
/// diagonal, so `t = (x / w + y / h) / 2`.
pub fn render_diagonal(stops: &[Stop], w: u32, h: u32) -> RgbaImage {
    RgbaImage::from_fn(w, h, |x, y| {
        let t = ((x as f32 + 0.5) / w as f32 + (y as f32 + 0.5) / h as f32) / 2.0;
        let c = sample(stops, t);
        Rgba([
            (c[0] * 255.0).round() as u8,
            (c[1] * 255.0).round() as u8,
            (c[2] * 255.0).round() as u8,
            255,
        ])
    })
}

fn hex(v: u32) -> Rgbf {
    [
        ((v >> 16) & 0xFF) as f32 / 255.0,
        ((v >> 8) & 0xFF) as f32 / 255.0,
        (v & 0xFF) as f32 / 255.0,
    ]
}

/// `.heroBackgroundDefaultVanilla`: sky, horizon, golden glow, grass, dirt (brand constants).
pub fn default_vanilla_stops() -> Vec<Stop> {
    vec![
        (0.0, hex(0x4A8FD0)),
        (0.22, hex(0x8FC8E8)),
        (0.42, hex(0xE0CC7E)),
        (0.62, hex(0x5BA84E)),
        (1.0, hex(0x7A5C36)),
    ]
}

/// `.heroBackgroundDefaultForge`: a dark anvil with a forge-fire glow at the bottom centre.
pub fn render_default_forge(w: u32, h: u32) -> RgbaImage {
    let base = [
        (0.0, hex(0x2B2D31)),
        (0.60, hex(0x1F2125)),
        (1.0, hex(0x3D2D1F)),
    ];
    let glow = hex(0xFF6B35);
    let radius = 0.65 * ((w * w + h * h) as f32).sqrt() / std::f32::consts::SQRT_2;
    RgbaImage::from_fn(w, h, |x, y| {
        let c = sample(&base, (y as f32 + 0.5) / h as f32);
        let dx = x as f32 + 0.5 - 0.5 * w as f32;
        let dy = y as f32 + 0.5 - 0.95 * h as f32;
        let r = (dx * dx + dy * dy).sqrt() / radius;
        let a = if r <= 0.35 {
            0.50 - (0.30 * r / 0.35)
        } else if r <= 0.60 {
            0.20 * (1.0 - (r - 0.35) / 0.25)
        } else {
            0.0
        };
        let mix = |b: f32, g: f32| ((b * (1.0 - a) + g * a) * 255.0).round() as u8;
        Rgba([
            mix(c[0], glow[0]),
            mix(c[1], glow[1]),
            mix(c[2], glow[2]),
            255,
        ])
    })
}

/// Whether a logo has a mostly transparent edge (`LogoTransparencyDetector`): 12 samples at an
/// inset; transparent when at least 60 % have alpha < 0.05.
pub fn logo_is_transparent(image: &RgbaImage) -> bool {
    let (w, h) = image.dimensions();
    if w == 0 || h == 0 {
        return false;
    }
    let ix = (w / 20).max(2).min(w - 1);
    let iy = (h / 20).max(2).min(h - 1);
    let (r, b) = (w.saturating_sub(1 + ix), h.saturating_sub(1 + iy));
    let pts = [
        (ix, iy),
        (r, iy),
        (ix, b),
        (r, b),
        (w / 4, iy),
        (w / 2, iy),
        (3 * w / 4, iy),
        (w / 4, b),
        (w / 2, b),
        (3 * w / 4, b),
        (ix, h / 2),
        (r, h / 2),
    ];
    let transparent = pts
        .iter()
        .filter(|&&(x, y)| (image.get_pixel(x.min(w - 1), y.min(h - 1)).0[3] as f32 / 255.0) < 0.05)
        .count();
    transparent * 100 >= pts.len() * 60
}

/// A source-image rectangle `(x, y, w, h)`.
pub type ViewRect = (f32, f32, f32, f32);

/// The centred crop of an `iw x ih` image that covers a `bw x bh` box.
pub fn cover_viewport(iw: f32, ih: f32, bw: f32, bh: f32) -> ViewRect {
    let s = (bw / iw).max(bh / ih);
    let vw = iw.min(bw / s);
    let vh = ih.min(bh / s);
    ((iw - vw) / 2.0, (ih - vh) / 2.0, vw, vh)
}

/// The decode width covering `box_w` layout px at `scale`, rounded up to a multiple of 64.
pub fn decode_width(box_w: f32, scale: f32) -> u32 {
    let px = (box_w * scale).ceil().max(1.0) as u32;
    px.div_ceil(64).max(1) * 64
}

fn encode_png(img: &RgbaImage) -> Option<Arc<Image>> {
    let mut out = Cursor::new(Vec::new());
    img.write_to(&mut out, image::ImageFormat::Png).ok()?;
    Some(Arc::new(Image::from_bytes(
        ImageFormat::Png,
        out.into_inner(),
    )))
}

fn gpui_format(bytes: &[u8]) -> Option<ImageFormat> {
    Some(match image::guess_format(bytes).ok()? {
        image::ImageFormat::Png => ImageFormat::Png,
        image::ImageFormat::Jpeg => ImageFormat::Jpeg,
        image::ImageFormat::Gif => ImageFormat::Gif,
        image::ImageFormat::WebP => ImageFormat::Webp,
        image::ImageFormat::Bmp => ImageFormat::Bmp,
        image::ImageFormat::Tiff => ImageFormat::Tiff,
        _ => return None,
    })
}

// ---------------------------------------------------------------------------------------------
// The cache

/// The gradient bitmap size (the 360x150 hero image area at a fraction of its size).
const GRADIENT_W: u32 = 96;
const GRADIENT_H: u32 = 40;

/// The canvas-drawn brand tiles of Browse's vanilla and loader cards
/// (`PlaceholderLogoFactory`): a two-stop vertical gradient with a short bold wordmark.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Brand {
    /// The green-grass "MC" tile.
    Vanilla,
    /// "F" in forge orange on dark grey.
    Forge,
    /// "N" in white on orange.
    NeoForge,
    /// "FAB" in brown on tan.
    Fabric,
}

/// What a brand tile draws (colours as `0xRRGGBB`, the text size per 128 px of tile).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BrandTile {
    pub top: u32,
    pub bottom: u32,
    pub text: &'static str,
    pub text_color: u32,
    pub text_size_per_128: u32,
}

impl Brand {
    /// The brand of a loader type (`forge`, `neoforge`, `fabric`; any case).
    pub fn of_loader(loader_type: &str) -> Option<Brand> {
        match loader_type.trim().to_ascii_lowercase().as_str() {
            "forge" => Some(Brand::Forge),
            "neoforge" => Some(Brand::NeoForge),
            "fabric" => Some(Brand::Fabric),
            _ => None,
        }
    }

    /// The loader type id (`None` for vanilla), also the cache file stem.
    pub fn loader_type(self) -> Option<&'static str> {
        match self {
            Brand::Vanilla => None,
            Brand::Forge => Some("forge"),
            Brand::NeoForge => Some("neoforge"),
            Brand::Fabric => Some("fabric"),
        }
    }

    /// The project's official logo (cached as `config/loader-logos/<type>.png`).
    pub fn official_url(self) -> Option<&'static str> {
        match self {
            Brand::Vanilla => None,
            Brand::Forge => Some("https://files.minecraftforge.net/static/images/embed_logo.png"),
            Brand::NeoForge => Some("https://neoforged.net/img/authors/neoforged.png"),
            Brand::Fabric => Some("https://fabricmc.net/assets/logo.png"),
        }
    }

    /// The cached official logo file in `dir`.
    pub fn cache_file(self, dir: &std::path::Path) -> Option<PathBuf> {
        self.loader_type().map(|t| dir.join(format!("{t}.png")))
    }

    /// The placeholder tile.
    pub fn tile(self) -> BrandTile {
        match self {
            Brand::Vanilla => BrandTile {
                top: 0x7BB344,
                bottom: 0x3F7821,
                text: "MC",
                text_color: 0xFFFFFF,
                text_size_per_128: 56,
            },
            Brand::Forge => BrandTile {
                top: 0x525252,
                bottom: 0x1F1F1F,
                text: "F",
                text_color: 0xFF8A3A,
                text_size_per_128: 78,
            },
            Brand::NeoForge => BrandTile {
                top: 0xF08646,
                bottom: 0xB54115,
                text: "N",
                text_color: 0xFFFFFF,
                text_size_per_128: 78,
            },
            Brand::Fabric => BrandTile {
                top: 0xDEC596,
                bottom: 0x937547,
                text: "FAB",
                text_color: 0x3B2A13,
                text_size_per_128: 44,
            },
        }
    }
}

/// Where a card's logo comes from.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum LogoSource {
    /// A cached file.
    File(PathBuf),
    /// A remote URL (available packs, or an installed pack whose cache path is unknown).
    Url(String),
    /// The bundled launcher logo (vanilla).
    Vanilla,
    /// A brand tile (Browse's vanilla and loader cards); loader brands upgrade to the official
    /// logo once it is cached.
    Brand(Brand),
    /// None declared: the bundled logo on a Forge default background.
    None,
}

impl LogoSource {
    fn key(&self) -> String {
        match self {
            LogoSource::File(p) => format!("file:{}", p.display()),
            LogoSource::Url(u) => format!("url:{u}"),
            LogoSource::Vanilla => "__mica_launcher_logo__".into(),
            LogoSource::Brand(b) => format!("__brand_{b:?}__"),
            LogoSource::None => "__none__".into(),
        }
    }
}

/// A decoded logo with its analysis.
#[derive(Clone)]
struct Loaded {
    image: Arc<Image>,
    palette: Option<Vec<Rgbf>>,
    transparent: bool,
}

#[derive(Clone)]
enum Slot {
    Loading,
    Ready(Loaded),
    Failed,
}

/// Decoded images, palettes and gradients (per app).
#[derive(Default)]
pub struct VisualCache {
    logos: HashMap<String, Slot>,
    gradients: HashMap<String, Arc<Image>>,
    backgrounds: HashMap<String, Slot>,
    pending_cache: HashSet<String>,
    /// Official loader logos being downloaded (or that failed) this session.
    loader_fetches: HashSet<Brand>,
    /// Available-pack logos were prefetched this session.
    available_prefetched: bool,
}

impl Global for VisualCache {}

/// What a card draws.
#[derive(Clone)]
pub struct CardVisual {
    /// The logo (the bundled logo until a pack logo loads).
    pub logo: ImageSource,
    /// Whether the pack logo has loaded (fade in).
    pub logo_loaded: bool,
    /// The logo's edge is transparent: drop its frame.
    pub logo_transparent: bool,
    /// The gradient behind everything.
    pub gradient: Option<Arc<Image>>,
    /// Draw this brand tile instead of `logo` (no official logo cached yet).
    pub placeholder: Option<Brand>,
}

fn analyze(bytes: Vec<u8>) -> Option<Loaded> {
    let format = gpui_format(&bytes)?;
    let decoded = image::load_from_memory(&bytes).ok()?;
    let small = decoded.thumbnail(64, 64).to_rgba8();
    let edges = decoded.thumbnail(256, 256).to_rgba8();
    Some(Loaded {
        palette: dominant_palette(&small, 4),
        transparent: logo_is_transparent(&edges),
        image: Arc::new(Image::from_bytes(format, bytes)),
    })
}

fn gradient_for(
    cx: &mut App,
    key: &str,
    render: impl FnOnce() -> Option<RgbaImage>,
) -> Option<Arc<Image>> {
    if let Some(g) = cx.default_global::<VisualCache>().gradients.get(key) {
        return Some(g.clone());
    }
    let g = encode_png(&render()?)?;
    cx.default_global::<VisualCache>()
        .gradients
        .insert(key.to_string(), g.clone());
    Some(g)
}

fn forge_gradient(cx: &mut App) -> Option<Arc<Image>> {
    gradient_for(cx, "__forge_default__", || {
        Some(render_default_forge(GRADIENT_W, GRADIENT_H))
    })
}

fn vanilla_gradient(cx: &mut App) -> Option<Arc<Image>> {
    gradient_for(cx, "__vanilla_default__", || {
        Some(render_diagonal(
            &default_vanilla_stops(),
            GRADIENT_W,
            GRADIENT_H,
        ))
    })
}

/// Downloads a loader's official logo into `config/loader-logos` once per session
/// (`PlaceholderLogoFactory.resolveLogo`); windows refresh when it lands, so the brand tile
/// upgrades to the official logo.
fn ensure_loader_logo(cx: &mut App, brand: Brand, backend: &Rc<dyn LibraryBackend>) {
    let (Some(url), Some(dest)) = (
        brand.official_url(),
        backend
            .loader_logos_dir()
            .and_then(|d| brand.cache_file(&d)),
    ) else {
        return;
    };
    if !cx
        .default_global::<VisualCache>()
        .loader_fetches
        .insert(brand)
    {
        return;
    }
    let fetch = backend.fetch_bytes(url);
    cx.spawn(async move |cx| {
        let written = match fetch.await {
            Ok(bytes) if gpui_format(&bytes).is_some() => {
                let dest2 = dest.clone();
                cx.background_spawn(async move {
                    if let Some(parent) = dest2.parent() {
                        std::fs::create_dir_all(parent)?;
                    }
                    std::fs::write(&dest2, bytes)
                })
                .await
                .map_err(|e| e.to_string())
            }
            Ok(_) => Err(super::backend::unavailable()),
            Err(e) => Err(e),
        };
        match written {
            Ok(()) => cx.update(|cx| cx.refresh_windows()),
            Err(e) => tracing::warn!(
                "{}",
                mmcl_i18n::tf!(
                    "log.placeholderLogo.officialFetchFailed",
                    brand.loader_type().unwrap_or_default(),
                    url,
                    e
                )
            ),
        }
    })
    .detach();
}

fn bundled_logo(_cx: &App) -> Option<Vec<u8>> {
    mmcl_ui_kit::Assets
        .load(mmcl_ui_kit::assets::APP_ICON)
        .ok()
        .flatten()
        .map(|c| c.into_owned())
}

/// Starts loading `source` if needed and returns what is known now.
fn logo_slot(cx: &mut App, source: &LogoSource, backend: &Rc<dyn LibraryBackend>) -> Slot {
    let key = source.key();
    if let Some(slot) = cx.default_global::<VisualCache>().logos.get(&key) {
        return slot.clone();
    }
    cx.default_global::<VisualCache>()
        .logos
        .insert(key.clone(), Slot::Loading);
    let source = source.clone();
    let fetch = match &source {
        LogoSource::Url(u) => Some(backend.fetch_bytes(u)),
        _ => None,
    };
    let bundled = matches!(source, LogoSource::Vanilla | LogoSource::None)
        .then(|| bundled_logo(cx))
        .flatten();
    cx.spawn(async move |cx| {
        let bytes: Option<Vec<u8>> = match (&source, fetch) {
            (LogoSource::File(p), _) => {
                let p = p.clone();
                cx.background_spawn(async move { std::fs::read(p).ok() })
                    .await
            }
            (LogoSource::Url(_), Some(f)) => f.await.ok(),
            _ => bundled,
        };
        let loaded = match bytes {
            Some(b) => cx.background_spawn(async move { analyze(b) }).await,
            None => None,
        };
        cx.update(|cx| {
            let slot = loaded.map(Slot::Ready).unwrap_or(Slot::Failed);
            cx.default_global::<VisualCache>().logos.insert(key, slot);
            cx.refresh_windows();
        });
    })
    .detach();
    Slot::Loading
}

/// The visuals of a card whose logo comes from `source`. Loads start on first use; windows
/// refresh when they finish.
pub fn card_visual(
    cx: &mut App,
    source: &LogoSource,
    backend: &Rc<dyn LibraryBackend>,
) -> CardVisual {
    let bundled: ImageSource = mmcl_ui_kit::assets::APP_ICON.into();
    match source {
        LogoSource::None => CardVisual {
            logo: bundled,
            logo_loaded: true,
            logo_transparent: false,
            gradient: forge_gradient(cx),
            placeholder: None,
        },
        LogoSource::Brand(brand) => {
            // A cached official logo draws like any cached file.
            if let Some(file) = backend
                .loader_logos_dir()
                .and_then(|d| brand.cache_file(&d))
                .filter(|f| f.metadata().is_ok_and(|m| m.len() > 0))
            {
                let file = LogoSource::File(file);
                if let Slot::Ready(_) = logo_slot(cx, &file, backend) {
                    return card_visual(cx, &file, backend);
                }
            } else {
                ensure_loader_logo(cx, *brand, backend);
            }
            CardVisual {
                logo: bundled,
                logo_loaded: true,
                logo_transparent: true,
                gradient: if *brand == Brand::Vanilla {
                    vanilla_gradient(cx)
                } else {
                    forge_gradient(cx)
                },
                placeholder: Some(*brand),
            }
        }
        _ => {
            let slot = logo_slot(cx, source, backend);
            let vanilla = matches!(source, LogoSource::Vanilla);
            match slot {
                Slot::Ready(l) => {
                    let key = format!("grad:{}", source.key());
                    let gradient = match (&l.palette, vanilla) {
                        (Some(p), _) if !p.is_empty() => {
                            let p = p.clone();
                            gradient_for(cx, &key, move || {
                                Some(render_diagonal(&gradient_stops(&p), GRADIENT_W, GRADIENT_H))
                            })
                        }
                        (_, true) => vanilla_gradient(cx),
                        _ => forge_gradient(cx),
                    };
                    CardVisual {
                        logo: ImageSource::Image(l.image),
                        logo_loaded: true,
                        logo_transparent: l.transparent,
                        gradient,
                        placeholder: None,
                    }
                }
                Slot::Loading | Slot::Failed => CardVisual {
                    logo: bundled,
                    logo_loaded: matches!(slot, Slot::Failed),
                    logo_transparent: false,
                    gradient: if vanilla {
                        vanilla_gradient(cx)
                    } else {
                        forge_gradient(cx)
                    },
                    placeholder: None,
                },
            }
        }
    }
}

/// A background image source: files draw directly (GPUI decodes them off the UI thread);
/// URLs are fetched once and kept.
pub fn background_source(
    cx: &mut App,
    source: &LogoSource,
    backend: &Rc<dyn LibraryBackend>,
) -> Option<ImageSource> {
    match source {
        LogoSource::File(p) => Some(ImageSource::from(p.clone())),
        LogoSource::Url(u) => {
            let key = u.clone();
            if let Some(slot) = cx.default_global::<VisualCache>().backgrounds.get(&key) {
                return match slot {
                    Slot::Ready(l) => Some(ImageSource::Image(l.image.clone())),
                    _ => None,
                };
            }
            cx.default_global::<VisualCache>()
                .backgrounds
                .insert(key.clone(), Slot::Loading);
            let fetch = backend.fetch_bytes(u);
            cx.spawn(async move |cx| {
                let image = fetch
                    .await
                    .ok()
                    .and_then(|b| gpui_format(&b).map(|f| Arc::new(Image::from_bytes(f, b))));
                cx.update(|cx| {
                    let slot = match image {
                        Some(image) => Slot::Ready(Loaded {
                            image,
                            palette: None,
                            transparent: false,
                        }),
                        None => Slot::Failed,
                    };
                    cx.default_global::<VisualCache>()
                        .backgrounds
                        .insert(key, slot);
                    cx.refresh_windows();
                });
            })
            .detach();
            None
        }
        _ => None,
    }
}

/// Runs `cache_images` once per pack per session for declared-but-missing images, then drops
/// what the cache knew about the pack so the next render resolves the new files.
pub fn ensure_cached(cx: &mut App, pack: &mmcl_engine::PackId, backend: &Rc<dyn LibraryBackend>) {
    let key = pack.as_str().to_string();
    if !cx.default_global::<VisualCache>().pending_cache.insert(key) {
        return;
    }
    let fut = backend.cache_images(pack);
    cx.spawn(async move |cx| {
        if fut.await {
            cx.update(|cx| cx.refresh_windows());
        }
    })
    .detach();
}

/// The dominant palette of a pack logo, once loaded (`sampleDominantPackPalette`); `None` for
/// vanilla or while loading.
pub fn pack_palette(cx: &App, source: &LogoSource, count: usize) -> Option<Vec<Rgbf>> {
    if count == 0 || matches!(source, LogoSource::Vanilla | LogoSource::Brand(_)) {
        return None;
    }
    match cx.try_global::<VisualCache>()?.logos.get(&source.key())? {
        Slot::Ready(l) => l
            .palette
            .as_ref()
            .filter(|p| p.len() >= count.min(p.len()).max(1))
            .map(|p| p.iter().take(count).copied().collect()),
        _ => None,
    }
}

/// Starts decoding the logos of `sources` so their gradients are ready
/// (`prefetchAvailableModpackBackgrounds`).
pub fn prefetch(cx: &mut App, sources: &[LogoSource], backend: &Rc<dyn LibraryBackend>) {
    for s in sources {
        if !matches!(s, LogoSource::None | LogoSource::Brand(_)) {
            let _ = logo_slot(cx, s, backend);
        }
    }
}

/// The logo sources of the available packs worth warming (their remote logos).
pub fn available_logo_sources(packs: &[mmcl_engine::PackSummary]) -> Vec<LogoSource> {
    let mut seen = HashSet::new();
    packs
        .iter()
        .filter(|p| !p.installed)
        .filter_map(|p| p.logo_url.as_ref())
        .map(|u| u.trim())
        .filter(|u| !u.is_empty() && seen.insert(u.to_string()))
        .map(|u| LogoSource::Url(u.to_string()))
        .collect()
}

/// Warms every available pack's logo palette once per session
/// (`prefetchAvailableModpackBackgrounds`), so Browse's gradients are ready on first view.
pub fn prefetch_available(
    cx: &mut App,
    packs: &[mmcl_engine::PackSummary],
    backend: &Rc<dyn LibraryBackend>,
) {
    let cache = cx.default_global::<VisualCache>();
    if cache.available_prefetched {
        return;
    }
    cache.available_prefetched = true;
    let sources = available_logo_sources(packs);
    prefetch(cx, &sources, backend);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn brands_follow_the_placeholder_factory() {
        assert_eq!(Brand::of_loader("Forge"), Some(Brand::Forge));
        assert_eq!(Brand::of_loader(" neoforge "), Some(Brand::NeoForge));
        assert_eq!(Brand::of_loader("fabric"), Some(Brand::Fabric));
        assert_eq!(Brand::of_loader("quilt"), None);
        assert_eq!(Brand::Vanilla.tile().text, "MC");
        assert_eq!(Brand::Vanilla.tile().top, 0x7BB344);
        assert_eq!(Brand::Fabric.tile().text, "FAB");
        assert!(Brand::Vanilla.official_url().is_none());
        assert_eq!(
            Brand::Forge.cache_file(std::path::Path::new("d")),
            Some(std::path::Path::new("d").join("forge.png"))
        );
        assert_eq!(Brand::Vanilla.cache_file(std::path::Path::new("d")), None);
    }

    #[test]
    fn available_logos_are_deduplicated_and_skip_installed() {
        let mut a = mmcl_engine::PackSummary {
            logo_url: Some("https://x/a.png".into()),
            ..Default::default()
        };
        let b = a.clone();
        let mut c = a.clone();
        c.logo_url = Some(" ".into());
        let mut d = a.clone();
        d.logo_url = Some("https://x/d.png".into());
        d.installed = true;
        a.name = "A".into();
        assert_eq!(
            available_logo_sources(&[a, b, c, d]),
            vec![LogoSource::Url("https://x/a.png".into())]
        );
    }

    fn solid(w: u32, h: u32, c: [u8; 4]) -> RgbaImage {
        RgbaImage::from_pixel(w, h, Rgba(c))
    }

    #[test]
    fn palette_of_a_solid_image_is_that_colour() {
        let p = dominant_palette(&solid(64, 64, [200, 40, 40, 255]), 4).unwrap();
        assert_eq!(p.len(), 1);
        assert!((p[0][0] - 200.0 / 255.0).abs() < 0.01);
    }

    #[test]
    fn transparent_pixels_are_skipped() {
        assert!(dominant_palette(&solid(64, 64, [200, 40, 40, 100]), 4).is_none());
    }

    #[test]
    fn saturation_outweighs_grey_and_near_colours_merge() {
        // 60 % grey, 40 % saturated red: red still wins through its weight.
        let mut img = solid(64, 64, [128, 128, 128, 255]);
        for y in 0..64 {
            for x in 0..26 {
                img.put_pixel(x, y, Rgba([230, 20, 20, 255]));
            }
        }
        let p = dominant_palette(&img, 4).unwrap();
        assert!(p[0][0] > 0.8 && p[0][1] < 0.2);
        assert_eq!(p.len(), 2);
        // Two nearly equal reds are one colour (distance <= 0.25).
        let mut near = solid(64, 64, [230, 20, 20, 255]);
        for y in 0..32 {
            for x in 0..64 {
                near.put_pixel(x, y, Rgba([210, 30, 30, 255]));
            }
        }
        assert_eq!(dominant_palette(&near, 4).unwrap().len(), 1);
    }

    #[test]
    fn gradient_stops_follow_the_rules() {
        let one = gradient_stops(&[[1.0, 0.0, 0.0]]);
        assert_eq!(one.len(), 3);
        assert_eq!(one[1].0, 0.55);
        assert!((one[2].1[0] - 0.35).abs() < 0.01);
        let three = gradient_stops(&[[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]]);
        let pos: Vec<f32> = three.iter().map(|s| s.0).collect();
        assert_eq!(pos, [0.0, 0.25, 0.5, 0.75, 1.0]);
        assert_eq!(to_hex(three[1].1), "#808000");
        let img = render_diagonal(&three, 20, 10);
        assert!(img.get_pixel(0, 0).0[0] > 230);
        assert!(img.get_pixel(19, 9).0[2] > 200);
    }

    #[test]
    fn logo_transparency_on_synthetic_images() {
        assert!(!logo_is_transparent(&solid(100, 100, [10, 10, 10, 255])));
        let mut ring = solid(100, 100, [0, 0, 0, 0]);
        for y in 20..80 {
            for x in 20..80 {
                ring.put_pixel(x, y, Rgba([255, 0, 0, 255]));
            }
        }
        assert!(logo_is_transparent(&ring));
        assert!(!logo_is_transparent(&RgbaImage::new(0, 0)));
    }

    // Port of PackBackgroundLayerTest.

    #[test]
    fn wide_image_cropped_equally_left_and_right() {
        let (x, y, w, h) = cover_viewport(400.0, 100.0, 200.0, 100.0);
        assert_eq!((x, y, w, h), (100.0, 0.0, 200.0, 100.0));
    }

    #[test]
    fn tall_image_cropped_equally_top_and_bottom() {
        let (x, y, w, h) = cover_viewport(100.0, 400.0, 100.0, 200.0);
        assert_eq!((x, y, w, h), (0.0, 100.0, 100.0, 200.0));
    }

    #[test]
    fn same_aspect_shows_the_whole_image() {
        assert_eq!(
            cover_viewport(720.0, 300.0, 360.0, 150.0),
            (0.0, 0.0, 720.0, 300.0)
        );
    }

    #[test]
    fn decode_width_covers_device_pixels_rounded_to_64() {
        assert_eq!(decode_width(360.0, 1.0), 384);
        assert_eq!(decode_width(360.0, 1.5), 576);
        assert_eq!(decode_width(10.0, 1.0), 64);
        assert_eq!(decode_width(128.0, 1.0), 128);
    }

    #[test]
    fn forge_default_glows_at_the_bottom() {
        let img = render_default_forge(GRADIENT_W, GRADIENT_H);
        let top = img.get_pixel(GRADIENT_W / 2, 0).0;
        let bottom = img.get_pixel(GRADIENT_W / 2, GRADIENT_H - 2).0;
        assert!(bottom[0] > top[0]);
        assert!(encode_png(&img).is_some());
    }
}
