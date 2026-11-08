// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `cargo xtask dist macos`: `<AppName>.app`, a DMG (hdiutil) and a PKG (pkgbuild +
//! productbuild), renamed `<AppName>-<numeric>-arm64|intel.{dmg,pkg}`.
//!
//! Signing is ad hoc unless `--sign IDENTITY` (or `MAC_SIGNING_IDENTITY`) names a Developer ID
//! Application identity; then the app is signed with the hardened runtime and
//! `packaging/macos/entitlements.plist`. `--installer-sign` (or `MAC_INSTALLER_IDENTITY`) signs
//! the PKG. `--notarize` submits DMG and PKG with `xcrun notarytool` using `APPLE_ID`,
//! `APPLE_TEAM_ID` and `APPLE_APP_PASSWORD`, then staples.

use std::path::{Path, PathBuf};
use std::process::Command;

use anyhow::{Context, Result, bail};
use chrono::Datelike;

use crate::ctx::{self, Args, Ctx, xml_escape};

/// Oldest macOS GPUI supports (Zed's floor).
pub const MIN_MACOS: &str = "10.15.7";

pub fn default_target() -> &'static str {
    if cfg!(target_arch = "x86_64") {
        "x86_64-apple-darwin"
    } else {
        "aarch64-apple-darwin"
    }
}

/// `arm64` / `intel` file-name suffix, and the productbuild host architecture.
pub fn arch_names(target: &str) -> (&'static str, &'static str) {
    if target.starts_with("x86_64") {
        ("intel", "x86_64")
    } else {
        ("arm64", "arm64")
    }
}

/// Everything that varies in `Info.plist`.
pub struct PlistInput<'a> {
    pub executable: &'a str,
    pub bundle_id: &'a str,
    pub name: &'a str,
    pub short_version: &'a str,
    pub bundle_version: &'a str,
    pub copyright: &'a str,
    pub register_url_scheme: bool,
    pub register_modpack: bool,
}

fn key_string(out: &mut String, key: &str, value: &str) {
    out.push_str(&format!(
        "    <key>{}</key>\n    <string>{}</string>\n",
        xml_escape(key),
        xml_escape(value)
    ));
}

fn key_bool(out: &mut String, key: &str, value: bool) {
    out.push_str(&format!(
        "    <key>{}</key>\n    <{}/>\n",
        xml_escape(key),
        if value { "true" } else { "false" }
    ));
}

/// Renders `Contents/Info.plist`. URL scheme and document types appear only when the
/// identity registers them (the side-by-side Rust build does not).
pub fn render_info_plist(i: &PlistInput) -> String {
    let mut s = String::from(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n<plist version=\"1.0\">\n<dict>\n",
    );
    key_string(&mut s, "CFBundleExecutable", i.executable);
    key_string(&mut s, "CFBundleIdentifier", i.bundle_id);
    key_string(&mut s, "CFBundleName", i.name);
    key_string(&mut s, "CFBundleDisplayName", i.name);
    key_string(&mut s, "CFBundleShortVersionString", i.short_version);
    key_string(&mut s, "CFBundleVersion", i.bundle_version);
    key_string(&mut s, "CFBundleIconFile", "AppIcon");
    key_string(&mut s, "CFBundlePackageType", "APPL");
    key_string(&mut s, "CFBundleSignature", "????");
    key_string(&mut s, "CFBundleInfoDictionaryVersion", "6.0");
    key_string(&mut s, "CFBundleDevelopmentRegion", "en");
    key_bool(&mut s, "CFBundleAllowMixedLocalizations", true);
    key_string(
        &mut s,
        "LSApplicationCategoryType",
        "public.app-category.games",
    );
    key_string(&mut s, "LSMinimumSystemVersion", MIN_MACOS);
    key_bool(&mut s, "NSHighResolutionCapable", true);
    key_bool(&mut s, "NSSupportsAutomaticGraphicsSwitching", true);
    key_string(&mut s, "NSHumanReadableCopyright", i.copyright);
    key_string(
        &mut s,
        "NSMicrophoneUsageDescription",
        "Minecraft voice-chat mods started by the launcher use the microphone.",
    );
    if i.register_url_scheme {
        s.push_str(
            "    <key>CFBundleURLTypes</key>\n    <array>\n      <dict>\n        <key>CFBundleURLName</key>\n        <string>Mica Minecraft Launcher Protocol</string>\n        <key>CFBundleURLSchemes</key>\n        <array>\n          <string>mmcl</string>\n        </array>\n        <key>CFBundleTypeRole</key>\n        <string>Viewer</string>\n      </dict>\n    </array>\n",
        );
    }
    if i.register_modpack {
        let uti = xml_escape(&format!("{}.mmcjson", i.bundle_id));
        s.push_str(&format!(
            "    <key>CFBundleDocumentTypes</key>\n    <array>\n      <dict>\n        <key>CFBundleTypeName</key>\n        <string>Mica Minecraft Modpack</string>\n        <key>CFBundleTypeRole</key>\n        <string>Viewer</string>\n        <key>LSHandlerRank</key>\n        <string>Owner</string>\n        <key>CFBundleTypeIconFile</key>\n        <string>Modpack</string>\n        <key>LSItemContentTypes</key>\n        <array>\n          <string>{uti}</string>\n        </array>\n      </dict>\n    </array>\n    <key>UTExportedTypeDeclarations</key>\n    <array>\n      <dict>\n        <key>UTTypeIdentifier</key>\n        <string>{uti}</string>\n        <key>UTTypeDescription</key>\n        <string>Mica Minecraft Modpack</string>\n        <key>UTTypeConformsTo</key>\n        <array>\n          <string>public.json</string>\n          <string>public.data</string>\n        </array>\n        <key>UTTypeIconFile</key>\n        <string>Modpack</string>\n        <key>UTTypeTagSpecification</key>\n        <dict>\n          <key>public.filename-extension</key>\n          <array>\n            <string>mmcjson</string>\n          </array>\n          <key>public.mime-type</key>\n          <string>application/x-mmcjson</string>\n        </dict>\n      </dict>\n    </array>\n"
        ));
    }
    s.push_str("</dict>\n</plist>\n");
    s
}

