// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Windows application manifest, written into the built executable after linking.
//!
//! gpui-pre-platform 0.3.11 turns on gpui's `windows-manifest` feature unconditionally, so the
//! link already carries GPUI's manifest as RT_MANIFEST id 1 and mmcl-app cannot embed a second
//! one (CVT1100 duplicate resource). Ours (`crates/mmcl-app/windows/app.manifest`) adds
//! `longPathAware` and the UTF-8 `activeCodePage` to what GPUI sets, so `cargo xtask dist
//! windows` replaces GPUI's resource with it before signing:
//!
//! 1. read the exe's resource tree with the small PE reader below and note every RT_MANIFEST
//!    entry (and its language),
//! 2. rewrite it with the Win32 resource updater (`BeginUpdateResourceW` / `UpdateResourceW` /
//!    `EndUpdateResourceW`, in kernel32 on every Windows, so no Windows SDK or `mt.exe` is
//!    needed): delete every existing manifest, write ours as id 1 in the language GPUI's had,
//! 3. read the exe back and check there is exactly one manifest and that it is ours, with each
//!    setting the launcher depends on ([`REQUIRED`]).
//!
//! `cargo xtask win-manifest <exe> [--check]` runs the same steps (or only step 3) on any exe,
//! which is how a dev build gets the manifest too.

use std::path::Path;

use anyhow::{Context, Result, bail};

/// `RT_MANIFEST`.
pub const RT_MANIFEST: u16 = 24;
/// `CREATEPROCESS_MANIFEST_RESOURCE_ID`: the manifest the loader reads for an exe.
pub const MANIFEST_ID: u16 = 1;
/// Language for the manifest when the exe has none yet: en-US, as link.exe and rc.exe use.
pub const DEFAULT_LANG: u16 = 0x0409;

/// The manifest source, relative to the workspace.
pub const SOURCE: &str = "crates/mmcl-app/windows/app.manifest";

/// Settings a manifest must carry, each as (what it is, a substring of the XML that proves it).
pub const REQUIRED: &[(&str, &str)] = &[
    ("longPathAware", "<longPathAware"),
    ("long paths enabled", ">true</longPathAware>"),
    ("activeCodePage", "<activeCodePage"),
    ("UTF-8 code page", ">UTF-8</activeCodePage>"),
    ("per-monitor v2 DPI", ">PerMonitorV2</dpiAwareness>"),
    (
        "Common Controls v6",
        "name=\"Microsoft.Windows.Common-Controls\"",
    ),
    (
        "Windows 10/11 supportedOS",
        "{8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a}",
    ),
    ("asInvoker", "level=\"asInvoker\""),
];

/// A resource type or name: a 16-bit id or a string.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ResName {
    Id(u16),
    Name(String),
}

/// One leaf of a PE resource tree.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Resource {
    pub kind: ResName,
    pub name: ResName,
    pub lang: u16,
    pub data: Vec<u8>,
}

/// Every resource in a PE image. An image without a resource directory has none.
pub fn read_resources(image: &[u8]) -> Result<Vec<Resource>> {
    let pe = Pe::parse(image)?;
    let Some((rva, size)) = pe.resource_dir else {
        return Ok(Vec::new());
    };
    if rva == 0 || size == 0 {
        return Ok(Vec::new());
    }
    let base = pe
        .offset_of(rva)
        .context("resource directory is outside every section")?;
    let mut out = Vec::new();
    for (kind, kind_dir) in pe.dir_entries(base, 0)? {
        let Entry::Dir(kind_dir) = kind_dir else {
            bail!("resource type entry is not a directory");
        };
        for (name, name_dir) in pe.dir_entries(base, kind_dir)? {
            let Entry::Dir(name_dir) = name_dir else {
                bail!("resource name entry is not a directory");
            };
            for (lang, leaf) in pe.dir_entries(base, name_dir)? {
                let Entry::Leaf(leaf) = leaf else {
                    bail!("resource language entry is a directory");
                };
                let ResName::Id(lang) = lang else {
                    bail!("resource language is not a numeric id");
                };
                out.push(Resource {
                    kind: kind.clone(),
                    name: name.clone(),
                    lang,
                    data: pe.leaf_data(base + leaf as usize)?,
                });
            }
        }
    }
    Ok(out)
}

/// The RT_MANIFEST resources of a PE image.
pub fn manifests(image: &[u8]) -> Result<Vec<Resource>> {
    Ok(read_resources(image)?
        .into_iter()
        .filter(|r| r.kind == ResName::Id(RT_MANIFEST))
        .collect())
}

