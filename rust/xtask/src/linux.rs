// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `cargo xtask dist linux`: `.deb`, `.rpm`, `.AppImage` and a portable `.tar.gz`.
//!
//! The deb (ar + tar.gz) and the rpm (the `rpm` crate) are written natively, so neither
//! dpkg-deb, rpmbuild nor cargo-deb / cargo-generate-rpm is needed. The AppImage needs
//! `appimagetool` (PATH or `APPIMAGETOOL`).
//!
//! Install layout (deb and rpm):
//! ```text
//! /opt/<pkg>/bin/<pkg>                                 the binary
//! /usr/bin/<pkg> -> /opt/<pkg>/bin/<pkg>
//! /usr/share/applications/<pkg>.desktop
//! /usr/share/icons/hicolor/<n>x<n>/apps/<pkg>.png      n = 16 32 48 64 128 256 512
//! /usr/share/doc/<pkg>/copyright (deb) | /usr/share/licenses/<pkg>/LICENSE (rpm)
//! ```
//! plus the `.mmcjson` MIME package and document icons when the identity registers the file
//! type (the side-by-side Rust identity does not).

use std::collections::BTreeSet;
use std::io::Cursor;
use std::path::{Path, PathBuf};
use std::process::Command;

use anyhow::{Context, Result, bail};
use flate2::Compression;
use flate2::write::GzEncoder;
use md5::Digest;

use crate::ctx::{self, Args, Ctx, xml_escape};

pub const DEFAULT_TARGET: &str = "x86_64-unknown-linux-gnu";
pub const ICON_SIZES: [u32; 7] = [16, 32, 48, 64, 128, 256, 512];
pub const ALL_FORMATS: [&str; 4] = ["deb", "rpm", "tar", "appimage"];

/// Debian runtime dependencies. GPUI needs X11/Wayland, xkbcommon, Vulkan, fontconfig and
/// freetype; the `--msa-signin` web view helper needs WebKitGTK 4.1 (and with it GTK 3).
pub const DEB_DEPENDS: &[&str] = &[
    "libc6 (>= 2.35)",
    "libgcc-s1",
    "libwebkit2gtk-4.1-0",
    "libgtk-3-0 | libgtk-3-0t64",
    "libxkbcommon0",
    "libxkbcommon-x11-0",
    "libwayland-client0",
    "libvulkan1",
    "libx11-6",
    "libx11-xcb1",
    "libxcb1",
    "libfontconfig1",
    "libfreetype6",
    "libasound2 | libasound2t64",
    // gilrs reads gamepads through libudev.
    "libudev1",
];

/// Fedora / RHEL runtime dependencies (same set).
pub const RPM_REQUIRES: &[&str] = &[
    "webkit2gtk4.1",
    "gtk3",
    "libxkbcommon",
    "libxkbcommon-x11",
    "libwayland-client",
    "vulkan-loader",
    "libX11",
    "libX11-xcb",
    "libxcb",
    "fontconfig",
    "freetype",
    "systemd-libs",
    "alsa-lib",
];

/// Package architecture names for a target triple: (deb, rpm / AppImage).
pub fn arch_names(target: &str) -> (&'static str, &'static str) {
    if target.starts_with("aarch64") {
        ("arm64", "aarch64")
    } else {
        ("amd64", "x86_64")
    }
}

/// One file in the package payload.
#[derive(Clone, Debug)]
pub enum Payload {
    File { data: Vec<u8>, mode: u32 },
    Symlink { target: String },
}

#[derive(Clone, Debug)]
pub struct Entry {
    /// Absolute install path (`/opt/...`).
    pub path: String,
    pub payload: Payload,
}

impl Entry {
    fn file(path: impl Into<String>, data: Vec<u8>, mode: u32) -> Entry {
        Entry {
            path: path.into(),
            payload: Payload::File { data, mode },
        }
    }
}

/// Escapes a Desktop Entry string value (backslash and control characters).
pub fn desktop_escape(s: &str) -> String {
    let mut out = String::new();
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\t' => out.push_str("\\t"),
            '\r' => out.push_str("\\r"),
            c if (c as u32) < 0x20 => {}
            c => out.push(c),
        }
    }
    out
}

