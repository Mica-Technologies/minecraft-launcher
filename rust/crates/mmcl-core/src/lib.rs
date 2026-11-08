// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Layer-0 core of the Mica Minecraft Launcher (Rust port).
//!
//! - [`identity`]: [`AppIdentity`], the single switch between the side-by-side Rust identity
//!   and the Java one (names, data folder, ports, installer and OS ids).
//! - [`build`]: version, flavour, [`BuildInfo`].
//! - [`mode`]: [`GameMode`] and the process-wide current mode.
//! - [`args`]: the command-line grammar ([`LauncherArgs`], [`PreSession`], [`CommandLine`]),
//!   including the Rust-only `--msa-signin` helper and gamepad switches.
//! - [`handheld`]: gamepad/handheld detection (Steam Deck, SteamOS, gamescope).
//! - [`paths`]: [`DataPaths`], every folder and file, mirroring Java's `LocalPathManager`.
//! - [`consts`]: shared launcher constants.
//! - [`filelock`], [`fsx`]: per-path locks, owner-only permissions, atomic writes, reset move.
//! - [`install`]: install kind and the executable to relaunch.
//! - [`error`]: the base [`Error`].

pub mod args;
pub mod build;
pub mod consts;
pub mod error;
pub mod filelock;
pub mod fsx;
pub mod handheld;
pub mod identity;
pub mod install;
pub mod mode;
pub mod paths;

pub use args::{CommandLine, LauncherArgs, ModeAction, MsaSigninArgs, PreSession};
pub use build::{BuildInfo, Flavor};
pub use error::{Error, Result};
pub use handheld::{GamepadMode, GamepadSource, HandheldKind};
pub use identity::{ACTIVE_IDENTITY, AppIdentity, IdentityKind};
pub use mode::GameMode;
pub use paths::{DataPaths, LauncherPaths};
