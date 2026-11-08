// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Crash-report analysis for the Mica Minecraft Launcher.
//!
//! [`analyze`] runs an ordered table of regex rules (most specific first) over a crash report
//! or game log and returns a [`CrashDiagnosis`]: a category, a severity, a title and summary,
//! and [`Suggestion`]s. The crate is pure:
//!
//! - text is returned as localization keys ([`Text`]), rendered by the front end through a
//!   [`TextResolver`] over its localizer;
//! - suggestions carry a [`CrashAction`] (data), which the front end carries out;
//! - machine facts (max RAM, system RAM) come in through [`Env`].
//!
//! The only filesystem access is listing `<pack root>/mods` to offer "Disable <jar>"
//! suggestions; [`actions`] has the small helpers for running the resulting actions.

pub mod actions;
mod analyze;
mod detectors;
mod diagnosis;
mod text;

pub use analyze::{
    Env, PackInfo, analyze, analyze_with, extract_crash_search_phrase, search_suggestion,
    search_url,
};
pub use detectors::{DetectorId, detector_order};
pub use diagnosis::{Category, CrashAction, CrashDiagnosis, PackFolder, Severity, Suggestion};
pub use text::{ResolvedArg, Text, TextArg, TextResolver};

#[cfg(test)]
mod tests;
