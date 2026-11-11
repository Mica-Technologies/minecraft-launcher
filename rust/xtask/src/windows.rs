// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `cargo xtask dist windows`: portable zip, per-user and per-machine MSIs, and the Burn
//! `Setup.exe`, all with WiX Toolset v5 (`wix build`).
//!
//! Outputs: `target/dist/packaging/{user,machine}/<AppName>-<winver>.msi`,
//! `target/dist/packaging/<AppName> Setup.exe`, and in the release folder
//! `<AppName>-<winver>-user.msi`, `-system.msi`, `<AppName> Setup.exe`,
//! `<AppName>-<winver>-portable-x64.zip`.

use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Command;

use anyhow::{Context, Result, bail};

use crate::ctx::{self, Args, Ctx};
use crate::version::validate_msi_version;
use crate::win_manifest;

pub const DEFAULT_TARGET: &str = "x86_64-pc-windows-msvc";

/// The two MSI install scopes.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum Scope {
    PerUser,
    PerMachine,
}

impl Scope {
    pub fn wix(self) -> &'static str {
        match self {
            Scope::PerUser => "perUser",
            Scope::PerMachine => "perMachine",
        }
    }
    /// `target/dist/packaging/<dir>/`.
    pub fn dir(self) -> &'static str {
        match self {
            Scope::PerUser => "user",
            Scope::PerMachine => "machine",
        }
    }
    /// Release-name suffix (parity with the Java release page).
    pub fn release_suffix(self) -> &'static str {
        match self {
            Scope::PerUser => "user",
            Scope::PerMachine => "system",
        }
    }
}

/// Installed executable name: the display name plus `.exe` (never the Java launcher's name,
/// so the MSI running gate and shortcuts never confuse the two products).
pub fn exe_name(ctx: &Ctx) -> String {
    format!("{}.exe", ctx.identity.display_name)
}

/// Staged inputs shared by both MSIs.
pub struct Staged {
    pub exe: PathBuf,
    pub license_txt: PathBuf,
    pub license_rtf: PathBuf,
}

pub fn dist(ctx: &Ctx, args: &Args) -> Result<()> {
    validate_msi_version(&ctx.versions.win_version)
        .map_err(|e| anyhow::anyhow!("{e} (pass --win-version to override)"))?;
    let built = ctx.app_binary()?;
    let stage = ctx.fresh_stage("windows")?;
    let exe = stage.join(exe_name(ctx));
    ctx::copy_file(&built, &exe)?;
    // Replace GPUI's manifest with ours before signing (signing covers the resources).
    win_manifest::apply(&exe, &ctx.workspace.join(win_manifest::SOURCE))?;
    let signer = Signer::from_env();
    if let Some(s) = &signer {
        s.sign(&exe)?;
    }
    let license_txt = stage.join("LICENSE.txt");
    let license = std::fs::read_to_string(ctx.license()).context("cannot read LICENSE")?;
    std::fs::write(
        &license_txt,
        license.replace("\r\n", "\n").replace('\n', "\r\n"),
    )?;
    let license_rtf = stage.join("LICENSE.rtf");
    std::fs::write(&license_rtf, text_to_rtf(&license))?;
    let staged = Staged {
        exe,
        license_txt,
        license_rtf,
    };

    std::fs::create_dir_all(&ctx.out)?;
    let app = ctx.identity.display_name;
    let winver = &ctx.versions.win_version;
    let zip_path = ctx.out.join(format!("{app}-{winver}-portable-x64.zip"));
    write_portable_zip(&zip_path, app, &[&staged.exe, &staged.license_txt])?;
    eprintln!("xtask: wrote {}", zip_path.display());

    if args.flag("skip-installers") {
        eprintln!("xtask: --skip-installers: MSI and Setup.exe not built");
        return Ok(());
    }
    let wix = find_wix()?;

    let mut msis = Vec::new();
    for scope in [Scope::PerUser, Scope::PerMachine] {
        let out = ctx
            .packaging_out()
            .join(scope.dir())
            .join(format!("{app}-{winver}.msi"));
        build_msi(ctx, &wix, &staged, scope, &out)?;
        if let Some(s) = &signer {
            s.sign(&out)?;
        }
        let release = ctx
            .out
            .join(format!("{app}-{winver}-{}.msi", scope.release_suffix()));
        ctx::copy_file(&out, &release)?;
        msis.push(out);
    }

    let bundle = ctx.packaging_out().join(format!("{app} Setup.exe"));
    build_bundle(ctx, &wix, &msis[0], &msis[1], &bundle)?;
    if let Some(s) = &signer {
        s.sign_bundle(&wix, &bundle, &ctx.dist.join("stage"))?;
    }
    ctx::copy_file(&bundle, &ctx.out.join(format!("{app} Setup.exe")))?;
    eprintln!("xtask: Windows installers are in {}", ctx.out.display());
    Ok(())
}