/// The packaged `.desktop` file. `exec` is the command (`/opt/<pkg>/bin/<pkg>`).
pub fn render_desktop(
    name: &str,
    exec: &str,
    icon: &str,
    wm_class: &str,
    register_url_scheme: bool,
    register_modpack: bool,
) -> String {
    let mut mime = String::new();
    if register_url_scheme {
        mime.push_str("x-scheme-handler/mmcl;");
    }
    if register_modpack {
        mime.push_str("application/x-mmcjson;");
    }
    let mut s = format!(
        "[Desktop Entry]\nType=Application\nName={}\nComment=Minecraft Forge modpack launcher\nExec={} %u\nIcon={}\nTerminal=false\nCategories=Game;\nKeywords=minecraft;modpack;forge;launcher;mica;\nStartupWMClass={}\n",
        desktop_escape(name),
        exec,
        desktop_escape(icon),
        desktop_escape(wm_class)
    );
    if !mime.is_empty() {
        s.push_str(&format!("MimeType={mime}\n"));
    }
    s
}

/// The shared-mime-info package for `.mmcjson`.
pub fn render_mime_xml(description: &str) -> String {
    format!(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mime-info xmlns=\"http://www.freedesktop.org/standards/shared-mime-info\">\n  <mime-type type=\"application/x-mmcjson\">\n    <comment>{}</comment>\n    <glob pattern=\"*.mmcjson\"/>\n  </mime-type>\n</mime-info>\n",
        xml_escape(description)
    )
}

/// Scales a square PNG to `size` px (Lanczos3) and encodes it as PNG.
pub fn scaled_png(source: &image::DynamicImage, size: u32) -> Result<Vec<u8>> {
    let scaled = source.resize_exact(size, size, image::imageops::FilterType::Lanczos3);
    let mut out = Cursor::new(Vec::new());
    scaled.write_to(&mut out, image::ImageFormat::Png)?;
    Ok(out.into_inner())
}

/// What goes into the deb / rpm, minus the license file (whose path differs).
pub struct Layout {
    pub pkg: String,
    pub binary: Vec<u8>,
    pub desktop: String,
    pub icons: Vec<(u32, Vec<u8>)>,
    pub mime_icons: Vec<(u32, Vec<u8>)>,
    pub mime_xml: Option<String>,
    pub license: Vec<u8>,
}

impl Layout {
    pub fn entries(&self, license_path: &str) -> Vec<Entry> {
        let pkg = &self.pkg;
        let bin = format!("/opt/{pkg}/bin/{pkg}");
        let mut e = vec![
            Entry::file(bin.clone(), self.binary.clone(), 0o755),
            Entry {
                path: format!("/usr/bin/{pkg}"),
                payload: Payload::Symlink { target: bin },
            },
            Entry::file(
                format!("/usr/share/applications/{pkg}.desktop"),
                self.desktop.clone().into_bytes(),
                0o644,
            ),
        ];
        for (n, png) in &self.icons {
            e.push(Entry::file(
                format!("/usr/share/icons/hicolor/{n}x{n}/apps/{pkg}.png"),
                png.clone(),
                0o644,
            ));
        }
        if let Some(xml) = &self.mime_xml {
            e.push(Entry::file(
                format!("/usr/share/mime/packages/{pkg}.xml"),
                xml.clone().into_bytes(),
                0o644,
            ));
            for (n, png) in &self.mime_icons {
                e.push(Entry::file(
                    format!("/usr/share/icons/hicolor/{n}x{n}/mimetypes/application-x-mmcjson.png"),
                    png.clone(),
                    0o644,
                ));
            }
        }
        e.push(Entry::file(license_path, self.license.clone(), 0o644));
        e
    }
}

/// Every ancestor directory of the entries (`/opt`, `/opt/<pkg>`, ...), sorted, without `/`.
pub fn parent_dirs(entries: &[Entry]) -> Vec<String> {
    let mut dirs = BTreeSet::new();
    for e in entries {
        let mut p = Path::new(&e.path).parent();
        while let Some(dir) = p {
            let s = dir.to_string_lossy().replace('\\', "/");
            if s == "/" || s.is_empty() {
                break;
            }
            dirs.insert(s);
            p = dir.parent();
        }
    }
    dirs.into_iter().collect()
}