/// productbuild's distribution file: shows the license, installs one component.
pub fn render_distribution(title: &str, bundle_id: &str, version: &str, host_arch: &str) -> String {
    let t = xml_escape(title);
    let id = xml_escape(bundle_id);
    let v = xml_escape(version);
    format!(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<installer-gui-script minSpecVersion=\"2\">\n  <title>{t}</title>\n  <license file=\"LICENSE.txt\"/>\n  <options customize=\"never\" require-scripts=\"false\" hostArchitectures=\"{host_arch}\"/>\n  <domains enable_localSystem=\"true\"/>\n  <choices-outline>\n    <line choice=\"default\">\n      <line choice=\"{id}\"/>\n    </line>\n  </choices-outline>\n  <choice id=\"default\"/>\n  <choice id=\"{id}\" visible=\"false\">\n    <pkg-ref id=\"{id}\"/>\n  </choice>\n  <pkg-ref id=\"{id}\" version=\"{v}\" onConclusion=\"none\">component.pkg</pkg-ref>\n</installer-gui-script>\n"
    )
}

/// pkgbuild component list: never relocate the app to a moved copy, upgrade in place.
pub fn render_component_plist(app_dir_name: &str) -> String {
    format!(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n<plist version=\"1.0\">\n<array>\n  <dict>\n    <key>BundleHasStrictIdentifier</key>\n    <true/>\n    <key>BundleIsRelocatable</key>\n    <false/>\n    <key>BundleIsVersionChecked</key>\n    <true/>\n    <key>BundleOverwriteAction</key>\n    <string>upgrade</string>\n    <key>RootRelativeBundlePath</key>\n    <string>{}</string>\n  </dict>\n</array>\n</plist>\n",
        xml_escape(app_dir_name)
    )
}

