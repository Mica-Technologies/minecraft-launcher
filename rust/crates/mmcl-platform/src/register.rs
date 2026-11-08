// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Scheme and file-type registration (`SchemeRegistrar`).
//!
//! Runs on every startup off the UI thread and is idempotent. Skipped in dev builds (never
//! register an IDE or `target/debug` binary) and when the executable path is unknown.
//!
//! **What gets registered follows the [`AppIdentity`]:** the Rust build, while it lives beside
//! the Java launcher, registers **neither** `mmcl://` nor `.mmcjson` (Java keeps those, or each
//! launcher would steal the other's links). For it this module only writes the Linux `.desktop`
//! entry that carries the recent-modpack actions. The full registration (HKCU classes, MIME
//! package) is kept for the Java identity, so the switch-over is the one identity constant.
//!
//! - Windows (HKCU only, no admin): the `mmcl` protocol key and the `.mmcjson` ProgID with its
//!   Explorer icon, then `SHChangeNotify(SHCNE_ASSOCCHANGED)` so Explorer notices.
//! - Linux: `~/.local/share/applications/<desktop id>` (with `MimeType=` only for what this
//!   identity registers), the hicolor icons, the MIME package, then the desktop and MIME
//!   databases are updated.
//! - macOS: nothing at runtime; `CFBundleURLTypes` and the document types live in `Info.plist`
//!   (registering at runtime would race the Launch Services cache).

use std::path::{Path, PathBuf};

use mmcl_core::AppIdentity;
#[allow(unused_imports)]
use mmcl_core::identity::{MODPACK_EXTENSION, MODPACK_MIME, URL_SCHEME, WIN_URL_DESCRIPTION};

use crate::host::RecentPack;

/// The launcher executable: `$APPIMAGE` when running from an AppImage, else the current
/// executable unless it is a cargo build output.
pub fn launcher_exe_path() -> Option<PathBuf> {
    if let Some(a) = std::env::var_os("APPIMAGE").filter(|a| !a.is_empty()) {
        return Some(PathBuf::from(a));
    }
    mmcl_core::install::launcher_executable()
}

/// Registers on a worker thread. Never blocks the caller.
pub fn register_if_needed() {
    mmcl_util::tasks::spawn(|| crate::guard(register_now));
}

/// Re-runs the registration so the Linux `.desktop` actions list the current recent packs.
/// On Windows and macOS the recent packs live elsewhere (jump list, dock menu), so it is a
/// no-op there.
pub fn refresh_recent_packs() {
    #[cfg(target_os = "linux")]
    register_now();
}

/// Registers on the calling thread.
pub fn register_now() {
    let identity = AppIdentity::current();
    if identity.is_dev() {
        tracing::debug!("{}", mmcl_i18n::t!("log.schemeRegistrar.devModeSkip"));
        return;
    }
    let Some(exe) = launcher_exe_path() else {
        tracing::debug!("{}", mmcl_i18n::t!("log.schemeRegistrar.exePathUnknown"));
        return;
    };
    if !identity.registers_url_scheme && !identity.registers_modpack_file_type {
        tracing::debug!("{}", mmcl_i18n::t!("log.schemeRegistrar.identitySkip"));
    }
    let result = register_for(identity, &exe);
    if let Err(e) = result {
        tracing::warn!(
            "{}",
            mmcl_i18n::tf!("log.schemeRegistrar.registrationFailed", e.to_string())
        );
    }
}