/// The deb's `data.tar.gz` (paths `./...`, root-owned).
pub fn deb_data_tar(entries: &[Entry], mtime: u64) -> Result<Vec<u8>> {
    let mut builder = tar::Builder::new(GzEncoder::new(Vec::new(), Compression::best()));
    let mut root = tar::Header::new_gnu();
    root.set_entry_type(tar::EntryType::Directory);
    root.set_mode(0o755);
    root.set_mtime(mtime);
    root.set_size(0);
    builder.append_data(&mut root, "./", std::io::empty())?;
    for dir in parent_dirs(entries) {
        let mut h = tar::Header::new_gnu();
        h.set_entry_type(tar::EntryType::Directory);
        h.set_mode(0o755);
        h.set_mtime(mtime);
        h.set_size(0);
        builder.append_data(&mut h, format!(".{dir}/"), std::io::empty())?;
    }
    for e in entries {
        let name = format!(".{}", e.path);
        let mut h = tar::Header::new_gnu();
        h.set_mtime(mtime);
        h.set_uid(0);
        h.set_gid(0);
        match &e.payload {
            Payload::File { data, mode } => {
                h.set_entry_type(tar::EntryType::Regular);
                h.set_mode(*mode);
                h.set_size(data.len() as u64);
                builder.append_data(&mut h, name, data.as_slice())?;
            }
            Payload::Symlink { target } => {
                h.set_entry_type(tar::EntryType::Symlink);
                h.set_mode(0o777);
                h.set_size(0);
                builder.append_link(&mut h, name, target)?;
            }
        }
    }
    Ok(builder.into_inner()?.finish()?)
}

/// Inputs of the deb `control` file.
pub struct DebControl<'a> {
    pub package: &'a str,
    pub version: &'a str,
    pub arch: &'a str,
    pub installed_kib: u64,
    pub depends: &'a [&'a str],
    pub description: &'a str,
    pub long_description: &'a str,
}

pub fn render_deb_control(c: &DebControl) -> String {
    let long: Vec<String> = c
        .long_description
        .lines()
        .map(|l| {
            if l.trim().is_empty() {
                " .".to_string()
            } else {
                format!(" {l}")
            }
        })
        .collect();
    format!(
        "Package: {}\nVersion: {}\nArchitecture: {}\nMaintainer: {} <{}>\nInstalled-Size: {}\nDepends: {}\nSection: games\nPriority: optional\nHomepage: {}\nDescription: {}\n{}\n",
        c.package,
        c.version,
        c.arch,
        ctx::MANUFACTURER,
        ctx::HELP_URL,
        c.installed_kib.max(1),
        c.depends.join(", "),
        ctx::ABOUT_URL,
        c.description,
        long.join("\n")
    )
}

/// The deb's `control.tar.gz`: control, md5sums, postinst, postrm.
pub fn deb_control_tar(
    control: &str,
    entries: &[Entry],
    scripts: &[(&str, Vec<u8>)],
    mtime: u64,
) -> Result<Vec<u8>> {
    let mut md5sums = String::new();
    for e in entries {
        if let Payload::File { data, .. } = &e.payload {
            let digest = md5::Md5::digest(data);
            md5sums.push_str(&format!("{}  {}\n", hex::encode(digest), &e.path[1..]));
        }
    }
    let mut builder = tar::Builder::new(GzEncoder::new(Vec::new(), Compression::best()));
    let mut add = |name: &str, data: &[u8], mode: u32| -> Result<()> {
        let mut h = tar::Header::new_gnu();
        h.set_entry_type(tar::EntryType::Regular);
        h.set_mode(mode);
        h.set_mtime(mtime);
        h.set_size(data.len() as u64);
        builder.append_data(&mut h, name, data)?;
        Ok(())
    };
    add("./control", control.as_bytes(), 0o644)?;
    add("./md5sums", md5sums.as_bytes(), 0o644)?;
    for (name, data) in scripts {
        add(&format!("./{name}"), data, 0o755)?;
    }
    Ok(builder.into_inner()?.finish()?)
}

