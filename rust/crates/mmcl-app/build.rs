// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.
//
// Windows only: embeds the launcher icon (resource id 1, so "<exe>",0 works as a DefaultIcon),
// the modpack document icon (id 2, "<exe>",1), and a VERSIONINFO block.
//
// The application manifest is GPUI's (`windows-manifest` feature: PerMonitorV2, Common
// Controls v6, asInvoker, Win10 supportedOS, SegmentHeap). That feature cannot be turned off
// from this workspace: gpui-pre-platform 0.3.11 enables it unconditionally on Windows
// (`[target.'cfg(windows)'.dependencies.gpui] features = ["windows-manifest"]`), and
// gpui-component, gpui-base and gpui-kit-assets depend on gpui-pre with default features. A
// second RT_MANIFEST id 1 here fails the link (CVT1100 duplicate resource). Our full manifest,
// which adds longPathAware and the UTF-8 activeCodePage, is `windows/app.manifest`;
// `cargo xtask dist windows` writes it over GPUI's after linking and before signing (xtask's
// `win_manifest` module: Win32 UpdateResourceW, then reads the exe back to check it), and
// `cargo xtask win-manifest <exe>` does the same for any build. A dev build without it still
// works: std uses `\\?\` paths for long paths and the launcher calls only wide Win32 APIs.
//
// Version: env MMCL_WIN_VERSION (set by `cargo xtask dist windows`), else derived from
// MMCL_VERSION (year shortened to two digits so the major part fits in 255), else 0.0.0.0.
// Product name: MMCL_ENVIRONMENT=DEV selects the dev display name.

use std::path::{Path, PathBuf};

const RELEASE_NAME: &str = "Mica Minecraft Launcher (Rust)";
const DEV_NAME: &str = "Mica Minecraft Launcher (Rust) DEV";
const VENDOR: &str = "Mica Technologies";
const DESCRIPTION: &str = "Mica Minecraft Launcher";

fn main() {
    for var in [
        "MMCL_WIN_VERSION",
        "MMCL_VERSION",
        "MMCL_ENVIRONMENT",
        "MMCL_ENV",
    ] {
        println!("cargo:rerun-if-env-changed={var}");
    }
    println!("cargo:rerun-if-changed=build.rs");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
        return;
    }
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap_or_default());
    // crates/mmcl-app -> rust -> repo root
    let deploy = manifest_dir.join("../../../deploy/windows");
    let app_icon = deploy.join("Mica Minecraft Launcher.ico");
    let doc_icon = deploy.join("Mica Minecraft Modpack.ico");
    for icon in [&app_icon, &doc_icon] {
        println!("cargo:rerun-if-changed={}", icon.display());
    }

    let win_version = non_empty("MMCL_WIN_VERSION")
        .or_else(|| non_empty("MMCL_VERSION").map(|v| win_version(&v)))
        .unwrap_or_else(|| "0.0.0".to_string());
    let parts = four_parts(&win_version);
    let dotted = parts.map(|p| p.to_string()).join(".");
    let commas = parts.map(|p| p.to_string()).join(",");
    let dev = non_empty("MMCL_ENVIRONMENT")
        .or_else(|| non_empty("MMCL_ENV"))
        .is_some_and(|v| v.eq_ignore_ascii_case("DEV"));
    let product = if dev { DEV_NAME } else { RELEASE_NAME };
    let year = std::env::var("SOURCE_DATE_EPOCH")
        .ok()
        .and_then(|s| s.parse::<i64>().ok())
        .map(year_of)
        .unwrap_or(2026);

    let rc = format!(
        "#pragma code_page(65001)\n\
         1 ICON \"{app_icon}\"\n\
         2 ICON \"{doc_icon}\"\n\
         1 VERSIONINFO\n\
         FILEVERSION {commas}\n\
         PRODUCTVERSION {commas}\n\
         FILEFLAGSMASK 0x3fL\n\
         FILEFLAGS 0x0L\n\
         FILEOS 0x40004L\n\
         FILETYPE 0x1L\n\
         FILESUBTYPE 0x0L\n\
         BEGIN\n\
         \x20 BLOCK \"StringFileInfo\"\n\
         \x20 BEGIN\n\
         \x20   BLOCK \"040904b0\"\n\
         \x20   BEGIN\n\
         \x20     VALUE \"CompanyName\", \"{VENDOR}\"\n\
         \x20     VALUE \"FileDescription\", \"{DESCRIPTION}\"\n\
         \x20     VALUE \"FileVersion\", \"{dotted}\"\n\
         \x20     VALUE \"InternalName\", \"mica-minecraft-launcher\"\n\
         \x20     VALUE \"LegalCopyright\", \"Copyright (c) {year} {VENDOR}. GNU GPL v3.\"\n\
         \x20     VALUE \"OriginalFilename\", \"{product}.exe\"\n\
         \x20     VALUE \"ProductName\", \"{product}\"\n\
         \x20     VALUE \"ProductVersion\", \"{dotted}\"\n\
         \x20   END\n\
         \x20 END\n\
         \x20 BLOCK \"VarFileInfo\"\n\
         \x20 BEGIN\n\
         \x20   VALUE \"Translation\", 0x409, 1200\n\
         \x20 END\n\
         END\n",
        app_icon = rc_path(&app_icon),
        doc_icon = rc_path(&doc_icon),
    );
    let out_dir = PathBuf::from(std::env::var("OUT_DIR").expect("OUT_DIR"));
    let rc_file = out_dir.join("mmcl-app.rc");
    std::fs::write(&rc_file, rc).expect("write mmcl-app.rc");
    embed_resource::compile(&rc_file, embed_resource::NONE)
        .manifest_optional()
        .expect("compile the Windows resources");
}

fn non_empty(name: &str) -> Option<String> {
    std::env::var(name)
        .ok()
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

/// An absolute path as an .rc string literal (backslashes doubled).
fn rc_path(p: &Path) -> String {
    let abs = std::fs::canonicalize(p).unwrap_or_else(|_| p.to_path_buf());
    let s = abs.display().to_string();
    let s = s.strip_prefix(r"\\?\").unwrap_or(&s).to_string();
    s.replace('\\', "\\\\").replace('"', "")
}

/// Same rule as `xtask::version::win_version`: numeric part, 4-digit year shortened.
fn win_version(version: &str) -> String {
    let cut = version.split('-').next().unwrap_or("");
    let cut = cut.split('+').next().unwrap_or("");
    let mut parts: Vec<String> = cut.split('.').map(str::to_string).collect();
    if let Some(first) = parts.first_mut()
        && first.len() == 4
        && first.bytes().all(|b| b.is_ascii_digit())
    {
        *first = first[2..].to_string();
    }
    parts.join(".")
}

/// Up to four numeric parts, clamped to 16 bits; missing parts are 0.
fn four_parts(v: &str) -> [u16; 4] {
    let mut out = [0u16; 4];
    for (i, part) in v.split('.').take(4).enumerate() {
        let digits: String = part.chars().take_while(|c| c.is_ascii_digit()).collect();
        out[i] = digits.parse::<u32>().unwrap_or(0).min(u16::MAX as u32) as u16;
    }
    out
}

/// Gregorian year of a Unix timestamp.
fn year_of(secs: i64) -> i64 {
    let days = secs.div_euclid(86_400);
    // Civil-from-days (Howard Hinnant).
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    yoe + era * 400 + i64::from(month <= 2)
}