/// The settings from [`REQUIRED`] a manifest lacks (empty: it has them all).
pub fn missing_settings(xml: &str) -> Vec<&'static str> {
    REQUIRED
        .iter()
        .filter(|(_, needle)| !xml.contains(needle))
        .map(|(what, _)| *what)
        .collect()
}

/// The manifest text without a UTF-8 byte-order mark.
fn manifest_text(bytes: &[u8]) -> Result<&str> {
    let bytes = bytes.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(bytes);
    std::str::from_utf8(bytes).context("the manifest is not UTF-8")
}

/// What to change in an exe's resources: languages to delete under (RT_MANIFEST, id 1), other
/// manifest names to delete, and the language to write ours in.
#[derive(Debug, PartialEq, Eq)]
pub struct Plan {
    pub delete: Vec<(ResName, u16)>,
    pub lang: u16,
}

/// Plans the replacement: keep the language of the existing id 1 manifest (GPUI's), drop every
/// other manifest entry so the loader can only ever see ours.
pub fn plan(existing: &[Resource]) -> Plan {
    let lang = existing
        .iter()
        .find(|r| r.name == ResName::Id(MANIFEST_ID))
        .map(|r| r.lang)
        .unwrap_or(DEFAULT_LANG);
    let delete = existing
        .iter()
        .filter(|r| !(r.name == ResName::Id(MANIFEST_ID) && r.lang == lang))
        .map(|r| (r.name.clone(), r.lang))
        .collect();
    Plan { delete, lang }
}

/// Checks that `exe` carries exactly one manifest, id 1, equal to `expected` (when given) and
/// with every [`REQUIRED`] setting. Returns the manifest text.
pub fn verify(exe: &Path, expected: Option<&[u8]>) -> Result<String> {
    let image = std::fs::read(exe).with_context(|| format!("cannot read {}", exe.display()))?;
    let found = manifests(&image)?;
    let [only] = found.as_slice() else {
        bail!(
            "{} has {} RT_MANIFEST resources; expected exactly one",
            exe.display(),
            found.len()
        );
    };
    if only.name != ResName::Id(MANIFEST_ID) {
        bail!(
            "{}: the manifest is {:?}, not id 1",
            exe.display(),
            only.name
        );
    }
    if let Some(expected) = expected
        && only.data != expected
    {
        bail!("{}: the embedded manifest is not ours", exe.display());
    }
    let text = manifest_text(&only.data)?.to_string();
    let missing = missing_settings(&text);
    if !missing.is_empty() {
        bail!(
            "{}: the embedded manifest lacks {}",
            exe.display(),
            missing.join(", ")
        );
    }
    Ok(text)
}

/// Replaces the manifest of `exe` with the file at `manifest`, then verifies it.
pub fn apply(exe: &Path, manifest: &Path) -> Result<()> {
    let data =
        std::fs::read(manifest).with_context(|| format!("cannot read {}", manifest.display()))?;
    let missing = missing_settings(manifest_text(&data)?);
    if !missing.is_empty() {
        bail!("{} lacks {}", manifest.display(), missing.join(", "));
    }
    let image = std::fs::read(exe).with_context(|| format!("cannot read {}", exe.display()))?;
    let plan = plan(&manifests(&image)?);
    drop(image);
    update::write(exe, &plan, &data)?;
    verify(exe, Some(&data))?;
    eprintln!(
        "xtask: wrote {} into {} (RT_MANIFEST #1, language 0x{:04x})",
        manifest.display(),
        exe.display(),
        plan.lang
    );
    Ok(())
}

#[cfg(windows)]
mod update {
    use std::ffi::c_void;
    use std::os::windows::ffi::OsStrExt;
    use std::path::Path;

    use anyhow::{Result, bail};