/// An `ar` archive in the layout dpkg expects (`debian-binary`, `control.tar.gz`,
/// `data.tar.gz`).
pub fn write_ar(members: &[(&str, &[u8])], mtime: u64) -> Vec<u8> {
    let mut out = b"!<arch>\n".to_vec();
    for (name, data) in members {
        let header = format!(
            "{:<16}{:<12}{:<6}{:<6}{:<8}{:<10}`\n",
            name,
            mtime,
            0,
            0,
            "100644",
            data.len()
        );
        debug_assert_eq!(header.len(), 60);
        out.extend_from_slice(header.as_bytes());
        out.extend_from_slice(data);
        if data.len() % 2 == 1 {
            out.push(b'\n');
        }
    }
    out
}

/// Reads back the members of an `ar` archive (test helper).
#[cfg(test)]
pub fn read_ar(bytes: &[u8]) -> Result<Vec<(String, Vec<u8>)>> {
    if !bytes.starts_with(b"!<arch>\n") {
        bail!("not an ar archive");
    }
    let mut pos = 8;
    let mut members = Vec::new();
    while pos + 60 <= bytes.len() {
        let header = std::str::from_utf8(&bytes[pos..pos + 60])?;
        let name = header[0..16].trim().trim_end_matches('/').to_string();
        let size: usize = header[48..58].trim().parse()?;
        let start = pos + 60;
        let end = start + size;
        if end > bytes.len() {
            bail!("truncated ar member {name}");
        }
        members.push((name, bytes[start..end].to_vec()));
        pos = end + (size % 2);
    }
    Ok(members)
}

pub fn build_deb(
    layout: &Layout,
    version: &str,
    arch: &str,
    scripts_dir: &Path,
) -> Result<Vec<u8>> {
    let pkg = &layout.pkg;
    let entries = layout.entries(&format!("/usr/share/doc/{pkg}/copyright"));
    let installed: u64 = entries
        .iter()
        .map(|e| match &e.payload {
            Payload::File { data, .. } => data.len() as u64,
            Payload::Symlink { .. } => 0,
        })
        .sum::<u64>()
        .div_ceil(1024);
    let control = render_deb_control(&DebControl {
        package: pkg,
        version,
        arch,
        installed_kib: installed,
        depends: DEB_DEPENDS,
        description: "Minecraft Forge modpack launcher",
        long_description: "Mica Minecraft Launcher installs, updates and launches Minecraft\nForge, NeoForge and Fabric modpacks, with Microsoft account sign-in.",
    });
    let scripts = read_scripts(scripts_dir)?;
    let script_refs: Vec<(&str, Vec<u8>)> = scripts
        .iter()
        .map(|(n, d)| (n.as_str(), d.clone()))
        .collect();
    let mtime = ctx::source_date_epoch();
    let control_tar = deb_control_tar(&control, &entries, &script_refs, mtime)?;
    let data_tar = deb_data_tar(&entries, mtime)?;
    Ok(write_ar(
        &[
            ("debian-binary", b"2.0\n"),
            ("control.tar.gz", &control_tar),
            ("data.tar.gz", &data_tar),
        ],
        mtime,
    ))
}

fn read_scripts(dir: &Path) -> Result<Vec<(String, Vec<u8>)>> {
    let mut out = Vec::new();
    for name in ["postinst", "postrm"] {
        let path = dir.join(name);
        let text = std::fs::read_to_string(&path)
            .with_context(|| format!("cannot read {}", path.display()))?;
        out.push((name.to_string(), text.replace("\r\n", "\n").into_bytes()));
    }
    Ok(out)
}

