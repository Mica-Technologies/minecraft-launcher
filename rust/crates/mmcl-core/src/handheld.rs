// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Gamepad / handheld mode (Rust only).
//!
//! On a Steam Deck or another handheld the launcher favours controller-friendly behaviour: the
//! device-code sign-in (enter a code at microsoft.com/link on a phone) is the default instead of
//! the web view, and the UI can use larger targets and focus navigation. The mode is decided
//! from, highest priority first: the command line (`--gamepad`/`--handheld`/`--no-gamepad`), the
//! saved setting, then auto-detection from the environment Steam sets up.

/// What auto-detection recognised.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub enum HandheldKind {
    /// `SteamDeck=1`: launched by Steam on a Steam Deck.
    SteamDeck,
    /// `SteamOS=1`: running under SteamOS (Deck or another SteamOS handheld/console).
    SteamOs,
    /// `SteamGamepadUI=1`: launched from Steam's gamepad UI (Big Picture / Game Mode).
    SteamGamepadUi,
    /// Running inside the gamescope compositor (`XDG_CURRENT_DESKTOP=gamescope` or
    /// `GAMESCOPE_WAYLAND_DISPLAY` set).
    Gamescope,
}

impl HandheldKind {
    /// A stable lowercase name for logs and diagnostics.
    pub const fn as_str(self) -> &'static str {
        match self {
            HandheldKind::SteamDeck => "steam-deck",
            HandheldKind::SteamOs => "steamos",
            HandheldKind::SteamGamepadUi => "steam-gamepad-ui",
            HandheldKind::Gamescope => "gamescope",
        }
    }
}

/// Where the gamepad decision came from.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub enum GamepadSource {
    /// An explicit command-line switch.
    CommandLine,
    /// The saved setting.
    Config,
    /// Environment auto-detection.
    Detected(HandheldKind),
    /// Nothing said so; the desktop default (off).
    Default,
}

/// The resolved gamepad/handheld mode.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub struct GamepadMode {
    pub enabled: bool,
    pub source: GamepadSource,
}

impl GamepadMode {
    /// Resolves the mode: command line, then config, then detection, else off.
    pub fn resolve(
        flag: Option<bool>,
        config: Option<bool>,
        detected: Option<HandheldKind>,
    ) -> GamepadMode {
        if let Some(enabled) = flag {
            return GamepadMode {
                enabled,
                source: GamepadSource::CommandLine,
            };
        }
        if let Some(enabled) = config {
            return GamepadMode {
                enabled,
                source: GamepadSource::Config,
            };
        }
        match detected {
            Some(kind) => GamepadMode {
                enabled: true,
                source: GamepadSource::Detected(kind),
            },
            None => GamepadMode {
                enabled: false,
                source: GamepadSource::Default,
            },
        }
    }

    /// Whether the device-code sign-in should be the default flow (it is in gamepad mode).
    pub fn prefers_device_code_sign_in(&self) -> bool {
        self.enabled
    }
}

fn truthy(value: Option<String>) -> bool {
    value.is_some_and(|v| {
        let v = v.trim();
        v == "1" || v.eq_ignore_ascii_case("true") || v.eq_ignore_ascii_case("yes")
    })
}

/// Detects a handheld / gamepad environment from environment variables. Pure over `env`.
pub fn detect(env: &dyn Fn(&str) -> Option<String>) -> Option<HandheldKind> {
    if truthy(env("SteamDeck")) {
        return Some(HandheldKind::SteamDeck);
    }
    if truthy(env("SteamOS")) {
        return Some(HandheldKind::SteamOs);
    }
    if truthy(env("SteamGamepadUI")) {
        return Some(HandheldKind::SteamGamepadUi);
    }
    let desktop_is_gamescope = env("XDG_CURRENT_DESKTOP").is_some_and(|d| {
        d.split(':')
            .any(|part| part.trim().eq_ignore_ascii_case("gamescope"))
    });
    if desktop_is_gamescope || env("GAMESCOPE_WAYLAND_DISPLAY").is_some_and(|v| !v.is_empty()) {
        return Some(HandheldKind::Gamescope);
    }
    None
}

