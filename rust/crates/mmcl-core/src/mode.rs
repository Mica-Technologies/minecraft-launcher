// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The game mode (`GameModeManager`): client or server.
//!
//! The mode selects the data root ([`crate::paths::DataPaths::for_mode`]). It is unset until the
//! command line has been applied; while it is unset, [`is_client`] and [`is_server`] are both
//! `false` and [`crate::paths::current`] returns `None`, so nothing can silently resolve a path
//! against the wrong root (the Java "early-path trap").

use parking_lot::RwLock;

/// Client (GUI/TUI, home-folder data root) or server (headless, working-directory data root).
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub enum GameMode {
    Client,
    Server,
}

impl GameMode {
    /// `"CLIENT"` or `"SERVER"` (`GameMode.getStringName()`), used in log file names.
    pub const fn as_str(self) -> &'static str {
        match self {
            GameMode::Client => "CLIENT",
            GameMode::Server => "SERVER",
        }
    }
}

impl std::fmt::Display for GameMode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}

static CURRENT: RwLock<Option<GameMode>> = RwLock::new(None);

/// The current mode, or `None` before it has been set.
pub fn current() -> Option<GameMode> {
    *CURRENT.read()
}

/// Sets the mode. May be called again (a restarted session re-applies the same arguments).
pub fn set(mode: GameMode) {
    *CURRENT.write() = Some(mode);
}

/// Clears the mode. Only tests and a full in-process restart should need this.
pub fn clear() {
    *CURRENT.write() = None;
}

/// Infers the mode from the environment (server when headless), sets it and returns it.
pub fn infer() -> GameMode {
    let mode =
        infer_from_env(&|name| std::env::var_os(name).map(|v| v.to_string_lossy().into_owned()));
    set(mode);
    mode
}

/// Pure inference: [`GameMode::Server`] when [`is_headless`] says so, else client.
pub fn infer_from_env(env: &dyn Fn(&str) -> Option<String>) -> GameMode {
    if is_headless(env) {
        GameMode::Server
    } else {
        GameMode::Client
    }
}

/// Headless detection. Linux and the BSDs: neither `DISPLAY` nor `WAYLAND_DISPLAY` is set.
/// Windows and macOS are always treated as graphical (parity with Java's
/// `GraphicsEnvironment.isHeadless()` on those systems).
pub fn is_headless(env: &dyn Fn(&str) -> Option<String>) -> bool {
    if cfg!(any(target_os = "windows", target_os = "macos")) {
        return false;
    }
    let set = |name: &str| env(name).is_some_and(|v| !v.is_empty());
    !set("DISPLAY") && !set("WAYLAND_DISPLAY")
}

/// `true` only when the mode is set to client.
pub fn is_client() -> bool {
    current() == Some(GameMode::Client)
}

/// `true` only when the mode is set to server.
pub fn is_server() -> bool {
    current() == Some(GameMode::Server)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names() {
        assert_eq!(GameMode::Client.as_str(), "CLIENT");
        assert_eq!(GameMode::Server.to_string(), "SERVER");
    }

    #[test]
    fn headless_rules() {
        let none = |_: &str| None;
        let x11 = |n: &str| (n == "DISPLAY").then(|| ":0".to_string());
        let wayland = |n: &str| (n == "WAYLAND_DISPLAY").then(|| "wayland-0".to_string());
        let empty = |_: &str| Some(String::new());
        if cfg!(any(target_os = "windows", target_os = "macos")) {
            assert!(!is_headless(&none));
            assert_eq!(infer_from_env(&none), GameMode::Client);
        } else {
            assert!(is_headless(&none));
            assert!(is_headless(&empty));
            assert!(!is_headless(&x11));
            assert!(!is_headless(&wayland));
            assert_eq!(infer_from_env(&none), GameMode::Server);
            assert_eq!(infer_from_env(&x11), GameMode::Client);
        }
    }
}
