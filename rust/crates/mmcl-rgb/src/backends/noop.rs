// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The do-nothing backend (Java `NoOpBackend`): a sentinel the controller filters out.

use crate::backend::{BackendKind, RgbBackend};
use crate::error::RgbResult;
use crate::frame::RgbFrame;

/// Always available, does nothing. Never given a controller slot.
#[derive(Default, Debug, Clone, Copy)]
pub struct NoOpBackend;

impl RgbBackend for NoOpBackend {
    fn name(&self) -> &str {
        "None"
    }
    fn is_available(&mut self) -> bool {
        true
    }
    fn start(&mut self) -> RgbResult<()> {
        Ok(())
    }
    fn render_frame(&mut self, _frame: &RgbFrame) -> RgbResult<()> {
        Ok(())
    }
    fn shutdown(&mut self) {}
    fn kind(&self) -> BackendKind {
        BackendKind::NoOp
    }
}