#[cfg(windows)]
fn register_for(identity: &AppIdentity, exe: &Path) -> crate::error::Result<()> {
    if !identity.registers_url_scheme && !identity.registers_modpack_file_type {
        return Ok(());
    }
    let config = mmcl_core::DataPaths::client().config_dir();
    let ico = config.join("icons").join("mmcjson-file.ico");
    if identity.registers_modpack_file_type
        && let Err(e) = stage_file(&ico, crate::MMCJSON_ICON_ICO)
    {
        tracing::warn!(
            "{}",
            mmcl_i18n::tf!("log.schemeRegistrar.icoStageFailed", e.to_string())
        );
    }
    let entries = windows_registry_entries(
        &exe.to_string_lossy(),
        &ico.to_string_lossy(),
        identity
            .win_modpack_progid
            .unwrap_or("MicaMinecraftLauncher.Modpack"),
        identity.registers_url_scheme,
        identity.registers_modpack_file_type,
    );
    crate::win::registry::write_entries(&entries)?;
    crate::win::notify_association_changed();
    tracing::debug!(
        "{}",
        mmcl_i18n::tf!(
            "log.schemeRegistrar.windowsRegistered",
            exe.display().to_string()
        )
    );
    Ok(())
}

#[cfg(target_os = "linux")]
fn register_for(identity: &AppIdentity, exe: &Path) -> crate::error::Result<()> {
    linux_register(identity, exe, &crate::recents::recent_packs())
}

#[cfg(not(any(windows, target_os = "linux")))]
fn register_for(_identity: &AppIdentity, _exe: &Path) -> crate::error::Result<()> {
    Ok(())
}

#[cfg_attr(not(windows), allow(dead_code))]
fn stage_file(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(path, bytes)
}

// ---- Windows registry layout (pure) ----

/// One registry value under `HKEY_CURRENT_USER`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RegEntry {
    /// Key path relative to `HKCU`.
    pub key: String,
    /// Value name; empty for the key's default value.
    pub name: String,
    /// String value.
    pub value: String,
}

fn reg(key: &str, name: &str, value: impl Into<String>) -> RegEntry {
    RegEntry {
        key: key.to_string(),
        name: name.to_string(),
        value: value.into(),
    }
}

/// The HKCU values that register `mmcl://` (`scheme`) and `.mmcjson` (`file_type`) for `exe`.
pub fn windows_registry_entries(
    exe: &str,
    ico: &str,
    progid: &str,
    scheme: bool,
    file_type: bool,
) -> Vec<RegEntry> {
    let command = format!("\"{exe}\" \"%1\"");
    let mut out = Vec::new();
    if scheme {
        let base = format!(r"Software\Classes\{URL_SCHEME}");
        out.push(reg(&base, "", WIN_URL_DESCRIPTION));
        out.push(reg(&base, "URL Protocol", ""));
        out.push(reg(
            &format!(r"{base}\shell\open\command"),
            "",
            command.clone(),
        ));
    }
    if file_type {
        let prog = format!(r"Software\Classes\{progid}");
        out.push(reg(
            &format!(r"Software\Classes\.{MODPACK_EXTENSION}"),
            "",
            progid,
        ));
        out.push(reg(&prog, "", "Mica Minecraft Modpack"));
        out.push(reg(&format!(r"{prog}\shell\open\command"), "", command));
        out.push(reg(
            &format!(r"{prog}\DefaultIcon"),
            "",
            format!("\"{ico}\",0"),
        ));
    }
    out
}

// ---- Linux desktop entry (pure parts) ----

/// Removes CR, LF and every other control character (`.desktop` values are one line).
pub fn strip_controls(s: &str) -> String {
    s.chars().filter(|c| *c >= ' ' && *c != '\u{7f}').collect()
}