    use super::{MANIFEST_ID, Plan, RT_MANIFEST, ResName};

    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn BeginUpdateResourceW(file: *const u16, delete_existing: i32) -> *mut c_void;
        fn UpdateResourceW(
            update: *mut c_void,
            kind: *const u16,
            name: *const u16,
            lang: u16,
            data: *const c_void,
            size: u32,
        ) -> i32;
        fn EndUpdateResourceW(update: *mut c_void, discard: i32) -> i32;
    }

    /// `MAKEINTRESOURCEW`, or a NUL-terminated string kept alive in `hold`.
    fn res_ptr(name: &ResName, hold: &mut Vec<Vec<u16>>) -> *const u16 {
        match name {
            ResName::Id(id) => usize::from(*id) as *const u16,
            ResName::Name(s) => {
                hold.push(s.encode_utf16().chain([0]).collect());
                hold.last().map_or(std::ptr::null(), |v| v.as_ptr())
            }
        }
    }

    pub fn write(exe: &Path, plan: &Plan, data: &[u8]) -> Result<()> {
        let wide: Vec<u16> = exe.as_os_str().encode_wide().chain([0]).collect();
        let size = u32::try_from(data.len())?;
        let kind = usize::from(RT_MANIFEST) as *const u16;
        // SAFETY: `wide` is NUL-terminated and outlives the call.
        let handle = unsafe { BeginUpdateResourceW(wide.as_ptr(), 0) };
        if handle.is_null() {
            bail!(
                "BeginUpdateResourceW failed for {}: {}",
                exe.display(),
                std::io::Error::last_os_error()
            );
        }
        let mut hold = Vec::new();
        let mut ok = true;
        for (name, lang) in &plan.delete {
            let name = res_ptr(name, &mut hold);
            // SAFETY: a null buffer with size 0 deletes the entry; the pointers are valid ids
            // or strings held in `hold`.
            ok &= unsafe { UpdateResourceW(handle, kind, name, *lang, std::ptr::null(), 0) } != 0;
        }
        let name = usize::from(MANIFEST_ID) as *const u16;
        // SAFETY: `data` is valid for `size` bytes for the duration of the call (the updater
        // copies it).
        ok &= unsafe { UpdateResourceW(handle, kind, name, plan.lang, data.as_ptr().cast(), size) }
            != 0;
        let err = std::io::Error::last_os_error();
        // SAFETY: `handle` came from BeginUpdateResourceW and is ended exactly once.
        let ended = unsafe { EndUpdateResourceW(handle, i32::from(!ok)) } != 0;
        if !ok {
            bail!("UpdateResourceW failed for {}: {err}", exe.display());
        }
        if !ended {
            bail!(
                "EndUpdateResourceW failed for {}: {}",
                exe.display(),
                std::io::Error::last_os_error()
            );
        }
        Ok(())
    }
}

#[cfg(not(windows))]
mod update {
    use std::path::Path;

    use anyhow::{Result, bail};

    use super::Plan;

    pub fn write(exe: &Path, _plan: &Plan, _data: &[u8]) -> Result<()> {
        bail!(
            "writing the manifest into {} needs the Win32 resource updater; run this on Windows",
            exe.display()
        )
    }
}

/// A resource directory entry's target, as an offset from the resource section start.
enum Entry {
    Dir(u32),
    Leaf(u32),
}

/// The parts of a PE image the resource reader needs.
struct Pe<'a> {
    image: &'a [u8],
    /// (virtual address, virtual size, raw offset, raw size) per section.
    sections: Vec<(u32, u32, u32, u32)>,
    resource_dir: Option<(u32, u32)>,
}

fn u16_at(b: &[u8], off: usize) -> Result<u16> {
    let s = b
        .get(off..off + 2)
        .with_context(|| format!("PE image is truncated at {off:#x}"))?;
    Ok(u16::from_le_bytes([s[0], s[1]]))
}

fn u32_at(b: &[u8], off: usize) -> Result<u32> {
    let s = b
        .get(off..off + 4)
        .with_context(|| format!("PE image is truncated at {off:#x}"))?;
    Ok(u32::from_le_bytes([s[0], s[1], s[2], s[3]]))
}

