// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The single-instance hand-off (core-config-i18n §5.1 step 7, Java `handleSecondLaunch`).
//!
//! When the lock is held by another launcher, this process forwards its deep link (or
//! `mmcl://open`) to it and exits. A TUI start never forwards: it prints `tui.alreadyRunning`.

use mmcl_core::args::first_launcher_uri;
use mmcl_platform::single_instance::{ForwardResult, OPEN_PAYLOAD};

use crate::startup_guard::{self, DialogLevel};

/// What a second launch does, decided before any I/O.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Plan {
    /// TUI with no deep link: print `tui.alreadyRunning`, exit 0.
    TuiAlreadyRunning,
    /// Forward this payload to the running instance.
    Forward(String),
}

/// Decides what to do: the first deep link in argv wins; a TUI start without one never
/// forwards.
pub fn plan(args: &[String], tui: bool) -> Plan {
    match first_launcher_uri(args) {
        Some(uri) => Plan::Forward(uri.to_string()),
        None if tui => Plan::TuiAlreadyRunning,
        None => Plan::Forward(OPEN_PAYLOAD.to_string()),
    }
}

/// The message key and arguments for a forward that was not delivered. The port is passed as
/// text (no digit grouping).
pub fn failure_message(result: ForwardResult, port: u16, bind_error: &str) -> String {
    match result {
        ForwardResult::DeliveredNoWindow => {
            mmcl_i18n::t!("startup.alreadyRunning.noWindow").into_owned()
        }
        ForwardResult::Rejected => mmcl_i18n::t!("startup.alreadyRunning.unreachable").into_owned(),
        _ => mmcl_i18n::tf!("startup.portUnavailable", port.to_string(), bind_error),
    }
}

/// Runs the hand-off and returns the process exit code.
pub fn run(args: &[String], tui: bool, headless: bool) -> i32 {
    let lock = mmcl_platform::single_instance::SingleInstance::global();
    match plan(args, tui) {
        Plan::TuiAlreadyRunning => {
            startup_guard::record("Second launch in TUI mode; another launcher is running");
            println!("{}", mmcl_i18n::t!("tui.alreadyRunning"));
            0
        }
        Plan::Forward(payload) => {
            let result = lock.forward(Some(&payload));
            startup_guard::record(&format!("Second launch hand-off: {result:?}"));
            if result.delivered() {
                return 0;
            }
            let bind_error = lock.last_acquire_failure().unwrap_or_default();
            let body = failure_message(result, lock.port(), &bind_error);
            let title = mmcl_core::AppIdentity::current().display_name;
            startup_guard::show_native_dialog(DialogLevel::Warning, title, &body, tui || headless);
            1
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn argv(a: &[&str]) -> Vec<String> {
        a.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn first_deep_link_is_forwarded() {
        assert_eq!(
            plan(&argv(&["-c", "mmcl://play?name=a", "MMCL://open"]), false),
            Plan::Forward("mmcl://play?name=a".into())
        );
    }

    #[test]
    fn plain_second_launch_asks_for_the_window() {
        assert_eq!(plan(&argv(&[]), false), Plan::Forward(OPEN_PAYLOAD.into()));
        assert_eq!(
            plan(&argv(&["-c", "Pack"]), false),
            Plan::Forward(OPEN_PAYLOAD.into())
        );
    }

    #[test]
    fn tui_without_a_link_never_forwards() {
        assert_eq!(plan(&argv(&["--cli"]), true), Plan::TuiAlreadyRunning);
        assert_eq!(
            plan(&argv(&["--tui", "mmcl://open"]), true),
            Plan::Forward("mmcl://open".into())
        );
    }

    #[test]
    fn failure_messages_follow_the_result() {
        let no_window = failure_message(ForwardResult::DeliveredNoWindow, 47831, "x");
        assert_eq!(no_window, mmcl_i18n::t!("startup.alreadyRunning.noWindow"));
        let rejected = failure_message(ForwardResult::Rejected, 47831, "x");
        assert_eq!(
            rejected,
            mmcl_i18n::t!("startup.alreadyRunning.unreachable")
        );
        let port = failure_message(ForwardResult::Unreachable, 47831, "in use");
        assert!(port.contains("47831"), "{port}");
        assert!(!port.contains("47,831"), "{port}");
        assert!(port.contains("in use"), "{port}");
    }
}