/// The launcher's `.desktop` entry. `MimeType=` lists only what this identity registers;
/// `Actions=` lists the recent packs (blank names skipped; ids `play-pack-N`).
pub fn desktop_entry(
    app_name: &str,
    icon_name: &str,
    exe: &str,
    recent: &[RecentPack],
    scheme: bool,
    file_type: bool,
) -> String {
    let exe_q = mmcl_util::quote::desktop_exec_quote(Some(exe));
    let mut s = String::new();
    s.push_str("[Desktop Entry]\n");
    s.push_str("Type=Application\n");
    s.push_str(&format!("Name={}\n", strip_controls(app_name)));
    s.push_str(&format!("Exec={exe_q} %u\n"));
    s.push_str("Terminal=false\n");
    s.push_str(&format!("Icon={}\n", strip_controls(icon_name)));
    let mut mime = String::new();
    if scheme {
        mime.push_str(&format!("x-scheme-handler/{URL_SCHEME};"));
    }
    if file_type {
        mime.push_str(&format!("{MODPACK_MIME};"));
    }
    if !mime.is_empty() {
        s.push_str(&format!("MimeType={mime}\n"));
    }
    s.push_str("Categories=Game;\n");
    s.push_str("Keywords=minecraft;modpack;forge;launcher;mica;\n");
    s.push_str("NoDisplay=false\n");
    let packs: Vec<&RecentPack> = recent
        .iter()
        .filter(|p| !p.pack_name.trim().is_empty())
        .collect();
    if !packs.is_empty() {
        let ids: Vec<String> = (0..packs.len())
            .map(|i| format!("play-pack-{i};"))
            .collect();
        s.push_str(&format!("Actions={}\n", ids.concat()));
        for (i, p) in packs.iter().enumerate() {
            s.push_str(&format!("\n[Desktop Action play-pack-{i}]\n"));
            s.push_str(&format!(
                "Name={}\n",
                strip_controls(&p.safe_display_name())
            ));
            s.push_str(&format!(
                "Exec={exe_q} \"{}\"\n",
                mmcl_util::deeplink::play_link(&p.pack_name)
            ));
        }
    }
    s
}

/// The shared-mime-info package declaring `application/x-mmcjson`.
pub fn mime_package_xml() -> String {
    format!(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n\
         <mime-info xmlns=\"http://www.freedesktop.org/standards/shared-mime-info\">\n  \
         <mime-type type=\"{MODPACK_MIME}\">\n    \
         <comment>Mica Minecraft Modpack</comment>\n    \
         <glob pattern=\"*.{MODPACK_EXTENSION}\"/>\n  \
         </mime-type>\n\
         </mime-info>\n"
    )
}

/// Icon sizes staged into the user's hicolor theme.
pub const LINUX_ICON_SIZES: [u32; 5] = [256, 128, 64, 48, 32];

/// Writes the `.desktop` entry, stages icons and the MIME package (once each), then updates
/// the desktop and MIME databases. `data_home` is `~/.local/share`.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
pub(crate) fn linux_register_into(
    data_home: &Path,
    identity: &AppIdentity,
    exe: &Path,
    recent: &[RecentPack],
    run_tools: bool,
) -> crate::error::Result<PathBuf> {
    let icon_name = identity.linux_package_name;
    let apps = data_home.join("applications");
    std::fs::create_dir_all(&apps)?;

    // Icons, once (guarded by the largest one existing).
    let icons = data_home.join("icons").join("hicolor");
    let guard = icons
        .join("256x256")
        .join("apps")
        .join(format!("{icon_name}.png"));
    if !guard.exists() {
        match stage_icons(&icons, icon_name) {
            Ok(()) => tracing::debug!("{}", mmcl_i18n::t!("log.schemeRegistrar.iconInstalled")),
            Err(e) => tracing::warn!(
                "{}",
                mmcl_i18n::tf!("log.schemeRegistrar.iconInstallFailed", e.to_string())
            ),
        }
    }

    // MIME package, once, only when this identity owns the file type.
    let mut mime_changed = false;
    if identity.registers_modpack_file_type {
        let pkg_dir = data_home.join("mime").join("packages");
        let pkg = pkg_dir.join(format!("{icon_name}.xml"));
        if !pkg.exists() {
            let r = std::fs::create_dir_all(&pkg_dir)
                .and_then(|_| std::fs::write(&pkg, mime_package_xml()));
            match r {
                Ok(()) => {
                    mime_changed = true;
                    tracing::debug!(
                        "{}",
                        mmcl_i18n::tf!(
                            "log.schemeRegistrar.mimeRegistered",
                            pkg.display().to_string()
                        )
                    );
                }
                Err(e) => tracing::warn!(
                    "{}",
                    mmcl_i18n::tf!("log.schemeRegistrar.mimeRegisterFailed", e.to_string())
                ),
            }
        }
    }

    let desktop = apps.join(identity.linux_desktop_id);
    let content = desktop_entry(
        identity.display_name,
        icon_name,
        &exe.to_string_lossy(),
        recent,
        identity.registers_url_scheme,
        identity.registers_modpack_file_type,
    );
    std::fs::write(&desktop, content)?;

    if run_tools {
        if mime_changed {
            run_quietly(
                "update-mime-database",
                &[data_home.join("mime").as_os_str()],
            );
        }
        run_quietly("update-desktop-database", &[apps.as_os_str()]);
    }
    let key = if identity.registers_url_scheme || identity.registers_modpack_file_type {
        "log.schemeRegistrar.linuxRegistered"
    } else {
        "log.schemeRegistrar.linuxDesktopEntryWritten"
    };
    tracing::debug!("{}", mmcl_i18n::tf!(key, desktop.display().to_string()));
    Ok(desktop)
}