pub fn dist(ctx: &Ctx, args: &Args) -> Result<()> {
    if !cfg!(target_os = "macos") {
        bail!("dist macos needs a macOS host (hdiutil, pkgbuild, codesign)");
    }
    let id = &ctx.identity;
    let name = id.display_name;
    let built = ctx.app_binary()?;
    let stage = ctx.fresh_stage("macos")?;

    // 1. The .app bundle.
    let app_root = stage.join("app");
    let app = app_root.join(format!("{name}.app"));
    let contents = app.join("Contents");
    ctx::copy_file(&built, &contents.join("MacOS").join(name))?;
    let plist = render_info_plist(&PlistInput {
        executable: name,
        bundle_id: id.mac_bundle_id,
        name,
        short_version: &ctx.versions.mac_short_version,
        bundle_version: &ctx.versions.mac_bundle_version,
        copyright: &format!(
            "Copyright \u{00a9} {} {}. Licensed under the GNU GPL v3.",
            chrono::Utc::now().year(),
            ctx::MANUFACTURER
        ),
        register_url_scheme: id.registers_url_scheme,
        register_modpack: id.registers_modpack_file_type,
    });
    std::fs::write(contents.join("Info.plist"), plist)?;
    std::fs::write(contents.join("PkgInfo"), "APPL????")?;
    let res = contents.join("Resources");
    ctx::copy_file(
        &ctx.deploy("mac/Mica Minecraft Launcher.icns"),
        &res.join("AppIcon.icns"),
    )?;
    ctx::copy_file(
        &ctx.deploy("mac/Mica Minecraft Modpack.icns"),
        &res.join("Modpack.icns"),
    )?;
    ctx::copy_file(&ctx.license(), &res.join("LICENSE"))?;
    if let Some(plutil) = ctx::find_tool("PLUTIL", &["plutil"]) {
        ctx::run(
            Command::new(plutil)
                .arg("-lint")
                .arg(contents.join("Info.plist")),
        )?;
    }

    // 2. Sign.
    let identity = args
        .get("sign")
        .map(str::to_string)
        .or_else(|| std::env::var("MAC_SIGNING_IDENTITY").ok())
        .filter(|s| !s.trim().is_empty());
    sign_app(ctx, &app, identity.as_deref())?;

    let (suffix, host_arch) = arch_names(&ctx.target);
    let numeric = &ctx.versions.numeric;
    let pkg_dir = ctx.packaging_out();
    std::fs::create_dir_all(&pkg_dir)?;
    std::fs::create_dir_all(&ctx.out)?;

    // 3. DMG: the app plus an /Applications link.
    let dmg_src = stage.join("dmg");
    std::fs::create_dir_all(&dmg_src)?;
    ctx::run(
        Command::new("ditto")
            .arg(&app)
            .arg(dmg_src.join(format!("{name}.app"))),
    )?;
    ctx::run(
        Command::new("ln")
            .args(["-s", "/Applications"])
            .arg(dmg_src.join("Applications")),
    )?;
    let dmg = pkg_dir.join(format!("{name}-{numeric}.dmg"));
    ctx::run(
        Command::new("hdiutil")
            .args(["create", "-volname", name, "-srcfolder"])
            .arg(&dmg_src)
            .args(["-ov", "-format", "UDZO"])
            .arg(&dmg),
    )?;
    if let Some(identity) = &identity {
        ctx::run(
            Command::new("codesign")
                .args(["--force", "--timestamp", "--sign", identity])
                .arg(&dmg),
        )?;
    }

    // 4. PKG: component package + distribution with the license page.
    let pkg_work = stage.join("pkg");
    let resources = pkg_work.join("resources");
    std::fs::create_dir_all(&resources)?;
    ctx::copy_file(&ctx.license(), &resources.join("LICENSE.txt"))?;
    let component_plist = pkg_work.join("component.plist");
    std::fs::write(
        &component_plist,
        render_component_plist(&format!("{name}.app")),
    )?;
    let component = pkg_work.join("component.pkg");
    ctx::run(
        Command::new("pkgbuild")
            .arg("--root")
            .arg(&app_root)
            .arg("--component-plist")
            .arg(&component_plist)
            .args([
                "--install-location",
                "/Applications",
                "--identifier",
                id.mac_bundle_id,
            ])
            .args(["--version", &ctx.versions.mac_bundle_version])
            .arg(&component),
    )?;
    let distribution = pkg_work.join("distribution.xml");
    std::fs::write(
        &distribution,
        render_distribution(
            name,
            id.mac_bundle_id,
            &ctx.versions.mac_bundle_version,
            host_arch,
        ),
    )?;
    let pkg = pkg_dir.join(format!("{name}-{numeric}.pkg"));
    let mut pb = Command::new("productbuild");
    pb.arg("--distribution")
        .arg(&distribution)
        .arg("--resources")
        .arg(&resources)
        .arg("--package-path")
        .arg(&pkg_work);
    let installer_identity = args
        .get("installer-sign")
        .map(str::to_string)
        .or_else(|| std::env::var("MAC_INSTALLER_IDENTITY").ok())
        .filter(|s| !s.trim().is_empty());
    if let Some(i) = &installer_identity {
        pb.args(["--sign", i]);
    }
    pb.arg(&pkg);
    ctx::run(&mut pb)?;

    // 5. Notarize when asked and credentials exist.
    if args.flag("notarize") {
        notarize(&dmg)?;
        notarize(&pkg)?;
    }

    for (file, ext) in [(&dmg, "dmg"), (&pkg, "pkg")] {
        ctx::copy_file(
            file,
            &ctx.out.join(format!("{name}-{numeric}-{suffix}.{ext}")),
        )?;
    }
    eprintln!("xtask: macOS packages are in {}", ctx.out.display());
    Ok(())
}

fn sign_app(ctx: &Ctx, app: &Path, identity: Option<&str>) -> Result<()> {
    let mut cmd = Command::new("codesign");
    match identity {
        Some(identity) => {
            cmd.args(["--force", "--deep", "--options", "runtime", "--timestamp"])
                .arg("--entitlements")
                .arg(ctx.packaging("macos/entitlements.plist"))
                .args(["--sign", identity]);
        }
        // arm64 binaries must carry at least an ad-hoc signature.
        None => {
            cmd.args(["--force", "--deep", "--sign", "-"]);
        }
    }
    ctx::run(cmd.arg(app))?;
    ctx::run(
        Command::new("codesign")
            .args(["--verify", "--deep", "--strict"])
            .arg(app),
    )
}