/// Locates `wix` (WiX Toolset v5 .NET tool), warning when the major version is not 5.
pub fn find_wix() -> Result<PathBuf> {
    let mut candidates = Vec::new();
    if let Some(p) = ctx::find_tool("WIX", &["wix"]) {
        candidates.push(p);
    }
    if let Some(home) = std::env::var_os("USERPROFILE").or_else(|| std::env::var_os("HOME")) {
        candidates.push(
            PathBuf::from(home)
                .join(".dotnet")
                .join("tools")
                .join("wix.exe"),
        );
    }
    let Some(wix) = candidates.into_iter().find(|p| p.is_file()) else {
        bail!(
            "WiX Toolset v5 was not found (looked for `wix` on PATH and in ~/.dotnet/tools; set \
             WIX to override). Install it with:\n  dotnet tool install --global wix --version 5.0.2\n  \
             wix extension add -g WixToolset.UI.wixext/5.0.2 WixToolset.Util.wixext/5.0.2 \
             WixToolset.Bal.wixext/5.0.2\nor pass --skip-installers to build only the portable zip."
        );
    };
    if let Ok(v) = ctx::run_capture(Command::new(&wix).arg("--version"))
        && !v.trim().starts_with('5')
    {
        eprintln!(
            "xtask: warning: wix reports version {}; these sources target WiX v5",
            v.trim()
        );
    }
    Ok(wix)
}

/// The `-d` variables of `product.wxs` for one scope.
pub fn msi_defines(ctx: &Ctx, staged: &Staged, scope: Scope) -> Vec<(String, String)> {
    let id = &ctx.identity;
    let p = |path: &Path| path.display().to_string();
    let mut d: Vec<(String, String)> = vec![
        ("Scope".into(), scope.wix().into()),
        ("AppName".into(), id.display_name.into()),
        ("ExeName".into(), exe_name(ctx)),
        ("Version".into(), ctx.versions.win_version.clone()),
        ("UpgradeCode".into(), id.win_upgrade_code.into()),
        ("Manufacturer".into(), ctx::MANUFACTURER.into()),
        (
            "Description".into(),
            mmcl_core::identity::DESCRIPTION.into(),
        ),
        ("Aumid".into(), id.win_aumid.into()),
        ("MenuGroup".into(), id.display_name.into()),
        ("ExePath".into(), p(&staged.exe)),
        ("LicenseTxt".into(), p(&staged.license_txt)),
        ("LicenseRtf".into(), p(&staged.license_rtf)),
        (
            "IconFile".into(),
            p(&ctx.deploy("windows/Mica Minecraft Launcher.ico")),
        ),
        (
            "BannerBmp".into(),
            p(&ctx.deploy("windows/installer-resources/installer-banner.bmp")),
        ),
        (
            "DialogBmp".into(),
            p(&ctx.deploy("windows/installer-resources/installer-background.bmp")),
        ),
        ("HelpUrl".into(), ctx::HELP_URL.into()),
        ("AboutUrl".into(), ctx::ABOUT_URL.into()),
        ("UpdateUrl".into(), ctx::RELEASES_URL.into()),
        (
            "RegKey".into(),
            format!("Software\\{}\\{}", ctx::MANUFACTURER, id.display_name),
        ),
        (
            "RegisterUrlScheme".into(),
            u8::from(id.registers_url_scheme).to_string(),
        ),
        (
            "RegisterModpackType".into(),
            u8::from(id.registers_modpack_file_type && id.win_modpack_progid.is_some()).to_string(),
        ),
    ];
    d.push((
        "ProgId".into(),
        id.win_modpack_progid
            .unwrap_or("MicaMinecraftLauncher.Modpack")
            .into(),
    ));
    d
}