#[cfg(target_os = "linux")]
fn linux_register(
    identity: &AppIdentity,
    exe: &Path,
    recent: &[RecentPack],
) -> crate::error::Result<()> {
    let Some(data_home) = dirs::data_dir() else {
        return Err(crate::error::PlatformError::Other(
            "no XDG data directory".into(),
        ));
    };
    linux_register_into(&data_home, identity, exe, recent, true).map(|_| ())
}

#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
fn stage_icons(hicolor: &Path, icon_name: &str) -> crate::error::Result<()> {
    let img = crate::icon::decode(crate::LAUNCHER_ICON_PNG)?;
    for size in LINUX_ICON_SIZES {
        let dir = hicolor.join(format!("{size}x{size}")).join("apps");
        std::fs::create_dir_all(&dir)?;
        let png = crate::icon::png_bytes(&crate::icon::scaled(&img, size))?;
        std::fs::write(dir.join(format!("{icon_name}.png")), png)?;
    }
    Ok(())
}

/// Spawns `cmd`, discards its output, waits at most 2 s and ignores every error.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
pub(crate) fn run_quietly(cmd: &str, args: &[&std::ffi::OsStr]) {
    use std::process::{Command, Stdio};
    let Ok(mut child) = Command::new(cmd)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    else {
        return;
    };
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
    loop {
        match child.try_wait() {
            Ok(Some(_)) | Err(_) => return,
            Ok(None) if std::time::Instant::now() >= deadline => {
                let _ = child.kill();
                let _ = child.wait();
                return;
            }
            Ok(None) => std::thread::sleep(std::time::Duration::from_millis(25)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_core::identity::{JAVA_RELEASE, RUST_RELEASE};

    #[test]
    fn the_rust_identity_registers_nothing_on_windows() {
        assert!(windows_registry_entries("C:\\a.exe", "C:\\i.ico", "P", false, false).is_empty());
        const {
            assert!(!RUST_RELEASE.registers_url_scheme && !RUST_RELEASE.registers_modpack_file_type)
        };
    }

    #[test]
    fn windows_registry_layout_matches_the_java_launcher() {
        let e = windows_registry_entries(
            r"C:\Program Files\Mica\Mica.exe",
            r"C:\Users\u\.MicaMinecraftLauncher\config\icons\mmcjson-file.ico",
            "MicaMinecraftLauncher.Modpack",
            true,
            true,
        );
        let cmd = "\"C:\\Program Files\\Mica\\Mica.exe\" \"%1\"";
        assert_eq!(
            e,
            vec![
                reg(
                    r"Software\Classes\mmcl",
                    "",
                    "URL:Mica Minecraft Launcher Protocol"
                ),
                reg(r"Software\Classes\mmcl", "URL Protocol", ""),
                reg(r"Software\Classes\mmcl\shell\open\command", "", cmd),
                reg(
                    r"Software\Classes\.mmcjson",
                    "",
                    "MicaMinecraftLauncher.Modpack"
                ),
                reg(
                    r"Software\Classes\MicaMinecraftLauncher.Modpack",
                    "",
                    "Mica Minecraft Modpack"
                ),
                reg(
                    r"Software\Classes\MicaMinecraftLauncher.Modpack\shell\open\command",
                    "",
                    cmd
                ),
                reg(
                    r"Software\Classes\MicaMinecraftLauncher.Modpack\DefaultIcon",
                    "",
                    "\"C:\\Users\\u\\.MicaMinecraftLauncher\\config\\icons\\mmcjson-file.ico\",0"
                ),
            ]
        );
    }

    #[test]
    fn desktop_entry_golden_for_the_java_identity() {
        let recent = vec![
            RecentPack::new("Alto Pack", "Alto\r\nPack", 5),
            RecentPack::new("  ", "blank", 4),
            RecentPack::new("x/y", "X", 3),
        ];
        let got = desktop_entry(
            "Mica Minecraft Launcher",
            "mica-minecraft-launcher",
            "/opt/mica/bin/Mica $1",
            &recent,
            true,
            true,
        );
        let want = "[Desktop Entry]\n\
Type=Application\n\
Name=Mica Minecraft Launcher\n\
Exec=\"/opt/mica/bin/Mica \\$1\" %u\n\
Terminal=false\n\
Icon=mica-minecraft-launcher\n\
MimeType=x-scheme-handler/mmcl;application/x-mmcjson;\n\
Categories=Game;\n\
Keywords=minecraft;modpack;forge;launcher;mica;\n\
NoDisplay=false\n\
Actions=play-pack-0;play-pack-1;\n\
\n\
[Desktop Action play-pack-0]\n\
Name=AltoPack\n\
Exec=\"/opt/mica/bin/Mica \\$1\" \"mmcl://play?name=Alto%20Pack\"\n\
\n\
[Desktop Action play-pack-1]\n\
Name=X\n\
Exec=\"/opt/mica/bin/Mica \\$1\" \"mmcl://play?name=x%2Fy\"\n";
        assert_eq!(got, want);
    }

    #[test]
    fn desktop_entry_for_the_rust_identity_has_no_mime_types_or_actions_without_recents() {
        let got = desktop_entry(
            "Mica Minecraft Launcher (Rust)",
            "mica-minecraft-launcher-rs",
            "/usr/bin/mmcl",
            &[],
            false,
            false,
        );
        assert!(!got.contains("MimeType="));
        assert!(!got.contains("Actions="));
        assert!(got.contains("Name=Mica Minecraft Launcher (Rust)\n"));
    }

    #[test]
    fn mime_package_declares_the_glob() {
        let xml = mime_package_xml();
        assert!(xml.contains("<mime-type type=\"application/x-mmcjson\">"));
        assert!(xml.contains("<glob pattern=\"*.mmcjson\"/>"));
    }

    #[test]
    fn linux_registration_writes_the_entry_icons_and_mime_only_for_owned_types() {
        let dir = tempfile::tempdir().unwrap();
        let exe = Path::new("/usr/bin/mmcl");
        let recent = [RecentPack::new("p", "P", 1)];
        let desktop = linux_register_into(dir.path(), &RUST_RELEASE, exe, &recent, false).unwrap();
        assert_eq!(
            desktop.file_name().unwrap(),
            "mica-minecraft-launcher-rs.desktop"
        );
        let content = std::fs::read_to_string(&desktop).unwrap();
        assert!(content.contains("Actions=play-pack-0;"));
        assert!(!content.contains("MimeType="));
        assert!(
            dir.path()
                .join("icons/hicolor/32x32/apps/mica-minecraft-launcher-rs.png")
                .is_file()
        );
        assert!(
            !dir.path().join("mime").exists(),
            "the Rust identity does not own .mmcjson"
        );

        let desktop = linux_register_into(dir.path(), &JAVA_RELEASE, exe, &[], false).unwrap();
        assert_eq!(
            desktop.file_name().unwrap(),
            "mica-minecraft-launcher.desktop"
        );
        assert!(
            dir.path()
                .join("mime/packages/mica-minecraft-launcher.xml")
                .is_file()
        );
    }

    #[test]
    fn run_quietly_ignores_missing_tools() {
        run_quietly("mmcl-definitely-not-a-command", &[]);
    }
}
