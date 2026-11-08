// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Desktop shortcuts that open the launcher on one modpack (`DesktopShortcutManager`).
//!
//! The shortcut runs `<launcher> -c <pack>`. Pack names come from server JSON, so every
//! format quotes them for its own parser:
//!
//! - Windows `.lnk` through `IShellLinkW` + `IPersistFile` (no PowerShell), arguments quoted
//!   with `windows_cmd_quote` (**Guard:** `pack" --flag "` must stay one argument).
//! - macOS `<name>.app` with a `launch.sh` that single-quotes (**Guard:** a double-quoted bash
//!   string still expands `$` and backticks).
//! - Linux `<name>.desktop` with `Exec=` quoting and CR/LF/NUL stripped (**Guard:** a pack name
//!   must not forge an `Exec=` line), then marked trusted with `gio`.

use std::path::{Path, PathBuf};

use mmcl_util::quote::{
    desktop_exec_quote, sanitize_file_name, shell_single_quote, strip_line_terminators,
};

/// What a shortcut needs from a pack.
pub trait ShortcutPack {
    /// The pack's unique name (passed to `-c`).
    fn pack_name(&self) -> &str;
    /// The cached logo image, if any.
    fn logo_path(&self) -> Option<PathBuf>;
}

/// Why a shortcut could not be created.
#[derive(Debug, thiserror::Error)]
pub enum ShortcutError {
    /// No desktop folder.
    #[error("{}", mmcl_i18n::t!("shortcut.error.noDesktop"))]
    NoDesktop,
    /// The launcher executable is not known (a cargo build output).
    #[error("{}", mmcl_i18n::t!("shortcut.error.exePathUnknown"))]
    ExePathUnknown,
    /// Writing the shortcut failed.
    #[error("{}", mmcl_i18n::tf!("shortcut.error.writeFailed", .0.to_string()))]
    Io(#[from] std::io::Error),
    /// The shell refused the shortcut (Windows COM).
    #[error("{}", mmcl_i18n::tf!("shortcut.error.shellLinkFailed", .0))]
    Shell(String),
}

impl ShortcutError {
    /// The localized message.
    pub fn localized(&self) -> String {
        self.to_string()
    }
}

/// The desktop folder: `xdg-user-dir DESKTOP` on Linux (via `user-dirs.dirs`), else
/// `~/Desktop`.
pub fn desktop_dir() -> Option<PathBuf> {
    let dir = if cfg!(target_os = "linux") {
        dirs::desktop_dir()
    } else {
        dirs::home_dir().map(|h| h.join("Desktop"))
    };
    dir.filter(|d| d.is_dir())
}

/// The launcher to start from a shortcut: `$APPIMAGE`, the current executable, or (inside a
/// macOS bundle) the bundle's `Contents/MacOS/<exe>`, which is the current executable too.
pub fn launcher_path() -> Option<PathBuf> {
    crate::register::launcher_exe_path()
}

/// Creates a desktop shortcut for `pack`. Returns the path written.
pub fn create_shortcut(pack: &dyn ShortcutPack) -> Result<PathBuf, ShortcutError> {
    let desktop = desktop_dir().ok_or(ShortcutError::NoDesktop)?;
    let exe = launcher_path().ok_or(ShortcutError::ExePathUnknown)?;
    let metadata = mmcl_core::paths::current()
        .unwrap_or_else(mmcl_core::DataPaths::client)
        .metadata_dir();
    let path = create_shortcut_in(&desktop, &exe, &metadata, pack)?;
    tracing::info!(
        "{}",
        mmcl_i18n::tf!("log.desktopShortcut.created", pack.pack_name())
    );
    Ok(path)
}

/// [`create_shortcut`] with every location given (the testable core).
pub fn create_shortcut_in(
    desktop: &Path,
    exe: &Path,
    metadata_dir: &Path,
    pack: &dyn ShortcutPack,
) -> Result<PathBuf, ShortcutError> {
    let name = sanitize_file_name(pack.pack_name());
    let name = if name.is_empty() {
        "Modpack".to_string()
    } else {
        name
    };
    let icon = convert_icon(pack.logo_path().as_deref(), metadata_dir, &name);
    write_platform_shortcut(desktop, exe, &name, pack.pack_name(), icon.as_deref())
}

/// Converts the pack logo to the platform's icon format. Failure logs
/// `log.desktopShortcut.iconConversionFailed` and continues without an icon.
fn convert_icon(logo: Option<&Path>, metadata_dir: &Path, name: &str) -> Option<PathBuf> {
    let logo = logo.filter(|p| p.is_file())?;
    type Convert = fn(&Path, &Path) -> crate::error::Result<()>;
    let (ext, convert): (&str, Convert) = if cfg!(windows) {
        ("ico", crate::icon::png_to_ico)
    } else if cfg!(target_os = "macos") {
        ("icns", crate::icon::png_to_icns)
    } else {
        return Some(logo.to_path_buf());
    };
    let dst = metadata_dir.join(format!("icon_{name}.{ext}"));
    match convert(logo, &dst) {
        Ok(()) => Some(dst),
        Err(e) => {
            tracing::warn!(
                "{}",
                mmcl_i18n::tf!("log.desktopShortcut.iconConversionFailed", e.to_string())
            );
            // macOS can still use the PNG.
            cfg!(target_os = "macos").then(|| logo.to_path_buf())
        }
    }
}

#[cfg(windows)]
fn write_platform_shortcut(
    desktop: &Path,
    exe: &Path,
    name: &str,
    pack: &str,
    icon: Option<&Path>,
) -> Result<PathBuf, ShortcutError> {
    let lnk = desktop.join(format!("{name}.lnk"));
    let args = windows_arguments(pack);
    crate::win::shell_link::save(&lnk, exe, &args, icon)
        .map_err(|e| ShortcutError::Shell(e.to_string()))?;
    Ok(lnk)
}

#[cfg(target_os = "macos")]
fn write_platform_shortcut(
    desktop: &Path,
    exe: &Path,
    name: &str,
    pack: &str,
    icon: Option<&Path>,
) -> Result<PathBuf, ShortcutError> {
    write_mac_app(desktop, exe, name, pack, icon)
}

#[cfg(not(any(windows, target_os = "macos")))]
fn write_platform_shortcut(
    desktop: &Path,
    exe: &Path,
    name: &str,
    pack: &str,
    icon: Option<&Path>,
) -> Result<PathBuf, ShortcutError> {
    write_linux_desktop(desktop, exe, name, pack, icon, true)
}

/// The Windows shortcut arguments: `-c "<pack>"`, quoted to survive `CommandLineToArgvW`.
pub fn windows_arguments(pack: &str) -> String {
    format!(
        "{} {}",
        mmcl_core::args::ARG_CLIENT_MODE,
        mmcl_util::quote::windows_cmd_quote(Some(&strip_line_terminators(Some(pack))))
    )
}

/// Escapes `&` and `<` for a plist string.
pub fn plist_escape(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;")
}

/// `Contents/Info.plist` of a macOS shortcut app.
pub fn mac_info_plist(name: &str, version: &str) -> String {
    format!(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n\
<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n\
<plist version=\"1.0\">\n\
<dict>\n\
    <key>CFBundleExecutable</key>\n\
    <string>launch.sh</string>\n\
    <key>CFBundleIconFile</key>\n\
    <string>icon.icns</string>\n\
    <key>CFBundleName</key>\n\
    <string>{}</string>\n\
    <key>CFBundlePackageType</key>\n\
    <string>APPL</string>\n\
    <key>CFBundleVersion</key>\n\
    <string>{}</string>\n\
</dict>\n\
</plist>\n",
        plist_escape(&strip_line_terminators(Some(name))),
        plist_escape(version)
    )
}

/// `Contents/MacOS/launch.sh` of a macOS shortcut app.
pub fn mac_launch_script(exe: &str, pack: &str) -> String {
    format!(
        "#!/bin/bash\nexec {} {} {}\n",
        shell_single_quote(Some(exe)),
        mmcl_core::args::ARG_CLIENT_MODE,
        shell_single_quote(Some(&strip_line_terminators(Some(pack))))
    )
}

/// A Linux `.desktop` shortcut.
pub fn linux_desktop_shortcut(exe: &str, pack: &str, icon: Option<&str>) -> String {
    let pack = strip_line_terminators(Some(pack));
    let mut s = String::from("[Desktop Entry]\nType=Application\n");
    s.push_str(&format!("Name={pack}\n"));
    s.push_str(&format!(
        "Exec={} {} {}\n",
        desktop_exec_quote(Some(&strip_line_terminators(Some(exe)))),
        mmcl_core::args::ARG_CLIENT_MODE,
        desktop_exec_quote(Some(&pack))
    ));
    if let Some(icon) = icon {
        s.push_str(&format!("Icon={}\n", strip_line_terminators(Some(icon))));
    }
    s.push_str("Terminal=false\nCategories=Game;\n");
    s
}

#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
fn write_mac_app(
    desktop: &Path,
    exe: &Path,
    name: &str,
    pack: &str,
    icon: Option<&Path>,
) -> Result<PathBuf, ShortcutError> {
    let app = desktop.join(format!("{name}.app"));
    let contents = app.join("Contents");
    let macos = contents.join("MacOS");
    let resources = contents.join("Resources");
    std::fs::create_dir_all(&macos)?;
    std::fs::create_dir_all(&resources)?;
    std::fs::write(
        contents.join("Info.plist"),
        mac_info_plist(name, mmcl_core::build::VERSION),
    )?;
    if let Some(icon) = icon {
        let _ = std::fs::copy(icon, resources.join("icon.icns"));
    }
    let script = macos.join("launch.sh");
    std::fs::write(&script, mac_launch_script(&exe.to_string_lossy(), pack))?;
    set_executable(&script)?;
    Ok(app)
}

#[cfg_attr(any(windows, target_os = "macos"), allow(dead_code))]
fn write_linux_desktop(
    desktop: &Path,
    exe: &Path,
    name: &str,
    pack: &str,
    icon: Option<&Path>,
    mark_trusted: bool,
) -> Result<PathBuf, ShortcutError> {
    let file = desktop.join(format!("{name}.desktop"));
    let icon = icon.map(|p| p.to_string_lossy().into_owned());
    std::fs::write(
        &file,
        linux_desktop_shortcut(&exe.to_string_lossy(), pack, icon.as_deref()),
    )?;
    set_executable(&file)?;
    if mark_trusted {
        crate::register::run_quietly(
            "gio",
            &[
                std::ffi::OsStr::new("set"),
                file.as_os_str(),
                std::ffi::OsStr::new("metadata::trusted"),
                std::ffi::OsStr::new("true"),
            ],
        );
    }
    Ok(file)
}

#[cfg(unix)]
fn set_executable(path: &Path) -> std::io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o755))
}

