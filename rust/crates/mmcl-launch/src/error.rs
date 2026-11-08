// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! [`LaunchError`]: every way preparing or starting a game can fail.
//!
//! Each error renders through a localization key ([`LaunchError::key`] plus
//! [`LaunchError::args`]), so a front end can show it in any locale. Errors of the crates this
//! one builds on are wrapped and keep their own keys.

use std::io;

use mmcl_i18n::FmtArg;
use mmcl_modpack::ModpackError;
use mmcl_mojang::fabric_profile::FabricProfileError;
use mmcl_mojang::{CoordError, ManifestError};
use mmcl_net::DownloadError;
use mmcl_runtime::RuntimeError;

/// Builds a [`LaunchError::Localized`] from a key literal and its arguments.
#[macro_export]
macro_rules! lerr {
    ($key:literal $(,)?) => {
        $crate::LaunchError::Localized { key: $key, args: Vec::new() }
    };
    ($key:literal, $($arg:expr),+ $(,)?) => {
        $crate::LaunchError::Localized { key: $key, args: vec![$($arg.to_string()),+] }
    };
}

/// Why a launch (or a verify run) failed.
#[derive(Debug, thiserror::Error)]
pub enum LaunchError {
    /// The user (or a sibling branch failure) cancelled the run.
    #[error("{}", render("launch.error.cancelled", &[]))]
    Cancelled,
    /// The user cancelled while the parallel preparation branches ran.
    #[error("{}", render("launch.error.preLaunchCancelled", &[]))]
    PreLaunchCancelled,
    /// A message with a localization key and its `{0}`-style arguments.
    #[error("{}", render(key, args))]
    Localized {
        key: &'static str,
        args: Vec<String>,
    },
    /// A step failed; `key` is the step's wrapper (`launch.error.minecraftFilesFailed`, ...)
    /// and `message` the cause, already rendered.
    #[error("{}", render(key, std::slice::from_ref(message)))]
    Step {
        key: &'static str,
        message: String,
        #[source]
        source: Option<Box<LaunchError>>,
    },
    /// A modpack-layer failure (managed files, sync, scan, loader factory).
    #[error(transparent)]
    Modpack(#[from] ModpackError),
    /// A Mojang/Forge/Fabric manifest could not be used.
    #[error("{}", render(.0.l10n_key(), &.0.l10n_args()))]
    Manifest(#[from] ManifestError),
    /// A Fabric profile could not be used.
    #[error("{}", render(.0.l10n_key(), &.0.l10n_args()))]
    Fabric(#[from] FabricProfileError),
    /// A Maven coordinate was malformed or tried to escape the libraries folder.
    #[error("{}", render(.0.l10n_key(), &.0.l10n_args()))]
    Coord(#[from] CoordError),
    /// The Java runtime could not be verified or installed.
    #[error(transparent)]
    Runtime(#[from] RuntimeError),
    /// A download failed.
    #[error(transparent)]
    Download(#[from] DownloadError),
    /// A natives or installer extraction failed.
    #[error(transparent)]
    Extract(#[from] mmcl_archive::ExtractError),
    /// A local file operation failed.
    #[error(transparent)]
    Io(#[from] io::Error),
}

fn render(key: &str, args: &[String]) -> String {
    let args: Vec<FmtArg> = args.iter().map(FmtArg::from).collect();
    mmcl_i18n::format(key, &args)
}

impl LaunchError {
    /// The localization key of the message a user sees (`modpack.error.generic` and friends
    /// for wrapped errors whose message is already rendered).
    pub fn key(&self) -> &'static str {
        match self {
            LaunchError::Cancelled => "launch.error.cancelled",
            LaunchError::PreLaunchCancelled => "launch.error.preLaunchCancelled",
            LaunchError::Localized { key, .. } | LaunchError::Step { key, .. } => key,
            LaunchError::Modpack(e) => e.key(),
            LaunchError::Manifest(e) => e.l10n_key(),
            LaunchError::Fabric(e) => e.l10n_key(),
            LaunchError::Coord(e) => e.l10n_key(),
            LaunchError::Runtime(e) => e.l10n_key(),
            LaunchError::Download(e) => e.message_key(),
            LaunchError::Extract(_) | LaunchError::Io(_) => "modpack.error.generic",
        }
    }

    /// The MessageFormat arguments for [`LaunchError::key`].
    pub fn args(&self) -> Vec<String> {
        match self {
            LaunchError::Cancelled | LaunchError::PreLaunchCancelled => Vec::new(),
            LaunchError::Localized { args, .. } => args.clone(),
            LaunchError::Step { message, .. } => vec![message.clone()],
            LaunchError::Modpack(e) => e.args(),
            LaunchError::Manifest(e) => e.l10n_args(),
            LaunchError::Fabric(e) => e.l10n_args(),
            LaunchError::Coord(e) => e.l10n_args(),
            LaunchError::Runtime(e) => e.l10n_args(),
            other => vec![other.to_string()],
        }
    }

    /// `true` when the run stopped because it was cancelled.
    pub fn is_cancelled(&self) -> bool {
        match self {
            LaunchError::Cancelled | LaunchError::PreLaunchCancelled => true,
            LaunchError::Runtime(e) => e.is_cancelled(),
            LaunchError::Download(e) => e.is_cancelled(),
            LaunchError::Modpack(ModpackError::ModsInterrupted) => true,
            LaunchError::Step {
                source: Some(s), ..
            } => s.is_cancelled(),
            _ => false,
        }
    }

    /// `true` for errors that already carry their own user-facing wording, which a step
    /// wrapper passes through unchanged (Java re-threw a `ModpackException` as is).
    pub(crate) fn is_self_describing(&self) -> bool {
        !matches!(self, LaunchError::Io(_) | LaunchError::Extract(_))
    }

    /// Wraps a step failure: self-describing errors pass through, anything else is wrapped
    /// with the step's key.
    pub(crate) fn wrap_step(self, key: &'static str) -> LaunchError {
        if self.is_self_describing() {
            return self;
        }
        LaunchError::Step {
            key,
            message: self.to_string(),
            source: Some(Box::new(self)),
        }
    }
}

/// The modpack layer's view of a launch error (the loader factory must return
/// [`ModpackError`]). Modpack errors come back unchanged; anything else keeps its rendered text.
impl From<LaunchError> for ModpackError {
    fn from(e: LaunchError) -> Self {
        match e {
            LaunchError::Modpack(m) => m,
            LaunchError::Io(io) => ModpackError::Io(io),
            other => ModpackError::Manifest(other.to_string()),
        }
    }
}

/// The crate's result type.
pub type Result<T, E = LaunchError> = std::result::Result<T, E>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn localized_errors_render_their_key() {
        let e = lerr!("minecraft.error.versionNotFound", "9.9");
        assert_eq!(e.key(), "minecraft.error.versionNotFound");
        assert_eq!(e.args(), vec!["9.9".to_string()]);
        assert!(e.to_string().contains("9.9"));
    }

    #[test]
    fn io_errors_are_wrapped_by_steps_but_localized_ones_are_not() {
        let io = LaunchError::Io(io::Error::other("disk"));
        let w = io.wrap_step("launch.error.minecraftFilesFailed");
        assert_eq!(w.key(), "launch.error.minecraftFilesFailed");
        assert!(w.to_string().contains("disk"));
        let l = lerr!("forge.error.libsTimedOut").wrap_step("launch.error.loaderLibsFailed");
        assert_eq!(l.key(), "forge.error.libsTimedOut");
    }

    #[test]
    fn cancellation_is_detected_through_wrappers() {
        assert!(LaunchError::Cancelled.is_cancelled());
        assert!(LaunchError::Runtime(RuntimeError::Cancelled).is_cancelled());
        assert!(!lerr!("launch.error.stepFailed").is_cancelled());
    }

    #[test]
    fn converts_to_modpack_error() {
        let m: ModpackError = LaunchError::Modpack(ModpackError::ModsTimedOut).into();
        assert!(matches!(m, ModpackError::ModsTimedOut));
        let m: ModpackError = lerr!("forge.error.libsTimedOut").into();
        assert!(matches!(m, ModpackError::Manifest(_)));
    }
}