pub fn build_rpm(
    layout: &Layout,
    version: &str,
    arch: &str,
    scripts_dir: &Path,
) -> Result<rpm::Package> {
    let pkg = &layout.pkg;
    let entries = layout.entries(&format!("/usr/share/licenses/{pkg}/LICENSE"));
    let scripts = read_scripts(scripts_dir)?;
    let script = |name: &str| -> String {
        scripts
            .iter()
            .find(|(n, _)| n == name)
            .map(|(_, d)| String::from_utf8_lossy(d).into_owned())
            .unwrap_or_default()
    };
    let config = rpm::BuildConfig::default()
        .compression(rpm::CompressionType::Gzip)
        .source_date(ctx::source_date_epoch() as u32);
    let mut b = rpm::PackageBuilder::new(
        pkg,
        version,
        "GPL-3.0-only",
        arch,
        "Minecraft Forge modpack launcher",
    );
    b.using_config(config);
    b.release("1");
    b.url(ctx::ABOUT_URL);
    b.vendor(ctx::MANUFACTURER);
    b.description(
        "Mica Minecraft Launcher installs, updates and launches Minecraft Forge, NeoForge and \
         Fabric modpacks, with Microsoft account sign-in.",
    );
    b.group("Amusements/Games");
    for dir in [format!("/opt/{pkg}"), format!("/opt/{pkg}/bin")] {
        b.with_dir_entry(rpm::FileOptions::dir(dir).permissions(0o755))
            .map_err(|e| anyhow::anyhow!("rpm: {e}"))?;
    }
    for e in &entries {
        match &e.payload {
            Payload::File { data, mode } => {
                let mut opts = rpm::FileOptions::new(e.path.clone()).permissions(*mode as u16);
                if e.path.starts_with("/usr/share/licenses/") {
                    opts = opts.license();
                }
                b.with_file_contents(data.clone(), opts)
                    .map_err(|err| anyhow::anyhow!("rpm: {}: {err}", e.path))?;
            }
            Payload::Symlink { target } => {
                b.with_symlink(rpm::FileOptions::symlink(e.path.clone(), target.clone()))
                    .map_err(|err| anyhow::anyhow!("rpm: {}: {err}", e.path))?;
            }
        }
    }
    for req in RPM_REQUIRES {
        b.requires(rpm::Dependency::any(*req));
    }
    b.post_install_script(script("postinst"));
    b.post_uninstall_script(script("postrm"));
    b.build().map_err(|e| anyhow::anyhow!("rpm: {e}"))
}

/// `<pkg>-<numeric>/` with the binary, license, desktop file and a 256 px icon.
pub fn build_tarball(layout: &Layout, top: &str, desktop: &str, icon256: &[u8]) -> Result<Vec<u8>> {
    let mtime = ctx::source_date_epoch();
    let mut builder = tar::Builder::new(GzEncoder::new(Vec::new(), Compression::best()));
    let mut add = |name: &str, data: &[u8], mode: u32| -> Result<()> {
        let mut h = tar::Header::new_gnu();
        h.set_entry_type(tar::EntryType::Regular);
        h.set_mode(mode);
        h.set_mtime(mtime);
        h.set_size(data.len() as u64);
        builder.append_data(&mut h, format!("{top}/{name}"), data)?;
        Ok(())
    };
    let pkg = &layout.pkg;
    add(pkg, &layout.binary, 0o755)?;
    add("LICENSE", &layout.license, 0o644)?;
    add(&format!("{pkg}.desktop"), desktop.as_bytes(), 0o644)?;
    add(&format!("{pkg}.png"), icon256, 0o644)?;
    Ok(builder.into_inner()?.finish()?)
}

/// Parses `--formats deb,rpm,...` (default: all four).
pub fn parse_formats(arg: Option<&str>) -> Result<(Vec<String>, bool)> {
    let Some(arg) = arg else {
        return Ok((ALL_FORMATS.iter().map(|s| s.to_string()).collect(), false));
    };
    let mut out = Vec::new();
    for f in arg.split(',').map(str::trim).filter(|f| !f.is_empty()) {
        let f = f.to_ascii_lowercase();
        if !ALL_FORMATS.contains(&f.as_str()) {
            bail!("unknown Linux format '{f}' (expected deb, rpm, tar, appimage)");
        }
        out.push(f);
    }
    Ok((out, true))
}