impl<'a> Pe<'a> {
    fn parse(image: &'a [u8]) -> Result<Pe<'a>> {
        if image.get(..2) != Some(b"MZ") {
            bail!("not a PE image (no MZ header)");
        }
        let pe = u32_at(image, 0x3c)? as usize;
        if image.get(pe..pe + 4) != Some(b"PE\0\0") {
            bail!("not a PE image (no PE signature)");
        }
        let coff = pe + 4;
        let sections = u16_at(image, coff + 2)? as usize;
        let opt_size = u16_at(image, coff + 16)? as usize;
        let opt = coff + 20;
        let (count_at, dirs_at) = match u16_at(image, opt)? {
            0x10b => (opt + 92, opt + 96),
            0x20b => (opt + 108, opt + 112),
            m => bail!("unknown PE optional header magic {m:#x}"),
        };
        let dir_count = u32_at(image, count_at)?;
        let resource_dir = if dir_count > 2 && dirs_at + 24 <= opt + opt_size {
            Some((u32_at(image, dirs_at + 16)?, u32_at(image, dirs_at + 20)?))
        } else {
            None
        };
        let table = opt + opt_size;
        let mut list = Vec::with_capacity(sections);
        for i in 0..sections {
            let s = table + i * 40;
            list.push((
                u32_at(image, s + 12)?,
                u32_at(image, s + 8)?,
                u32_at(image, s + 20)?,
                u32_at(image, s + 16)?,
            ));
        }
        Ok(Pe {
            image,
            sections: list,
            resource_dir,
        })
    }

    /// File offset of a virtual address that has bytes in the file.
    fn offset_of(&self, rva: u32) -> Option<usize> {
        self.sections.iter().find_map(|&(va, vsize, raw, rsize)| {
            let off = rva.checked_sub(va)?;
            let len = if vsize == 0 { rsize } else { vsize.min(rsize) };
            (off < len).then(|| raw as usize + off as usize)
        })
    }

    /// Entries of the resource directory at `base + dir`. Offsets are relative to the resource
    /// section start `base`. The tree is walked to a fixed depth of three, so a malformed
    /// image cannot loop.
    fn dir_entries(&self, base: usize, dir: u32) -> Result<Vec<(ResName, Entry)>> {
        let at = base + dir as usize;
        let named = u16_at(self.image, at + 12)? as usize;
        let ids = u16_at(self.image, at + 14)? as usize;
        let mut out = Vec::with_capacity(named + ids);
        for i in 0..named + ids {
            let e = at + 16 + i * 8;
            let name = u32_at(self.image, e)?;
            let target = u32_at(self.image, e + 4)?;
            let name = if name & 0x8000_0000 != 0 {
                let s = base + (name & 0x7fff_ffff) as usize;
                let len = u16_at(self.image, s)? as usize;
                let mut units = Vec::with_capacity(len);
                for k in 0..len {
                    units.push(u16_at(self.image, s + 2 + k * 2)?);
                }
                ResName::Name(String::from_utf16_lossy(&units))
            } else {
                ResName::Id((name & 0xffff) as u16)
            };
            let entry = if target & 0x8000_0000 != 0 {
                Entry::Dir(target & 0x7fff_ffff)
            } else {
                Entry::Leaf(target)
            };
            out.push((name, entry));
        }
        Ok(out)
    }

    /// The bytes an IMAGE_RESOURCE_DATA_ENTRY points at.
    fn leaf_data(&self, entry: usize) -> Result<Vec<u8>> {
        let rva = u32_at(self.image, entry)?;
        let size = u32_at(self.image, entry + 4)? as usize;
        let off = self
            .offset_of(rva)
            .context("resource data is outside every section")?;
        Ok(self
            .image
            .get(off..off + size)
            .context("resource data runs past the end of the image")?
            .to_vec())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const APP_MANIFEST: &str = include_str!("../../crates/mmcl-app/windows/app.manifest");

    /// A minimal PE32+ image with one `.rsrc` section holding `res` (type id, name id, lang,
    /// data), laid out the way rc.exe/cvtres do: type, name and language directories, then data
    /// entries, then the data.
    fn pe_with(res: &[(u16, u16, u16, &[u8])]) -> Vec<u8> {
        const RVA: u32 = 0x1000;
        const RAW: usize = 0x400;
        let mut sorted: Vec<_> = res.to_vec();
        sorted.sort_by_key(|r| (r.0, r.1, r.2));
        let mut kinds: Vec<u16> = sorted.iter().map(|r| r.0).collect();
        kinds.dedup();

        // Directory sizes: 16 + 8 per entry.
        let mut rsrc = Vec::new();
        let dir = |entries: &[(u32, u32)]| {
            let mut d = vec![0u8; 12];
            d.extend_from_slice(&0u16.to_le_bytes());
            d.extend_from_slice(&(entries.len() as u16).to_le_bytes());
            for (n, t) in entries {
                d.extend_from_slice(&n.to_le_bytes());
                d.extend_from_slice(&t.to_le_bytes());
            }
            d
        };
        // Pass 1: compute offsets.
        let root_len = 16 + 8 * kinds.len();
        let mut off = root_len;
        let mut name_dirs = Vec::new();
        for k in &kinds {
            let mut names: Vec<u16> = sorted.iter().filter(|r| r.0 == *k).map(|r| r.1).collect();
            names.dedup();
            name_dirs.push((off, *k, names.clone()));
            off += 16 + 8 * names.len();
        }
        let mut lang_dirs = Vec::new();
        for (_, k, names) in &name_dirs {
            for n in names {
                let langs: Vec<u16> = sorted
                    .iter()
                    .filter(|r| r.0 == *k && r.1 == *n)
                    .map(|r| r.2)
                    .collect();
                lang_dirs.push((off, *k, *n, langs.clone()));
                off += 16 + 8 * langs.len();
            }
        }
        let entries_at = off;
        let data_at = entries_at + 16 * sorted.len();
        // Pass 2: write.
        rsrc.extend(dir(&name_dirs
            .iter()
            .map(|(o, k, _)| (u32::from(*k), *o as u32 | 0x8000_0000))
            .collect::<Vec<_>>()));
        for (_, k, names) in &name_dirs {
            let entries: Vec<(u32, u32)> = names
                .iter()
                .map(|n| {
                    let o = lang_dirs.iter().find(|l| l.1 == *k && l.2 == *n).unwrap().0;
                    (u32::from(*n), o as u32 | 0x8000_0000)
                })
                .collect();
            rsrc.extend(dir(&entries));
        }
        for (_, k, n, langs) in &lang_dirs {
            let entries: Vec<(u32, u32)> = langs
                .iter()
                .map(|l| {
                    let idx = sorted
                        .iter()
                        .position(|r| r.0 == *k && r.1 == *n && r.2 == *l)
                        .unwrap();
                    (u32::from(*l), (entries_at + 16 * idx) as u32)
                })
                .collect();
            rsrc.extend(dir(&entries));
        }
        let mut data_off = data_at;
        for r in &sorted {
            rsrc.extend_from_slice(&(RVA + data_off as u32).to_le_bytes());
            rsrc.extend_from_slice(&(r.3.len() as u32).to_le_bytes());
            rsrc.extend_from_slice(&[0u8; 8]);
            data_off += r.3.len().next_multiple_of(8);
        }
        for r in &sorted {
            rsrc.extend_from_slice(r.3);
            rsrc.resize(rsrc.len().next_multiple_of(8), 0);
        }

        let mut img = vec![0u8; RAW];
        img[..2].copy_from_slice(b"MZ");
        img[0x3c..0x40].copy_from_slice(&0x80u32.to_le_bytes());
        img[0x80..0x84].copy_from_slice(b"PE\0\0");
        let coff = 0x84;
        img[coff..coff + 2].copy_from_slice(&0x8664u16.to_le_bytes());
        img[coff + 2..coff + 4].copy_from_slice(&1u16.to_le_bytes());
        let opt_size: u16 = 112 + 16 * 8;
        img[coff + 16..coff + 18].copy_from_slice(&opt_size.to_le_bytes());
        let opt = coff + 20;
        img[opt..opt + 2].copy_from_slice(&0x20bu16.to_le_bytes());
        img[opt + 108..opt + 112].copy_from_slice(&16u32.to_le_bytes());
        img[opt + 112 + 16..opt + 112 + 20].copy_from_slice(&RVA.to_le_bytes());
        img[opt + 112 + 20..opt + 112 + 24].copy_from_slice(&(rsrc.len() as u32).to_le_bytes());
        let sec = opt + opt_size as usize;
        img[sec..sec + 5].copy_from_slice(b".rsrc");
        img[sec + 8..sec + 12].copy_from_slice(&(rsrc.len() as u32).to_le_bytes());
        img[sec + 12..sec + 16].copy_from_slice(&RVA.to_le_bytes());
        img[sec + 16..sec + 20].copy_from_slice(&(rsrc.len() as u32).to_le_bytes());
        img[sec + 20..sec + 24].copy_from_slice(&(RAW as u32).to_le_bytes());
        img.extend_from_slice(&rsrc);
        img
    }

    #[test]
    fn shipped_manifest_has_every_required_setting() {
        assert_eq!(missing_settings(APP_MANIFEST), Vec::<&str>::new());
    }

    #[test]
    fn gpui_style_manifest_lacks_long_paths_and_utf8() {
        let gpui = APP_MANIFEST
            .lines()
            .filter(|l| !l.contains("longPathAware") && !l.contains("activeCodePage"))
            .collect::<Vec<_>>()
            .join("\n");
        assert_eq!(
            missing_settings(&gpui),
            vec![
                "longPathAware",
                "long paths enabled",
                "activeCodePage",
                "UTF-8 code page"
            ]
        );
    }

    #[test]
    fn reads_every_resource_leaf() {
        let img = pe_with(&[
            (3, 1, 0x409, b"icon-a"),
            (24, 1, 0x409, b"<assembly/>"),
            (16, 1, 0x409, b"version"),
            (3, 2, 0x409, b"icon-b"),
        ]);
        let all = read_resources(&img).unwrap();
        assert_eq!(all.len(), 4);
        let m = manifests(&img).unwrap();
        assert_eq!(
            m,
            vec![Resource {
                kind: ResName::Id(24),
                name: ResName::Id(1),
                lang: 0x409,
                data: b"<assembly/>".to_vec(),
            }]
        );
        assert!(
            all.iter()
                .any(|r| r.name == ResName::Id(2) && r.data == b"icon-b")
        );
    }

    #[test]
    fn image_without_resources_has_none() {
        let mut img = pe_with(&[(3, 1, 0, b"x")]);
        // Zero the resource data directory.
        let opt = 0x84 + 20;
        img[opt + 128..opt + 136].fill(0);
        assert!(read_resources(&img).unwrap().is_empty());
    }

    #[test]
    fn rejects_non_pe_and_truncated_input() {
        assert!(read_resources(b"not a pe").is_err());
        let img = pe_with(&[(24, 1, 0x409, b"<assembly/>")]);
        assert!(read_resources(&img[..0x100]).is_err());
        assert!(read_resources(&img[..img.len() - 20]).is_err());
    }

    #[test]
    fn plan_keeps_gpui_language_and_drops_strays() {
        let res = |name: u16, lang: u16| Resource {
            kind: ResName::Id(RT_MANIFEST),
            name: ResName::Id(name),
            lang,
            data: Vec::new(),
        };
        assert_eq!(
            plan(&[res(1, 0)]),
            Plan {
                delete: vec![],
                lang: 0
            }
        );
        assert_eq!(
            plan(&[res(1, 0x409), res(1, 0), res(2, 0x409)]),
            Plan {
                delete: vec![(ResName::Id(1), 0), (ResName::Id(2), 0x409)],
                lang: 0x409
            }
        );
        assert_eq!(
            plan(&[]),
            Plan {
                delete: vec![],
                lang: DEFAULT_LANG
            }
        );
    }

    #[test]
    fn verify_accepts_only_one_complete_manifest() {
        let dir = tempfile::tempdir().unwrap();
        let exe = dir.path().join("a.exe");
        let ours = APP_MANIFEST.as_bytes();
        std::fs::write(&exe, pe_with(&[(24, 1, 0x409, ours)])).unwrap();
        assert!(verify(&exe, Some(ours)).unwrap().contains("longPathAware"));
        assert!(verify(&exe, Some(b"other")).is_err());

        std::fs::write(&exe, pe_with(&[(24, 1, 0x409, ours), (24, 1, 0, ours)])).unwrap();
        assert!(verify(&exe, None).is_err());

        std::fs::write(&exe, pe_with(&[(24, 1, 0x409, b"<assembly/>")])).unwrap();
        let err = verify(&exe, None).unwrap_err().to_string();
        assert!(err.contains("longPathAware"), "{err}");

        std::fs::write(&exe, pe_with(&[(3, 1, 0x409, b"icon")])).unwrap();
        assert!(verify(&exe, None).is_err());
    }

    /// Writes the manifest into a copy of a real executable from this machine, then reads it
    /// back. The test image is a copy of the test binary itself (a real MSVC-linked PE).
    #[cfg(windows)]
    #[test]
    fn apply_rewrites_a_real_executable() {
        let dir = tempfile::tempdir().unwrap();
        let exe = dir.path().join("copy.exe");
        std::fs::copy(std::env::current_exe().unwrap(), &exe).unwrap();
        let manifest = dir.path().join("app.manifest");
        std::fs::write(&manifest, APP_MANIFEST).unwrap();
        apply(&exe, &manifest).unwrap();
        // A second run replaces, never duplicates.
        apply(&exe, &manifest).unwrap();
        let text = verify(&exe, Some(APP_MANIFEST.as_bytes())).unwrap();
        assert!(text.contains(">UTF-8</activeCodePage>"));
        assert!(text.contains(">true</longPathAware>"));
    }
}