/// The `-d` variables of `bundle.wxs`.
pub fn bundle_defines(ctx: &Ctx, user_msi: &Path, machine_msi: &Path) -> Vec<(String, String)> {
    let p = |path: &Path| path.display().to_string();
    vec![
        ("AppName".into(), ctx.identity.display_name.into()),
        ("Manufacturer".into(), ctx::MANUFACTURER.into()),
        ("BundleVersion".into(), ctx.versions.bundle_version.clone()),
        (
            "BundleUpgradeCode".into(),
            ctx.identity.win_bundle_upgrade_code.into(),
        ),
        ("MsiUserPath".into(), p(user_msi)),
        ("MsiMachinePath".into(), p(machine_msi)),
        (
            "IconFile".into(),
            p(&ctx.deploy("windows/Mica Minecraft Launcher.ico")),
        ),
        (
            "ThemeFile".into(),
            p(&ctx.packaging("windows/bundle/theme.xml")),
        ),
        (
            "ThemeLocFile".into(),
            p(&ctx.packaging("windows/bundle/theme.wxl")),
        ),
        ("AboutUrl".into(), ctx::ABOUT_URL.into()),
        ("HelpUrl".into(), ctx::HELP_URL.into()),
        ("UpdateUrl".into(), ctx::RELEASES_URL.into()),
        ("LicenseUrl".into(), ctx::LICENSE_URL.into()),
    ]
}

fn build_msi(ctx: &Ctx, wix: &Path, staged: &Staged, scope: Scope, out: &Path) -> Result<()> {
    let src = ctx.packaging("windows");
    let obj = ctx.dist.join("obj").join(scope.dir());
    std::fs::create_dir_all(&obj)?;
    let mut cmd = Command::new(wix);
    cmd.current_dir(&src).args([
        "build",
        "-nologo",
        "-arch",
        "x64",
        "-ext",
        "WixToolset.UI.wixext",
        "-ext",
        "WixToolset.Util.wixext",
        "-culture",
        "en-US",
        "-loc",
    ]);
    cmd.arg(src.join("strings_en-us.wxl"));
    for (k, v) in msi_defines(ctx, staged, scope) {
        cmd.arg("-d").arg(format!("{k}={v}"));
    }
    cmd.arg("-intermediatefolder").arg(&obj);
    cmd.arg(src.join("product.wxs"))
        .arg(src.join("ui.wxs"))
        .arg(src.join("ShortcutPromptDlg.wxs"));
    cmd.arg("-o").arg(out);
    ctx::run(&mut cmd)
}

fn build_bundle(ctx: &Ctx, wix: &Path, user: &Path, machine: &Path, out: &Path) -> Result<()> {
    let src = ctx.packaging("windows/bundle");
    let obj = ctx.dist.join("obj").join("bundle");
    std::fs::create_dir_all(&obj)?;
    let mut cmd = Command::new(wix);
    cmd.current_dir(&src)
        .args(["build", "-nologo", "-arch", "x64", "-ext"])
        .arg(bal_extension(home_dir().as_deref()))
        .args(["-ext", "WixToolset.Util.wixext"]);
    for (k, v) in bundle_defines(ctx, user, machine) {
        cmd.arg("-d").arg(format!("{k}={v}"));
    }
    cmd.arg("-intermediatefolder").arg(&obj);
    cmd.arg(src.join("bundle.wxs")).arg("-o").arg(out);
    ctx::run(&mut cmd)
}

