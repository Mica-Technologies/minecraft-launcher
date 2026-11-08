// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! OS dark-mode probe (`OsThemeUtilities`) for code without a GPUI context (TUI mode, early
//! startup). GUI code should prefer `cx.window_appearance()` and
//! `window.observe_window_appearance` for live changes.
//!
//! On a detector error the answer is **dark**, the launcher's "when in doubt" default, except
//! on macOS where a missing `AppleInterfaceStyle` is how the OS says Light.

/// Whether the OS prefers a dark appearance.
pub fn is_os_dark() -> bool {
    crate::guard(probe).unwrap_or(true)
}

#[cfg(windows)]
fn probe() -> Option<bool> {
    let key = windows_registry::CURRENT_USER
        .open(r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize")
        .ok();
    let value = key.and_then(|k| k.get_u32("AppsUseLightTheme").ok());
    Some(dark_from_apps_use_light(value))
}

#[cfg(target_os = "macos")]
fn probe() -> Option<bool> {
    use objc2_foundation::{NSString, NSUserDefaults};
    let defaults = NSUserDefaults::standardUserDefaults();
    let style = defaults.stringForKey(&NSString::from_str("AppleInterfaceStyle"));
    Some(dark_from_apple_style(
        style.map(|s| s.to_string()).as_deref(),
    ))
}

#[cfg(target_os = "linux")]
fn probe() -> Option<bool> {
    if let Some(dark) = crate::linux::portal_color_scheme().and_then(dark_from_portal) {
        return Some(dark);
    }
    if let Ok(theme) = std::env::var("GTK_THEME")
        && !theme.trim().is_empty()
    {
        return Some(dark_from_theme_name(&theme));
    }
    if let Some(scheme) = gsettings("color-scheme") {
        if scheme.contains("prefer-dark") {
            return Some(true);
        }
        if scheme.contains("prefer-light") {
            return Some(false);
        }
    }
    gsettings("gtk-theme").map(|t| dark_from_theme_name(&t))
}

#[cfg(target_os = "linux")]
fn gsettings(key: &str) -> Option<String> {
    let out = std::process::Command::new("gsettings")
        .args(["get", "org.gnome.desktop.interface", key])
        .stdin(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    let s = String::from_utf8_lossy(&out.stdout)
        .trim()
        .trim_matches('\'')
        .to_string();
    (!s.is_empty()).then_some(s)
}

#[cfg(not(any(windows, target_os = "macos", target_os = "linux")))]
fn probe() -> Option<bool> {
    None
}

/// Windows `AppsUseLightTheme`: 0 is dark; a missing value is the "when in doubt" dark.
pub fn dark_from_apps_use_light(value: Option<u32>) -> bool {
    value.is_none_or(|v| v == 0)
}

/// macOS `AppleInterfaceStyle`: `Dark` is dark; missing means Light.
pub fn dark_from_apple_style(style: Option<&str>) -> bool {
    style.is_some_and(|s| s.eq_ignore_ascii_case("dark"))
}

/// xdg-desktop-portal `org.freedesktop.appearance color-scheme`: 1 prefers dark, 2 prefers
/// light, 0 (no preference) is undecided.
pub fn dark_from_portal(value: u32) -> Option<bool> {
    match value {
        1 => Some(true),
        2 => Some(false),
        _ => None,
    }
}

/// A GTK theme name such as `Adwaita-dark` or `Breeze:dark`.
pub fn dark_from_theme_name(name: &str) -> bool {
    name.to_ascii_lowercase().contains("dark")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn windows_value_mapping() {
        assert!(dark_from_apps_use_light(Some(0)));
        assert!(!dark_from_apps_use_light(Some(1)));
        assert!(dark_from_apps_use_light(None));
    }

    #[test]
    fn mac_value_mapping() {
        assert!(dark_from_apple_style(Some("Dark")));
        assert!(!dark_from_apple_style(None));
        assert!(!dark_from_apple_style(Some("Light")));
    }

    #[test]
    fn linux_value_mapping() {
        assert_eq!(dark_from_portal(1), Some(true));
        assert_eq!(dark_from_portal(2), Some(false));
        assert_eq!(dark_from_portal(0), None);
        assert!(dark_from_theme_name("Adwaita-dark"));
        assert!(dark_from_theme_name("Breeze:Dark"));
        assert!(!dark_from_theme_name("Adwaita"));
    }

    #[test]
    fn the_probe_never_panics() {
        let _ = is_os_dark();
    }
}