/// [`detect`] over this process's environment.
pub fn detect_from_process_env() -> Option<HandheldKind> {
    detect(&|name| std::env::var_os(name).map(|v| v.to_string_lossy().into_owned()))
}

/// The `--gamepad` / `--no-gamepad` switch of this process: 0 unset, 1 on, 2 off.
static CLI_FLAG: std::sync::atomic::AtomicU8 = std::sync::atomic::AtomicU8::new(0);

/// Records the command-line gamepad switch ([`crate::args::gamepad_flag`]) for this process.
/// It wins over the saved setting and is never saved.
pub fn set_cli_flag(flag: Option<bool>) {
    let v = match flag {
        None => 0,
        Some(true) => 1,
        Some(false) => 2,
    };
    CLI_FLAG.store(v, std::sync::atomic::Ordering::Relaxed);
}

/// The command-line gamepad switch recorded with [`set_cli_flag`].
pub fn cli_flag() -> Option<bool> {
    match CLI_FLAG.load(std::sync::atomic::Ordering::Relaxed) {
        1 => Some(true),
        2 => Some(false),
        _ => None,
    }
}

/// The effective mode for this process: the command-line switch, the saved setting, then
/// detection from the process environment.
pub fn effective(config: Option<bool>) -> GamepadMode {
    GamepadMode::resolve(cli_flag(), config, detect_from_process_env())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn env_of(pairs: &'static [(&'static str, &'static str)]) -> impl Fn(&str) -> Option<String> {
        move |name| {
            pairs
                .iter()
                .find(|(k, _)| *k == name)
                .map(|(_, v)| v.to_string())
        }
    }

    #[test]
    fn detects_steam_variables() {
        assert_eq!(
            detect(&env_of(&[("SteamDeck", "1")])),
            Some(HandheldKind::SteamDeck)
        );
        assert_eq!(
            detect(&env_of(&[("SteamOS", "1")])),
            Some(HandheldKind::SteamOs)
        );
        assert_eq!(
            detect(&env_of(&[("SteamGamepadUI", "1")])),
            Some(HandheldKind::SteamGamepadUi)
        );
        assert_eq!(
            detect(&env_of(&[("XDG_CURRENT_DESKTOP", "gamescope")])),
            Some(HandheldKind::Gamescope)
        );
        assert_eq!(
            detect(&env_of(&[("GAMESCOPE_WAYLAND_DISPLAY", "gamescope-0")])),
            Some(HandheldKind::Gamescope)
        );
        assert_eq!(
            detect(&env_of(&[("SteamOS", "1"), ("SteamDeck", "1")])),
            Some(HandheldKind::SteamDeck)
        );
    }

    #[test]
    fn ignores_false_and_unrelated_values() {
        assert_eq!(detect(&env_of(&[])), None);
        assert_eq!(detect(&env_of(&[("SteamDeck", "0")])), None);
        assert_eq!(detect(&env_of(&[("SteamOS", "")])), None);
        assert_eq!(detect(&env_of(&[("XDG_CURRENT_DESKTOP", "KDE")])), None);
        assert_eq!(detect(&env_of(&[("GAMESCOPE_WAYLAND_DISPLAY", "")])), None);
        assert_eq!(
            detect(&env_of(&[("SteamDeck", "true")])),
            Some(HandheldKind::SteamDeck)
        );
    }

    #[test]
    fn resolution_precedence() {
        let deck = Some(HandheldKind::SteamDeck);
        let m = GamepadMode::resolve(Some(false), Some(true), deck);
        assert_eq!((m.enabled, m.source), (false, GamepadSource::CommandLine));
        let m = GamepadMode::resolve(None, Some(false), deck);
        assert_eq!((m.enabled, m.source), (false, GamepadSource::Config));
        let m = GamepadMode::resolve(None, None, deck);
        assert_eq!(
            (m.enabled, m.source),
            (true, GamepadSource::Detected(HandheldKind::SteamDeck))
        );
        assert!(m.prefers_device_code_sign_in());
        let m = GamepadMode::resolve(None, None, None);
        assert_eq!((m.enabled, m.source), (false, GamepadSource::Default));
        assert!(!m.prefers_device_code_sign_in());
        let m = GamepadMode::resolve(Some(true), None, None);
        assert!(m.enabled);
    }
}
