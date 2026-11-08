// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Microsoft sign-in window of the `--msa-signin` helper process.
//!
//! GPUI has no web view, and the official client ID `00000000402b5328` only redirects to
//! `https://login.live.com/oauth20_desktop.srf`, which a system browser cannot hand back. So the
//! launcher re-executes itself as `<exe> --msa-signin --url=<authorize url> ...`; `mmcl-app`
//! dispatches that mode to [`run_helper`], which:
//!
//! 1. applies `--locale` (window title and log messages),
//! 2. checks the arguments ([`HelperConfig::from_args`]; only `https` addresses are opened),
//! 3. opens a `tao` window with a `wry` web view on a fresh, non-persistent profile, loads the
//!    authorize URL with the desktop-browser User-Agent,
//! 4. watches every main-frame navigation (and page load, as a backstop) for the redirect
//!    prefix, blocks that navigation, prints the URL as the only stdout line and exits
//!    [`EXIT_REDIRECT`] (0),
//! 5. exits [`EXIT_CANCELLED`] (2) when the user closes the window.
//!
//! The other exit codes are in [`output`] and decode with [`HelperExit::from_code`]. The parent
//! (`mmcl-auth`'s web-view sign-in flow) parses the printed URL with `parse_callback`; this
//! crate never interprets the code and never logs the URL.
//!
//! A separate process keeps tao and GPUI from fighting over the main-thread event loop (both
//! want it on macOS) and keeps WebKitGTK out of the main process on Linux. On other targets the
//! crate builds without a web engine and [`run_helper`] fails with [`EXIT_FAILED`].
//!
//! Linux packages must depend on `libwebkit2gtk-4.1` (runtime) and builds need its `-dev`
//! package.

pub mod capture;
pub mod config;
pub mod layout;
pub mod output;

#[cfg(any(windows, target_os = "macos", target_os = "linux"))]
mod window;

pub use capture::{NavDecision, RedirectWatcher, is_redirect};
pub use config::{
    ARG_WATCH_STDIN, ConfigError, DEFAULT_REDIRECT_PREFIX, DEFAULT_USER_AGENT, HelperConfig,
};
pub use output::{
    EXIT_BAD_ARGS, EXIT_CANCELLED, EXIT_FAILED, EXIT_PARENT_GONE, EXIT_REDIRECT, HelperExit,
    emit_redirect,
};

use mmcl_core::MsaSigninArgs;

/// `true` when this build has a web engine (Windows, macOS, Linux).
pub const fn is_supported() -> bool {
    cfg!(any(windows, target_os = "macos", target_os = "linux"))
}

/// Runs the sign-in helper and returns the process exit code. Call it from the main thread
/// (tao requires that on macOS) and pass the result to `std::process::exit`.
pub fn run_helper(args: &MsaSigninArgs) -> i32 {
    if let Some(tag) = args
        .locale
        .as_deref()
        .map(str::trim)
        .filter(|t| !t.is_empty())
    {
        mmcl_i18n::init(mmcl_i18n::locale::resolve(tag));
    }
    let cfg = match HelperConfig::from_args(args) {
        Ok(cfg) => cfg,
        Err(e) => {
            tracing::error!("{}", e.localized());
            return EXIT_BAD_ARGS;
        }
    };
    run_window(&cfg)
}

/// [`run_helper`] for the raw arguments that follow `--msa-signin`.
pub fn run_helper_argv(rest: &[String]) -> i32 {
    run_helper(&MsaSigninArgs::parse(rest))
}

#[cfg(any(windows, target_os = "macos", target_os = "linux"))]
fn run_window(cfg: &HelperConfig) -> i32 {
    window::run(cfg)
}

#[cfg(not(any(windows, target_os = "macos", target_os = "linux")))]
fn run_window(_cfg: &HelperConfig) -> i32 {
    tracing::error!("{}", mmcl_i18n::t!("signInHelper.log.unsupported"));
    EXIT_FAILED
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bad_arguments_exit_before_any_window_opens() {
        assert_eq!(run_helper(&MsaSigninArgs::default()), EXIT_BAD_ARGS);
        assert_eq!(
            run_helper_argv(&["--url=http://login.live.com/".to_string()]),
            EXIT_BAD_ARGS
        );
    }

    #[test]
    fn desktop_targets_have_a_web_engine() {
        assert_eq!(
            is_supported(),
            cfg!(any(windows, target_os = "macos", target_os = "linux"))
        );
    }
}
