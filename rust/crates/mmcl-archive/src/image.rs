// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Image transcoding (Java `ImageFormatUtilities`) and the icon writers desktop shortcuts use
//! (from Java `DesktopShortcutManager`).
//!
//! Pack logos and backgrounds arrive in whatever format a pack author chose. Anything that is
//! not PNG, JPEG, GIF or BMP is transcoded to PNG in place. GPUI could decode WebP itself, but
//! WebP is still transcoded so the content-addressed image cache (keyed by the SHA-1 of the
//! bytes *after* transcode) keeps the Java folder's layout.

use std::fs::{self, File};
use std::io::{self, BufWriter, Read, Write};
use std::path::{Path, PathBuf};

use image::imageops::FilterType;
use image::{DynamicImage, ImageFormat, RgbaImage};
use mmcl_i18n::tf;

/// Errors from the icon writers.
#[derive(Debug, thiserror::Error)]
pub enum ImageError {
    /// The source image could not be read or decoded.
    #[error("Unable to read image {path}: {source}")]
    Read {
        path: PathBuf,
        #[source]
        source: image::ImageError,
    },
    /// Encoding a scaled image failed.
    #[error("Unable to encode image: {0}")]
    Encode(#[source] image::ImageError),
    /// Building the ICNS container failed.
    #[error("Unable to build the ICNS icon: {0}")]
    Icns(#[source] io::Error),
    /// Writing the output file failed.
    #[error(transparent)]
    Io(#[from] io::Error),
}

/// The ICO sizes, largest first, as Java writes them.
pub const ICO_SIZES: [u32; 4] = [256, 48, 32, 16];

/// `true` when the first bytes match a format every front end decodes natively: PNG
/// (`89 50 4E 47`), JPEG (`FF D8 FF`), GIF (`47 49 46 38`) or BMP (`42 4D`).
pub fn matches_native_magic(head: &[u8]) -> bool {
    head.starts_with(&[0x89, 0x50, 0x4E, 0x47])
        || head.starts_with(&[0xFF, 0xD8, 0xFF])
        || head.starts_with(&[0x47, 0x49, 0x46, 0x38])
        || head.starts_with(&[0x42, 0x4D])
}

/// `true` for a regular file at least 8 bytes long whose first 12 bytes match a native format.
/// A file shorter than 12 bytes cannot be sniffed and gives `false`, as in Java.
pub fn is_natively_decodable(path: &Path) -> bool {
    let Ok(meta) = fs::metadata(path) else {
        return false;
    };
    if !meta.is_file() || meta.len() < 8 {
        return false;
    }
    let mut head = [0u8; 12];
    match File::open(path).and_then(|mut f| f.read_exact(&mut head)) {
        Ok(()) => matches_native_magic(&head),
        Err(_) => false,
    }
}

/// Makes `path` natively decodable, transcoding it to PNG in place when needed.
///
/// Already natively decodable gives `true`. Otherwise the image is decoded (WebP, TIFF, ICO
/// and the native formats); an undecodable file gives `false` and is left alone. A decoded
/// image is written as PNG to `<file>.transcode.tmp`, then renamed over the file. The temp
/// file never outlives the call.
pub fn ensure_decodable(path: &Path) -> bool {
    if !path.is_file() {
        return false;
    }
    if is_natively_decodable(path) {
        return true;
    }
    let decoded = match image::ImageReader::open(path).and_then(|r| r.with_guessed_format()) {
        Ok(reader) => {
            if reader.format().is_none() {
                // No decoder claims the bytes: not transcodable, and not worth a warning
                // (Java's ImageIO.read returns null here without logging).
                return false;
            }
            reader.decode()
        }
        Err(e) => {
            tracing::warn!("{}", tf!("log.imageFormat.readFailed", path, e.to_string()));
            return false;
        }
    };
    let img = match decoded {
        Ok(img) => img,
        Err(e) => {
            tracing::warn!("{}", tf!("log.imageFormat.readFailed", path, e.to_string()));
            return false;
        }
    };

    let temp = sibling_with_suffix(path, ".transcode.tmp");
    let result = (|| -> Result<(), String> {
        let file = File::create(&temp).map_err(|e| e.to_string())?;
        let mut out = BufWriter::new(file);
        if let Err(e) = img.write_to(&mut out, ImageFormat::Png) {
            tracing::warn!("{}", tf!("log.imageFormat.writeFailed", path));
            return Err(e.to_string());
        }
        out.flush().map_err(|e| e.to_string())?;
        drop(out);
        fs::rename(&temp, path).map_err(|e| e.to_string())
    })();
    let ok = match result {
        Ok(()) => true,
        Err(msg) => {
            tracing::warn!("{}", tf!("log.imageFormat.transcodeFailed", path, msg));
            false
        }
    };
    if temp.exists() {
        let _ = fs::remove_file(&temp);
    }
    ok
}

/// Scales `img` to `width` x `height` RGBA with Lanczos3 (Java uses area averaging).
pub fn scale_rgba(img: &DynamicImage, width: u32, height: u32) -> RgbaImage {
    image::imageops::resize(&img.to_rgba8(), width, height, FilterType::Lanczos3)
}

/// Encodes `img` as PNG bytes.
pub fn encode_png(img: &RgbaImage) -> Result<Vec<u8>, ImageError> {
    let mut buf = io::Cursor::new(Vec::new());
    img.write_to(&mut buf, ImageFormat::Png)
        .map_err(ImageError::Encode)?;
    Ok(buf.into_inner())
}

/// Builds an ICO container holding one PNG payload per image: `ICONDIR` (reserved 0, type 1,
/// count), one 16-byte `ICONDIRENTRY` each (a width or height of 256 is written as 0; palette
/// 0, reserved 0, planes 1, 32 bpp, size, offset), then the payloads. All little-endian.
///
/// `images` pairs each square size with its PNG bytes.
pub fn build_ico(images: &[(u32, Vec<u8>)]) -> Vec<u8> {
    let count = images.len() as u16;
    let mut out = Vec::new();
    out.extend_from_slice(&0u16.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&count.to_le_bytes());
    let mut offset = 6 + 16 * images.len() as u32;
    for (size, png) in images {
        let dim = if *size >= 256 { 0 } else { *size as u8 };
        out.push(dim);
        out.push(dim);
        out.push(0);
        out.push(0);
        out.extend_from_slice(&1u16.to_le_bytes());
        out.extend_from_slice(&32u16.to_le_bytes());
        out.extend_from_slice(&(png.len() as u32).to_le_bytes());
        out.extend_from_slice(&offset.to_le_bytes());
        offset += png.len() as u32;
    }
    for (_, png) in images {
        out.extend_from_slice(png);
    }
    out
}

/// Converts the image at `src` to an ICO at `dst` with PNG payloads at 256, 48, 32 and 16
/// pixels.
pub fn png_to_ico(src: &Path, dst: &Path) -> Result<(), ImageError> {
    let original = open_image(src)?;
    let mut images = Vec::with_capacity(ICO_SIZES.len());
    for size in ICO_SIZES {
        images.push((size, encode_png(&scale_rgba(&original, size, size))?));
    }
    write_atomically(dst, &build_ico(&images))
}

/// Converts the image at `src` to an ICNS at `dst`, in process (Java ran `iconutil` on an
/// `.iconset`). Holds the same sizes as that iconset: 16, 32, 128, 256 and 512 points, each at
/// 1x and 2x. A caller that gets an error falls back to the PNG.
pub fn png_to_icns(src: &Path, dst: &Path) -> Result<(), ImageError> {
    use icns::{IconFamily, IconType, Image, PixelFormat};
    let original = open_image(src)?;
    let types = [
        (IconType::RGBA32_16x16, 16),
        (IconType::RGBA32_16x16_2x, 32),
        (IconType::RGBA32_32x32, 32),
        (IconType::RGBA32_32x32_2x, 64),
        (IconType::RGBA32_128x128, 128),
        (IconType::RGBA32_128x128_2x, 256),
        (IconType::RGBA32_256x256, 256),
        (IconType::RGBA32_256x256_2x, 512),
        (IconType::RGBA32_512x512, 512),
        (IconType::RGBA32_512x512_2x, 1024),
    ];
    let mut family = IconFamily::new();
    for (icon_type, pixels) in types {
        let scaled = scale_rgba(&original, pixels, pixels);
        let image = Image::from_data(PixelFormat::RGBA, pixels, pixels, scaled.into_raw())
            .map_err(ImageError::Icns)?;
        family
            .add_icon_with_type(&image, icon_type)
            .map_err(ImageError::Icns)?;
    }
    let mut bytes = Vec::new();
    family.write(&mut bytes).map_err(ImageError::Icns)?;
    write_atomically(dst, &bytes)
}

fn open_image(src: &Path) -> Result<DynamicImage, ImageError> {
    let read_err = |source| ImageError::Read {
        path: src.to_path_buf(),
        source,
    };
    image::ImageReader::open(src)
        .map_err(|e| read_err(image::ImageError::IoError(e)))?
        .with_guessed_format()
        .map_err(|e| read_err(image::ImageError::IoError(e)))?
        .decode()
        .map_err(read_err)
}

fn sibling_with_suffix(path: &Path, suffix: &str) -> PathBuf {
    let mut s = path.as_os_str().to_owned();
    s.push(suffix);
    PathBuf::from(s)
}

/// Writes `bytes` to a sibling temp file and renames it over `dst`.
fn write_atomically(dst: &Path, bytes: &[u8]) -> Result<(), ImageError> {
    if let Some(parent) = dst.parent().filter(|p| !p.as_os_str().is_empty()) {
        fs::create_dir_all(parent)?;
    }
    let temp = sibling_with_suffix(dst, &format!(".tmp-{}", uuid::Uuid::new_v4()));
    let result = (|| -> io::Result<()> {
        let mut f = File::create(&temp)?;
        f.write_all(bytes)?;
        f.sync_all()?;
        drop(f);
        fs::rename(&temp, dst)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temp);
    }
    Ok(result?)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(dir: &Path, name: &str, format: ImageFormat) -> PathBuf {
        let img = RgbaImage::from_fn(20, 10, |x, y| {
            image::Rgba([x as u8 * 10, y as u8 * 20, 128, 255])
        });
        let path = dir.join(name);
        DynamicImage::ImageRgba8(img)
            .save_with_format(&path, format)
            .unwrap();
        path
    }

    #[test]
    fn magic_bytes() {
        assert!(matches_native_magic(&[
            0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        ]));
        assert!(matches_native_magic(&[0xFF, 0xD8, 0xFF, 0xE0]));
        assert!(matches_native_magic(b"GIF89a"));
        assert!(matches_native_magic(b"BM\0\0"));
        assert!(!matches_native_magic(b"RIFF\0\0\0\0WEBP"));
        assert!(!matches_native_magic(b""));
    }

    #[test]
    fn short_or_missing_files_are_not_natively_decodable() {
        let dir = tempfile::tempdir().unwrap();
        assert!(!is_natively_decodable(&dir.path().join("missing.png")));
        assert!(!is_natively_decodable(dir.path()));
        let short = dir.path().join("short.png");
        fs::write(&short, [0x89, 0x50, 0x4E, 0x47, 0, 0, 0, 0, 0]).unwrap();
        assert!(
            !is_natively_decodable(&short),
            "fewer than 12 bytes cannot be sniffed"
        );
    }

    #[test]
    fn a_png_is_left_alone() {
        let dir = tempfile::tempdir().unwrap();
        let png = sample(dir.path(), "logo.png", ImageFormat::Png);
        let before = fs::read(&png).unwrap();
        assert!(is_natively_decodable(&png));
        assert!(ensure_decodable(&png));
        assert_eq!(fs::read(&png).unwrap(), before);
    }

    #[test]
    fn webp_is_transcoded_to_png_in_place() {
        let dir = tempfile::tempdir().unwrap();
        let webp = sample(dir.path(), "logo.img", ImageFormat::WebP);
        assert!(!is_natively_decodable(&webp));
        assert!(ensure_decodable(&webp));
        assert!(is_natively_decodable(&webp));
        let img = image::load_from_memory_with_format(&fs::read(&webp).unwrap(), ImageFormat::Png)
            .unwrap();
        assert_eq!((img.width(), img.height()), (20, 10));
        assert!(!sibling_with_suffix(&webp, ".transcode.tmp").exists());
    }

    #[test]
    fn undecodable_bytes_are_left_alone() {
        let dir = tempfile::tempdir().unwrap();
        let junk = dir.path().join("junk.png");
        fs::write(&junk, b"this is definitely not an image file").unwrap();
        assert!(!ensure_decodable(&junk));
        assert_eq!(
            fs::read(&junk).unwrap(),
            b"this is definitely not an image file"
        );
        assert!(!sibling_with_suffix(&junk, ".transcode.tmp").exists());
        assert!(!ensure_decodable(&dir.path().join("missing")));
    }

    #[test]
    fn ico_header_bytes() {
        let ico = build_ico(&[(256, vec![1, 2, 3]), (16, vec![4, 5])]);
        assert_eq!(&ico[0..6], &[0, 0, 1, 0, 2, 0]);
        // First entry: 256 written as 0, offset 6 + 16 * 2 = 38.
        assert_eq!(
            &ico[6..22],
            &[0, 0, 0, 0, 1, 0, 32, 0, 3, 0, 0, 0, 38, 0, 0, 0]
        );
        // Second entry follows the first payload.
        assert_eq!(
            &ico[22..38],
            &[16, 16, 0, 0, 1, 0, 32, 0, 2, 0, 0, 0, 41, 0, 0, 0]
        );
        assert_eq!(&ico[38..], &[1, 2, 3, 4, 5]);
    }

    #[test]
    fn png_to_ico_writes_four_png_payloads() {
        let dir = tempfile::tempdir().unwrap();
        let png = sample(dir.path(), "logo.png", ImageFormat::Png);
        let ico = dir.path().join("out").join("icon.ico");
        png_to_ico(&png, &ico).unwrap();
        let bytes = fs::read(&ico).unwrap();
        assert_eq!(u16::from_le_bytes([bytes[4], bytes[5]]), 4);
        let sizes: Vec<u8> = (0..4).map(|i| bytes[6 + 16 * i]).collect();
        assert_eq!(sizes, vec![0, 48, 32, 16]);
        let first_offset = u32::from_le_bytes(bytes[18..22].try_into().unwrap()) as usize;
        assert_eq!(first_offset, 6 + 16 * 4);
        assert!(matches_native_magic(&bytes[first_offset..]));
        // The reader in `image` accepts it.
        let decoded = image::load_from_memory_with_format(&bytes, ImageFormat::Ico).unwrap();
        assert_eq!(decoded.width(), 256);
    }

    #[test]
    fn png_to_icns_writes_a_readable_family() {
        let dir = tempfile::tempdir().unwrap();
        let png = sample(dir.path(), "logo.png", ImageFormat::Png);
        let icns_path = dir.path().join("icon.icns");
        png_to_icns(&png, &icns_path).unwrap();
        let bytes = fs::read(&icns_path).unwrap();
        assert_eq!(&bytes[0..4], b"icns");
        let family = icns::IconFamily::read(io::Cursor::new(bytes)).unwrap();
        assert!(family.has_icon_with_type(icns::IconType::RGBA32_512x512_2x));
        assert!(family.has_icon_with_type(icns::IconType::RGBA32_16x16));
    }

    #[test]
    fn icon_writers_report_an_unreadable_source() {
        let dir = tempfile::tempdir().unwrap();
        let junk = dir.path().join("junk.png");
        fs::write(&junk, b"nope").unwrap();
        assert!(matches!(
            png_to_ico(&junk, &dir.path().join("a.ico")),
            Err(ImageError::Read { .. })
        ));
        assert!(png_to_icns(&junk, &dir.path().join("a.icns")).is_err());
        assert!(!dir.path().join("a.ico").exists());
    }
}
