// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Game sessions of the Mica Minecraft Launcher (Rust port of `game/session/*` and
//! `gui/LogTrimPolicy`).
//!
//! * [`session`]: one [`GameSession`] per launch, with its phase machine
//!   (`Preparing -> Running -> Exited | Crashed`, or `Cancelled | Failed` when no game started).
//!   A game the user stopped ends `Exited`, not `Crashed` ([`GameSession::stop_requested`]).
//! * [`admission`]: the pure launch admission rule (one game per pack, one per account).
//! * [`registry`]: the [`GameSessionRegistry`] that admits launches atomically and keeps the
//!   last [`registry::MAX_ENDED`] ended sessions for display.
//! * [`log`]: [`GameLog`], which drains a game's stdout and stderr for its whole life, redacts
//!   every line, keeps a bounded buffer, writes the session log file and batches lines to
//!   listeners. It numbers every line, so a poller sees new output even once the buffer is full.
//! * [`follower`] and [`latest`]: keep "what are you playing" surfaces (RGB, Discord) on the
//!   most recently started running game, applying only the newest update.
//! * [`process`]: the [`ProcessHandle`] seam and [`ChildProcess`], its implementation for a
//!   `tokio::process::Child`.
//! * [`trim`]: pure log-trimming helpers shared with the console views.

pub mod admission;
pub mod error;
pub mod follower;
pub mod latest;
mod listeners;
pub mod log;
pub mod process;
pub mod registry;
pub mod session;
pub mod trim;

#[cfg(test)]
mod i18n_keys;
#[cfg(test)]
pub(crate) mod test_support;

pub use admission::{Decision, Outcome, check};
pub use error::SessionError;
pub use follower::{RunningGameFollower, Sink};
pub use latest::LatestUpdateRunner;
pub use listeners::ListenerId;
pub use log::{GameLog, LinesSince, LogListener, Subscription, Tail, log_listener};
pub use process::{ChildProcess, ProcessHandle, exit_code_of};
pub use registry::GameSessionRegistry;
pub use session::{Clock, GameSession, Phase, system_clock};
pub use trim::{full_log_drop_offset, tail_start};