pub fn dist(ctx: &Ctx, args: &Args) -> Result<()> {
    let (formats, explicit) = parse_formats(args.get("formats"))?;
    let id = &ctx.identity;
    let pkg = id.linux_package_name.to_string();
    let numeric = &ctx.versions.numeric;
    let (deb_arch, rpm_arch) = arch_names(&ctx.target);
    let built = ctx.app_binary()?;
    let stage = ctx.fresh_stage("linux")?;

    let source_icon = image::open(ctx.deploy("linux/Mica Minecraft Launcher.png"))
        .context("cannot read the launcher icon")?;
    let mut icons = Vec::new();
    for n in ICON_SIZES {
        icons.push((n, scaled_png(&source_icon, n)?));
    }
    let register_modpack = id.registers_modpack_file_type;
    let mut mime_icons = Vec::new();
    if register_modpack {
        let doc_icon = image::open(ctx.deploy("linux/Mica Minecraft Modpack.png"))
            .context("cannot read the modpack icon")?;
        for n in ICON_SIZES {
            mime_icons.push((n, scaled_png(&doc_icon, n)?));
        }
    }
    let layout = Layout {
        pkg: pkg.clone(),
        binary: std::fs::read(&built)?,
        desktop: render_desktop(
            id.display_name,
            &format!("/opt/{pkg}/bin/{pkg}"),
            &pkg,
            &pkg,
            id.registers_url_scheme,
            register_modpack,
        ),
        icons,
        mime_icons,
        mime_xml: register_modpack.then(|| render_mime_xml("Mica Minecraft Modpack")),
        license: std::fs::read(ctx.license())?,
    };
    let scripts = ctx.packaging("linux");
    let out_dir = ctx.packaging_out();
    std::fs::create_dir_all(&out_dir)?;
    std::fs::create_dir_all(&ctx.out)?;
    let icon256 = layout
        .icons
        .iter()
        .find(|(n, _)| *n == 256)
        .map(|(_, d)| d.clone())
        .unwrap_or_default();

    for format in &formats {
        let file: Option<PathBuf> = match format.as_str() {
            "deb" => {
                let path = out_dir.join(format!("{pkg}_{numeric}-1_{deb_arch}.deb"));
                std::fs::write(
                    &path,
                    build_deb(&layout, &format!("{numeric}-1"), deb_arch, &scripts)?,
                )?;
                Some(path)
            }
            "rpm" => {
                let path = out_dir.join(format!("{pkg}-{numeric}-1.{rpm_arch}.rpm"));
                build_rpm(&layout, numeric, rpm_arch, &scripts)?
                    .write_file(&path)
                    .map_err(|e| anyhow::anyhow!("rpm: {e}"))?;
                Some(path)
            }
            "tar" => {
                let top = format!("{pkg}-{numeric}");
                let path = out_dir.join(format!("{pkg}-{numeric}-linux-{rpm_arch}.tar.gz"));
                let desktop = render_desktop(id.display_name, &pkg, &pkg, &pkg, false, false);
                std::fs::write(&path, build_tarball(&layout, &top, &desktop, &icon256)?)?;
                Some(path)
            }
            "appimage" => build_appimage(ctx, &layout, &stage, &icon256, rpm_arch, explicit)?,
            _ => None,
        };
        if let Some(file) = file {
            let name = file.file_name().context("output without a name")?;
            ctx::copy_file(&file, &ctx.out.join(name))?;
            eprintln!("xtask: wrote {}", file.display());
        }
    }
    eprintln!("xtask: Linux packages are in {}", ctx.out.display());
    Ok(())
}

fn build_appimage(
    ctx: &Ctx,
    layout: &Layout,
    stage: &Path,
    icon256: &[u8],
    arch: &str,
    explicit: bool,
) -> Result<Option<PathBuf>> {
    let Some(tool) = ctx::find_tool(
        "APPIMAGETOOL",
        &["appimagetool", "appimagetool-x86_64.AppImage"],
    ) else {
        if explicit {
            bail!("appimagetool was not found (PATH or APPIMAGETOOL)");
        }
        eprintln!("xtask: warning: appimagetool not found; AppImage skipped");
        return Ok(None);
    };
    let pkg = &layout.pkg;
    let appdir = stage.join(format!("{pkg}.AppDir"));
    let bin = appdir.join("usr/bin").join(pkg);
    std::fs::create_dir_all(bin.parent().unwrap())?;
    std::fs::write(&bin, &layout.binary)?;
    let apprun = appdir.join("AppRun");
    std::fs::write(
        &apprun,
        format!(
            "#!/bin/sh\nHERE=\"$(dirname \"$(readlink -f \"$0\")\")\"\nexec \"$HERE/usr/bin/{pkg}\" \"$@\"\n"
        ),
    )?;
    for (n, png) in &layout.icons {
        let p = appdir.join(format!("usr/share/icons/hicolor/{n}x{n}/apps/{pkg}.png"));
        std::fs::create_dir_all(p.parent().unwrap())?;
        std::fs::write(p, png)?;
    }
    std::fs::write(appdir.join(format!("{pkg}.png")), icon256)?;
    std::fs::write(appdir.join(".DirIcon"), icon256)?;
    let desktop = render_desktop(ctx.identity.display_name, pkg, pkg, pkg, false, false);
    std::fs::write(appdir.join(format!("{pkg}.desktop")), desktop)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        for p in [&bin, &apprun] {
            std::fs::set_permissions(p, std::fs::Permissions::from_mode(0o755))?;
        }
    }
    let out = ctx
        .packaging_out()
        .join(format!("{pkg}-{}-{arch}.AppImage", ctx.versions.numeric));
    ctx::run(
        Command::new(tool)
            .env("ARCH", arch)
            .env("APPIMAGE_EXTRACT_AND_RUN", "1")
            .arg(&appdir)
            .arg(&out),
    )?;
    Ok(Some(out))
}