/// The user's home folder (`USERPROFILE`), where `wix extension add -g` caches extensions.
fn home_dir() -> Option<PathBuf> {
    std::env::var_os("USERPROFILE").map(PathBuf::from)
}

/// The `-ext` value for the bootstrapper extension.
///
/// WiX 5 resolves `-ext WixToolset.Bal.wixext` to `WixToolset.Bal.wixext.dll` inside the cached
/// package, but the 5.x package ships that DLL as `WixToolset.BootstrapperApplications.wixext.dll`,
/// so the name alone fails with WIX0144 even though the extension is installed. Pass the DLL's
/// path when the global cache has it (newest version first), else the name.
fn bal_extension(home: Option<&Path>) -> std::ffi::OsString {
    const NAME: &str = "WixToolset.Bal.wixext";
    let Some(home) = home else {
        return NAME.into();
    };
    let root = home.join(".wix").join("extensions").join(NAME);
    let mut versions: Vec<PathBuf> = std::fs::read_dir(&root)
        .map(|d| d.filter_map(|e| e.ok().map(|e| e.path())).collect())
        .unwrap_or_default();
    versions.sort();
    versions
        .iter()
        .rev()
        .map(|v| {
            v.join("wixext5")
                .join("WixToolset.BootstrapperApplications.wixext.dll")
        })
        .find(|dll| dll.is_file())
        .map(|dll| dll.into_os_string())
        .unwrap_or_else(|| NAME.into())
}

/// `<app>/<file>` entries for each staged file.
pub fn write_portable_zip(path: &Path, folder: &str, files: &[&Path]) -> Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let file = std::fs::File::create(path)?;
    let mut zip = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated)
        .unix_permissions(0o755);
    for f in files {
        let name = f
            .file_name()
            .context("file without a name")?
            .to_string_lossy();
        zip.start_file(format!("{folder}/{name}"), options)?;
        zip.write_all(&std::fs::read(f)?)?;
    }
    zip.finish()?;
    Ok(())
}

/// Plain text to a minimal RTF document for the WixUI license page: `\`, `{`, `}` escaped,
/// non-ASCII as `\uN?`, one `\par` per line.
pub fn text_to_rtf(text: &str) -> String {
    let mut out = String::from(
        "{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0\\fswiss Segoe UI;}}\\viewkind4\\uc1\\pard\\f0\\fs16 ",
    );
    for line in text.replace("\r\n", "\n").split('\n') {
        for c in line.chars() {
            match c {
                '\\' => out.push_str("\\\\"),
                '{' => out.push_str("\\{"),
                '}' => out.push_str("\\}"),
                '\t' => out.push_str("\\tab "),
                c if c.is_ascii() => out.push(c),
                c => {
                    let mut buf = [0u16; 2];
                    for unit in c.encode_utf16(&mut buf) {
                        out.push_str(&format!("\\u{}?", *unit as i16));
                    }
                }
            }
        }
        out.push_str("\\par\n");
    }
    out.push('}');
    out
}

/// Optional Authenticode signing: `WINDOWS_SIGN_CERT` (a .pfx path) and
/// `WINDOWS_SIGN_PASSWORD`, with `signtool` from PATH or `SIGNTOOL`. Absent: skipped.
pub struct Signer {
    signtool: PathBuf,
    cert: PathBuf,
    password: Option<String>,
    timestamp: String,
}

