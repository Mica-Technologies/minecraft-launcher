// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Launch progress for the Mica Minecraft Launcher.
//!
//! Two layers, ported from the Java `game.modpack` package:
//!
//! * [`LaunchProgressTracker`]: the multi-row model the progress screen renders. One row per
//!   [`StepId`], each with a [`StepState`], a progress fraction and a sub-text. Listeners run
//!   after the tracker lock is released.
//! * [`ProgressSink`]: the legacy single-bar sink every sub-call (file sync, loader, runtime,
//!   scanner) reports to. [`SectionProgress`] is the weighted 0-100 model behind the old
//!   progress window, [`TrackerBridge`] forwards a serial pipeline into the tracker, and
//!   [`StepHandle`] binds one parallel branch to one step. [`NullSink`] stands in for "no
//!   progress provider".
//!
//! The crate is pure and synchronous; it depends on no other launcher crate. Byte-level
//! download progress is reached through the small [`DownloadMeter`] trait, which the network
//! crate's `DownloadTracker` implements.

mod bridge;
mod clock;
mod handle;
mod meter;
mod section;
mod sink;
mod step;
mod tracker;

pub use bridge::{ErrorHandler, MeterFactory, TrackerBridge};
pub use clock::{Clock, MonotonicClock};
pub use handle::StepHandle;
pub use meter::DownloadMeter;
pub use section::{PROGRESS_PERCENT_BASE, SUBMIT_THROTTLE, SectionProgress};
pub use sink::{NullSink, ProgressSink};
pub use step::{ParseStepIdError, StepId};
pub use tracker::{
    LaunchProgressTracker, Listener, ListenerId, Step, StepSnapshot, StepState, UnknownStep,
};
