// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The entry point: [`analyze`] runs the rule table over a crash text.

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;

use crate::detectors::{DETECTORS, Detector, DetectorId, EXCEPTION_PHRASE, Input};
use crate::diagnosis::{CrashAction, CrashDiagnosis, Suggestion};
use crate::text::Text;

/// What the analyzer needs to know about the pack that crashed.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct PackInfo<'a> {
    /// The pack's display name (prefixed to the web-search query).
    pub name: Option<&'a str>,
    /// The pack's install root (for the mods folder and disable-mod suggestions).
    pub root: Option<&'a Path>,
    /// The pack's manifest URL (a reinstall needs one).
    pub url: Option<&'a str>,
}

/// Machine facts the out-of-memory rule uses, supplied by the caller so the analyzer stays pure.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Env {
    /// The launcher's configured max RAM in MB (`ConfigManager.getMaxRam()`).
    pub max_ram_mb: u64,
    /// Total system RAM in whole GiB (`bytes / 2^30`); 0 when unknown.
    pub system_ram_gb: u64,
}

/// Analyzes a crash report (or the game log when there is no report).
///
/// Blank or missing text gives [`CrashDiagnosis::unknown`]. Otherwise the detectors run in
/// order and the first hit wins; one that panics is logged (key
/// `log.crashAnalyzer.detectorThrew`) and skipped. With no hit, an exception found in the text
/// becomes a "Search the web" suggestion on the unknown diagnosis.
pub fn analyze(
    text: Option<&str>,
    pack: Option<&PackInfo<'_>>,
    exit_code: i32,
    env: &Env,
) -> CrashDiagnosis {
    analyze_with(text, pack, exit_code, env, &mut |id, message| {
        tracing::warn!(
            target: "mmcl_crash",
            key = "log.crashAnalyzer.detectorThrew",
            detector = ?id,
            error = %message,
            "log.crashAnalyzer.detectorThrew"
        );
    })
}

/// [`analyze`] with a caller-supplied handler for a detector that panicked, so a front end can
/// log `log.crashAnalyzer.detectorThrew` through its own localizer.
pub fn analyze_with(
    text: Option<&str>,
    pack: Option<&PackInfo<'_>>,
    exit_code: i32,
    env: &Env,
    on_detector_error: &mut dyn FnMut(DetectorId, String),
) -> CrashDiagnosis {
    run_table(DETECTORS, text, pack, exit_code, env, on_detector_error)
}

pub(crate) fn run_table(
    table: &[Detector],
    text: Option<&str>,
    pack: Option<&PackInfo<'_>>,
    exit_code: i32,
    env: &Env,
    on_detector_error: &mut dyn FnMut(DetectorId, String),
) -> CrashDiagnosis {
    let Some(text) = text.filter(|t| !t.trim().is_empty()) else {
        return CrashDiagnosis::unknown(exit_code);
    };
    let input = Input { text, pack, env };
    for detector in table {
        match catch_unwind(AssertUnwindSafe(|| (detector.detect)(&input))) {
            Ok(Some(diagnosis)) => return diagnosis,
            Ok(None) => {}
            Err(payload) => on_detector_error(detector.id, panic_message(payload.as_ref())),
        }
    }
    let search =
        extract_crash_search_phrase(text).and_then(|phrase| search_suggestion(pack, &phrase));
    match search {
        Some(s) => CrashDiagnosis::unknown_with_suggestions(exit_code, vec![s]),
        None => CrashDiagnosis::unknown(exit_code),
    }
}

fn panic_message(payload: &(dyn std::any::Any + Send)) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        (*s).to_owned()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        String::from("panic")
    }
}

/// Pulls the most salient exception out of `text` for a web search: the first fully-qualified
/// `...Exception` / `...Error` type, plus `": " + message` when one follows on the same line
/// (trimmed and cut to 120 characters). `None` when there is no such exception.
pub fn extract_crash_search_phrase(text: &str) -> Option<String> {
    if text.trim().is_empty() {
        return None;
    }
    let caps = EXCEPTION_PHRASE.captures(text)?;
    let ty = caps.get(1)?.as_str();
    let message = match caps.get(2).map(|m| m.as_str().trim()) {
        Some(m) if !m.is_empty() => m,
        _ => return Some(ty.to_owned()),
    };
    // Cap the message so the query stays focused (and a long user-specific path cannot bloat
    // the URL).
    let message: String = if message.chars().count() > 120 {
        message
            .chars()
            .take(120)
            .collect::<String>()
            .trim()
            .to_owned()
    } else {
        message.to_owned()
    };
    Some(format!("{ty}: {message}"))
}

/// The Google search URL for a crash phrase, prefixed with the pack name when known. The
/// query is form-encoded like Java's `URLEncoder` (space → `+`).
pub fn search_url(pack: Option<&PackInfo<'_>>, phrase: &str) -> String {
    let query = match pack.and_then(|p| p.name) {
        Some(name) => format!("{name} {phrase}"),
        None => phrase.to_owned(),
    };
    let encoded: String = url::form_urlencoded::byte_serialize(query.as_bytes()).collect();
    format!("https://www.google.com/search?q={encoded}")
}

/// A "Search the web" suggestion for `phrase`; `None` when it is blank.
pub fn search_suggestion(pack: Option<&PackInfo<'_>>, phrase: &str) -> Option<Suggestion> {
    if phrase.trim().is_empty() {
        return None;
    }
    Some(Suggestion::of(
        Text::key("crash.action.searchWeb"),
        CrashAction::OpenUrl(search_url(pack, phrase)),
    ))
}
