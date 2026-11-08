// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The application facade every front end uses (GUI, TUI, MCP, server mode).
//!
//! [`Engine`] owns the tokio runtime, the config, the account manager, the pack manager, the
//! runtime manager, the game sessions and the event bus ([`Engine::subscribe`]). Every launcher
//! operation is a method on it; see `API.md` in this crate for the full list. Front ends never
//! reach into the lower crates for application operations.
//!
//! Every operation is implemented; [`EngineError::NotImplemented`] stays in the error enum for
//! front ends that still match on it.

pub mod engine;
pub mod error;
pub mod event;
pub mod model;
pub mod session;
pub mod settings;
pub mod task;

pub use engine::{
    CrashActionResult, Engine, EngineOptions, LaunchOptions, RuntimeChoice, SignInRequest,
};
pub use error::{EngineError, EngineResult, LaunchRefusal};
pub use event::{
    EngineEvent, Notification, NotificationKind, OperationId, OperationProgress, SignInEvent,
};
pub use model::*;
pub use session::{
    LaunchStepView, LogEvent, LogSnapshot, LogSubscription, SessionId, SessionInfo, SessionPhase,
    StepStateView, format_uptime,
};
pub use settings::{Setting, Settings, SideEffects};
pub use task::{EngineTask, TaskAborted};

/// Re-exports front ends need in signatures.
pub use mmcl_auth::{DeviceCodePrompt, SignInKind};
pub use mmcl_config::{ScanFrequency, WindowBounds};
pub use mmcl_core::GameMode;
pub use mmcl_crash::{CrashAction, PackFolder};
