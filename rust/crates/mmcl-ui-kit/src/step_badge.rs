// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `StepBadge`: a 22x22 circle showing one launch step's state, with a glyph drawn at 14 px.

use gpui::{App, IntoElement, ParentElement, RenderOnce, Styled, Window, div};

use crate::icons::Icon;
use crate::scale::u;
use crate::theme::{ThemeExt, hsla};

/// Badge diameter (px).
pub const SIZE: f32 = 22.0;
/// Glyph size (px): the 24 px artwork scaled 14/24.
pub const GLYPH: f32 = 14.0;

/// A launch step's state, as the badge shows it (maps 1:1 from the progress tracker's states).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum StepState {
    /// Not started: an outlined ring, no glyph.
    Pending,
    /// In progress: active-indicator disc with a dot.
    Running,
    /// Finished: primary disc with a check.
    Done,
    /// Failed: error disc with a cross.
    Failed,
    /// Skipped: surface disc with a dash.
    Skipped,
}

impl StepState {
    /// Every state.
    pub const ALL: [StepState; 5] = [
        StepState::Pending,
        StepState::Running,
        StepState::Done,
        StepState::Failed,
        StepState::Skipped,
    ];
}

/// The glyph a state shows (`None` for Pending).
pub const fn glyph_for(state: StepState) -> Option<Icon> {
    match state {
        StepState::Pending => None,
        StepState::Running => Some(Icon::Dot),
        StepState::Done => Some(Icon::Check),
        StepState::Failed => Some(Icon::Close),
        StepState::Skipped => Some(Icon::Remove),
    }
}

/// The step badge element.
#[derive(IntoElement)]
pub struct StepBadge {
    state: StepState,
}

impl StepBadge {
    /// A badge for `state`.
    pub fn new(state: StepState) -> Self {
        StepBadge { state }
    }
}

impl RenderOnce for StepBadge {
    fn render(self, _window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.tokens();
        let base = div()
            .flex_none()
            .size(u(SIZE))
            .rounded_full()
            .flex()
            .items_center()
            .justify_center();
        let (bg, fg) = match self.state {
            StepState::Pending => {
                return base.border(u(1.5)).border_color(hsla(t.md_outline));
            }
            StepState::Running => (t.md_active_indicator, t.md_on_active_indicator),
            StepState::Done => (t.md_primary, t.md_on_primary),
            StepState::Failed => (t.md_error, t.md_on_error),
            StepState::Skipped => (t.md_surface_container_highest, t.md_on_surface_variant),
        };
        let mut el = base.bg(hsla(bg));
        if let Some(icon) = glyph_for(self.state) {
            el = el.child(icon.svg(GLYPH).text_color(hsla(fg)));
        }
        el
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Port of `ProgressVisualsTest.everyStateButPendingHasAGlyph`.
    #[test]
    fn every_state_but_pending_has_a_glyph() {
        assert!(glyph_for(StepState::Pending).is_none());
        for s in StepState::ALL {
            if s != StepState::Pending {
                assert!(glyph_for(s).is_some(), "{s:?}");
            }
        }
    }
}