#[cfg(not(unix))]
#[allow(dead_code)]
fn set_executable(_path: &Path) -> std::io::Result<()> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Pack(&'static str);
    impl ShortcutPack for Pack {
        fn pack_name(&self) -> &str {
            self.0
        }
        fn logo_path(&self) -> Option<PathBuf> {
            None
        }
    }

    #[test]
    fn windows_arguments_keep_a_hostile_name_as_one_argument() {
        assert_eq!(windows_arguments("Alto"), "-c \"Alto\"");
        assert_eq!(
            windows_arguments("a\" --flag \"b"),
            "-c \"a\\\" --flag \\\"b\""
        );
        assert_eq!(windows_arguments("x\r\ny"), "-c \"xy\"");
    }

    #[test]
    fn mac_script_single_quotes_everything() {
        let s = mac_launch_script(
            "/Applications/Mica.app/Contents/MacOS/mica",
            "\"; rm -rf $HOME; #\"",
        );
        assert_eq!(
            s,
            "#!/bin/bash\nexec '/Applications/Mica.app/Contents/MacOS/mica' -c '\"; rm -rf $HOME; #\"'\n"
        );
        assert!(mac_launch_script("/x", "it's").contains("'it'\\''s'"));
    }

    #[test]
    fn mac_plist_escapes_the_name() {
        let p = mac_info_plist("A & <B>", "1.0");
        assert!(p.contains("<string>A &amp; &lt;B></string>"));
        assert!(p.contains("<string>launch.sh</string>"));
    }

    #[test]
    fn linux_shortcut_cannot_forge_an_exec_line() {
        let s = linux_desktop_shortcut("/usr/bin/mmcl", "x\nExec=evil", Some("/i.png"));
        assert_eq!(
            s,
            "[Desktop Entry]\nType=Application\nName=xExec=evil\nExec=\"/usr/bin/mmcl\" -c \"xExec=evil\"\nIcon=/i.png\nTerminal=false\nCategories=Game;\n"
        );
        assert!(linux_desktop_shortcut("/b", "$HOME`x`", None).contains("-c \"\\$HOME\\`x\\`\""));
    }

    #[test]
    fn file_writers_lay_out_the_shortcuts() {
        let dir = tempfile::tempdir().unwrap();
        let app = write_mac_app(dir.path(), Path::new("/bin/mmcl"), "Alto", "Alto", None).unwrap();
        assert!(app.join("Contents/Info.plist").is_file());
        assert!(app.join("Contents/MacOS/launch.sh").is_file());
        let d = write_linux_desktop(
            dir.path(),
            Path::new("/bin/mmcl"),
            "Alto",
            "Alto",
            None,
            false,
        )
        .unwrap();
        assert!(std::fs::read_to_string(d).unwrap().contains("-c \"Alto\""));
    }

    #[cfg(not(windows))]
    #[test]
    fn create_shortcut_in_uses_the_sanitized_name() {
        let dir = tempfile::tempdir().unwrap();
        let p = create_shortcut_in(
            dir.path(),
            Path::new("/bin/mmcl"),
            dir.path(),
            &Pack("a/b:c"),
        )
        .unwrap();
        assert!(
            p.file_name()
                .unwrap()
                .to_string_lossy()
                .starts_with("a_b_c")
        );
    }

    #[cfg(windows)]
    #[test]
    fn create_shortcut_in_writes_a_lnk() {
        let dir = tempfile::tempdir().unwrap();
        let exe = std::env::current_exe().unwrap();
        let p = create_shortcut_in(dir.path(), &exe, dir.path(), &Pack("a/b:c")).unwrap();
        assert_eq!(p.file_name().unwrap(), "a_b_c.lnk");
        assert!(p.is_file());
    }
}