fn notarize(file: &PathBuf) -> Result<()> {
    let get = |k: &str| std::env::var(k).ok().filter(|v| !v.trim().is_empty());
    let (Some(apple_id), Some(team), Some(password)) = (
        get("APPLE_ID"),
        get("APPLE_TEAM_ID"),
        get("APPLE_APP_PASSWORD"),
    ) else {
        eprintln!(
            "xtask: --notarize: APPLE_ID / APPLE_TEAM_ID / APPLE_APP_PASSWORD not set; skipped"
        );
        return Ok(());
    };
    eprintln!("xtask: notarizing {}", file.display());
    let status = Command::new("xcrun")
        .args(["notarytool", "submit"])
        .arg(file)
        .args([
            "--apple-id",
            &apple_id,
            "--team-id",
            &team,
            "--password",
            &password,
            "--wait",
        ])
        .status()
        .context("could not start xcrun notarytool")?;
    if !status.success() {
        bail!("notarytool failed ({status}) for {}", file.display());
    }
    ctx::run(Command::new("xcrun").args(["stapler", "staple"]).arg(file))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input(register: bool) -> String {
        render_info_plist(&PlistInput {
            executable: "Mica Minecraft Launcher (Rust)",
            bundle_id: "com.micatechnologies.minecraft.launcher.rs",
            name: "Mica Minecraft Launcher (Rust)",
            short_version: "2026.5.12",
            bundle_version: "2026.5.12.1",
            copyright: "Copyright \u{00a9} 2026 Mica Technologies & co",
            register_url_scheme: register,
            register_modpack: register,
        })
    }

    #[test]
    fn plist_parses_and_has_versions() {
        let v: plist::Value = plist::from_bytes(input(false).as_bytes()).unwrap();
        let d = v.as_dictionary().unwrap();
        assert_eq!(
            d["CFBundleShortVersionString"].as_string(),
            Some("2026.5.12")
        );
        assert_eq!(d["CFBundleVersion"].as_string(), Some("2026.5.12.1"));
        assert_eq!(
            d["CFBundleIdentifier"].as_string(),
            Some("com.micatechnologies.minecraft.launcher.rs")
        );
        assert_eq!(d["NSHighResolutionCapable"].as_boolean(), Some(true));
        assert!(d["NSMicrophoneUsageDescription"].as_string().is_some());
        // The side-by-side identity registers neither mmcl:// nor .mmcjson.
        assert!(!d.contains_key("CFBundleURLTypes"));
        assert!(!d.contains_key("CFBundleDocumentTypes"));
        assert!(!d.contains_key("UTExportedTypeDeclarations"));
    }

    #[test]
    fn plist_registration_when_enabled() {
        let v: plist::Value = plist::from_bytes(input(true).as_bytes()).unwrap();
        let d = v.as_dictionary().unwrap();
        let url = d["CFBundleURLTypes"].as_array().unwrap()[0]
            .as_dictionary()
            .unwrap();
        let schemes: Vec<_> = url["CFBundleURLSchemes"]
            .as_array()
            .unwrap()
            .iter()
            .map(|s| s.as_string().unwrap())
            .collect();
        assert_eq!(schemes, vec!["mmcl"]);
        let uti = d["UTExportedTypeDeclarations"].as_array().unwrap()[0]
            .as_dictionary()
            .unwrap();
        assert_eq!(
            uti["UTTypeIdentifier"].as_string(),
            Some("com.micatechnologies.minecraft.launcher.rs.mmcjson")
        );
        let tags = uti["UTTypeTagSpecification"].as_dictionary().unwrap();
        assert_eq!(
            tags["public.mime-type"].as_string(),
            Some("application/x-mmcjson")
        );
    }

    #[test]
    fn distribution_and_component_are_xml() {
        let dist = render_distribution("A & B", "com.x", "1.2.3", "arm64");
        assert!(dist.contains("<title>A &amp; B</title>"));
        assert!(dist.contains("hostArchitectures=\"arm64\""));
        let comp = render_component_plist("A (Rust).app");
        let v: plist::Value = plist::from_bytes(comp.as_bytes()).unwrap();
        let d = v.as_array().unwrap()[0].as_dictionary().unwrap();
        assert_eq!(d["BundleIsRelocatable"].as_boolean(), Some(false));
        assert_eq!(
            d["RootRelativeBundlePath"].as_string(),
            Some("A (Rust).app")
        );
    }

    #[test]
    fn arch_suffixes() {
        assert_eq!(arch_names("aarch64-apple-darwin"), ("arm64", "arm64"));
        assert_eq!(arch_names("x86_64-apple-darwin"), ("intel", "x86_64"));
    }
}
