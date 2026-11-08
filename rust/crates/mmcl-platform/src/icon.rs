// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Icon helpers: scaling a PNG, PNG-payload `.ico` and `.icns` writers (desktop shortcuts),
//! and raw pixel buffers for the tray icons.

use std::io::Cursor;
use std::path::Path;

use image::imageops::FilterType;
use image::{ImageFormat, RgbaImage};

use crate::error::{PlatformError, Result};

/// Decodes PNG (or any format the `image` build supports) bytes into RGBA.
pub fn decode(bytes: &[u8]) -> Result<RgbaImage> {
    image::load_from_memory(bytes)
        .map(|i| i.to_rgba8())
        .map_err(|e| PlatformError::Other(e.to_string()))
}

/// Scales `img` to `size` x `size` (Lanczos3).
pub fn scaled(img: &RgbaImage, size: u32) -> RgbaImage {
    if img.width() == size && img.height() == size {
        return img.clone();
    }
    image::imageops::resize(img, size, size, FilterType::Lanczos3)
}

/// Encodes `img` as PNG.
pub fn png_bytes(img: &RgbaImage) -> Result<Vec<u8>> {
    let mut out = Cursor::new(Vec::new());
    img.write_to(&mut out, ImageFormat::Png)
        .map_err(|e| PlatformError::Other(e.to_string()))?;
    Ok(out.into_inner())
}

/// An `.ico` holding one PNG image per size (Windows Vista and later read PNG entries).
pub fn ico_bytes(img: &RgbaImage, sizes: &[u32]) -> Result<Vec<u8>> {
    let images: Vec<(u32, Vec<u8>)> = sizes
        .iter()
        .map(|&s| png_bytes(&scaled(img, s)).map(|b| (s, b)))
        .collect::<Result<_>>()?;
    let count = images.len() as u16;
    let mut out = Vec::new();
    out.extend_from_slice(&0u16.to_le_bytes()); // reserved
    out.extend_from_slice(&1u16.to_le_bytes()); // type: icon
    out.extend_from_slice(&count.to_le_bytes());
    let mut offset = 6 + 16 * u32::from(count);
    for (size, data) in &images {
        let dim = if *size >= 256 { 0u8 } else { *size as u8 };
        out.push(dim); // width (0 = 256)
        out.push(dim); // height
        out.push(0); // palette
        out.push(0); // reserved
        out.extend_from_slice(&1u16.to_le_bytes()); // planes
        out.extend_from_slice(&32u16.to_le_bytes()); // bpp
        out.extend_from_slice(&(data.len() as u32).to_le_bytes());
        out.extend_from_slice(&offset.to_le_bytes());
        offset += data.len() as u32;
    }
    for (_, data) in images {
        out.extend_from_slice(&data);
    }
    Ok(out)
}

/// An `.icns` with PNG payloads: `ic08` (256), `ic07` (128) and `ic09` (512).
pub fn icns_bytes(img: &RgbaImage) -> Result<Vec<u8>> {
    let mut body = Vec::new();
    for (kind, size) in [(b"ic07", 128u32), (b"ic08", 256), (b"ic09", 512)] {
        let data = png_bytes(&scaled(img, size))?;
        body.extend_from_slice(kind);
        body.extend_from_slice(&((data.len() + 8) as u32).to_be_bytes());
        body.extend_from_slice(&data);
    }
    let mut out = Vec::with_capacity(body.len() + 8);
    out.extend_from_slice(b"icns");
    out.extend_from_slice(&((body.len() + 8) as u32).to_be_bytes());
    out.extend_from_slice(&body);
    Ok(out)
}

/// Converts the image at `src` to an `.ico` at `dst` (16 to 256 px).
pub fn png_to_ico(src: &Path, dst: &Path) -> Result<()> {
    let img = decode(&std::fs::read(src)?)?;
    let bytes = ico_bytes(&img, &[16, 32, 48, 64, 128, 256])?;
    write_file(dst, &bytes)
}

/// Converts the image at `src` to an `.icns` at `dst`.
pub fn png_to_icns(src: &Path, dst: &Path) -> Result<()> {
    let img = decode(&std::fs::read(src)?)?;
    let bytes = icns_bytes(&img)?;
    write_file(dst, &bytes)
}

fn write_file(dst: &Path, bytes: &[u8]) -> Result<()> {
    if let Some(parent) = dst.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(dst, bytes)?;
    Ok(())
}

/// ARGB32 in network byte order (the StatusNotifierItem pixmap format).
pub fn argb32_be(img: &RgbaImage) -> Vec<u8> {
    let mut out = Vec::with_capacity(img.as_raw().len());
    for px in img.pixels() {
        let [r, g, b, a] = px.0;
        out.extend_from_slice(&[a, r, g, b]);
    }
    out
}

/// BGRA rows (bottom-up order is the caller's concern), for a Windows 32-bpp DIB.
#[cfg_attr(not(windows), allow(dead_code))]
pub(crate) fn bgra(img: &RgbaImage) -> Vec<u8> {
    let mut out = Vec::with_capacity(img.as_raw().len());
    for px in img.pixels() {
        let [r, g, b, a] = px.0;
        out.extend_from_slice(&[b, g, r, a]);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> RgbaImage {
        RgbaImage::from_pixel(4, 4, image::Rgba([10, 20, 30, 255]))
    }

    #[test]
    fn ico_header_and_directory_are_well_formed() {
        let bytes = ico_bytes(&sample(), &[16, 256]).unwrap();
        assert_eq!(&bytes[0..6], &[0, 0, 1, 0, 2, 0]);
        // First entry: 16x16, 32 bpp, data right after the 6 + 2*16 byte header.
        assert_eq!(bytes[6], 16);
        assert_eq!(&bytes[12..14], &32u16.to_le_bytes());
        assert_eq!(u32::from_le_bytes(bytes[18..22].try_into().unwrap()), 38);
        // Second entry: 256 is written as 0.
        assert_eq!(bytes[22], 0);
        assert_eq!(&bytes[38..42], b"\x89PNG");
    }

    #[test]
    fn icns_lengths_add_up() {
        let bytes = icns_bytes(&sample()).unwrap();
        assert_eq!(&bytes[0..4], b"icns");
        assert_eq!(
            u32::from_be_bytes(bytes[4..8].try_into().unwrap()) as usize,
            bytes.len()
        );
        assert_eq!(&bytes[8..12], b"ic07");
    }

    #[test]
    fn pixel_orders() {
        let img = RgbaImage::from_pixel(1, 1, image::Rgba([1, 2, 3, 4]));
        assert_eq!(argb32_be(&img), vec![4, 1, 2, 3]);
        assert_eq!(bgra(&img), vec![3, 2, 1, 4]);
    }

    #[test]
    fn the_bundled_icon_decodes_and_converts() {
        let dir = tempfile::tempdir().unwrap();
        let png = dir.path().join("logo.png");
        std::fs::write(&png, crate::LAUNCHER_ICON_PNG).unwrap();
        png_to_ico(&png, &dir.path().join("a").join("logo.ico")).unwrap();
        png_to_icns(&png, &dir.path().join("logo.icns")).unwrap();
        assert!(dir.path().join("a").join("logo.ico").is_file());
        assert!(png_to_ico(&dir.path().join("missing.png"), &dir.path().join("x.ico")).is_err());
    }
}