impl Signer {
    pub fn from_env() -> Option<Signer> {
        let cert = std::env::var_os("WINDOWS_SIGN_CERT")
            .map(PathBuf::from)
            .filter(|p| p.is_file())?;
        let Some(signtool) = ctx::find_tool("SIGNTOOL", &["signtool"]) else {
            eprintln!(
                "xtask: warning: WINDOWS_SIGN_CERT is set but signtool was not found; not signing"
            );
            return None;
        };
        Some(Signer {
            signtool,
            cert,
            password: std::env::var("WINDOWS_SIGN_PASSWORD").ok(),
            timestamp: std::env::var("WINDOWS_SIGN_TIMESTAMP_URL")
                .unwrap_or_else(|_| "http://timestamp.digicert.com".into()),
        })
    }

    pub fn sign(&self, file: &Path) -> Result<()> {
        let mut cmd = Command::new(&self.signtool);
        cmd.args(["sign", "/fd", "sha256", "/td", "sha256", "/tr"])
            .arg(&self.timestamp)
            .arg("/f")
            .arg(&self.cert);
        if let Some(pw) = &self.password {
            cmd.arg("/p").arg(pw);
        }
        cmd.arg(file);
        // The password is on the command line: log a redacted form only.
        eprintln!("xtask: signing {}", file.display());
        let status = cmd.status().context("could not start signtool")?;
        if !status.success() {
            bail!("signtool failed ({status}) for {}", file.display());
        }
        Ok(())
    }

    /// Burn bundles: detach the engine, sign it, reattach, then sign the bundle.
    pub fn sign_bundle(&self, wix: &Path, bundle: &Path, work: &Path) -> Result<()> {
        let engine = work.join("burn-engine.exe");
        ctx::run(
            Command::new(wix)
                .args(["burn", "detach"])
                .arg(bundle)
                .arg("-engine")
                .arg(&engine),
        )?;
        self.sign(&engine)?;
        ctx::run(
            Command::new(wix)
                .args(["burn", "reattach"])
                .arg(bundle)
                .arg("-engine")
                .arg(&engine)
                .arg("-o")
                .arg(bundle),
        )?;
        self.sign(bundle)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bal_extension_uses_the_cached_dll_path() {
        let home = tempfile::tempdir().unwrap();
        assert_eq!(bal_extension(None), "WixToolset.Bal.wixext");
        assert_eq!(bal_extension(Some(home.path())), "WixToolset.Bal.wixext");
        let root = home.path().join(".wix/extensions/WixToolset.Bal.wixext");
        for v in ["5.0.1", "5.0.2"] {
            let dir = root.join(v).join("wixext5");
            std::fs::create_dir_all(&dir).unwrap();
            std::fs::write(
                dir.join("WixToolset.BootstrapperApplications.wixext.dll"),
                b"",
            )
            .unwrap();
        }
        let got = PathBuf::from(bal_extension(Some(home.path())));
        assert!(got.ends_with("5.0.2/wixext5/WixToolset.BootstrapperApplications.wixext.dll"));
    }

    #[test]
    fn rtf_escapes() {
        let rtf = text_to_rtf("a{b}\\c\n\u{00e9}\u{1F600}");
        assert!(rtf.starts_with("{\\rtf1"));
        assert!(rtf.contains("a\\{b\\}\\\\c\\par"));
        assert!(rtf.contains("\\u233?"));
        // A surrogate pair is written as two signed 16-bit units.
        assert!(rtf.contains("\\u-10179?\\u-8704?"));
        assert!(rtf.ends_with('}'));
    }

    #[test]
    fn portable_zip_layout() {
        let dir = tempfile::tempdir().unwrap();
        let exe = dir.path().join("App (Rust).exe");
        std::fs::write(&exe, b"MZ").unwrap();
        let zip_path = dir.path().join("out.zip");
        write_portable_zip(&zip_path, "App (Rust)", &[&exe]).unwrap();
        let mut archive = zip::ZipArchive::new(std::fs::File::open(&zip_path).unwrap()).unwrap();
        assert_eq!(archive.len(), 1);
        assert_eq!(
            archive.by_index(0).unwrap().name(),
            "App (Rust)/App (Rust).exe"
        );
    }
}