/// Entry names of a `.tar.gz` (test helper).
#[cfg(test)]
fn gunzip_tar_names(bytes: &[u8]) -> Vec<String> {
    let mut a = tar::Archive::new(flate2::read::GzDecoder::new(bytes));
    a.entries()
        .unwrap()
        .map(|e| e.unwrap().path().unwrap().to_string_lossy().into_owned())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn layout(register: bool) -> Layout {
        Layout {
            pkg: "mica-minecraft-launcher-rs".into(),
            binary: b"\x7fELF".to_vec(),
            desktop: render_desktop(
                "Mica Minecraft Launcher (Rust)",
                "/opt/mica-minecraft-launcher-rs/bin/mica-minecraft-launcher-rs",
                "mica-minecraft-launcher-rs",
                "mica-minecraft-launcher-rs",
                register,
                register,
            ),
            icons: vec![(16, b"png16".to_vec()), (256, b"png256".to_vec())],
            mime_icons: vec![(16, b"doc".to_vec())],
            mime_xml: register.then(|| render_mime_xml("Mica & Modpack")),
            license: b"GPL".to_vec(),
        }
    }

    fn scripts_dir() -> PathBuf {
        ctx::workspace_dir().join("packaging/linux")
    }

    #[test]
    fn desktop_entry_without_registration() {
        let d = render_desktop("A\nB\\", "/opt/x/bin/x", "x", "x", false, false);
        assert!(d.starts_with("[Desktop Entry]\n"));
        assert!(d.contains("Name=A\\nB\\\\\n"));
        assert!(d.contains("Exec=/opt/x/bin/x %u\n"));
        assert!(d.contains("StartupWMClass=x\n"));
        assert!(!d.contains("MimeType"));
        let d = render_desktop("A", "/x", "x", "x", true, true);
        assert!(d.contains("MimeType=x-scheme-handler/mmcl;application/x-mmcjson;\n"));
    }

    #[test]
    fn mime_xml_escapes() {
        let x = render_mime_xml("A & <B>");
        assert!(x.contains("<comment>A &amp; &lt;B&gt;</comment>"));
        let mut reader = quick_xml::Reader::from_str(&x);
        while reader.read_event().unwrap() != quick_xml::events::Event::Eof {}
    }

    #[test]
    fn ar_round_trip() {
        let ar = write_ar(&[("debian-binary", b"2.0\n"), ("odd", b"abc")], 0);
        let members = read_ar(&ar).unwrap();
        assert_eq!(members.len(), 2);
        assert_eq!(members[0], ("debian-binary".into(), b"2.0\n".to_vec()));
        assert_eq!(members[1], ("odd".into(), b"abc".to_vec()));
    }

    #[test]
    fn deb_has_layout_and_control() {
        let deb = build_deb(&layout(false), "2026.05.12-1", "amd64", &scripts_dir()).unwrap();
        let members = read_ar(&deb).unwrap();
        let names: Vec<_> = members.iter().map(|m| m.0.as_str()).collect();
        assert_eq!(
            names,
            vec!["debian-binary", "control.tar.gz", "data.tar.gz"]
        );
        let data = gunzip_tar_names(&members[2].1);
        for want in [
            "opt/mica-minecraft-launcher-rs/bin/mica-minecraft-launcher-rs",
            "usr/bin/mica-minecraft-launcher-rs",
            "usr/share/applications/mica-minecraft-launcher-rs.desktop",
            "usr/share/icons/hicolor/256x256/apps/mica-minecraft-launcher-rs.png",
            "usr/share/doc/mica-minecraft-launcher-rs/copyright",
        ] {
            assert!(data.iter().any(|n| n == want), "missing {want} in {data:?}");
        }
        assert!(!data.iter().any(|n| n.contains("mime")));
        let control = gunzip_tar_names(&members[1].1);
        assert_eq!(control, vec!["control", "md5sums", "postinst", "postrm"]);
        let mut a = tar::Archive::new(flate2::read::GzDecoder::new(members[1].1.as_slice()));
        let mut first = a.entries().unwrap().next().unwrap().unwrap();
        let mut text = String::new();
        std::io::Read::read_to_string(&mut first, &mut text).unwrap();
        assert!(text.contains("Package: mica-minecraft-launcher-rs\n"));
        assert!(text.contains("Version: 2026.05.12-1\n"));
        assert!(text.contains("libwebkit2gtk-4.1-0"));
        assert!(text.ends_with('\n'));
    }

    #[test]
    fn deb_includes_mime_when_registering() {
        let deb = build_deb(&layout(true), "1-1", "amd64", &scripts_dir()).unwrap();
        let members = read_ar(&deb).unwrap();
        let data = gunzip_tar_names(&members[2].1);
        assert!(
            data.iter()
                .any(|n| n == "usr/share/mime/packages/mica-minecraft-launcher-rs.xml")
        );
        assert!(
            data.iter()
                .any(|n| n == "usr/share/icons/hicolor/16x16/mimetypes/application-x-mmcjson.png")
        );
    }

    #[test]
    fn rpm_round_trip() {
        let pkg = build_rpm(&layout(false), "2026.05.12", "x86_64", &scripts_dir()).unwrap();
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("x.rpm");
        pkg.write_file(&path).unwrap();
        let back = rpm::Package::open(&path).unwrap();
        assert_eq!(
            back.metadata.get_name().unwrap(),
            "mica-minecraft-launcher-rs"
        );
        assert_eq!(back.metadata.get_version().unwrap(), "2026.05.12");
        assert_eq!(back.metadata.get_release().unwrap(), "1");
        assert_eq!(back.metadata.get_arch().unwrap(), "x86_64");
        let requires: Vec<String> = back
            .metadata
            .get_requires()
            .unwrap()
            .iter()
            .map(|d| d.name.clone())
            .collect();
        assert!(requires.iter().any(|r| r == "webkit2gtk4.1"));
        let paths: Vec<String> = back
            .metadata
            .get_file_paths()
            .unwrap()
            .iter()
            .map(|p| p.to_string_lossy().replace('\\', "/"))
            .collect();
        assert!(
            paths
                .iter()
                .any(|p| p == "/usr/bin/mica-minecraft-launcher-rs")
        );
        assert!(
            paths
                .iter()
                .any(|p| p == "/usr/share/licenses/mica-minecraft-launcher-rs/LICENSE")
        );
    }

    #[test]
    fn tarball_layout() {
        let l = layout(false);
        let t = build_tarball(&l, "pkg-1.0", "[Desktop Entry]\n", b"png").unwrap();
        let names = gunzip_tar_names(&t);
        assert_eq!(
            names,
            vec![
                "pkg-1.0/mica-minecraft-launcher-rs",
                "pkg-1.0/LICENSE",
                "pkg-1.0/mica-minecraft-launcher-rs.desktop",
                "pkg-1.0/mica-minecraft-launcher-rs.png"
            ]
        );
    }

    #[test]
    fn formats_and_arches() {
        assert_eq!(parse_formats(None).unwrap().0.len(), 4);
        assert_eq!(
            parse_formats(Some("deb, rpm")).unwrap().0,
            vec!["deb", "rpm"]
        );
        assert!(parse_formats(Some("snap")).is_err());
        assert_eq!(arch_names("x86_64-unknown-linux-gnu"), ("amd64", "x86_64"));
        assert_eq!(
            arch_names("aarch64-unknown-linux-gnu"),
            ("arm64", "aarch64")
        );
    }

    #[test]
    fn icon_scaling() {
        let img = image::DynamicImage::new_rgba8(64, 64);
        let png = scaled_png(&img, 16).unwrap();
        let back = image::load_from_memory(&png).unwrap();
        assert_eq!((back.width(), back.height()), (16, 16));
    }
}
