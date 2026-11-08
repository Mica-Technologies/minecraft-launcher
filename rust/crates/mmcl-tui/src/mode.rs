// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `--cli` / `--tui` detection and the process-wide TUI flag (Java `TuiMode`).
//!
//! Java captured the real stdout/stdin before the logger reassigned `System.out`; Rust has no
//! global stream reassignment, so enabling TUI mode instead switches the logger to file-only
//! output and sets a flag that GUI-only code checks before it would open a window.

use std::sync::atomic::{AtomicBool, Ordering};

static ENABLED: AtomicBool = AtomicBool::new(false);

/// `true` when any argument is exactly `--cli` or `--tui` (case-sensitive: `--CLI`, `-cli` and
/// `"--cli "` do not count). Pure; checked at the very top of `main`.
pub fn requested_in(args: &[String]) -> bool {
    mmcl_core::args::tui_requested(args)
}

/// Turns TUI mode on for this process: log lines stop going to the console (they would draw
/// over the UI) and [`is_enabled`] reports `true`.
pub fn enable() {
    ENABLED.store(true, Ordering::SeqCst);
    mmcl_log::set_tui_file_only(true);
}

/// Whether TUI mode is on. Code that would lazily open a GPUI window must check it.
pub fn is_enabled() -> bool {
    ENABLED.load(Ordering::SeqCst)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(a: &[&str]) -> Vec<String> {
        a.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn empty_args_are_not_a_request() {
        assert!(!requested_in(&[]));
        assert!(!requested_in(&args(&[])));
    }

    #[test]
    fn cli_alone_is_a_request() {
        assert!(requested_in(&args(&["--cli"])));
    }

    #[test]
    fn tui_alone_is_a_request() {
        assert!(requested_in(&args(&["--tui"])));
    }

    #[test]
    fn flag_anywhere_among_other_args_counts() {
        assert!(requested_in(&args(&["-s", "MyPack", "--cli"])));
        assert!(requested_in(&args(&["--tui", "-c"])));
    }

    #[test]
    fn unrelated_args_are_not_a_request() {
        assert!(!requested_in(&args(&["-c", "MyPack"])));
        assert!(!requested_in(&args(&["--help"])));
    }

    #[test]
    fn upper_case_is_a_near_miss() {
        assert!(!requested_in(&args(&["--CLI"])));
    }

    #[test]
    fn trailing_space_is_a_near_miss() {
        assert!(!requested_in(&args(&["--cli "])));
    }

    #[test]
    fn single_dash_is_a_near_miss() {
        assert!(!requested_in(&args(&["-cli"])));
    }

    #[test]
    fn enable_sets_the_flag() {
        enable();
        assert!(is_enabled());
    }
}
